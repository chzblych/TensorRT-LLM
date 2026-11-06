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
import com.nvidia.bloom.SlurmConfig
import com.nvidia.bloom.SlurmCluster
import com.nvidia.bloom.Utils
import com.nvidia.bloom.FailureEvidenceCollector
import trtllm.FailureClassifier
import trtllm.ContextDeath
import trtllm.exceptions.InfraFailure

@Field def pipeline

def initialize(pipeline)
{
    this.pipeline = pipeline
    return this
}

def scpFromRemoteCmd(Map remote, String remotePath, String localPath) {
    String portOpt = remote.port ? "-P ${remote.port} " : ""
    if (remote.privateKeyPath) {
        return "scp -i ${remote.privateKeyPath} ${portOpt}-r -p ${pipeline.COMMON_SSH_OPTIONS} ${remote.user}@${remote.host}:${remotePath} ${localPath}"
    }
    return "sshpass -p '${remote.passwd}' scp ${portOpt}-r -p ${pipeline.COMMON_SSH_OPTIONS} ${remote.user}@${remote.host}:${remotePath} ${localPath}"
}

// Print the last `lines` lines of the remote Slurm job log file to the console when the Slurm job fails.
// If the log file does not exist, print a message to the console.
def echoRemoteLogTail(def pipeline, Map remote, String remotePath, int lines = 200) {
    pipeline.echo("===== Last ${lines} lines of ${remotePath} on ${remote.host} =====")
    try {
        def tailOut = Utils.exec(
            pipeline,
            script: Utils.sshUserCmd(remote,
                "\"bash -c 'if [ -f \\\"${remotePath}\\\" ]; then tail -n ${lines} -- \\\"${remotePath}\\\"; " +
                "else echo \\\"[log not found: ${remotePath}]\\\"; fi'\""),
            returnStdout: true,
            numRetries: 1,
        )?.trim()
        pipeline.echo(tailOut ?: "")
    } catch (InterruptedException e) {
        throw e
    } catch (Exception tailEx) {
        pipeline.echo("Ignorable warning: could not tail ${remotePath} on ${remote.host}: ${tailEx.message}")
    }
}

// Stream-scan the complete remote job log for the source-safe catalog subset,
// returning at most one occurrence of each signature. Matching policy and
// query precedence remain in the shared libraries.
def collectSlurmJobLogEvidence(def pipeline, Map remote, String remoteLogPath) {
    def queries = FailureClassifier.failureEvidenceQueries(InfraFailure.SLURM).findAll {
        pipeline.SLURM_JOB_LOG_EVIDENCE_QUERY_IDS.contains(it.id)
    }
    def queryTerms = queries.collectMany { it.anyOf ?: (it.allOf ?: []) }.unique()
    if (!queryTerms) return [source: "SLURM_JOB_LOG", collectionStatus: "NO_MATCH"]
    def grepPatterns = queryTerms.collect {
        "-e '" + it.replace("'", "'\"'\"'") + "'"
    }.join(" ")
    try {
        def scanLog = "if [ -f '${remoteLogPath}' ]; then " +
            "grep -aFi ${grepPatterns} -- '${remoteLogPath}' 2>/dev/null " +
            '''| awk 'index($0, ".py::") == 0' ''' +
            "| grep -aFio ${grepPatterns} " +
            '''| awk '{ key=tolower($0); if (seen[key]++ == 0) print }'; fi'''
        def candidateText = Utils.exec(
            pipeline,
            script: "timeout 300s " + Utils.sshUserCmd(remote, Utils.bashWrappedRemoteCmd(scanLog)),
            returnStdout: true,
            retryOnFail: false,
            noNVDFEvent: true,
            timeout: false,
        )?.trim()
        def match = FailureEvidenceCollector.matchQueries(candidateText, queries)
        return [
            source: "SLURM_JOB_LOG",
            collectionStatus: match.matchedQueryId == null ? "NO_MATCH" : "MATCHED",
            matchedQueryId: match.matchedQueryId,
            matchedTerms: match.matchedTerms,
            matchedMessage: match.matchedMessage,
        ]
    } catch (InterruptedException interruptedError) {
        throw interruptedError
    } catch (Exception collectionError) {
        pipeline.echo("Ignorable warning: could not collect failure evidence from ${remoteLogPath} on " +
            "${remote.host}: ${collectionError.message}")
        return [source: "SLURM_JOB_LOG", collectionStatus: "COLLECTION_ERROR"]
    }
}

// SLURM job IDs are digit-only. Not String.isNumber(): that is a
// BigDecimal-style parse, so values like "1.5" pass and later feed
// invalid IDs into scancel/sacct/scontrol.
def isValidSlurmJobId(def slurmJobID) {
    return slurmJobID && slurmJobID.toString() ==~ /\d+/
}

def cleanUpSlurmResources(def pipeline, SlurmCluster cluster, String clusterName, String jobUID){
    CloudManager.withSlurmFrontendFailover(pipeline, clusterName, cluster) { remote ->
        def jobWorkspace = "/home/svc_tensorrt/bloom/scripts/${jobUID}"
        def s3SpoolRoot = "/home/svc_tensorrt/bloom/scripts/.s3-spool-${jobUID}"

        Utils.exec(pipeline, script: "echo \"=== [Clean Up Slurm Resource] START: \$(date '+%Y-%m-%d %H:%M:%S') jobUID=${jobUID} ===\"")
        Utils.exec(pipeline, script: "echo Sleeping to allow Slurm job completion; sleep 30")

        def slurmJobID = Utils.exec(
            pipeline,
            // Try to grab the job id from ${jobWorkspace}/slurm_job_id.txt.
            // The slurm_run.sh will add the slurm job id in that file.
            script: Utils.sshUserCmd(
                remote,
                "\"cat ${jobWorkspace}/slurm_job_id.txt || true\""
            ),
            returnStdout: true
        ).trim()

        if (!isValidSlurmJobId(slurmJobID)) {
            pipeline.echo "Slurm job may not submit successfully. No job ID found."
        } else {
            Utils.exec(pipeline, script: "echo \"=== [Clean Up Slurm Resource] gpu_job_id=${slurmJobID} ===\"")

            Utils.exec(
                pipeline,
                script: Utils.sshUserCmd(
                    remote,
                    "\"scancel ${slurmJobID} || true; sacct -j ${slurmJobID} --format=JobID,JobName%100,Partition%15,Account%15,State,ExitCode,NodeList%30 || true; scontrol show job ${slurmJobID} || true\""
                )
            )
        }

        Utils.exec(pipeline, script: "echo Sleeping to allow Slurm job termination; sleep 30")

        def cleanupCommands = [
            // .sqsh is shared across jobs (named by image digest), so age-prune
            // instead of deleting per job; reused images keep a refreshed mtime.
            "find ${cluster.scratchPath}/users/svc_tensorrt/containers -maxdepth 1 -name 'container-*.sqsh' -mtime +10 -delete 2>/dev/null || true",
            "find ${cluster.scratchPath}/users/svc_tensorrt/containers -maxdepth 1 \\( -name 'container-*.tmp' -o -name 'container-*.lock' \\) -mtime +1 -delete 2>/dev/null || true",
            // Fat sqsh files are shared across jobs (keyed by package, image and builder script),
            // so age-prune rather than delete per job; reused ones keep a refreshed mtime.
            "find ${cluster.scratchPath}/users/svc_tensorrt/fat_sqsh -maxdepth 1 -name 'fat-*.sqsh' -mtime +7 -delete 2>/dev/null || true",
            "find ${cluster.scratchPath}/users/svc_tensorrt/fat_sqsh -maxdepth 1 -name 'fat-*.sqsh.*.tmp' -mtime +1 -delete 2>/dev/null || true",
            // Keep the job workspace (job-output.log etc.) for debugging; the
            // PruneSlurmOutputs job removes it after the retention period. Drop the
            // S3 spool and anything holding credentials right away: the launch
            // script embeds HF/GitHub tokens and OpenSearch credentials, and the
            // netrc files hold the Artifactory credentials.
            "rm -rf ${s3SpoolRoot} ${jobWorkspace}/s3_secret_key ${jobWorkspace}/enroot-config ${jobWorkspace}/${jobUID}-slurm_launch.sh ${jobWorkspace}/artifactory-netrc ${jobWorkspace}/fat-artifactory-netrc || true",
        ].join(" ; ")
        Utils.exec(
            pipeline,
            script: Utils.sshUserCmd(
                remote,
                Utils.bashWrappedRemoteCmd(cleanupCommands)
            )
        )

        Utils.exec(pipeline, script: "echo \"=== [Clean Up Slurm Resource] END: \$(date '+%Y-%m-%d %H:%M:%S') gpu_job_id=${slurmJobID} jobUID=${jobUID} cleaned up ===\"")
    }
}

// Methods to run Slurm job with Jenkins Agent
def cleanUpNodeResources(def pipeline, SlurmCluster cluster, String clusterName, String nodeName, String slurmJobID) {
    Utils.exec(pipeline, script: "echo Sleeping to allow docker stop; sleep 30")

    CloudManager.destroyNode(nodeName)

    Utils.exec(pipeline, script: "echo Sleeping to allow node destruction; sleep 30")

    CloudManager.withSlurmFrontendFailover(pipeline, clusterName, cluster) { remote ->
        // A missing/non-numeric ID means the job was never submitted (or its ID
        // never captured); running the dump anyway executes `scancel null` /
        // `scontrol show job null`, whose "Invalid job id specified" output then
        // shows up in failure analysis as a bogus error signature.
        if (!isValidSlurmJobId(slurmJobID)) {
            Utils.exec(pipeline, script: "echo \"No SLURM job ID captured for node ${nodeName}; skipping job cleanup dump\"")
        } else {
            Utils.exec(pipeline, script: "echo Slurm job ID: ${slurmJobID}")

            Utils.exec(
                pipeline,
                script: Utils.sshUserCmd(
                    remote,
                    "\"scancel ${slurmJobID} || true; sacct -j ${slurmJobID} --format=JobID,JobName%100,Partition%15,Account%15,State,ExitCode,NodeList%30 || true; scontrol show job ${slurmJobID} || true\""
                )
            )
        }

        Utils.exec(pipeline, script: "echo Sleeping to allow Slurm job termination; sleep 30")

        def entrypoint = SlurmConfig.containerRuntimeToEntrypoint[cluster.containerRuntime]
        def cleanupCommands = [
            "rm -rf /home/svc_tensorrt/bloom/scripts/agent-${nodeName}.jar /home/svc_tensorrt/bloom/scripts/${nodeName}-${entrypoint} || true",
            "rm -rf ${cluster.scratchPath}/users/svc_tensorrt/enroot-config-${nodeName} || true",
            // The fat sqsh builder's netrc, in case neither the builder nor Prepare Container
            // removed it.
            "rm -f /home/svc_tensorrt/bloom/scripts/${nodeName}/fat-artifactory-netrc || true",
            // .sqsh is shared across jobs (named by image digest), so age-prune
            // instead of deleting per job; reused images keep a refreshed mtime.
            "find ${cluster.scratchPath}/users/svc_tensorrt/containers -maxdepth 1 -name 'container-*.sqsh' -mtime +10 -delete 2>/dev/null || true",
            "find ${cluster.scratchPath}/users/svc_tensorrt/containers -maxdepth 1 \\( -name 'container-*.tmp' -o -name 'container-*.lock' \\) -mtime +1 -delete 2>/dev/null || true",
            "find ${cluster.scratchPath}/users/svc_tensorrt/fat_sqsh -maxdepth 1 -name 'fat-*.sqsh' -mtime +7 -delete 2>/dev/null || true",
            "find ${cluster.scratchPath}/users/svc_tensorrt/fat_sqsh -maxdepth 1 -name 'fat-*.sqsh.*.tmp' -mtime +1 -delete 2>/dev/null || true",
        ].join(" ; ")
        Utils.exec(
            pipeline,
            script: Utils.sshUserCmd(
                remote,
                Utils.bashWrappedRemoteCmd(cleanupCommands)
            )
        )

        Utils.exec(pipeline, script: "echo Slurm job ID: ${slurmJobID} cleaned up")
    }
}

// ---- Off-pod SLURM resource reconciliation --------------------------------
// A SLURM stage runs inside a K8s dispatcher pod that ssh-drives the job on the
// login node. If that pod dies mid-run (eviction, container error, agent
// offline), the in-pod cleanup can no longer reach the controller, so the SLURM
// job and any Jenkins agent node leak. State + reconciliation live in the shared
// `resourceLedger` (trtllm-jenkins-shared-lib). Each SLURM stage registers
// sibling entries -- "<stage>/dispatcher-pod" (the pod spec, from the pod
// wrapper) and one "<stage>/slurm/<job UID or node name>" per attempt (that
// attempt's SLURM job / Jenkins node, from the stage body) -- so freeing the
// job/node after a successful in-pod cleanup (markReclaimed) never disturbs the
// pod spec a *later* attempt's off-pod reconciliation needs, and an attempt
// whose cleanup failed stays registered beside the next one. The ledger stores
// only serializable primitives (it rejects anything else) so pipeline
// persistence is unaffected; the SlurmCluster is rebuilt from clusterName at
// reconcile time. The functions below are thin SLURM-specific adapters over
// that generic ledger.
//
// Shared-library contract used below (trtllm-jenkins-shared-lib), documented here
// for equivalence with the pre-shared inlined finalizer this replaced:
//   * ContextDeath.isContextDeath(e) -- true when the flattened cause + suppressed
//     chain contains a dispatcher-pod-death signal: "pod failed (reason:", "pod
//     just failed", "pod failed because container terminated", "unable to create
//     live filepath". Same pattern set the inlined isDispatcherPodFailure matched,
//     now also traversing suppressed exceptions' own cause chains.
//   * register(id, type, fields, ownerBuildTag) -- merges non-null fields into the
//     id's entry; stores serializable primitives only (rejects anything else); a
//     `fields` map may not overwrite the reserved id/type/ownerBuildTag keys.
//   * markReclaimed(id) -- drops the entry (idempotent).
//   * get(id) -- a deep-copied snapshot of the entry, or null.
//   * reconcile(pipeline, select, reclaim) -- for each live entry matching
//     select(entry), runs reclaim(pipeline, entry): deregisters entries whose
//     reclaim returns truthy; leaves live those returning falsy or throwing (a
//     throw is logged, never propagated). This is the equivalence backbone --
//     "reconcile off-pod, deregister on success, leave for the sweep on failure."

// Register a live SLURM resource. The pod wrapper passes a podSpec (recorded
// under "<stage>/dispatcher-pod"); the stage body passes the SLURM job / Jenkins
// node identity (recorded under "<stage>/slurm/<jobUID or nodeName>", one entry
// per attempt). Keyed separately so they are reclaimed independently. Absent
// fields are skipped by the ledger.
void registerSlurmResource(String stageName, Map fields) {
    if (!stageName) {
        return
    }
    if (fields.containsKey('podSpec')) {
        pipeline.resourceLedger.register(id: "${stageName}/dispatcher-pod", type: 'k8sDispatcherPod',
            fields: [podSpec: fields.podSpec, containerName: fields.containerName],
            ownerBuildTag: pipeline.env.BUILD_TAG)
    } else {
        pipeline.resourceLedger.register(id: "${stageName}/slurm/${fields.jobUID ?: fields.nodeName}",
            type: fields.usedSbatch ? 'slurmJob' : 'slurmNode',
            fields: [clusterName: fields.clusterName, nodeName: fields.nodeName,
                     jobUID: fields.jobUID, slurmJobId: fields.slurmJobId, usedSbatch: fields.usedSbatch],
            ownerBuildTag: pipeline.env.BUILD_TAG)
    }
}

// Called once an attempt's resources are actually torn down: drop that attempt's
// SLURM job/node entry (`attempt` is its jobUID or nodeName). The stage's
// dispatcher-pod entry is a separate id and is left intact (a later attempt's
// off-pod reconciliation still needs its pod spec).
void deregisterSlurmResource(String stageName, String attempt) {
    if (!stageName) {
        return
    }
    pipeline.resourceLedger.markReclaimed("${stageName}/slurm/${attempt}")
}

// Reclaim one orphaned SLURM entry off the (dead) dispatcher pod: launch a fresh
// short-lived pod and run the normal cleanup from there (scancel the job, clean
// the workspace, and -- agent path -- drop the leaked Jenkins node). Returns true
// when the entry is reconciled (so resourceLedger deregisters it) and false when
// it cannot be (left for the post-build sweep / manual cleanup). The pod spec and
// container come from the sibling "<stage>/dispatcher-pod" entry; an in-catch
// caller may pass podSpecOverride to use the failing attempt's spec directly.
// Best-effort: a launch/cleanup failure is logged and the entry left live; it
// never masks the stage's own failure. Used as the reclaim body for both the
// in-catch finalize and the post-build sweep.
def reconcileSlurmResource(pipeline, def entry, def podSpecOverride = null) {
    // The ledger is shared build-wide; only SLURM job/node entries are
    // reconcilable here. Never touch (or deregister) another subsystem's entry a
    // selector might pass in -- return false to leave it live and untouched.
    if (entry.type != 'slurmJob' && entry.type != 'slurmNode') {
        return false
    }
    // Pod died before any job/node was provisioned: nothing to reconcile.
    if (!entry.jobUID && !entry.nodeName) {
        return true
    }
    def podEntry = pipeline.resourceLedger.get(((entry.id ?: "") as String).replaceFirst(/\/slurm\/[^\/]+$/, "/dispatcher-pod"))
    def podSpec = podSpecOverride ?: podEntry?.podSpec
    def containerName = podEntry?.containerName ?: "trt-llm"
    def cluster = entry.clusterName ? SlurmConfig.clusterConfig[entry.clusterName] : null
    if (!podSpec || !cluster) {
        pipeline.echo "[SLURM-FINALIZER] ${entry.id}: cannot reconcile off-pod (missing pod spec or unknown cluster " +
             "'${entry.clusterName}'); SLURM job=${entry.slurmJobId ?: entry.jobUID ?: 'unknown'} " +
             "node=${entry.nodeName ?: 'n/a'} may need manual cleanup."
        return false
    }
    try {
        pipeline.echo "[SLURM-FINALIZER] ${entry.id}: reconciling orphaned SLURM resources off-pod " +
             "(job=${entry.slurmJobId ?: entry.jobUID}, node=${entry.nodeName ?: 'n/a'})."
        pipeline.trtllm_utils.launchKubernetesPod(pipeline, podSpec, containerName, {
            if (entry.usedSbatch) {
                cleanUpSlurmResources(pipeline, cluster, entry.clusterName, entry.jobUID)
            } else {
                cleanUpNodeResources(pipeline, cluster, entry.clusterName, entry.nodeName, entry.slurmJobId)
            }
        })
        pipeline.echo "[SLURM-FINALIZER] ${entry.id}: off-pod reconciliation complete."
        return true
    } catch (Exception e) {
        pipeline.echo "[SLURM-FINALIZER] ${entry.id}: off-pod reconciliation failed (${e.toString()}); leaving entry for post-build sweep."
        return false
    }
}

// Reconcile this stage's orphaned SLURM jobs/nodes off-pod, every attempt's (in-catch,
// on a detected dispatcher-pod death). podSpecOverride is the failing attempt's pod
// spec so reconciliation never depends on the sibling entry surviving.
def finalizeOrphanedSlurmResource(pipeline, String stageName, def podSpecOverride = null) {
    if (!stageName) {
        return
    }
    pipeline.resourceLedger.reconcile(pipeline,
        { (it.id as String).startsWith("${stageName}/slurm/") },
        { p, entry -> reconcileSlurmResource(p, entry, podSpecOverride) })
}

// Post-build backstop: reconcile any SLURM job/node still registered at the end of
// the build (a dispatcher-pod death whose in-catch finalize also failed, or a
// failure mode the catch never saw). Runs off-pod from a fresh cleanup pod; inert
// dispatcher-pod-only entries are skipped by the selector.
def sweepOrphanedSlurmResources(pipeline) {
    // Scope to this subsystem's SLURM job/node entries: the ledger is shared
    // build-wide, so filter by type as well as a live job/node handle rather than
    // passing any entry that merely happens to carry a jobUID/nodeName field.
    pipeline.resourceLedger.reconcile(pipeline,
        { (it.type == 'slurmJob' || it.type == 'slurmNode') && (it.jobUID || it.nodeName) },
        { p, entry -> reconcileSlurmResource(p, entry) })
}

// Authoritative timeout signal: ask the SLURM controller (via sacct on the
// cluster login node) for a job's terminal state. Returns the uppercased
// primary state token -- e.g. "TIMEOUT", "COMPLETED", "FAILED", "NODE_FAIL",
// "OUT_OF_MEMORY", "CANCELLED" -- or null when it can't be determined.
//
// Best-effort: any SSH/sacct error returns null so callers fall back to the
// duration heuristic rather than failing the stage.
def querySlurmJobState(def pipeline, SlurmCluster cluster, String clusterName, String slurmJobID) {
    if (!isValidSlurmJobId(slurmJobID)) {
        return null
    }
    String state = null
    try {
        CloudManager.withSlurmFrontendFailover(pipeline, clusterName, cluster) { remote ->
            // -X: allocation row only (skip .batch/.extern steps). -Pn:
            // parsable, no header. First line's first token is the job state;
            // SLURM renders cancellations as "CANCELLED by <uid>", so we keep
            // only the leading token.
            def out = Utils.exec(
                pipeline,
                script: Utils.sshUserCmd(remote, "\"sacct -j ${slurmJobID} --format=State -Pn -X || true\""),
                returnStdout: true,
            )?.trim()
            if (out) {
                state = out.readLines()[0]?.trim()?.tokenize(' ')?.getAt(0)?.toUpperCase(java.util.Locale.ROOT)
            }
        }
    } catch (Exception e) {
        pipeline.echo("[INFRA-RETRY] Could not query SLURM job ${slurmJobID} state via sacct: ${e.message}")
    }
    return state
}

boolean isNonTerminalSlurmState(String state) {
    return state != null && pipeline.SLURM_NON_TERMINAL_STATES.contains(state.toUpperCase(java.util.Locale.ROOT))
}

def readSlurmWorkspaceFile(def pipeline, Map remote, String path, String stageName, int numRetries=1)
{
    try {
        def value = Utils.exec(
            pipeline,
            script: Utils.sshUserCmd(
                remote,
                Utils.bashWrappedRemoteCmd("cat ${path} 2>/dev/null || true")
            ),
            returnStdout: true,
            numRetries: numRetries
        ).trim()
        return value.readLines().collect { it.trim() }.find { it } ?: ""
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        // A dead frontend must propagate so the enclosing withSlurmFrontendFailover
        // fails over to another remote; swallowing it as "" would strand the stage on
        // the unreachable frontend. Any other read failure (missing file, transient)
        // is non-fatal -- the metadata is best-effort, so return "" and carry on.
        if (CloudManager.isSlurmFrontendConnectionFailure(pipeline, e)) {
            throw e
        }
        pipeline.echo "[INFRA-RETRY] ${stageName}: unable to read SLURM metadata file ${path}: ${e.toString()}"
        return ""
    }
}

// State flows through the mutated placementContext map (read by the retry
// loop); this records into it and returns nothing.
def recordSlurmPlacementContext(Map placementContext, String slurmJobID, def nodeList, String stageName)
{
    if (placementContext == null) {
        return
    }

    if (isValidSlurmJobId(slurmJobID)) {
        placementContext.slurmJobId = slurmJobID
    }

    def normalized = pipeline.trtllm_utils.normalizeSlurmNodeLists(nodeList)
    if (!normalized.isEmpty()) {
        placementContext.lastSlurmNodeList = normalized.join(' ')
        def jobLabel = slurmJobID ? "job ${slurmJobID}" : "job"
        pipeline.echo "[INFRA-RETRY] ${stageName}: SLURM ${jobLabel} ran on node list(s): ${placementContext.lastSlurmNodeList}"
    }
}

def captureSlurmWorkspaceMetadata(def pipeline, Map remote, String jobWorkspace, Map placementContext, String stageName)
{
    def metadata = [slurmJobId: null, nodeList: null]
    if (!jobWorkspace) {
        return metadata
    }

    metadata.slurmJobId = readSlurmWorkspaceFile(pipeline, remote, "${jobWorkspace}/slurm_job_id.txt", stageName)
    if (metadata.slurmJobId && !isValidSlurmJobId(metadata.slurmJobId)) {
        // A garbage value (e.g. an error message that landed in the file) must
        // not be recorded or later fed into sacct/scontrol diagnostics.
        pipeline.echo "[INFRA-RETRY] ${stageName}: ignoring non-numeric SLURM job ID '${metadata.slurmJobId}' from ${jobWorkspace}/slurm_job_id.txt"
        metadata.slurmJobId = null
    }
    metadata.nodeList = readSlurmWorkspaceFile(pipeline, remote, "${jobWorkspace}/slurm_node_list.txt", stageName)
    if (placementContext != null && metadata.slurmJobId) {
        placementContext.slurmJobId = metadata.slurmJobId
    }
    def normalized = pipeline.trtllm_utils.normalizeSlurmNodeLists(metadata.nodeList)
    if (placementContext != null && !normalized.isEmpty()) {
        placementContext.lastSlurmNodeList = normalized.join(' ')
        metadata.nodeList = placementContext.lastSlurmNodeList
    }
    return metadata
}

def captureSlurmJobNodeList(def pipeline, SlurmCluster cluster, String clusterName, String slurmJobID, Map placementContext, String stageName, String jobWorkspace=null)
{
    if (placementContext == null) {
        return
    }

    def capturedJobID = slurmJobID
    def nodeList = null
    try {
        CloudManager.withSlurmFrontendFailover(pipeline, clusterName, cluster) { remote ->
            def metadata = captureSlurmWorkspaceMetadata(pipeline, remote, jobWorkspace, placementContext, stageName)
            capturedJobID = capturedJobID ?: metadata.slurmJobId
            nodeList = metadata.nodeList
            if (!capturedJobID && jobWorkspace) {
                capturedJobID = readSlurmWorkspaceFile(pipeline, remote, "${jobWorkspace}/slurm_job_id.txt", stageName, 3)
                recordSlurmPlacementContext(placementContext, capturedJobID, null, stageName)
            }
            if (!nodeList && jobWorkspace) {
                nodeList = readSlurmWorkspaceFile(pipeline, remote, "${jobWorkspace}/slurm_node_list.txt", stageName, 3)
            }
            // Digit-only revalidation: capturedJobID may come from the caller or
            // a raw workspace-file read, and an invalid value here would produce
            // the same "Invalid job id specified" noise in sacct/scontrol.
            if (!isValidSlurmJobId(capturedJobID)) {
                return
            }

            if (nodeList) {
                return
            }

            nodeList = Utils.exec(
                pipeline,
                script: Utils.sshUserCmd(
                    remote,
                    "\"bash -c 'sacct -j ${capturedJobID} --format=NodeList -Pn --allocations 2>/dev/null | head -1 || true'\""
                ),
                returnStdout: true,
                numRetries: 1
            ).trim()
            if (!nodeList) {
                nodeList = Utils.exec(
                    pipeline,
                    script: Utils.sshUserCmd(
                        remote,
                        "\"bash -c 'scontrol show job ${capturedJobID} 2>/dev/null | tr \" \" \"\\n\" | sed -n \"s/^NodeList=//p\" | head -1 || true'\""
                    ),
                    returnStdout: true,
                    numRetries: 1
                ).trim()
            }
        }
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        pipeline.echo "[INFRA-RETRY] ${stageName}: unable to capture SLURM node list for job ${capturedJobID}: ${e.toString()}"
    }

    recordSlurmPlacementContext(placementContext, capturedJobID, nodeList, stageName)
}

return this
