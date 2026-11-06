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
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

@Field def pipeline

def initialize(pipeline)
{
    this.@pipeline = pipeline
    return this
}

// ============================================================================
// CBTS (Change-Based Testing Selection)
//
// Calls `cbts.command main` (jenkins/scripts/cbts/cbts/command/main.py) with PR
// changed_files + diffs and returns a result map (or null = defer to existing
// filter chain). Result keys: scope, affected_stages, reasons,
// test_db_dir_override, affected_stage_test_counts, affected_stage_split_counts,
// coverage_residual_files, coverage_decline_reason.
// CBTS narrows test cases only — Build always runs. See cbts/README.md.
//
// Every `python3 -m cbts.command ...` call below is prefixed with
// `PYTHONPATH=${LLM_ROOT}/jenkins/scripts/cbts` — the outer vendor dir, so
// `import cbts.*` resolves without touching the rest of jenkins/scripts/.
// ============================================================================

def _cbtsEnsureCliDependencies(pipeline)
{
    try {
        // The preparation pod is Debian; requirements.txt includes Click and PyYAML.
        pipeline.trtllm_utils.llmExecStepWithRetry(pipeline,
            script: "apt-get update -qq && apt-get install -y -qq python3-pip")
        pipeline.trtllm_utils.llmExecStepWithRetry(pipeline,
            script: "python3 -m pip install --break-system-packages --disable-pip-version-check " +
                    "-r ${pipeline.LLM_ROOT}/jenkins/scripts/cbts/requirements.txt")
        return true
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        pipeline.echo("CBTS CLI dependencies unavailable (non-fatal): ${e.message}")
        return false
    }
}

def getCbtsResult(pipeline, testFilter, globalVars)
{
    // Explicit kill switch: `/bot run --disable-cbts` forces a full run.
    if (testFilter[(pipeline.runParameters.DISABLE_CBTS)]) {
        pipeline.echo("CBTS: disabled — user-specified /bot run --disable-cbts")
        if (_cbtsEnsureCliDependencies(pipeline)) {
            _cbtsReportDecision(pipeline, globalVars, "disabled", "user flag: disable_cbts", null)
        }
        return null
    }

    def isOfficialPostMergeJob = (pipeline.env.JOB_NAME ==~ /.*PostMerge.*/)
    if (isOfficialPostMergeJob) {
        pipeline.echo("CBTS: deferring — post-merge job")
        return null
    }

    // CBTS only activates on bare `/bot run`. If the user specified any
    // stage-selection flag, defer entirely to their explicit choice.
    def triggeredFlags = _cbtsTriggeredUserFlags(testFilter)
    if (!triggeredFlags.isEmpty()) {
        pipeline.echo("CBTS: deferring — user-specified /bot run flag(s): ${triggeredFlags.join(', ')}")
        if (_cbtsEnsureCliDependencies(pipeline)) {
            _cbtsReportDecision(pipeline, globalVars, "deferred",
                                "user flags: ${triggeredFlags.join(', ')}", null)
        }
        return null
    }

    def changedFiles = pipeline.mrChanges.getMergeRequestChangedFileList(pipeline, globalVars).unique()
    if (!changedFiles) {
        pipeline.echo("CBTS: deferring — no changed files detected")
        return null
    }

    try {
        if (!_cbtsEnsureCliDependencies(pipeline)) {
            pipeline.echo("CBTS: falling back to full run because CLI dependencies are unavailable")
            return null
        }
        def coveragePilotEligible = false
        // Ask Python which file patterns need diffs, fetch them.
        def patternsOut = pipeline.sh(
            script: "cd ${pipeline.LLM_ROOT} && PYTHONPATH=jenkins/scripts/cbts " +
                    "python3 -m cbts.command main --list-needed-diffs",
            returnStdout: true,
        ).trim()
        def needsDiffFor = patternsOut ? patternsOut.readLines().collect { it.trim() }.findAll { it } : []
        def filesNeedingDiff = changedFiles.findAll { filePath ->
            _cbtsMatchesAnyPattern(filePath, needsDiffFor)
        }
        def diffs = [:]
        if (filesNeedingDiff) {
            def fileChanges = pipeline.mrChanges.getMRChangedFile(pipeline, globalVars, "getFileChanges", "", filesNeedingDiff)
            diffs = filesNeedingDiff.collectEntries { filePath ->
                // Null (patch omitted for binary / rename / too-large diffs) coerces to empty.
                [(filePath): fileChanges[filePath] ?: ""]
            }
        }

        // Write INPUT_JSON; Python reads stages/yaml itself.
        def inputJson = groovy.json.JsonOutput.toJson([
            changed_files: changedFiles,
            diffs: diffs,
            post_merge: testFilter[(pipeline.runParameters.IS_POST_MERGE)] ?: false,
        ])
        def inputPath = "${pipeline.LLM_ROOT}/cbts_input.json"
        pipeline.writeFile file: inputPath, text: inputJson

        def mainCmd = "cd ${pipeline.LLM_ROOT} && PYTHONPATH=jenkins/scripts/cbts " +
                      "python3 -m cbts.command main cbts_input.json"
        def output = pipeline.sh(script: mainCmd, returnStdout: true)
        def result = _cbtsParseSelectionResult(output)

        // Tier 1 owns the definition of handled files. Only prepare a coverage DB when
        // its residual is eligible for Tier 2, and scope the compatibility check to it.
        if (result.scope == null && result.coverage_residual_files &&
                !result.coverage_decline_reason) {
            def residualPath = "${pipeline.LLM_ROOT}/cbts_coverage_residual.json"
            pipeline.writeFile file: residualPath,
                      text: groovy.json.JsonOutput.toJson(result.coverage_residual_files)
            def coverageContext = _cbtsCoverageAudit(pipeline, globalVars, residualPath)
            def coverageDb = coverageContext?.db
            coveragePilotEligible = coverageContext?.pilotEligible ?: false
            if (coverageDb?.meta) {
                def coverageCmd = mainCmd + " --coverage-db-meta ${coverageDb.meta}"
                if (coverageDb.path) {
                    coverageCmd += " --coverage-db ${coverageDb.path}"
                }
                output = pipeline.sh(script: coverageCmd, returnStdout: true)
                result = _cbtsParseSelectionResult(output)
            } else if (coverageDb?.compatibility) {
                result.coverage_compatibility = coverageDb.compatibility
                result.coverage_decline_reason = coverageDb.decline_reason ?: ""
                result.coverage_decline_category = "coverage_unavailable"
                output = groovy.json.JsonOutput.toJson(result)
            }
        }

        if (result.scope == null) {
            pipeline.echo("CBTS: deferring — Python returned scope=null. " +
                          "Reasons: ${result.reasons.join('; ')}")
            _cbtsReportDecision(pipeline, globalVars, "fallback", "", output,
                                false, false, false, coveragePilotEligible)
            return null
        }
        def runStatus = (testFilter[(pipeline.runParameters.IS_POST_MERGE)] ?: false) ? "post_merge" : "pre_merge"
        def multiGpuRequired = (testFilter[(pipeline.runParameters.MULTI_GPU_FILE_CHANGED)] ?: false) as boolean
        def multiGpuLabelGateOpen = multiGpuRequired &&
            _cbtsMultiGpuLabelGateOpen(pipeline, globalVars)
        def coverageShadow = result.scope == "coverage" && !coveragePilotEligible
        // Applied decisions pass the uploaded test DB to L0_Test. Shadow
        // decisions save the same artifact but return null below, so L0_Test
        // keeps using the baseline test DB.
        if (result.test_db_dir_override || coverageShadow) {
            try {
                def archiveFiles = []
                if (result.test_db_dir_override) {
                    archiveFiles.add(result.test_db_dir_override)
                }
                if (coverageShadow) {
                    pipeline.writeFile file: "${pipeline.LLM_ROOT}/cbts_shadow_decision.json", text: output
                    archiveFiles.add("cbts_shadow_decision.json")
                }
                // Tar/upload from the workspace: rtUpload patterns are workspace-relative; /tmp uploads 0 files.
                pipeline.sh "tar czf ${pipeline.LLM_ROOT}/cbts_test_db.tar.gz -C ${pipeline.LLM_ROOT} ${archiveFiles.join(' ')}"
                pipeline.trtllm_utils.uploadArtifacts("${pipeline.LLM_ROOT}/cbts_test_db.tar.gz", "${pipeline.UPLOAD_PATH}/cbts/")
                result.cbts_test_db_artifact_path = "${pipeline.UPLOAD_PATH}/cbts/cbts_test_db.tar.gz"
                pipeline.echo("CBTS Layer 3: uploaded cbts_test_db to ${result.cbts_test_db_artifact_path}")
            } catch (InterruptedException e) {
                throw e
            } catch (Exception e) {
                pipeline.echo("CBTS Layer 3: artifact upload failed (non-fatal): ${e.message}")
            }
        }
        if (coverageShadow) {
            if (result.cbts_test_db_artifact_path) {
                try {
                    def reportedDecision = new groovy.json.JsonSlurper().parseText(output)
                    reportedDecision.shadow_selection_artifact_path = result.cbts_test_db_artifact_path
                    output = groovy.json.JsonOutput.toJson(reportedDecision)
                } catch (InterruptedException e) {
                    throw e
                } catch (Exception e) {
                    pipeline.echo("CBTS: shadow artifact path annotation failed (non-fatal): ${e.message}")
                }
            }
            pipeline.echo("CBTS: shadow coverage decision — scope=${result.scope}, " +
                          "stages=${result.affected_stages.size()}; baseline remains active")
            _cbtsReportDecision(pipeline, globalVars, runStatus, "", output,
                                multiGpuRequired, multiGpuLabelGateOpen, false,
                                coveragePilotEligible)
            return null
        }
        pipeline.echo("CBTS: scope=${result.scope}, " +
                      "stages=${result.affected_stages.size()}")
        _cbtsReportDecision(pipeline, globalVars, runStatus, "", output,
                            multiGpuRequired, multiGpuLabelGateOpen, true,
                            coveragePilotEligible)
        return result
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        pipeline.echo("CBTS failed, falling back to full run: ${e}")
        return null
    }
}

// Observe the multi-GPU approval gate at CBTS decision time. This is telemetry
// only: a later dispatch re-checks the label so this snapshot cannot change CI
// behavior. Match the dispatch policy and fail open on non-interruption errors.
def _cbtsMultiGpuLabelGateOpen(pipeline, globalVars)
{
    try {
        def blockReason = pipeline.mrChanges.requireMultiGpuApprovalLabel(
            pipeline, globalVars, "CBTS telemetry")
        return !blockReason
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        pipeline.echo("CBTS: multi-GPU label telemetry check failed (${e.message}); failing open")
        return true
    }
}

def _cbtsCoverageDecline(boolean pilotEligible, String reason)
{
    return [
        db: [
            compatibility: "unknown",
            decline_reason: "coverage tier declined: ${reason}",
        ],
        pilotEligible: pilotEligible,
    ]
}

// Resolve pilot application eligibility, then fetch and audit the touch DB for
// every Tier-2 residual. Returns {db, pilotEligible}; a compatibility decline
// remains in db without a path so it is observable in shadow and pilot modes.
def _cbtsCoverageAudit(pipeline, globalVars, String residualPath)
{
    def pilotEligible = false
    def artifactoryNetrc = ""
    try {
        // artifact.py resolves, downloads and merges the x86/SBSA DBs; paths come back
        // ${LLM_ROOT}-relative, matching the main.py caller's `cd ${LLM_ROOT}`.
        // The checked-out revision is the PR head; its merge base is what drift is measured against.
        def prHead = pipeline.env.gitlabMergeRequestLastCommit ?: ""
        def prNumber = _cbtsPrNumber(globalVars)
        if (!(prHead ==~ /[0-9a-fA-F]{40}/) || !(prNumber ==~ /[1-9]\d*/)) {
            pipeline.echo("CBTS audit: PR number/head unavailable; cannot make coverage DB selection repeatable")
            return _cbtsCoverageDecline(
                pilotEligible, "PR identity unavailable for coverage DB pin")
        }

        // artifact.py reads build_info.txt with jenkins/scripts/fetch_build_artifact.sh, which
        // looks in the primary location first and authenticates to aws with this netrc. It holds
        // the publishing credential, the one the pipelines publish build artifacts with.
        // When artifactory.nvidia.com is the primary location and rejects the credential, the
        // helper prints a message naming it, artifact.py exits 1, and the coverage tier declines
        // like after any other failure; a rejection while it is the secondary counts as a failed
        // read, and artifact.py skips that build.
        // While artifactory.nvidia.com is the primary location (the default) and is down or
        // unreachable, every build_info.txt read fails after the helper's retries. artifact.py
        // skips each build whose read fails, so the coverage tier declines only after
        // --resolve-pin has tried every recent build that has both coverage archives, or after
        // --prepare has tried the pinned one. Setup Environment, which runs this before any build
        // starts, waits for those reads; build_commit in artifact.py says how long each takes and
        // what changes after a rollback to urm.
        def artifactoryCredentialId = 'aws-artifactory-credentials'
        def artifactsPrimary = pipeline.artifactPaths.llmArtifactsPrimary()
        artifactoryNetrc = "${pipeline.pwd(tmp: true)}/artifactory-netrc-${UUID.randomUUID().toString()}"
        pipeline.withCredentials([pipeline.usernamePassword(credentialsId: artifactoryCredentialId,
                usernameVariable: 'ARTIFACTORY_USER', passwordVariable: 'ARTIFACTORY_PASSWORD')]) {
            pipeline.withEnv(["ARTIFACTORY_NETRC=${artifactoryNetrc}"]) {
                pipeline.sh(label: "Write Artifactory netrc", script: '''
                    set +x
                    umask 077
                    printf 'machine artifactory.nvidia.com login %s password %s\\n' "$ARTIFACTORY_USER" "$ARTIFACTORY_PASSWORD" > "$ARTIFACTORY_NETRC"
                ''')
            }
        }
        def artifactEnv = [
            "LLM_ARTIFACTS_PRIMARY=${artifactsPrimary}",
            "ARTIFACTORY_NETRC=${artifactoryNetrc}",
            "ARTIFACTORY_CREDENTIAL_ID=${artifactoryCredentialId}",
        ]

        def readyJson = ""
        def prAuthor = ""
        def pinPlanJson = ""
        pipeline.withCredentials([pipeline.usernamePassword(
            credentialsId: 'github-cred-trtllm-ci',
            usernameVariable: 'NOT_USED_YET',
            passwordVariable: 'GITHUB_API_TOKEN'),
        ]) {
            prAuthor = pipeline.sh(
                script: "cd ${pipeline.LLM_ROOT} && PYTHONPATH=jenkins/scripts/cbts " +
                        "python3 -m cbts.command coverage pilot",
                returnStdout: true,
            ).trim()
            pilotEligible = prAuthor && pipeline.CBTS_COVERAGE_PILOT_USERS.any { it.equalsIgnoreCase(prAuthor) }
            pipeline.echo("CBTS coverage pilot: pr_author=${prAuthor ?: 'unknown'}, eligible=${pilotEligible}")
            pipeline.withEnv(artifactEnv) {
                pinPlanJson = pipeline.sh(
                    script: "cd ${pipeline.LLM_ROOT} && PYTHONPATH=jenkins/scripts/cbts " +
                            "python3 -m cbts.command coverage selection artifact " +
                            "--resolve-pin cbts_db_pin.json --pr-number ${prNumber} " +
                            "--pr-head ${prHead} || true",
                    returnStdout: true,
                ).trim()
            }
        }
        if (!pinPlanJson) {
            pipeline.echo("CBTS audit: coverage DB pin could not be resolved")
            return _cbtsCoverageDecline(pilotEligible, "coverage DB pin could not be resolved")
        }
        def pinPlan = new groovy.json.JsonSlurper().parseText(pinPlanJson)
        if (pinPlan.status != "ready") {
            pipeline.echo("CBTS audit: ${pinPlan.decline_reason ?: 'coverage DB pin declined'}")
            return [db: pinPlan, pilotEligible: pilotEligible]
        }

        if (pinPlan.pin_upload_required) {
            try {
                pipeline.trtllm_utils.uploadArtifacts(
                    "${pipeline.LLM_ROOT}/${pinPlan.pin_path}", pinPlan.pin_target)
                pipeline.echo("CBTS audit: pinned coverage DB build ${pinPlan.build} " +
                              "for PR head ${prHead}")
            } catch (InterruptedException e) {
                throw e
            } catch (Exception e) {
                pipeline.echo("CBTS audit: coverage DB pin upload failed (${e.message})")
                return _cbtsCoverageDecline(pilotEligible, "coverage DB pin upload failed")
            }
        } else {
            pipeline.echo("CBTS audit: reusing pinned coverage DB build ${pinPlan.build}")
        }

        pipeline.withCredentials([
            pipeline.usernamePassword(
                credentialsId: 'github-cred-trtllm-ci',
                usernameVariable: 'NOT_USED_YET',
                passwordVariable: 'GITHUB_API_TOKEN'),
        ]) {
            pipeline.withEnv(artifactEnv) {
                readyJson = pipeline.sh(
                    script: "cd ${pipeline.LLM_ROOT} && PYTHONPATH=jenkins/scripts/cbts " +
                            "python3 -m cbts.command coverage selection artifact " +
                            "--prepare cbts_cov --paths-json cbts_coverage_residual.json" +
                            " --pr-head ${prHead}" +
                            " --build ${pinPlan.build}" +
                            " --expected-commit ${pinPlan.commit} || true",
                    returnStdout: true,
                ).trim()
            }
        }
        if (!readyJson) {
            pipeline.echo("CBTS audit: no coverage DB could be prepared — Tier 2 decision unavailable")
            return _cbtsCoverageDecline(pilotEligible, "coverage DB could not be prepared")
        }
        def ready = new groovy.json.JsonSlurper().parseText(readyJson)
        if (!ready.path) {
            pipeline.echo("CBTS audit: Tier-2 residual compatibility check did not pass")
            return [db: ready, pilotEligible: pilotEligible]
        }
        pipeline.echo("CBTS audit: Tier-2 residual applies cleanly to the selected coverage DB")
        pipeline.sh("cd ${pipeline.LLM_ROOT} && PYTHONPATH=jenkins/scripts/cbts " +
           "python3 -m cbts.command coverage selection audit --db ${ready.path}")
        return [db: ready, pilotEligible: pilotEligible]
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        pipeline.echo("CBTS audit: skipped (non-fatal): ${e.message}")
        return [db: null, pilotEligible: pilotEligible]
    } finally {
        if (artifactoryNetrc) {
            pipeline.sh(label: "Remove Artifactory netrc", script: "rm -f '${artifactoryNetrc}'")
        }
    }
}

def _cbtsPrNumber(globalVars)
{
    def prUrl = globalVars[this.@pipeline.runParameters.GITHUB_PR_API_URL]
    if (prUrl) {
        def match = (prUrl =~ /\/pulls?\/(\d+)/)
        if (match.find()) {
            return match.group(1)
        }
        return ""
    }
    return this.@pipeline.env.gitlabMergeRequestIid ?: ""
}

// Post one CBTS decision record to OpenSearch (best-effort; never blocks CI).
// decisionJson null for deferred; reason used only then. Context/creds via env.
// Multi-GPU enters the pre-merge denominator only when normal CI requires it
// and the approval-label gate is open at CBTS decision time.
def _cbtsReportDecision(pipeline, globalVars, String status, String reason, String decisionJson,
                        boolean multiGpuRequired = false, boolean multiGpuLabelGateOpen = false,
                        boolean cbtsApplied = false, boolean coveragePilotEligible = false)
{
    try {
        def args = "--status ${status}"
        if (multiGpuRequired) {
            args += " --multi-gpu-required"
        }
        if (multiGpuLabelGateOpen) {
            args += " --multi-gpu-label-gate-open"
        }
        if (cbtsApplied) {
            args += " --cbts-applied"
        }
        if (coveragePilotEligible) {
            args += " --coverage-pilot-eligible"
        }
        if (decisionJson != null) {
            pipeline.writeFile(file: "${pipeline.LLM_ROOT}/cbts_decision.json", text: decisionJson)
            args += " --decision cbts_decision.json"
        } else if (reason) {
            args += " --reason '${reason.replace("'", "")}'"
        }
        // PR number for s_pr_number, mirroring perf_regression_utils: GitHub PR
        // builds carry it in github_pr_api_url (.../pulls/<n>); GitLab MR builds
        // expose env.gitlabMergeRequestIid. Empty for post-merge/branch builds.
        def prNumber = _cbtsPrNumber(globalVars)
        if (prNumber) {
            args += " --pr-number ${prNumber}"
        }
        pipeline.sh("cd ${pipeline.LLM_ROOT} && PYTHONPATH=jenkins/scripts/cbts " +
           "python3 -m cbts.command report-decision ${args}")
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        pipeline.echo("CBTS: decision telemetry failed (non-fatal): ${e.message}")
    }
}

// Translate an Ant-style glob to a regex:
//   **/  zero or more path segments
//   **   any chars (including /)
//   *    any chars except /
//   ?    single char except /
def _cbtsGlobToRegex(String glob)
{
    def escaped = glob.collect { c ->
        (c == '*' || c == '?') ? c
            : ('.+()[]{}|^$\\'.contains(c) ? '\\' + c : c)
    }.join('')
    return '^' + escaped
        .replace('**/', '__CBTSDOUBLESLASH__')
        .replace('**',  '__CBTSDOUBLESTAR__')
        .replace('*',   '[^/]*')
        .replace('?',   '[^/]')
        .replace('__CBTSDOUBLESLASH__', '(?:.*/)?')
        .replace('__CBTSDOUBLESTAR__',  '.*') + '$'
}

def _cbtsMatchesAnyPattern(String filePath, List patterns)
{
    return patterns.any { filePath ==~ _cbtsGlobToRegex(it) }
}

// Returns user-set stage-selection flags that should force CBTS to defer.
// IS_POST_MERGE and orthogonal flags (REUSE_*, DEBUG_MODE, DETAILED_LOG, ...)
// are intentionally absent.
def _cbtsTriggeredUserFlags(testFilter)
{
    def deferFlags = [
        this.@pipeline.runParameters.ENABLE_SKIP_TEST, this.@pipeline.runParameters.TEST_STAGE_LIST, this.@pipeline.runParameters.EXTRA_STAGE_LIST, this.@pipeline.runParameters.GPU_TYPE_LIST,
        this.@pipeline.runParameters.TEST_BACKEND, this.@pipeline.runParameters.ADD_MULTI_GPU_TEST, this.@pipeline.runParameters.ONLY_MULTI_GPU_TEST, this.@pipeline.runParameters.DISABLE_MULTI_GPU_TEST,
    ]
    return deferFlags.findAll { testFilter[it] }
                     .collect { "${it}=${testFilter[it]}" }
}

// Parse CBTS JSON stdout into a map. `scope == null` → no decision; caller
// logs reasons and defers.
def _cbtsParseSelectionResult(String text)
{
    def data = new groovy.json.JsonSlurper().parseText(text)
    return [
        scope: data.scope,
        affected_stages: data.affected_stages ?: [],
        reasons: data.reasons ?: [],
        test_db_dir_override: data.test_db_dir_override,
        affected_stage_test_counts: data.affected_stage_test_counts ?: [:],
        affected_stage_split_counts: data.affected_stage_split_counts ?: [:],
        // The first pass uses these to decide whether Tier 2 should prepare a DB.
        coverage_residual_files: data.coverage_residual_files ?: [],
        coverage_decline_reason: data.coverage_decline_reason ?: "",
        // Explicit null check preserves `false`; default True is safe.
        sanity_required: data.sanity_required != null ? data.sanity_required : true,
        perfsanity_required: data.perfsanity_required != null ? data.perfsanity_required : true,
        // Coverage tier omits multi-GPU stages; L0_Test re-adds them under this flag.
        enable_multi_gpu: data.enable_multi_gpu ?: false,
    ]
}

return this
