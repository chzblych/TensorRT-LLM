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
import groovy.json.JsonSlurper

@Field def pipeline

def initialize(pipeline)
{
    this.@pipeline = pipeline
    return this
}

// Container configuration
def getContainerURIs()
{
    // available tags can be found in: https://artifactory.nvidia.com/artifactory/sw-tensorrt-llm-docker-local/tensorrt-llm/
    // [base_image_name]-[arch]-[os](-[python_version])-[trt_version]-[torch_install_type]-[stage]-[date]-[mr_id]
    def tagProps = this.@pipeline.readProperties file: "${this.@pipeline.LLM_ROOT}/jenkins/current_image_tags.properties", interpolate: true
    def uris = [:]
    def keys = [
        "LLM_DOCKER_IMAGE",
        "LLM_SBSA_DOCKER_IMAGE",
        "LLM_SBSA_WHEEL_DOCKER_IMAGE",
        "LLM_ROCKYLINUX8_PY310_DOCKER_IMAGE",
        "LLM_ROCKYLINUX8_PY312_DOCKER_IMAGE"
    ]
    for (def key in keys) {
        uris[key] = tagProps[key]
    }
    return uris
}

def echoNodeAndGpuInfo(pipeline, stageName)
{
    String hostNodeName = pipeline.sh(script: 'echo $HOST_NODE_NAME', returnStdout: true)
    String gpuUuids = pipeline.sh(script: "nvidia-smi -q | grep \"GPU UUID\" | awk '{print \$4}' | tr '\n' ',' || true", returnStdout: true)
    pipeline.echo "HOST_NODE_NAME = ${hostNodeName} ; GPU_UUIDS = ${gpuUuids} ; STAGE_NAME = ${stageName}"
}

def getReleaseRepo()
{
    def ciVersions = this.@pipeline.readProperties file: "${this.@pipeline.LLM_ROOT}/jenkins/ci_versions.properties"
    def commit = ciVersions.TRTLLM_RELEASE_REPO
    def overrideCommit = this.@pipeline.env."TRTLLM_RELEASE_REPO_OVERRIDE_${commit}"
    if (overrideCommit) {
        this.@pipeline.echo "Overriding TRTLLM_RELEASE_REPO commit with value from environment variable \$TRTLLM_RELEASE_REPO_OVERRIDE_${commit}: ${overrideCommit}"
        commit = overrideCommit
    }
    def url = ""
    this.@pipeline.withCredentials([this.@pipeline.string(credentialsId: "TRTLLM_RELEASE_REPO", variable: "RELEASE_REPO_URL")]) {
        url = this.@pipeline.RELEASE_REPO_URL
    }
    return [url: url, commit: commit]
}

def checkoutTargetBranchFile(String targetBranch, String targetCommit, String sourcePath, String outputPrefix)
{
    if (targetCommit) {
        def outputFile = "${outputPrefix}_${targetCommit}.txt"
        try {
            this.@pipeline.sh "wget https://artifactory.pdx.nvidia.com/artifactory/vcs-remote/NVIDIA/TensorRT-LLM/raw/" +
               "${targetCommit}/${sourcePath} -O ${outputFile}"
            return outputFile
        } catch (InterruptedException e) {
            throw e
        } catch (Exception e) {
            this.@pipeline.echo "Failed to checkout ${sourcePath} by public target commit. Error: ${e.toString()}"
        }
    }

    def outputFile = "${outputPrefix}.txt"
    try {
        this.@pipeline.withCredentials([this.@pipeline.string(credentialsId: 'default-llm-repo', variable: 'DEFAULT_LLM_REPO')]) {
            this.@pipeline.trtllm_utils.checkoutFile(this.@pipeline.DEFAULT_LLM_REPO, targetBranch, sourcePath, ".")
        }
        def sourceFileName = sourcePath.tokenize('/').last()
        this.@pipeline.sh "mv ${sourceFileName} ${outputFile}"
        return outputFile
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        this.@pipeline.echo "Failed to checkout ${sourcePath} from internal GitLab at ref " +
             "${targetBranch}. Error: ${e.toString()}"
    }
    return ""
}

def mergeWaiveList(pipeline, globalVars)
{
    // Get current waive list
    pipeline.sh "git config --global --add safe.directory \"*\""
    pipeline.sh "cp ${pipeline.LLM_ROOT}/tests/integration/test_lists/waives.txt ./waives_CUR_${pipeline.env.gitlabCommit}.txt"
    pipeline.sh "cp ${pipeline.LLM_ROOT}/jenkins/scripts/mergeWaiveList.py ./"

    // Get TOT waive list
    def targetBranch = pipeline.env.gitlabTargetBranch ? pipeline.env.gitlabTargetBranch : globalVars[pipeline.runParameters.TARGET_BRANCH]
    pipeline.echo "Target branch: ${targetBranch}"

    def targetBranchTOTCommit = ""
    try {
        pipeline.withCredentials([pipeline.usernamePassword(credentialsId: 'svc_tensorrt_gitlab_api_token', usernameVariable: 'GITHUB_USER', passwordVariable: 'GITHUB_PASSWORD')]) {
            def apiUrl = "https://api.github.com/repos/NVIDIA/TensorRT-LLM/commits?sha=${targetBranch}&per_page=1"
            def connection = new URL(apiUrl).openConnection()
            connection.setRequestProperty("Authorization", "Basic " + "${pipeline.GITHUB_USER}:${pipeline.GITHUB_PASSWORD}".bytes.encodeBase64().toString())
            connection.setRequestMethod("GET")
            def response = connection.inputStream.text
            def json = new JsonSlurper().parseText(response)
            targetBranchTOTCommit = json[0].sha
        }
        pipeline.echo "Target branch TOT commit: ${targetBranchTOTCommit}"
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        pipeline.echo "Failed to resolve the target branch TOT commit from public GitHub. Error: ${e.toString()}"
    }

    def waiveTot = checkoutTargetBranchFile(
        targetBranch, targetBranchTOTCommit, "tests/integration/test_lists/waives.txt", "waives_TOT")
    if (!waiveTot) {
        pipeline.catchError(
            buildResult: 'SUCCESS',
            stageResult: 'UNSTABLE') {
            pipeline.error "Failed to get TOT waive list. Fallback to use the default test waive list from the PR."
        }
        return [targetBranch: targetBranch, targetCommit: targetBranchTOTCommit]
    }

    try {
        // Get waive list diff in current MR
        def diff = pipeline.mrChanges.getMergeRequestOneFileChanges(pipeline, globalVars, "tests/integration/test_lists/waives.txt")

        // Write diff to a temporary file to avoid shell escaping issues
        pipeline.writeFile file: 'diff_content.txt', text: diff

        // Merge waive lists
        pipeline.sh """
            python3 mergeWaiveList.py \
            --cur-waive-list=waives_CUR_${pipeline.env.gitlabCommit}.txt \
            --latest-waive-list=${waiveTot} \
            --diff-file=diff_content.txt \
            --output-file=waives.txt
        """
        def primary = pipeline.artifactPaths.llmArtifactsPrimary()
        def waiveListTarget = primary == "aws" ?
            "${pipeline.artifactPaths.llmArtifactsRepoPath('aws', pipeline.UPLOAD_PATH)}/waive_list/" : "${pipeline.UPLOAD_PATH}/waive_list/"
        pipeline.trtllm_utils.uploadArtifacts("waives*.txt", waiveListTarget, primary)
        def waiveListUrl = pipeline.artifactPaths.llmArtifactsUrl(primary, "${pipeline.UPLOAD_PATH}/waive_list/waives.txt")
        pipeline.echo "New merged test waive list: ${waiveListUrl}"
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        pipeline.catchError(
            buildResult: 'SUCCESS',
            stageResult: 'UNSTABLE') {
            pipeline.error "Merge test waive list failed. Fallback to use the default test waive list from the PR. Error: ${e.toString()}"
        }
    }
    return [targetBranch: targetBranch, targetCommit: targetBranchTOTCommit]
}

def getMaintenanceStageList(pipeline, globalVars, Map targetBranchInfo)
{
    def maintenanceConfigPath = "jenkins/config/maintenance_stages.txt"
    def targetBranch = targetBranchInfo.targetBranch
    def targetBranchTOTCommit = targetBranchInfo.targetCommit
    def maintenanceSource = "${pipeline.LLM_ROOT}/${maintenanceConfigPath}"
    def maintenanceTot = checkoutTargetBranchFile(
        targetBranch, targetBranchTOTCommit, maintenanceConfigPath, "maintenance_stages_TOT")
    if (!maintenanceTot) {
        pipeline.catchError(
            buildResult: 'SUCCESS',
            stageResult: 'UNSTABLE') {
            pipeline.error "Failed to get target maintenance config. No maintenance stages will be skipped."
        }
        globalVars[pipeline.runParameters.MAINTENANCE_ENTRIES] = [reason: "", patterns: []]
    } else {
        if (!pipeline.fileExists(maintenanceSource)) {
            def changedFileList = pipeline.mrChanges.getMRChangedFile(pipeline, globalVars, "getChangedFileList")
            if (changedFileList.contains(maintenanceConfigPath)) {
                pipeline.error "Deleting or renaming ${maintenanceConfigPath} is not allowed."
            }
        }
        def maintenanceDiff = ""
        try {
            def fetchedMaintenanceDiff = pipeline.mrChanges.getMergeRequestOneFileChanges(
                pipeline, globalVars, maintenanceConfigPath)
            if (fetchedMaintenanceDiff == null) {
                pipeline.echo "WARNING: Maintenance config diff is unavailable. Using target TOT entries."
            } else {
                maintenanceDiff = fetchedMaintenanceDiff
            }
        } catch (InterruptedException e) {
            throw e
        } catch (Exception e) {
            pipeline.echo "WARNING: Failed to get maintenance config diff. " +
                 "Using target TOT entries. Error: ${e.toString()}"
        }
        pipeline.writeFile file: 'maintenance_diff_content.txt', text: maintenanceDiff
        pipeline.sh """
            python3 mergeWaiveList.py \
            --maintenance-config \
            --cur-waive-list=${maintenanceSource} \
            --latest-waive-list=${maintenanceTot} \
            --diff-file=maintenance_diff_content.txt \
            --output-file=maintenance_entries.json
        """
        globalVars[pipeline.runParameters.MAINTENANCE_ENTRIES] = pipeline.readJSON(
            file: 'maintenance_entries.json', returnPojo: true)
    }
    pipeline.echo "Effective maintenance entries: ${globalVars[pipeline.runParameters.MAINTENANCE_ENTRIES]}"
}

return this
