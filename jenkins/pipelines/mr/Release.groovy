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

import java.lang.InterruptedException
import groovy.transform.Field
import groovy.json.JsonSlurper
import com.nvidia.bloom.Logger
import com.nvidia.bloom.JobBuilder
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils as jUtils

@Field def pipeline

def initialize(pipeline)
{
    this.@pipeline = pipeline
    return this
}

def launchReleaseCheck(pipeline, globalVars)
{
    def releaseSteps = {
        pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "apt-get update && apt-get install -y python3-pip")
        pipeline.sh "pip3 config set global.break-system-packages true"
        pipeline.sh "git config --global --add safe.directory \"*\""
        // Step 1: Clone TRT-LLM source codes
        pipeline.trtllm_utils.checkoutSource(pipeline.LLM_REPO, pipeline.env.gitlabCommit, pipeline.LLM_ROOT, true, true)
        pipeline.sh "cd ${pipeline.LLM_ROOT} && git config --unset-all core.hooksPath"

        // Step 2: Run guardwords scan
        def isOfficialPostMergeJob = (pipeline.env.JOB_NAME ==~ /.*PostMerge.*/)
        if (isOfficialPostMergeJob || pipeline.runMode == "nightly_release") {
            pipeline.trtllm_utils.checkoutSource(pipeline.SCAN_REPO, pipeline.SCAN_COMMIT, pipeline.SCAN_ROOT, false, true)
            pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "cd ${pipeline.SCAN_ROOT} && pip3 install -e .")
            try {
                def ignoreList = [
                    "*/.git/*",
                    "*/3rdparty/*",
                    "*/cpp/tensorrt_llm/deep_ep/nvshmem_src_*.txz",
                    "*/examples/scaffolding/contrib/mcp/weather/weather.py",
                    "*/tensorrt_llm_internal_cutlass_kernels_static.tar.xz",
                    "*/triton_kernels/*.py"
                ]
                pipeline.sh """#!/bin/bash
                    set -eo pipefail
                    cd ${pipeline.LLM_ROOT}
                    # Batch files below ARG_MAX; pipefail propagates scan failures to prevent false greens.
                    find . -type f \\
                        ${ignoreList.collect { "-not -path \"${it}\"" }.join(' ')} \\
                        -not -path "./scan.log" \\
                        -exec confidentiality-scan {} + 2>&1 | tee scan.log
                """.stripIndent()
            } catch (InterruptedException e) {
                throw e
            } catch (Exception e) {
                pipeline.catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                    pipeline.error "Release Check failed (warn-only): ${e.getMessage()}"
                }
            } finally {
                pipeline.trtllm_utils.uploadArtifacts("${pipeline.LLM_ROOT}/scan.log", "${pipeline.UPLOAD_PATH}/guardwords-scan-results/")
                pipeline.echo "Guardwords Scan Results: https://urm.nvidia.com/artifactory/${pipeline.UPLOAD_PATH}/guardwords-scan-results/scan.log"
            }
        }

        // Step 3: Run pre-commit checks
        // Post-merge CI runs on all files; pre-merge CI runs only on changed files.
        def precommitArgs = "-a"
        if (!isOfficialPostMergeJob) {
            // Use GitLab/GitHub API to get the exact list of changed files in this MR.
            // This avoids git history depth issues with shallow clones.
            def changedFileList = pipeline.mrChanges.getMergeRequestChangedFileList(pipeline, globalVars)
            pipeline.echo "Changed file count from API: ${changedFileList ? changedFileList.size() : 0}"
            // GitHub's PR Files API caps out at 3000 entries, silently truncating the
            // list for huge PRs. Fail closed with a 2500 margin below that cap.
            if (changedFileList && !changedFileList.isEmpty() && changedFileList.size() < 2500) {
                def changedFilesPath = "${pipeline.LLM_ROOT}/changed_files.txt"
                pipeline.writeFile file: changedFilesPath, text: changedFileList.unique().join("\n")
                // Script runs after "cd ${LLM_ROOT}", so use relative path
                precommitArgs = "--files-from changed_files.txt"
                pipeline.echo "Pre-commit will check ${changedFileList.unique().size()} changed file(s)"
            } else if (changedFileList && changedFileList.size() >= 2500) {
                pipeline.echo "Changed file list hit the 2500-file safety margin, falling back to all files"
            } else {
                pipeline.echo "Could not determine changed files, falling back to all files"
            }
        }
        pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "cd ${pipeline.LLM_ROOT} && python3 -u scripts/release_check.py ${precommitArgs} || (git restore . && false)")

        // Step 4: Run license check
        pipeline.withEnv(['GONOSUMDB=*.nvidia.com']) {
            pipeline.withCredentials([
                pipeline.gitUsernamePassword(
                    credentialsId: 'svc_tensorrt_gitlab_read_api_token',
                    gitToolName: 'git-tool'
                ),
                pipeline.string(
                    credentialsId: 'default-git-url',
                    variable: 'DEFAULT_GIT_URL'
                )
            ]) {
                pipeline.sh "go install ${pipeline.DEFAULT_GIT_URL}/TensorRT/Infrastructure/licensechecker/cmd/license_checker@v0.3.0"
            }
        }
        pipeline.sh "cd ${pipeline.LLM_ROOT}/cpp && /go/bin/license_checker -config ../jenkins/license_cpp.json include tensorrt_llm"
    }

    def image = "artifactory.pdx.nvidia.com/docker-remote/golang:1.23"
    def stageName = "Release-Check"
    pipeline.trtllm_utils.launchKubernetesPod(pipeline, pipeline.createKubernetesPodConfig(image, "package"), "trt-llm", {
        pipeline.stage("[${stageName}] Run") {
            if (pipeline.RELEASE_CHECK_CHOICE == pipeline.STAGE_CHOICE_SKIP) {
                pipeline.echo "Release Check job is skipped due to Jenkins configuration"
                return
            }
            try {
                pipeline.mrEnvironment.echoNodeAndGpuInfo(pipeline, stageName)
                releaseSteps()
            } catch (InterruptedException e) {
                throw e
            } catch (Exception e) {
                if (pipeline.RELEASE_CHECK_CHOICE == pipeline.STAGE_CHOICE_IGNORE) {
                    pipeline.catchError(
                        buildResult: 'SUCCESS',
                        stageResult: 'FAILURE') {
                        pipeline.error "Release Check failed but ignored due to Jenkins configuration"
                    }
                } else {
                    throw e
                }
            }
        }
    })
}

// Inside a parallel branch, pass a nested stage name: Blue Ocean shows a skip marked on the branch's own stage as NOT_BUILT.
def skipStage(String stageName, String message)
{
    this.@pipeline.stage(stageName) {
        this.@pipeline.echo message
        jUtils.markStageSkippedForConditional(stageName)
    }
}

def launchTestListCheck(pipeline, globalVars)
{
    def key = "Check-Test-List"
    def image = globalVars["LLM_DOCKER_IMAGE"]
    pipeline.trtllm_utils.launchKubernetesPod(pipeline, pipeline.createKubernetesPodConfig(image, "package"), "trt-llm", {
        pipeline.stage("[${key}] Run") {
            pipeline.mrEnvironment.echoNodeAndGpuInfo(pipeline, key)
            pipeline.sh "git config --global --add safe.directory \"*\""
            pipeline.trtllm_utils.checkoutSource(pipeline.LLM_REPO, pipeline.env.gitlabCommit, pipeline.LLM_ROOT, false, true)

            def llmPath = pipeline.sh(script: "realpath ${pipeline.LLM_ROOT}", returnStdout: true).trim()
            pipeline.sh "NVIDIA_TRITON_SERVER_VERSION=26.09 LLM_ROOT=${llmPath} LLM_BACKEND_ROOT=${llmPath}/triton_backend " +
               "python3 ${llmPath}/scripts/check_test_list.py --l0 --qa --waive --validate --parity --check-duplicate-waives"
        }
    })
}

// Pick the single BOLT profile bundle this pipeline will use, and pin it.
//
// Without a pin every consumer resolves `latest` independently, at whatever
// moment it happens to run. Post-merge promotes a new bundle mid-pipeline, so a
// stage that pulls before the promote and one that pulls after legitimately get
// different profiles -- and parallel post-merge pipelines make it worse. That is
// invisible: each consumer succeeds, and the run is green having tested
// something other than what it shipped.
//
// Pinning applies to post-merge and nightly releases. Pre-merge reads `latest` (its
// drift is acceptable and its behaviour must not change), and returning "" leaves
// every consumer on exactly today's path.
//
// Best-effort by design. A branch with nothing promoted, or a bundle too old to
// carry the label, yields "" and the pipeline runs unpinned rather than failing
// -- an unresolvable pin is a reason to keep the old behaviour, not to break the
// build.
def resolveBoltProfileRef(String branch, String triple = "aarch64-linux-gnu")
{
    if (!this.@pipeline.isBoltReleasePipeline()) {
        return ""
    }
    def ref = ""
    try {
        ref = this.@pipeline.sh(returnStdout: true, script: """
            bash ${this.@pipeline.LLM_ROOT}/scripts/bolt/internal/artifactory.sh \
                 resolve-latest ${branch} ${triple} 2>/dev/null || true
        """).trim()
    } catch (InterruptedException e) {
        // Aborts and timeouts reach here as FlowInterruptedException, which
        // extends InterruptedException. Letting the catch below swallow one
        // would turn a cancelled pipeline into an unpinned pipeline that
        // carries on and launches every build job.
        throw e
    } catch (Exception e) {
        this.@pipeline.echo "BOLT profile pin: resolve failed (${e.message}); running unpinned."
        return ""
    }
    if (!ref) {
        this.@pipeline.echo "BOLT profile pin: nothing promoted for ${branch}/${triple}; running unpinned."
        return ""
    }
    this.@pipeline.echo "BOLT profile pin: ${branch}/${triple} -> ${ref}. Every consumer in this run uses this bundle."
    return ref
}

def getCommonParameters()
{
    return [
        'gitlabSourceRepoHttpUrl': this.@pipeline.LLM_REPO,
        'gitlabCommit': this.@pipeline.env.gitlabCommit,
        'artifactPath': this.@pipeline.UPLOAD_PATH,
        'uploadPath': this.@pipeline.UPLOAD_PATH,
    ]
}

def getPLCRepoUrlKey()
{
    def repoUrlKey = "tensorrt_llm_github_sync"
    this.@pipeline.withCredentials([this.@pipeline.string(credentialsId: 'default-llm-repo', variable: 'DEFAULT_LLM_REPO')]) {
        if (this.@pipeline.LLM_REPO == this.@pipeline.DEFAULT_LLM_REPO) {
            repoUrlKey = "tensorrt_llm_internal"
        } else if (this.@pipeline.LLM_REPO == "https://github.com/NVIDIA/TensorRT-LLM.git" ||
                   this.@pipeline.LLM_REPO == "https://github.com/NVIDIA/TensorRT-LLM") {
            repoUrlKey = "tensorrt_llm_github"
        }
    }
    return repoUrlKey
}

def findCachedPLCSourceScanResult(commit) {
    def plcJobUrl = "${this.@pipeline.trtllm_utils.resolveCurrentJenkinsBaseUrl(this.@pipeline)}/job/LLM/job/helpers/job/PLCScanningSetup"
    def exitCode = this.@pipeline.sh(
        script: "python3 ${this.@pipeline.LLM_ROOT}/jenkins/scripts/find_plc_build.py --commit ${commit} --jenkins-base ${plcJobUrl} > find_plc_build.json",
        returnStatus: true
    )
    if (exitCode != 0) {
        return null
    }
    def buildInfo = new JsonSlurper().parseText(this.@pipeline.readFile("find_plc_build.json"))
    return [result: buildInfo.result, url: buildInfo.url]
}

def launchJob(pipeline, jobName, reuseBuild, enableFailFast, globalVars, additionalParameters = [:]) {
    def parameters = getCommonParameters()
    // Build a local copy to avoid racey growth from shared parallel mutations.
    // In particular, CACHED_CHANGED_FILE_LIST can become very large and may
    // trigger "Argument list too long" when passed to downstream jobs.
    def globalVarsToPass = globalVars.findAll { key, value -> key != pipeline.runParameters.CACHED_CHANGED_FILE_LIST }
    globalVarsToPass[pipeline.runParameters.LLM_ARTIFACTS_PRIMARY_KEY] = pipeline.artifactPaths.llmArtifactsPrimary()
    String globalVarsJson = pipeline.writeJSON returnText: true, json: globalVarsToPass
    parameters += [
        'enableFailFast': enableFailFast,
        'globalVars': globalVarsJson,
    ] + additionalParameters

    // Preserve an explicit empty phase override from the dry-run helper.
    if (!additionalParameters.containsKey('testPhase2StageName') && pipeline.env.testPhase2StageName) {
        parameters += [
            'testPhase2StageName': pipeline.env.testPhase2StageName,
        ]
    }

    if (reuseBuild) {
        parameters['reuseArtifactPath'] = "sw-tensorrt-generic/llm-artifacts/${pipeline.JOB_NAME}/${reuseBuild}"
    }

    if (jobName.startsWith("/")) {
        jobName = jobName.substring(1)
    } else {
        def pos = pipeline.env.JOB_NAME.lastIndexOf("/")
        def jobDir
        if (pos != -1) {
            jobDir = pipeline.env.JOB_NAME.substring(0, pos + 1)
        } else {
            jobDir = ""
        }
        jobName = "${jobDir}${jobName}"
    }

    pipeline.echo "Trigger ${jobName} job, params: ${parameters}"

    def logger = new Logger(pipeline)
    def (jenkinsURL, buildStatus) = JobBuilder.build(pipeline, logger, jobName, parameters, 1, false)
    // Infra-scoped fail-fast (parent half). A downstream sub-job returns UNSTABLE
    // when it saw only infra aborts and no genuine test/build failure (see
    // runBranchesWithInfraDefer in L0_Test.groovy and Build.groovy). That is incomplete coverage,
    // not a failure: throwing here is exactly what trips the per-arch failFast and
    // cancels the healthy sibling architecture, so do NOT throw. Mark the build
    // UNSTABLE (visible + re-runnable) and let the sibling finish. FAILURE and
    // ABORTED still throw below, so real failures fail-fast exactly as before.
    if (buildStatus == "UNSTABLE") {
        pipeline.catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
            pipeline.error "Downstream job ${jobName} is infra-incomplete (UNSTABLE); sibling not cancelled"
        }
        return buildStatus
    }
    if (buildStatus != "SUCCESS") {
        pipeline.error "Downstream job did not succeed"
    }
    return buildStatus
}

def launchInfraDryRunTestJob(pipeline, arch, testFilter, globalVars, imageParameters)
{
    String testFilterJson = pipeline.writeJSON returnText: true, json: testFilter
    def additionalParameters = [
        'testFilter': testFilterJson,
        // Keep all synthetic GPU-count stages in this helper.
        'testPhase2StageName': '',
    ] + imageParameters
    pipeline.stage("[Test-${arch}-Single-GPU] Remote Run") {
        launchJob(pipeline, "L0_Test-${arch}-Single-GPU", false, false, globalVars, additionalParameters)
    }
}

return this
