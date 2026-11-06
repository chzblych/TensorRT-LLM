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
import trtllm.exceptions.InfraFailure
import trtllm.exceptions.PipelineInterruption
import trtllm.exceptions.UserFailure

@Field def pipeline

def initialize(pipeline)
{
    this.@pipeline = pipeline
    return this
}

def echoNodeAndGpuInfo(pipeline, stageName)
{
    String hostNodeName = pipeline.sh(script: '''
        if [ -n "$HOST_NODE_NAME" ]; then
            echo "$HOST_NODE_NAME"
        else
            hostname -f || hostname
        fi
    ''', returnStdout: true).trim()

    String gpuUuids = pipeline.sh(script: "nvidia-smi -q | grep \"GPU UUID\" | awk '{print \$4}' | tr '\n' ',' || true", returnStdout: true)
    pipeline.echo "HOST_NODE_NAME = ${hostNodeName} ; GPU_UUIDS = ${gpuUuids} ; STAGE_NAME = ${stageName}"
}

def runLLMDocBuild(pipeline, config)
{
    // Step 1: cloning source code
    pipeline.sh "pwd && ls -alh"
    pipeline.sh "env | sort"
    // allow to checkout from forked repo, svc_tensorrt needs to have access to the repo, otherwise clone will fail
    pipeline.trtllm_utils.checkoutSource(pipeline.LLM_REPO, pipeline.env.gitlabCommit, pipeline.LLM_ROOT, true, true)
    pipeline.sh "mkdir TensorRT-LLM"
    pipeline.sh "cp -r ${pipeline.LLM_ROOT}/ TensorRT-LLM/src/"
    pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "git config --global --add safe.directory \"*\"")

    def llmPath = pipeline.sh (script: "realpath .", returnStdout: true).trim()
    def llmSrc = "${llmPath}/TensorRT-LLM/src"

    // Step 2: download TRT-LLM tarfile
    def tarName = pipeline.BUILD_CONFIGS[config][pipeline.TARNAME]
    pipeline.testBuild.fetchBuildArtifact(pipeline, "${pipeline.ARTIFACT_PATH}/${tarName}", "${llmPath}/${tarName}")
    pipeline.sh "cd ${llmPath} && tar -zxf ${tarName}"
    // install python package
    pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "cd ${llmSrc} && pip3 install -r requirements-dev.txt")
    pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "cd ${llmPath} && pip3 install --force-reinstall --no-deps TensorRT-LLM/tensorrt_llm-*.whl")

    // Step 3: build doc
    pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "apt-get update && apt-get install -y doxygen python3-pip graphviz")

    def containerPATH = pipeline.sh(script: "echo \${PATH}", returnStdout: true).replaceAll("\\s", "")
    if (!containerPATH.contains("/usr/local/bin:")) {
        pipeline.echo "Prepend /usr/local/bin into \${PATH}"
        containerPATH = "/usr/local/bin:${containerPATH}"
    }
    containerPATH = containerPATH.replaceAll(':+$', '')
    pipeline.withEnv(["PATH=${containerPATH}"]) {
        pipeline.sh "env | sort"
        pipeline.sh "rm -rf ${pipeline.LLM_ROOT}/docs/build"
        pipeline.trtllm_utils.llmExecStepWithRetry(
            pipeline,
            script: """
                cd ${pipeline.LLM_ROOT}/docs && \
                pip3 install -r requirements.txt && \
                pip3 install git+https://github.com/sphinx-doc/sphinx.git@v7.4.7 && \
                doxygen Doxygen && \
                export TRTLLM_DOCS_REQUIRE_IMPORT=1 && \
                export LD_LIBRARY_PATH=\$(python3 ../scripts/cuda_driver_stub.py) && \
                make html && \
                cd build/html && \
                touch .nojekyll
            """
        )
    }

    pipeline.echo "Upload built html."
    pipeline.sh "tar -czvf doc-html-preview.tar.gz  ${pipeline.LLM_ROOT}/docs/build/html"
    pipeline.trtllm_utils.uploadArtifacts(
        "doc-html-preview.tar.gz",
        "${pipeline.UPLOAD_PATH}/test-results/"
    )
}

def runLLMAgentFlowTest(pipeline, stageName)
{
    // agent-flow is a self-contained, pure-CPU sub-project: it needs neither a
    // GPU nor the TRT-LLM wheel, only its own dependencies (pure-Python wheels
    // declared in agent-flow/pyproject.toml).
    pipeline.sh "pwd && ls -alh"
    pipeline.trtllm_utils.checkoutSource(pipeline.LLM_REPO, pipeline.env.gitlabCommit, pipeline.LLM_ROOT, false, true)
    pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "git config --global --add safe.directory \"*\"")

    def agentFlowRoot = "${pipeline.LLM_ROOT}/agent-flow"

    // Install agent-flow with its test extras (pytest, pytest-asyncio) and the
    // runtime deps from pyproject.toml (claude-agent-sdk, openai-codex, ...).
    // These resolve from the container's default PyPI mirror.
    pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "cd ${agentFlowRoot} && pip3 install -e \".[test]\"")

    if (pipeline.testSelection.isInfraDryRun()) {
        // Keep the normal environment and reporting path, but replace product tests.
        pipeline.sh """
            rm -rf "${agentFlowRoot}/tests" && \
            mkdir -p "${agentFlowRoot}/tests" && \
            printf '%s\\n' \
                'def test_infra_dry_run_placeholder():' \
                '    pass' \
                > "${agentFlowRoot}/tests/test_infra_dry_run_placeholder.py"
        """
    }

    pipeline.sh "mkdir -p ${pipeline.WORKSPACE}/${stageName}"

    // test_workflow_entrypoint_modules_run_without_import_warnings is deselected
    // because importing agent_flow.workflows.modeling_bringup runs a live skill
    // probe (a real Claude/Codex session) at module-import time, which stalls in
    // headless CI. This is a known agent-flow bug slated for an upstream fix;
    // remove the --deselect once that lands. The outer `timeout` guards against
    // the same probe stalling the whole stage in-process.
    def deselect = "--deselect tests/test_examples.py::test_workflow_entrypoint_modules_run_without_import_warnings"
    pipeline.sh """
        cd ${agentFlowRoot} && \
        timeout 1200 python3 -m pytest tests -v ${deselect} \
            --junitxml=${pipeline.WORKSPACE}/${stageName}/results.xml
    """

    // Rename the JUnit testsuite from pytest's default "pytest" to the stage
    // name so CI reporting attributes these cases to this stage (mirrors the
    // unittest path in runLLMTestlistOnPlatformImpl).
    pipeline.sh "cd ${pipeline.WORKSPACE}/${stageName} && sed -i 's/testsuite name=\"pytest\"/testsuite name=\"${stageName}\"/g' results.xml || true"
}

// TODO: Update existing functions to use runInDockerOnNodeMultiStage and get rid of runInDockerOnNode
def runInDockerOnNodeMultiStage(image, label, dockerArgs, partitionTimeout, needToDeleteDir=true)
{
    return {
        runner -> this.@pipeline.node(label) {
            try {
                if (needToDeleteDir) {
                    this.@pipeline.deleteDir()
                }
                this.@pipeline.stage('Pull Docker Image') {
                    this.@pipeline.withCredentials([this.@pipeline.usernamePassword(
                        credentialsId: this.@pipeline.ARTIFACTORY_CREDENTIALS_ID,
                        usernameVariable: 'USERNAME',
                        passwordVariable: 'PASSWORD'
                    )]) {
                        this.@pipeline.sh """
                            set +x
                            echo "\$PASSWORD" | docker login ${this.@pipeline.ARTIFACTORY_DOCKER_HOST} -u "\$USERNAME" --password-stdin
                        """
                    }
                    this.@pipeline.docker.image(image).pull()
                }
                // We submit the Slurm job with the Slurm partition's time spec.
                // Minus 10 minutes to avoid the Slurm job being stopped earlier.
                this.@pipeline.timeout(time: partitionTimeout - 10, unit: 'MINUTES') {
                    this.@pipeline.docker.image(image).inside(dockerArgs) {
                        this.@pipeline.trtllm_utils.setupGithubFetchAuth()
                        runner()
                    }
                }
            } catch (Exception e) {
                if (e.getMessage()?.contains("Failed to kill container")) {
                    this.@pipeline.echo "Known benign error ignored: ${e.getMessage()}"
                } else {
                    throw e // Re-throw if it's a different Exception
                }
            }
        }
    }
}

def runInEnrootOnNode(label, partitionTimeout)
{
    return {
        runner -> this.@pipeline.node(label) {
            this.@pipeline.trtllm_utils.setupGithubFetchAuth()
            // We submit the Slurm job with the Slurm partition's time spec.
            // Minus 10 minutes to avoid the Slurm job being stopped earlier.
            this.@pipeline.timeout(time: partitionTimeout - 10, unit: 'MINUTES') {
                runner()
            }
        }
    }
}

def runInKubernetes(pipeline, podSpec, containerName)
{
    return {
        stageName, runner -> pipeline.stage(stageName) {
            pipeline.trtllm_utils.launchKubernetesPod(pipeline, podSpec, containerName) {
                echoNodeAndGpuInfo(pipeline, stageName)
                runner()
            }
        }
    }
}

// Retry-aware K8s pod launcher. Mirrors the SLURM retry loop's classification
// (classify(ex, InfraFailure.K8S)), budget (K8S_INFRA_RETRY_MAX), and backoff,
// but operates at the pod-launch level so transient pod failures
// (ImagePullBackOff, eviction, OOMKilled, JNLP disconnect, node NotReady) get
// a fresh pod on each retry rather than
// retrying the test body inside a dying pod.
//
// `runner` is invoked with `(attemptTag, isFinalAttempt, retryContext)`. Callers append
// `attemptTag` to any postTag they pass into cacheErrorAndUploadResult so each
// attempt's tar and ensureStageResultNotUploaded guard key are unique;
// `isFinalAttempt` lets cacheErrorAndUploadResult suppress synthetic stage-fail
// XML and junit() for intermediate retryable failures so the per-build test
// report isn't poisoned by transient infra blips that the next attempt
// recovers from.
//
// Options (Map; all optional):
//   singleAttempt (bool, default false) skip the retry loop entirely; run
//                                       once with attemptTag="" and
//                                       isFinalAttempt=true. Used by SLURM
//                                       dispatcher closures so the outer
//                                       pod-level retry does not nest on top
//                                       of the inner SLURM retry and inflate
//                                       the worst-case attempt budget.
def runKubernetesPodWithInfraRetry(Map opts = [:], pipeline, podSpec, containerName, String stageName, Closure runner)
{
    boolean singleAttempt = opts.singleAttempt ?: false
    // SLURM dispatcher pods opt in to off-pod resource reconciliation: on a
    // mid-run pod death their SLURM job / Jenkins node would otherwise leak.
    boolean slurmDispatcher = opts.slurmDispatcher ?: false
    // Per-stage override of the K8s pod-level infra-retry budget (opts.infraRetryMax,
    // 0 = no retry) so resource-scarce pools can cap or disable stage retries. Applies
    // to the outer test-pod retry loop below; the singleAttempt launch-retry keeps the
    // global (relaunching a dispatcher pod doesn't tax the scarce test hardware). SLURM
    // dispatchers carry the same opt through to their inner SLURM retry via retryContext.
    int k8sInfraRetryMax = pipeline.infraRetry.resolveInfraRetryMax(InfraFailure.K8S, opts.infraRetryMax as Integer)

    // DEBUG_MODE preserves the existing 2-hour-input human-inspection workflow
    // inside runLLMTestlistOnPlatform's finallyRunner: a single attempt only.
    if (pipeline.testFilter[(pipeline.runParameters.DEBUG_MODE)]) {
        pipeline.trtllm_utils.launchKubernetesPod(pipeline, podSpec, containerName, { runner("", true, null) })
        return
    }

    // singleAttempt opts out of the outer pod-level *execution* retry: the inner
    // retry (e.g. SLURM) owns re-runs, and re-running after work has started
    // could double-submit. But a failure *before* the runner body begins is a
    // pod/agent launch failure (the JNLP agent never came online, the node was
    // pulled during provisioning, etc.): nothing has been dispatched yet, so
    // relaunching a fresh pod -- steered off the bad node by host-node avoidance
    // -- is safe and is the only way these recover. Retry launch failures only;
    // once the runner starts (runnerStarted), honor singleAttempt and rethrow.
    if (singleAttempt) {
        def avoidedKubernetesHostNodes = []
        def launchAttempt = 0
        while (true) {
            launchAttempt++
            Map attemptPlacementContext = [runnerStarted: false]
            // Declared above the try so the catch can reference it when reconciling
            // a dispatcher-pod death: Groovy block-scopes a try-local `def`, so a
            // declaration inside the try is not visible in the catch (it would
            // resolve as an undefined property and throw MissingPropertyException).
            def attemptPodSpec = null
            try {
                if (launchAttempt > 1 && !avoidedKubernetesHostNodes.isEmpty()) {
                    pipeline.echo "[INFRA-RETRY] ${stageName}: relaunching pod (attempt ${launchAttempt}), avoiding prior host node(s): ${avoidedKubernetesHostNodes.join(', ')}"
                }
                attemptPodSpec = pipeline.trtllm_utils.withKubernetesHostNodeExclusion(podSpec, avoidedKubernetesHostNodes)
                if (slurmDispatcher) {
                    // Record the dispatcher pod spec so the off-pod finalizer/sweep can
                    // launch a fresh cleanup pod if this pod dies mid-run.
                    pipeline.slurmResources.registerSlurmResource(stageName, [podSpec: attemptPodSpec, containerName: containerName])
                }
                pipeline.trtllm_utils.launchKubernetesPodWithPlacement(pipeline, attemptPodSpec, containerName, attemptPlacementContext, {
                    attemptPlacementContext.runnerStarted = true
                    runner("", true, null)
                })
                return
            } catch (InterruptedException e) {
                throw e
            } catch (Exception e) {
                // Once the runner has started, this is an execution failure the
                // inner retry owns -- honor singleAttempt and do not re-run. But if
                // the dispatcher pod itself died mid-run, its in-pod cleanup could not
                // reach the login node, leaking the SLURM job / Jenkins node. We are
                // back on the parent context here, so reconcile them from a fresh
                // cleanup pod before failing closed.
                if (attemptPlacementContext.runnerStarted) {
                    if (slurmDispatcher && pipeline.infraRetry.isDispatcherPodFailure(e)) {
                        // Pass this attempt's pod spec explicitly so the finalizer
                        // never depends on the registry entry's podSpec surviving a
                        // prior attempt's deregister.
                        pipeline.slurmResources.finalizeOrphanedSlurmResource(pipeline, stageName, attemptPodSpec)
                    }
                    throw e
                }
                def c = pipeline.infraRetry.classifyFailure(pipeline, e, InfraFailure.K8S)
                if (c instanceof PipelineInterruption) throw e
                if (!(c instanceof InfraFailure)) {
                    // UserFailure -> don't retry, but leave a trace: a pod/agent
                    // launch exception missing from PATTERN_CATALOG (e.g. a
                    // pod-scheduling timeout) lands here and would otherwise
                    // vanish with no log output at all.
                    pipeline.echo "[INFRA-RETRY] ${stageName}: pod/agent launch failed before execution with no infra pattern matched (classified as user failure); not retrying. Exception: ${e.toString()}"
                    throw e
                }

                pipeline.infraRetry.rememberAvoidedKubernetesHostNodes(avoidedKubernetesHostNodes, attemptPlacementContext.lastHostNode, stageName)

                if (launchAttempt > pipeline.K8S_INFRA_RETRY_MAX) {
                    pipeline.echo "[INFRA-RETRY] ${stageName}: pod launch failed before execution (${c.detectedPattern}), " +
                         "but max launch retries (${pipeline.K8S_INFRA_RETRY_MAX}) exhausted after ${launchAttempt} attempts. Failing."
                    throw c
                }
                if (!pipeline.infraRetry.hasBudgetForInfraRetry(pipeline, stageName, InfraFailure.K8S, c, launchAttempt, pipeline.K8S_INFRA_RETRY_MAX, 60L * 1000L, true)) {
                    pipeline.echo "[INFRA-RETRY] ${stageName}: pod launch failed (${c.detectedPattern}) is retryable, " +
                         "but remaining CI timeout budget is too small for a relaunch. Failing without retry."
                    throw c
                }

                pipeline.echo "[INFRA-RETRY] ${stageName}: pod/agent launch failed before execution on attempt ${launchAttempt}: ${c.detectedPattern}"
                pipeline.echo "[INFRA-RETRY] ${stageName}: Exception: ${e.toString()}"
                pipeline.echo "[INFRA-RETRY] ${stageName}: Will relaunch (attempt ${launchAttempt + 1} of ${pipeline.K8S_INFRA_RETRY_MAX + 1}) after 60s cooldown."
                pipeline.sleep(60)
            }
        }
    }

    def attempt = 0
    // Severity of the previous attempt's classification, or null on first attempt.
    // Used to compute isFinalAttempt for the next attempt: a PERSISTENT prior
    // failure caps the budget at 1 retry (attempt 2 is final), so the next
    // attempt must not suppress synthetic stage-fail XML / junit().
    def lastSeverity = null
    def avoidedKubernetesHostNodes = []
    while (true) {
        attempt++
        Map attemptPlacementContext = [:]
        // Shared by result suppression and the retry decision for this attempt only.
        Map retryContext = [
            scope: InfraFailure.K8S,
            stageName: stageName,
            attempt: attempt,
            backoffMs: 60L * 1000L,
            infraRetryMax: opts.infraRetryMax,
            excludedKubernetesHostNodes: avoidedKubernetesHostNodes.collect(),
        ]
        try {
            if (attempt > 1) {
                pipeline.echo "[INFRA-RETRY] ${stageName}: Starting attempt ${attempt} of ${k8sInfraRetryMax + 1}"
                if (!avoidedKubernetesHostNodes.isEmpty()) {
                    pipeline.echo "[INFRA-RETRY] ${stageName}: avoiding prior Kubernetes host node(s): ${avoidedKubernetesHostNodes.join(', ')}"
                }
            }
            // Attempt 1 keeps the caller-supplied postTag verbatim so the
            // canonical artifact name is unchanged for downstream consumers.
            // Retries append "-pod-N" to dodge the upload-once guard and
            // preserve every attempt's tarball in Artifactory. The "-pod-"
            // separator is distinct from the inner SLURM retry's "-attempt-"
            // suffix so the two nest unambiguously: outer-pod 2 / inner-attempt
            // 2 yields "-pod-2-attempt-2" rather than colliding with "-attempt-2".
            def attemptTag = (attempt == 1) ? "" : "-pod-${attempt}"
            // For attempt 1 we don't yet know whether the failure (if any) will
            // be PERSISTENT, so use the worst-case multi-retry budget. From
            // attempt 2 onward we know the prior classification — if it was
            // PERSISTENT, effectiveMax for THIS attempt is 1, meaning attempt
            // 2 IS the final attempt; pass isFinalAttempt=true so the inner
            // cacheErrorAndUploadResult does not suppress synthetic stage-fail
            // XML / junit() on what would otherwise look (to it) like just
            // another intermediate attempt.
            def effectiveMaxThisAttempt = (lastSeverity == InfraFailure.PERSISTENT) ? Math.min(1, k8sInfraRetryMax) : k8sInfraRetryMax
            boolean isFinalAttempt = (attempt > effectiveMaxThisAttempt)
            def attemptPodSpec = pipeline.trtllm_utils.withKubernetesHostNodeExclusion(podSpec, avoidedKubernetesHostNodes)
            pipeline.trtllm_utils.launchKubernetesPodWithPlacement(pipeline, attemptPodSpec, containerName, attemptPlacementContext, { runner(attemptTag, isFinalAttempt, retryContext) })
            if (attempt > 1) {
                pipeline.echo "[INFRA-RETRY] ${stageName}: Succeeded on attempt ${attempt}"
            }
            return
        } catch (InterruptedException e) {
            // User abort / pipeline timeout -- never retry
            throw e
        } catch (Exception e) {
            // classifyFailure() handles FlowInterruptedException + exit-code-143 +
            // typed throws + originating-step evidence and cause-chain fallback, returning one of
            // PipelineInterruption / InfraFailure / UserFailure. Scope=K8S
            // ensures we only match catalog rows tagged K8S or BOTH; the new
            // scope-isolation guard inside classify() also prevents a typed
            // SLURM-scoped InfraFailure (e.g. from an inner SLURM retry that
            // exhausted its own budget) from being treated as K8s infra here.
            def c = pipeline.infraRetry.classifyFailure(pipeline, e, InfraFailure.K8S, retryContext)
            if (c instanceof PipelineInterruption) throw e
            if (!(c instanceof InfraFailure)) {
                // UserFailure -> don't retry, but leave a trace so the decline
                // is diagnosable from the console (see the launch-loop twin above).
                pipeline.echo "[INFRA-RETRY] ${stageName}: stage failed with no infra pattern matched (classified as user failure); not retrying. Exception: ${e.toString()}"
                throw e
            }

            pipeline.infraRetry.rememberAvoidedKubernetesHostNodes(avoidedKubernetesHostNodes, attemptPlacementContext.lastHostNode, stageName)

            def effectiveMax = (c.severity == InfraFailure.PERSISTENT) ? Math.min(1, k8sInfraRetryMax) : k8sInfraRetryMax

            if (attempt > effectiveMax) {
                pipeline.echo "[INFRA-RETRY] ${stageName}: Infrastructure failure (${c.detectedPattern}) " +
                     "but max retries (${effectiveMax}) exhausted after ${attempt} attempts. Failing."
                throw c
            }
            if (!pipeline.infraRetry.hasBudgetForInfraRetry(pipeline, stageName, InfraFailure.K8S, c, attempt, effectiveMax, 60L * 1000L, true)) {
                pipeline.echo "[INFRA-RETRY] ${stageName}: Infrastructure failure (${c.detectedPattern}) is retryable, " +
                     "but remaining CI timeout budget is too small for another K8s attempt. Failing without retry."
                throw c
            }

            pipeline.echo "[INFRA-RETRY] ${stageName}: Infrastructure failure detected on attempt ${attempt}: " +
                 "${c.detectedPattern}"
            pipeline.echo "[INFRA-RETRY] ${stageName}: Exception: ${e.toString()}"
            pipeline.echo "[INFRA-RETRY] ${stageName}: Will retry (attempt ${attempt + 1} of ${effectiveMax + 1}) after 60s cooldown."

            // Remember severity so the next attempt's isFinalAttempt is correct.
            lastSeverity = c.severity
            pipeline.sleep(60)
        }
    }
}

def launchTestJobsForImagesSanityCheck(pipeline, globalVars) {
    def testConfigs = [
        "NGC Devel Image amd64": [
            name: "NGC-Devel-Image-amd64-Sanity-Test",
            k8sArch: "amd64",
            wheelInstalled: false,
            config: pipeline.VANILLA_CONFIG,
        ],
        "NGC Devel Image arm64": [
            name: "NGC-Devel-Image-arm64-Sanity-Test",
            k8sArch: "arm64",
            wheelInstalled: false,
            config: pipeline.LINUX_AARCH64_CONFIG,
        ],
        "NGC Release Image amd64": [
            name: "NGC-Release-Image-amd64-Sanity-Test-A10",
            gpuType: "a10",
            k8sArch: "amd64",
            wheelInstalled: true,
            config: pipeline.VANILLA_CONFIG,
        ],
        "NGC Release Image arm64": [
            name: "NGC-Release-Image-arm64-Sanity-Test-GH200",
            gpuType: "gh200",
            k8sArch: "arm64",
            wheelInstalled: true,
            config: pipeline.LINUX_AARCH64_CONFIG,
        ],
    ]
    if (!pipeline.ENABLE_NGC_DEVEL_IMAGE_TEST) {
        ["NGC Devel Image amd64", "NGC Devel Image arm64"].each { key ->
            testConfigs.remove(key)
        }
        pipeline.echo "NGC Devel Image test is disabled."
    }
    if (!pipeline.ENABLE_NGC_RELEASE_IMAGE_TEST) {
        ["NGC Release Image amd64", "NGC Release Image arm64"].each { key ->
            testConfigs.remove(key)
        }
        pipeline.echo "NGC Release Image test is disabled."
    }
    // Update testConfigs image field using the map from globalVars
    testConfigs.each { key, config ->
        if (globalVars[pipeline.runParameters.IMAGE_KEY_TO_TAG] && globalVars[pipeline.runParameters.IMAGE_KEY_TO_TAG][key]) {
            config.image = globalVars[pipeline.runParameters.IMAGE_KEY_TO_TAG][key]
        }
    }
    // Filter out all configs that don't have image set
    testConfigs = testConfigs.findAll { key, config ->
        return config.image != null
    }
    def maintenanceConfig = globalVars[pipeline.runParameters.MAINTENANCE_ENTRIES] ?: [reason: "", patterns: []]
    def maintenancePatterns = maintenanceConfig.patterns ?: []
    pipeline.testSelection.warnUnmatchedMaintenancePatterns(
        testConfigs.values()*.name, maintenancePatterns)

    pipeline.echo "Filtered test configs with images:"
    pipeline.println testConfigs

    def testJobs = testConfigs.collectEntries { key, values -> [values.name, {
        if (pipeline.stagePatterns.stageMatchesAnyPattern(values.name, maintenancePatterns)) {
            pipeline.stage(values.name) {
                pipeline.stage("Skip - Maintenance") {
                    pipeline.testSelection.failMaintenanceStage(values.name, maintenanceConfig.reason)
                }
            }
        } else if (values.wheelInstalled) {
            pipeline.stage(values.name) {
                pipeline.echo "Run ${values.name} sanity test."
                def imageSanitySpec = pipeline.createKubernetesPodConfig(values.image, values.gpuType, values.k8sArch)
                runKubernetesPodWithInfraRetry(pipeline, imageSanitySpec, "trt-llm", values.name, { attemptTag, isFinalAttempt, retryContext = null ->
                    pipeline.sh "env | sort"
                    pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "apt-get update && apt-get install -y git rsync curl")
                    pipeline.jenkinsRunner.runLLMTestlistOnPlatform(pipeline, values.gpuType, "l0_sanity_check", values.config, values.name, 1, 1, true, "-SubJob-TestImage" + attemptTag, isFinalAttempt, retryContext)
                })
            }
        } else {
            pipeline.stage(values.name) {
                def imageSanitySpec = pipeline.createKubernetesPodConfig(values.image, "build", values.k8sArch)
                pipeline.trtllm_utils.launchKubernetesPod(pipeline, imageSanitySpec, "trt-llm", {
                    pipeline.sh "env | sort"
                    def cpuArch = values.k8sArch == "amd64" ? pipeline.X86_64_TRIPLE : pipeline.AARCH64_TRIPLE
                    pipeline.testBuild.runLLMBuild(pipeline, cpuArch, false, "imageTest/")
                })
            }
        }
    }]}

    return testJobs
}

return this
