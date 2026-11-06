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

import java.lang.InterruptedException
import groovy.transform.Field

def gitlabParamsFromBot = [:]
if (env.gitlabTriggerPhrase)
{
    gitlabParamsFromBot = readJSON text: env.gitlabTriggerPhrase, returnPojo: true
}
runMode = gitlabParamsFromBot.get("run_mode", "full")

// LLM repository configuration
withCredentials([string(credentialsId: 'default-llm-repo', variable: 'DEFAULT_LLM_REPO')]) {
    LLM_REPO = env.gitlabSourceRepoHttpUrl ? env.gitlabSourceRepoHttpUrl : "${DEFAULT_LLM_REPO}"
}
LLM_ROOT = "llm"
LLM_ARTIFACTS_PRIMARY_FROM_PARENT = ""

// LLM repository configuration
withCredentials([string(credentialsId: 'default-scan-repo', variable: 'DEFAULT_SCAN_REPO')]) {
    SCAN_REPO = "${DEFAULT_SCAN_REPO}"
}
SCAN_COMMIT = "main"
SCAN_ROOT = "scan"

ARTIFACT_PATH = env.artifactPath ? env.artifactPath : "sw-tensorrt-generic/llm-artifacts/${JOB_NAME}/${BUILD_NUMBER}"
UPLOAD_PATH = env.uploadPath ? env.uploadPath : "sw-tensorrt-generic/llm-artifacts/${JOB_NAME}/${BUILD_NUMBER}"

// Build artifacts (build_info.txt, packaged builds, waive_list/) are published to the
// primary location and read from it first; LLM_ARTIFACTS_PRIMARY ("aws", the default, or "urm")
// selects it. Reads fall back to the other location only when a file is absent.
LLM_ARTIFACTS_LOCATIONS = [
    aws: [root: "https://artifactory.nvidia.com/artifactory", repo: "sw-tensorrt-llm-generic-local"],
    urm: [root: "https://urm.nvidia.com/artifactory", repo: "sw-tensorrt-generic"],
]

// GitLab project id for TRT-LLM repo, used to set GitLab status. Only needed when triggering by timer or by hand.
withCredentials([string(credentialsId: 'gitlab-llm-repo-id', variable: 'GITLAB_PROJECT_ID')]) {
    GITLAB_PROJECT_ID = env.gitlabProjectId ? env.gitlabProjectId : "${GITLAB_PROJECT_ID}"
}

// K8s secret in namespace sw-tensorrt for pulling from artifactory.nvidia.com
ARTIFACTORY_IMAGE_PULL_SECRET = "trtllm-artifactory"

// Stage choices
STAGE_CHOICE_NORMAL = "normal"
STAGE_CHOICE_SKIP = "skip"
STAGE_CHOICE_IGNORE = "ignore"

RELEASE_CHECK_CHOICE = env.releaseCheckChoice ? env.releaseCheckChoice : STAGE_CHOICE_NORMAL
BUILD_CHECK_CHOICE = env.buildCheckChoice ? env.buildCheckChoice : STAGE_CHOICE_NORMAL
X86_TEST_CHOICE = env.x86TestChoice ? env.x86TestChoice : STAGE_CHOICE_NORMAL
SBSA_TEST_CHOICE = env.SBSATestChoice ? env.SBSATestChoice : STAGE_CHOICE_NORMAL

// "Fail Fast" feature is enabled by default for the pre-merge pipeline.
// "Fail Fast" feature is always disabled for the post-merge pipeline.
boolean enableFailFast = !(env.JOB_NAME ==~ /.*PostMerge.*/ || env.JOB_NAME ==~ /.*Dependency_Testing_TRT.*/) && !gitlabParamsFromBot.get("disable_fail_fast", false)

boolean isReleaseCheckMode = (runMode == "release_check")

GEN_POST_MERGE_BUILDS_ONLY = (env.JOB_NAME?.contains("GenPostMergeBuilds") ?: false)

BUILD_STATUS_NAME = isReleaseCheckMode ? "Jenkins Release Check" : "Jenkins Full Build"

def trimForStageList(stageNameList)
{
    if (stageNameList == null) {
        return null
    }
    trimedList = []
    stageNameList.each { stageName ->
        trimedList.add(stageName.trim().replaceAll('\\\\', ''))
    }
    return trimedList
}

def normalizeReleaseTargets(rawReleaseTarget)
{
    def supportedTargets = ["wheel", "container"]
    def releaseTarget = rawReleaseTarget?.toString()?.trim()
    if (!releaseTarget || releaseTarget.equalsIgnoreCase("all")) {
        return supportedTargets
    }

    def requestedTargets = releaseTarget.split(",", -1).collect {
        it.trim().toLowerCase()
    }
    if (requestedTargets.any { !it }) {
        error "Invalid release_target '${rawReleaseTarget}': empty entries are not allowed"
    }
    if (requestedTargets.contains("all")) {
        error "Invalid release_target '${rawReleaseTarget}': all cannot be combined with other targets"
    }

    def invalidTargets = requestedTargets.findAll {
        !supportedTargets.contains(it)
    }.unique()
    if (invalidTargets) {
        error "Invalid release_target '${rawReleaseTarget}': supported values are all, wheel, and container"
    }
    return supportedTargets.findAll { requestedTargets.contains(it) }
}

@Field def REUSE_TEST = "reuse_test"   // Determine if the pipeline should reuse test results in a stage from the previous pipelines.
@Field def REUSE_STAGE_LIST = "reuse_stage_list"
@Field def ENABLE_SKIP_TEST = "skip_test"
@Field def TEST_STAGE_LIST = "stage_list"
@Field def GPU_TYPE_LIST = "gpu_type"
@Field def TEST_BACKEND = "test_backend"
@Field def IS_POST_MERGE = "post_merge"
@Field def ADD_MULTI_GPU_TEST = "add_multi_gpu_test"
@Field def ENABLE_MULTI_GPU_TEST = "multi_gpu_test"
@Field def ONLY_MULTI_GPU_TEST = "only_multi_gpu_test"
@Field def DISABLE_MULTI_GPU_TEST = "disable_multi_gpu_test"
@Field def EXTRA_STAGE_LIST = "extra_stage"
@Field def MULTI_GPU_FILE_CHANGED = "multi_gpu_file_changed"
@Field def ONLY_ONE_GROUP_CHANGED = "only_one_group_changed"
@Field def AUTO_TRIGGER_TAG_LIST = "auto_trigger_tag_list"
@Field def DEBUG_MODE = "debug"
@Field def DETAILED_LOG = "detailed_log"
@Field def CBTS_RESULT = "cbts_result"
@Field def CBTS_COVERAGE = "cbts_coverage"
@Field def DISABLE_CBTS = "disable_cbts"
@Field def INFRA_DRY_RUN = "infra_dry_run"
// Kill switch for CBTS per-test coverage; official post-merge pipeline only, single-GPU stages only in Phase 1.
@Field def ENABLE_CBTS_COVERAGE = true
// Version-controlled Tier 2 application policy. Coverage decisions are evaluated
// for every eligible PR, but only these authors receive coverage-based narrowing.
// Keep the policy in the infra-owned Groovy boundary so rollout requires infra review.
@Field def CBTS_COVERAGE_PILOT_USERS = [
    "crazydemo",
    "QiJune",
    "sunnyqgg",
    "Barry-Delaney",
    "xxi-nv",
    "leslie-fang25",
    "rosong11",
    "tongyuantongyu",
    "jieli-matrix",
    "StanleySun639",
    "xinhe-nv",
    "ruodil",
    "fredricz-20070104",
    "yufeiwu-nv",
    "yingguo-trt",
] as Set
@Field def OSS_COMPLIANCE_FILE_CHANGED = "oss_compliance_file_changed"
// `/bot run` key that opts a single run into BOLT consume.
@Field def BOLT_CONSUME = "bolt_consume"
// Version-controlled rollout switch for BOLT premerge consume. Kept in the
// infra-owned Groovy boundary like ENABLE_CBTS_COVERAGE above, so turning consume
// on for every eligible build is a reviewed code change; a `/bot run` with
// `"bolt_consume": true` opts in a single run without one. Either way
// resolveBoltConsume() still applies the post-merge and branch restrictions.
@Field def ENABLE_BOLT_PREMERGE_CONSUME = true
// Version-controlled rollout switch for post-merge and nightly release BOLT.
//
// Post-merge cannot use the pre-merge shape, where the BOLTed build REPLACES the
// canonical tarball: that tarball is BoltProfileGen's input and must stay
// un-BOLTed, or the next bundle is generated from already-optimized binaries.
// So post-merge publishes BOTH -- canonical untouched, plus bolted-<tarName>
// carrying a bolt.ref property naming the bundle applied.
//
// On means post-merge SBSA also runs its tests against bolted-<tarName> instead
// of only publishing it. Turning it off stops both the extra publish and the
// test-side switch; the canonical tarball is never touched either way, so a
// post-merge run with this off is byte-for-byte what it is today.
//
// This is deliberately not the pre-merge switch above. Post-merge is scoped to
// one branch and one arch, and it exercises the same apply-to-tarball path a
// pre-merge build would, which is what makes it the smoke test for flipping
// ENABLE_BOLT_PREMERGE_CONSUME afterwards.
@Field def ENABLE_BOLT_POSTMERGE_VARIANT = true

def testFilter = [
    (REUSE_TEST): gitlabParamsFromBot.get(REUSE_TEST, null),
    (REUSE_STAGE_LIST): trimForStageList(gitlabParamsFromBot.get(REUSE_STAGE_LIST, null)?.tokenize(',')),
    (ENABLE_SKIP_TEST): gitlabParamsFromBot.get((ENABLE_SKIP_TEST), false),
    (TEST_STAGE_LIST): trimForStageList(gitlabParamsFromBot.get((TEST_STAGE_LIST), null)?.tokenize(',')),
    (GPU_TYPE_LIST): trimForStageList(gitlabParamsFromBot.get((GPU_TYPE_LIST), null)?.tokenize(',')),
    (TEST_BACKEND): trimForStageList(gitlabParamsFromBot.get((TEST_BACKEND), null)?.tokenize(',')),
    (IS_POST_MERGE): (env.JOB_NAME ==~ /.*PostMerge.*/) || gitlabParamsFromBot.get((IS_POST_MERGE), false),
    (ADD_MULTI_GPU_TEST): gitlabParamsFromBot.get((ADD_MULTI_GPU_TEST), false),
    (ONLY_MULTI_GPU_TEST): gitlabParamsFromBot.get((ONLY_MULTI_GPU_TEST), false) || gitlabParamsFromBot.get((ENABLE_MULTI_GPU_TEST), false),
    (DISABLE_MULTI_GPU_TEST): gitlabParamsFromBot.get((DISABLE_MULTI_GPU_TEST), false),
    (EXTRA_STAGE_LIST): trimForStageList(gitlabParamsFromBot.get((EXTRA_STAGE_LIST), null)?.tokenize(',')),
    (MULTI_GPU_FILE_CHANGED): false,
    (ONLY_ONE_GROUP_CHANGED): "",
    (DEBUG_MODE): gitlabParamsFromBot.get(DEBUG_MODE, false),
    (AUTO_TRIGGER_TAG_LIST): [],
    (DETAILED_LOG): gitlabParamsFromBot.get(DETAILED_LOG, false),
    (CBTS_RESULT): null,
    (CBTS_COVERAGE): false,
    (DISABLE_CBTS): gitlabParamsFromBot.get((DISABLE_CBTS), false),
    (INFRA_DRY_RUN): (params.InfraDryRun?.toString()?.toBoolean() ?: false),
]

String reuseBuild = gitlabParamsFromBot.get('reuse_build', null)

@Field def GITHUB_PR_API_URL = "github_pr_api_url"
// Used to read the git changes when there is no PR or MR to read the changes from (i.e., when trigger from merge queue):
// the changes are base_commit..gitlabCommit, read from `git diff` in the internal Git mirror.
@Field def BASE_COMMIT = "base_commit"
@Field def CACHED_CHANGED_FILE_LIST = "cached_changed_file_list"
@Field def ACTION_INFO = "action_info"
@Field def IMAGE_KEY_TO_TAG = "image_key_to_tag"
@Field def TARGET_BRANCH = "target_branch"
@Field def TRTLLM_VERSION_OVERRIDE = "trtllm_version_override"
@Field def RUN_MODE = "run_mode"
@Field def BUILD_BRANCH = "build_branch"
@Field def MAINTENANCE_ENTRIES = "maintenance_entries"
@Field def BOLT_CONSUME_BUILD = "bolt_consume_build"
// The one BOLT profile bundle every consumer in this pipeline applies. Resolved
// once (see resolveBoltProfileRef) rather than each consumer reading the mutable
// `latest` pointer whenever it happens to run -- which made two stages in the
// same pipeline able to disagree about which profiles they used. Empty means
// unpinned, i.e. today's read-`latest`-per-consumer behaviour.
@Field def BOLT_PROFILE_REF = "bolt_profile_ref"
// Whether this run's build publishes bolted-<tarName> beside an untouched
// canonical. Decided once: the build needs it to know what to publish, and the
// test stages need it to know what to fetch. Two copies of the condition would
// eventually disagree, and the failure would be tests silently reading the
// un-BOLTed tarball -- indistinguishable from a healthy run.
@Field def BOLT_PUBLISH_VARIANT = "bolt_publish_variant"
// The branch whose promote directory holds that bundle. A ref alone does not
// address an object -- the path is <branch>/<triple>/bolt-profile-<ref>-<triple>
// -- and consumers used to supply the branch themselves from four different
// expressions. They agree on post-merge main and nowhere guaranteed else, and a
// disagreement is a 404, which apply_latest.sh reports as "nothing promoted":
// indistinguishable from a cold start, so it degrades silently. Carrying the
// branch with the ref means a pinned consumer never has to guess.
@Field def BOLT_PROFILE_BRANCH = "bolt_profile_branch"
// The one triple with a BOLT profile producer. BoltProfileGen runs aarch64 only,
// so this is the only triple with a promoted bundle to resolve or apply. Named
// once because the pin resolver and the consume scope below must not disagree.
@Field def BOLT_PRODUCER_TRIPLE = "aarch64-linux-gnu"
// The triples BOLT consume applies to. Stated explicitly rather than inferred
// from the pin: "which bundle" and "which architectures consume BOLT" are
// independent questions, and answering the second from the first made the
// x86_64 path behave differently depending on whether the pin happened to
// resolve -- an unresolvable pin is non-fatal by design, so x86_64 would then
// go on to stage llvm-bolt and request a bundle that does not exist before
// giving up. Both consumers are launched for both architectures off this one
// map, so the scope has to travel with it.
//
// Adding a triple here also needs a per-triple pin: BOLT_PROFILE_REF names one
// bundle under one promote directory, so a second architecture reading it would
// demand aarch64's bundle. Change the two together.
@Field def BOLT_CONSUME_TRIPLES = "bolt_consume_triples"
@Field def RELEASE_TARGET = "release_target"
@Field def JOB_TYPE = "job_type"
// The primary location of build artifacts (see llmArtifactsPrimary), passed to every child job.
// Child jobs run on other Jenkins controllers, whose own LLM_ARTIFACTS_PRIMARY can differ.
@Field def LLM_ARTIFACTS_PRIMARY_KEY = "llm_artifacts_primary"
// Release scripts repository (nspect.py, kitmaker_wheel.py) as [url: ..., commit: ...],
// resolved once in setupPipelineEnvironment from the TRTLLM_RELEASE_REPO credential and
// jenkins/ci_versions.properties.
@Field def TRTLLM_RELEASE_REPO = "TRTLLM_RELEASE_REPO"
def globalVars = [
    (GITHUB_PR_API_URL): gitlabParamsFromBot.get('github_pr_api_url', null),
    (BASE_COMMIT): gitlabParamsFromBot.get('base_commit', null),
    (CACHED_CHANGED_FILE_LIST): null,
    (ACTION_INFO): gitlabParamsFromBot.get('action_info', null),
    (IMAGE_KEY_TO_TAG): [:],
    (TARGET_BRANCH): gitlabParamsFromBot.get('target_branch', 'main'),
    (TRTLLM_VERSION_OVERRIDE): null,
    (RUN_MODE): runMode,
    (MAINTENANCE_ENTRIES): [reason: "", patterns: []],
    (RELEASE_TARGET): runMode == "nightly_release" ?
        normalizeReleaseTargets(gitlabParamsFromBot.get(RELEASE_TARGET, null)) : [],
    (BOLT_PROFILE_REF): "",
    (BOLT_PUBLISH_VARIANT): isBoltReleasePipeline() && ENABLE_BOLT_POSTMERGE_VARIANT,
    (BOLT_PROFILE_BRANCH): "",
    (BOLT_CONSUME_TRIPLES): BOLT_PRODUCER_TRIPLE,
    (JOB_TYPE): env.JOB_BASE_NAME,
    (TRTLLM_RELEASE_REPO): null,
]
globalVars[BUILD_BRANCH] = resolveBuildBranch(globalVars)
// Compare against "true" rather than relying on Groovy truthiness: the bot phrase
// is free-form JSON, and a quoted "false" would otherwise read as opt-in.
// Scope variant publication to post-merge and nightly releases so pre-merge
// consumption remains governed by its own rollout switch and explicit opt-in.
globalVars[BOLT_CONSUME_BUILD] = resolveBoltConsume(
    ENABLE_BOLT_PREMERGE_CONSUME || globalVars[BOLT_PUBLISH_VARIANT] ||
        gitlabParamsFromBot.get(BOLT_CONSUME, false).toString() == "true",
    globalVars[TARGET_BRANCH],
    globalVars[BOLT_PUBLISH_VARIANT])
if (runMode == "nightly_release") {
    globalVars[TRTLLM_VERSION_OVERRIDE] = params.version
}

// If not running all test stages in the L0 pre-merge, we will not update the GitLab status at the end.
// GenPostMergeBuilds pipelines do not update GitLab status.
boolean enableUpdateGitlabStatus =
    !GEN_POST_MERGE_BUILDS_ONLY &&
    !testFilter[INFRA_DRY_RUN] &&
    !testFilter[ENABLE_SKIP_TEST] &&
    !testFilter[ONLY_MULTI_GPU_TEST] &&
    !testFilter[DISABLE_MULTI_GPU_TEST] &&
    !testFilter[DEBUG_MODE] &&
    testFilter[GPU_TYPE_LIST] == null &&
    testFilter[TEST_STAGE_LIST] == null &&
    testFilter[TEST_BACKEND] == null

def createKubernetesPodConfig(image, type, arch = "amd64")
{
    def targetCould = "kubernetes-cpu"
    def selectors = """
                  nvidia.com/node_type: builder
                  kubernetes.io/os: linux"""
    def containerConfig = ""
    def nodeLabelPrefix = ""

    def archSuffix = arch == "arm64" ? "arm" : "amd"
    def jnlpImage = "artifactory.pdx.nvidia.com/sw-ipp-blossom-sre-docker-local/lambda/custom_jnlp_images_${archSuffix}_linux:jdk17"

    switch(type)
    {
    case "agent":
        containerConfig = """
                  - name: alpine
                    image: artifactory.pdx.nvidia.com/docker-remote/alpine:latest
                    command: ['cat']
                    tty: true
                    resources:
                      requests:
                        cpu: '2'
                        memory: 5Gi
                        ephemeral-storage: 25Gi
                      limits:
                        cpu: '2'
                        memory: 5Gi
                        ephemeral-storage: 25Gi
                    imagePullPolicy: Always"""
        nodeLabelPrefix = "cpu"
        break
    case "package":
        containerConfig = """
                  - name: trt-llm
                    image: ${image}
                    command: ['cat']
                    tty: true
                    resources:
                      requests:
                        cpu: '2'
                        memory: 20Gi
                        ephemeral-storage: 25Gi
                      limits:
                        cpu: '2'
                        memory: 20Gi
                        ephemeral-storage: 25Gi
                    imagePullPolicy: Always"""
        nodeLabelPrefix = "cpu"
        break
    }
    def nodeLabel = trtllm_utils.generateNodeLabel(nodeLabelPrefix)
    def podConfig = [
        cloud: targetCould,
        namespace: "sw-tensorrt",
        label: nodeLabel,
        yaml: """
            apiVersion: v1
            kind: Pod
            spec:
                qosClass: Guaranteed
                affinity:
                    nodeAffinity:
                        requiredDuringSchedulingIgnoredDuringExecution:
                            nodeSelectorTerms:
                            - matchExpressions:
                              - key: "tensorrt/taints"
                                operator: DoesNotExist
                              - key: "tensorrt/affinity"
                                operator: NotIn
                                values:
                                - "core"
                                - "qa_only"
                nodeSelector: ${selectors}
                imagePullSecrets:
                  - name: ${ARTIFACTORY_IMAGE_PULL_SECRET}
                containers:
                  ${containerConfig}
                    env:
                    - name: HOST_NODE_NAME
                      valueFrom:
                        fieldRef:
                          fieldPath: spec.nodeName
                  - name: jnlp
                    image: ${jnlpImage}
                    args: ['\$(JENKINS_SECRET)', '\$(JENKINS_NAME)']
                    resources:
                      requests:
                        cpu: '2'
                        memory: 5Gi
                        ephemeral-storage: 25Gi
                      limits:
                        cpu: '2'
                        memory: 5Gi
                        ephemeral-storage: 25Gi
                qosClass: Guaranteed
                volumes:
                - name: sw-tensorrt-pvc
                  persistentVolumeClaim:
                    claimName: sw-tensorrt-pvc

        """.stripIndent(),
    ]

    return podConfig
}

def setupPipelineEnvironment(pipeline, testFilter, globalVars)
{
    sh "env | sort"
    echo "Using GitLab repo: ${LLM_REPO}."
    sh "git config --global --add safe.directory \"*\""
    // NB: getContainerURIs reads files in ${LLM_ROOT}/jenkins/
    if (env.gitlabMergeRequestLastCommit) {
        env.gitlabCommit = env.gitlabMergeRequestLastCommit
        trtllm_utils.checkoutSource(LLM_REPO, env.gitlabCommit, LLM_ROOT, true, true)
    } else {
        branch = env.gitlabBranch ? env.gitlabBranch : "main"
        trtllm_utils.checkoutSource(LLM_REPO, branch, LLM_ROOT, true, true)
        checkoutCommit = sh (script: "cd ${LLM_ROOT} && git rev-parse HEAD",returnStdout: true).trim()
        env.gitlabCommit = checkoutCommit
    }
    loadPipelineModules("${LLM_ROOT}/jenkins/pipelines")
    if (params.program_version_name) {
        env.NSPECT_RELEASE_VERSION = params.program_version_name
    } else {
        def versionFile = readFile("${LLM_ROOT}/tensorrt_llm/version.py")
        def versionMatcher = versionFile =~ /(?m)^__version__ = "([^"]+)"$/
        if (!versionMatcher.find()) {
            error "Unable to read __version__ from ${LLM_ROOT}/tensorrt_llm/version.py"
        }
        def nspectReleaseVersionSuffix = "dev"
        if (runMode == "nightly_release") {
            nspectReleaseVersionSuffix = "nightly"
        } else if (env.JOB_NAME ==~ /.*PostMerge.*/) {
            nspectReleaseVersionSuffix = "postmerge"
        }
        env.NSPECT_RELEASE_VERSION = "${versionMatcher.group(1)}_${nspectReleaseVersionSuffix}"
    }
    echo "Env.gitlabMergeRequestLastCommit: ${env.gitlabMergeRequestLastCommit}."
    echo "Freeze GitLab commit. Branch: ${env.gitlabBranch}. Commit: ${env.gitlabCommit}."
    if (!GEN_POST_MERGE_BUILDS_ONLY && !testFilter[INFRA_DRY_RUN]) {
        trtllm_utils.updateGitlabStatus(BUILD_STATUS_NAME, 'running', GITLAB_PROJECT_ID, env.gitlabCommit)
    }
    if (testFilter[INFRA_DRY_RUN]) {
        pipeline.echo("Changed-file analysis is skipped for the infrastructure dry run.")
        testFilter[(MULTI_GPU_FILE_CHANGED)] = false
        testFilter[(ONLY_ONE_GROUP_CHANGED)] = ""
        testFilter[(AUTO_TRIGGER_TAG_LIST)] = []
        pipeline.echo("CBTS is skipped for the infrastructure dry run.")
    } else {
        testFilter[(MULTI_GPU_FILE_CHANGED)] = mrSelection.getMultiGpuFileChanged(pipeline, testFilter, globalVars)
        testFilter[(ONLY_ONE_GROUP_CHANGED)] = mrSelection.getOnlyOneGroupChanged(pipeline, testFilter, globalVars)
        testFilter[(AUTO_TRIGGER_TAG_LIST)] = mrSelection.getAutoTriggerTagList(pipeline, testFilter, globalVars)
        testFilter[(CBTS_RESULT)] = mrCbts.getCbtsResult(pipeline, testFilter, globalVars)
        // Decide CBTS coverage eligibility here so L0_Test only consumes the propagated flag.
        testFilter[(CBTS_COVERAGE)] = ENABLE_CBTS_COVERAGE && (env.JOB_NAME ==~ /.*PostMerge.*/)
    }
    pipeline.echo("CBTS coverage eligible: ${testFilter[(CBTS_COVERAGE)]}")
    testFilter[(OSS_COMPLIANCE_FILE_CHANGED)] = mrSelection.getOssComplianceFileChanged(pipeline, globalVars)
    mrEnvironment.getContainerURIs().each { k, v ->
        globalVars[k] = v
    }
    globalVars[TRTLLM_RELEASE_REPO] = mrEnvironment.getReleaseRepo()
}

def preparation(pipeline, testFilter, globalVars)
{
    image = "artifactory.pdx.nvidia.com/docker-remote/buildpack-deps:trixie-scm"
    setupPipelineSpec = createKubernetesPodConfig(image, "package")
    trtllm_utils.launchKubernetesPod(pipeline, setupPipelineSpec, "trt-llm", {
        def targetBranchInfo = [:]
        stage("Setup Environment") {
            setupPipelineEnvironment(pipeline, testFilter, globalVars)
        }
        // Must resolve BEFORE launchStages, not inside any branch of it.
        // launchStages forks Release-Check, SBSA and x86_64 in parallel and
        // launchJob serializes globalVars per child job, so a pin assigned in
        // one branch races the jobs in the others: whichever serializes first
        // receives an empty pin and resolves `latest` on its own -- the drift
        // this exists to remove, now with the added property of being
        // nondeterministic. Release Check is also skippable, which would leave
        // the whole pipeline unpinned.
        //
        // preparation() is the earliest correct home: the script-level BOLT
        // setup runs outside any node and cannot shell out, while the stage
        // above has already checked the repo out into ${LLM_ROOT}, which is
        // where the resolver's script lives.
        stage("Pin BOLT Profile Bundle") {
            def pinBranch = globalVars[BUILD_BRANCH]
            def pinRef = mrRelease.resolveBoltProfileRef(pinBranch, BOLT_PRODUCER_TRIPLE)
            globalVars[BOLT_PROFILE_REF] = pinRef
            // The branch is only meaningful alongside a ref, so it is left empty
            // when the pin does not resolve -- a consumer cannot then half-honour
            // a pin. Which architectures consume BOLT is unaffected either way;
            // that is BOLT_CONSUME_TRIPLES, decided independently of this.
            globalVars[BOLT_PROFILE_BRANCH] = pinRef ? pinBranch : ""
        }
        stage("Upload Build Info") {
            try {
                def branch = globalVars[BUILD_BRANCH]
                def buildInfo = "commit=${env.gitlabCommit}\n" +
                    "branch=${branch}\n" +
                    "date=${new Date().format('yyyy-MM-dd HH:mm:ss z', TimeZone.getTimeZone('UTC'))}\n" +
                    "jenkins_url=${env.BUILD_URL}"
                writeFile file: 'build_info.txt', text: buildInfo
                def primary = artifactPaths.llmArtifactsPrimary()
                def buildInfoTarget = primary == "aws" ?
                    "${artifactPaths.llmArtifactsRepoPath('aws', UPLOAD_PATH)}/" : "${UPLOAD_PATH}/"
                trtllm_utils.uploadArtifacts("build_info.txt", buildInfoTarget, primary)
                def buildInfoUrl = artifactPaths.llmArtifactsUrl(primary, "${UPLOAD_PATH}/build_info.txt")
                pipeline.echo "Build info: ${buildInfoUrl}"
            } catch (InterruptedException e) {
                throw e
            } catch (Exception e) {
                pipeline.echo "Upload Build Info failed: ${e.toString()}"
            }
        }
        stage("Merge Test Waive List") {
            if (testFilter[INFRA_DRY_RUN]) {
                echo "Skipping Merge Test Waive List for the infrastructure dry run."
            } else {
                targetBranchInfo = mrEnvironment.mergeWaiveList(pipeline, globalVars)
            }
        }
        stage("Get Maintenance Stage List") {
            if (testFilter[INFRA_DRY_RUN]) {
                echo "Skipping Get Maintenance Stage List for the infrastructure dry run."
            } else {
                mrEnvironment.getMaintenanceStageList(pipeline, globalVars, targetBranchInfo)
            }
        }
    })
}

def resolveBuildBranch(globalVars)
{
    if (globalVars[GITHUB_PR_API_URL]) {
        return "github-pr-" + globalVars[GITHUB_PR_API_URL].split('/').last()
    }
    return env.gitlabBranch ? env.gitlabBranch : "main"
}

// Release consumers need an unoptimized canonical tarball and a separate
// optimized variant. Pin their profiles once so tests and images agree.
def isBoltReleasePipeline()
{
    return (env.JOB_NAME ==~ /.*PostMerge.*/) || runMode == "nightly_release"
}

def resolveBoltConsume(boolean requested, String targetBranch, boolean postMergeVariant = false)
{
    if (!requested) {
        return false
    }
    // Post-merge and nightly release consumers require the canonical tarball
    // to remain unoptimized for profile generation and image-side BOLT.
    if (isBoltReleasePipeline() && !postMergeVariant) {
        echo "BOLT consume requested but disabled: post-merge and nightly releases require an un-BOLTed canonical tarball."
        return false
    }
    if (targetBranch != "main") {
        echo "BOLT consume requested but disabled: no promoted bundle is guaranteed for target branch '${targetBranch}' (main only for now)."
        return false
    }
    echo "BOLT consume enabled: build helpers will re-BOLT their tarball with main's promoted profile bundle."
    return true
}

def collectTestResults(pipeline, testFilter, globalVars)
{
    collectResultPodSpec = createKubernetesPodConfig("", "agent")
    trtllm_utils.launchKubernetesPod(pipeline, collectResultPodSpec, "alpine", {
        // 1. Serial: download tarballs, extract, and run junit
        stage ("Collect Test Result") {
            sh "rm -rf **/*.xml *.tar.gz"

            testResultLink = "https://urm.nvidia.com/artifactory/${UPLOAD_PATH}/test-results"

            trtllm_utils.llmExecStepWithRetry(pipeline, script: "apk add --no-cache curl")
            trtllm_utils.llmExecStepWithRetry(pipeline, script: "apk add python3")
            trtllm_utils.llmExecStepWithRetry(pipeline, script: "wget ${testResultLink}/", allowStepFailed: true)
            sh "cat index.html | grep \"tar.gz\" | cut -d \"\\\"\" -f 2 > result_file_names.txt"
            sh "cat result_file_names.txt"
            trtllm_utils.llmExecStepWithRetry(pipeline, script: "cat result_file_names.txt | xargs -n1 -I {} wget -c -nv ${testResultLink}/{}", allowStepFailed: true)
            sh "ls -l | grep \"tar.gz\" || true"
            resultFileNumber = sh(script: "cat result_file_names.txt | wc -l", returnStdout: true)
            resultFileDownloadedNumber = sh(script: "ls -l | grep \"tar.gz\" | wc -l", returnStdout: true)
            echo "Result File Number: ${resultFileNumber}, Downloaded: ${resultFileDownloadedNumber}"

            sh "find . -name results-\\*.tar.gz -type f -exec tar -zxvf {} \\; || true"
            trtllm_utils.checkoutSource(LLM_REPO, env.gitlabCommit, LLM_ROOT, true, true)

            junit(testResults: '**/results*.xml', allowEmptyResults : true)

            // Pre-install shared dependencies for parallel tasks
            trtllm_utils.llmExecStepWithRetry(pipeline, script: "apk add py3-pip")
            trtllm_utils.llmExecStepWithRetry(pipeline, script: "pip3 config set global.break-system-packages true")
            // This is minimal alpine pod, install the libraries needed by CBTS utilities manually
            trtllm_utils.llmExecStepWithRetry(pipeline, script: "pip3 install -r ${LLM_ROOT}/jenkins/scripts/cbts/requirements.txt")
        } // Collect test result stage

        // 2. Parallel: Rerun Report, Test Coverage, and AI Failure Analysis
        def parallelTasks = [:]
        parallelTasks["Rerun Report"] = {
            try {
            timeout(time: 10, unit: 'MINUTES') {
            stage("Rerun Report") {
                sh "rm -rf rerun && mkdir -p rerun"
                sh "find . -type f -wholename '*/rerun_results.xml' -exec sh -c 'mv \"{}\" \"rerun/\$(basename \$(dirname \"{}\"))_rerun_results.xml\"' \\; || true"
                sh "find rerun -type f"
                def rerunFileCount = sh(returnStdout: true, script: 'find rerun -type f | wc -l').replaceAll("\\s","").toInteger()
                if (rerunFileCount == 0) {
                    echo "Rerun report is skipped because there is no rerun test data file."
                    return
                }
                def xmlFiles = findFiles(glob: 'rerun/**/*.xml')
                def xmlFileList = xmlFiles.collect { it.path }
                def inputfiles = xmlFileList.join(',')
                echo "inputfiles: ${inputfiles}"
                sh """
                    python3 llm/jenkins/scripts/test_rerun.py \
                    generate_rerun_report \
                    --output-file=rerun/rerun_report.xml \
                    --input-files=${inputfiles}
                """
                if (fileExists("rerun/rerun_report.html")) {
                    try {
                        trtllm_utils.uploadArtifacts("rerun/rerun_report.html", "${UPLOAD_PATH}/test-results/")
                        echo "Rerun report: https://urm.nvidia.com/artifactory/${UPLOAD_PATH}/test-results/rerun_report.html"
                    } catch (Exception e) {
                        echo "Failed to upload rerun report: ${e.toString()}"
                    }
                } else {
                    echo "No rerun test results found, skipping rerun report upload."
                }
                catchError(
                    buildResult: 'SUCCESS',
                    stageResult: 'UNSTABLE') {
                    error "Some failed tests were reruned, please check the rerun report."
                }
            } // Rerun report stage
            } // timeout 10 min
            } catch (Exception e) {
                echo "Rerun Report failed or timed out: ${e.toString()}"
            }
        }
        parallelTasks["Test Coverage"] = {
            try {
            timeout(time: 10, unit: 'MINUTES') {
            try {
                stage("Test Coverage") {
                    sh "ls"
                    sh "rm -rf cov && mkdir -p cov"
                    // Collect the per-process PY_START data files from every stage's results tarball.
                    sh "find . -type f -name '.cbtscov.*.sqlite' -exec mv -t cov/ {} + || true"
                    sh "cd cov && ls -la"
                    def fileCount = sh(returnStdout: true, script: 'find cov -name ".cbtscov.*.sqlite" | wc -l').replaceAll("\\s","").toInteger()
                    if (fileCount == 0) {
                        echo "CBTS coverage skipped: no PY_START data files."
                        return
                    }
                    // Merge into the indexed touch DB, a per-file HTML report, and the coverage rate.
                    sh """
                        PYTHONPATH=llm/jenkins/scripts/cbts \
                        python3 -m cbts.command coverage collection pystart-report \
                            --glob 'cov/.cbtscov.*.sqlite' \
                            --out-sqlite cov/cbts_touchmap.sqlite \
                            --out-dir cov/cbts_report \
                            --source-root llm
                    """
                    // Upload compressed only: the guardword scanner byte-matches raw files but not archives.
                    sh "cd cov && tar czf cbts_pystart_report.tar.gz cbts_touchmap.sqlite cbts_report"
                    trtllm_utils.uploadArtifacts("cov/cbts_pystart_report.tar.gz", "${UPLOAD_PATH}/cbts-coverage/")
                    echo "CBTS coverage (touch DB + report): https://urm.nvidia.com/artifactory/${UPLOAD_PATH}/cbts-coverage/cbts_pystart_report.tar.gz"
                } // Test coverage
            }
            catch (InterruptedException e)
            {
                throw e
            }
            catch (Exception e)
            {
                pipeline.echo("Test coverage failed execution.")
            }
            } // timeout 10 min
            } catch (Exception e) {
                echo "Test Coverage failed or timed out: ${e.toString()}"
            }
        }
        if (currentBuild.currentResult == 'FAILURE') {
            parallelTasks["AI Failure Analysis"] = {
                try {
                timeout(time: 10, unit: 'MINUTES') {
                stage("AI Failure Analysis") {
                    try {
                        def prNumber = null
                        if (globalVars[GITHUB_PR_API_URL]) {
                            def prMatch = (globalVars[GITHUB_PR_API_URL] =~ /\/pulls?\/(\d+)/)
                            if (prMatch) {
                                prNumber = prMatch[0][1]
                            }
                        }
                        def analysis = trtllm_utils.analyzePipelineFailureWithAgent(
                            pipeline, env.JOB_NAME, env.BUILD_NUMBER, prNumber)
                        if (analysis) {
                            def bucket = 'sw-tensorrt-ci-analysis'
                            def key = "${env.JOB_NAME}/${env.BUILD_NUMBER}/failure_analysis.html"
                            def htmlUrl = "https://pbss.s8k.io/v1/AUTH_svc_tensorrt/${bucket}/${key}"
                            // Self-rendering HTML page: marked.js parses the analysis at page load
                            // and DOMPurify sanitises the result before injection into the DOM. The
                            // analysis text comes from the CI agent which consumes build logs (which
                            // can include attacker-controlled PR content), so we treat it as untrusted.
                            // Hardening:
                            //   1. CDN scripts pinned to specific versions and protected with SRI.
                            //   2. Analysis embedded in a `<script type="application/json">` data
                            //      block read via textContent + JSON.parse — never inlined into
                            //      executable JS source. Every `<` in the JSON is rewritten to its
                            //      JSON unicode escape so a payload cannot smuggle a `</script>`
                            //      and break out of the data block.
                            //   3. marked output is run through DOMPurify before innerHTML assignment
                            //      to strip event-handler attributes and other XSS vectors.
                            def jsonAnalysis = groovy.json.JsonOutput.toJson(analysis).replace("<", "\\u003c")
                            def htmlDoc = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8">
<title>CI Failure Analysis &middot; ${env.JOB_NAME} #${env.BUILD_NUMBER}</title>
<script src="https://cdn.jsdelivr.net/npm/marked@14.1.4/marked.min.js" integrity="sha384-lqPzN0kmFw9t2syAMwVPM4VbAyqsz/lPyYWbb2Xt6nSPM0WPNrpSWCUBgdcAdgnC" crossorigin="anonymous"></script>
<script src="https://cdn.jsdelivr.net/npm/dompurify@3.2.4/dist/purify.min.js" integrity="sha384-eEu5CTj3qGvu9PdJuS+YlkNi7d2XxQROAFYOr59zgObtlcux1ae1Il3u7jvdCSWu" crossorigin="anonymous"></script>
<style>body{font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;max-width:900px;margin:2em auto;padding:0 1em;color:#24292e}h1,h2,h3{border-bottom:1px solid #eaecef;padding-bottom:.3em}pre{background:#f6f8fa;padding:1em;overflow:auto;border-radius:6px}code{background:#f6f8fa;padding:.2em .4em;border-radius:3px}pre code{background:none;padding:0}a{color:#0366d6}blockquote{border-left:4px solid #dfe2e5;padding:0 1em;color:#6a737d}table{border-collapse:collapse}th,td{border:1px solid #dfe2e5;padding:6px 13px}header{margin-bottom:1.5em;color:#586069}</style>
</head><body>
<header><a href="${env.BUILD_URL}">${env.JOB_NAME} #${env.BUILD_NUMBER}</a></header>
<main id="md"></main>
<script id="md-source" type="application/json">${jsonAnalysis}</script>
<script>
  // Disable marked's strikethrough tokenizer: CI failure-analysis text routinely
  // contains literal tildes (~/path, ~50ms, regex anchors, etc.) that should not
  // be interpreted as markup. Other GFM extensions (tables, fences, autolinks,
  // task lists) stay enabled.
  marked.use({ tokenizer: { del() { return false; } } });
  const src = JSON.parse(document.getElementById('md-source').textContent);
  document.getElementById('md').innerHTML = DOMPurify.sanitize(marked.parse(src));
</script>
</body></html>
"""
                            writeFile file: 'failure_analysis.html', text: htmlDoc
                            trtllm_utils.llmExecStepWithRetry(pipeline, script: 'apk add --no-cache aws-cli')
                            // Alpine's musl libc fires A and AAAA queries in parallel; pbss.s8k.io's AAAA
                            // returns SERVFAIL and musl treats that as a fatal lookup failure (glibc would
                            // not). Pin the A-record IP in /etc/hosts so getaddrinfo resolves from files.
                            trtllm_utils.llmExecStepWithRetry(pipeline, script: '''
                                if ! grep -q 'pbss.s8k.io' /etc/hosts; then
                                    ip=$(nslookup -type=A pbss.s8k.io 2>/dev/null | awk '/^Address[: ]/ && $NF !~ /:53$/ && $NF !~ /#53$/ { print $NF; exit }')
                                    if [ -n "$ip" ]; then
                                        printf '%s\\n' "$ip pbss.s8k.io" >> /etc/hosts
                                    fi
                                fi
                            ''')
                            withCredentials([string(
                                    credentialsId: 'svc_tensorrt-swift-stack-key',
                                    variable: 'AWS_SECRET_ACCESS_KEY')]) {
                                trtllm_utils.llmExecStepWithRetry(pipeline, script:
                                    "AWS_ACCESS_KEY_ID=svc_tensorrt aws s3 cp failure_analysis.html" +
                                    " 's3://${bucket}/${key}' --endpoint-url https://pbss.s8k.io" +
                                    " --content-type text/html")
                            }
                            // Surface the URL via currentBuild.description so the upstream PR_Github
                            // wrapper can extract it and include it in the GitHub PR comment.
                            def existingDesc = currentBuild.description ?: ""
                            currentBuild.description = existingDesc +
                                (existingDesc ? "<br/>" : "") +
                                "<a href='${htmlUrl}'>CI Agent Failure Analysis</a>"
                            echo "CI Agent Failure Analysis: ${htmlUrl}"
                        }
                    } catch (Exception e) {
                        // Analysis is best-effort; do not fail the pipeline
                    }
                }
                } // timeout 10 min
                } catch (Exception e) {
                    echo "AI Failure Analysis failed or timed out: ${e.toString()}"
                }
            }
        }
        parallel parallelTasks
    })
}

def loadPipelineModules(String sourceRoot)
{
    artifactPaths = load("${sourceRoot}/common/ArtifactPaths.groovy").initialize(this)
    mrCbts = load("${sourceRoot}/mr/Cbts.groovy").initialize(this)
    mrChanges = load("${sourceRoot}/mr/Changes.groovy").initialize(this)
    mrEnvironment = load("${sourceRoot}/mr/Environment.groovy").initialize(this)
    mrRelease = load("${sourceRoot}/mr/Release.groovy").initialize(this)
    mrResults = load("${sourceRoot}/mr/Results.groovy").initialize(this)
    mrSelection = load("${sourceRoot}/mr/Selection.groovy").initialize(this)
    mrStages = load("${sourceRoot}/mr/Stages.groovy").initialize(this)
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
        // CBTS decision telemetry; auto-exposes _USR/_PSW that open_search_db.py reads.
        OPEN_SEARCH_DB_BASE_URL=credentials("open_search_db_base_url")
        OPEN_SEARCH_DB_CREDENTIALS=credentials("open_search_db_credentials")
    }
    post {
        unsuccessful {
            script {
                if (!GEN_POST_MERGE_BUILDS_ONLY && !testFilter[INFRA_DRY_RUN]) {
                    trtllm_utils.updateGitlabStatus(BUILD_STATUS_NAME, "failed", GITLAB_PROJECT_ID, env.gitlabCommit)
                }
            }
        }
        success {
            script {
                if (!testFilter[INFRA_DRY_RUN]) {
                    if (enableUpdateGitlabStatus) {
                        trtllm_utils.updateGitlabStatus(BUILD_STATUS_NAME, "success", GITLAB_PROJECT_ID, env.gitlabCommit)
                    } else if (!GEN_POST_MERGE_BUILDS_ONLY) {
                        trtllm_utils.updateGitlabStatus(BUILD_STATUS_NAME, "canceled", GITLAB_PROJECT_ID, env.gitlabCommit)
                        trtllm_utils.updateGitlabStatus("Custom Jenkins build", "success", GITLAB_PROJECT_ID, env.gitlabCommit)
                    }
                }
            }
        }
        aborted {
            script {
                if (!GEN_POST_MERGE_BUILDS_ONLY && !testFilter[INFRA_DRY_RUN]) {
                    trtllm_utils.updateGitlabStatus(BUILD_STATUS_NAME, 'canceled', GITLAB_PROJECT_ID, env.gitlabCommit)
                }
            }
        }
        always {
            script {
                if (!isReleaseCheckMode &&
                    !GEN_POST_MERGE_BUILDS_ONLY &&
                    runMode != "nightly_release") {
                    collectTestResults(this, testFilter, globalVars)
                }
            }
        }
    }
    stages {
        stage("Preparation")
        {
            steps
            {
                script {
                    globalVars = trtllm_utils.initializeCiBudget(this, globalVars, 24, 'HOURS', 'L0_MergeRequest')
                    preparation(this, testFilter, globalVars)
                    println globalVars
                    globalVars[ACTION_INFO] = trtllm_utils.setupPipelineDescription(this, globalVars[ACTION_INFO])
                    echo "enableFailFast is: ${enableFailFast}"
                    echo "env.gitlabTriggerPhrase is: ${env.gitlabTriggerPhrase}"
                    println testFilter
                    echo "Check the passed GitLab bot testFilter parameters."
                }
            }
        }
        stage("Build And Test") {
            steps {
                script {
                    if (isReleaseCheckMode) {
                        def releaseCheckStages = [
                            "Release-Check": {
                                stage("Release-Check") {
                                    if (testFilter[INFRA_DRY_RUN]) {
                                        echo "Skipping Release-Check for the infrastructure dry run."
                                    } else {
                                        mrRelease.launchReleaseCheck(this, globalVars)
                                    }
                                }
                            },
                            "Check-Test-List": {
                                stage("Check-Test-List") {
                                    if (testFilter[INFRA_DRY_RUN]) {
                                        mrRelease.skipStage("[Check-Test-List] Run", "Skipping Check Test List for the infrastructure dry run.")
                                    } else if (testFilter[(ONLY_ONE_GROUP_CHANGED)] == "Docs") {
                                        mrRelease.skipStage("[Check-Test-List] Run", "Skipping Check Test List for Docs-only changes.")
                                    } else {
                                        mrRelease.launchTestListCheck(this, globalVars)
                                    }
                                }
                            },
                        ]
                        releaseCheckStages.failFast = enableFailFast
                        parallel releaseCheckStages
                    } else {
                        // globalVars[CACHED_CHANGED_FILE_LIST] is only used in setupPipelineEnvironment
                        // Remove it to workaround the "Argument list too long" error
                        globalVars.remove(CACHED_CHANGED_FILE_LIST)
                        mrStages.launchStages(this, reuseBuild, testFilter, enableFailFast, globalVars)
                    }
                }
            }
        }
    } // stages
} // pipeline
