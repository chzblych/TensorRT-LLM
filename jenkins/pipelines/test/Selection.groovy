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
    this.@pipeline = pipeline
    return this
}

def isInfraDryRun() {
    return this.@pipeline.testFilter[(this.@pipeline.runParameters.INFRA_DRY_RUN)] ?: false
}

// CBTS Layer 2.5: rename narrowed stages (reuse-safety) and resize their splits to k.
def cbtsResizeSplits(configs) {
    def cbts = this.@pipeline.testFilter[(this.@pipeline.runParameters.CBTS_RESULT)]
    if (cbts == null || !cbts.cbts_test_db_artifact_path) {
        return configs
    }
    def kByStage = cbts.affected_stage_split_counts
    if (!kByStage) {
        return configs
    }
    def resized = [:]
    configs.each { key, values ->
        def k = kByStage[key]
        if (k == null) {
            resized[key] = values
            return
        }
        int kk = Math.max(1, k as int)
        if ((values[2] as int) > kk) {
            this.@pipeline.echo "CBTS [${cbts.scope}]: ${key} narrowed -> ${kk} shard(s); dropping group ${values[2]}"
            return
        }
        def v = values.collect()
        v[3] = kk
        resized[key + this.@pipeline.CBTS_STAGE_SUFFIX] = v
    }
    return resized
}

// CBTS Layer 2: replace the normal stage set with the selector's affected
// stages while retaining the baseline sanity and multi-GPU gates.
def filterCbtsStageJobs(parallelJobs, parallelJobsFiltered, multiGpuJobs, testFilter) {
    def cbts = testFilter[(this.@pipeline.runParameters.CBTS_RESULT)]
    if (cbts == null) {
        return parallelJobsFiltered
    }

    // cbtsResizeSplits renames only narrowed stages (those in
    // affected_stage_split_counts) to `-cbts`; affected-but-not-narrowed
    // stages keep their original name, so match each per its actual key.
    def stageSuffix = cbts.cbts_test_db_artifact_path ? this.@pipeline.CBTS_STAGE_SUFFIX : ""
    def narrowed = (cbts.affected_stage_split_counts ?: [:]).keySet()
    def affectedSet = (cbts.affected_stages ?: []).collect {
        (stageSuffix && narrowed.contains(it)) ? (it + stageSuffix) : it
    } as Set
    def needsSanity = cbts.sanity_required
    def needsPerfSanity = cbts.perfsanity_required
    def filtered = parallelJobs.findAll { key, _ ->
        if (key.contains("-OnDemand-")) {
            return false
        }
        if (key =~ /Post-Merge/) return affectedSet.contains(key)
        return affectedSet.contains(key) ||
               (needsSanity && key =~ /PackageSanityCheck/) ||
               (needsPerfSanity && key =~ /PerfSanity/)
    }
    // Preserve baseline auto-triggered stages when coverage replaces the selected stage set.
    if (cbts.scope == "coverage") {
        def autoTriggerTags = testFilter[(this.@pipeline.runParameters.AUTO_TRIGGER_TAG_LIST)] ?: []
        filtered += parallelJobsFiltered.findAll { key, _ ->
            autoTriggerTags.any { key.contains(it) }
        }
    }
    if (affectedSet.isEmpty()) {
        if (filtered.isEmpty()) {
            this.@pipeline.echo "CBTS [${cbts.scope}]: trigger-mode mismatch + nothing force-kept → no-op"
        } else {
            this.@pipeline.echo "CBTS [${cbts.scope}]: trigger-mode mismatch — running " +
                 "${filtered.size()} force-kept stage(s) only"
        }
    } else if (filtered) {
        this.@pipeline.echo "CBTS [${cbts.scope}]: limiting to ${filtered.size()} stages " +
             "(sanity_required=${needsSanity}, perfsanity_required=${needsPerfSanity})"
    } else {
        this.@pipeline.echo "CBTS [${cbts.scope}]: empty stage set after filtering"
    }

    // The coverage tier omits multi-GPU; re-add it under the baseline gate.
    if (cbts.enable_multi_gpu && testFilter[(this.@pipeline.runParameters.MULTI_GPU_FILE_CHANGED)]) {
        filtered += multiGpuJobs
        this.@pipeline.echo "CBTS [${cbts.scope}]: multi-GPU file changed → running " +
             "${multiGpuJobs.size()} multi-GPU stage(s) at baseline"
    }
    return filtered
}

// Return the OpenSearch regexp for sibling shards and their CBTS variants.
String getTestReuseStagePattern(String stageName) {
    def stageNamePrefix = stageName.replaceFirst(/-\d+(-cbts)?$/, "")
    return "${stageNamePrefix}-[0-9]+(-cbts)?"
}

def failMaintenanceStage(String stageName, String reason) {
    this.@pipeline.ciState.maintenanceSkips.add(stageName)
    this.@pipeline.echo "Skip - Resource maintenance is in progress. No test agent was launched."
    this.@pipeline.echo "Skip reason: ${reason}"
    this.@pipeline.catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
        this.@pipeline.error "Stage '${stageName}' was not executed because of resource maintenance."
    }
}

def warnUnmatchedMaintenancePatterns(Collection stageNames, List patterns) {
    (patterns ?: []).findAll { pattern ->
        !stageNames.any { stageName ->
            this.@pipeline.stagePatterns.stageMatchesPattern(stageName.toString(), pattern.toString())
        }
    }.each { pattern ->
        this.@pipeline.echo "WARNING: Maintenance pattern '${pattern}' matched no selected stage in this job."
    }
}

def escapeMaintenanceHtml(def value) {
    return value.toString()
        .replace('&', '&amp;')
        .replace('<', '&lt;')
        .replace('>', '&gt;')
        .replace("'", '&#39;')
        .replace('"', '&quot;')
}

def appendMaintenanceSummary(Map globalVars) {
    try {
        if (!this.@pipeline.ciState.maintenanceSkips) {
            return
        }

        def parents = globalVars[this.@pipeline.runParameters.ACTION_INFO]?.get('parents') ?: []
        if (parents.size() < 2) {
            this.@pipeline.echo "WARNING: No parent job found to append the maintenance summary."
            return
        }

        def parentJob = parents[-2]
        def buildLabel = "${escapeMaintenanceHtml(this.@pipeline.env.JOB_NAME)} #${escapeMaintenanceHtml(this.@pipeline.env.BUILD_NUMBER)}"
        def buildUrl = escapeMaintenanceHtml(this.@pipeline.env.BUILD_URL)
        def stages = this.@pipeline.ciState.maintenanceSkips.collect { stageName ->
            "<b>${escapeMaintenanceHtml(stageName)}</b>"
        }.unique().join('<br/>')
        def reason = escapeMaintenanceHtml(globalVars[this.@pipeline.runParameters.MAINTENANCE_ENTRIES].reason)
        def summary = "<span data-maintenance-warning='true'>" +
            "<b>Resource maintenance in <a href='${buildUrl}'>${buildLabel}</a>:</b> ${reason}<br/>" +
            "<b>Affected stages:</b><br/>${stages}</span><br/>"
        this.@pipeline.trtllm_utils.appendBuildDescription(
            this.@pipeline, parentJob['name'], parentJob['build_number'], summary)
        this.@pipeline.echo "Appended maintenance summary to parent build ${parentJob['name']} #${parentJob['build_number']}."
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        this.@pipeline.echo "WARNING: Failed to append maintenance summary: ${e.toString()}"
    }
}

def checkStageNameSet(stageNames, jobKeys, paramName) {
    this.@pipeline.echo "Validate stage names for the passed GitLab bot params [${paramName}]."
    def unmatchedNames = stageNames.findAll { pattern ->
        if (pattern.contains('*')) {
            // Wildcard pattern: check that it matches at least one stage
            !jobKeys.any { key -> this.@pipeline.stagePatterns.stageMatchesPattern(key, pattern) }
        } else {
            // Exact name: check that it exists
            !(pattern in jobKeys)
        }
    }
    if (unmatchedNames) {
        def sortedJobKeys = jobKeys.sort()
        throw new Exception("Cannot find the stage names [${unmatchedNames}] from the passed params [${paramName}]. Available stage names (${sortedJobKeys.size()} total):\n${sortedJobKeys.collect { "    ${it}" }.join('\n')}")
    }
}

def checkStageName(stageNames) {
    def invalidStageName = stageNames.findAll { !(it ==~ /[-\+\w\[\]]+/) }
    if (invalidStageName) {
        throw new Exception("Invalid stage name: [${invalidStageName}], we only support chars '-+_[]0-9a-zA-Z' .")
    }
}

def ensureStageResultNotUploaded(stageName) {
    if(!this.@pipeline.ciState.uploadResultStageNames.contains(stageName)) {
        this.@pipeline.ciState.uploadResultStageNames.add(stageName)
    } else {
        this.@pipeline.stage('Upload Test Result') {
            this.@pipeline.catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                this.@pipeline.error "Upload test results for ${stageName} failed because it has already been uploaded."
            }
        }
    }
}

def buildStageConfigs(stageName, platform, testlist, testCount, gpuCount, nodeCount, runWithSbatch=false, useClusterDurations=false) {
    def configs = [:]
    for (int k = 1; k <= testCount; k++) {
        def key = "${stageName}-${k}"
        configs[key] = [platform, testlist, k, testCount, gpuCount, nodeCount, runWithSbatch, useClusterDurations]
    }
    return configs
}

return this
