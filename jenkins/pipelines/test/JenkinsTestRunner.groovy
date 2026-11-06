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
import com.nvidia.bloom.SlurmConfig
import trtllm.exceptions.InfraFailure

@Field def pipeline
@Field def modelExpressVersion

def initialize(pipeline, modelExpressVersion)
{
    this.@pipeline = pipeline
    this.@modelExpressVersion = modelExpressVersion
    return this
}

def runLLMTestlistOnPlatformImpl(pipeline, platform, testList, config=pipeline.VANILLA_CONFIG, stageName="Undefined", splitId=1, splits=1, skipInstallWheel=false, String postTag="", boolean useClusterDurations=false, Map retryContext=null)
{
    // Step 1: create LLM_ROOT dir and clean up the workspace
    def llmRootConfig = "${pipeline.LLM_ROOT}${config}"
    pipeline.sh """
        env | sort
        pwd && ls -alh
        rm -rf ./*
        mkdir ${llmRootConfig}
        ls -alh ${pipeline.env.WORKSPACE}
        ls -alh ${pipeline.env.WORKSPACE_TMP}
    """

    def llmPath = pipeline.sh (script: "realpath ${llmRootConfig}", returnStdout: true).trim()
    def llmSrc = "${llmPath}/TensorRT-LLM/src"
    pipeline.testRuntime.echoNodeAndGpuInfo(pipeline, stageName)

    // Step 2: run tests
    pipeline.stage ("Setup Environment")
    {
        // Random sleep to avoid resource contention
        pipeline.sleep(10 * Math.random())
        pipeline.sh "curl ifconfig.me || true"
        pipeline.sh "nproc && free -g && hostname"
        pipeline.testRuntime.echoNodeAndGpuInfo(pipeline, stageName)
        pipeline.sh "cat ${pipeline.MODEL_CACHE_DIR}/README"
        if (stageName.startsWith("CPU-")) {
            pipeline.sh "ln -s /usr/local/cuda/compat/lib.real /usr/local/cuda/compat/lib"
        } else {
            pipeline.sh "nvidia-smi && nvidia-smi -q && nvidia-smi topo -m"
        }
        pipeline.sh "df -h"

        // setup HF_HOME to cache model and datasets
        // init the huggingface cache from nfs, since the nfs is read-only, and HF_HOME needs to be writable, otherwise it will fail at creating file lock
        pipeline.sh "mkdir -p ${pipeline.HF_HOME} && ls -alh ${pipeline.HF_HOME}"
        pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "rsync -r ${pipeline.MODEL_CACHE_DIR}/hugging-face-cache/ ${pipeline.HF_HOME}/ && ls -lh ${pipeline.HF_HOME}")
        pipeline.sh "df -h"

        // install package
        pipeline.sh "env | sort"
        pipeline.sh "which python3"
        pipeline.sh "python3 --version"

        pipeline.sh "rm -rf results-${stageName}.tar.gz ${stageName}/*"
        // Inside a fat sqsh (SLURM agent) TRT-LLM is pre-extracted at /tmp/TensorRT-LLM;
        // symlink it to skip the ~2-min download.  In all other contexts (K8s pods,
        // non-fat-sqsh SLURM) the directory does not exist, so fall back to download + tar.
        def tarName = pipeline.BUILD_CONFIGS[config][pipeline.TARNAME]
        // /tmp/trtllm_installed.txt is written only by fat_build_inline.sh, distinguishing
        // a pre-baked fat sqsh from a base image that merely has /tmp/TensorRT-LLM after
        // a prior normal install on the same node.
        def isFatSqsh = pipeline.sh(script: "test -f /tmp/trtllm_installed.txt && test -d /tmp/TensorRT-LLM && echo yes || echo no", returnStdout: true).trim() == "yes"
        if (isFatSqsh) {
            pipeline.sh "ln -sfn /tmp/TensorRT-LLM ${llmPath}/TensorRT-LLM"
            // Resolve to real path so pytest --rootdir doesn't produce ../../../ node IDs.
            llmSrc = pipeline.sh(script: "realpath ${llmPath}/TensorRT-LLM/src", returnStdout: true).trim()
        } else {
            def llmTarArtifactPath = pipeline.testBuild.boltedTarArtifactPath(pipeline, config, tarName)
            pipeline.timeout(time: 30, unit: 'MINUTES') {
                pipeline.testBuild.fetchBuildArtifact(pipeline, llmTarArtifactPath, "${llmPath}/${tarName}")
            }
            pipeline.sh "cd ${llmPath} && tar -zxf ${tarName}"
        }

        // install python package
        // isFatSqsh: deps and wheel are pre-baked in the fat sqsh — skip all installs.
        // skipInstallWheel: wheel already installed externally (e.g. K8s sanity check
        //   pre-installs a platform-specific wheel) — install deps but skip the wheel.
        pipeline.timeout(time: 60, unit: 'MINUTES') {
            if (!isFatSqsh) {
                pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "cd ${llmSrc} && pip3 install -r requirements-dev.txt")
                // SMG's generated package remains an opt-in dependency. OpenEngine's
                // private bindings and grpcio dependency are part of the base package.
                pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "cd ${llmSrc} && pip3 install -r requirements-grpc-smg.txt")
            }
            if (stageName.contains("-Ray-")) {
                pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "pip3 install ray[default]==2.55.1")
                pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "bash ${llmSrc}/jenkins/scripts/install_mamba.sh")
            }
            if (!isFatSqsh && !skipInstallWheel) {
                pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "cd ${llmPath} && pip3 install --force-reinstall --no-deps TensorRT-LLM/tensorrt_llm-*.whl")
            }
            if (stageName.contains("-ModelExpress-")) {
                // The wheel goes in with --no-deps above, so its `mx` extra never
                // applies. nixl comes from the image (install_nixl.sh).
                pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "pip3 install modelexpress==${this.@modelExpressVersion}")
            }
        }

        pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "git config --global --add safe.directory \"*\"")
    }

    if (pipeline.testFilter[(pipeline.runParameters.DEBUG_MODE)]) {
        pipeline.stage("Interactive Debug Session")
        {
            pipeline.testFilter[(pipeline.runParameters.DEBUG_MODE)] = false

            pipeline.sh """
                echo 'Port 22' >> /etc/ssh/sshd_config
                echo 'PermitRootLogin yes' >> /etc/ssh/sshd_config
                echo 'PasswordAuthentication yes' >> /etc/ssh/sshd_config
                echo 'PubkeyAuthentication yes' >> /etc/ssh/sshd_config
                echo 'AllowTcpForwarding yes' >> /etc/ssh/sshd_config
                echo 'GatewayPorts yes' >> /etc/ssh/sshd_config
                cat /etc/ssh/sshd_config
            """

            pipeline.sh "service ssh restart"
            pipeline.sh "service ssh status"

            pipeline.sh "ssh-keygen -t rsa -b 2048 -f ~/.ssh/id_rsa -N '' -q"

            pipeline.sh """
                chmod 700 ~/.ssh
                chmod 400 ~/.ssh/id_rsa
                touch ~/.ssh/authorized_keys
                chmod 600 ~/.ssh/authorized_keys
            """

            // Write env variables to a file
            pipeline.sh 'env | sort | sed -E \'s/^([^=]+)=(.*)$/export \\1="\\2"/\' > debug_env.sh'
            pipeline.sh "cat debug_env.sh"

            // The portConfig file is in the VM
            def portConfigFilePath = "/root/.ssh/ports_config.txt"

            pipeline.withCredentials([
                pipeline.usernamePassword(credentialsId: 'tensorrt_llm_infra_debug_vm_01_credentials', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD'),
                pipeline.string(credentialsId: 'DEBUG_HOST_NAME', variable: 'HOST_NAME')
                ]) {
                pipeline.sh "sshpass -p ${pipeline.PASSWORD} -v ssh ${pipeline.USERNAME}@${pipeline.HOST_NAME} ${pipeline.COMMON_SSH_OPTIONS} 'cat >> ~/.ssh/authorized_keys' < ~/.ssh/id_rsa.pub"
                pipeline.sh "ssh -v ${pipeline.USERNAME}@${pipeline.HOST_NAME} ${pipeline.COMMON_SSH_OPTIONS} 'echo \"\" > ~/.ssh/known_hosts && cat ~/.ssh/id_rsa.pub' >> ~/.ssh/authorized_keys"
                pipeline.sh "ssh -v ${pipeline.USERNAME}@${pipeline.HOST_NAME} ${pipeline.COMMON_SSH_OPTIONS} 'cat ~/.ssh/ports_config.txt' >> ${portConfigFilePath}"

                def (int userPort, int monitorPort) = pipeline.testDb.getSSHConnectionPorts(portConfigFilePath, stageName)
                if (userPort == 0) {
                    pipeline.echo "Fail to setup an interactive debug session and exit the debug mode."
                    pipeline.testFilter[(pipeline.runParameters.DEBUG_MODE)] = false
                    return
                }

                pipeline.sh "ssh -f ${pipeline.COMMON_SSH_OPTIONS} -L 1111:127.0.0.1:${monitorPort} -R ${monitorPort}:127.0.0.1:1112 -NR ${userPort}:localhost:22 ${pipeline.USERNAME}@${pipeline.HOST_NAME}"
                pipeline.sh "autossh -fNR ${userPort}:localhost:22 ${pipeline.USERNAME}@${pipeline.HOST_NAME}"
                pipeline.sh "ps aux | grep ssh"
                try {
                    pipeline.timeout(time: 2, unit: 'HOURS') {
                        pipeline.input message: "Pause 2 hours for Pre-Debug. Please type 'ssh root@${pipeline.HOST_NAME} -p ${userPort}' on the CLI to create the connection. Please press the button to proceed when you finish debugging."
                    }
                } catch (InterruptedException e) {
                    pipeline.echo "Pre-debug session was interrupted by user or timeout"
                    pipeline.currentBuild.result = 'ABORTED'
                    pipeline.error("Pipeline aborted during pre-debug session")
                } catch (Exception e) {
                    pipeline.echo "An error occurred during pre-debug session: ${e.message}"
                    pipeline.currentBuild.result = 'FAILURE'
                    pipeline.error("Error in pre-debug session: ${e.message}")
                }
            }

            pipeline.testFilter[(pipeline.runParameters.DEBUG_MODE)] = true
        }
    }

    pipeline.stage ("[${stageName}] Run Pytest")
    {
        def noRegularTests = false
        def noIsolateTests = false
        def rerunFailed = false
        def rerunInfraFailure = null
        def unrerunInfraFailure = null
        def hasUnrerunFailure = false
        def infraDryRun = pipeline.testSelection.isInfraDryRun()
        if (infraDryRun) {
            testList = pipeline.INFRA_DRY_RUN_TEST_CONTEXT
            splitId = 1
            splits = 1
        }

        // When useClusterDurations is set, use a per-cluster durations file keyed on
        // partition.clusterName (e.g. "oci-hsg", "dlcluster").  This lets each cluster
        // build its own timing baseline so sharding is not skewed by timings collected
        // on different hardware.  Falls back to the shared .test_durations when unset.
        def clusterDurationsArgs = []
        def clusterDurationsPath = ""
        String clusterNameForDurations = null
        if (useClusterDurations) {
            def partition = SlurmConfig.resolvePlatform(platform)
            def clusterKey = partition.clusterName.replaceAll('[^a-zA-Z0-9]', '_')
            clusterNameForDurations = clusterKey
            clusterDurationsPath = "${llmSrc}/tests/integration/defs/.test_durations_${clusterKey}"
            clusterDurationsArgs = ["--durations-path ${clusterDurationsPath}"]
        }

        def testDBList = pipeline.testDb.renderTestDB(pipeline, testList, llmSrc, stageName, null, clusterNameForDurations)
        def waivesFilePath = infraDryRun
            ? "${llmSrc}/infra_dry_run_waives.txt"
            : "${llmSrc}/tests/integration/test_lists/waives.txt"

        pipeline.testResults.prepareStageWaives(
            pipeline, llmSrc, stageName, waivesFilePath, postTag, infraDryRun)

        // Process shard test list and create separate files for regular and isolate tests
        def preprocessedLists = pipeline.testDb.processShardTestList(llmSrc, testDBList, splitId, splits, clusterDurationsPath)

        // Test Coverage
        def TRTLLM_WHL_PATH = pipeline.sh(returnStdout: true, script: "pip3 show tensorrt_llm | grep Location | cut -d ' ' -f 2").replaceAll("\\s","")
        pipeline.sh "echo ${TRTLLM_WHL_PATH}"
        def coverageConfigFile = "${llmSrc}/${stageName}/.coveragerc"
        pipeline.sh "mkdir -p ${llmSrc}/${stageName} && touch ${coverageConfigFile}"
        // CBTS stages render coveragerc.template; all other stages leave the rcfile empty (no coverage). Keep in sync with the SLURM branch.
        if (pipeline.testExecution.isCbtsStage(stageName)) {
            // K8s runner knows TRTLLM_WHL_PATH here, so all placeholders are substituted at controller time (no worker-side sed).
            pipeline.sh """
                mkdir -p ${pipeline.WORKSPACE}/${stageName}
                cp ${llmSrc}/jenkins/scripts/cbts/cbts/coverage/collection/coveragerc.template ${coverageConfigFile}
                sed -i \\
                    -e 's|@TRTLLM_WHEEL_PATH@|${TRTLLM_WHL_PATH}|g' \\
                    -e 's|@JOB_WORKSPACE@|${pipeline.WORKSPACE}/${stageName}|g' \\
                    -e 's|@STAGE_NAME@|${stageName}|g' \\
                    ${coverageConfigFile}
                cat ${coverageConfigFile}
            """
        }
        pipeline.testRuntime.echoNodeAndGpuInfo(pipeline, stageName)

        // Allocate a unique port section for this container to avoid port conflicts
        def hostNodeName = pipeline.testExecution.getHostNodeName()
        def containerPortStart = pipeline.testExecution.getStartingPortForHost(hostNodeName, stageName)
        def containerPortNum = pipeline.ciState.PORT_SECTION_SIZE
        def uploadPath = pipeline.UPLOAD_PATH.replaceFirst("sw-tensorrt-generic/llm-artifacts/LLM/", "")

        // Some clusters do not allow dmesg -C so we add || true
        // Temporarily disable to reduce the log size
        // sh 'if [ "$(id -u)" -eq 0 ]; then dmesg -C || true; fi'
        def extraArgs = [*clusterDurationsArgs]
        if (pipeline.ENABLE_UPLOAD_TEST_RESULTS && !pipeline.testFilter[(pipeline.runParameters.DETAILED_LOG)]) {
            extraArgs += [
                "--capture=fd",
                "--s3-upload-path=${uploadPath}/${stageName}",
                "--s3-upload-mode=deferred",
            ]
        }
        def pytestCommand = pipeline.testExecution.getPytestBaseCommandLine(
            llmSrc,
            stageName,
            waivesFilePath,
            "${pipeline.WORKSPACE}/${stageName}",
            coverageConfigFile,
            "",  // pytestUtil
            extraArgs,  // extraArgs
            containerPortStart,
            containerPortNum
        )

        // Only add --test-list if there are regular tests to run
        if (preprocessedLists.regularCount > 0) {
            pytestCommand += ["--test-list=${preprocessedLists.regular}"]
            pytestCommand += pipeline.testDb.getInfraDryRunPytestTargets(preprocessedLists.regular)
        }

        def containerPIP_LLM_LIB_PATH = pipeline.sh(script: "pip3 show tensorrt_llm | grep \"Location\" | awk -F\":\" '{ gsub(/ /, \"\", \$2); print \$2\"/tensorrt_llm/libs\"}'", returnStdout: true).replaceAll("\\s","")
        def containerLD_LIBRARY_PATH = pipeline.sh(script: "echo \${LD_LIBRARY_PATH}", returnStdout: true).replaceAll("\\s","")
        if (!containerLD_LIBRARY_PATH.contains("${containerPIP_LLM_LIB_PATH}:")) {
            pipeline.echo "Prepend ${containerPIP_LLM_LIB_PATH} into \${LD_LIBRARY_PATH}"
            containerLD_LIBRARY_PATH = "${containerPIP_LLM_LIB_PATH}:${containerLD_LIBRARY_PATH}"
        }
        containerLD_LIBRARY_PATH = containerLD_LIBRARY_PATH.replaceAll(':+$', '')
        def testEnvironment = ["LD_LIBRARY_PATH=${containerLD_LIBRARY_PATH}"]
        if (infraDryRun) {
            testEnvironment += ["stageName=${stageName}"]
        }
        pipeline.withEnv(testEnvironment) {
            pipeline.withCredentials([
                pipeline.string(credentialsId: 'TRTLLM_HF_TOKEN', variable: 'HF_TOKEN'),
                pipeline.string(credentialsId: 'svc_tensorrt-swift-stack-key', variable: 'S3_SECRET_KEY'),
                pipeline.string(credentialsId: 'llm_evaltool_repo_url', variable: 'EVALTOOL_REPO_URL')
            ]) {
                pipeline.sh "env | sort"
                try {
                    // Sentinel that the watcher polls to know pytest has exited
                    // (success or failure). Lives outside ${stageName}/ so the
                    // `rm -rf ${stageName}/` at pytest startup doesn't wipe it.
                    def pytestDoneFile = "${pipeline.WORKSPACE}/.pytest-done-${stageName}"
                    def progressTar = "results-${stageName}${postTag}-progress.tar.gz"
                    def progressUrl = "https://urm.nvidia.com/artifactory/${pipeline.UPLOAD_PATH}/test-results/${progressTar}"
                    pipeline.sh "rm -f ${pytestDoneFile}"
                    try {
                        if (preprocessedLists.regularCount > 0) {
                            // Run pytest in the foreground while a shell subshell
                            // watcher periodically tars + uploads results.xml. The
                            // watcher is a background subshell of the same `sh`
                            // step (not a Groovy parallel branch) so Blue Ocean
                            // renders the stage as a single box rather than a
                            // nested parallel split.
                            pipeline.withCredentials([pipeline.usernamePassword(
                                    credentialsId: 'urm-artifactory-creds',
                                    usernameVariable: 'ART_USER',
                                    passwordVariable: 'ART_PASS')]) {
                                pipeline.sh """
                                    set +e
                                    export STAGE_NAME='${stageName}'
                                    export PROGRESS_TAR='${progressTar}'
                                    export PROGRESS_URL='${progressUrl}'
                                    export TIMEOUT_XML_SCRIPT='${llmSrc}/jenkins/scripts/generate_timeout_xml.py'
                                    export POST_TAG='${postTag}'
                                    # ---- background watcher ----
                                    PROGRESS_DONE_FILE='${pytestDoneFile}' \\
                                    PROGRESS_INTERVAL=${pipeline.PROGRESS_UPLOAD_INTERVAL_SEC} \\
                                    LABEL_PREFIX='checkpoint' \\
                                    XML_PATH='${pipeline.WORKSPACE}/${stageName}/results.xml' \\
                                    bash '${llmSrc}/jenkins/scripts/progress_upload_watcher.sh' &
                                    WATCHER_PID=\$!

                                    # ---- foreground pytest ----
                                    rm -rf '${stageName}/'
                                    cd '${llmSrc}/tests/integration/defs'
                                    ${pytestCommand.join(" ")}
                                    rc=\$?

                                    touch '${pytestDoneFile}'
                                    wait \$WATCHER_PID 2>/dev/null || true

                                    # ---- immediate final snapshot of run 1 ----
                                    if [ -f '${pipeline.WORKSPACE}/${stageName}/results.xml' ]; then
                                        LABEL='run1 final snapshot' FINAL_SNAPSHOT=1 \\
                                        bash '${llmSrc}/jenkins/scripts/progress_upload_snapshot.sh' || true
                                    fi

                                    exit \$rc
                                """
                            }
                        } else {
                            pipeline.echo "No regular tests to run for stage ${stageName}"
                            noRegularTests = true
                            pipeline.sh "mkdir -p ${stageName}"
                            // Create an empty results.xml file for consistency
                            pipeline.sh """
                                echo '<?xml version="1.0" encoding="UTF-8"?>' > ${stageName}/results.xml
                                echo '<testsuites>' >> ${stageName}/results.xml
                                echo '<testsuite name="${stageName}" errors="0" failures="0" skipped="0" tests="0" time="0.0">' >> ${stageName}/results.xml
                                echo '</testsuite>' >> ${stageName}/results.xml
                                echo '</testsuites>' >> ${stageName}/results.xml
                            """
                            pipeline.sh "touch ${pytestDoneFile}"
                        }
                    } catch (InterruptedException e) {
                        throw e
                    } catch (Exception e) {
                        def rerunResult = pipeline.testResults.rerunFailedTests(
                            pipeline, stageName, llmSrc, pytestCommand, "results.xml", "regular", postTag, retryContext)
                        // Unconditional: a test can crash again during the rerun itself
                        // (isRerunFailed=true), and unfinished_test.txt is the only
                        // record of that crash. junit()'s "results*.xml" glob picks up
                        // results-timeout.xml directly, so this is what surfaces it.
                        def hadUnfinishedTests = pipeline.testResults.generateTimeoutTestResultXml(pipeline, stageName)
                        if (rerunResult.failed) {
                            pipeline.catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                                pipeline.error "Regular tests failed after rerun attempt"
                            }
                            rerunFailed = true
                            rerunInfraFailure = rerunResult.infraFailure ?: rerunInfraFailure
                        } else if (hadUnfinishedTests) {
                            // Rerun passed but the first run had a timeout: mark this
                            // stage FAILURE so "[${stageName}] Run Pytest" turns red,
                            // not just the enclosing parent stage.
                            pipeline.catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                                pipeline.error "Some tests terminated unexpectedly, please check the test report."
                            }
                        } else if (pipeline.testResults.fileHasContent("${pipeline.WORKSPACE}/${stageName}/rerun/regular/rerun_0.txt")) {
                            String scope = retryContext?.scope ?: InfraFailure.K8S
                            def classified = pipeline.infraRetry.classifyFailure(pipeline, e, scope, retryContext)
                            if (classified instanceof InfraFailure) {
                                unrerunInfraFailure = classified
                            }
                            // Failures that finished (not a timeout) but were never
                            // rerun because duration > 10 min and no known failure
                            // signature matched: results.xml still carries their
                            // original <failure>, but neither branch above fires for
                            // them, so without this the stage silently reports green.
                            pipeline.catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                                pipeline.error "Some tests failed and were not eligible for rerun (duration > 10 min, no matching failure signature), please check the test report."
                            }
                            hasUnrerunFailure = true
                        }
                    }

                    // Run the isolated tests if exists
                    if (preprocessedLists.isolateCount > 0) {
                        pipeline.stage ("[${stageName}] Run Pytest (Isolated)") {
                            pipeline.echo "There are ${preprocessedLists.isolateCount} isolated tests to run"
                            def isolatedResult = pipeline.testExecution.runIsolatedTests(
                                pipeline, preprocessedLists, pytestCommand, llmSrc, stageName, postTag, retryContext)
                            rerunFailed = isolatedResult.rerunFailed || rerunFailed
                            rerunInfraFailure = isolatedResult.rerunInfraFailure ?: rerunInfraFailure
                            unrerunInfraFailure = isolatedResult.unrerunInfraFailure ?: unrerunInfraFailure
                            hasUnrerunFailure = isolatedResult.hasUnrerunFailure || hasUnrerunFailure
                        }
                    } else {
                        pipeline.echo "No isolated tests to run for stage ${stageName}"
                        noIsolateTests = true
                    }

                    if (noRegularTests && noIsolateTests) {
                        pipeline.error "No tests were executed for stage ${stageName}, please check the test list and test-db rendering result."
                    }
                } finally {
                    if (pipeline.ENABLE_UPLOAD_TEST_RESULTS && !pipeline.testFilter[(pipeline.runParameters.DETAILED_LOG)]) {
                        pipeline.sh """
                            python3 ${llmSrc}/tests/test_common/s3_output.py \
                                --drain-spool "${pipeline.WORKSPACE}/${stageName}" || true
                        """
                    }
                }
            }

            // CBTS coverage liveness signal: log this stage's touch counts (no artifacts); never fails the stage (|| true).
            if (pipeline.testExecution.isCbtsStage(stageName)) {
                pipeline.sh """
                    cd ${pipeline.WORKSPACE}/${stageName} && \
                    PYTHONPATH=${llmSrc}/jenkins/scripts/cbts \
                    python3 -m cbts.command coverage collection pystart-report \
                        --glob '.cbtscov.${stageName}*' || true
                """
            }
        }

        // Generate comprehensive rerun report if any reruns occurred
        pipeline.stage ("Generate Report") {
            pipeline.timeout(time: 15, unit: 'MINUTES'){
                pipeline.testResults.generateRerunReport(stageName, llmSrc)
            }
        }

        // Unguarded error() on purpose -- catchError here would swallow the
        // throw that cacheErrorAndUploadResult relies on to fail the build.
        if (rerunFailed) {
            if (rerunInfraFailure != null) {
                throw rerunInfraFailure
            }
            pipeline.error "Some tests still failed after rerun attempts, please check the test report."
        }

        if (pipeline.fileExists("${stageName}/results-timeout.xml") || pipeline.testResults.generateTimeoutTestResultXml(pipeline, stageName)) {
            pipeline.error "Some tests terminated unexpectedly, please check the test report."
        }

        if (hasUnrerunFailure) {
            if (unrerunInfraFailure != null) {
                throw unrerunInfraFailure
            }
            pipeline.error "Some tests failed and were not eligible for rerun (duration > 10 min, no matching failure signature), please check the test report."
        }
    }

}

// Single-attempt test path. The infra-failure retry loop now lives one layer up
// in `runKubernetesPodWithInfraRetry` so that retries get a fresh K8s pod
// (recovering from ImagePullBackOff, pod eviction, OOMKilled, JNLP disconnect,
// etc.). Callers that want pod-level retry pass through `postTag` (already
// composed with an attempt tag by the helper) and `isFinalAttempt` (so this
// function's `cacheErrorAndUploadResult` can suppress synthetic stage-fail XML
// and junit() for intermediate retryable failures).
def runLLMTestlistOnPlatform(pipeline, platform, testList, config=pipeline.VANILLA_CONFIG, stageName="Undefined", splitId=1, splits=1, skipInstallWheel=false, postTag="", boolean isFinalAttempt=true, Map retryContext=null, boolean useClusterDurations=false)
{
    pipeline.testResults.cacheErrorAndUploadResult(pipeline, stageName, {
        // PMIx builds a singleton ID, singleton.{hostname}.{pid}, that must fit a
        // 50-byte buffer; our 63-character pod names overflow it and every
        // MpiPoolSession spawn fails. PMIX_HOSTNAME replaces only the name PMIx
        // sees, so the pod keeps its own for logging and for port sectioning in
        // getHostNodeName(). Kept out of runLLMTestlistOnPlatformImpl: the SLURM
        // path runs that on the compute node, where one shared name would break
        // locality. The ALLOW_RUN_AS_ROOT pair now comes from the image
        // (Dockerfile.multi); only the bare-metal sanity stages still set it.
        def testEnv = [
            "PMIX_HOSTNAME=mpi-node0",
        ]
        pipeline.withEnv(testEnv) {
            runLLMTestlistOnPlatformImpl(pipeline, platform, testList, config, stageName, splitId, splits, skipInstallWheel, postTag, useClusterDurations, retryContext)
        }
    }, {
        if (pipeline.testFilter[(pipeline.runParameters.DEBUG_MODE)]) {
            try {
                pipeline.timeout(time: 2, unit: 'HOURS') {
                    pipeline.input message: "Pause 2 hours for Post-Debug. Please press the button to proceed when you finish debugging."
                }
            } catch (InterruptedException e) {
                pipeline.echo "Post-debug session was interrupted by user or timeout"
                pipeline.currentBuild.result = 'ABORTED'
                pipeline.error("Pipeline aborted during post-debug session")
            } catch (Exception e) {
                pipeline.echo "An error occurred during post-debug session: ${e.message}"
                pipeline.currentBuild.result = 'FAILURE'
                pipeline.error("Error in post-debug session: ${e.message}")
            }
        }
        pipeline.testResults.prepareJenkinsTestResults(pipeline, config, stageName)
    }, false, postTag, isFinalAttempt, retryContext)
}

def executeLLMTestOnSlurm(pipeline, platform, testList, config=pipeline.VANILLA_CONFIG, stageName="Undefined", splitId=1, splits=1, skipInstallWheel=false, runner, String postTag="", boolean useClusterDurations=false, Map retryContext=null, Closure classifySlurmFailure=null)
{
    runner {
        pipeline.testResults.cacheErrorAndUploadResult(pipeline, stageName, {
            try {
                runLLMTestlistOnPlatformImpl(pipeline, platform, testList, config, stageName, splitId, splits, skipInstallWheel, postTag, useClusterDurations, retryContext)
            } catch (InterruptedException e) {
                throw e
            } catch (Exception e) {
                // Label the failure against the SLURM job's terminal state before
                // cacheErrorAndUploadResult decides junit suppression, so a monitor-
                // detected infra failure (e.g. slurm-job-still-running) suppresses this
                // attempt's results and a passing retry stays green. Pipeline aborts
                // (FlowInterruptedException) must not be relabeled.
                if (classifySlurmFailure == null || e.getClass().name.contains("FlowInterruptedException")) {
                    throw e
                }
                throw classifySlurmFailure(e)
            }
        }, {
            pipeline.testResults.prepareJenkinsTestResults(pipeline, config, stageName)
        }, false, postTag, true, retryContext)
    }
}

return this
