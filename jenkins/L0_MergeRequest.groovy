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

@Field def podFactory
@Field def runParameters
@Field def parentResults

def ciLibrary = library(
    identifier: "trtllm-ci@${env.gitlabMergeRequestLastCommit ?: env.gitlabCommit ?: env.gitlabBranch ?: 'main'}",
    changelog: false,
    retriever: legacySCM(scm: scm, libraryPath: 'jenkins/pipeline-library')
).trtllm.ci
podFactory = ciLibrary.pods.PodFactory.new()
runParameters = ciLibrary.parameters.RunParameters.new()
parentResults = ciLibrary.results.ParentResults.new()

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

def testFilter = runParameters.parentTestFilter(
    gitlabParamsFromBot, env.JOB_NAME ==~ /.*PostMerge.*/,
    params.InfraDryRun?.toString()?.toBoolean() ?: false)

String reuseBuild = gitlabParamsFromBot.get('reuse_build', null)

// The one triple with a BOLT profile producer. BoltProfileGen runs aarch64 only,
// so this is the only triple with a promoted bundle to resolve or apply. Named
// once because the pin resolver and the consume scope below must not disagree.
@Field def BOLT_PRODUCER_TRIPLE = "aarch64-linux-gnu"
def globalVars = [
    (runParameters.GITHUB_PR_API_URL): gitlabParamsFromBot.get('github_pr_api_url', null),
    (runParameters.BASE_COMMIT): gitlabParamsFromBot.get('base_commit', null),
    (runParameters.CACHED_CHANGED_FILE_LIST): null,
    (runParameters.ACTION_INFO): gitlabParamsFromBot.get('action_info', null),
    (runParameters.IMAGE_KEY_TO_TAG): [:],
    (runParameters.TARGET_BRANCH): gitlabParamsFromBot.get('target_branch', 'main'),
    (runParameters.TRTLLM_VERSION_OVERRIDE): null,
    (runParameters.RUN_MODE): runMode,
    (runParameters.MAINTENANCE_ENTRIES): [reason: "", patterns: []],
    (runParameters.RELEASE_TARGET): runMode == "nightly_release" ?
        normalizeReleaseTargets(gitlabParamsFromBot.get(runParameters.RELEASE_TARGET, null)) : [],
    (runParameters.BOLT_PROFILE_REF): "",
    (runParameters.BOLT_PUBLISH_VARIANT): isBoltReleasePipeline() && ENABLE_BOLT_POSTMERGE_VARIANT,
    (runParameters.BOLT_PROFILE_BRANCH): "",
    (runParameters.BOLT_CONSUME_TRIPLES): BOLT_PRODUCER_TRIPLE,
    (runParameters.JOB_TYPE): env.JOB_BASE_NAME,
    (runParameters.TRTLLM_RELEASE_REPO): null,
]
globalVars[runParameters.BUILD_BRANCH] = resolveBuildBranch(globalVars)
// Compare against "true" rather than relying on Groovy truthiness: the bot phrase
// is free-form JSON, and a quoted "false" would otherwise read as opt-in.
// Scope variant publication to post-merge and nightly releases so pre-merge
// consumption remains governed by its own rollout switch and explicit opt-in.
globalVars[runParameters.BOLT_CONSUME_BUILD] = resolveBoltConsume(
    ENABLE_BOLT_PREMERGE_CONSUME || globalVars[runParameters.BOLT_PUBLISH_VARIANT] ||
        gitlabParamsFromBot.get(runParameters.BOLT_CONSUME, false).toString() == "true",
    globalVars[runParameters.TARGET_BRANCH],
    globalVars[runParameters.BOLT_PUBLISH_VARIANT])
if (runMode == "nightly_release") {
    globalVars[runParameters.TRTLLM_VERSION_OVERRIDE] = params.version
}

// If not running all test stages in the L0 pre-merge, we will not update the GitLab status at the end.
// GenPostMergeBuilds pipelines do not update GitLab status.
boolean enableUpdateGitlabStatus =
    !GEN_POST_MERGE_BUILDS_ONLY &&
    !testFilter[runParameters.INFRA_DRY_RUN] &&
    !testFilter[runParameters.ENABLE_SKIP_TEST] &&
    !testFilter[runParameters.ONLY_MULTI_GPU_TEST] &&
    !testFilter[runParameters.DISABLE_MULTI_GPU_TEST] &&
    !testFilter[runParameters.DEBUG_MODE] &&
    testFilter[runParameters.GPU_TYPE_LIST] == null &&
    testFilter[runParameters.TEST_STAGE_LIST] == null &&
    testFilter[runParameters.TEST_BACKEND] == null

def createKubernetesPodConfig(image, type, arch = "amd64")
{
    def pod = podFactory.mergeRequestPod(image, type, arch)
    pod.label = trtllm_utils.generateNodeLabel(pod.label)
    return pod
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
        def branch = env.gitlabBranch ? env.gitlabBranch : "main"
        trtllm_utils.checkoutSource(LLM_REPO, branch, LLM_ROOT, true, true)
        def checkoutCommit = sh (script: "cd ${LLM_ROOT} && git rev-parse HEAD",returnStdout: true).trim()
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
    if (!GEN_POST_MERGE_BUILDS_ONLY && !testFilter[runParameters.INFRA_DRY_RUN]) {
        trtllm_utils.updateGitlabStatus(BUILD_STATUS_NAME, 'running', GITLAB_PROJECT_ID, env.gitlabCommit)
    }
    if (testFilter[runParameters.INFRA_DRY_RUN]) {
        pipeline.echo("Changed-file analysis is skipped for the infrastructure dry run.")
        testFilter[(runParameters.MULTI_GPU_FILE_CHANGED)] = false
        testFilter[(runParameters.ONLY_ONE_GROUP_CHANGED)] = ""
        testFilter[(runParameters.AUTO_TRIGGER_TAG_LIST)] = []
        pipeline.echo("CBTS is skipped for the infrastructure dry run.")
    } else {
        testFilter[(runParameters.MULTI_GPU_FILE_CHANGED)] = mrSelection.getMultiGpuFileChanged(pipeline, testFilter, globalVars)
        testFilter[(runParameters.ONLY_ONE_GROUP_CHANGED)] = mrSelection.getOnlyOneGroupChanged(pipeline, testFilter, globalVars)
        testFilter[(runParameters.AUTO_TRIGGER_TAG_LIST)] = mrSelection.getAutoTriggerTagList(pipeline, testFilter, globalVars)
        testFilter[(runParameters.CBTS_RESULT)] = mrCbts.getCbtsResult(pipeline, testFilter, globalVars)
        // Decide CBTS coverage eligibility here so L0_Test only consumes the propagated flag.
        testFilter[(runParameters.CBTS_COVERAGE)] = ENABLE_CBTS_COVERAGE && (env.JOB_NAME ==~ /.*PostMerge.*/)
    }
    pipeline.echo("CBTS coverage eligible: ${testFilter[(runParameters.CBTS_COVERAGE)]}")
    testFilter[(runParameters.OSS_COMPLIANCE_FILE_CHANGED)] = mrSelection.getOssComplianceFileChanged(pipeline, globalVars)
    mrEnvironment.getContainerURIs().each { k, v ->
        globalVars[k] = v
    }
    globalVars[runParameters.TRTLLM_RELEASE_REPO] = mrEnvironment.getReleaseRepo()
}

def preparation(pipeline, testFilter, globalVars)
{
    def image = "artifactory.pdx.nvidia.com/docker-remote/buildpack-deps:trixie-scm"
    def setupPipelineSpec = createKubernetesPodConfig(image, "package")
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
            def pinBranch = globalVars[runParameters.BUILD_BRANCH]
            def pinRef = mrRelease.resolveBoltProfileRef(pinBranch, BOLT_PRODUCER_TRIPLE)
            globalVars[runParameters.BOLT_PROFILE_REF] = pinRef
            // The branch is only meaningful alongside a ref, so it is left empty
            // when the pin does not resolve -- a consumer cannot then half-honour
            // a pin. Which architectures consume BOLT is unaffected either way;
            // that is BOLT_CONSUME_TRIPLES, decided independently of this.
            globalVars[runParameters.BOLT_PROFILE_BRANCH] = pinRef ? pinBranch : ""
        }
        stage("Upload Build Info") {
            try {
                def branch = globalVars[runParameters.BUILD_BRANCH]
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
            if (testFilter[runParameters.INFRA_DRY_RUN]) {
                echo "Skipping Merge Test Waive List for the infrastructure dry run."
            } else {
                targetBranchInfo = mrEnvironment.mergeWaiveList(pipeline, globalVars)
            }
        }
        stage("Get Maintenance Stage List") {
            if (testFilter[runParameters.INFRA_DRY_RUN]) {
                echo "Skipping Get Maintenance Stage List for the infrastructure dry run."
            } else {
                mrEnvironment.getMaintenanceStageList(pipeline, globalVars, targetBranchInfo)
            }
        }
    })
}

def resolveBuildBranch(globalVars)
{
    if (globalVars[runParameters.GITHUB_PR_API_URL]) {
        return "github-pr-" + globalVars[runParameters.GITHUB_PR_API_URL].split('/').last()
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
                if (!GEN_POST_MERGE_BUILDS_ONLY && !testFilter[runParameters.INFRA_DRY_RUN]) {
                    trtllm_utils.updateGitlabStatus(BUILD_STATUS_NAME, "failed", GITLAB_PROJECT_ID, env.gitlabCommit)
                }
            }
        }
        success {
            script {
                if (!testFilter[runParameters.INFRA_DRY_RUN]) {
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
                if (!GEN_POST_MERGE_BUILDS_ONLY && !testFilter[runParameters.INFRA_DRY_RUN]) {
                    trtllm_utils.updateGitlabStatus(BUILD_STATUS_NAME, 'canceled', GITLAB_PROJECT_ID, env.gitlabCommit)
                }
            }
        }
        always {
            script {
                if (!isReleaseCheckMode &&
                    !GEN_POST_MERGE_BUILDS_ONLY &&
                    runMode != "nightly_release") {
                    parentResults.collectTestResults(this, testFilter, globalVars)
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
                    globalVars[runParameters.ACTION_INFO] = trtllm_utils.setupPipelineDescription(this, globalVars[runParameters.ACTION_INFO])
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
                                    if (testFilter[runParameters.INFRA_DRY_RUN]) {
                                        echo "Skipping Release-Check for the infrastructure dry run."
                                    } else {
                                        mrRelease.launchReleaseCheck(this, globalVars)
                                    }
                                }
                            },
                            "Check-Test-List": {
                                stage("Check-Test-List") {
                                    if (testFilter[runParameters.INFRA_DRY_RUN]) {
                                        mrRelease.skipStage("[Check-Test-List] Run", "Skipping Check Test List for the infrastructure dry run.")
                                    } else if (testFilter[(runParameters.ONLY_ONE_GROUP_CHANGED)] == "Docs") {
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
                        globalVars.remove(runParameters.CACHED_CHANGED_FILE_LIST)
                        mrStages.launchStages(this, reuseBuild, testFilter, enableFailFast, globalVars)
                    }
                }
            }
        }
    } // stages
} // pipeline
