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

@Library(['bloom-jenkins-shared-lib@main', 'trtllm-jenkins-shared-lib@main']) _

import groovy.transform.Field
import com.nvidia.bloom.KubernetesManager
import com.nvidia.bloom.Constants
import com.nvidia.bloom.Utils

@Field def podFactory
@Field def runParameters
@Field def stageCatalog

def ciLibrary = library(
    identifier: "trtllm-ci@${env.gitlabMergeRequestLastCommit ?: env.gitlabCommit ?: env.gitlabBranch ?: 'main'}",
    changelog: false,
    retriever: legacySCM(scm: scm, libraryPath: 'jenkins/pipeline-library')
).trtllm.ci
podFactory = ciLibrary.pods.PodFactory.new()
runParameters = ciLibrary.parameters.RunParameters.new()
stageCatalog = ciLibrary.stages.StageCatalog.new()

// LLM repository configuration
withCredentials([string(credentialsId: 'default-llm-repo', variable: 'DEFAULT_LLM_REPO')]) {
    LLM_REPO = env.gitlabSourceRepoHttpUrl ? env.gitlabSourceRepoHttpUrl : "${DEFAULT_LLM_REPO}"
}
LLM_ROOT = "llm"

ARTIFACT_PATH = env.artifactPath ? env.artifactPath : "sw-tensorrt-generic/llm-artifacts/${JOB_NAME}/${BUILD_NUMBER}"
UPLOAD_PATH = env.uploadPath ? env.uploadPath : "sw-tensorrt-generic/llm-artifacts/${JOB_NAME}/${BUILD_NUMBER}"
URM_ARTIFACTORY_BASE = "https://urm.nvidia.com/artifactory"
ENABLE_UPLOAD_TEST_RESULTS = params.enableUploadTestResults != null ? params.enableUploadTestResults : true

// The primary location a parent pipeline passed in globalVars, which this job uses instead of its
// own controller's LLM_ARTIFACTS_PRIMARY. Empty when the job was started on its own.
LLM_ARTIFACTS_PRIMARY_FROM_PARENT = ""

// Build artifacts (build_info.txt, packaged builds, waive_list/) are published to the
// primary location and read from it first; LLM_ARTIFACTS_PRIMARY ("aws", the default, or "urm")
// selects it. Reads fall back to the other location only when a file is absent.

// "Setup Environment" stashes jenkins/scripts/fetch_build_artifact.sh from the run's commit under
// this name, because test stages read build artifacts before they have a source tree.
FETCH_BUILD_ARTIFACT_STASH = "fetch-build-artifact"

// Exit codes of jenkins/scripts/fetch_build_artifact.sh that this pipeline acts on, by the
// names the helper gives them. tests/unittest/scripts/test_fetch_build_artifact.py checks
// that they match.
FETCH_ABSENT = 2
FETCH_TRANSFER_FAILED = 4

X86_64_TRIPLE = "x86_64-linux-gnu"
AARCH64_TRIPLE = "aarch64-linux-gnu"

// default package name
linuxPkgName = ( env.targetArch == AARCH64_TRIPLE ? "tensorrt-llm-sbsa-release-src-" : "tensorrt-llm-release-src-" ) + (env.artifactCommit ? env.artifactCommit : env.gitlabCommit) + ".tar.gz"

// Container configuration
// available tags can be found in: https://artifactory.nvidia.com/artifactory/sw-tensorrt-llm-docker-local/tensorrt-llm/
// [base_image_name]-[arch]-[os](-[python_version])-[trt_version]-[torch_install_type]-[stage]-[date]-[mr_id]
LLM_DOCKER_IMAGE = env.dockerImage
X86_64_DOCKER_IMAGE = LLM_DOCKER_IMAGE.replace("aarch64", "x86_64").replace("sbsa", "x86_64")
LLM_ROCKYLINUX8_PY310_DOCKER_IMAGE = env.wheelDockerImagePy310
LLM_ROCKYLINUX8_PY312_DOCKER_IMAGE = env.wheelDockerImagePy312
LLM_WHEEL_DOCKER_IMAGE = env.wheelDockerImage

ARTIFACTORY_DOCKER_HOST = "artifactory.nvidia.com"
// Read-only artifactory pull credentials
ARTIFACTORY_CREDENTIALS_ID = "trtllm-artifactory-credentials"
// Credential with which the read helper reads build artifacts on artifactory.nvidia.com: the
// publishing credential, the one the pipelines publish build artifacts with.
LLM_ARTIFACTS_CREDENTIALS_ID = "aws-artifactory-credentials"

// DLFW torch image
DLFW_IMAGE = "urm.nvidia.com/docker/nvidia/pytorch:26.09-py3"

//Ubuntu base image
UBUNTU_22_04_IMAGE = "artifactory.pdx.nvidia.com/docker-remote/ubuntu:22.04"
UBUNTU_24_04_IMAGE = "artifactory.pdx.nvidia.com/docker-remote/ubuntu:24.04"

// Literals for easier access.
@Field def TARNAME = "tarName"

@Field def VANILLA_CONFIG = "Vanilla"

@Field def SINGLE_DEVICE_CONFIG = "SingleDevice"

@Field def LLVM_CONFIG = "LLVM"

@Field def LINUX_AARCH64_CONFIG = "linux_aarch64"

@Field def INFRA_DRY_RUN_TEST_CONTEXT = "infra_dry_run"

@Field def BUILD_CONFIGS = [
  // Vanilla TARNAME is used for packaging in runLLMPackage
  (VANILLA_CONFIG) : [(TARNAME) : "TensorRT-LLM.tar.gz"],
  (SINGLE_DEVICE_CONFIG) : [(TARNAME) : "single-device-TensorRT-LLM.tar.gz"],
  (LLVM_CONFIG) : [(TARNAME) : "llvm-TensorRT-LLM.tar.gz"],
  (LINUX_AARCH64_CONFIG) : [(TARNAME) : "TensorRT-LLM-GH200.tar.gz"],
]

CCACHE_DIR="/mnt/sw-tensorrt-pvc/scratch.trt_ccache/llm_ccache"
MODEL_CACHE_DIR="/scratch.trt_llm_data/llm-models"

// Maximum SLURM infra-failure retries (total attempts = SLURM_INFRA_RETRY_MAX + 1).
// Recognised failure patterns are tagged with scope=SLURM or BOTH in the
// shared-lib PATTERN_CATALOG.
//
// Capped at 1 (2 attempts) because each SLURM attempt that times out at the
// partition walltime burns ~4 hours, and the dominant retryable failure
// (AgentOfflineException after test ran for hours) is a test-timeout
// proxy — not a transient blip worth multi-retry. Cross-layer total is also
// capped at 2: SLURM dispatcher pods pass singleAttempt:true to the outer
// K8s pod retry, so 1 pod × 2 SLURM attempts = 2 total.
SLURM_INFRA_RETRY_MAX = 1

// Only source-specific signatures are safe to match against arbitrary test
// stdout/stderr in the SLURM job log. Keep the concrete terms and their retry
// policy owned by FailureClassifier.
SLURM_JOB_LOG_EVIDENCE_QUERY_IDS = [
    "cuda-device-unavailable",
    "cuda-buffer-mapping",
    "nvidia-driver-unavailable",
    "gpu-lost",
    "gpu-ecc-uncorrectable",
    "nvlink-fabric-failure",
]

// Maximum K8s infra-failure retries (total attempts = K8S_INFRA_RETRY_MAX + 1).
// Kept distinct from SLURM_INFRA_RETRY_MAX so the two paths can be tuned
// independently as production telemetry comes in. Patterns tagged scope=K8S
// or BOTH in the shared-lib PATTERN_CATALOG.
//
// Capped at 1 (2 attempts) so a non-SLURM K8s stage runs at most 2 pods.
// For SLURM dispatcher pods this budget is bypassed (singleAttempt:true)
// to avoid nesting with the inner SLURM retry.
K8S_INFRA_RETRY_MAX = 1

// Infra-scoped fail-fast master switch. When true, a branch whose post-retry
// failure classifies as a positive infra abort (via
// FailureClassifier.isDeferrableInfra) is recorded and swallowed -- its sibling
// branches keep running instead of being SIGTERMed by failFast -- and a sub-job
// that saw only infra aborts (no genuine failure) resolves to UNSTABLE. When
// false, every failure rethrows and the original bare-boolean fail-fast is fully
// restored. Kept separate from params.enableFailFast so the scoped behavior can
// be disabled pipeline-wide without turning fail-fast itself off. Both K8s-scoped
// and SLURM-scoped aborts are deferred: runBranchesWithInfraDefer classifies each
// branch under its real execution scope (SLURM dispatcher stages under both K8S
// and SLURM, every other stage under K8S).
//
// Overridable without a code change by setting the ENABLE_INFRA_SCOPED_FAILFAST
// env var on the job. Env values are strings ("false" is truthy in Groovy), so
// the override goes through toBoolean() rather than the bare elvis.
ENABLE_INFRA_SCOPED_FAILFAST = env.ENABLE_INFRA_SCOPED_FAILFAST ? env.ENABLE_INFRA_SCOPED_FAILFAST.toBoolean() : true

// Per-stage override of the above: set `infraRetryMax` in a stage's opts map (the
// 3rd element of its parallel-jobs config tuple, alongside singleAttempt) to cap
// or disable stage-level infra retries for resource-scarce hardware pools --
// `infraRetryMax: 0` disables retries entirely (1 attempt). It may only reduce the
// budget: values above the scope global are clamped down to it (resolveInfraRetryMax),
// so it can never increase retries past these caps. It applies to whichever
// stage-level retry the stage uses: the SLURM retry (runLLMTestlistOnSlurm) for
// dispatcher pods, or the K8s pod retry (runKubernetesPodWithInfraRetry) for regular
// test pods. It does NOT touch the dispatcher-pod launch-retry (relaunching a cheap
// Blossom pod doesn't tax the scarce hardware). Null/absent = use the globals above.

// Fallback discriminator for SLURM timeouts.
// If we can't reach the SLURM node for an authoritative reason,
// we apply a heuristic: if the job needed more than this of its budget
// to fail, we treat it as a timeout.
SLURM_TIMEOUT_RETRY_FRACTION = 0.9

// SLURM states in which the job is still alive (no terminal verdict yet). When a
// monitor/agent exception surfaces while the job is in one of these, the failure
// is a transient infra blip (lost SSH/agent) -- not a test result -- so the stage
// should retry rather than defer an opaque exception to the classifier (which
// could mistake it for a test failure and not retry). Mirrors the active-state
// set the sbatch resubmit guard reuses an existing job on.
SLURM_NON_TERMINAL_STATES = [
    "RUNNING", "PENDING", "CONFIGURING", "COMPLETING",
    "REQUEUED", "RESIZING", "SUSPENDED", "SIGNALING", "STOPPED",
]

// How often the background shell watcher snapshots the in-progress
// ${stageName}/ directory (containing the PeriodicJUnitXML reporter's
// results.xml) up to Artifactory. 10 minutes balances Artifactory traffic
// against staleness for multi-hour test stages.
PROGRESS_UPLOAD_INTERVAL_SEC = 600

// ENABLE_NGC_DEVEL_IMAGE_TEST is currently disabled in the Jenkins BuildDockerImageSanityTest job config
ENABLE_NGC_DEVEL_IMAGE_TEST = params.enableNgcDevelImageTest ?: false
ENABLE_NGC_RELEASE_IMAGE_TEST = params.enableNgcReleaseImageTest ?: false

COMMON_SSH_OPTIONS = Utils.DEFAULT_CUSTOM_SSH_OPTIONS

// Per-stage CBTS coverage exclusions applied on top of the upstream eligibility decision.
CBTS_EXCLUDE_STAGES = [] as Set

// Test filter flags
// Multi-GPU stages matching any entry here run inside the single-GPU job
// instead of waiting for the separate multi-GPU dispatch (which requires
// the 'ci: full pre-merge approved' label). Supports exact names and
// wildcard (*) patterns.
@Field def MULTI_GPU_RUN_WITH_SINGLE = [
    // Add stage patterns here, e.g.:
    // "DGX_H100-2_GPUs-*",
]

// Suffix for CBTS-narrowed stages so they cannot be reused as whole non-CBTS stages.
// Their individual passed testcases may still be reused through an OpenSearch query.
// A suffix (not prefix) keeps the GPU type as the first '-' token for positional parsers.
@Field def CBTS_STAGE_SUFFIX = "-cbts"
@Field def testFilter

testFilter = runParameters.testFilterDefaults()

// Parent execution inputs, resolved before building the normal test plan.
BOLT_PINNED_REF = ""
BOLT_TEST_BOLTED_TARBALL = false
// Release scripts repository as [url: ..., commit: ...], resolved by L0_MergeRequest.
TRTLLM_RELEASE_REPO = null
def globalVars = [
    (runParameters.GITHUB_PR_API_URL): null,
    (runParameters.CACHED_CHANGED_FILE_LIST): null,
    (runParameters.ACTION_INFO): null,
    (runParameters.IMAGE_KEY_TO_TAG): [:],
    (runParameters.TRTLLM_VERSION_OVERRIDE): null,
    (runParameters.RUN_MODE): null,
    (runParameters.MAINTENANCE_ENTRIES): [reason: "", patterns: []],
    (runParameters.BOLT_CONSUME_BUILD): false,
    // Pre-declared so updateMapWithJson() populates it from the parent: that
    // helper only updates keys already present here, so an absent key is
    // silently dropped -- which for this one would mean running unpinned
    // without saying so.
    (runParameters.BOLT_PROFILE_REF): "",
    (runParameters.BOLT_PUBLISH_VARIANT): false,
    (runParameters.BOLT_PROFILE_BRANCH): "",
    (runParameters.JOB_TYPE): null,
    // Pre-declared so updateMapWithJson() populates it from the parent (see
    // LLM_ARTIFACTS_PRIMARY_FROM_PARENT).
    (runParameters.LLM_ARTIFACTS_PRIMARY_KEY): "",
    (runParameters.TRTLLM_RELEASE_REPO): null,
]

def createKubernetesPodConfig(image, type, arch = "amd64", gpuCount = 1, modelExpress = false)
{
    def inputs = [podTimeoutSeconds: env.podTimeoutSeconds]
    if (type != null && !(type in ["agent", "slurm", "build", "cpu"])) {
        inputs.gpuType = KubernetesManager.selectGPU(type)
        inputs.defaultDriverVersion = Constants.DEFAULT_NVIDIA_DRIVER_VERSION
        inputs.defaultOpenDriverVersion = Constants.DEFAULT_NVIDIA_OPEN_DRIVER_VERSION
    }
    def pod = podFactory.testPod(image, type, arch, gpuCount, modelExpress, inputs)
    pod.label = trtllm_utils.generateNodeLabel(pod.label)
    return pod
}

def loadPipelineModules(String sourceRoot)
{
    def podDefaults = podFactory.defaults()
    artifactPaths = load("${sourceRoot}/common/ArtifactPaths.groovy").initialize(this)
    infraRetry = load("${sourceRoot}/test/InfraRetry.groovy").initialize(this)
    sbatchScripts = load("${sourceRoot}/test/SbatchScripts.groovy").initialize(this)
    slurmAgent = load("${sourceRoot}/test/SlurmAgent.groovy").initialize(this)
    slurmResources = load("${sourceRoot}/test/SlurmResources.groovy").initialize(this)
    slurmSbatch = load("${sourceRoot}/test/SlurmSbatch.groovy").initialize(this)
    stagePatterns = load("${sourceRoot}/common/StagePatterns.groovy").initialize(this)
    testBuild = load("${sourceRoot}/test/Build.groovy").initialize(this)
    testDb = load("${sourceRoot}/test/Db.groovy").initialize(this)
    testExecution = load("${sourceRoot}/test/Execution.groovy").initialize(this, podDefaults.TESTER_CORES)
    jenkinsRunner = load("${sourceRoot}/test/JenkinsTestRunner.groovy").initialize(this, podDefaults.MODEL_EXPRESS_VERSION)
    testResults = load("${sourceRoot}/test/Results.groovy").initialize(this)
    testRuntime = load("${sourceRoot}/test/Runtime.groovy").initialize(this)
    testSelection = load("${sourceRoot}/test/Selection.groovy").initialize(this)
    def catalog = stageCatalog.expandForPipeline(readJSON(file: "${sourceRoot}/test/stages.json", returnPojo: true))
    testStages = load("${sourceRoot}/test/Stages.groovy").initialize(this, catalog)
    ciState = load("${sourceRoot}/test/State.groovy")
}

pipeline {
    agent {
        kubernetes createKubernetesPodConfig("", "agent")
    }
    options {
        // Check the valid options at: https://www.jenkins.io/doc/book/pipeline/syntax/
        // some step like results analysis stage, does not need to check out source code
        skipDefaultCheckout()
        // to better analyze the time for each step/test
        timestamps()
        timeout(time: 24, unit: 'HOURS')
    }
    environment {
        //Workspace normally is: /home/jenkins/agent/workspace/LLM/L0_MergeRequest@tmp/
        HF_HOME="${env.WORKSPACE_TMP}/.cache/huggingface"
        CCACHE_DIR="${CCACHE_DIR}"
        GITHUB_MIRROR="https://urm.nvidia.com/artifactory/github-go-remote"
        PIP_INDEX_URL="https://artifactory.pdx.nvidia.com/artifactory/api/pypi/pypi-remote/simple"
        // force datasets to be offline mode, to prevent CI jobs are downloading HF dataset causing test failures
        HF_DATASETS_OFFLINE=1
        CMAKE_POLICY_VERSION_MINIMUM="3.5"
        OPEN_SEARCH_DB_BASE_URL=credentials("open_search_db_base_url")
        OPEN_SEARCH_DB_CREDENTIALS=credentials("open_search_db_credentials")
    }
    stages {
        stage("Setup Environment")
        {
            steps
            {
                script {
                    def moduleRoot = "${pwd(tmp: true)}/pipeline-modules"
                    def moduleCheckout = trtllm_utils.checkoutSpec(LLM_REPO, env.gitlabCommit, moduleRoot)
                    moduleCheckout.extensions += [[$class: 'SparseCheckoutPaths',
                        sparseCheckoutPaths: [[path: 'jenkins/pipelines']]]]
                    checkout(scm: moduleCheckout, changelog: false, poll: false)
                    loadPipelineModules("${moduleRoot}/jenkins/pipelines")
                    echo "enableFailFast is: ${params.enableFailFast}"
                    echo "env.testFilter is: ${env.testFilter}"
                    testFilter = trtllm_utils.updateMapWithJson(this, testFilter, env.testFilter, "testFilter")
                    println testFilter
                    echo "env.globalVars is: ${env.globalVars}"
                    globalVars = trtllm_utils.updateMapWithJson(this, globalVars, env.globalVars, "globalVars")
                    globalVars = trtllm_utils.initializeCiBudget(this, globalVars, 24, 'HOURS', 'L0_Test')
                    globalVars[runParameters.ACTION_INFO] = trtllm_utils.setupPipelineDescription(this, globalVars[runParameters.ACTION_INFO])
                    if (globalVars[runParameters.LLM_ARTIFACTS_PRIMARY_KEY]) {
                        LLM_ARTIFACTS_PRIMARY_FROM_PARENT = globalVars[runParameters.LLM_ARTIFACTS_PRIMARY_KEY].toString()
                        echo "Build artifacts: primary location ${artifactPaths.llmArtifactsPrimary()}, passed by the parent pipeline"
                    }
                    // The read helper that fetchBuildArtifact unstashes on every node.
                    def fetchHelperDir = "${pwd(tmp: true)}/fetch-build-artifact"
                    trtllm_utils.checkoutFile(LLM_REPO, env.gitlabCommit, "jenkins/scripts/fetch_build_artifact.sh", fetchHelperDir)
                    dir(fetchHelperDir) {
                        stash(name: FETCH_BUILD_ARTIFACT_STASH, includes: "fetch_build_artifact.sh")
                    }
                }
            }
        }
        stage("Test") {
            steps {
                script {
                    // Default scope map so the image-sanity path (which does not
                    // build one) still has a value for runBranchesWithInfraDefer;
                    // launchTestJobs overwrites this with per-stage scopes.
                    def stageInfraScope = [:]
                    def parallelJobs
                    try {
                        if (env.JOB_NAME ==~ /.*BuildDockerImageSanityTest.*/) {
                            parallelJobs = testRuntime.launchTestJobsForImagesSanityCheck(this, globalVars)
                        } else {
                            BOLT_PINNED_REF = globalVars[runParameters.BOLT_PROFILE_REF]?.toString() ?: ""
                            BOLT_TEST_BOLTED_TARBALL = globalVars[runParameters.BOLT_PUBLISH_VARIANT] ?: false
                            TRTLLM_RELEASE_REPO = globalVars[runParameters.TRTLLM_RELEASE_REPO]
                            def testPlan = testStages.launchTestJobs(this, testFilter, globalVars)
                            parallelJobs = testPlan.jobs
                            stageInfraScope = testPlan.infraScopes
                        }

                        def singleGpuJobs = parallelJobs
                        def dgxJobs = [:]

                        def testPhase2StageName = env.testPhase2StageName
                        if (testPhase2StageName) {
                            def multiGpuPattern = /\d+_GPUs/
                            singleGpuJobs = parallelJobs.findAll{!(it.key =~ multiGpuPattern)}
                            dgxJobs = parallelJobs.findAll{it.key =~ multiGpuPattern}

                            // Move approval-exempt multi-GPU stages into singleGpuJobs so they
                            // run without waiting for the multi-GPU dispatch (which requires
                            // the 'ci: full pre-merge approved' label).
                            def exemptJobs = dgxJobs.findAll { stageName, stageValue ->
                                MULTI_GPU_RUN_WITH_SINGLE.any { pattern ->
                                    stagePatterns.stageMatchesPattern(stageName, pattern)
                                }
                            }
                            if (exemptJobs) {
                                echo "[Multi-GPU split] Moving ${exemptJobs.keySet()} to single-GPU job (approval-exempt)"
                                singleGpuJobs += exemptJobs
                                dgxJobs -= exemptJobs
                            }
                        }

                        if (env.JOB_NAME ==~ /.*Single-GPU.*/) {
                            echo "Only run single-GPU tests."
                            if (dgxJobs.size() > 0) {
                                if (globalVars[runParameters.ACTION_INFO]['parents'].size() > 0) {
                                    // We add a special marker to the parent job's description.
                                    // This will be used to decide whether to run multi-GPU test stage.
                                    def parentJob = globalVars[runParameters.ACTION_INFO]['parents'][-2]
                                    def archStr = (env.targetArch == X86_64_TRIPLE) ? "x86_64" : (env.targetArch == AARCH64_TRIPLE ? "SBSA" : "Unknown")
                                    trtllm_utils.appendBuildDescription(this, parentJob['name'], parentJob['build_number'], "====Require ${archStr} Multi-GPU Testing====<br/>")
                                } else {
                                    echo "No parent job found to add the special marker for executing multi-GPU test stage."
                                }
                            } else {
                                echo "Skip multi-GPU testing. No test to run."
                            }
                            if (singleGpuJobs.size() > 0) {
                                trtllm_utils.runBranchesWithInfraDefer(this, singleGpuJobs, params.enableFailFast,
                                    ENABLE_INFRA_SCOPED_FAILFAST, infraRetry.infraDeferPredicate(this, stageInfraScope))
                            } else if (testSelection.isInfraDryRun()) {
                                error "Skip single-GPU testing. No test to run for infrastructure dry run."
                            } else {
                                echo "Skip single-GPU testing. No test to run."
                            }
                        } else if (env.JOB_NAME ==~ /.*Multi-GPU.*/) {
                            echo "Only run multi-GPU tests."
                            if (dgxJobs.size() > 0) {
                                trtllm_utils.runBranchesWithInfraDefer(this, dgxJobs, params.enableFailFast,
                                    ENABLE_INFRA_SCOPED_FAILFAST, infraRetry.infraDeferPredicate(this, stageInfraScope))
                            } else {
                                error "Skip multi-GPU testing. No test to run."
                            }
                        } else {
                            if (singleGpuJobs.size() > 0) {
                                trtllm_utils.runBranchesWithInfraDefer(this, singleGpuJobs, params.enableFailFast,
                                    ENABLE_INFRA_SCOPED_FAILFAST, infraRetry.infraDeferPredicate(this, stageInfraScope))
                            } else {
                                echo "Skip single-GPU testing. No test to run."
                            }

                            if (dgxJobs.size() > 0) {
                                stage(testPhase2StageName) {
                                    trtllm_utils.runBranchesWithInfraDefer(this, dgxJobs, params.enableFailFast,
                                    ENABLE_INFRA_SCOPED_FAILFAST, infraRetry.infraDeferPredicate(this, stageInfraScope))
                                }
                            }
                        }
                    } finally {
                        // Backstop: reclaim any SLURM job / Jenkins node left orphaned
                        // by a dispatcher-pod death whose in-catch finalize did not run
                        // or failed. Best-effort; never fails the build.
                        try {
                            slurmResources.sweepOrphanedSlurmResources(this)
                        } catch (Exception sweepErr) {
                            echo "[SLURM-FINALIZER] post-build sweep error: ${sweepErr}"
                        }
                        testSelection.appendMaintenanceSummary(globalVars)
                    }
                }
            }
        } // Test stage
    } // stages
} // pipeline
