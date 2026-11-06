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

package trtllm.ci.results

class ParentResults implements Serializable {
    def collectTestResults(pipeline, testFilter, globalVars)
    {
        def collectResultPodSpec = pipeline.createKubernetesPodConfig("", "agent")
        pipeline.trtllm_utils.launchKubernetesPod(pipeline, collectResultPodSpec, "alpine", {
            // 1. Serial: download tarballs, extract, and run junit
            pipeline.stage ("Collect Test Result") {
                pipeline.sh "rm -rf **/*.xml *.tar.gz"

                def testResultLink = "https://urm.nvidia.com/artifactory/${pipeline.UPLOAD_PATH}/test-results"

                pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "apk add --no-cache curl")
                pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "apk add python3")
                pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "wget ${testResultLink}/", allowStepFailed: true)
                pipeline.sh "cat index.html | grep \"tar.gz\" | cut -d \"\\\"\" -f 2 > result_file_names.txt"
                pipeline.sh "cat result_file_names.txt"
                pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "cat result_file_names.txt | xargs -n1 -I {} wget -c -nv ${testResultLink}/{}", allowStepFailed: true)
                pipeline.sh "ls -l | grep \"tar.gz\" || true"
                def resultFileNumber = pipeline.sh(script: "cat result_file_names.txt | wc -l", returnStdout: true)
                def resultFileDownloadedNumber = pipeline.sh(script: "ls -l | grep \"tar.gz\" | wc -l", returnStdout: true)
                pipeline.echo "Result File Number: ${resultFileNumber}, Downloaded: ${resultFileDownloadedNumber}"

                pipeline.sh "find . -name results-\\*.tar.gz -type f -exec tar -zxvf {} \\; || true"
                pipeline.trtllm_utils.checkoutSource(pipeline.LLM_REPO, pipeline.env.gitlabCommit, pipeline.LLM_ROOT, true, true)

                pipeline.junit(testResults: '**/results*.xml', allowEmptyResults : true)

                // Pre-install shared dependencies for parallel tasks
                pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "apk add py3-pip")
                pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "pip3 config set global.break-system-packages true")
                // This is minimal alpine pod, install the libraries needed by CBTS utilities manually
                pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "pip3 install -r ${pipeline.LLM_ROOT}/jenkins/scripts/cbts/requirements.txt")
            } // Collect test result stage

            // 2. Parallel: Rerun Report, Test Coverage, and AI Failure Analysis
            def parallelTasks = [:]
            parallelTasks["Rerun Report"] = {
                try {
                pipeline.timeout(time: 10, unit: 'MINUTES') {
                pipeline.stage("Rerun Report") {
                    pipeline.sh "rm -rf rerun && mkdir -p rerun"
                    pipeline.sh "find . -type f -wholename '*/rerun_results.xml' -exec sh -c 'mv \"{}\" \"rerun/\$(basename \$(dirname \"{}\"))_rerun_results.xml\"' \\; || true"
                    pipeline.sh "find rerun -type f"
                    def rerunFileCount = pipeline.sh(returnStdout: true, script: 'find rerun -type f | wc -l').replaceAll("\\s","").toInteger()
                    if (rerunFileCount == 0) {
                        pipeline.echo "Rerun report is skipped because there is no rerun test data file."
                        return
                    }
                    def xmlFiles = pipeline.findFiles(glob: 'rerun/**/*.xml')
                    def xmlFileList = xmlFiles.collect { it.path }
                    def inputfiles = xmlFileList.join(',')
                    pipeline.echo "inputfiles: ${inputfiles}"
                    pipeline.sh """
                    python3 llm/jenkins/scripts/test_rerun.py \
                    generate_rerun_report \
                    --output-file=rerun/rerun_report.xml \
                    --input-files=${inputfiles}
                """
                    if (pipeline.fileExists("rerun/rerun_report.html")) {
                        try {
                            pipeline.trtllm_utils.uploadArtifacts("rerun/rerun_report.html", "${pipeline.UPLOAD_PATH}/test-results/")
                            pipeline.echo "Rerun report: https://urm.nvidia.com/artifactory/${pipeline.UPLOAD_PATH}/test-results/rerun_report.html"
                        } catch (Exception e) {
                            pipeline.echo "Failed to upload rerun report: ${e.toString()}"
                        }
                    } else {
                        pipeline.echo "No rerun test results found, skipping rerun report upload."
                    }
                    pipeline.catchError(
                        buildResult: 'SUCCESS',
                        stageResult: 'UNSTABLE') {
                        pipeline.error "Some failed tests were reruned, please check the rerun report."
                    }
                } // Rerun report stage
                } // timeout 10 min
                } catch (Exception e) {
                    pipeline.echo "Rerun Report failed or timed out: ${e.toString()}"
                }
            }
            parallelTasks["Test Coverage"] = {
                try {
                pipeline.timeout(time: 10, unit: 'MINUTES') {
                try {
                    pipeline.stage("Test Coverage") {
                        pipeline.sh "ls"
                        pipeline.sh "rm -rf cov && mkdir -p cov"
                        // Collect the per-process PY_START data files from every stage's results tarball.
                        pipeline.sh "find . -type f -name '.cbtscov.*.sqlite' -exec mv -t cov/ {} + || true"
                        pipeline.sh "cd cov && ls -la"
                        def fileCount = pipeline.sh(returnStdout: true, script: 'find cov -name ".cbtscov.*.sqlite" | wc -l').replaceAll("\\s","").toInteger()
                        if (fileCount == 0) {
                            pipeline.echo "CBTS coverage skipped: no PY_START data files."
                            return
                        }
                        // Merge into the indexed touch DB, a per-file HTML report, and the coverage rate.
                        pipeline.sh """
                        PYTHONPATH=llm/jenkins/scripts/cbts \
                        python3 -m cbts.command coverage collection pystart-report \
                            --glob 'cov/.cbtscov.*.sqlite' \
                            --out-sqlite cov/cbts_touchmap.sqlite \
                            --out-dir cov/cbts_report \
                            --source-root llm
                    """
                        // Upload compressed only: the guardword scanner byte-matches raw files but not archives.
                        pipeline.sh "cd cov && tar czf cbts_pystart_report.tar.gz cbts_touchmap.sqlite cbts_report"
                        pipeline.trtllm_utils.uploadArtifacts("cov/cbts_pystart_report.tar.gz", "${pipeline.UPLOAD_PATH}/cbts-coverage/")
                        pipeline.echo "CBTS coverage (touch DB + report): https://urm.nvidia.com/artifactory/${pipeline.UPLOAD_PATH}/cbts-coverage/cbts_pystart_report.tar.gz"
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
                    pipeline.echo "Test Coverage failed or timed out: ${e.toString()}"
                }
            }
            if (pipeline.currentBuild.currentResult == 'FAILURE') {
                parallelTasks["AI Failure Analysis"] = {
                    try {
                    pipeline.timeout(time: 10, unit: 'MINUTES') {
                    pipeline.stage("AI Failure Analysis") {
                        try {
                            def prNumber = null
                            if (globalVars[pipeline.runParameters.GITHUB_PR_API_URL]) {
                                def prMatch = (globalVars[pipeline.runParameters.GITHUB_PR_API_URL] =~ /\/pulls?\/(\d+)/)
                                if (prMatch) {
                                    prNumber = prMatch[0][1]
                                }
                            }
                            def analysis = pipeline.trtllm_utils.analyzePipelineFailureWithAgent(
                                pipeline, pipeline.env.JOB_NAME, pipeline.env.BUILD_NUMBER, prNumber)
                            if (analysis) {
                                def bucket = 'sw-tensorrt-ci-analysis'
                                def key = "${pipeline.env.JOB_NAME}/${pipeline.env.BUILD_NUMBER}/failure_analysis.html"
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
<title>CI Failure Analysis &middot; ${pipeline.env.JOB_NAME} #${pipeline.env.BUILD_NUMBER}</title>
<script src="https://cdn.jsdelivr.net/npm/marked@14.1.4/marked.min.js" integrity="sha384-lqPzN0kmFw9t2syAMwVPM4VbAyqsz/lPyYWbb2Xt6nSPM0WPNrpSWCUBgdcAdgnC" crossorigin="anonymous"></script>
<script src="https://cdn.jsdelivr.net/npm/dompurify@3.2.4/dist/purify.min.js" integrity="sha384-eEu5CTj3qGvu9PdJuS+YlkNi7d2XxQROAFYOr59zgObtlcux1ae1Il3u7jvdCSWu" crossorigin="anonymous"></script>
<style>body{font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;max-width:900px;margin:2em auto;padding:0 1em;color:#24292e}h1,h2,h3{border-bottom:1px solid #eaecef;padding-bottom:.3em}pre{background:#f6f8fa;padding:1em;overflow:auto;border-radius:6px}code{background:#f6f8fa;padding:.2em .4em;border-radius:3px}pre code{background:none;padding:0}a{color:#0366d6}blockquote{border-left:4px solid #dfe2e5;padding:0 1em;color:#6a737d}table{border-collapse:collapse}th,td{border:1px solid #dfe2e5;padding:6px 13px}header{margin-bottom:1.5em;color:#586069}</style>
</head><body>
<header><a href="${pipeline.env.BUILD_URL}">${pipeline.env.JOB_NAME} #${pipeline.env.BUILD_NUMBER}</a></header>
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
                                pipeline.writeFile file: 'failure_analysis.html', text: htmlDoc
                                pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: 'apk add --no-cache aws-cli')
                                // Alpine's musl libc fires A and AAAA queries in parallel; pbss.s8k.io's AAAA
                                // returns SERVFAIL and musl treats that as a fatal lookup failure (glibc would
                                // not). Pin the A-record IP in /etc/hosts so getaddrinfo resolves from files.
                                pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: '''
                                if ! grep -q 'pbss.s8k.io' /etc/hosts; then
                                    ip=$(nslookup -type=A pbss.s8k.io 2>/dev/null | awk '/^Address[: ]/ && $NF !~ /:53$/ && $NF !~ /#53$/ { print $NF; exit }')
                                    if [ -n "$ip" ]; then
                                        printf '%s\\n' "$ip pbss.s8k.io" >> /etc/hosts
                                    fi
                                fi
                            ''')
                                pipeline.withCredentials([pipeline.string(
                                        credentialsId: 'svc_tensorrt-swift-stack-key',
                                        variable: 'AWS_SECRET_ACCESS_KEY')]) {
                                    pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script:
                                        "AWS_ACCESS_KEY_ID=svc_tensorrt aws s3 cp failure_analysis.html" +
                                        " 's3://${bucket}/${key}' --endpoint-url https://pbss.s8k.io" +
                                        " --content-type text/html")
                                }
                                // Surface the URL via currentBuild.description so the upstream PR_Github
                                // wrapper can extract it and include it in the GitHub PR comment.
                                def existingDesc = pipeline.currentBuild.description ?: ""
                                pipeline.currentBuild.description = existingDesc +
                                    (existingDesc ? "<br/>" : "") +
                                    "<a href='${htmlUrl}'>CI Agent Failure Analysis</a>"
                                pipeline.echo "CI Agent Failure Analysis: ${htmlUrl}"
                            }
                        } catch (Exception e) {
                            // Analysis is best-effort; do not fail the pipeline
                        }
                    }
                    } // timeout 10 min
                    } catch (Exception e) {
                        pipeline.echo "AI Failure Analysis failed or timed out: ${e.toString()}"
                    }
                }
            }
            pipeline.parallel parallelTasks
        })
    }
}
