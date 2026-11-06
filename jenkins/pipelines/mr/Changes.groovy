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

@Field def pipeline

def initialize(pipeline)
{
    this.pipeline = pipeline
    return this
}

def getGitlabMRChangedFile(pipeline, function, filePath="") {
    def result = null
    def pageId = 0
    pipeline.withCredentials([
        pipeline.usernamePassword(
            credentialsId: 'svc_tensorrt_gitlab_read_api_token',
            usernameVariable: 'GITLAB_API_USER',
            passwordVariable: 'GITLAB_API_TOKEN'
        ),
        pipeline.string(credentialsId: 'default-git-url', variable: 'DEFAULT_GIT_URL')
    ]) {
        while(true) {
            pageId += 1
            def rawDataJson = pipeline.sh(
                script: """
                    curl --header "PRIVATE-TOKEN: $pipeline.GITLAB_API_TOKEN" \
                         --url "https://${pipeline.DEFAULT_GIT_URL}/api/v4/projects/${pipeline.env.gitlabMergeRequestTargetProjectId}/merge_requests/${pipeline.env.gitlabMergeRequestIid}/diffs?page=${pageId}&per_page=20"
                """,
                returnStdout: true
            )
            def rawDataList = pipeline.readJSON text: rawDataJson, returnPojo: true
            if (function == "getOneFileChanges") {
                if (result == null) {
                    result = ""
                }
                rawDataList.find { rawData ->
                    if (rawData.get("new_path") == filePath || rawData.get("old_path") == filePath) {
                        result = rawData.get("diff")
                        return true
                    }
                    return false
                }
                if (result != "") { break }
            } else if (function == "getChangedFileList") {
                if (result == null) {
                    result = []
                }
                rawDataList.each { rawData ->
                    result += [rawData.get("old_path"), rawData.get("new_path")]
                }
            } else if (function == "getFileChanges") {
                if (result == null) {
                    result = [:]
                }
                rawDataList.each { rawData ->
                    [rawData.get("old_path"), rawData.get("new_path")]
                        .findAll { it }
                        .each { changedFilePath -> result[changedFilePath] = rawData.get("diff") }
                }
            }
            if (!rawDataList) { break }
        }
    }
    return result
}

def getGithubMRChangedFile(pipeline, githubPrApiUrl, function, filePath="") {
    def result = null
    def pageId = 0
    pipeline.withCredentials([
        pipeline.usernamePassword(
            credentialsId: 'github-cred-trtllm-ci',
            usernameVariable: 'NOT_USED_YET',
            passwordVariable: 'GITHUB_API_TOKEN'
        ),
    ]) {
        while(true) {
            pageId += 1
            def rawDataJson = pipeline.sh(
                script: """
                    curl --header "Authorization: Bearer \${GITHUB_API_TOKEN}" \
                         --url "${githubPrApiUrl}/files?page=${pageId}&per_page=20"
                """,
                returnStdout: true
            )
            pipeline.echo "rawDataJson: ${rawDataJson}"
            def rawDataList = pipeline.readJSON text: rawDataJson, returnPojo: true
            if (function == "getOneFileChanges") {
                if (result == null) {
                    result = ""
                }
                rawDataList.find { rawData ->
                    if (rawData.get("filename") == filePath || rawData.get("previous_filename") == filePath) {
                        result = rawData.get("patch") ?: ""
                        return true
                    }
                    return false
                }
                if (result != "") { break }
            } else if (function == "getChangedFileList") {
                if (result == null) {
                    result = []
                }
                rawDataList.each { rawData ->
                    result += [rawData.get("filename"), rawData.get("previous_filename")].findAll { it }
                }
            } else if (function == "getFileChanges") {
                if (result == null) {
                    result = [:]
                }
                rawDataList.each { rawData ->
                    [rawData.get("filename"), rawData.get("previous_filename")]
                        .findAll { it }
                        .each { changedFilePath -> result[changedFilePath] = rawData.get("patch") ?: "" }
                }
            }
            if (!rawDataList) { break }
        }
    }
    return result
}

def getGitMirrorMRChangedFile(pipeline, globalVars, function, filePath="", filePaths=[]) {
    def headCommit = pipeline.env.gitlabCommit?.toString()
    // The merge queue names the base commit; for a PR, the wrapper pushed the base to the mirror.
    def baseRef = globalVars[pipeline.BASE_COMMIT]
    if (!baseRef) {
        def wrapperBuildNumber = globalVars[pipeline.ACTION_INFO]?.get("parents")?.getAt(0)?.get("build_number")?.toString()
        baseRef = "refs/heads/prjob/${wrapperBuildNumber}/base"
    }
    pipeline.withEnv(["GIT_DIFF_BASE_REF=${baseRef}"]) {
        pipeline.withCredentials([pipeline.gitUsernamePassword(credentialsId: 'svc_tensorrt_gitlab_api_token', gitToolName: 'Default'),]) {
            pipeline.sh "git -C ${pipeline.LLM_ROOT} fetch --no-tags --depth=1 origin \"\${GIT_DIFF_BASE_REF}\""
        }
    }
    def baseCommit = pipeline.sh(script: "git -C ${pipeline.LLM_ROOT} rev-parse FETCH_HEAD", returnStdout: true).trim()
    pipeline.echo("Using internal Git mirror diff: ${baseCommit}...${headCommit}")

    def nameStatus = pipeline.sh(
        script: "git -C ${pipeline.LLM_ROOT} -c core.quotepath=false diff --name-status --find-renames ${baseCommit} ${headCommit}",
        returnStdout: true
    )
    def changedFiles = nameStatus.readLines().collect { line ->
        def fields = line.split('\t', -1)
        def paths = []
        for (int index = 1; index < fields.length; index++) {
            if (fields[index]) {
                paths.add(fields[index])
            }
        }
        [status: fields[0], paths: paths]
    }
    if (function == "getChangedFileList") {
        return changedFiles.collectMany { changedFile ->
            changedFile.status.startsWith("R") || changedFile.status.startsWith("C")? changedFile.paths.reverse(): changedFile.paths
        }
    }

    def renamePaths = [:]
    changedFiles.findAll { changedFile ->
        changedFile.status.startsWith("R") || changedFile.status.startsWith("C")
    }.each { changedFile ->
        changedFile.paths.each { changedFilePath -> renamePaths[changedFilePath] = changedFile.paths }
    }
    def cachedDiffs = [:]
    def getFileDiff = { changedFilePath ->
        if (cachedDiffs.containsKey(changedFilePath)) {
            return cachedDiffs[changedFilePath]
        }
        def diffPaths = renamePaths.get(changedFilePath, [changedFilePath])
        def rawDiff = ""
        pipeline.withEnv([
            "GIT_DIFF_PATH=${diffPaths[0]}",
            "GIT_DIFF_RENAME_PATH=${diffPaths.size() > 1 ? diffPaths[1] : diffPaths[0]}",
        ]) {
            rawDiff = pipeline.sh(
                script: "git -C ${pipeline.LLM_ROOT} diff --unified=3 --inter-hunk-context=1 --find-renames ${baseCommit} ${headCommit} -- \":(literal)\${GIT_DIFF_PATH}\" \":(literal)\${GIT_DIFF_RENAME_PATH}\"",
                returnStdout: true
            )
        }
        def lines = rawDiff.readLines()
        def firstHunk = lines.findIndexOf { it.startsWith("@@") }
        def diff = firstHunk < 0 ? "" : lines.drop(firstHunk).join("\n")
        diffPaths.each { diffPath -> cachedDiffs[diffPath] = diff }
        return diff
    }

    if (function == "getOneFileChanges") {
        return getFileDiff(filePath)
    }
    if (function == "getFileChanges") {
        return filePaths.unique().collectEntries { changedFilePath -> [(changedFilePath): getFileDiff(changedFilePath)] }
    }
    pipeline.error("Unsupported PR diff operation: ${function}")
}

def getGithubMRChangedFileWithFallback(pipeline, globalVars, function, filePath="", filePaths=[]) {
    try {
        return getGithubMRChangedFile(pipeline, globalVars[pipeline.GITHUB_PR_API_URL], function, filePath)
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        pipeline.echo("WARNING: [PR_DIFF_FALLBACK] GitHub PR files API failed for ${function}; " +
                      "trying the internal Git mirror. Error: ${e.toString()}")
        def result = getGitMirrorMRChangedFile(pipeline, globalVars, function, filePath, filePaths)
        pipeline.echo("WARNING: [PR_DIFF_FALLBACK] Internal Git mirror fallback succeeded for ${function}.")
        return result
    }
}

// Changed files or diffs of this build, from the first source (in `globalVars`):
//   github_pr_api_url  GitHub PR files API, internal Git mirror with `git diff` as the fallback;
//   base_commit        internal Git mirror, git diff base_commit..gitlabCommit;
//   otherwise          GitLab MR diffs API.
// function:
//  - getChangedFileList -> list of paths
//  - getOneFileChanges -> diff of `filePath`
//  - getFileChanges -> map of path to diff, for the paths in `filePaths`
def getMRChangedFile(pipeline, globalVars, function, filePath="", filePaths=[]) {
    def result
    if (globalVars[pipeline.GITHUB_PR_API_URL]) {
        result = getGithubMRChangedFileWithFallback(pipeline, globalVars, function, filePath, filePaths)
    } else if (globalVars[pipeline.BASE_COMMIT]) {
        result = getGitMirrorMRChangedFile(pipeline, globalVars, function, filePath, filePaths)
    } else {
        result = getGitlabMRChangedFile(pipeline, function, filePath)
    }
    if (function == "getFileChanges") {
        result = result.findAll { changedFilePath, diff -> changedFilePath in filePaths }
    }
    return result
}

// Gate multi-GPU stages behind 'ci: full pre-merge approved' label.
// Uses trtllm_utils.validatePRLabelApproval() from the shared lib to verify
// both label existence and that the labeler is an active team member.
// Exempt: PostMerge pipelines and GitLab MR builds (no GITHUB_PR_API_URL).
def requireMultiGpuApprovalLabel(pipeline, globalVars, String arch) {
    if (!globalVars[pipeline.GITHUB_PR_API_URL]) {
        pipeline.echo "[requireMultiGpuApprovalLabel] Skipping label check: not a GitHub PR (no GITHUB_PR_API_URL)"
        return false
    }
    if (pipeline.env.JOB_NAME ==~ /.*PostMerge.*/) {
        pipeline.echo "[requireMultiGpuApprovalLabel] Skipping label check: PostMerge pipeline is exempt"
        return false
    }

    def prMatch = (globalVars[pipeline.GITHUB_PR_API_URL] =~ /\/pulls?\/(\d+)/)
    if (!prMatch) {
        pipeline.echo "[requireMultiGpuApprovalLabel] Could not extract PR number from ${globalVars[pipeline.GITHUB_PR_API_URL]}. Failing open."
        return false
    }
    def prNumber = prMatch[0][1]

    def result = pipeline.trtllm_utils.validatePRLabelApproval(pipeline, prNumber, "ci: full pre-merge approved")
    if (!result.checkCompleted) {
        // API error — fail-open: do not block CI if the label check itself fails
        pipeline.echo "[requireMultiGpuApprovalLabel] Label validation incomplete (${result.error}). Failing open."
        return false
    }
    if (result.labelExists && result.authorized) {
        return false
    }

    // Label missing or unauthorized — write description marker for wrapper
    // to surface in PR comment, and return the block reason string.
    def existingDesc = pipeline.currentBuild.description ?: ""
    pipeline.currentBuild.description = existingDesc + (existingDesc ? "<br/>" : "") +
        "<span data-multi-gpu-label-required='true'>" +
        "Multi-GPU tests require label 'ci: full pre-merge approved'" +
        "</span>"
    def reason = !result.labelExists
        ? "label 'ci: full pre-merge approved' is not present on this PR"
        : "label 'ci: full pre-merge approved' was applied by '${result.actor}' who is not an active member of NVIDIA/trt-llm-ci-approvers"
    def blockMsg = "${arch} Multi-GPU tests blocked: ${reason}. " +
         "Ask a member of NVIDIA/trt-llm-ci-approvers to add the label, then re-trigger CI."
    pipeline.echo "[requireMultiGpuApprovalLabel] ${blockMsg}"
    return blockMsg
}

def getMergeRequestChangedFileList(pipeline, globalVars) {
    def isOfficialPostMergeJob = (pipeline.env.JOB_NAME ==~ /.*PostMerge.*/)
    if ((pipeline.params.InfraDryRun?.toString()?.toBoolean() ?: false) ||
        isOfficialPostMergeJob ||
        pipeline.runMode == "nightly_release") {
        pipeline.echo("Force set changed file list to empty list.")
        return []
    }

    if (globalVars[pipeline.CACHED_CHANGED_FILE_LIST] != null) {
        return globalVars[pipeline.CACHED_CHANGED_FILE_LIST]
    }
    try {
        def changedFileList = getMRChangedFile(pipeline, globalVars, "getChangedFileList")
        def changedFileListStr = changedFileList.join(",\n")
        pipeline.echo("The changeset of this MR is: ${changedFileListStr}.")
        globalVars[pipeline.CACHED_CHANGED_FILE_LIST] = changedFileList
        return globalVars[pipeline.CACHED_CHANGED_FILE_LIST]
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        pipeline.echo("Get merge request changed file list failed. Error: ${e.toString()}")
        globalVars[pipeline.CACHED_CHANGED_FILE_LIST] = []
        return globalVars[pipeline.CACHED_CHANGED_FILE_LIST]
    }
}

def getMergeRequestOneFileChanges(pipeline, globalVars, filePath) {
    // Note: This function intentionally propagates exceptions to the caller.
    // If there is an error to get the changed file diff, skip merging the waive list.
    def isOfficialPostMergeJob = (pipeline.env.JOB_NAME ==~ /.*PostMerge.*/)
    if ((pipeline.params.InfraDryRun?.toString()?.toBoolean() ?: false) ||
        isOfficialPostMergeJob ||
        pipeline.runMode == "nightly_release") {
        pipeline.echo("Force set changed file diff to empty string.")
        return ""
    }

    def diff = getMRChangedFile(pipeline, globalVars, "getOneFileChanges", filePath)
    pipeline.echo("The change of ${filePath} is: ${diff}")
    return diff
}

return this
