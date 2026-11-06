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

import groovy.transform.Field
import trtllm.exceptions.InfraFailure

@Field def pipeline

def initialize(pipeline)
{
    this.pipeline = pipeline
    return this
}

def prepareX86TestJobs(pipeline)
{
    pipeline.x86TestConfigs = [
        "CPU-Generic-x86-1": ["cpu", "l0_cpu", 1, 2],
        "CPU-Generic-x86-2": ["cpu", "l0_cpu", 2, 2],
        "DGX_H100-4_GPUs-CPP-1": ["dgx-h100-x4", "l0_dgx_h100", 1, 1, 4],
        "A10-PyTorch-1": ["a10", "l0_a10", 1, 3],
        "A10-PyTorch-2": ["a10", "l0_a10", 2, 3],
        "A10-PyTorch-3": ["a10", "l0_a10", 3, 3],
        "A30-PyTorch-1": ["a30", "l0_a30", 1, 2],
        "A30-PyTorch-2": ["a30", "l0_a30", 2, 2],
        "A30-CPP-1": ["a30", "l0_a30", 1, 1],
        "A100X-PyTorch-1": ["a100x", "l0_a100", 1, 1],
        "L40S-PyTorch-1": ["l40s", "l0_l40s", 1, 2],
        "L40S-PyTorch-2": ["l40s", "l0_l40s", 2, 2],
        "H100_PCIe-PyTorch-Ray-1": ["h100-cr", "l0_h100", 1, 1],
        "H100_PCIe-CPP-1": ["h100-cr", "l0_h100", 1, 1],
        // platform, test DB, split, splits, GPU count, ModelExpress sidecars
        // In this Kubernetes map only, an optional 6th element `true` attaches the Redis +
        // ModelExpress server sidecars and the CI ModelExpress env to the pod
        // (`createKubernetesPodConfig(..., values[5])`). The Slurm maps read slot 6 as the
        // node count and slot 7 as `runWithSbatch` instead.
        "DGX_H100-2_GPUs-PyTorch-ModelExpress-1": ["dgx-h100-x4", "l0_model_express", 1, 1, 2, true],
        "DGX_H100-4_GPUs-PyTorch-ModelExpress-OnDemand-1": ["dgx-h100-x4", "l0_model_express", 1, 1, 4, true],
        // Post-merge only: the `stage: post_merge` rows of l0_model_express, i.e. the TP=1 smoke
        // rows of the non-representative families and the accuracy canaries.
        "DGX_H100-2_GPUs-PyTorch-ModelExpress-Post-Merge-1": ["dgx-h100-x4", "l0_model_express", 1, 1, 2, true],
        "RTX5090-PyTorch-1": ["rtx-5090", "l0_gb202", 1, 1],
        "RTX5080-PyTorch-1": ["rtx-5080", "l0_gb203", 1, 2],
        "RTX5080-PyTorch-2": ["rtx-5080", "l0_gb203", 2, 2],
        // Currently post-merge test stages only run tests with "stage: post_merge" mako
        // in the test-db. This behavior may change in the future.
        "A10-PyTorch-Post-Merge-1": ["a10", "l0_a10", 1, 4],
        "A10-PyTorch-Post-Merge-2": ["a10", "l0_a10", 2, 4],
        "A10-PyTorch-Post-Merge-3": ["a10", "l0_a10", 3, 4],
        "A10-PyTorch-Post-Merge-4": ["a10", "l0_a10", 4, 4],
        "A10-FMHA-Post-Merge-1": ["a10", "l0_a10", 1, 1],
        "A30-CPP-Post-Merge-1": ["a30", "l0_a30", 1, 2],
        "A30-CPP-Post-Merge-2": ["a30", "l0_a30", 2, 2],
        // "A30-Triton-Post-Merge-1": ["a30", "l0_a30", 1, 2],
        // "A30-Triton-Post-Merge-2": ["a30", "l0_a30", 2, 2],
        "A100X-PyTorch-Post-Merge-1": ["a100x", "l0_a100", 1, 1],
        "L40S-PyTorch-Post-Merge-1": ["l40s", "l0_l40s", 1, 1],
        "L40S-FMHA-Post-Merge-1": ["l40s", "l0_l40s", 1, 1],
        "H100_PCIe-FMHA-Post-Merge-1": ["h100-cr", "l0_h100", 1, 1],
        "DGX_H200-8_GPUs-PyTorch-Post-Merge-1": ["dgx-h200-x8", "l0_dgx_h200", 1, 1, 8],
        "DGX_H200-4_GPUs-PyTorch-Post-Merge-1": ["dgx-h200-x4", "l0_dgx_h200", 1, 1, 4],
        "DGX_H200-8_GPUs-PyTorch-PerfSanity-Post-Merge-1": ["dgx-h200-x8", "l0_dgx_h200_perf_sanity", 1, 1, 8],
        // Disable RTXPro6000 stages due to nodes will be offline temporarily.
        // [TODO] Split tests between RTXPro6000 and RTXPro6000D and move reasonable mount of tests to pre-merge.
        // "RTXPro6000-PyTorch-Post-Merge-1": ["rtx-pro-6000", "l0_rtx_pro_6000", 1, 1],
        // "RTXPro6000-4_GPUs-PyTorch-Post-Merge-1": ["rtx-pro-6000-x4", "l0_rtx_pro_6000", 1, 2, 4],
        // "RTXPro6000-4_GPUs-PyTorch-Post-Merge-2": ["rtx-pro-6000-x4", "l0_rtx_pro_6000", 2, 2, 4],
        "RTXPro6000D-PyTorch-1": ["rtx-pro-6000d", "l0_rtx_pro_6000", 1, 1],
        "RTXPro6000D-PyTorch-Post-Merge-1": ["rtx-pro-6000d", "l0_rtx_pro_6000", 1, 1],
        // Disable RTXPro6000D-4_GPUs-PyTorch-Post-Merge-1 and RTXPro6000D-4_GPUs-PyTorch-Post-Merge-2 due to some nodes are offline temporarily.
        // "RTXPro6000D-4_GPUs-PyTorch-Post-Merge-1": ["rtx-pro-6000d-x4", "l0_rtx_pro_6000", 1, 2, 4],
        // "RTXPro6000D-4_GPUs-PyTorch-Post-Merge-2": ["rtx-pro-6000d-x4", "l0_rtx_pro_6000", 2, 2, 4],
    ]

    pipeline.x86TestConfigs = pipeline.testSelection.cbtsResizeSplits(pipeline.x86TestConfigs)
    pipeline.parallelJobs = pipeline.x86TestConfigs.collectEntries{key, values -> [key, [pipeline.createKubernetesPodConfig(pipeline.LLM_DOCKER_IMAGE, values[0], "amd64", values[4] ?: 1, values.size() > 5 ? values[5] : false), { attemptTag, isFinalAttempt, retryContext = null ->
        def config = pipeline.VANILLA_CONFIG
        if (key.contains("single-device")) {
            config = pipeline.SINGLE_DEVICE_CONFIG
        }
        if (key.contains("llvm")) {
            config = pipeline.LLVM_CONFIG
        }
        pipeline.testPlatform.runLLMTestlistOnPlatform(pipeline, values[0], values[1], config, key, values[2], values[3], false, attemptTag, isFinalAttempt, retryContext)
    }]]}
    pipeline.fullSet = pipeline.parallelJobs.keySet()

    pipeline.x86SlurmTestConfigs = [
        "DGX_H100-PyTorch-1": ["auto:dgx-h100-x1", "l0_h100", 1, 6],
        "DGX_H100-PyTorch-2": ["auto:dgx-h100-x1", "l0_h100", 2, 6],
        "DGX_H100-PyTorch-3": ["auto:dgx-h100-x1", "l0_h100", 3, 6],
        "DGX_H100-PyTorch-4": ["auto:dgx-h100-x1", "l0_h100", 4, 6],
        "DGX_H100-PyTorch-5": ["auto:dgx-h100-x1", "l0_h100", 5, 6],
        "DGX_H100-PyTorch-6": ["auto:dgx-h100-x1", "l0_h100", 6, 6],
        "DGX_H100-PyTorch-Post-Merge-1": ["auto:dgx-h100-x1", "l0_h100", 1, 2],
        "DGX_H100-PyTorch-Post-Merge-2": ["auto:dgx-h100-x1", "l0_h100", 2, 2],
        "DGX_A100-FMHA-Post-Merge-1": ["auto:dgx-a100-x1", "l0_a100", 1, 1],
        "DGX_H100-2_GPUs-PyTorch-Others-1": ["auto:dgx-h100-x2", "l0_dgx_h100", 1, 2, 2],
        "DGX_H100-2_GPUs-PyTorch-Others-2": ["auto:dgx-h100-x2", "l0_dgx_h100", 2, 2, 2],
        "DGX_H100-2_GPUs-PyTorch-GptOss-1": ["auto:dgx-h100-x2", "l0_dgx_h100", 1, 1, 2],
        "DGX_H100-2_GPUs-PyTorch-Ray-1": ["auto:dgx-h100-x2", "l0_dgx_h100", 1, 1, 2],
        "DGX_H100-4_GPUs-PyTorch-DeepSeek-1": ["auto:dgx-h100-x4", "l0_dgx_h100", 1, 1, 4],
        "DGX_H100-4_GPUs-PyTorch-GptOss-1": ["auto:dgx-h100-x4", "l0_dgx_h100", 1, 1, 4],
        "DGX_H100-4_GPUs-PyTorch-Others-1": ["auto:dgx-h100-x4", "l0_dgx_h100", 1, 2, 4],
        "DGX_H100-4_GPUs-PyTorch-Others-2": ["auto:dgx-h100-x4", "l0_dgx_h100", 2, 2, 4],
        "DGX_H100-4_GPUs-PyTorch-Ray-1": ["auto:dgx-h100-x4", "l0_dgx_h100", 1, 1, 4],
        "DGX_H100-4_GPUs-PyTorch-Post-Merge-1": ["auto:dgx-h100-x4", "l0_dgx_h100", 1, 1, 4],
        "DGX_B200-CPP-1": ["auto:dgx-b200-flex", "l0_b200", 1, 1, 1, 1, true],
        "DGX_B200-PyTorch-1": ["auto:dgx-b200-flex", "l0_b200", 1, 9, 1, 1, true],
        "DGX_B200-PyTorch-2": ["auto:dgx-b200-flex", "l0_b200", 2, 9, 1, 1, true],
        "DGX_B200-PyTorch-3": ["auto:dgx-b200-flex", "l0_b200", 3, 9, 1, 1, true],
        "DGX_B200-PyTorch-4": ["auto:dgx-b200-flex", "l0_b200", 4, 9, 1, 1, true],
        "DGX_B200-PyTorch-5": ["auto:dgx-b200-flex", "l0_b200", 5, 9, 1, 1, true],
        "DGX_B200-PyTorch-6": ["auto:dgx-b200-flex", "l0_b200", 6, 9, 1, 1, true],
        "DGX_B200-PyTorch-7": ["auto:dgx-b200-flex", "l0_b200", 7, 9, 1, 1, true],
        "DGX_B200-PyTorch-8": ["auto:dgx-b200-flex", "l0_b200", 8, 9, 1, 1, true],
        "DGX_B200-PyTorch-9": ["auto:dgx-b200-flex", "l0_b200", 9, 9, 1, 1, true],
        "DGX_B200-PyTorch-Post-Merge-1": ["auto:dgx-b200-flex", "l0_b200", 1, 2, 1, 1, true],
        "DGX_B200-PyTorch-Post-Merge-2": ["auto:dgx-b200-flex", "l0_b200", 2, 2, 1, 1, true],
        "DGX_B200-2_GPUs-PyTorch-1": ["auto:dgx-b200-flex", "l0_dgx_b200", 1, 1, 2, 1, true],
        "DGX_B200-4_GPUs-PyTorch-1": ["auto:dgx-b200-flex", "l0_dgx_b200", 1, 3, 4, 1, true],
        "DGX_B200-4_GPUs-PyTorch-2": ["auto:dgx-b200-flex", "l0_dgx_b200", 2, 3, 4, 1, true],
        "DGX_B200-4_GPUs-PyTorch-3": ["auto:dgx-b200-flex", "l0_dgx_b200", 3, 3, 4, 1, true],
        "DGX_B200-4_GPUs-PyTorch-Ray-1": ["auto:dgx-b200-flex", "l0_dgx_b200", 1, 1, 4, 1, true],
        "DGX_B200-4_GPUs-PyTorch-Post-Merge-1": ["auto:dgx-b200-flex", "l0_dgx_b200", 1, 4, 4, 1, true],
        "DGX_B200-4_GPUs-PyTorch-Post-Merge-2": ["auto:dgx-b200-flex", "l0_dgx_b200", 2, 4, 4, 1, true],
        "DGX_B200-4_GPUs-PyTorch-Post-Merge-3": ["auto:dgx-b200-flex", "l0_dgx_b200", 3, 4, 4, 1, true],
        "DGX_B200-4_GPUs-PyTorch-Post-Merge-4": ["auto:dgx-b200-flex", "l0_dgx_b200", 4, 4, 4, 1, true],
        "DGX_B200-8_GPUs-PyTorch-1": ["auto:dgx-b200-flex", "l0_dgx_b200", 1, 4, 8, 1, true],
        "DGX_B200-8_GPUs-PyTorch-2": ["auto:dgx-b200-flex", "l0_dgx_b200", 2, 4, 8, 1, true],
        "DGX_B200-8_GPUs-PyTorch-3": ["auto:dgx-b200-flex", "l0_dgx_b200", 3, 4, 8, 1, true],
        "DGX_B200-8_GPUs-PyTorch-4": ["auto:dgx-b200-flex", "l0_dgx_b200", 4, 4, 8, 1, true],
        // M3 CTX TP2/EP2 -> GEN TP4/EP1 C++ NIXL bounce accuracy (6 GPUs).
        "DGX_B200-6_GPUs-PyTorch-M3-Post-Merge-1": ["auto:dgx-b200-flex", "l0_dgx_b200_m3_6gpu", 1, 1, 6, 1, true],
        "B300-PyTorch-1": ["auto:dgx-b300-flex", "l0_b300", 1, 2, 1, 1, true],
        "B300-PyTorch-2": ["auto:dgx-b300-flex", "l0_b300", 2, 2, 1, 1, true],
        "B300-PyTorch-Post-Merge-1": ["auto:dgx-b300-flex", "l0_b300", 1, 1, 1, 1, true],
        "DGX_B300-4_GPUs-PyTorch-1": ["auto:dgx-b300-flex", "l0_dgx_b300", 1, 1, 4, 1, true],
        // Keep this functional stage on AWS-PDX for EFA coverage.
        "DGX_B300-2_GPUs-2_Nodes-PyTorch-Disagg-PerfSanity-FUNCTIONAL-ONLY-1": ["b300-flex-aws-pdx", "l0_dgx_b300_efa_multi_nodes", 1, 1, 2, 2, true],
        "DGX_B300-4_GPUs-PyTorch-Post-Merge-1": ["auto:dgx-b300-flex", "l0_dgx_b300", 1, 2, 4, 1, true],
        "DGX_B300-4_GPUs-PyTorch-Post-Merge-2": ["auto:dgx-b300-flex", "l0_dgx_b300", 2, 2, 4, 1, true],
        // VisualGen PerfSanity post-merge test
        "DGX_B200-8_GPUs-PyTorch-VisualGen-PerfSanity-Post-Merge-1": ["auto:dgx-b200-flex", "l0_b200_visual_gen_perf_sanity", 1, 1, 8, 1, true],
        // Single-GPU Gemma4 PerfSanity post-merge baseline
        "DGX_B200-PyTorch-PerfSanity-Post-Merge-1": ["auto:dgx-b200-flex", "l0_b200_perf_sanity", 1, 1, 1, 1, true],
        // PerfSanity post-merge tests
        "DGX_B200-8_GPUs-PyTorch-PerfSanity-Post-Merge-1": ["auto:dgx-b200-flex", "l0_b200_multi_gpus_perf_sanity", 1, 1, 8, 1, true],
        "GR100-PyTorch-Post-Merge-1": ["auto:gr100-x1", "l0_gr100", 1, 1],
    ]
    pipeline.x86SlurmTestConfigs = pipeline.testSelection.cbtsResizeSplits(pipeline.x86SlurmTestConfigs)
    pipeline.fullSet += pipeline.x86SlurmTestConfigs.keySet()

    pipeline.parallelSlurmJobs = pipeline.x86SlurmTestConfigs.collectEntries{key, values -> [key, [pipeline.createKubernetesPodConfig(pipeline.X86_64_DOCKER_IMAGE, "slurm", "amd64"), { attemptTag, isFinalAttempt, retryContext = null ->
        // attemptTag comes from runKubernetesPodWithInfraRetry for the outer
        // dispatcher pod (when retry is enabled — see opts below) and is
        // threaded into runLLMTestlistOnSlurm so a future re-enable of outer
        // pod retry composes a unique postTag instead of colliding with the
        // dead pod's recorded upload. With singleAttempt:true (default for
        // SLURM stages) the outer retry is off and attemptTag is always "".
        def config = pipeline.VANILLA_CONFIG
        if (key.contains("single-device")) {
            config = pipeline.SINGLE_DEVICE_CONFIG
        }
        if (key.contains("llvm")) {
            config = pipeline.LLVM_CONFIG
        }
        pipeline.infraRetry.runLLMTestlistOnSlurm(pipeline, values[0], values[1], config, key, values[2], values[3], values[4] ?: 1, values[5] ?: 1, values[6] ?: false, false, attemptTag, false, retryContext?.infraRetryMax)
    }, [singleAttempt: true, slurmDispatcher: true]]]}
    // SLURM dispatcher pods run their own inner retry loop
    // (runLLMTestlistOnSlurm with SLURM_INFRA_RETRY_MAX). Disabling the outer
    // K8s pod retry (singleAttempt:true) here caps total attempts at
    // SLURM_INFRA_RETRY_MAX+1 instead of (SLURM_INFRA_RETRY_MAX+1) *
    // (K8S_INFRA_RETRY_MAX+1). Each SLURM attempt that hits the partition
    // walltime burns ~4h, so nesting the two layers cost up to ~36h per stage
    // on consistently-timing-out tests before this cap.

    pipeline.parallelJobs += pipeline.parallelSlurmJobs
}

def prepareSbsaTestJobs(pipeline)
{
    // SBSA machines from the Blossom machine pool
    pipeline.SBSATestConfigs = [
        "CPU-Generic-arm-1": ["cpu", "l0_cpu", 1, 2],
        "CPU-Generic-arm-2": ["cpu", "l0_cpu", 2, 2],
        "GH200-PyTorch-Post-Merge-1": ["gh200", "l0_gh200", 1, 1],
        // DGX Spark is also named as GB10 Grace Blackwell Superchip.
        "GB10-PyTorch-1": ["gb10x", "l0_gb10", 1, 1],
    ]
    pipeline.SBSATestConfigs = pipeline.testSelection.cbtsResizeSplits(pipeline.SBSATestConfigs)
    pipeline.fullSet += pipeline.SBSATestConfigs.keySet()

    pipeline.SBSASlurmTestConfigs = [
        // [platform, testList, splitId, splits, gpuCount, nodeCount?, runWithSbatch?, useClusterDurations?]
        // useClusterDurations=true: record actual test times so each cluster builds its own
        // .test_durations_<clusterName> baseline for load-balanced sharding.
        "GB200-4_GPUs-PyTorch-1": ["auto:gb200-x4", "l0_gb200_multi_gpus", 1, 5, 4, 1, false, true],
        "GB200-4_GPUs-PyTorch-2": ["auto:gb200-x4", "l0_gb200_multi_gpus", 2, 5, 4, 1, false, true],
        "GB200-4_GPUs-PyTorch-3": ["auto:gb200-x4", "l0_gb200_multi_gpus", 3, 5, 4, 1, false, true],
        "GB200-4_GPUs-PyTorch-4": ["auto:gb200-x4", "l0_gb200_multi_gpus", 4, 5, 4, 1, false, true],
        "GB200-4_GPUs-PyTorch-5": ["auto:gb200-x4", "l0_gb200_multi_gpus", 5, 5, 4, 1, false, true],
        "GB200-4_GPUs-PyTorch-Post-Merge-1": ["auto:gb200-x4", "l0_gb200_multi_gpus", 1, 2, 4, 1, false, true],
        "GB200-4_GPUs-PyTorch-Post-Merge-2": ["auto:gb200-x4", "l0_gb200_multi_gpus", 2, 2, 4, 1, false, true],
        "GB10-PyTorch-Post-Merge-1": ["gb10x-single", "l0_gb10", 1, 1],
        "GB300-4_GPUs-PyTorch-1": ["auto:gb300-x4", "l0_gb300", 1, 1, 4, 1, true, false],
        "GB300-4_GPUs-PyTorch-Post-Merge-1": ["auto:gb300-x4", "l0_gb300_multi_gpus", 1, 3, 4, 1, true, false],
        "GB300-4_GPUs-PyTorch-Post-Merge-2": ["auto:gb300-x4", "l0_gb300_multi_gpus", 2, 3, 4, 1, true, false],
        "GB300-4_GPUs-PyTorch-Post-Merge-3": ["auto:gb300-x4", "l0_gb300_multi_gpus", 3, 3, 4, 1, true, false],
        // PerfSanity post-merge tests
        "GB200-4_GPUs-PyTorch-PerfSanity-Post-Merge-1": ["auto:gb200-x4", "l0_gb200_multi_gpus_perf_sanity", 1, 1, 4],
        "GB300-4_GPUs-PyTorch-PerfSanity-Post-Merge-1": ["auto:gb300-x4", "l0_gb300_multi_gpus_perf_sanity", 1, 7, 4, 1, true, false],
        "GB300-4_GPUs-PyTorch-PerfSanity-Post-Merge-2": ["auto:gb300-x4", "l0_gb300_multi_gpus_perf_sanity", 2, 7, 4, 1, true, false],
        "GB300-4_GPUs-PyTorch-PerfSanity-Post-Merge-3": ["auto:gb300-x4", "l0_gb300_multi_gpus_perf_sanity", 3, 7, 4, 1, true, false],
        "GB300-4_GPUs-PyTorch-PerfSanity-Post-Merge-4": ["auto:gb300-x4", "l0_gb300_multi_gpus_perf_sanity", 4, 7, 4, 1, true, false],
        "GB300-4_GPUs-PyTorch-PerfSanity-Post-Merge-5": ["auto:gb300-x4", "l0_gb300_multi_gpus_perf_sanity", 5, 7, 4, 1, true, false],
        "GB300-4_GPUs-PyTorch-PerfSanity-Post-Merge-6": ["auto:gb300-x4", "l0_gb300_multi_gpus_perf_sanity", 6, 7, 4, 1, true, false],
        "GB300-4_GPUs-PyTorch-PerfSanity-Post-Merge-7": ["auto:gb300-x4", "l0_gb300_multi_gpus_perf_sanity", 7, 7, 4, 1, true, false],
    ]
    pipeline.SBSASlurmTestConfigs = pipeline.testSelection.cbtsResizeSplits(pipeline.SBSASlurmTestConfigs)
    pipeline.fullSet += pipeline.SBSASlurmTestConfigs.keySet()

    pipeline.multiNodesSBSAConfigs = [
        // Each GB200 testcase below uses 8 GPUs and 2 nodes.
        // https://nvbugs/5598863 (uncorrectable NVLink error detected during the execution) may not exist in OCI machines.
        "GB200-8_GPUs-2_Nodes-PyTorch-1": ["auto:gb200-flex", "l0_gb200_multi_nodes", 1, 2, 8, 2],
        "GB200-8_GPUs-2_Nodes-PyTorch-2": ["auto:gb200-flex", "l0_gb200_multi_nodes", 2, 2, 8, 2],
        "GB200-8_GPUs-2_Nodes-PyTorch-Post-Merge-1": ["auto:gb200-flex", "l0_gb200_multi_nodes", 1, 3, 8, 2],
        "GB200-8_GPUs-2_Nodes-PyTorch-Post-Merge-2": ["auto:gb200-flex", "l0_gb200_multi_nodes", 2, 3, 8, 2],
        "GB200-8_GPUs-2_Nodes-PyTorch-Post-Merge-3": ["auto:gb200-flex", "l0_gb200_multi_nodes", 3, 3, 8, 2],
        // GB300 accuracy post-merge aggregated (4 GPUs per node). One test list per topology,
        // spelled out here rather than via buildStageConfigs: test_to_stage_mapping.py resolves
        // stage <-> test by list name with a line-based parser, so a shared list or a helper's
        // output breaks the mapping. For SingleNvlinkDomain see singleNvlinkDomainMode.
        "GB300-8_GPUs-2_Nodes-PyTorch-SingleNvlinkDomain-Post-Merge-1": ["auto:gb300-flex", "l0_gb300_multi_nodes_node2_gpu8", 1, 1, 8, 2],
        "GB300-16_GPUs-4_Nodes-PyTorch-SingleNvlinkDomain-Post-Merge-1": ["auto:gb300-flex", "l0_gb300_multi_nodes_node4_gpu16", 1, 1, 16, 4],
    ]
    // PerfSanity post-merge aggregated
    // 2 Nodes
    pipeline.multiNodesSBSAConfigs += pipeline.testSelection.buildStageConfigs(
        "GB200-8_GPUs-2_Nodes-PyTorch-PerfSanity-Node2-GPU8-Post-Merge",
        "auto:gb200-flex",
        "l0_gb200_multi_nodes_perf_sanity_node2_gpu8",
        2,
        8,
        2
    )
    // PerfSanity post-merge disaggregated
    // 2 Nodes
    pipeline.multiNodesSBSAConfigs += pipeline.testSelection.buildStageConfigs(
        "GB200-8_GPUs-2_Nodes-PyTorch-Disagg-PerfSanity-CTX1-NODE1-GPU1-GEN1-NODE1-GPU4-Post-Merge",
        "auto:gb200-flex",
        "l0_gb200_multi_nodes_perf_sanity_ctx1_node1_gpu1_gen1_node1_gpu4",
        2,
        8,
        2
    )
    // 3 Nodes
    pipeline.multiNodesSBSAConfigs += pipeline.testSelection.buildStageConfigs(
        "GB200-12_GPUs-3_Nodes-PyTorch-Disagg-PerfSanity-CTX1-NODE1-GPU1-GEN1-NODE2-GPU8-Post-Merge",
        "auto:gb200-flex",
        "l0_gb200_multi_nodes_perf_sanity_ctx1_node1_gpu1_gen1_node2_gpu8",
        2,
        12,
        3
    )
    // gen_only_no_context: gen1 (2 nodes, 8 GPUs), no ctx fleet = 8 GPUs
    pipeline.multiNodesSBSAConfigs += pipeline.testSelection.buildStageConfigs(
        "GB200-8_GPUs-2_Nodes-PyTorch-PerfSanity-GEN1-NODE2-GPU8-Post-Merge",
        "auto:gb200-flex",
        "l0_gb200_multi_nodes_perf_sanity_gen1_node2_gpu8",
        1,
        8,
        2
    )
    // GB300 PerfSanity post-merge disaggregated
    // GLM-5 (ctx DEP2): 3 Nodes
    pipeline.multiNodesSBSAConfigs += pipeline.testSelection.buildStageConfigs(
        "GB300-12_GPUs-3_Nodes-PyTorch-Disagg-PerfSanity-CTX1-NODE1-GPU2-GEN1-NODE2-GPU8-Post-Merge",
        "auto:gb300-flex",
        "l0_gb300_multi_nodes_perf_sanity_ctx1_node1_gpu2_gen1_node2_gpu8",
        3,
        12,
        3
    )
    // 9 Nodes
    pipeline.multiNodesSBSAConfigs += pipeline.testSelection.buildStageConfigs(
        "GB300-36_GPUs-9_Nodes-PyTorch-Disagg-PerfSanity-CTX1-NODE1-GPU2-GEN1-NODE8-GPU32-Post-Merge",
        "auto:gb300-flex",
        "l0_gb300_multi_nodes_perf_sanity_ctx1_node1_gpu2_gen1_node8_gpu32",
        1,
        36,
        9
    )
    // 9 Nodes: ctx1 (1 node, 4 GPUs) + gen4 (2 nodes, 8 GPUs each) = 36 GPUs
    pipeline.multiNodesSBSAConfigs += pipeline.testSelection.buildStageConfigs(
        "GB300-36_GPUs-9_Nodes-PyTorch-Disagg-PerfSanity-CTX1-NODE1-GPU4-GEN4-NODE2-GPU8-Post-Merge",
        "auto:gb300-flex",
        "l0_gb300_multi_nodes_perf_sanity_ctx1_node1_gpu4_gen4_node2_gpu8",
        2,
        36,
        9
    )
    // 10 Nodes: ctx6 (1 node, 4 GPUs each) + gen1 (4 nodes, 16 GPUs) = 40 GPUs
    pipeline.multiNodesSBSAConfigs += pipeline.testSelection.buildStageConfigs(
        "GB300-40_GPUs-10_Nodes-PyTorch-Disagg-PerfSanity-CTX6-NODE1-GPU4-GEN1-NODE4-GPU16-Post-Merge",
        "auto:gb300-flex",
        "l0_gb300_multi_nodes_perf_sanity_ctx6_node1_gpu4_gen1_node4_gpu16",
        2,
        40,
        10
    )
    // 11 Nodes: ctx3 (1 node, 4 GPUs each) + gen1 (8 nodes, 32 GPUs) = 44 GPUs
    pipeline.multiNodesSBSAConfigs += pipeline.testSelection.buildStageConfigs(
        "GB300-44_GPUs-11_Nodes-PyTorch-Disagg-PerfSanity-CTX3-NODE1-GPU4-GEN1-NODE8-GPU32-Post-Merge",
        "auto:gb300-flex",
        "l0_gb300_multi_nodes_perf_sanity_ctx3_node1_gpu4_gen1_node8_gpu32",
        2,
        44,
        11
    )
    // 14 Nodes: ctx12 (1 node, 4 GPUs each) + gen1 (2 nodes, 8 GPUs) = 56 GPUs
    pipeline.multiNodesSBSAConfigs += pipeline.testSelection.buildStageConfigs(
        "GB300-56_GPUs-14_Nodes-PyTorch-Disagg-PerfSanity-CTX12-NODE1-GPU4-GEN1-NODE2-GPU8-Post-Merge",
        "auto:gb300-flex",
        "l0_gb300_multi_nodes_perf_sanity_ctx12_node1_gpu4_gen1_node2_gpu8",
        2,
        56,
        14
    )
    // gen_only_no_context: gen1 (2 nodes, 8 GPUs), no ctx fleet = 8 GPUs
    pipeline.multiNodesSBSAConfigs += pipeline.testSelection.buildStageConfigs(
        "GB300-8_GPUs-2_Nodes-PyTorch-PerfSanity-GEN1-NODE2-GPU8-Post-Merge",
        "auto:gb300-flex",
        "l0_gb300_multi_nodes_perf_sanity_gen1_node2_gpu8",
        6,
        8,
        2
    )
    // gen_only_no_context: gen1 (4 nodes, 16 GPUs), no ctx fleet = 16 GPUs
    pipeline.multiNodesSBSAConfigs += pipeline.testSelection.buildStageConfigs(
        "GB300-16_GPUs-4_Nodes-PyTorch-PerfSanity-GEN1-NODE4-GPU16-Post-Merge",
        "auto:gb300-flex",
        "l0_gb300_multi_nodes_perf_sanity_gen1_node4_gpu16",
        2,
        16,
        4
    )
    // gen_only_no_context: gen1 (8 nodes, 32 GPUs), no ctx fleet = 32 GPUs
    pipeline.multiNodesSBSAConfigs += pipeline.testSelection.buildStageConfigs(
        "GB300-32_GPUs-8_Nodes-PyTorch-PerfSanity-GEN1-NODE8-GPU32-Post-Merge",
        "auto:gb300-flex",
        "l0_gb300_multi_nodes_perf_sanity_gen1_node8_gpu32",
        2,
        32,
        8
    )
    // Nemotron-Ultra-V3 8k64k con1 and GLM-5.3-Flash FP8 8k1k con16: ctx1 (1 node, 4 GPUs) + gen1 tep4 (1 node, 4 GPUs) = 8 GPUs
    pipeline.multiNodesSBSAConfigs += pipeline.testSelection.buildStageConfigs(
        "GB300-8_GPUs-2_Nodes-PyTorch-Disagg-PerfSanity-CTX1-NODE1-GPU4-GEN1-NODE1-GPU4-Post-Merge",
        "auto:gb300-flex",
        "l0_gb300_multi_nodes_perf_sanity_ctx1_node1_gpu4_gen1_node1_gpu4",
        2,
        8,
        2
    )
    // Nemotron-Ultra-V3 50k2k con12: ctx1 (1 node, 4 GPUs) + gen6 (6 nodes, 4 GPUs each) = 28 GPUs
    pipeline.multiNodesSBSAConfigs += pipeline.testSelection.buildStageConfigs(
        "GB300-28_GPUs-7_Nodes-PyTorch-Disagg-PerfSanity-CTX1-NODE1-GPU4-GEN6-NODE1-GPU4-Post-Merge",
        "auto:gb300-flex",
        "l0_gb300_multi_nodes_perf_sanity_ctx1_node1_gpu4_gen6_node1_gpu4",
        2,
        28,
        7
    )
    // Nemotron-Ultra-V3 con9832 (8k64k) and con1197 (50k2k) are ctx_only-only:
    // their full 68-/72-GPU e2e+gen_only disagg topologies are intentionally not
    // created; the ctx_only ids run in the 4-GPU multi_gpus post-merge stage.
    pipeline.multiNodesSBSAConfigs = pipeline.testSelection.cbtsResizeSplits(pipeline.multiNodesSBSAConfigs)
    pipeline.fullSet += pipeline.multiNodesSBSAConfigs.keySet()

    if (pipeline.env.targetArch == pipeline.AARCH64_TRIPLE) {
        pipeline.parallelJobs = pipeline.SBSATestConfigs.collectEntries{key, values -> [key, [pipeline.createKubernetesPodConfig(pipeline.LLM_DOCKER_IMAGE, values[0], "arm64"), { attemptTag, isFinalAttempt, retryContext = null ->
            pipeline.testPlatform.runLLMTestlistOnPlatform(pipeline, values[0], values[1], pipeline.LINUX_AARCH64_CONFIG, key, values[2], values[3], false, attemptTag, isFinalAttempt, retryContext, values[4] ?: false)
        }]]}

        // Add SBSA Slurm jobs
        // singleAttempt:true disables the outer K8s pod retry; see the x86
        // SLURM closure above for the full rationale (cap nested retry budget
        // so consistently-timing-out tests don't burn ~36h on retry cascades).
        pipeline.parallelSlurmJobs = pipeline.SBSASlurmTestConfigs.collectEntries{key, values -> [key, [pipeline.createKubernetesPodConfig(pipeline.X86_64_DOCKER_IMAGE, "slurm", "amd64"), { attemptTag, isFinalAttempt, retryContext = null ->
            // attemptTag is threaded into runLLMTestlistOnSlurm as the outer
            // dispatcher pod's tag so the inner SLURM retry's postTag can't
            // collide with a previous dispatcher pod's upload. See the x86
            // SLURM closure for the full rationale.
            def config = pipeline.LINUX_AARCH64_CONFIG
            if (key.contains("single-device")) {
                config = pipeline.SINGLE_DEVICE_CONFIG
            }
            if (key.contains("llvm")) {
                config = pipeline.LLVM_CONFIG
            }
            pipeline.infraRetry.runLLMTestlistOnSlurm(pipeline, values[0], values[1], config, key, values[2], values[3], values[4] ?: 1, values[5] ?: 1, values[6] ?: false, false, attemptTag, values[7] ?: false, retryContext?.infraRetryMax)
        }, [singleAttempt: true, slurmDispatcher: true]]]}
        pipeline.parallelJobs += pipeline.parallelSlurmJobs

        // Add SBSA multi node Slurm jobs
        // singleAttempt:true disables the outer K8s pod retry; see above.
        pipeline.parallelMultiNodesSBSAJobs = pipeline.multiNodesSBSAConfigs.collectEntries{key, values -> [key, [pipeline.createKubernetesPodConfig(pipeline.X86_64_DOCKER_IMAGE, "slurm", "amd64"), { attemptTag, isFinalAttempt, retryContext = null ->
            def config = pipeline.LINUX_AARCH64_CONFIG
            if (key.contains("single-device")) {
                config = pipeline.SINGLE_DEVICE_CONFIG
            }
            if (key.contains("llvm")) {
                config = pipeline.LLVM_CONFIG
            }
            pipeline.infraRetry.runLLMTestlistOnSlurm(pipeline, values[0], values[1], config, key, values[2], values[3], values[4] ?: 1, values[5] ?: 2, values[6] ?: false, false, attemptTag, values[7] ?: false, retryContext?.infraRetryMax)
        }, [singleAttempt: true, slurmDispatcher: true]]]}

        pipeline.parallelJobs += pipeline.parallelMultiNodesSBSAJobs
    }
}

def launchTestJobs(pipeline, testFilter, globalVars)
{
    def versionOverride = globalVars[pipeline.TRTLLM_VERSION_OVERRIDE] ?: ""
    pipeline.BOLT_PINNED_REF = globalVars[pipeline.BOLT_PROFILE_REF]?.toString() ?: ""
    pipeline.BOLT_TEST_BOLTED_TARBALL = globalVars[pipeline.BOLT_PUBLISH_VARIANT] ?: false
    pipeline.TRTLLM_RELEASE_REPO = globalVars["TRTLLM_RELEASE_REPO"]
    // IMPORTANT: Stage Configuration Syntax Requirement
    //
    // The test_to_stage_mapping.py script expects stage definitions in the following format:
    // "Stage-Name": ["platform", "yaml_file", splitId, split_count, gpu_count]
    //
    // Where:
    // - Stage-Name: Must be quoted string, used to identify the Jenkins stage
    // - platform: Hardware platform identifier (e.g., "a10", "h100-cr")
    // - yaml_file: Test database YAML filename without .yml extension (e.g., "l0_a10")
    // - splitId: Current split number (1-based)
    // - split_count: Total number of splits
    // - gpu_count: Number of GPUs required (optional, defaults to 1)
    //
    // This format is parsed by scripts/test_to_stage_mapping.py to provide bidirectional
    // mapping between test names and Jenkins stage names. Any changes to this syntax
    // may break the mapping functionality.

    prepareX86TestJobs(pipeline)
    prepareSbsaTestJobs(pipeline)

    // Doc build is pure CPU work (checkout + wheel install + doxygen/sphinx
    // `make html` + upload); it never touches a GPU, so run it on the CPU
    // "build" pod. Mirrors the CPU-only agent-flow job below.
    pipeline.docBuildSpec = pipeline.createKubernetesPodConfig(pipeline.LLM_DOCKER_IMAGE, "build")
    pipeline.docBuildConfigs = [
        "CPU-Build_Docs": [pipeline.docBuildSpec, {
            pipeline.sh "rm -rf **/*.xml *.tar.gz"
            pipeline.testRuntime.runLLMDocBuild(pipeline, pipeline.config=pipeline.VANILLA_CONFIG)
        }],
    ]

    pipeline.fullSet += pipeline.docBuildConfigs.keySet()

    if (pipeline.env.targetArch == pipeline.AARCH64_TRIPLE) {
        pipeline.docBuildConfigs = [:]
    }

    pipeline.docBuildJobs = pipeline.docBuildConfigs.collectEntries{key, values -> [key, [values[0], { attemptTag, isFinalAttempt, retryContext = null ->
        // attemptTag uniquifies the upload-once guard key and tar filename per
        // pod-launch attempt; isFinalAttempt suppresses synthetic stage-fail XML
        // and junit() on intermediate retryable infra failures.
        pipeline.stage("[${key}] Run") {
            pipeline.testResults.cacheErrorAndUploadResult(pipeline, "${key}", values[1], {}, true, attemptTag, isFinalAttempt, retryContext)
        }
    }]]}

    // agent-flow: pure-CPU pytest suite for the imported agent-flow sub-project.
    // Runs on a CPU node ("build" pod type, kubernetes-cpu cloud) — it needs no
    // GPU and no TRT-LLM wheel, only its own `pip install -e agent-flow[test]`.
    // CBTS narrows this to agent-flow-only changes via
    // jenkins/scripts/cbts/cbts/rules/agent_flow.py; AGENT_FLOW_STAGE in that
    // rule MUST equal the stage name below or CBTS Layer 2 will drop it.
    pipeline.agentFlowTestSpec = pipeline.createKubernetesPodConfig(pipeline.LLM_DOCKER_IMAGE, "build")
    // Single source of truth for the stage name: it is both the map key (which
    // cacheErrorAndUploadResult uses for the results dir it tars and feeds to
    // junit) and the name passed to runLLMAgentFlowTest (which writes
    // results.xml into that dir). If the two ever diverge, junit finds no
    // report ("No test report files were found").
    def agentFlowStageName = "CPU-AgentFlow-UnitTest"
    pipeline.agentFlowTestConfigs = [
        (agentFlowStageName): [pipeline.agentFlowTestSpec, {
            pipeline.sh "rm -rf **/*.xml *.tar.gz"
            pipeline.testRuntime.runLLMAgentFlowTest(pipeline, agentFlowStageName)
        }],
    ]

    pipeline.fullSet += pipeline.agentFlowTestConfigs.keySet()

    // agent-flow tests are architecture-independent; run them once on x86 only.
    if (pipeline.env.targetArch == pipeline.AARCH64_TRIPLE) {
        pipeline.agentFlowTestConfigs = [:]
    }

    pipeline.agentFlowTestJobs = pipeline.agentFlowTestConfigs.collectEntries{key, values -> [key, [values[0], { attemptTag, isFinalAttempt, retryContext = null ->
        pipeline.stage("[${key}] Run") {
            pipeline.testResults.cacheErrorAndUploadResult(pipeline, "${key}", values[1], {}, false, attemptTag, isFinalAttempt, retryContext)
        }
    }]]}

    // Python version and OS for sanity check
    // Slots: [buildImage, gpuType, cpuArch, reinstallDependencies, isDlfw, pipInstallImage, extraPytorchInstall, platName]
    pipeline.x86SanityCheckConfigs = [
        "PY312-DLFW": [
            pipeline.LLM_DOCKER_IMAGE,
            "B200_PCIe",
            pipeline.X86_64_TRIPLE,
            false,
            true,
            pipeline.DLFW_IMAGE,
            false,
            'manylinux_2_39_x86_64',
        ],
        "PY310-UB2204": [
            pipeline.LLM_ROCKYLINUX8_PY310_DOCKER_IMAGE,
            "A10",
            pipeline.X86_64_TRIPLE,
            true,
            false,
            pipeline.UBUNTU_22_04_IMAGE,
            true, // Extra install PyTorch CUDA 13.0 package to align with the CUDA version used for building TensorRT LLM wheels.
            'manylinux_2_28_x86_64',
        ],
        "PY312-UB2404": [
            pipeline.LLM_ROCKYLINUX8_PY312_DOCKER_IMAGE,
            "A100X",
            pipeline.X86_64_TRIPLE,
            true,
            false,
            pipeline.UBUNTU_24_04_IMAGE,
            true, // Extra PyTorch CUDA 13.0 install
            'manylinux_2_28_x86_64',
        ],
    ]

    pipeline.aarch64SanityCheckConfigs = [
        "PY312-UB2404": [
            pipeline.LLM_WHEEL_DOCKER_IMAGE,
            "GH200",
            pipeline.AARCH64_TRIPLE,
            false,
            false,
            pipeline.UBUNTU_24_04_IMAGE,
            true, // Extra PyTorch CUDA 13.0 install
            'manylinux_2_39_aarch64',
        ],
        "PY312-DLFW": [
            pipeline.LLM_DOCKER_IMAGE,
            "GH200",
            pipeline.AARCH64_TRIPLE,
            false,
            true,
            pipeline.DLFW_IMAGE,
            false,
            'manylinux_2_39_aarch64',
        ],
    ]

    def toStageName = { gpuType, key -> "${gpuType}-PackageSanityCheck-${key}".toString() }
    pipeline.fullSet += pipeline.x86SanityCheckConfigs.collectEntries{ key, values -> [toStageName(values[1], key), null] }.keySet()
    pipeline.fullSet += pipeline.aarch64SanityCheckConfigs.collectEntries{ key, values -> [toStageName(values[1], key), null] }.keySet()

    pipeline.sanityCheckConfigs = pipeline.x86SanityCheckConfigs
    if (pipeline.env.targetArch == pipeline.AARCH64_TRIPLE) {
        pipeline.sanityCheckConfigs = pipeline.aarch64SanityCheckConfigs
    }

    pipeline.sanityCheckJobs = pipeline.sanityCheckConfigs.collectEntries {key, values -> [toStageName(values[1], key), {
        pipeline.testResults.cacheErrorAndUploadResult(pipeline, toStageName(values[1], key), {
            def cpu_arch = values[2]
            def gpu_type = values[1].toLowerCase()
            if (values[1] == "B200_PCIe") {
                gpu_type = "b100-ts2"
            }
            if (values[1] == "RTX5090") {
                gpu_type = "rtx-5090"
            }

            def k8s_arch = "amd64"
            if (cpu_arch == pipeline.AARCH64_TRIPLE) {
                k8s_arch = "arm64"
            }

            def buildSpec = pipeline.createKubernetesPodConfig(values[0], "build", k8s_arch)
            def buildRunner = pipeline.testRuntime.runInKubernetes(pipeline, buildSpec, "trt-llm")
            def sanityRunner = null


            def sanitySpec = pipeline.createKubernetesPodConfig(values[0], gpu_type, k8s_arch)
            sanityRunner = pipeline.testRuntime.runInKubernetes(pipeline, sanitySpec, "trt-llm")

            def isDlfw = values[4]
            def wheelPath = ""
            def cpver = "cp312"
            if (key.contains("PY310")) {
                cpver = "cp310"
            }

            // Same switch the build helpers use for the tarball, so the released
            // wheel and the released tarball are never optimized differently.
            def boltConsume = globalVars[pipeline.BOLT_CONSUME_BUILD]?.toString() == "true"
            // The bundle this pipeline pinned, so the released wheel is optimized
            // with the same profiles the tested build was. Empty means unpinned:
            // apply_latest.sh then takes whatever `latest` is, i.e. today's
            // behaviour.
            def boltProfileRef = globalVars[pipeline.BOLT_PROFILE_REF]?.toString() ?: ""
            def boltProfileBranch = globalVars[pipeline.BOLT_PROFILE_BRANCH]?.toString() ?: ""
            def kitmakerDryRun = testFilter[(pipeline.IS_POST_MERGE)] ?: false

            buildRunner("[${toStageName(values[1], key)}] Build") {
                wheelPath = pipeline.testBuild.runLLMBuild(
                    pipeline, cpu_arch, values[3], "", versionOverride, cpver,
                    values[7], isDlfw, boltConsume, boltProfileRef, boltProfileBranch,
                    kitmakerDryRun)
            }

            // TODO: Re-enable the sanity check after updating GPU testers' driver version.
            // sanityRunner("Sanity check") {
            //     runPackageSanityCheck(pipeline, wheelPath, values[3])
            // }

            def checkPipStage = false
            if (cpu_arch == pipeline.X86_64_TRIPLE) {
                checkPipStage = true
            } else if (cpu_arch == pipeline.AARCH64_TRIPLE) {
                checkPipStage = true
            }

            if (checkPipStage) {
                pipeline.stage("[${toStageName(values[1], key)}] Run LLMAPI Test") {
                    pipeline.pipInstallSanitySpec = pipeline.createKubernetesPodConfig(values[5], gpu_type, k8s_arch)
                    pipeline.testRuntime.runKubernetesPodWithInfraRetry(pipeline, pipeline.pipInstallSanitySpec, "trt-llm", toStageName(values[1], key), { attemptTag, isFinalAttempt, retryContext = null ->
                        pipeline.echo "###### Prerequisites Start ######"
                        pipeline.testRuntime.echoNodeAndGpuInfo(pipeline, toStageName(values[1], key))
                        // Clean up the pip constraint file from the base NGC PyTorch image.
                        if (values[5] == pipeline.DLFW_IMAGE) {
                            pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "[ -f /etc/pip/constraint.txt ] && : > /etc/pip/constraint.txt || true")
                            // Remove the python3-pygments pip package because the dlfw image already includes a Debian pygments package, which conflicts with the pip-installed version.
                            pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "apt-get remove -y python3-pygments")
                            // Remove stale nvidia-cutlass-dsl from the base image to prevent namespace
                            // directory corruption when pip upgrades to the version required by tensorrt_llm.
                            pipeline.trtllm_utils.llmExecStepWithRetry(
                                pipeline,
                                script: "pip3 uninstall -y nvidia-cutlass-dsl nvidia-cutlass-dsl-libs-base " +
                                    "nvidia-cutlass-dsl-libs-core nvidia-cutlass-dsl-libs-cu12 " +
                                    "nvidia-cutlass-dsl-libs-cu13 || true")
                            pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: 'rm -rf $(python3 -c "import site; print(site.getsitepackages()[0])")/nvidia_cutlass_dsl*')
                        }
                        pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "apt-get update && apt-get install -y python3-pip git rsync curl wget")
                        pipeline.trtllm_utils.checkoutSource(pipeline.LLM_REPO, pipeline.env.gitlabCommit, pipeline.LLM_ROOT, true, true)
                        pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "pip3 config set global.break-system-packages true")
                        pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "pip3 install 'requests>=2.32.4,<3'")
                        pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "pip3 uninstall -y tensorrt")
                        if (values[5] != pipeline.DLFW_IMAGE) {
                            def ubuntu_version = key.contains("UB2404") ? "ubuntu2404" : "ubuntu2204"
                            def platform = cpu_arch == pipeline.X86_64_TRIPLE ? "x86_64" : "sbsa"
                            pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "wget https://developer.download.nvidia.com/compute/cuda/repos/${ubuntu_version}/${platform}/cuda-keyring_1.1-1_all.deb")
                            pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "dpkg -i cuda-keyring_1.1-1_all.deb")
                            // Match the CUDA the DLFW base image builds the wheel with.
                            pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "apt-get update && apt-get install -y cuda-toolkit-13-4")
                        }
                        // Extra CUDA 13 PyTorch install for all bare-metal environments (Default PyTorch is for CUDA 12.8)
                        if (values[6]) {
                            pipeline.echo "###### Extra CUDA 13 PyTorch install Start ######"
                            // Must match what requirements.txt resolves to from the public index: the
                            // wheel under test is linked against that libtorch, and a mismatch fails
                            // the import with an undefined c10 symbol instead of a version error.
                            // The internal mirror proxies https://download.pytorch.org/whl/cu132.
                            pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "pip3 install torch==2.14.0+cu132 torchvision==0.29.0+cu132 --extra-index-url https://artifactory.pdx.nvidia.com/artifactory/api/pypi/pytorch-cuda-thirteen-two-remote/simple")
                        }

                        // A stock image, so nothing here went through Dockerfile.multi or
                        // install_base.sh: what a singleton MPI_Comm_spawn needs has to be
                        // redone by hand, or checkPipInstall's quickstart hangs in
                        // MpiPoolSession until the timeout. PRRTE will not fork its DVM as
                        // root without these (Open MPI 4 only checked that in mpirun), and
                        // PMIX_HOSTNAME is the hostname-too-long-for-MPI one -- see
                        // runLLMTestlistOnPlatform.
                        def libEnv = [
                            "OMPI_ALLOW_RUN_AS_ROOT=1",
                            "OMPI_ALLOW_RUN_AS_ROOT_CONFIRM=1",
                            "PRTE_ALLOW_RUN_AS_ROOT=1",
                            "PRTE_ALLOW_RUN_AS_ROOT_CONFIRM=1",
                            "PMIX_HOSTNAME=mpi-node0",
                        ]
                        pipeline.echo "###### Check pip install Start ######"
                        pipeline.withEnv(libEnv) {
                            // Retry 2 times if timeout occurs.
                            pipeline.sh "env | sort"
                            pipeline.trtllm_utils.llmRetry(1, "checkPipInstall", {
                                pipeline.timeout(time: 30, unit: 'MINUTES') {
                                    pipeline.testBuild.checkPipInstall(pipeline, wheelPath)
                                }
                            })
                        }
                        pipeline.echo "###### Run LLMAPI tests Start ######"

                        // Resolve the real tensorrt_llm install location after pip install,
                        // and expose UCX shared libraries shipped inside the wheel
                        // (tensorrt_llm/libs/ucx/*.so and libtensorrt_llm_ucx_wrapper.so)
                        // so dlopen can find them at test runtime.
                        // Use `pip3 show` (metadata only) instead of `import tensorrt_llm`,
                        // because importing executes tensorrt_llm/__init__.py which prints a
                        // version banner to stdout and would pollute the captured path.
                        def trtllmLibsDir = pipeline.sh(
                            script: "pip3 show tensorrt_llm | grep \"Location\" | awk -F\":\" '{ gsub(/ /, \"\", \$2); print \$2\"/tensorrt_llm/libs\"}'",
                            returnStdout: true,
                        ).replaceAll("\\s","")
                        libEnv += ["LD_LIBRARY_PATH+trtllm_ucx=${trtllmLibsDir}/ucx"]
                        libEnv += ["LD_LIBRARY_PATH+trtllm_libs=${trtllmLibsDir}"]

                        def config = pipeline.VANILLA_CONFIG
                        if (cpu_arch == pipeline.AARCH64_TRIPLE) {
                            config = pipeline.LINUX_AARCH64_CONFIG
                        }
                        pipeline.withEnv(libEnv) {
                            pipeline.sh "env | sort"
                            pipeline.testPlatform.runLLMTestlistOnPlatform(pipeline, gpu_type, "l0_sanity_check", config, toStageName(values[1], key), 1, 1, true, "-SubJob-RunTest" + attemptTag, isFinalAttempt, retryContext)
                        }
                    })
                }
            }
        }, {}, true)
    }]}

    // OnDemand stages are available through --stage-list/--extra-stage only.
    pipeline.multiGpuJobs = pipeline.parallelJobs.findAll{(it.key =~ /\d+_GPUs/) && !it.key.contains("Post-Merge") && !it.key.contains("-OnDemand-")}
    pipeline.println pipeline.multiGpuJobs.keySet()
    pipeline.multiGpuJobsPostMerge = pipeline.parallelJobs.findAll{(it.key =~ /\d+_GPUs/) && it.key.contains("Post-Merge")}

    pipeline.parallelJobs += pipeline.docBuildJobs
    pipeline.parallelJobs += pipeline.sanityCheckJobs
    pipeline.parallelJobs += pipeline.agentFlowTestJobs

    pipeline.onDemandJobs = pipeline.parallelJobs.findAll {it.key.contains("-OnDemand-")}
    pipeline.postMergeJobs = pipeline.parallelJobs.findAll {it.key.contains("Post-Merge")}

    // Start as a normal pre-merge job
    pipeline.parallelJobsFiltered = pipeline.parallelJobs - pipeline.multiGpuJobs - pipeline.postMergeJobs - pipeline.onDemandJobs

    // Check if the multi GPU related file has changed or not. If changed, add multi GPU test stages.
    if (testFilter[(pipeline.MULTI_GPU_FILE_CHANGED)]) {
        pipeline.parallelJobsFiltered += pipeline.multiGpuJobs
    }

    if (testFilter[(pipeline.AUTO_TRIGGER_TAG_LIST)]) {
        pipeline.echo "AUTO_TRIGGER_TAG_LIST mode is true. Auto trigger tags: ${testFilter[(pipeline.AUTO_TRIGGER_TAG_LIST)].join(', ')}."
        def autoTriggerTagStages = [:]
        for (tag in testFilter[(pipeline.AUTO_TRIGGER_TAG_LIST)]) {
            autoTriggerTagStages += (pipeline.parallelJobs - pipeline.onDemandJobs).findAll { it.key.contains(tag) }
        }
        pipeline.parallelJobsFiltered += autoTriggerTagStages
        if (autoTriggerTagStages.size() > 0) {
            pipeline.echo "Auto trigger will force run stages: ${autoTriggerTagStages.keySet().join(', ')}."
        }
        pipeline.println pipeline.parallelJobsFiltered.keySet()
    }

    // Check --post-merge or post-merge pipelines.
    // If true, add post-merge only test stages and multi-GPU test stages.
    if (testFilter[(pipeline.IS_POST_MERGE)]) {
        pipeline.parallelJobsFiltered += pipeline.multiGpuJobs
        pipeline.parallelJobsFiltered += pipeline.postMergeJobs
    }

    // Check --skip-test, only run doc build and sanity check stages.
    if (testFilter[(pipeline.ENABLE_SKIP_TEST)]) {
        pipeline.echo "All test stages are skipped."
        pipeline.parallelJobsFiltered = pipeline.docBuildJobs + pipeline.sanityCheckJobs
    }

    // Check --add-multi-gpu-test, if true, add multi-GPU test stages back.
    if (testFilter[(pipeline.ADD_MULTI_GPU_TEST)]) {
        pipeline.parallelJobsFiltered += pipeline.multiGpuJobs
    }

    // Check --only-multi-gpu-test, if true, only run multi-GPU test stages.
    if (testFilter[(pipeline.ONLY_MULTI_GPU_TEST)]) {
        if (testFilter[(pipeline.IS_POST_MERGE)]) {
            pipeline.parallelJobsFiltered = pipeline.multiGpuJobsPostMerge
        } else {
            pipeline.parallelJobsFiltered = pipeline.multiGpuJobs
        }
    }

    // Check --disable-multi-gpu-test, if true, remove multi-GPU test stages.
    if (testFilter[(pipeline.DISABLE_MULTI_GPU_TEST)]) {
        pipeline.parallelJobsFiltered -= pipeline.multiGpuJobs
    }

    // Check --gpu-type, filter test stages.
    if (testFilter[(pipeline.GPU_TYPE_LIST)] != null) {
        pipeline.echo "Use GPU_TYPE_LIST for filtering. GPU types: ${testFilter[(pipeline.GPU_TYPE_LIST)]}."
        pipeline.parallelJobsFiltered = pipeline.parallelJobsFiltered.findAll {it.key.tokenize('-')[0] in testFilter[(pipeline.GPU_TYPE_LIST)]}
        pipeline.println pipeline.parallelJobsFiltered.keySet()
    }

    // Check --backend-mode, filter test stages.
    if (testFilter[(pipeline.TEST_BACKEND)] != null) {
        pipeline.echo "Use TEST_BACKEND for filtering. Backend mode: ${testFilter[(pipeline.TEST_BACKEND)]}."
        def backendMode = testFilter[(pipeline.TEST_BACKEND)].collect { it.toLowerCase() }
        def changeMap = [
            "pytorch": "-PyTorch-",
            "cpp": "-CPP-",
            "triton": "-Triton-",
            "fmha": "-FMHA-",
        ]
        def backendModeList = backendMode.collect { changeMap.get(it) }.flatten()
        def parallelJobsNoBackend = pipeline.parallelJobsFiltered.findAll { key, _ ->
            !changeMap.values().any { backend -> key.contains(backend) }
        }
        def parallelJobsBackendMode = pipeline.parallelJobsFiltered.findAll { key, _ ->
            backendModeList.any { backend -> key.contains(backend) }
        }
        pipeline.parallelJobsFiltered = parallelJobsNoBackend + parallelJobsBackendMode
        pipeline.echo "parallelJobsBackendMode: ${parallelJobsBackendMode.keySet()}"
        pipeline.println pipeline.parallelJobsFiltered.keySet()
    }

    if (testFilter[(pipeline.ONLY_ONE_GROUP_CHANGED)] == "Docs") {
        pipeline.echo "Only docs files are changed, run doc build stage only."
        pipeline.parallelJobsFiltered = pipeline.docBuildJobs
        pipeline.println pipeline.parallelJobsFiltered.keySet()
    } else if (testFilter[(pipeline.ONLY_ONE_GROUP_CHANGED)] != "") {
        if (testFilter[(pipeline.TEST_BACKEND)] != null) {
            pipeline.echo "Force disable ONLY_ONE_GROUP_CHANGED mode. Backend mode set by flag: ${testFilter[(pipeline.TEST_BACKEND)]}."
        } else {
            pipeline.echo "ONLY_ONE_GROUP_CHANGED mode is true. The group is: ${testFilter[(pipeline.ONLY_ONE_GROUP_CHANGED)]}."
            def excludedBackends = new HashMap()
            excludedBackends["PyTorch"] = ["-CPP-", "-FMHA-"]     // Only pytorch file change also need to run triton tests
            excludedBackends["Triton"] = ["-PyTorch-", "-CPP-", "-FMHA-"]
            excludedBackends["FMHA"] = ["-PyTorch-", "-CPP-", "-Triton-"]
            def group = testFilter[(pipeline.ONLY_ONE_GROUP_CHANGED)]
            if (excludedBackends.containsKey(group)) {
                pipeline.parallelJobsFiltered = pipeline.parallelJobsFiltered.findAll { key, value ->
                    !excludedBackends[group].any { backend -> key.contains(backend) }
                }
            }
            pipeline.println pipeline.parallelJobsFiltered.keySet()
        }
    }

    // Keep manually triggered stages out of every automatic selection path.
    pipeline.parallelJobsFiltered -= pipeline.onDemandJobs

    // Check --stage-list, only run the stages in stage-list. Supports wildcard '*'.
    if (testFilter[pipeline.TEST_STAGE_LIST] != null) {
        pipeline.echo "Use TEST_STAGE_LIST for filtering. Stages: ${testFilter[(pipeline.TEST_STAGE_LIST)]}."
        pipeline.parallelJobsFiltered = pipeline.parallelJobs.findAll { pipeline.stagePatterns.stageMatchesAnyPattern(it.key, testFilter[(pipeline.TEST_STAGE_LIST)]) }
        pipeline.println pipeline.parallelJobsFiltered.keySet()
    }

    // Check --extra-stage, add the stages in extra-stage. Supports wildcard '*'.
    if (testFilter[pipeline.EXTRA_STAGE_LIST] != null) {
        pipeline.echo "Use EXTRA_STAGE_LIST for filtering. Stages: ${testFilter[(pipeline.EXTRA_STAGE_LIST)]}."
        pipeline.parallelJobsFiltered += pipeline.parallelJobs.findAll { pipeline.stagePatterns.stageMatchesAnyPattern(it.key, testFilter[(pipeline.EXTRA_STAGE_LIST)]) }
        pipeline.println pipeline.parallelJobsFiltered.keySet()
    }

    pipeline.testSelection.checkStageName(pipeline.fullSet)

    if (testFilter[(pipeline.TEST_STAGE_LIST)] != null) {
        pipeline.testSelection.checkStageNameSet(testFilter[(pipeline.TEST_STAGE_LIST)], pipeline.fullSet, pipeline.TEST_STAGE_LIST)
    }
    if (testFilter[(pipeline.EXTRA_STAGE_LIST)] != null) {
        pipeline.testSelection.checkStageNameSet(testFilter[(pipeline.EXTRA_STAGE_LIST)], pipeline.fullSet, pipeline.EXTRA_STAGE_LIST)
    }

    pipeline.parallelJobsFiltered = pipeline.testSelection.filterCbtsStageJobs(
        pipeline.parallelJobs, pipeline.parallelJobsFiltered, pipeline.multiGpuJobs, testFilter)

    if (globalVars[pipeline.RUN_MODE] == "nightly_release") {
        pipeline.parallelJobsFiltered = pipeline.sanityCheckJobs
    }

    pipeline.echo "Check the passed GitLab bot testFilter parameters."
    def keysStr = pipeline.parallelJobsFiltered.keySet().join(",\n")
    pipeline.echo "Now we will run stages: [\n${keysStr}\n]"
    def maintenanceConfig = globalVars[pipeline.MAINTENANCE_ENTRIES] ?: [reason: "", patterns: []]
    def maintenancePatterns = maintenanceConfig.patterns ?: []
    pipeline.testSelection.warnUnmatchedMaintenancePatterns(
        pipeline.parallelJobsFiltered.keySet(), maintenancePatterns)

    // Per-stage execution scope for infra-scoped fail-fast (runBranchesWithInfraDefer).
    // A stage carrying opts.slurmDispatcher runs its work through a SLURM dispatcher
    // pod, so its post-retry failures can be SLURM-scoped; everything else is K8s.
    // Built here, where the config tuple's opts (3rd element) is still visible, and
    // keyed by stage name so the K8s/SLURM group subsets can look each branch up.
    pipeline.stageInfraScope = [:]
    pipeline.parallelJobsFiltered = pipeline.parallelJobsFiltered.collectEntries { key, values ->
        def stageOpts = (values instanceof List && values.size() >= 3 && values[2] instanceof Map) ? values[2] : [:]
        pipeline.stageInfraScope[key] = stageOpts.slurmDispatcher ? InfraFailure.SLURM : InfraFailure.K8S
        [key, {
        pipeline.stage(key) {
            if (key in testFilter[pipeline.REUSE_STAGE_LIST]) {
                pipeline.stage("Skip - Reused") {
                    pipeline.echo "Skip - Passed in the previous pipelines."
                }
            } else if (pipeline.stagePatterns.stageMatchesAnyPattern(key, maintenancePatterns)) {
                pipeline.stage("Skip - Maintenance") {
                    pipeline.testSelection.failMaintenanceStage(key, maintenanceConfig.reason)
                }
            } else if (values instanceof List) {
                // parallelJobs entries are either [podSpec, runner] or
                // [podSpec, runner, opts] where opts is a Map passed through
                // to runKubernetesPodWithInfraRetry (e.g. singleAttempt:true
                // for SLURM dispatcher pods to disable nested pod retry).
                def opts = (values.size() >= 3 && values[2] instanceof Map) ? values[2] : [:]
                pipeline.testRuntime.runKubernetesPodWithInfraRetry(opts, pipeline, values[0], "trt-llm", key, { attemptTag, isFinalAttempt, retryContext = null ->
                    // Carry a per-stage infra-retry override (opts.infraRetryMax) to the
                    // inner runner via retryContext -- the SLURM dispatcher's runner reads
                    // it and passes it to runLLMTestlistOnSlurm. Preserve the null default
                    // when no override is set so non-SLURM runners are unaffected.
                    def innerRetryContext = retryContext
                    if (opts.infraRetryMax != null) {
                        innerRetryContext = (retryContext ?: [:]) + [infraRetryMax: opts.infraRetryMax]
                    }
                    values[1](attemptTag, isFinalAttempt, innerRetryContext)
                })
            } else {
                values()
            }
        }
    }]}

    return pipeline.parallelJobsFiltered
}

return this
