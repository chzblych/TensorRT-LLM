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
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

@Field def pipeline

def initialize(pipeline)
{
    this.pipeline = pipeline
    return this
}

// Upload sqlite-only early coverage after single-GPU finishes, before multi-GPU starts; non-fatal.
def uploadArchCoverage(String arch, pipeline, testFilter) {
    if (!testFilter[(pipeline.CBTS_COVERAGE)]) {
        return
    }
    def sbsaPrefixPattern = "^results-(GH200|GB10|GB200|GB300|CPU-Generic-arm)-"
    def archGrepCmd = (arch == "SBSA")
        ? "grep -E '${sbsaPrefixPattern}' result_file_names.txt > arch_file_names.txt || true"
        : "grep -v -E '${sbsaPrefixPattern}' result_file_names.txt > arch_file_names.txt || true"
    try {
        pipeline.timeout(time: 15, unit: 'MINUTES') {
            def podSpec = pipeline.createKubernetesPodConfig("", "agent")
            pipeline.trtllm_utils.launchKubernetesPod(pipeline, podSpec, "alpine", {
                pipeline.stage("Upload Single-GPU Coverage (${arch})") {
                    def testResultLink = "https://urm.nvidia.com/artifactory/${pipeline.UPLOAD_PATH}/test-results"
                    pipeline.sh "rm -rf cov && mkdir -p cov"
                    pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "apk add --no-cache curl python3 py3-pip")
                    pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "pip3 config set global.break-system-packages true")
                    pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "wget ${testResultLink}/", allowStepFailed: true)
                    pipeline.sh "cat index.html | grep \"tar.gz\" | cut -d \"\\\"\" -f 2 > result_file_names.txt"
                    pipeline.sh archGrepCmd
                    pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "cat arch_file_names.txt | xargs -n1 -I {} wget -c -nv ${testResultLink}/{}", allowStepFailed: true)
                    pipeline.sh "find . -name 'results-*.tar.gz' -type f -exec tar -zxvf {} \\; || true"
                    pipeline.sh "find . -type f -name '.cbtscov.*.sqlite' -exec mv -t cov/ {} + || true"
                    def fileCount = pipeline.sh(returnStdout: true, script: 'find cov -name ".cbtscov.*.sqlite" | wc -l').replaceAll("\\s","").toInteger()
                    if (fileCount > 0) {
                        pipeline.trtllm_utils.checkoutSource(pipeline.LLM_REPO, pipeline.env.gitlabCommit, pipeline.LLM_ROOT, true, true)
                        // This is minimal alpine pod, install the libraries needed by CBTS utilities manually
                        pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "pip3 install -r ${pipeline.LLM_ROOT}/jenkins/scripts/cbts/requirements.txt")
                        pipeline.sh """
                            PYTHONPATH=${pipeline.LLM_ROOT}/jenkins/scripts/cbts \
                            python3 -m cbts.command coverage collection pystart-report \
                                --glob 'cov/.cbtscov.*.sqlite' \
                                --out-sqlite cov/cbts_touchmap.sqlite
                        """
                        pipeline.sh "cd cov && tar czf cbts_pystart_report_${arch}.tar.gz cbts_touchmap.sqlite"
                        pipeline.trtllm_utils.uploadArtifacts("cov/cbts_pystart_report_${arch}.tar.gz", "${pipeline.UPLOAD_PATH}/cbts-coverage/")
                        pipeline.echo "CBTS early coverage (${arch}): https://urm.nvidia.com/artifactory/${pipeline.UPLOAD_PATH}/cbts-coverage/cbts_pystart_report_${arch}.tar.gz"
                    } else {
                        pipeline.echo "CBTS early coverage (${arch}): no data files found, skipping."
                    }
                }
            })
        }
    } catch (FlowInterruptedException e) {
        // The 15-minute timeout above is non-fatal; user aborts and upstream
        // pipeline interruptions must keep propagating.
        if (e.causes.any { it.class.simpleName == 'ExceededTimeout' }) {
            pipeline.echo "CBTS early coverage upload (${arch}) timed out after 15 min (non-fatal): skipping."
        } else {
            throw e
        }
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        pipeline.echo "CBTS early coverage upload (${arch}) failed (non-fatal): ${e.toString()}"
    }
}

return this
