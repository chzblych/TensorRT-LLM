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
import com.nvidia.bloom.CloudManager
import com.nvidia.bloom.SlurmCluster
import com.nvidia.bloom.Utils
import trtllm.exceptions.InfraFailure

@Field def pipeline

def initialize(pipeline)
{
    this.@pipeline = pipeline
    return this
}

// `postTag` uniquifies the uploaded tar filename, the Artifactory guard key and
// the locally-staged result XMLs when the same stageName is uploaded more than
// once in a build (e.g. SLURM infra-failure retries). First attempt passes "".
def uploadResults(def pipeline, SlurmCluster cluster, String clusterName, String nodeName, String stageName, String postTag="", boolean suppressTestReporting=false) {
    pipeline.stage('Submit Test Result') {
        pipeline.sh "ls -al ${stageName}/ || true"
        if (!pipeline.fileExists(stageName)) {
            pipeline.echo "No local test-result directory for ${stageName}; skipping upload."
            return
        }

        if (suppressTestReporting) {
            // This attempt is superseded by a planned retry. Rename its XMLs so
            // Collect Test Result does not ingest them after extracting the tar.
            // The progress snapshot still contains the old names, so it must not
            // be promoted and the modified directory must be uploaded instead.
            pipeline.sh """
                cd ${stageName} && for f in results*.xml; do
                    [ -e "\$f" ] && mv "\$f" "superseded-\$f"
                done || true
            """
        }

        // Always re-tar the local stage directory and upload it directly, rather
        // than trusting the last progress snapshot uploaded by
        // progress_upload_snapshot.sh to still match it (the superseded-file
        // rename above, or other post-snapshot writes, can leave it stale). Use
        // --transform so tar contents carry the postTag filename without
        // touching on-disk results*.xml files.
        pipeline.testSelection.ensureStageResultNotUploaded("${stageName}${postTag}")
        def transformOpt = postTag ? "--transform 's|^\\(${stageName}/results[^/]*\\)\\.xml\$|\\1${postTag}.xml|'" : ""
        pipeline.sh "tar -czvf results-${stageName}${postTag}.tar.gz ${transformOpt} ${stageName}/"
        pipeline.trtllm_utils.uploadArtifacts(
            "results-${stageName}${postTag}.tar.gz",
            "${pipeline.UPLOAD_PATH}/test-results/"
        )
        deleteProgressArtifact(stageName, postTag)

        // Pull this stage's per-process .cbtscov files as one archive into ${stageName}/cbts/; bounded and non-fatal.
        if (pipeline.testExecution.isCbtsStage(stageName)) {
            CloudManager.withSlurmSshCredentials(pipeline, clusterName, cluster) { remote ->
                def remoteWs = "/home/svc_tensorrt/bloom/scripts/${nodeName}"
                def cbtsLocalDir = "${stageName}/cbts"
                def cbtsArchive = "cbts_coverage_${stageName}.tar.gz"
                pipeline.sh "mkdir -p ${cbtsLocalDir}"
                try {
                    pipeline.timeout(time: 5, unit: 'MINUTES') {
                        // Freeze writers, then pack on the login node; an archive with no member is dropped.
                        Utils.exec(
                            pipeline,
                            script: Utils.sshUserCmd(
                                remote,
                                "\"cd '${remoteWs}' && sleep 2 && " +
                                "tar czf '${cbtsArchive}' .cbtscov.${stageName}* 2>/dev/null; " +
                                "tar tzf '${cbtsArchive}' 2>/dev/null | grep -q . || rm -f '${cbtsArchive}'\""
                            ),
                            returnStatus: true,
                            numRetries: 3,
                        )
                        def gotArchive = Utils.exec(
                            pipeline,
                            script: pipeline.slurmResources.scpFromRemoteCmd(remote, "${remoteWs}/${cbtsArchive}", "${cbtsLocalDir}/"),
                            returnStatus: true,
                            numRetries: 3,
                        ) == 0
                        if (gotArchive) {
                            pipeline.sh "tar xzf ${cbtsLocalDir}/${cbtsArchive} -C ${cbtsLocalDir}/ && rm -f ${cbtsLocalDir}/${cbtsArchive}"
                        } else {
                            pipeline.echo "CBTS: no coverage archive retrieved for ${stageName} (no coverage data or transfer skipped)."
                        }
                    }
                } catch (Exception e) {
                    pipeline.echo "CBTS: coverage pull for ${stageName} skipped (${e.message}); continuing."
                }
            }
        }
    }

    // For a reportable attempt, rename local results*.xml with postTag so
    // junit() reports each attempt separately. Superseded attempts were already
    // renamed above and therefore do not match this loop.
    if (postTag) {
        pipeline.sh """
            cd ${stageName}
            for f in results*.xml; do
                [ -f "\$f" ] || continue
                case "\$f" in *${postTag}.xml) continue ;; esac
                mv "\$f" "\${f%.xml}${postTag}.xml" || true
            done
        """
    }

    // junit() uses local XML files already downloaded by the final progress snapshot.
    def hasLocalResults = pipeline.sh(script: "ls ${stageName}/results*.xml 2>/dev/null | wc -l", returnStdout: true).trim().toInteger() > 0
    if (hasLocalResults && !suppressTestReporting) {
        pipeline.junit(allowEmptyResults: true, testResults: "${stageName}/results*.xml")
    } else if (suppressTestReporting) {
        pipeline.echo "[INFRA-RETRY] ${stageName}${postTag}: suppressing junit() because a retry is still planned"
    }
}

def cacheErrorAndUploadResult(pipeline, stageName, taskRunner, finallyRunner, noResultIfSuccess=false, postTag="", boolean isFinalAttempt=true, Map retryContext=null)
{
    pipeline.testSelection.checkStageName([stageName])
    def Boolean stageIsInterrupted = false
    def Boolean stageIsFailed = true
    Throwable caughtError = null
    try {
        taskRunner()
        stageIsFailed = false
    } catch (InterruptedException e) {
        caughtError = e
        stageIsInterrupted = true
        throw e
    } catch (Exception e) {
        caughtError = e
        throw e
    } finally {
        pipeline.infraRetry.preservePrimaryFailure(caughtError) {
        pipeline.testSelection.ensureStageResultNotUploaded(stageName + postTag)
        if (stageIsInterrupted) {
            pipeline.echo "Stage is interrupted, skip to upload test result."
        } else {
            // Temporarily disable to reduce the log size
            // sh 'if [ "$(id -u)" -eq 0 ]; then dmesg || true; fi'
            boolean shouldProcessResults = !noResultIfSuccess || stageIsFailed
            if (shouldProcessResults) {
            // Suppress synthetic stage-fail XML and junit() when this attempt is
            // an intermediate retry that classified as a retryable infra failure.
            // Without this, every transient pod-level failure poisons the per-build
            // junit report with a permanent "Stage Failed" entry that the retry
            // (on a fresh pod) cannot remove. The tar artifact is still uploaded
            // for forensics; only the in-build test reporting is gated.
            boolean suppressTestReporting = false
            if (stageIsFailed && caughtError != null) {
                if (retryContext != null) {
                    suppressTestReporting = pipeline.infraRetry.retryContextAllowsRetry(pipeline, retryContext, caughtError, false)
                } else if (!isFinalAttempt) {
                    def c = pipeline.infraRetry.classifyFailure(pipeline, caughtError, InfraFailure.K8S)
                    suppressTestReporting = c instanceof InfraFailure
                }
                if (suppressTestReporting) {
                    suppressTestReporting = true
                    pipeline.echo "[INFRA-RETRY] ${stageName}${postTag}: suppressing synthetic stage-fail XML and junit() because a retry is still planned"
                }
            }

            pipeline.echo "noResultIfSuccess: ${noResultIfSuccess}, stageIsFailed: ${stageIsFailed}"
            pipeline.sh "mkdir -p ${stageName}"
            finallyRunner()
            if (stageIsFailed && !suppressTestReporting) {
                if (stageIsInterrupted) {
                    pipeline.echo "Stage is interrupted, skip to generate terminated unexpectedly test result."
                } else if (!pipeline.fileExists("${stageName}/results-timeout.xml")) {
                    // Generate timeout test result xml if there are terminated unexpectedly tests
                    generateTimeoutTestResultXml(pipeline, stageName)
                }
                // Generate stage fail test result xml if the stage failed and there is no result*.xml
                def stageXml = generateStageFailTestResultXml(stageName, "Stage Failed", "Stage run failed without result", "results*.xml")
                if (stageXml != null) {
                    pipeline.sh "echo '${stageXml}' > ${stageName}/results-stage.xml"
                }
            }
            // Redact credential-looking values (e.g. OPEN_SEARCH_DB_CREDENTIALS) before
            // writing: this file gets tar'd and uploaded to Artifactory, and Jenkins
            // credential masking only applies to console log output, not to files.
            pipeline.sh "STAGE_NAME=${stageName} && env | sort | sed -E 's/^([A-Za-z0-9_]*(SECRET|TOKEN|PASSWORD|CREDENTIAL|API_KEY|_PSW)[A-Za-z0-9_]*)=.*/\\1=***REDACTED***/' > ${stageName}/debug_env.txt"
            if (pipeline.testExecution.isCbtsStage(stageName)) {
                pipeline.testExecution.freezeCbtsCoverage(stageName)
            }
            pipeline.echo "Upload test results."
            if (suppressTestReporting) {
                // This attempt is superseded by a planned retry. Keep the tar for
                // forensics, but move its result XMLs aside so the top-level Collect
                // Test Result stage's junit('**/results*.xml') does not re-ingest a
                // superseded attempt's results (e.g. a results-timeout.xml left by a
                // monitor-cut still-running job) and flip the build UNSTABLE even
                // though the stage passed on retry. junit() here was already gated.
                pipeline.sh """
                    cd ${stageName} && for f in results*.xml; do
                        [ -e "\$f" ] && mv "\$f" "superseded-\$f"
                    done || true
                """
            }

            // Always re-tar and re-upload the current state of ${stageName}/,
            // rather than trusting the last progress snapshot to still match it:
            // debug_env.txt, CBTS coverage freeze, a rerun merge rewriting
            // results.xml, or the superseded-file rename above can all leave the
            // last uploaded snapshot stale.
            def transformOpt = postTag ? "--transform 's|^\\(${stageName}/results[^/]*\\)\\.xml\$|\\1${postTag}.xml|'" : ""
            pipeline.sh "tar -czvf results-${stageName}${postTag}.tar.gz ${transformOpt} ${stageName}/"
            pipeline.trtllm_utils.uploadArtifacts(
                "results-${stageName}${postTag}.tar.gz",
                "${pipeline.UPLOAD_PATH}/test-results/"
            )
            deleteProgressArtifact(stageName, postTag)
            if (!suppressTestReporting) {
                pipeline.junit(testResults: "${stageName}/results*.xml")
            }
            }
        }

        // Clean up the workspace
        pipeline.sh """
            env | sort
            pwd && ls -alh
            rm -rf ./*
        """

        pipeline.echo "Finished test stage execution."
        }
    }
}

def generateTimeoutTestResultXml(pipeline, stageName) {
    def scriptPath = pipeline.sh(
        script: "find -L . -name generate_timeout_xml.py | head -n 1 | xargs --no-run-if-empty realpath",
        returnStdout: true
    ).trim()
    if (!scriptPath) {
        // Without the script no results-timeout.xml is written, but the tests that did not
        // finish must still fail the stage.
        def hasUnfinished = pipeline.sh(script: "grep -q '[^[:space:]]' '${stageName}/unfinished_test.txt'", returnStatus: true) == 0
        pipeline.echo "generate_timeout_xml.py not found (source tree may have been cleaned up); skipping timeout XML generation" +
            (hasUnfinished ? ", but ${stageName}/unfinished_test.txt lists unfinished tests." : ".")
        return hasUnfinished
    }
    def curPath = pipeline.sh(script: "realpath .", returnStdout: true).trim()
    def outputFilePath = "${curPath}/${stageName}/results-timeout.xml"
    pipeline.sh """python3 ${scriptPath} --stage-name '${stageName}' --test-file-path 'unfinished_test.txt' --output-file '${outputFilePath}'"""
    if (pipeline.fileExists(outputFilePath)) {
        return true
    }
    return false
}

def generateStageFailTestResultXml(stageName, subName, failureLog, resultPath) {
    String resultFiles = this.@pipeline.sh(script: "cd ${stageName} && ls -l ${resultPath} | wc -l", returnStdout: true).trim()
    this.@pipeline.echo "resultFiles: ${resultFiles}"
    if (resultFiles != "0") {
        return null
    }
    return """<?xml version="1.0" encoding="UTF-8"?><testsuites>
        <testsuite name="${stageName}" errors="0" failures="1" skipped="0" tests="1" time="1.00">
        <testcase name="${subName}" classname="${stageName}" time="1.0">
        <failure message="${failureLog}"> ${failureLog}
        </failure></testcase></testsuite></testsuites>"""
}

// generate_rerun_tests_list opens rerun_0/1/2.txt eagerly but removes any that
// end up empty, so this also guards against a future change to that cleanup.
def fileHasContent(path) {
    return this.@pipeline.fileExists(path) && this.@pipeline.readFile(path).trim()
}

// Return whether the rerun failed and any typed infra failure classified from
// the rerun's originating shell step. The typed failure lets the outer retry
// layer preserve bounded evidence after result/report generation finishes.
def rerunFailedTests(pipeline, stageName, llmSrc, testCmdLine, resultFileName="results.xml", testType="regular", postTag="", Map retryContext=null) {
    if (!pipeline.fileExists("${pipeline.WORKSPACE}/${stageName}/${resultFileName}")) {
        // No results were ever flushed (e.g. the process was killed before any
        // test finished reporting). test_rerun.py treats a missing xml as "no
        // tests ran" and falls back to unfinished_test.txt / the original test
        // list to classify each test, so keep going instead of assuming a
        // rerun already failed.
        pipeline.echo "There is no ${resultFileName} file; falling back to unfinished/not-run classification for ${testType}"
    }

    // Create rerun directory structure to avoid conflicts
    def rerunDir = "${pipeline.WORKSPACE}/${stageName}/rerun/${testType}"
    pipeline.sh "mkdir -p ${rerunDir}"

    // Generate rerun test lists
    def failSignaturesList = pipeline.trtllm_utils.getFailSignaturesList().join(",")
    def testListCmd = testCmdLine.find { it.contains("--test-list=") }
    def testListArg = testListCmd ? "--test-list=${testListCmd.split('=', 2)[1]}" : ""
    def unfinishedTestFile = "${pipeline.WORKSPACE}/${stageName}/unfinished_test.txt"
    def unfinishedTestArg = pipeline.fileExists(unfinishedTestFile) ? "--unfinished-test-file=${unfinishedTestFile}" : ""
    pipeline.sh """
        python3 ${llmSrc}/jenkins/scripts/test_rerun.py \
        generate_rerun_tests_list \
        --output-dir=${rerunDir}/ \
        --input-file=${pipeline.WORKSPACE}/${stageName}/${resultFileName} \
        --fail-signatures='${failSignaturesList}' \
        ${testListArg} \
        ${unfinishedTestArg}
    """

    // If the stage has more than 5 failed tests, skip the rerun step
    def validLineCount = 0
    for (times in [1, 2]) {
        def currentRerunTestList = "${rerunDir}/rerun_${times}.txt"
        if (pipeline.fileExists(currentRerunTestList)) {
            def count = pipeline.sh(
                script: "grep -v '^[[:space:]]*\$' ${currentRerunTestList} | wc -l",
                returnStdout: true
            ).trim().toInteger()
            pipeline.echo "Found ${count} ${testType} tests to rerun ${times} time(s)"
            validLineCount += count
        }
    }

    // Rerun tests
    def isRerunFailed = false
    def rerunInfraFailure = null
    for (times in [1, 2]) {
        def currentRerunTestList = "${rerunDir}/rerun_${times}.txt"
        if (!pipeline.fileExists(currentRerunTestList)) {
            pipeline.echo "No failed ${testType} tests need to be rerun ${times} time(s)"
            continue
        }
        pipeline.sh "cat ${currentRerunTestList}"
        def xmlFile = "${rerunDir}/rerun_results_${times}.xml"
        // change the testCmdLine for rerun
        def noNeedLine = ["--splitting-algorithm", "--splits", "--group", "--cov"]
        def needToChangeLine = ["--test-list", "--csv", "--periodic-junit-xmlpath"]
        def newTestCmdLine = testCmdLine.findAll { cmd ->
            !noNeedLine.any { line -> cmd.contains(line) } && !needToChangeLine.any { line -> cmd.contains(line) }
        }
        newTestCmdLine += [
            "--test-list=${currentRerunTestList}",
            "--csv=${rerunDir}/rerun_report_${times}.csv",
            "--periodic-junit-xmlpath ${xmlFile}",
            "--reruns ${times - 1}"
        ]
        def rerunProgressTar = "results-${stageName}${postTag}-progress.tar.gz"
        def rerunProgressUrl = "https://urm.nvidia.com/artifactory/${pipeline.UPLOAD_PATH}/test-results/${rerunProgressTar}"
        def rerunDoneFile = "${pipeline.WORKSPACE}/.rerun${times}-done-${stageName}"
        pipeline.sh "rm -f ${rerunDoneFile}"
        try {
            pipeline.withCredentials([pipeline.usernamePassword(
                    credentialsId: 'urm-artifactory-creds',
                    usernameVariable: 'ART_USER',
                    passwordVariable: 'ART_PASS')]) {
                pipeline.sh """
                    set +e
                    export STAGE_NAME='${stageName}'
                    export PROGRESS_TAR='${rerunProgressTar}'
                    export PROGRESS_URL='${rerunProgressUrl}'
                    export POST_TAG='${postTag}'
                    # ---- background watcher for rerun${times} ----
                    PROGRESS_DONE_FILE='${rerunDoneFile}' \\
                    PROGRESS_INTERVAL=${pipeline.PROGRESS_UPLOAD_INTERVAL_SEC} \\
                    LABEL_PREFIX='rerun${times} checkpoint' \\
                    XML_PATH='${xmlFile}' \\
                    bash '${llmSrc}/jenkins/scripts/progress_upload_watcher.sh' &
                    WATCHER_PID=\$!

                    # ---- foreground rerun ----
                    cd ${llmSrc}/tests/integration/defs && \\
                    ${newTestCmdLine.join(" ")}
                    rc=\$?

                    touch '${rerunDoneFile}'
                    wait \$WATCHER_PID 2>/dev/null || true

                    # ---- immediate final snapshot of rerun${times} ----
                    LABEL='rerun${times} final snapshot' FINAL_SNAPSHOT=1 \\
                    bash '${llmSrc}/jenkins/scripts/progress_upload_snapshot.sh' || true

                    exit \$rc
                """
            }
        } catch(InterruptedException e) {
            throw e
        } catch (Exception e) {
            if (!pipeline.fileExists(xmlFile)) {
                pipeline.echo "The ${testType} tests crashed when rerun attempt."
                throw e
            }
            String scope = retryContext?.scope ?: InfraFailure.K8S
            def classified = pipeline.infraRetry.classifyFailure(pipeline, e, scope, retryContext)
            if (classified instanceof InfraFailure) {
                rerunInfraFailure = classified
            }
            pipeline.echo "The ${testType} tests still failed after rerun attempt."
            isRerunFailed = true
        }
    }

    pipeline.echo "isRerunFailed for ${testType}: ${isRerunFailed}"
    return [failed: isRerunFailed, infraFailure: rerunInfraFailure]
}

def generateRerunReport(stageName, llmSrc) {
    this.@pipeline.echo "Generating comprehensive rerun report for stage: ${stageName}"

    def rerunBaseDir = "${this.@pipeline.WORKSPACE}/${stageName}/rerun"
    def regularRerunDir = "${rerunBaseDir}/regular"

    // Check if regular rerun directory has rerun_results_*.xml files
    def hasRegularReruns = this.@pipeline.sh(script: "[ -d '${regularRerunDir}' ] && find '${regularRerunDir}' -name 'rerun_results_*.xml' | head -1 | grep -q . && echo 'true' || echo 'false'", returnStdout: true).trim() == 'true'

    // Check if any isolated rerun directories have rerun_results_*.xml files
    def hasIsolatedReruns = this.@pipeline.sh(script: "find ${rerunBaseDir} -type d -name 'isolated_*' -exec find {} -name 'rerun_results_*.xml' \\; 2>/dev/null | head -1 | grep -q . && echo 'true' || echo 'false'", returnStdout: true).trim() == 'true'

    // Find isolated tests that have actual rerun results and build mapping
    def isolatedTestsWithReruns = []
    if (hasIsolatedReruns) {
        def isolatedDirsOutput = this.@pipeline.sh(script: "find ${rerunBaseDir} -type d -name 'isolated_*' 2>/dev/null || true", returnStdout: true).trim()
        if (isolatedDirsOutput) {
            def isolatedDirs = isolatedDirsOutput.split('\n').findAll { it.trim() }
            isolatedDirs.each { isolatedDir ->
                // Extract the isolated number from directory name (e.g., isolated_0 -> 0)
                def isolatedNum = isolatedDir.split('/').last().replace('isolated_', '')

                // Check if this isolated directory has any rerun results
                def hasRerunResults = this.@pipeline.sh(script: "find '${isolatedDir}' -name 'rerun_results_*.xml' | head -1 | grep -q . && echo 'true' || echo 'false'", returnStdout: true).trim() == 'true'

                if (hasRerunResults) {
                    isolatedTestsWithReruns.add([
                        dir: isolatedDir,
                        num: isolatedNum,
                        originalResult: "${this.@pipeline.WORKSPACE}/${stageName}/results_isolated_${isolatedNum}.xml"
                    ])
                }
            }
        }
    }

    // Collect rerun result files and corresponding original result files
    def rerunResultFiles = []

    this.@pipeline.echo "Found regular reruns: ${hasRegularReruns}"
    this.@pipeline.echo "Found isolated tests with reruns: ${isolatedTestsWithReruns.collect { "isolated_${it.num}" }}"

    if (!hasRegularReruns && !hasIsolatedReruns) {
        this.@pipeline.echo "No rerun results found, skipping rerun report generation"
        return
    }

    // Specify the stage name correctly for all result xml files.
    this.@pipeline.sh "cd ${this.@pipeline.WORKSPACE}/${stageName} && find . -name '*.xml' -exec sed -i 's/testsuite name=\"pytest\"/testsuite name=\"${stageName}\"/g' {} + || true"

    // Collect all original and rerun result files
    def allInputFiles = []

    // ---- Regular test results ----
    // Add original results
    if (this.@pipeline.fileExists("${this.@pipeline.WORKSPACE}/${stageName}/results.xml")) {
        allInputFiles.add("${this.@pipeline.WORKSPACE}/${stageName}/results.xml")
        // Add to rerunResultFiles only if it has reruns
        if (hasRegularReruns) {
            rerunResultFiles.add("${this.@pipeline.WORKSPACE}/${stageName}/results.xml")
        }
    }
    // Add regular rerun results
    if (hasRegularReruns) {
        for (times in [1, 2]) {
            def rerunFile = "${regularRerunDir}/rerun_results_${times}.xml"
            if (this.@pipeline.fileExists(rerunFile)) {
                allInputFiles.add(rerunFile)
                rerunResultFiles.add(rerunFile)
            }
        }
    }

    // ---- Isolated test results ----
    // Add ALL isolated test results to allInputFiles
    def isolatedResults = this.@pipeline.sh(script: "find ${this.@pipeline.WORKSPACE}/${stageName} -name 'results_isolated_*.xml' 2>/dev/null || true", returnStdout: true).trim()
    if (isolatedResults) {
        isolatedResults.split('\n').each { file ->
            if (file.trim()) {
                allInputFiles.add(file.trim())
            }
        }
    }
    // Add isolated rerun results
    isolatedTestsWithReruns.each { isolatedTest ->
        if (this.@pipeline.fileExists(isolatedTest.originalResult)) {
            rerunResultFiles.add(isolatedTest.originalResult)
            this.@pipeline.echo "Added isolated result with reruns to rerunResultFiles: ${isolatedTest.originalResult}"
        }
        for (times in [1, 2]) {
            def rerunFile = "${isolatedTest.dir}/rerun_results_${times}.xml"
            if (this.@pipeline.fileExists(rerunFile)) {
                allInputFiles.add(rerunFile)
                rerunResultFiles.add(rerunFile)
            }
        }
    }

    if (allInputFiles.isEmpty()) {
        this.@pipeline.echo "No valid input files found for rerun report generation"
        return
    }

    this.@pipeline.echo "Generating rerun report with input files: ${rerunResultFiles.join(',')}"

    // Generate comprehensive rerun report
    this.@pipeline.sh """
        python3 ${llmSrc}/jenkins/scripts/test_rerun.py \
        generate_rerun_report \
        --output-file=${this.@pipeline.WORKSPACE}/${stageName}/rerun_results.xml \
        --input-files=${rerunResultFiles.join(",")}
    """

    // Update original results xml file with all rerun results for junit
    this.@pipeline.sh """
        python3 ${llmSrc}/jenkins/scripts/test_rerun.py \
        merge_junit_xmls \
        --output-file=${this.@pipeline.WORKSPACE}/${stageName}/results.xml \
        --input-files=${allInputFiles.join(",")} \
        --deduplicate
    """

    // Upload rerun report
    if (this.@pipeline.fileExists("${this.@pipeline.WORKSPACE}/${stageName}/rerun_results.html")) {
        this.@pipeline.trtllm_utils.uploadArtifacts(
            "${this.@pipeline.WORKSPACE}/${stageName}/rerun_results.html",
            "${this.@pipeline.UPLOAD_PATH}/rerun_reports/${stageName}_rerun_results.html"
        )
        this.@pipeline.echo "Test rerun report: https://urm.nvidia.com/artifactory/${this.@pipeline.UPLOAD_PATH}/rerun_reports/${stageName}_rerun_results.html"
    }

    // Remove isolation results since they are merged into results.xml
    this.@pipeline.sh "rm -rf ${this.@pipeline.WORKSPACE}/${stageName}/results_isolated_*.xml || true"

    this.@pipeline.echo "Rerun report generation completed for stage: ${stageName}"
}

def mergeWaivesTxt(pipeline, llmSrc, stageName) {
    try {
        pipeline.testBuild.fetchBuildArtifact(pipeline, "${pipeline.ARTIFACT_PATH}/waive_list/waives.txt", "waives.txt")
        if (!pipeline.fileExists("waives.txt")) {
            pipeline.error "There is no merged waives.txt file, use the default waives.txt."
        }
        pipeline.sh "mv waives.txt ${llmSrc}/tests/integration/test_lists/waives.txt"
        pipeline.echo "Download merged waives.txt successfully"
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        pipeline.echo "Failed to download merged waives.txt, use the default waives.txt. Error: ${e.message}"
    }
}

/**
 * Append passes that previously succeeded for this commit + stage to the
 * stage's waives.txt as SKIPs, so the upcoming pytest run skips them.
 *
 * Two sources are merged:
 *
 *  1. OpenSearch records of prior pipeline runs for the same commit and
 *     stage (the historical mechanism). Populated only after a pipeline
 *     completes -- has nothing to say about the current run.
 *
 *  2. Tarballs uploaded by earlier attempts of the *current* build, when
 *     the infra-failure retry loop has fired. The current run's attempt 1
 *     uploads its (partial) results before the retry kicks off; this
 *     function downloads them and extracts passes via test_rerun.py's
 *     extract_passed_tests mode. Closes the gap where a retry would
 *     otherwise re-run every test that already passed pre-failure.
 *
 * @param llmSrc      Local TRT-LLM source root.
 * @param stageName   Stage name; both the OpenSearch query key and the
 *                    Artifactory artifact prefix (`results-${stageName}*`).
 * @param waivesTxt   Path to the stage's waives.txt; passes are appended
 *                    here with reason "SKIP (Reused from previous pipeline)".
 * @param postTag     This attempt's full tar suffix (e.g. ""
 *                    on attempt 1, "-attempt-2" on the first retry,
 *                    "-SubJob-RunTest-attempt-2" for a retried sub-job).
 *                    Used by priorAttemptTags() to enumerate earlier
 *                    attempts in this build.
 */
def reusePassedTestResults(llmSrc, stageName, waivesTxt, String postTag = "") {
    try {
        def reusedTests = []
        def workDir = "${this.@pipeline.WORKSPACE}/${stageName}"
        this.@pipeline.sh "mkdir -p ${workDir}"

        // 1. OpenSearch lookup -- tests that PASSED in a previous pipeline run
        //    for this commit + sibling stage shards. CBTS and non-CBTS results
        //    are merged only at testcase granularity.
        def passedTestListFile = "${workDir}/passed_test_list.txt"
        def stageNamePattern = this.@pipeline.testSelection.getTestReuseStagePattern(stageName)
        this.@pipeline.sh """
            python3 ${llmSrc}/jenkins/scripts/open_search_query.py \
            --commit-id ${this.@pipeline.env.gitlabCommit} \
            --stage-name-pattern '${stageNamePattern}' \
            --output-file ${passedTestListFile}
        """
        if (this.@pipeline.fileExists(passedTestListFile)) {
            reusedTests += this.@pipeline.readFile(file: passedTestListFile).readLines().collect { it.trim() }.findAll { it }
        }

        // 2. Prior-attempt recovery -- tests that PASSED in an earlier attempt
        //    of THIS pipeline run before infra retry fired. Only runs if postTag
        //    decodes as a retry attempt (matches "...-attempt-N").
        def priorTags = priorAttemptTags(postTag)
        if (!priorTags.isEmpty()) {
            def priorXmls = []
            priorTags.each { priorTag ->
                def tarName = "results-${stageName}${priorTag}.tar.gz"
                def tarUrl = "https://urm.nvidia.com/artifactory/${this.@pipeline.UPLOAD_PATH}/test-results/${tarName}"
                def priorDir = "${workDir}/prior${priorTag.replace('-', '_')}"
                this.@pipeline.sh "mkdir -p ${priorDir}"
                // Probe with HEAD so we can distinguish "this prior attempt never
                // uploaded a tarball" (HTTP 404, expected when an attempt died
                // before its finally block ran) from real errors (auth, 5xx,
                // network). Only 404 is benign; anything else fails the build
                // so silent skips don't mask a configuration regression.
                def httpStatus = this.@pipeline.sh(returnStdout: true,
                                    script: "curl -sS -o /dev/null -w '%{http_code}' --connect-timeout 10 --max-time 30 '${tarUrl}'").trim()
                if (httpStatus == '404') {
                    this.@pipeline.echo "Prior attempt artifact ${tarName} not present (HTTP 404); skipping"
                    return
                }
                if (httpStatus != '200') {
                    this.@pipeline.error "Probing prior attempt artifact ${tarName} returned HTTP ${httpStatus} (expected 200 or 404)"
                }
                this.@pipeline.sh "cd ${priorDir} && wget -nv -nc '${tarUrl}'"
                this.@pipeline.sh "cd ${priorDir} && tar -xzf ${tarName}"
                // results.xml may live at ${stageName}/results.xml inside the
                // tar, or at the tar's root depending on how it was packaged.
                // Scan both. Also match superseded-results*.xml: a suppressed
                // intermediate attempt renames its result XMLs with that prefix
                // (so the build-level junit does not re-ingest the superseded
                // attempt), but its PASSED tests are still valid to reuse here --
                // extract_passed_tests only pulls the passing subset.
                def xmlFiles = this.@pipeline.sh(returnStdout: true,
                                  script: "find ${priorDir} -maxdepth 4 \\( -name 'results*.xml' -o -name 'superseded-results*.xml' \\) 2>/dev/null | tr '\\n' ',' | sed 's/,\$//'").trim()
                if (xmlFiles) {
                    priorXmls += xmlFiles.split(',') as List
                }
            }
            if (!priorXmls.isEmpty()) {
                def priorPassedFile = "${workDir}/prior_attempt_passed.txt"
                this.@pipeline.sh """
                    python3 ${llmSrc}/jenkins/scripts/test_rerun.py \
                    extract_passed_tests \
                    --output-file ${priorPassedFile} \
                    --input-files ${priorXmls.join(',')}
                """
                if (this.@pipeline.fileExists(priorPassedFile)) {
                    def priorPasses = this.@pipeline.readFile(file: priorPassedFile).readLines().collect { it.trim() }.findAll { it }
                    if (!priorPasses.isEmpty()) {
                        this.@pipeline.echo "Reusing ${priorPasses.size()} passed test(s) from prior attempt(s): ${priorTags}"
                    }
                    reusedTests += priorPasses
                }
            }
        }

        // 3. Dedupe and append everything to waives.txt as SKIPs.
        reusedTests = reusedTests.unique()
        if (reusedTests.size() > 0) {
            def reusedTestsContent = reusedTests.collect { test ->
                "${test} SKIP (Reused from previous pipeline)"
            }.join('\n')

            this.@pipeline.echo "Reused tests:\n${reusedTestsContent}"

            this.@pipeline.sh(label: "Append Reused Tests", script: """
cat >> ${waivesTxt} << 'REUSED_TESTS_EOF'
${reusedTestsContent}
REUSED_TESTS_EOF
""")
            this.@pipeline.echo "Appended ${reusedTests.size()} reused tests to ${waivesTxt}"
        } else {
            this.@pipeline.echo "No reused tests found"
        }
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        this.@pipeline.echo "Failed to add passed test list from previous pipeline run to the waives.txt. Error: ${e.message}"
    }
}

// Removes the in-progress checkpoint tarball uploaded by the inline
// shell watcher in runLLMTestlistOnPlatformImpl / runLLMTestlistWithSbatch.
// Called after final-result handling in both execution paths, including when
// test execution failed. Progress checkpoints are not intentionally retained
// based on the test outcome; interruption or upload failure may skip cleanup.
//
// Build-scoped: ${UPLOAD_PATH} is per-build, so unswept progress tars are
// garbage-collected with the rest of the build's artifacts anyway.
def deleteProgressArtifact(stageName, postTag="") {
    // UPLOAD_PATH points to the virtual repo `sw-tensorrt-generic`, which
    // accepts GET/PUT but returns 404 on DELETE. Rewrite to the backing
    // local repo so the delete actually lands.
    def deletePath = this.@pipeline.UPLOAD_PATH.replaceFirst(/^sw-tensorrt-generic\//, 'sw-tensorrt-generic-local/')
    def targetUrl = "https://urm.nvidia.com/artifactory/${deletePath}/test-results/results-${stageName}${postTag}-progress.tar.gz"
    try {
        this.@pipeline.withCredentials([this.@pipeline.usernamePassword(
                credentialsId: 'urm-artifactory-creds',
                usernameVariable: 'ART_USER',
                passwordVariable: 'ART_PASS')]) {
            def httpStatus = this.@pipeline.sh(
                script: "curl -sSo /dev/null -w '%{http_code}' --retry 2 -X DELETE -u \"\$ART_USER:\$ART_PASS\" '${targetUrl}'",
                returnStdout: true
            ).trim()
            if (httpStatus == '204' || httpStatus == '200') {
                this.@pipeline.echo "[PROGRESS-UPLOAD] ${stageName}: deleted progress tar ${targetUrl}"
            } else if (httpStatus == '404') {
                this.@pipeline.echo "[PROGRESS-UPLOAD] ${stageName}: progress tar not found, skipping delete"
            } else {
                this.@pipeline.echo "[PROGRESS-UPLOAD] ${stageName}: delete returned HTTP ${httpStatus} (non-fatal)"
            }
        }
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        this.@pipeline.echo "[PROGRESS-UPLOAD] ${stageName}: progress tar delete failed (non-fatal): ${e.message}"
    }
}

/**
 * Decode a postTag into the postTags used by prior attempts of the same
 * stage in this build.
 *
 * The retry runners compose postTag from two retry layers:
 *   - runKubernetesPodWithInfraRetry (outer K8s pod retry) contributes
 *     ``"-pod-${P}"`` for outer attempt P>=2 (and ``""`` for P=1).
 *   - runLLMTestlistOnSlurm (inner SLURM retry) appends ``"-attempt-${I}"``
 *     for inner attempt I>=2 (and ``""`` for I=1).
 * The two suffixes nest: full postTag = ``${base}${outerTag}${innerSuffix}``
 * where base is the caller-supplied prefix (e.g. ``""`` or
 * ``"-SubJob-RunTest"``). The ``-pod-`` and ``-attempt-`` separators are
 * distinct so the nested form is unambiguous.
 *
 * This function inverts that composition to enumerate the postTags of
 * earlier attempts so reusePassedTestResults() can locate their uploaded
 * tarballs.
 *
 * Returns an empty list when ``postTag`` does not encode a retry — either
 * attempt 1 of attempt 1 (``postTag == ""``), or a caller-supplied tag that
 * never went through the retry loop (e.g. ``"-SubJob-RunTest"``).
 *
 * For nested cases we don't know how many inner attempts each prior outer
 * pod attempt completed, so we over-enumerate up to SLURM_INFRA_RETRY_MAX+1;
 * the HTTP probe in reusePassedTestResults handles 404 for absent tarballs.
 *
 * Examples (with default SLURM_INFRA_RETRY_MAX=2):
 *
 *   ""                            -> []
 *   "-attempt-2"                  -> [""]
 *   "-attempt-3"                  -> ["", "-attempt-2"]
 *   "-pod-2"                      -> ["", "-attempt-2", "-attempt-3"]
 *   "-pod-2-attempt-2"            -> ["", "-attempt-2", "-attempt-3", "-pod-2"]
 *   "-SubJob-RunTest"             -> []
 *   "-SubJob-RunTest-pod-2"       -> ["-SubJob-RunTest", "-SubJob-RunTest-attempt-2", "-SubJob-RunTest-attempt-3"]
 *
 * @param postTag the current attempt's full tar suffix.
 * @return List of postTag strings, ordered oldest-attempt-first.
 */
@NonCPS
def priorAttemptTags(String postTag) {
    if (!postTag) return []
    // Peel "-attempt-N" suffix (inner SLURM retry counter) if present.
    String remaining = postTag
    Integer innerAttempt = null
    def mInner = remaining =~ /^(.*)-attempt-(\d+)$/
    if (mInner.matches()) {
        innerAttempt = (mInner[0][2] as Integer)
        remaining = mInner[0][1]
    }
    // Peel "-pod-P" suffix (outer K8s retry counter) if present.
    Integer podAttempt = null
    def mPod = remaining =~ /^(.*)-pod-(\d+)$/
    if (mPod.matches()) {
        podAttempt = (mPod[0][2] as Integer)
        remaining = mPod[0][1]
    }
    String base = remaining
    if (innerAttempt == null && podAttempt == null) return []   // no retry encoded
    int outerN = podAttempt ?: 1
    int innerN = innerAttempt ?: 1
    int maxInner = this.@pipeline.SLURM_INFRA_RETRY_MAX + 1
    def priors = []
    // Earlier outer attempts (1..outerN-1) with all possible inner attempts.
    for (int p = 1; p < outerN; p++) {
        String podSuffix = (p == 1) ? "" : "-pod-${p}"
        priors << "${base}${podSuffix}".toString()                          // inner attempt 1
        for (int i = 2; i <= maxInner; i++) {
            priors << "${base}${podSuffix}-attempt-${i}".toString()
        }
    }
    // Current outer attempt's earlier inner attempts (1..innerN-1).
    String thisPodSuffix = (outerN == 1) ? "" : "-pod-${outerN}"
    if (innerN > 1) {
        priors << "${base}${thisPodSuffix}".toString()                      // inner attempt 1
        for (int i = 2; i < innerN; i++) {
            priors << "${base}${thisPodSuffix}-attempt-${i}".toString()
        }
    }
    return priors
}

def prepareJenkinsTestResults(pipeline, config, stageName)
{
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
}

def prepareStageWaives(pipeline, llmSrc, stageName, waivesFilePath, postTag, boolean dryRun, String dryRunParent = null)
{
    if (dryRun) {
        def prefix = dryRunParent == null ? "" : "mkdir -p ${dryRunParent} && "
        pipeline.sh "${prefix}: > ${waivesFilePath}"
    } else {
        mergeWaivesTxt(pipeline, llmSrc, stageName)
        if (pipeline.testFilter[(pipeline.runParameters.REUSE_TEST)] != false) {
            reusePassedTestResults(llmSrc, stageName, waivesFilePath, postTag)
        }
    }
}

return this
