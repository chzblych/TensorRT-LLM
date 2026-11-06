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
import com.nvidia.bloom.Logger
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils as jUtils

@Field def pipeline

def initialize(pipeline)
{
    this.pipeline = pipeline
    return this
}

def launchStages(pipeline, reuseBuild, testFilter, enableFailFast, globalVars)
{
    def releaseTargets = globalVars[pipeline.RELEASE_TARGET] ?: []
    def wheelSelected = releaseTargets.contains("wheel")
    def containerSelected = releaseTargets.contains("container")
    def buildAllDockerImages = false
    def runContainerScanning = false
    pipeline.stages = [
        "Release-Check": {
            pipeline.script {
                if (testFilter[pipeline.INFRA_DRY_RUN]) {
                    pipeline.echo "Skipping Release-Check for the infrastructure dry run."
                    return
                } else if (pipeline.GEN_POST_MERGE_BUILDS_ONLY) {
                    pipeline.echo "Skipping Release-Check (GenPostMergeBuilds mode: builds only)"
                    return
                }
                pipeline.mrRelease.launchReleaseCheck(pipeline, globalVars)
            }
        },
        "Check-Test-List": {
            pipeline.script {
                if (testFilter[pipeline.INFRA_DRY_RUN]) {
                    pipeline.mrRelease.skipStage("[Check-Test-List] Run", "Skipping Check Test List for the infrastructure dry run.")
                    return
                } else if (pipeline.GEN_POST_MERGE_BUILDS_ONLY) {
                    pipeline.mrRelease.skipStage("[Check-Test-List] Run", "Skipping Check Test List (GenPostMergeBuilds mode: builds only)")
                    return
                } else if (pipeline.runMode == "nightly_release") {
                    pipeline.mrRelease.skipStage("[Check-Test-List] Run", "Skipping Check Test List for nightly_release.")
                    return
                } else if (testFilter[(pipeline.ONLY_ONE_GROUP_CHANGED)] == "Docs") {
                    pipeline.mrRelease.skipStage("[Check-Test-List] Run", "Skipping Check Test List for Docs-only changes.")
                    return
                } else if (pipeline.env.JOB_NAME ==~ /.*BuildDockerImageSanityTest.*/) {
                    pipeline.mrRelease.skipStage("[Check-Test-List] Run", "Skipping Check Test List for BuildDockerImageSanityTest.")
                    return
                }
                pipeline.mrRelease.launchTestListCheck(pipeline, globalVars)
            }
        },
        "OSS-Compliance-Check": {
            pipeline.script {
                pipeline.stage("[OSS-Compliance-Check] Run") {
                    // Nightly releases always scan; merge requests scan only when compliance files change.
                    def isNightlyRelease = pipeline.runMode == "nightly_release"
                    if (!isNightlyRelease && !testFilter[(pipeline.OSS_COMPLIANCE_FILE_CHANGED)]) {
                        pipeline.echo "Skipping OSS Compliance Check: no OSS compliance-related files changed."
                        jUtils.markStageSkippedForConditional(pipeline.STAGE_NAME)
                        return
                    }
                    if (pipeline.GEN_POST_MERGE_BUILDS_ONLY) {
                        pipeline.echo "Skipping OSS Compliance Check (GenPostMergeBuilds mode: builds only)"
                        jUtils.markStageSkippedForConditional(pipeline.STAGE_NAME)
                        return
                    }
                    def repoUrlKey = pipeline.mrRelease.getPLCRepoUrlKey()
                    def logger = new Logger(pipeline)
                    try {
                        // find_plc_build.py only needs python3 + network access to Jenkins, but
                        // the top-level pipeline agent (createKubernetesPodConfig("", "agent")) is
                        // a bare alpine container with neither, so run the lookup in a "package"
                        // pod instead (same image used by preparation()'s mergeWaiveList.py call).
                        def cached
                        def packageImage = "urm.nvidia.com/docker/buildpack-deps:trixie-scm"
                        pipeline.trtllm_utils.launchKubernetesPod(pipeline, pipeline.createKubernetesPodConfig(packageImage, "package"), "trt-llm", {
                            pipeline.trtllm_utils.checkoutSource(pipeline.LLM_REPO, pipeline.env.gitlabCommit, pipeline.LLM_ROOT, true, true)
                            cached = pipeline.mrRelease.findCachedPLCSourceScanResult(pipeline.env.gitlabCommit)
                        })
                        def handleResult
                        if (cached) {
                            pipeline.echo "Commit ${pipeline.env.gitlabCommit} unchanged since a prior PLC source-code scan; reusing its result: ${cached.result}"
                            pipeline.echo "Reused PLC scan pipeline: ${cached.url}"
                            handleResult = cached.result
                        } else {
                            pipeline.echo "Triggering OSS Compliance (PLC) scan for ref: ${pipeline.env.gitlabCommit}"
                            def params = [
                                pipeline.string(name: 'ref', value: pipeline.env.gitlabCommit),
                                pipeline.string(name: 'repoUrlKey', value: repoUrlKey),
                                pipeline.string(name: 'forkOwner', value: ''),
                                pipeline.string(name: 'postMergePipelineName', value: ''),
                                pipeline.string(name: 'postMergeBuildNumber', value: ''),
                                pipeline.string(name: 'scanMode', value: isNightlyRelease ? 'release' : 'pre_merge'),
                                pipeline.string(name: 'runSourceCodeScanning', value: 'true'),
                                pipeline.string(name: 'runContainerOssScanning', value: 'false'),
                                pipeline.string(name: 'runContainerMalwareScanning', value: 'false'),
                                pipeline.string(name: 'runSonarQube', value: 'false'),
                            ]
                            def handle = pipeline.build(
                                job: "/LLM/helpers/PLCScanningSetup",
                                parameters: params,
                                propagate: false
                            )
                            handleResult = handle.result
                        }
                        if (handleResult == "UNSTABLE" && !isNightlyRelease) {
                            logger.log("OSS Compliance Check downstream job is UNSTABLE, ignoring")
                        } else if (handleResult != "SUCCESS") {
                            pipeline.error "Downstream job did not succeed"
                        }
                    } catch (InterruptedException e) {
                        throw e
                    } catch (Exception e) {
                        if (isNightlyRelease) {
                            throw e
                        }
                        pipeline.catchError(buildResult: 'Failure', stageResult: 'Failure') {
                            pipeline.error "OSS Compliance Check failed: ${e.getMessage()}, please rerun."
                        }
                    }
                }
            }
        },
        "x86_64-Linux": {
            pipeline.script {
                // CBTS deliberately does NOT short-circuit at the arch / Build
                // layer. Build normally runs so a wheel exists for sanity checks
                // and post-merge consumers; case-level narrowing happens later
                // in L0_Test.groovy::launchTestJobs (Layer 2) and renderTestDB
                // (Layer 3).
                // Nightly publishes the PackageSanityCheck wheel, while this
                // build still provides the source tar consumed by test runners.
                def testStageName = "[Build-x86_64] Remote Run"
                def buildInfraIncomplete = false
                pipeline.stage(testStageName) {
                    def additionalParameters = [
                        'dockerImage': globalVars["LLM_DOCKER_IMAGE"],
                        'wheelDockerImagePy310': globalVars["LLM_ROCKYLINUX8_PY310_DOCKER_IMAGE"],
                        'wheelDockerImagePy312': globalVars["LLM_ROCKYLINUX8_PY312_DOCKER_IMAGE"],
                        // Re-BOLT the packed tarball with the target branch's promoted
                        // profile bundle so the tests below exercise bolted binaries.
                        // Off unless resolveBoltConsume() allowed it (pre-merge, main).
                        'boltConsume': globalVars[pipeline.BOLT_CONSUME_BUILD],
                        // Publish the BOLTed build beside an untouched canonical rather
                        // than replacing it. Post-merge only: canonical is the un-BOLTed
                        // tarball BoltProfileGen profiles.
                        'boltPublishVariant': globalVars[pipeline.BOLT_PUBLISH_VARIANT],
                    ]
                    // launchJob returns UNSTABLE (without throwing) when the build
                    // sub-job was infra-incomplete: only infra aborts, no genuine
                    // build failure (see runBranchesWithInfraDefer in Build.groovy).
                    def buildStatus = pipeline.mrRelease.launchJob(pipeline, "/LLM/helpers/Build-x86_64", reuseBuild, enableFailFast, globalVars, additionalParameters)
                    buildInfraIncomplete = (buildStatus == "UNSTABLE")
                }

                if (pipeline.GEN_POST_MERGE_BUILDS_ONLY) {
                    pipeline.echo "Skipping x86_64 tests (GenPostMergeBuilds mode: builds only)"
                    return
                }
                if (testFilter[(pipeline.TEST_STAGE_LIST)]?.contains("NGC-Container-Scaning")) {
                    pipeline.echo "Skipping x86_64 tests (PLC container scanning)"
                    return
                }
                if (testFilter[pipeline.INFRA_DRY_RUN]) {
                    def imageParameters = [
                        'dockerImage': globalVars["LLM_DOCKER_IMAGE"],
                        'wheelDockerImagePy310': globalVars["LLM_ROCKYLINUX8_PY310_DOCKER_IMAGE"],
                        'wheelDockerImagePy312': globalVars["LLM_ROCKYLINUX8_PY312_DOCKER_IMAGE"],
                    ]
                    pipeline.mrRelease.launchInfraDryRunTestJob(pipeline, "x86_64", testFilter, globalVars, imageParameters)
                    return
                }

                // Build was infra-incomplete (UNSTABLE): the wheel/tar this arch's
                // test sub-jobs would consume was never produced, so launching them
                // can only fail on a missing artifact. Skip them WITHOUT throwing --
                // throwing is exactly what trips the arch-level failFast and cancels
                // the healthy sibling architecture and its consumers. Keep the build
                // UNSTABLE (already set by launchJob); do NOT escalate to FAILURE.
                // Unlike the single-GPU-infra-incomplete policy below, post-merge
                // skips too: with no artifact there is no signal to be had.
                if (buildInfraIncomplete) {
                    pipeline.stage("[Test-x86_64] Blocked - build infra-incomplete") {
                        pipeline.echo "x86_64 build was infra-incomplete (UNSTABLE); skipping x86_64 test sub-jobs (no artifact to test). Build stays UNSTABLE."
                    }
                    return
                }

                testStageName = "[Test-x86_64-Single-GPU] Remote Run"
                def singleGpuTestFailed = false
                def singleGpuInfraIncomplete = false
                pipeline.stage(testStageName) {
                    if (pipeline.X86_TEST_CHOICE == pipeline.STAGE_CHOICE_SKIP) {
                        pipeline.echo "x86_64 test job is skipped due to Jenkins configuration"
                        return
                    }
                    try {
                        String testFilterJson = pipeline.writeJSON returnText: true, json: testFilter
                        def additionalParameters = [
                            'testFilter': testFilterJson,
                            'dockerImage': globalVars["LLM_DOCKER_IMAGE"],
                            'wheelDockerImagePy310': globalVars["LLM_ROCKYLINUX8_PY310_DOCKER_IMAGE"],
                            'wheelDockerImagePy312': globalVars["LLM_ROCKYLINUX8_PY312_DOCKER_IMAGE"],
                        ]

                        // launchJob returns UNSTABLE (without throwing) when the single-GPU
                        // sub-job was infra-incomplete: only infra aborts, no real failure.
                        def singleGpuStatus = pipeline.mrRelease.launchJob(pipeline, "L0_Test-x86_64-Single-GPU", false, enableFailFast, globalVars, additionalParameters)
                        singleGpuInfraIncomplete = (singleGpuStatus == "UNSTABLE")
                    } catch (InterruptedException e) {
                        throw e
                    } catch (Exception e) {
                        if (pipeline.X86_TEST_CHOICE == pipeline.STAGE_CHOICE_IGNORE) {
                            pipeline.catchError(
                                buildResult: 'SUCCESS',
                                stageResult: 'FAILURE') {
                                pipeline.error "x86_64 test failed but ignored due to Jenkins configuration"
                            }
                        } else {
                            pipeline.catchError(
                                buildResult: 'FAILURE',
                                stageResult: 'FAILURE') {
                                pipeline.error "x86_64 single-GPU test failed"
                            }
                            singleGpuTestFailed = true
                        }
                    }
                }
                pipeline.mrResults.uploadArchCoverage("x86_64", pipeline, testFilter)

                def requireMultiGpuTesting = pipeline.currentBuild.description?.contains("Require x86_64 Multi-GPU Testing") ?: false
                pipeline.echo "requireMultiGpuTesting: ${requireMultiGpuTesting}"
                if (!requireMultiGpuTesting) {
                    if (singleGpuTestFailed) {
                        pipeline.error "x86_64 single-GPU test failed"
                    }
                    return
                }

                if (singleGpuTestFailed) {
                    if (pipeline.env.JOB_NAME ==~ /.*PostMerge.*/) {
                        pipeline.echo "In the official post-merge pipeline, x86_64 single-GPU test failed, whereas multi-GPU test is still kept running."
                    } else {
                        pipeline.stage("[Test-x86_64-Multi-GPU] Blocked") {
                            pipeline.error "This pipeline requires running multi-GPU test, but x86_64 single-GPU test has failed."
                        }
                        return
                    }
                }

                // Single-GPU was infra-incomplete (UNSTABLE): its coverage is a
                // prerequisite for multi-GPU. In pre-merge, explicitly block multi-GPU
                // rather than mark it skipped: it is required but cannot run until the
                // single-GPU sub-job is re-run. Post-merge keeps running multi-GPU for
                // maximum signal, mirroring the single-GPU-failed policy.
                if (singleGpuInfraIncomplete) {
                    if (pipeline.env.JOB_NAME ==~ /.*PostMerge.*/) {
                        pipeline.echo "In the official post-merge pipeline, x86_64 single-GPU test was infra-incomplete (UNSTABLE); multi-GPU test is still kept running."
                    } else {
                        pipeline.stage("[Test-x86_64-Multi-GPU] Blocked") {
                            pipeline.catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                                pipeline.error "This pipeline requires running x86_64 multi-GPU test, but x86_64 single-GPU test is infra-incomplete (UNSTABLE)."
                            }
                        }
                        return
                    }
                }

                // Label gate: check before entering the Remote Run stage so a
                // missing/unauthorized label shows as "Blocked" (not a Remote Run
                // failure) and does not trigger fail-fast.
                def x86LabelBlock = pipeline.mrChanges.requireMultiGpuApprovalLabel(pipeline, globalVars, "x86_64")
                if (x86LabelBlock) {
                    pipeline.stage("[Test-x86_64-Multi-GPU] Blocked") {
                        pipeline.catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                            pipeline.error x86LabelBlock
                        }
                    }
                    return
                }

                testStageName = "[Test-x86_64-Multi-GPU] Remote Run"
                pipeline.stage(testStageName) {
                    if (pipeline.X86_TEST_CHOICE == pipeline.STAGE_CHOICE_SKIP) {
                        pipeline.echo "x86_64 test job is skipped due to Jenkins configuration"
                        return
                    }
                    try {
                        def testFilterJson = pipeline.writeJSON returnText: true, json: testFilter
                        def additionalParameters = [
                            'testFilter': testFilterJson,
                            'dockerImage': globalVars["LLM_DOCKER_IMAGE"],
                            'wheelDockerImagePy310': globalVars["LLM_ROCKYLINUX8_PY310_DOCKER_IMAGE"],
                            'wheelDockerImagePy312': globalVars["LLM_ROCKYLINUX8_PY312_DOCKER_IMAGE"],
                        ]

                        pipeline.mrRelease.launchJob(pipeline, "L0_Test-x86_64-Multi-GPU", false, enableFailFast, globalVars, additionalParameters)

                    } catch (InterruptedException e) {
                        throw e
                    } catch (Exception e) {
                        if (pipeline.X86_TEST_CHOICE == pipeline.STAGE_CHOICE_IGNORE) {
                            pipeline.catchError(
                                buildResult: 'SUCCESS',
                                stageResult: 'FAILURE') {
                                pipeline.error "x86_64 test failed but ignored due to Jenkins configuration"
                            }
                        } else {
                            throw e
                        }
                    }
                }
            }
        },
        "SBSA-Linux": {
            pipeline.script {
                if (testFilter[(pipeline.ONLY_ONE_GROUP_CHANGED)] == "Docs") {
                    pipeline.echo "SBSA build job is skipped due to Jenkins configuration or conditional pipeline run"
                    return
                }
                // CBTS deliberately does NOT short-circuit the SBSA Build —
                // see x86 track above for the rationale.

                def testStageName = "[Build-SBSA] Remote Run"
                def buildInfraIncomplete = false
                pipeline.stage(testStageName) {
                    def additionalParameters = [
                        "dockerImage": globalVars["LLM_SBSA_DOCKER_IMAGE"],
                        // Off unless resolveBoltConsume() allowed it. Post-merge is
                        // allowed only together with boltPublishVariant below, which
                        // keeps canonical -- the BOLT-Profile-Gen input -- un-BOLTed.
                        'boltConsume': globalVars[pipeline.BOLT_CONSUME_BUILD],
                        'boltPublishVariant': globalVars[pipeline.BOLT_PUBLISH_VARIANT],
                    ]
                    // launchJob returns UNSTABLE (without throwing) when the build
                    // sub-job was infra-incomplete: only infra aborts, no genuine
                    // build failure (see runBranchesWithInfraDefer in Build.groovy).
                    def buildStatus = pipeline.mrRelease.launchJob(pipeline, "/LLM/helpers/Build-SBSA", reuseBuild, enableFailFast, globalVars, additionalParameters)
                    buildInfraIncomplete = (buildStatus == "UNSTABLE")
                }

                if (pipeline.GEN_POST_MERGE_BUILDS_ONLY) {
                    pipeline.echo "Skipping SBSA tests (GenPostMergeBuilds mode: builds only)"
                    return
                }

                if (testFilter[(pipeline.TEST_STAGE_LIST)]?.contains("NGC-Container-Scaning")) {
                    pipeline.echo "Skipping SBSA tests (PLC container scanning)"
                    return
                }
                if (testFilter[pipeline.INFRA_DRY_RUN]) {
                    def imageParameters = [
                        "dockerImage": globalVars["LLM_SBSA_DOCKER_IMAGE"],
                        'wheelDockerImage': globalVars["LLM_SBSA_WHEEL_DOCKER_IMAGE"],
                    ]
                    pipeline.mrRelease.launchInfraDryRunTestJob(pipeline, "SBSA", testFilter, globalVars, imageParameters)
                    return
                }

                // BOLT profile generation (producer): refreshes the branch-keyed
                // `latest` bundle the BOLT consumers pull, on this pipeline's
                // cadence. Post-merge only -- it profiles the SBSA build just
                // built under three multi-hour GPU workloads, so it must never
                // land on a pre-merge /bot run. Best-effort: the producer depends
                // on external SLURM health and bundles are drift-tolerant, so a
                // failure just leaves `latest` where it was; it must not fail
                // post-merge nor block the tests behind it.
                if (pipeline.env.JOB_NAME ==~ /.*PostMerge.*/) {
                    pipeline.stage("[BOLT-Profile-Gen] Remote Run") {
                        pipeline.catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            def additionalParameters = [
                                "dockerImage": globalVars["LLM_SBSA_DOCKER_IMAGE"],
                                'targetArch': "aarch64-linux-gnu",
                                'branch': globalVars[pipeline.BUILD_BRANCH],
                                'promote': "true",
                                // Exactly ONE writer publishes a BOLTed build per
                                // commit, and this launch site is where that choice
                                // is made. The SBSA build stage is that writer (see
                                // BOLT_PUBLISH_VARIANT): it publishes
                                // bolted-<tarName> and leaves canonical un-BOLTed on
                                // purpose, canonical being this job's own input and
                                // what every consumer not yet migrated still
                                // fetches. BoltProfileGen's canonical repush is the
                                // other writer and would overwrite exactly that.
                                // Already its default, but sent explicitly: the
                                // default lives in a job config editable outside
                                // this repo, and a flip there would silently put
                                // both writers on the same object.
                                'boltPublishCanonical': "false",
                            ]
                            pipeline.mrRelease.launchJob(pipeline, "/LLM/helpers/BoltProfileGen", false, false, globalVars, additionalParameters)
                        }
                    }
                }

                // Build was infra-incomplete (UNSTABLE): the wheel/tar this arch's
                // test sub-jobs would consume was never produced, so launching them
                // can only fail on a missing artifact. Skip them WITHOUT throwing --
                // throwing is exactly what trips the arch-level failFast and cancels
                // the healthy sibling architecture and its consumers. Keep the build
                // UNSTABLE (already set by launchJob); do NOT escalate to FAILURE.
                // Unlike the single-GPU-infra-incomplete policy below, post-merge
                // skips too: with no artifact there is no signal to be had.
                if (buildInfraIncomplete) {
                    pipeline.stage("[Test-SBSA] Blocked - build infra-incomplete") {
                        pipeline.echo "SBSA build was infra-incomplete (UNSTABLE); skipping SBSA test sub-jobs (no artifact to test). Build stays UNSTABLE."
                    }
                    return
                }

                testStageName = "[Test-SBSA-Single-GPU] Remote Run"
                def singleGpuTestFailed = false
                def singleGpuInfraIncomplete = false
                pipeline.stage(testStageName) {
                    if (pipeline.SBSA_TEST_CHOICE == pipeline.STAGE_CHOICE_SKIP) {
                        pipeline.echo "SBSA test job is skipped due to Jenkins configuration"
                        return
                    }
                    try {
                        String testFilterJson = pipeline.writeJSON returnText: true, json: testFilter
                        def additionalParameters = [
                            'testFilter': testFilterJson,
                            "dockerImage": globalVars["LLM_SBSA_DOCKER_IMAGE"],
                            'wheelDockerImage': globalVars["LLM_SBSA_WHEEL_DOCKER_IMAGE"],
                        ]

                        // launchJob returns UNSTABLE (without throwing) when the single-GPU
                        // sub-job was infra-incomplete: only infra aborts, no real failure.
                        def singleGpuStatus = pipeline.mrRelease.launchJob(pipeline, "L0_Test-SBSA-Single-GPU", false, enableFailFast, globalVars, additionalParameters)
                        singleGpuInfraIncomplete = (singleGpuStatus == "UNSTABLE")
                    } catch (InterruptedException e) {
                        throw e
                    } catch (Exception e) {
                        if (pipeline.SBSA_TEST_CHOICE == pipeline.STAGE_CHOICE_IGNORE) {
                            pipeline.catchError(
                                buildResult: 'SUCCESS',
                                stageResult: 'FAILURE') {
                                pipeline.error "SBSA test failed but ignored due to Jenkins configuration"
                            }
                        } else {
                            pipeline.catchError(
                                buildResult: 'FAILURE',
                                stageResult: 'FAILURE') {
                                pipeline.error "SBSA single-GPU test failed"
                            }
                            singleGpuTestFailed = true
                        }
                    }
                }

                pipeline.mrResults.uploadArchCoverage("SBSA", pipeline, testFilter)

                def requireMultiGpuTesting = pipeline.currentBuild.description?.contains("Require SBSA Multi-GPU Testing") ?: false
                pipeline.echo "requireMultiGpuTesting: ${requireMultiGpuTesting}"
                if (!requireMultiGpuTesting) {
                    if (singleGpuTestFailed) {
                        pipeline.error "SBSA single-GPU test failed"
                    }
                    return
                }

                if (singleGpuTestFailed) {
                    if (pipeline.env.JOB_NAME ==~ /.*PostMerge.*/) {
                        pipeline.echo "In the official post-merge pipeline, SBSA single-GPU test failed, whereas multi-GPU test is still kept running."
                    } else {
                        pipeline.stage("[Test-SBSA-Multi-GPU] Blocked") {
                            pipeline.error "This pipeline requires running SBSA multi-GPU test, but SBSA single-GPU test has failed."
                        }
                        return
                    }
                }

                // Single-GPU was infra-incomplete (UNSTABLE): its coverage is a
                // prerequisite for multi-GPU. In pre-merge, explicitly block multi-GPU
                // rather than mark it skipped: it is required but cannot run until the
                // single-GPU sub-job is re-run. Post-merge keeps running multi-GPU for
                // maximum signal, mirroring the single-GPU-failed policy.
                if (singleGpuInfraIncomplete) {
                    if (pipeline.env.JOB_NAME ==~ /.*PostMerge.*/) {
                        pipeline.echo "In the official post-merge pipeline, SBSA single-GPU test was infra-incomplete (UNSTABLE); multi-GPU test is still kept running."
                    } else {
                        pipeline.stage("[Test-SBSA-Multi-GPU] Blocked") {
                            pipeline.catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                                pipeline.error "This pipeline requires running SBSA multi-GPU test, but SBSA single-GPU test is infra-incomplete (UNSTABLE)."
                            }
                        }
                        return
                    }
                }

                def sbsaLabelBlock = pipeline.mrChanges.requireMultiGpuApprovalLabel(pipeline, globalVars, "SBSA")
                if (sbsaLabelBlock) {
                    pipeline.stage("[Test-SBSA-Multi-GPU] Blocked") {
                        pipeline.catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                            pipeline.error sbsaLabelBlock
                        }
                    }
                    return
                }

                testStageName = "[Test-SBSA-Multi-GPU] Remote Run"
                pipeline.stage(testStageName) {
                    if (pipeline.SBSA_TEST_CHOICE == pipeline.STAGE_CHOICE_SKIP) {
                        pipeline.echo "SBSA test job is skipped due to Jenkins configuration"
                        return
                    }
                    try {
                        def testFilterJson = pipeline.writeJSON returnText: true, json: testFilter
                        def additionalParameters = [
                            'testFilter': testFilterJson,
                            "dockerImage": globalVars["LLM_SBSA_DOCKER_IMAGE"],
                            'wheelDockerImage': globalVars["LLM_SBSA_WHEEL_DOCKER_IMAGE"],
                        ]

                        pipeline.mrRelease.launchJob(pipeline, "L0_Test-SBSA-Multi-GPU", false, enableFailFast, globalVars, additionalParameters)

                    } catch (InterruptedException e) {
                        throw e
                    } catch (Exception e) {
                        if (pipeline.SBSA_TEST_CHOICE == pipeline.STAGE_CHOICE_IGNORE) {
                            pipeline.catchError(
                                buildResult: 'SUCCESS',
                                stageResult: 'FAILURE') {
                                pipeline.error "SBSA test failed but ignored due to Jenkins configuration"
                            }
                        } else {
                            throw e
                        }
                    }
                }
            }
        },
    ]
    def dockerBuildJob = [
        "Build-Docker-Images": {
            pipeline.script {
                def imageBuildStatus
                def testStageName = "[Build-Docker-Images] Remote Run"
                pipeline.stage(testStageName) {
                    try {
                        def branch = globalVars[pipeline.BUILD_BRANCH]

                        // Force the image tag suffix to be this L0_MergeRequest BUILD_NUMBER
                        // instead of the BuildDockerImages helper job's own counter.
                        def shortCommit = pipeline.env.gitlabCommit ? pipeline.env.gitlabCommit.substring(0, 7) : "undefined"
                        def branchTag = branch.replaceAll('/', '_')
                        def defaultTag = "${shortCommit}-${branchTag}-${pipeline.env.BUILD_NUMBER}"

                        def additionalParameters = [
                            'branch': branch,
                            'action': "push",
                            'triggerType': pipeline.runMode == "nightly_release" ?
                                "nightly-release" :
                                (pipeline.env.JOB_NAME ==~ /.*PostMerge.*/ ? "post-merge" : "pre-merge"),
                            'register_images': pipeline.runMode == "nightly_release" || (pipeline.env.JOB_NAME ==~ /.*PostMerge.*/),
                            'useWheelFromBuildStage': (pipeline.env.JOB_NAME ==~ /.*PostMerge.*/),
                            'defaultTag': defaultTag,
                            // Canonical images carry BOLT profiles: the raw build is
                            // published as <tag>-noprofiles and <tag> is produced by the
                            // overlay. Both unconditional -- the contract is a property of
                            // the image, not of what triggered the build, so a bundle that
                            // cannot be pulled is an error rather than a silent plain
                            // retag. Left unset, boltProfileBranch resolves LLM_BRANCH ->
                            // main, so a ref with no promoted bundle still gets profiles.
                            'boltOverlayEnabled': true,
                            'boltProfilesRequired': true,
                            // The overlay above only bakes in the profile bundle; it
                            // leaves the installed wheel unoptimized. This BOLTs the
                            // wheel the SBSA release image installs, applying the
                            // branch's last promoted bundle during the image build.
                            // Deliberately not this run's profiles: those are not
                            // published until BoltProfileGen finishes, hours after
                            // this build starts, so depending on them would serialize
                            // every release behind a multi-hour GPU job. Inert on
                            // x86_64 (no promoted bundle) and whenever the wheel is
                            // built from source rather than downloaded.
                            'boltOptimizeWheel': true,
                        ]
                        if (pipeline.runMode == "nightly_release") {
                            additionalParameters += [
                                'runSanityCheck': true,
                                'buildInternalRelease': false,
                                'buildCiImage': false,
                                'buildNgcRelease': true,
                                'artifactPath': pipeline.ARTIFACT_PATH,
                                'useWheelFromBuildStage': wheelSelected,
                                'wait_success_seconds': "",
                                'program_version_name': pipeline.env.NSPECT_RELEASE_VERSION,
                            ]
                        } else if (buildAllDockerImages) {
                            additionalParameters += [
                                'runSanityCheck': pipeline.env.JOB_NAME ==~ /.*PostMerge.*/,
                                'program_version_name': pipeline.env.NSPECT_RELEASE_VERSION,
                            ]
                        } else {
                            additionalParameters += [
                                'runSanityCheck': false,
                                'buildInternalRelease': false,
                                'buildCiImage': false,
                                'artifactPath': pipeline.ARTIFACT_PATH,
                                'nspect_id': "",
                            ]
                        }
                        if (pipeline.params.release_type_id) {
                            additionalParameters['release_type_id'] = pipeline.params.release_type_id
                        }
                        imageBuildStatus = pipeline.mrRelease.launchJob(pipeline, "/LLM/helpers/BuildDockerImages", false, enableFailFast, globalVars, additionalParameters)
                    } catch (InterruptedException e) {
                        throw e
                    } catch (Exception e) {
                        if (pipeline.BUILD_CHECK_CHOICE == pipeline.STAGE_CHOICE_IGNORE) {
                            pipeline.catchError(
                                buildResult: 'SUCCESS',
                                stageResult: 'FAILURE') {
                                pipeline.error "Build-Docker-Images job failed but ignored due to Jenkins configuration"
                            }
                        } else {
                            throw e
                        }
                    }
                }
                if (!runContainerScanning || imageBuildStatus != "SUCCESS") {
                    return
                }
                pipeline.stage("[NGC-Container-Compliance-Check] Run") {
                    def repoUrlKey = pipeline.mrRelease.getPLCRepoUrlKey()
                    pipeline.echo "Triggering OSS Compliance (PLC) container scan for ref: ${pipeline.env.gitlabCommit}"
                    try {
                        def params = [
                            pipeline.string(name: 'ref', value: pipeline.env.gitlabCommit),
                            pipeline.string(name: 'repoUrlKey', value: repoUrlKey),
                            pipeline.string(name: 'postMergePipelineName', value: pipeline.env.JOB_NAME),
                            pipeline.string(name: 'postMergeBuildNumber', value: pipeline.env.BUILD_NUMBER),
                            pipeline.string(
                                name: 'scanMode',
                                value: pipeline.runMode == "nightly_release" ? 'release' : 'pre_merge'),
                            pipeline.string(name: 'runSourceCodeScanning', value: 'false'),
                            pipeline.string(name: 'runContainerOssScanning', value: 'true'),
                            pipeline.string(name: 'runContainerMalwareScanning', value: 'false'),
                            pipeline.string(name: 'runSonarQube', value: 'false'),
                        ]
                        def logger = new Logger(pipeline)
                        def handle = pipeline.build(
                            job: "/LLM/helpers/PLCScanningSetup",
                            parameters: params,
                            propagate: false
                        )
                        if (handle.result != "SUCCESS") {
                            if (pipeline.runMode == "nightly_release") {
                                pipeline.error "Risks detected on nightly NGC Containers"
                            } else {
                                pipeline.catchError(buildResult: pipeline.currentBuild.result ?: 'SUCCESS', stageResult: 'UNSTABLE') {
                                    pipeline.error "Risks detected on NGC Containers"
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        throw e
                    } catch (Exception e) {
                        if (pipeline.runMode == "nightly_release") {
                            throw e
                        } else {
                            pipeline.catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
                                pipeline.error "OSS Compliance Check failed: ${e.getMessage()}"
                            }
                        }
                    }
                }
            }
        }
    ]
    if (pipeline.runMode == "nightly_release") {
        testFilter[pipeline.TEST_STAGE_LIST] = null
        testFilter[pipeline.EXTRA_STAGE_LIST] = null
        if (!wheelSelected) {
            pipeline.stages.remove("x86_64-Linux")
            pipeline.stages.remove("SBSA-Linux")
        }
        if (containerSelected) {
            runContainerScanning = true
        }
    } else {
        if ((pipeline.env.JOB_NAME ==~ /.*PostMerge.*/) &&
            !pipeline.GEN_POST_MERGE_BUILDS_ONLY) {
            buildAllDockerImages = true
        }
        if (!pipeline.GEN_POST_MERGE_BUILDS_ONLY &&
            (testFilter[(pipeline.TEST_STAGE_LIST)]?.contains("Build-Docker-Images") ||
             testFilter[(pipeline.EXTRA_STAGE_LIST)]?.contains("Build-Docker-Images"))) {
            buildAllDockerImages = true
            testFilter[(pipeline.TEST_STAGE_LIST)]?.remove("Build-Docker-Images")
            testFilter[(pipeline.EXTRA_STAGE_LIST)]?.remove("Build-Docker-Images")
            pipeline.echo "Will run Build-Docker-Images job"
            pipeline.stages.remove("x86_64-Linux")
            pipeline.stages.remove("SBSA-Linux")
            pipeline.echo "Build-Docker-Images job is set explicitly. Both x86_64-Linux and SBSA-Linux sub-pipelines will be disabled."
        }
        if (testFilter[(pipeline.TEST_STAGE_LIST)]?.contains("NGC-Container-Scaning")) {
            runContainerScanning = true
            testFilter[(pipeline.TEST_STAGE_LIST)]?.remove("NGC-Container-Scanning")
            pipeline.echo "Will run job to build ngc containers and running in-pipeline scanning for them"
        }
    }

    if (buildAllDockerImages || runContainerScanning) {
        pipeline.stages += dockerBuildJob
    }

    def alwaysFailFastStages = ["Release-Check", "Check-Test-List"] as Set
    pipeline.parallelJobs = pipeline.stages.collectEntries{key, value -> [key, {
        pipeline.script {
            pipeline.stage(key) {
                if (enableFailFast || key in alwaysFailFastStages) {
                    value()
                } else {
                    // Avoid interrupting other stages on failure.
                    pipeline.catchError(catchInterruptions: false) {
                        value()
                    }
                }
            }
        }
    }]}

    // Preserve both architecture tracks during dry acceptance.
    def effectiveFailFast = testFilter[pipeline.INFRA_DRY_RUN] ? false : enableFailFast
    pipeline.parallelJobs.failFast = effectiveFailFast
    pipeline.parallel pipeline.parallelJobs
}

return this
