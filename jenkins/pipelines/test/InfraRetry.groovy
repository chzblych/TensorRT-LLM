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
import com.nvidia.bloom.Utils
import com.nvidia.bloom.FailureEvidenceCollector
import trtllm.FailureClassifier
import trtllm.ContextDeath
import trtllm.exceptions.InfraFailure
import trtllm.exceptions.PipelineInterruption
import trtllm.exceptions.TrtllmCiException
import trtllm.exceptions.UserFailure

@Field def pipeline

def initialize(pipeline)
{
    this.pipeline = pipeline
    return this
}

// True when an exception indicates the K8s dispatcher pod this SLURM stage runs
// inside died mid-run -- kubelet eviction, container termination, or the JNLP
// agent otherwise going offline. Retrying inside such a pod is futile (every
// step runs on the dead agent and fails immediately) and its in-pod cleanup can
// no longer reach the SLURM controller, so callers stop retrying in place and
// reconcile the orphaned SLURM job / Jenkins node off-pod. Delegates to the shared
// ContextDeath.isContextDeath, which matches the same pattern set the inlined
// version used (see the contract block above) across the flattened cause chain --
// so the signal is still recognized when wrapped by the cleanup's AbortException
// (e.g. "Error during clean up SLURM resources: ... marked offline: Pod failed
// (Reason: Evicted ...)") -- and additionally traverses suppressed causes.
boolean isDispatcherPodFailure(Throwable e) {
    return ContextDeath.isContextDeath(e)
}

def runLLMTestlistOnSlurm(pipeline, platform, testList, config=pipeline.VANILLA_CONFIG, stageName="Undefined", splitId=1, splits=1, gpuCount=1, nodeCount=1, runWithSbatch=false, skipInstallWheel=false, String outerAttemptTag="", boolean useClusterDurations=false, Integer infraRetryMax=null)
{
  pipeline.echo "Run Slurm job with native sbatch: $runWithSbatch"

  // Per-stage override of the SLURM infra-retry budget (from opts.infraRetryMax,
  // threaded via the dispatcher's retryContext). Lets resource-scarce pools cap
  // or disable stage-level retries (0 = no retry). Null falls back to the global.
  int slurmInfraRetryMax = resolveInfraRetryMax(InfraFailure.SLURM, infraRetryMax)

  def attempt = 0
  // Avoided SLURM nodes keyed by cluster. The platform can resolve to a
  // different cluster on each attempt (auto: platforms pick one at random), and
  // a node name from one cluster is unknown to another's controller -- passing
  // it to --exclude makes sbatch hard-fail with "Invalid node name specified".
  // Keying by cluster lets each attempt exclude only nodes that belong to the
  // cluster it actually submits to.
  def avoidedSlurmNodeListsByCluster = [:]

  while (true) {
    attempt++
    Map attemptPlacementContext = [
      excludedSlurmNodeListsByCluster: avoidedSlurmNodeListsByCluster.collectEntries { c, ns -> [(c): ns.collect()] }
    ]
    // Describes this attempt so the stage body can suppress its junit when a
    // retryable infra failure means another attempt will follow (mirrors the K8s
    // path's retryContext). scope=SLURM so classification/budget match this loop.
    // Shared by result suppression and the retry decision for this attempt only.
    Map slurmRetryContext = [
      scope: InfraFailure.SLURM,
      stageName: stageName,
      attempt: attempt,
      backoffMs: 60L * 1000L,
      infraRetryMax: slurmInfraRetryMax,
    ]
    try {
      if (attempt > 1) {
        pipeline.echo "[INFRA-RETRY] ${stageName}: Starting attempt ${attempt} of ${slurmInfraRetryMax + 1}"
        if (!avoidedSlurmNodeListsByCluster.isEmpty()) {
          pipeline.echo "[INFRA-RETRY] ${stageName}: avoiding prior SLURM node list(s): " +
               avoidedSlurmNodeListsByCluster.collect { c, ns -> "${c}: ${ns.join(' ')}" }.join('; ')
        }
      }

      // Each attempt uploads its own test-result artifact under a unique name so
      // the attempt-1 tar (already in Artifactory from its finally block) is not
      // clobbered and the ensureStageResultNotUploaded guard does not trip on
      // the retry. First attempt keeps the canonical unsuffixed name so existing
      // downstream consumers (dashboards, the JIRA bot, etc.) are unaffected.
      //
      // outerAttemptTag is the K8s outer dispatcher pod's tag ("" for outer
      // attempt 1, "-pod-${N}" for outer attempt N>=2). Prefixing the inner
      // postTag with it ensures the new dispatcher pod's inner attempt 1
      // upload doesn't collide with the dead pod's already-recorded upload
      // in GlobalState.uploadResultStageNames.
      def innerSuffix = (attempt == 1) ? "" : "-attempt-${attempt}"
      def postTag = "${outerAttemptTag}${innerSuffix}"

      if (nodeCount > 1 || runWithSbatch) {
        pipeline.slurmSbatch.runLLMTestlistWithSbatch(pipeline, platform, testList, config, stageName, splitId, splits, gpuCount, nodeCount, skipInstallWheel, postTag, useClusterDurations, attemptPlacementContext, slurmRetryContext)
      } else {
        pipeline.slurmAgent.runLLMTestlistWithAgent(pipeline, platform, testList, config, stageName, splitId, splits, gpuCount, skipInstallWheel, postTag, useClusterDurations, attemptPlacementContext, slurmRetryContext)
      }

      // Job succeeded
      if (attempt > 1) {
        pipeline.echo "[INFRA-RETRY] ${stageName}: Succeeded on attempt ${attempt}"
      }
      return

    } catch (InterruptedException e) {
      // User abort / pipeline timeout -- never retry
      throw e
    } catch (Exception e) {
      // If the K8s dispatcher pod this stage runs inside died mid-run, every
      // retry attempt would execute on that same dead pod and fail immediately,
      // and the in-pod cleanup can no longer reach the SLURM controller. Stop
      // retrying in place and propagate so the pod-level wrapper reconciles the
      // orphaned SLURM job / Jenkins node off-pod (fail closed).
      if (isDispatcherPodFailure(e)) {
        pipeline.echo "[INFRA-RETRY] ${stageName}: dispatcher pod died mid-run; not retrying on the dead pod (${e.toString()}). Failing closed for off-pod reconciliation."
        throw e
      }
      // classifyFailure() handles FlowInterruptedException + exit-code-143 +
      // typed throws + originating-step evidence and cause-chain fallback, returning one of
      // PipelineInterruption / InfraFailure / UserFailure. Scope=SLURM
      // ensures we only match catalog rows tagged SLURM or BOTH.
      def c = classifyFailure(pipeline, e, InfraFailure.SLURM, slurmRetryContext)
      if (c instanceof PipelineInterruption) throw e
      if (!(c instanceof InfraFailure)) {
        // UserFailure -> don't retry, but leave a trace: a failure whose
        // exception matches no catalog pattern lands here and would
        // otherwise decline the retry with no log output at all.
        pipeline.echo "[INFRA-RETRY] ${stageName}: SLURM attempt failed with no infra pattern matched (classified as user failure); not retrying. Exception: ${e.toString()}"
        throw e
      }

      rememberAvoidedSlurmNodeLists(avoidedSlurmNodeListsByCluster, attemptPlacementContext.lastSlurmClusterName, attemptPlacementContext.lastSlurmNodeList, stageName)

      def effectiveMax = (c.severity == InfraFailure.PERSISTENT) ? Math.min(1, slurmInfraRetryMax) : slurmInfraRetryMax

      if (attempt > effectiveMax) {
        pipeline.echo "[INFRA-RETRY] ${stageName}: Infrastructure failure (${c.detectedPattern}) " +
             "but max retries (${effectiveMax}) exhausted after ${attempt} attempts. Failing."
        throw c
      }
      if (!hasBudgetForInfraRetry(pipeline, stageName, InfraFailure.SLURM, c, attempt, effectiveMax, 60L * 1000L, true)) {
        pipeline.echo "[INFRA-RETRY] ${stageName}: Infrastructure failure (${c.detectedPattern}) is retryable, " +
             "but remaining CI timeout budget is too small for another SLURM attempt. Failing without retry."
        throw c
      }

      pipeline.echo "[INFRA-RETRY] ${stageName}: Infrastructure failure detected on attempt ${attempt}: " +
           "${c.detectedPattern}"
      pipeline.echo "[INFRA-RETRY] ${stageName}: Exception: ${e.toString()}"
      pipeline.echo "[INFRA-RETRY] ${stageName}: Will retry (attempt ${attempt + 1} of ${effectiveMax + 1}) after 60s cooldown."

      pipeline.sleep(60)
    }
  }
}

def recordRenderedStageAttemptEstimate(pipeline, String llmSrc, String testListPath, String stageName, def renderedTestCount, String clusterName=null)
{
    def estimate = pipeline.trtllm_utils.estimateRenderedStageAttemptMillis(pipeline, llmSrc, testListPath, stageName, renderedTestCount, clusterName)
    if (estimate.error) {
        pipeline.echo "[CI-BUDGET] ${stageName}: failed to read .test_durations; using count-based estimate. Error: ${estimate.error}"
    }

    pipeline.ciState.stageAttemptEstimateMs[stageName] = estimate.estimatedMs
    pipeline.ciState.stageAttemptEstimateDetails[stageName] = [
        renderedCount: estimate.renderedCount,
        knownCount: estimate.knownCount,
        unknownCount: estimate.unknownCount,
    ]
    pipeline.echo "[CI-BUDGET] ${stageName}: recorded test runtime estimate " +
         "${pipeline.trtllm_utils.formatCiBudgetMillis(estimate.estimatedMs)} " +
         "(rendered=${estimate.renderedCount}, known=${estimate.knownCount}, unknown=${estimate.unknownCount})"
}

long estimateStageRetryRuntimeMs(String stageName, String scope)
{
    Long testEstimateValue = pipeline.trtllm_utils.parseCiBudgetLong(pipeline.ciState.stageAttemptEstimateMs[stageName])
    long testEstimateMs = testEstimateValue != null ? testEstimateValue : 0L
    if (testEstimateMs > 0L) {
        long overheadMs = scope == InfraFailure.SLURM ? 45L * 60L * 1000L : 30L * 60L * 1000L
        return testEstimateMs + overheadMs
    }
    return scope == InfraFailure.SLURM ? 4L * 60L * 60L * 1000L : 2L * 60L * 60L * 1000L
}

long retrySafetyMarginMs(String scope)
{
    return scope == InfraFailure.SLURM ? 20L * 60L * 1000L : 15L * 60L * 1000L
}

// Resolve a per-stage infra-retry override (opts.infraRetryMax) against its
// scope's global budget. The override may only CAP or DISABLE retries, never
// exceed the global (which bounds CI time / worst-case attempts), so it is
// clamped to [0, scopeDefault]; e.g. with the default of 1, infraRetryMax=2
// still yields 1, and infraRetryMax=0 disables. Null falls back to the default.
int resolveInfraRetryMax(String scope, Integer override)
{
    int scopeDefault = (scope == InfraFailure.SLURM) ? pipeline.SLURM_INFRA_RETRY_MAX : pipeline.K8S_INFRA_RETRY_MAX
    if (override == null) {
        return scopeDefault
    }
    return Math.max(0, Math.min(override, scopeDefault))
}

int retryMaxForFailure(String scope, InfraFailure failure, Integer infraRetryMax=null)
{
    // Per-stage override caps the scope default (see resolveInfraRetryMax); a
    // PERSISTENT failure is still capped to at most one retry, so infraRetryMax=0
    // disables retries for every severity.
    int base = resolveInfraRetryMax(scope, infraRetryMax)
    return (failure.severity == InfraFailure.PERSISTENT) ? Math.min(1, base) : base
}

boolean hasBudgetForInfraRetry(def pipeline, String stageName, String scope, InfraFailure failure, int attempt, int effectiveMax, long backoffMs, boolean logDecision)
{
    if (attempt > effectiveMax) {
        return false
    }
    long estimateMs = estimateStageRetryRuntimeMs(stageName, scope)
    long safetyMs = retrySafetyMarginMs(scope)
    return pipeline.trtllm_utils.canSpendCiBudget(pipeline, [
        globalVars: pipeline.globalVars,
        label: "infra-retry:${scope}:${stageName}:attempt-${attempt + 1}",
        estimateMs: estimateMs,
        backoffMs: backoffMs,
        safetyMs: safetyMs,
        logDecision: logDecision,
    ])
}

def classifyFailure(def pipeline, Throwable error, String scope, Map retryContext=null)
{
    if (error instanceof TrtllmCiException) return FailureClassifier.classify(error, scope)

    // Reuse evidence only when it was collected for this exact error and classification scope.
    boolean cachedForFailure = retryContext?.failureEvidence != null &&
        retryContext.failureEvidenceError == error && retryContext.failureEvidenceScope == scope
    Map evidence = cachedForFailure ? retryContext.failureEvidence : null
    if (evidence == null) {
        evidence = FailureEvidenceCollector.collectOriginatingStepEvidence(pipeline, error, FailureClassifier.failureEvidenceQueries(scope))
        if (evidence.collectionStatus == "MATCHED") {
            pipeline.echo "[FAILURE-EVIDENCE] ${scope}: originating step matched ${evidence.matchedQueryId} [${evidence.matchedTerms.join(', ')}]"
        }
        if (retryContext != null) {
            retryContext.failureEvidence = evidence
            retryContext.failureEvidenceError = error
            retryContext.failureEvidenceScope = scope
        }
    }
    return FailureClassifier.classify(error, scope, evidence)
}

def preservePrimaryFailure(Throwable primaryError, Closure actions)
{
    try {
        actions()
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        // Post-processing failures must not replace the failure that triggered them.
        if (primaryError == null || FailureClassifier.classify(e, InfraFailure.BOTH) instanceof PipelineInterruption) throw e
        pipeline.echo "[INFRA-RETRY] Secondary failure while handling ${primaryError}:\n${Utils.getStackTrace(e)}"
    }
}

boolean retryContextAllowsRetry(def pipeline, Map retryContext, Throwable error, boolean logDecision=false)
{
    if (retryContext == null || error == null) {
        return false
    }
    String scope = retryContext.scope ?: InfraFailure.K8S
    def classified = classifyFailure(pipeline, error, scope, retryContext)
    if (!(classified instanceof InfraFailure)) {
        return false
    }
    Long parsedAttempt = pipeline.trtllm_utils.parseCiBudgetLong(retryContext.attempt)
    int attempt = parsedAttempt != null ? parsedAttempt as int : 1
    int effectiveMax = retryMaxForFailure(scope, classified, retryContext.infraRetryMax as Integer)
    Long parsedBackoffMs = pipeline.trtllm_utils.parseCiBudgetLong(retryContext.backoffMs)
    long backoffMs = parsedBackoffMs != null ? parsedBackoffMs : 60L * 1000L
    return hasBudgetForInfraRetry(pipeline, retryContext.stageName ?: "Unknown", scope, classified, attempt, effectiveMax, backoffMs, logDecision)
}

def rememberAvoidedKubernetesHostNodes(List avoidedNodes, def nodes, String stageName)
{
    def hostNodes = pipeline.trtllm_utils.normalizeKubernetesHostNames(nodes)
    hostNodes.each { nodeName ->
        if (!avoidedNodes.contains(nodeName)) {
            avoidedNodes << nodeName
        }
    }
    if (!hostNodes.isEmpty()) {
        pipeline.echo "[INFRA-RETRY] ${stageName}: recorded Kubernetes host node(s) for retry avoidance: ${hostNodes.join(', ')}"
    }
}

def rememberAvoidedSlurmNodeLists(Map avoidedNodeListsByCluster, String clusterName, def nodes, String stageName)
{
    // Without the cluster the node ran on we can't safely exclude it (a node name
    // is only valid on its own cluster's controller), so skip -- degrading to no
    // avoidance rather than risking a cross-cluster "Invalid node name" submit.
    if (!clusterName) {
        return
    }
    def nodeLists = pipeline.trtllm_utils.normalizeSlurmNodeLists(nodes)
    if (nodeLists.isEmpty()) {
        return
    }
    def clusterNodeLists = avoidedNodeListsByCluster.get(clusterName)
    if (clusterNodeLists == null) {
        clusterNodeLists = []
        avoidedNodeListsByCluster.put(clusterName, clusterNodeLists)
    }
    nodeLists.each { nodeList ->
        if (!clusterNodeLists.contains(nodeList)) {
            clusterNodeLists << nodeList
        }
    }
    pipeline.echo "[INFRA-RETRY] ${stageName}: recorded SLURM node list(s) for retry avoidance on ${clusterName}: ${nodeLists.join(', ')}"
}

// Deferrable-infra predicate for trtllm_utils.runBranchesWithInfraDefer. Every
// branch is checked under K8S -- where the motivating pod-scheduling abort
// (KubernetesClientTimeoutException) matches, and which also covers K8s-pod
// aborts of a SLURM dispatcher pod. A SLURM dispatcher stage (per stageScopes,
// built from opts.slurmDispatcher) is ADDITIONALLY checked under SLURM so a
// SLURM-scoped abort (SSH outage to the head node, slurm_track ssh exit 255,
// monitor loss while the job is still active) defers too instead of cascading
// via failFast. The inner SLURM retry (runLLMTestlistOnSlurm) is already
// exhausted by the time the branch body returns, so this stays post-retry,
// mirroring the K8s path. Stages absent from stageScopes are K8S-only.
def infraDeferPredicate(def pipeline, Map stageScopes) {
    return { e, stageName ->
        boolean slurmScoped = (stageScopes[stageName] == InfraFailure.SLURM)
        classifyFailure(pipeline, e, InfraFailure.K8S) instanceof InfraFailure ||
            (slurmScoped && classifyFailure(pipeline, e, InfraFailure.SLURM) instanceof InfraFailure)
    }
}

return this
