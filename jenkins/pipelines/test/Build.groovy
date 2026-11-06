/*
 * Copyright (c) 2026, NVIDIA CORPORATION. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import groovy.transform.Field
import com.nvidia.bloom.Utils

@Field def pipeline

def initialize(pipeline)
{
    this.pipeline = pipeline
    return this
}

// Cache key formula is mirrored in fat_build_sbatch_body.sh (the only writer) and
// prepare_container.sh; a divergent key misses the cache silently forever.
// Hashed on the Jenkins controller so no $()/\${} has to survive SSH quoting.
def computeFatSqshPath(String fatSqshDir, String llmTarArtifactPath, String fatBuildScriptLocalPath) {
    def scriptHash = pipeline.sh(returnStdout: true, script: "sha256sum ${fatBuildScriptLocalPath} | cut -d' ' -f1 | head -c 8").trim()
    def hash = pipeline.sh(returnStdout: true, script: "printf '%s' '${llmTarArtifactPath}|${pipeline.LLM_DOCKER_IMAGE}|${scriptHash}' | sha256sum | cut -d' ' -f1 | head -c 16").trim()
    return [scriptHash: scriptHash, hash: hash, path: "${fatSqshDir}/fat-${hash}.sqsh"]
}

// Shared by the SLURM agent and sbatch flows.
def generateFatBuildSbatchScript(Map args) {
    return """\
#!/bin/bash
#SBATCH --output=${args.fatBuildLogPath}
#SBATCH ${args.fatBuilderArgs}
export FAT_CONTAINER_DIR="${args.containerDir}"
export FAT_CONTAINER="${args.container}"
export FAT_SQSH_DIR="${args.fatSqshDir}"
export FAT_LLM_TAR_ARTIFACT_PATH="${args.llmTarArtifactPath}"
export FAT_LLM_DOCKER_IMAGE="${args.llmDockerImage}"
export FAT_BUILD_SCRIPT_PATH="${args.fatBuildScriptPathNode}"
export FAT_TAR_NAME="${args.tarName}"
export FAT_FETCH_HELPER_PATH="${args.fetchHelperPathNode}"
export LLM_ARTIFACTS_PRIMARY="${pipeline.artifactPaths.llmArtifactsPrimary()}"
export ARTIFACTORY_NETRC="${args.artifactoryNetrcPathNode}"
export ARTIFACTORY_CREDENTIAL_ID="${pipeline.LLM_ARTIFACTS_CREDENTIALS_ID}"
bash "${args.fatBuildBodyPathNode}"
""".stripIndent()
}

def generateFatPrepareScript(Map args) {
    return """\
#!/bin/bash
export FAT_SQSH_DIR="${args.fatSqshDir}"
export FAT_LLM_TAR_ARTIFACT_PATH="${args.llmTarArtifactPath}"
export FAT_LLM_DOCKER_IMAGE="${args.llmDockerImage}"
export FAT_BUILD_SCRIPT_PATH="${args.fatBuildScriptPathNode}"
export FAT_BUILD_SBATCH_PATH="${args.fatBuildSbatchPathNode}"
export FAT_BUILD_LOG_TEMPLATE="${args.fatBuildLogPath}"
export FAT_ARTIFACTORY_NETRC="${args.artifactoryNetrcPathNode}"
bash "${args.prepareBodyPathNode}"
""".stripIndent()
}

// Delivers the netrc with which the fat sqsh builder reads the packaged build. It is separate from
// the GPU job's netrc, which slurm_install.sh deletes after its own download while a builder that
// is still queued has yet to read the package. The builder deletes it once its download is done,
// and prepare_container.sh deletes it when this stage submits no builder.
def deliverFatBuilderNetrc(pipeline, def remote, String netrcPathNode) {
    def netrcPathLocal = "${pipeline.pwd(tmp: true)}/artifactory-netrc-${UUID.randomUUID().toString()}"
    try {
        writeArtifactoryNetrc(pipeline, netrcPathLocal)
        // scp -p keeps the netrc's 600 mode on the node.
        Utils.copyFileToRemoteHost(pipeline, remote, netrcPathLocal, netrcPathNode)
    } finally {
        pipeline.sh(label: "Remove Artifactory netrc", script: "rm -f '${netrcPathLocal}'")
    }
}

// Writes a netrc for artifactory.nvidia.com with LLM_ARTIFACTS_CREDENTIALS_ID, readable only by its
// owner. The shell's printf builtin moves the secret from the bound variables into the file, so it
// never appears on a command line or in the trace. Callers remove the file in a finally block.
def writeArtifactoryNetrc(pipeline, String netrcPath)
{
    pipeline.withCredentials([pipeline.usernamePassword(credentialsId: pipeline.LLM_ARTIFACTS_CREDENTIALS_ID,
            usernameVariable: 'ARTIFACTORY_USER', passwordVariable: 'ARTIFACTORY_PASSWORD')]) {
        pipeline.withEnv(["ARTIFACTORY_NETRC=${netrcPath}"]) {
            pipeline.sh(label: "Write Artifactory netrc", script: '''
                set +x
                umask 077
                printf 'machine artifactory.nvidia.com login %s password %s\\n' "$ARTIFACTORY_USER" "$ARTIFACTORY_PASSWORD" > "$ARTIFACTORY_NETRC"
            ''')
        }
    }
}

// Returns the path of the read helper, jenkins/scripts/fetch_build_artifact.sh from the run's
// commit, in the node's temporary directory, which workspace resets and package extraction leave
// alone. Unstashes it there the first time.
def readHelperPath(pipeline)
{
    def helperDir = "${pipeline.pwd(tmp: true)}/fetch-build-artifact"
    def helper = "${helperDir}/fetch_build_artifact.sh"
    if (!pipeline.fileExists(helper)) {
        pipeline.dir(helperDir) {
            pipeline.unstash(pipeline.FETCH_BUILD_ARTIFACT_STASH)
        }
    }
    return helper
}

// Reads one in-scope build artifact with the read helper, which tries the primary location first
// and the other one only when the file is absent there, and checks each download against the
// SHA-1 that the serving location reports. The helper's netrc lives in the node's temporary
// directory beside the helper, and is removed before this returns.
//
// "download" writes dest and returns null. "exists" writes nothing and returns "<url> <sha1>" of
// the location that holds the file. Both fail the step at once on a final failure of the helper,
// such as FETCH_ABSENT or a rejected credential, and on FETCH_TRANSFER_FAILED once the helper's
// and llmExecStepWithRetry's retries are spent. "properties" writes the properties JSON to dest and
// returns the helper's exit status instead of failing: 0 when found, otherwise one of the exit
// codes the helper names, such as FETCH_ABSENT when neither location has properties for it.
def fetchBuildArtifact(pipeline, String artifactPath, String dest, String mode = "download")
{
    if (!(mode in ["download", "exists", "properties"])) {
        pipeline.error("fetchBuildArtifact: unknown mode '${mode}'")
    }
    def primary = pipeline.artifactPaths.llmArtifactsPrimary()
    def helper = readHelperPath(pipeline)
    def command = "bash '${helper}'" + (mode == "download" ? "" : " --${mode}") + " '${artifactPath}'" +
        (mode == "exists" ? "" : " '${dest}'")
    def netrcPath = "${pipeline.pwd(tmp: true)}/artifactory-netrc-${UUID.randomUUID().toString()}"
    def statusPath = "${pipeline.pwd(tmp: true)}/fetch-status-${UUID.randomUUID().toString()}"
    try {
        writeArtifactoryNetrc(pipeline, netrcPath)
        return pipeline.withEnv([
            "LLM_ARTIFACTS_PRIMARY=${primary}",
            "ARTIFACTORY_NETRC=${netrcPath}",
            "ARTIFACTORY_CREDENTIAL_ID=${pipeline.LLM_ARTIFACTS_CREDENTIALS_ID}",
        ]) {
            if (mode == "properties") {
                // The helper retries transient errors itself.
                return pipeline.sh(label: "Fetch properties of ${artifactPath}", script: command, returnStatus: true)
            }
            // The helper retries transient errors itself, so only FETCH_TRANSFER_FAILED fails the
            // script and lets llmExecStepWithRetry repeat it. Any other status is final: the script
            // records it and succeeds, and the step fails on it below without a retry.
            def script = "status=0; ${command} || status=\$?; " +
                "if [ \"\$status\" -eq ${pipeline.FETCH_TRANSFER_FAILED} ]; then exit \"\$status\"; fi; " +
                "echo \"\$status\" > '${statusPath}'"
            def stdout = pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, label: "Fetch ${artifactPath}", script: script,
                returnStdout: mode == "exists")
            def status = pipeline.readFile(statusPath).trim()
            if (status != "0") {
                pipeline.error("fetch_build_artifact.sh could not read ${artifactPath} (exit status ${status}); " +
                    "its message above says why.")
            }
            if (mode == "download") {
                return null
            }
            def location = stdout.trim()
            if (!(location ==~ /\S+ [0-9a-f]{40}/)) {
                pipeline.error("fetchBuildArtifact: unexpected location '${location}' for ${artifactPath}")
            }
            return location
        }
    } finally {
        pipeline.sh(label: "Remove Artifactory netrc", script: "rm -f '${netrcPath}' '${statusPath}'")
    }
}

// Which build of <tarName> this stage tests, as the artifact path to read it
// from with fetchBuildArtifact.
//
// Deterministic, not discovered. The pipeline already decided whether the build
// published a BOLTed variant, so there is nothing to probe for: fetch
// bolted-<tarName> and let a missing object fail the stage. A probe-and-fall-back
// shape cannot distinguish "no BOLTed build was published" from "the probe
// itself failed", and both outcomes produce a normal-looking log while the stage
// quietly tests un-BOLTed binaries.
//
// Also asserts the artifact carries the bundle this pipeline pinned. Addressing
// by name proves only that something is there; the property proves it is the
// right something, which is what keeps the tested build and the released wheel
// on one profile set. Absent property means an artifact predating the label:
// reported and allowed rather than failed, as is a property lookup that keeps
// failing. A rejected credential fails the stage.
def boltedTarArtifactPath(pipeline, String config, String tarName)
{
    // Only the SBSA build is BOLTed, so only its tarball has a bolted- variant.
    // Asking for one on any other config would 404 the stage rather than fail
    // honestly, so the arch gate has to match the build's exactly.
    if (!pipeline.BOLT_TEST_BOLTED_TARBALL || config != pipeline.LINUX_AARCH64_CONFIG) {
        return "${pipeline.ARTIFACT_PATH}/${tarName}"
    }
    if (pipeline.BOLT_PINNED_REF) {
        def propsFile = "${pipeline.pwd(tmp: true)}/bolted-${tarName}.properties.json"
        def propsStatus = fetchBuildArtifact(pipeline, "${pipeline.ARTIFACT_PATH}/bolted-${tarName}", propsFile, "properties")
        // FETCH_ABSENT: no properties on either location. FETCH_TRANSFER_FAILED: the lookup kept
        // failing.
        if (!(propsStatus in [0, pipeline.FETCH_ABSENT, pipeline.FETCH_TRANSFER_FAILED])) {
            pipeline.error("[BOLT] Could not read the properties of bolted-${tarName}: " +
                  "fetch_build_artifact.sh exited ${propsStatus} (see its message above).")
        }
        def got = ""
        if (propsStatus == 0) {
            got = pipeline.sh(returnStdout: true, script: """
                tr -d ' \\n' < '${propsFile}' \\
                  | sed -n 's/.*"bolt\\.ref":\\["\\([^"]*\\)"\\].*/\\1/p'
                rm -f '${propsFile}'
            """).trim()
        }
        if (got && got != pipeline.BOLT_PINNED_REF) {
            pipeline.error("[BOLT] bolted-${tarName} carries bolt.ref=${got}, but this pipeline pinned ${pipeline.BOLT_PINNED_REF}. " +
                  "Refusing to test a build optimized with a different profile bundle than the release artifacts use.")
        }
        if (propsStatus == pipeline.FETCH_TRANSFER_FAILED) {
            pipeline.echo "[BOLT] the bolt.ref lookup for bolted-${tarName} failed (fetch_build_artifact.sh exited ${propsStatus}, see its message above); proceeding on pin ${pipeline.BOLT_PINNED_REF}"
        } else if (!got) {
            pipeline.echo "[BOLT] bolted-${tarName} carries no bolt.ref (artifact predates the label); proceeding on pin ${pipeline.BOLT_PINNED_REF}"
        }
    }
    pipeline.echo "[BOLT] testing the BOLTed build: bolted-${tarName}"
    return "${pipeline.ARTIFACT_PATH}/bolted-${tarName}"
}

def checkPipInstall(pipeline, wheel_path)
{
    def wheelArtifactLinks = "https://urm.nvidia.com/artifactory/${pipeline.UPLOAD_PATH}/${wheel_path}"
    pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: """
        cd ${pipeline.LLM_ROOT}/tests/unittest && \
        python3 check_pip_install.py --wheel_path ${wheelArtifactLinks}
        """)
}

def pythonVersionFromCpver(cpver)
{
    if (cpver == "cp310") {
        return "3.10"
    }
    if (cpver == "cp312") {
        return "3.12"
    }
    pipeline.error "Unsupported Python ABI for Kitmaker dry run: ${cpver}"
}

def runKitmakerWheelDryRun(pipeline, wheel_path, python_bin, publish_to)
{
    def wheelUrl = "https://urm.nvidia.com/artifactory/${pipeline.UPLOAD_PATH}/${wheel_path}"
    def releaseScriptsDir = "release-scripts"

    pipeline.echo "Running Kitmaker wheel dry run for ${wheelUrl} with publish target ${publish_to}"
    def kitmakerDryRunMetadata = null
    pipeline.stage("Kitmaker Publish Dry Run") {
        pipeline.catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
            if (!pipeline.TRTLLM_RELEASE_REPO?.url || !pipeline.TRTLLM_RELEASE_REPO?.commit) {
                pipeline.error "TRTLLM_RELEASE_REPO is missing from globalVars; it is resolved by L0_MergeRequest"
            }
            pipeline.sh "rm -rf ${releaseScriptsDir}"
            pipeline.trtllm_utils.checkoutSource(pipeline.TRTLLM_RELEASE_REPO.url, pipeline.TRTLLM_RELEASE_REPO.commit, releaseScriptsDir, false, true)
            pipeline.trtllm_utils.llmExecStepWithRetry(
                pipeline,
                script: "${python_bin} -m pip install -r ${releaseScriptsDir}/requirements.txt")
            pipeline.withCredentials([pipeline.string(credentialsId: pipeline.KITMAKER_CREDENTIALS_ID, variable: 'KITMAKER_API_TOKEN')]) {
                pipeline.retry(3) {
                    pipeline.echo "Publishing Kitmaker wheel dry run for ${wheelUrl}"
                    def resultData = pipeline.sh(script: """${python_bin} ${releaseScriptsDir}/kitmaker_wheel.py publish \
                    --pic-email ${pipeline.KITMAKER_DRY_RUN_PIC_EMAIL} \
                    --publish-to ${publish_to} \
                    --size large \
                    --wheel-urls ${wheelUrl} \
                    --no-upload
                """, returnStdout: true).trim()
                    pipeline.echo "${resultData}"
                    def resultJson = pipeline.readJSON text: resultData
                    kitmakerDryRunMetadata = [
                        releaseUuid: resultJson["release_uuid"],
                        releaseScriptsDir: releaseScriptsDir,
                        pythonBin: python_bin,
                    ]
                }
            }
        }
    }
    return kitmakerDryRunMetadata
}

def checkKitmakerWheelDryRun(pipeline, kitmakerDryRunMetadata)
{
    if (!kitmakerDryRunMetadata?.releaseUuid) {
        pipeline.echo "Skipping Kitmaker wheel dry run check because publish did not return a release UUID"
        return
    }
    pipeline.stage("Kitmaker Check Dry Run") {
        pipeline.catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
            pipeline.retry(3) {
                pipeline.echo "Checking Kitmaker wheel dry run ${kitmakerDryRunMetadata.releaseUuid}"
                pipeline.withCredentials([pipeline.string(credentialsId: pipeline.KITMAKER_CREDENTIALS_ID, variable: 'KITMAKER_API_TOKEN')]) {
                    pipeline.sh """${kitmakerDryRunMetadata.pythonBin} ${kitmakerDryRunMetadata.releaseScriptsDir}/kitmaker_wheel.py check \
                        ${kitmakerDryRunMetadata.releaseUuid} \
                        --wait \
                        --ignore-missing-logs-error
                    """
                }
            }
        }
    }
}

// Apply the branch's latest promoted BOLT profile bundle to a freshly built
// wheel, in place. Mirrors Build.groovy's premerge applyLatestBolt (same
// llvm-bolt staging, same apply_latest.sh, same exit-code contract), but for a
// standalone .whl rather than a packed tarball.
//
// Every failure is fatal, including a missing bundle. The caller only reaches
// here when the pipeline asked for BOLT, and this wheel is what the release job
// publishes, so "BOLT is on" has to mean the wheel IS optimized -- degrading to
// an un-BOLTed wheel with a log line is how an unoptimized artifact reaches PyPI
// unnoticed. That is stricter than Build.groovy's applyLatestBolt, which treats
// a missing bundle as a skip; it has to, because the same switch covers x86_64,
// where nothing is promoted yet. This path is aarch64-only and the switch is
// main-only, so there is always a bundle to find.
def applyLatestBoltToWheel(pipeline, String wheel, String cpu_arch, String boltProfileRef = "",
                           String boltProfileBranch = "")
{
    def llvmArch = (cpu_arch == pipeline.AARCH64_TRIPLE) ? "ARM64" : "X64"
    // apply_latest.sh resolves exactly one branch, so try the build's own branch
    // and fall back to main. Profiles are function-name-keyed and applied with
    // -infer-stale-profile, so a nearby branch's bundle is valid -- the same
    // candidate-branch fallback BuildDockerImage.groovy's overlay uses.
    def branches = [pipeline.env.gitlabTargetBranch, pipeline.env.branch_name, "main"]
        .collect { it?.toString()?.trim() }
        .findAll { it }
        .unique()
    // A pin now supplies its own branch, so nothing is left to guess: the ref
    // names one object under that branch's promote directory. Walking candidates
    // would only add ways to fetch something other than what was pinned.
    if (boltProfileRef && boltProfileBranch) {
        branches = [boltProfileBranch]
        pipeline.echo "[bolt-wheel] pinned to BOLT bundle ${boltProfileRef} on ${boltProfileBranch}"
    }

    pipeline.stage("BOLT release wheel") {
        pipeline.sh """
            set -e
            export PATH="\$PWD/.bolt-llvm/bin:\$PATH"
            if ! command -v llvm-bolt >/dev/null 2>&1; then
                . tensorrt_llm/scripts/bolt/internal/llvm_bolt_version.sh
                echo "[bolt-wheel] staging llvm-bolt \${LLVM_BOLT_VERSION}"
                tb=LLVM-\${LLVM_BOLT_VERSION}-Linux-${llvmArch}.tar.xz
                mkdir -p .bolt-llvm
                curl -fSL --retry 10 --retry-all-errors --retry-delay 15 --connect-timeout 60 \
                     -o /tmp/\$tb "https://github.com/llvm/llvm-project/releases/download/llvmorg-\${LLVM_BOLT_VERSION}/\$tb"
                tar -xJf /tmp/\$tb -C .bolt-llvm --strip-components=1
                rm -f /tmp/\$tb
            fi
        """
        // Exit codes (apply_latest.sh): 3 = no promoted bundle for branch/triple,
        // 2 = apply error, 0 = applied. Only 3 is worth trying the next branch for;
        // an apply error means the bundle IS there and did not take, which retrying
        // against a different branch would only paper over.
        def rc = 3
        def appliedFrom = null
        for (b in branches) {
            rc = pipeline.sh(returnStatus: true, script: """
                export PATH="\$PWD/.bolt-llvm/bin:\$PATH"
                export BOLT_PROFILE_REF='${boltProfileRef}'
                # Quoted: the branch comes from job env and the wheel name from a
                # directory listing, so an unquoted expansion would let a shell
                # metacharacter in either run before apply_latest.sh starts.
                bash tensorrt_llm/scripts/bolt/internal/apply_latest.sh \
                     '${b}' '${cpu_arch}' '${wheel}' '${wheel}.bolted'
            """)
            if (rc != 3) {
                appliedFrom = b
                break
            }
            pipeline.echo "[bolt-wheel] no promoted bundle for ${b}/${cpu_arch}; trying next candidate branch"
        }
        if (rc == 3 && boltProfileRef) {
            pipeline.error("[bolt-wheel] pinned BOLT bundle ${boltProfileRef} (${cpu_arch}) not found under any of " +
                  "${branches.join(', ')}. This pipeline pinned a bundle the release wheel cannot fetch; " +
                  "refusing to publish a wheel optimized with anything else.")
        }
        if (rc == 3) {
            pipeline.error("[bolt-wheel] no promoted BOLT bundle for any of ${branches.join(', ')} (${cpu_arch}); " +
                  "refusing to upload an unoptimized release wheel (promote a bundle via BoltProfileGen, " +
                  "or turn BOLT consume off for this run)")
        }
        if (rc != 0) {
            pipeline.error("[bolt-wheel] apply_latest.sh failed (rc=${rc}) for ${appliedFrom}/${cpu_arch}")
        }
        pipeline.sh "mv -f ${wheel}.bolted ${wheel}"
        pipeline.echo "[bolt-wheel] ${wheel} is now BOLTed (profiles from ${appliedFrom}/${cpu_arch}" +
             (boltProfileRef ? ", bundle ${boltProfileRef})" : ")")
    }
}

def runLLMBuild(
    pipeline,
    cpu_arch,
    reinstall_dependencies=false,
    wheel_path="",
    version_override="",
    cpver="cp312",
    plat_name="",
    is_dlfw=false,
    boltConsume=false,
    boltProfileRef="",
    boltProfileBranch="",
    kitmakerDryRun=false)
{
    pipeline.sh "pwd && ls -alh"
    pipeline.sh "env | sort"
    pipeline.sh "ccache -sv"

    pipeline.trtllm_utils.checkoutSource(pipeline.LLM_REPO, pipeline.env.gitlabCommit, "tensorrt_llm", true, true)
    // Random sleep to avoid resource contention
    pipeline.sleep(10 * Math.random())
    pipeline.sh "curl ifconfig.me || true"
    pipeline.sh "nproc && free -g && hostname"
    pipeline.sh "cat ${pipeline.CCACHE_DIR}/ccache.conf"
    pipeline.sh "bash -c 'pip3 show tensorrt || true'"
    if (reinstall_dependencies == true) {
        pipeline.sh "#!/bin/bash \n" + "pip3 uninstall -y torch"
        pipeline.sh "#!/bin/bash \n" + "yum remove -y libcudnn*"
    }

    pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "#!/bin/bash \n" + "cd tensorrt_llm/ && pip3 install -r requirements-dev.txt")
    pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "#!/bin/bash \n" + "cd tensorrt_llm/ && pip3 install -r requirements-grpc-smg.txt")
    pipeline.buildArgs = "--clean --nixl_root /opt/nvidia/nvda_nixl"
    if (cpu_arch == pipeline.AARCH64_TRIPLE) {
        pipeline.buildArgs += " -a '90;100;103;107;120;121'"
    } else {
        pipeline.buildArgs += " -a '80;86;89;90;100;103;107;120'"
    }
    def platNameArg = plat_name ? " --plat-name ${plat_name}" : ""

    pipeline.withEnv([
        "TRTLLM_BUILD_SOURCE_COMMIT=${pipeline.env.gitlabCommit}",
        "TRTLLM_VERSION_OVERRIDE=${version_override}",
    ]) {
        pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "#!/bin/bash \n" + "cd tensorrt_llm/ && python3 scripts/build_wheel.py --version-override \"\${TRTLLM_VERSION_OVERRIDE}\" --use_ccache -G Ninja -j ${pipeline.BUILD_JOBS} -D 'WARNING_IS_ERROR=ON' --extra-cmake-vars ENABLE_BOLT_COMPATIBLE=ON ${pipeline.buildArgs}${platNameArg}")
    }

    def wheelName = pipeline.sh(returnStdout: true, script: 'cd tensorrt_llm/build && ls -1 *.whl').trim()

    // ENABLE_BOLT_COMPATIBLE=ON above only makes the binaries BOLT-able; it does
    // not optimize them. The tarball gets the actual optimization elsewhere
    // (Build.groovy premerge, BoltProfileGen postmerge), but this wheel is built
    // and uploaded on its own, so without this step the released SBSA wheel ships
    // unoptimized. Must run BEFORE the upload below, and before the local
    // pip install / DLFW repack, so every consumer sees the same bytes.
    //
    // Gated on the pipeline's BOLT switch, not on the run mode: the wheel the
    // release job publishes is the same artifact whether the run is nightly,
    // weekly or GA, so the trigger has to be "is BOLT on", not "is this a
    // nightly". aarch64 only -- x86_64 has no promoted bundle.
    //
    // Scoped to the wheel published at the root of <arch>/, which is the one the
    // release job picks up. An imageTest/ build is a throwaway wheel compiled
    // inside an already-released image to prove that image can still build from
    // source; optimizing it would prove nothing and only add a failure mode.
    if (boltConsume && cpu_arch == pipeline.AARCH64_TRIPLE && !wheel_path) {
        applyLatestBoltToWheel(pipeline, "tensorrt_llm/build/${wheelName}", cpu_arch,
                               boltProfileRef, boltProfileBranch)
    }

    def rootWheelUploadPath = "${cpu_arch}/${wheel_path}"
    // DLFW publishes the built public-version wheel under its subdirectory. Other
    // builds continue to publish the built wheel at the original path.
    def builtWheelUploadPath =
        is_dlfw ? "${rootWheelUploadPath}dlfw/" : rootWheelUploadPath
    pipeline.echo "uploading ${wheelName} to ${builtWheelUploadPath}"
    pipeline.trtllm_utils.uploadArtifacts(
        "tensorrt_llm/build/${wheelName}",
        "${pipeline.UPLOAD_PATH}/${builtWheelUploadPath}")

    def uploadedWheelPath = "${builtWheelUploadPath}${wheelName}"
    def wheelPath = uploadedWheelPath
    if (is_dlfw) {
        // NVIDIA_PYTORCH_VERSION ships with the NGC PyTorch image itself, which
        // makes it the ground truth for the NGC release version. Example value: 26.08
        def ngcReleaseVersion = pipeline.sh(
            returnStdout: true,
            script: 'echo $NVIDIA_PYTORCH_VERSION'
        ).trim()
        def matcher = ngcReleaseVersion =~ /^(\d+)\.(\d+)$/
        if (!matcher.find()) {
            pipeline.error "Failed to extract NGC Release version from NVIDIA_PYTORCH_VERSION: '${ngcReleaseVersion}'"
        }
        pipeline.echo "NGC Release version: ${ngcReleaseVersion}"
        def dlfwLocalVersion =
            "ngcpytorch${matcher.group(1)}${matcher.group(2)}"
        def localWheelPath = pipeline.sh(
            returnStdout: true,
            script: "python3 tensorrt_llm/jenkins/scripts/repack_wheel.py " +
                "tensorrt_llm/build/${wheelName} ${dlfwLocalVersion} " +
                "--output-dir tensorrt_llm/build/local-version"
        ).trim()
        def localWheelName = localWheelPath.tokenize('/').last()
        pipeline.echo "uploading ${localWheelName} to ${rootWheelUploadPath}"
        pipeline.trtllm_utils.uploadArtifacts(
            localWheelPath,
            "${pipeline.UPLOAD_PATH}/${rootWheelUploadPath}")
        wheelPath = "${rootWheelUploadPath}${localWheelName}"
    }

    def kitmakerDryRunMetadata = null
    if (version_override?.contains("+")) {
        pipeline.echo "Skipping Kitmaker wheel dry run for local version '${version_override}'"
    } else if (!kitmakerDryRun) {
        pipeline.echo "Skipping Kitmaker wheel dry run outside post-merge"
    } else {
        def kitmakerPython = "tensorrt_llm/.venv-${pythonVersionFromCpver(cpver)}/bin/python3"
        kitmakerDryRunMetadata = runKitmakerWheelDryRun(pipeline, uploadedWheelPath, kitmakerPython, pipeline.KITMAKER_PUBLISH_TO)
    }

    if (reinstall_dependencies) {
        // Test installation in the new environment
        // Reserve CUDA 13.0 torch and torchvision packages
        def pip_keep = "^pip==|^torch==|^torchvision=="
        pipeline.sh "bash -c 'pip3 list --format=freeze | grep -Ev \"${pip_keep}\" | xargs -r pip3 uninstall -y'"
        pipeline.sh "bash -c 'yum remove -y libcudnn* libnccl* libcublas* && rm -rf /usr/local/tensorrt'"
    }

    // Test preview installation
    pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "#!/bin/bash \n" + "cd tensorrt_llm/ && pip3 install pytest build/tensorrt_llm-*.whl")

    // Upload attribution files when they exist
    def attrDir = "tensorrt_llm/cpp/build/attribution"
    def wheelBase = wheelName.replace('.whl', '')
    for (f in ["missing_files.json", "import_payload.json", "file_mappings.json"]) {
        if (pipeline.fileExists("${attrDir}/${f}")) {
            pipeline.trtllm_utils.uploadArtifacts("${attrDir}/${f}", "${pipeline.UPLOAD_PATH}/${cpu_arch}/attribution/${wheel_path}${wheelBase}/")
        }
    }
    checkKitmakerWheelDryRun(pipeline, kitmakerDryRunMetadata)

    return wheelPath
}

def runPackageSanityCheck(pipeline, wheel_path, reinstall_dependencies=false)
{
    def whlUrl = "https://urm.nvidia.com/artifactory/${pipeline.UPLOAD_PATH}/${wheel_path}"

    // Random sleep to avoid resource contention
    pipeline.sleep(10 * Math.random())
    pipeline.sh "curl ifconfig.me || true"
    pipeline.sh "nproc && free -g && hostname"
    pipeline.sh "bash -c 'pip3 show tensorrt || true'"
    pipeline.sh "cat ${pipeline.MODEL_CACHE_DIR}/README"
    pipeline.sh "nvidia-smi && nvidia-smi -q && nvidia-smi topo -m"

    pipeline.sh "pwd && ls -alh"
    pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "wget -nv ${whlUrl}")

    if (reinstall_dependencies) {
        // Test installation in the new environment
        // Reserve CUDA 13.0 torch and torchvision packages
        def pip_keep = "^pip==|^torch==|^torchvision=="
        pipeline.sh "bash -c 'pip3 list --format=freeze | grep -Ev \"${pip_keep}\" | xargs -r pip3 uninstall -y'"
        pipeline.sh "bash -c 'yum remove -y libcudnn* libnccl* libcublas* && rm -rf /usr/local/tensorrt'"
    }
    //WAR: remove python3-pygments first since it is installed in NGC PyTorch image
    pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "apt-get remove -y python3-pygments")

    // Test preview installation
    pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "bash -c 'pip3 install pytest tensorrt_llm-*.whl'")

    def pkgUrl = "https://urm.nvidia.com/artifactory/${pipeline.ARTIFACT_PATH}/${pipeline.linuxPkgName}"
    pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "wget -nv ${pkgUrl}")
    pipeline.sh "tar -zvxf ${pipeline.linuxPkgName}"

    // TODO: The steps below drove the removed TensorRT engine flow (trtllm-build / examples/run.py).
    // When re-enabling this sanity check, use PyTorch backend test samples instead
    // (e.g. examples/llm-api/quickstart_example.py).
    // trtllm_utils.llmExecStepWithRetry(pipeline, script: "bash -c 'cd tensorrt_llm/examples/models/core/gpt && python3 ../../../generate_checkpoint_config.py --architecture GPTForCausalLM --dtype float16'")
    // trtllm_utils.llmExecStepWithRetry(pipeline, script: "bash -c 'cd tensorrt_llm/examples/models/core//gpt && trtllm-build --model_config config.json --log_level verbose'")
    // trtllm_utils.llmExecStepWithRetry(pipeline, script: "bash -c 'cd tensorrt_llm/examples/models/core/gpt && python3 ../../../run.py --max_output_len 4 --end_id -1'")
}

return this
