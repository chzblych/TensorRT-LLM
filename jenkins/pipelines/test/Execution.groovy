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
import com.nvidia.bloom.SlurmCluster
import trtllm.exceptions.InfraFailure

@Field def pipeline

def initialize(pipeline)
{
    this.pipeline = pipeline
    return this
}

def getShortenedJenkinsInstanceName() {
    return pipeline.trtllm_utils.getShortenedInstanceName(pipeline.env.JENKINS_URL ?: Jenkins.instance.rootUrl)
}

def isCbtsStage(String stageName) {
    // Pipeline-level eligibility (post-merge gate + kill switch) is decided in L0_MergeRequest.groovy and propagated via testFilter.
    if (!(pipeline.testFilter[(pipeline.CBTS_COVERAGE)] ?: false)) {
        return false
    }
    // PerfSanity stages skip coverage to avoid skewing measurements.
    if (stageName.contains("Perf")) {
        return false
    }
    // Skip stages with no product Python coverage: TensorRT (legacy) and CPP (gtest).
    if (stageName.contains("TensorRT") || stageName.contains("CPP")) {
        return false
    }
    // Phase 1: single-GPU only; multi-GPU / multi-node stages carry the "_GPUs" / "_Nodes" token.
    if (stageName.contains("_GPUs") || stageName.contains("_Nodes")) {
        return false
    }
    return !pipeline.CBTS_EXCLUDE_STAGES.contains(stageName)
}

def freezeCbtsCoverage(String stageDir) {
    // pytest_sessionfinish already waited for every subscriber to save and leave, so this only
    // settles the directory against a writer CBTS never knew about.
    pipeline.sh(
        returnStatus: true,
        script: """
            mkdir -p ${stageDir}
            prev=\$(stat -c %y ${stageDir} 2>/dev/null || echo unknown)
            for i in \$(seq 1 15); do
                sleep 1
                cur=\$(stat -c %y ${stageDir} 2>/dev/null || echo unknown)
                [ "\$cur" = "\$prev" ] && break
                prev=\$cur
            done
        """
    )
}

def runIsolatedTests(pipeline, preprocessedLists, testCmdLine, llmSrc, stageName, postTag="", Map retryContext=null) {
    // Run the isolated tests one by one to avoid any potential conflicts
    def isolateTestList = preprocessedLists.isolate
    def isolateTestLines = pipeline.readFile(file: isolateTestList).readLines()
    def rerunFailed = false
    def rerunInfraFailure = null
    def unrerunInfraFailure = null
    def hasUnrerunFailure = false

    for (int i = 0; i < isolateTestLines.size(); i++) {
        def isolateTestName = isolateTestLines[i].trim()
        // Create a temporary file for this single isolated test
        def singleTestFile = "${isolateTestList}_isolated_${i}.txt"
        pipeline.sh "echo '${isolateTestName}' > ${singleTestFile}"
        pipeline.sh "cat ${singleTestFile}"

        def isolateTestCmdLine = testCmdLine.findAll { cmd ->
            !cmd.contains("--test-list=") &&
            !cmd.contains("--test-prefix=") &&
            !cmd.contains("--csv=") &&
            !cmd.contains("--periodic-junit-xmlpath")
        }
        isolateTestCmdLine += ["--test-list=${singleTestFile}"]
        isolateTestCmdLine += ["--test-prefix=${stageName}"]
        isolateTestCmdLine += ["--csv=${pipeline.WORKSPACE}/${stageName}/report_isolated_${i}.csv"]
        isolateTestCmdLine += ["--periodic-junit-xmlpath ${pipeline.WORKSPACE}/${stageName}/results_isolated_${i}.xml"]

        try {
            pipeline.sh """
                cd ${llmSrc}/tests/integration/defs && \
                ${isolateTestCmdLine.join(" ")}
            """
        } catch (InterruptedException e) {
            throw e
        } catch (Exception e) {
            def rerunResult = pipeline.testResults.rerunFailedTests(
                pipeline, stageName, llmSrc, isolateTestCmdLine, "results_isolated_${i}.xml", "isolated_${i}", postTag, retryContext)
            if (rerunResult.failed) {
                pipeline.catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                    pipeline.error "Isolated test ${i} (${isolateTestName}) failed after rerun attempt"
                }
                // Mark that at least one isolated test failed, but continue processing other tests
                rerunFailed = true
                rerunInfraFailure = rerunResult.infraFailure ?: rerunInfraFailure
            } else {
                // Strip trailing " TIMEOUT (N)" / "TIMEOUT(N)" / " ISOLATION" turtle
                // directives the same way generate_duration.py's _TURTLE_DIRECTIVE_RE does.
                def bareTestName = isolateTestName.replaceAll(/(?:\s+(?:TIMEOUT\s*\(\d+\)|ISOLATION))+\s*$/, '').trim()
                def unfinishedTestFile = "${pipeline.WORKSPACE}/${stageName}/unfinished_test.txt"
                // conftest.py prefixes nodeids with --test-prefix=${stageName}; match in
                // Groovy, not a shell grep, so a quote in a parametrized id can't break it.
                def isTestUnfinished = pipeline.fileExists(unfinishedTestFile) &&
                    pipeline.readFile(unfinishedTestFile).readLines().collect { it.trim() }.contains("${stageName}/${bareTestName}".toString())
                if (isTestUnfinished) {
                    // Record this crash as a JUnit <testcase> like the regular-test
                    // path does. hasUnrerunFailure stays untouched here: it drives
                    // the duration/no-signature message below, which doesn't apply.
                    pipeline.testResults.generateTimeoutTestResultXml(pipeline, stageName)
                    pipeline.catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                        pipeline.error "Isolated test ${i} (${isolateTestName}) terminated unexpectedly, please check the test report."
                    }
                } else if (pipeline.testResults.fileHasContent("${pipeline.WORKSPACE}/${stageName}/rerun/isolated_${i}/rerun_0.txt")) {
                    String scope = retryContext?.scope ?: InfraFailure.K8S
                    def classified = pipeline.infraRetry.classifyFailure(pipeline, e, scope, retryContext)
                    if (classified instanceof InfraFailure) {
                        unrerunInfraFailure = classified
                    }
                    // Same duration/no-signature gap as the regular-test path: this
                    // finished but failed, and was never actually rerun, so
                    // results_isolated_${i}.xml still carries the original
                    // <failure> with nothing here to flag it.
                    pipeline.catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                        pipeline.error "Isolated test ${i} (${isolateTestName}) failed and was not eligible for rerun (duration > 10 min, no matching failure signature)"
                    }
                    hasUnrerunFailure = true
                }
            }
        } finally {
            // Clean up the temporary test file
            pipeline.sh "rm -f ${singleTestFile}"
        }
    }

    // After processing all isolated tests, set stage failure if any test failed
    if (rerunFailed) {
        pipeline.catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
            pipeline.error "One or more isolated tests failed after rerun attempts"
        }
    }
    if (hasUnrerunFailure) {
        pipeline.catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
            pipeline.error "One or more isolated tests failed and were not eligible for rerun, please check the test report."
        }
    }

    return [
        rerunFailed: rerunFailed,
        rerunInfraFailure: rerunInfraFailure,
        unrerunInfraFailure: unrerunInfraFailure,
        hasUnrerunFailure: hasUnrerunFailure,
    ]
}

def executeLLMTestOnSlurm(pipeline, platform, testList, config=pipeline.VANILLA_CONFIG, stageName="Undefined", splitId=1, splits=1, skipInstallWheel=false, runner, String postTag="", boolean useClusterDurations=false, Map retryContext=null, Closure classifySlurmFailure=null)
{
    runner {
        // TODO: refactor the finallyRunner to reuse within slurm or nonslurm job.
        pipeline.testResults.cacheErrorAndUploadResult(pipeline, stageName, {
            try {
                pipeline.testPlatform.runLLMTestlistOnPlatformImpl(pipeline, platform, testList, config, stageName, splitId, splits, skipInstallWheel, postTag, useClusterDurations, retryContext)
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
            // If the execution test list is null, remove the test result xml
            pipeline.sh """
                ls -al ${stageName}/
                if ! grep -q '<testcase' ${stageName}/results.xml; then
                    rm ${stageName}/results.xml || true
                fi
            """
            def llmPath = pipeline.sh (script: "realpath .", returnStdout: true).trim()
            def llmSrc = "${llmPath}/${pipeline.LLM_ROOT}${config}/TensorRT-LLM/src"
            // CPP tests will generate test result in ${llmSrc}/cpp/build_backup/, move these files to job result folder
            pipeline.sh "ls -al ${llmSrc}/cpp/build_backup/ || true"
            pipeline.sh "ls -al ${llmSrc}/cpp/build/ || true"
            // Sed for CPP test result
            pipeline.sh "cd ${llmSrc}/cpp/build_backup/ && sed -i 's/\" classname=\"/\" classname=\"${stageName}./g' *.xml || true"
            pipeline.sh "cd ${llmSrc}/cpp/build_backup/ && sed -i 's/testsuite name=\"[^\"]*\"/testsuite name=\"${stageName}\"/g' *.xml || true"
            // Sed for Pytest result
            pipeline.sh "cd ${stageName} && sed -i 's/testsuite name=\"pytest\"/testsuite name=\"${stageName}\"/g' *.xml || true"
            // Copy CPP test result
            pipeline.sh "cp ${llmSrc}/cpp/build_backup/*.xml ${stageName} || true"
            pipeline.sh "ls -al ${stageName}/"
        }, false, postTag, true, retryContext)
    }
}

// End of Methods to run Slurm job with Jenkins Agent

def getNodeArgs(int nodeCount, int gpuCount, boolean setSegment = false) {
    int gpusPerNode = ((gpuCount / nodeCount) as BigDecimal).setScale(0, BigDecimal.ROUND_CEILING).intValue()
    def args = nodeCount == 1 ? [
        "--nodes=${nodeCount}",
        "--gpus-per-node=${gpuCount}"
    ] : [
        "--nodes=${nodeCount}",
        "--ntasks=${gpuCount}",
        "--ntasks-per-node=${gpusPerNode}",
        "--gpus-per-node=${gpusPerNode}",
    ]
    if (setSegment && gpuCount > 1) {
        args += ["--segment=${nodeCount}"]
    }
    return args
}

def getPytestBaseCommandLine(
    String llmSrc,
    String stageName,
    String waivesFilePath,
    String outputPath,
    String coverageConfigFile,
    String pytestUtil = "",
    List<String> extraArgs = [],
    int containerPortStart = 0,
    int containerPortNum = 0,
    String clusterName = ""
) {
    def extraInternalEnv = ""
    def pytestTestTimeout = "3600"
    def cbtsMode = isCbtsStage(stageName)

    // TRT uses half of the host logic cores for engine building which is bad for multi-GPU machines.
    extraInternalEnv = "__LUNOWUD=\"-thread_pool_size=${pipeline.TESTER_CORES}\""
    // CPP test execution is timing out easily, so we always override its internal timeout to the same value as pytest
    extraInternalEnv += " CPP_TEST_TIMEOUT_OVERRIDDEN=${pytestTestTimeout}"
    // Enable NCCL debug information for multi-GPU tests
    extraInternalEnv += " NCCL_DEBUG=INFO"
    // GB200 stages run on multiple clusters; only gcp-iad needs the gIB NCCL network plugin over IB.
    if (stageName.startsWith("GB200") && clusterName == "gcp-iad") {
        extraInternalEnv += " NCCL_IB_DISABLE=0 NCCL_NET=gIB NCCL_PROFILER_PLUGIN=none"
    }
    // Pass stage name to perf sanity tests for OpenSearch tracking
    extraInternalEnv += " stageName=${stageName}"
    // Let the test fixtures install optional media deps (opencv / av / ffmpeg).
    extraInternalEnv += " TRTLLM_AUTO_INSTALL_MEDIA_DEPS=1"
    // Persist the AutoTuner profiling cache to a CONTAINER-LOCAL, volatile path so
    // that repeated tactic profiling is reused across testcases within one stage.
    // /tmp lives on the container overlay (srun --no-container-mount-home / fresh
    // pod), so the cache is never written to the host and vanishes when the stage
    // container is destroyed. Never point this at a bind-mounted / shared path:
    // the AutoTuner cache uses fcntl.lockf, which is unreliable over NFS.
    extraInternalEnv += " TLLM_AUTOTUNER_CACHE_PATH=/tmp/trtllm_autotuner_cache/autotuner_cache.json"
    // CBTS stages put cbts_plugin/sitecustomize (bare-importable guest hooks) plus the cbts
    // package itself on PYTHONPATH (via ${VAR:-} for set -u safety), plus the config env vars
    // sitecustomize.py reads in subprocesses. The context channel's address is created and
    // exported by the plugin itself.
    if (cbtsMode) {
        def cbtsDir = "${llmSrc}/jenkins/scripts/cbts"
        def cbtsInjectorsDir = "${cbtsDir}/cbts_injectors"
        extraInternalEnv += " PYTHONPATH=${cbtsInjectorsDir}:${cbtsDir}:\${PYTHONPATH:-}"
        extraInternalEnv += " CBTS_COVERAGE_CONFIG=${coverageConfigFile}"
        // Names the stage the coverage is attributed to, and tells sitecustomize.py what this
        // process is. sitecustomize.py consumes the role, so the pool workers and servers this
        // pytest spawns start unlabelled and are treated as product processes.
        extraInternalEnv += " CBTS_STAGE=${stageName}"
        extraInternalEnv += " CBTS_PROCESS_ROLE=outer_pytest"
    }

    // Container port allocation environment variables for avoiding port conflicts
    def portEnvVars = ""
    if (containerPortStart > 0 && containerPortNum > 0) {
        portEnvVars = "CONTAINER_PORT_START=${containerPortStart} CONTAINER_PORT_NUM=${containerPortNum}"
    }

    def jUnitLogging = "out-err"
    if (pipeline.ENABLE_UPLOAD_TEST_RESULTS) {
        jUnitLogging = "all"
    }

    def testCmdLine = [
        "LLM_ROOT=${llmSrc}",
        "LLM_BACKEND_ROOT=${llmSrc}/triton_backend",
        "LLM_MODELS_ROOT=${pipeline.MODEL_CACHE_DIR}",
        "MODEL_CACHE_DIR=${pipeline.MODEL_CACHE_DIR}",
        "COLUMNS=300",
        extraInternalEnv,
        portEnvVars,
        pytestUtil,
        "pytest",
        "-vv",
        pipeline.testFilter[(pipeline.DETAILED_LOG)] ? "-s" : "",
        "--timeout-method=thread",
        "--apply-test-list-correction",
        "--timeout=${pytestTestTimeout}",
        "--rootdir ${llmSrc}/tests/integration/defs",
        "--test-prefix=${stageName}",
        "--waives-file=${waivesFilePath}",
        "--output-dir=${outputPath}/",
        "--csv=${outputPath}/report.csv",
        "-o junit_logging=${jUnitLogging}",
        // Coverage capture: only CBTS (post-merge) stages instrument, via cbts_plugin / sitecustomize.
        cbtsMode ? "-p cbts_plugin" : "",
        "--periodic-junit",
        "--periodic-junit-xmlpath ${outputPath}/results.xml",
        "--periodic-batch-size=1",
        "--periodic-save-unfinished-test",
        // Reruns redirect --periodic-junit-xmlpath elsewhere but inherit this
        // flag unchanged, so every attempt shares one unfinished_test.txt.
        "--periodic-unfinished-test-path=${outputPath}/unfinished_test.txt",
        "--periodic-hang-traceback",
    ]

    if (stageName.contains("-Ray-")) {
        testCmdLine += ["--run-ray"]
    }
    // PackageSanityCheck and the NGC image sanity tests validate an alternative environment against
    // basic cases, so the normal cpu_only / not-cpu_only split does not apply.
    def unittestMarkExpr = (stageName.startsWith("CPU-")) ? "cpu_only" :
        (stageName =~ /PackageSanityCheck|-Sanity-Test/) ? "" : "not cpu_only"
    testCmdLine += ["--unittest-markexpr='${unittestMarkExpr}'"]
    if (extraArgs) {
        testCmdLine += extraArgs
    }
    return testCmdLine as String[]
}

def getMountListForSlurmTest(SlurmCluster cluster, boolean useSbatch = false)
{
    def mounts = []

    // mounts for SLURM job submission and logs
    if (useSbatch) {
        mounts += [
            "/home/svc_tensorrt/bloom/scripts",
        ]
    } else {
        mounts += [
            "/home/svc_tensorrt/bloom/scripts",
            "/home/svc_tensorrt/slurm-logs",
        ]
    }

    // data/cache mounts
    if (cluster.containerRuntime.toString() == "DOCKER") {
        mounts += [
            "/home/scratch.trt_llm_data_ci:/scratch.trt_llm_data:ro",
        ]
    } else if (cluster.containerRuntime.toString() == "ENROOT") {
        if (!cluster.scratchPath) {
            throw new Exception("Scratch path is not set for cluster: ${cluster.name}")
        }
        mounts += [
            "${cluster.scratchPath}:/scratch.trt_llm_data:ro",
        ]
    } else {
        throw new Exception("Unsupported container runtime: ${cluster.containerRuntime}")
    }

    // TODO: Add mounts for different cache directories like pip, triton, etc.

    return mounts
}

/**
 * Allocates and returns a starting port section for the given host node.
 * This function is thread-safe and ensures each stage running on the same
 * host node gets a unique port range to avoid conflicts.
 *
 * @param hostNodeName The HOST_NODE_NAME of the node running the stage
 * @param stageName Optional stage name for logging purposes
 * @return The starting port number for this stage's port section
 */
def getStartingPortForHost(String hostNodeName, String stageName = "") {
    pipeline.lock(resource: 'globalstate-hostNodePortMap') {
        def startingPort = pipeline.ciState.hostNodePortMap.get(hostNodeName, pipeline.ciState.BASE_PORT)

        // Store the next available starting port for this host
        def nextPort = startingPort + pipeline.ciState.PORT_SECTION_SIZE

        // Wrap around if we exceed MAX_PORT
        if (nextPort > pipeline.ciState.MAX_PORT) {
            nextPort = pipeline.ciState.BASE_PORT
        }

        pipeline.ciState.hostNodePortMap[hostNodeName] = nextPort

        return startingPort
    }
}

/**
 * Gets the HOST_NODE_NAME from the current environment.
 * Falls back to hostname if HOST_NODE_NAME is not set.
 *
 * The name must stay distinct per pod: getStartingPortForHost() hands out a
 * private port section per name.
 *
 * @return The host node name
 */
def getHostNodeName() {
    return pipeline.sh(script: '''
        if [ -n "$HOST_NODE_NAME" ]; then
            echo "$HOST_NODE_NAME"
        else
            hostname -f || hostname
        fi
    ''', returnStdout: true).trim()
}

return this
