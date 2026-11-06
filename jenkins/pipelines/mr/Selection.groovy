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

def getAutoTriggerTagList(pipeline, testFilter, globalVars) {
    def autoTriggerTagList = []
    def isOfficialPostMergeJob = (pipeline.env.JOB_NAME ==~ /.*PostMerge.*/)
    if (isOfficialPostMergeJob) {
        pipeline.echo("Force set auto trigger tags to empty list.")
        return autoTriggerTagList
    }
    def changedFileList = pipeline.mrChanges.getMergeRequestChangedFileList(pipeline, globalVars)
    if (!changedFileList || changedFileList.isEmpty()) {
        return autoTriggerTagList
    }
    def specialFileToTagMap = [
        "tensorrt_llm/_torch/models/modeling_deepseekv3.py": ["-DeepSeek-"],
        "tests/integration/defs/triton_server/": ["-Triton-"],
        "triton_backend/": ["-Triton-"],
        "cpp/kernels/fmha_v2/": ["-FMHA-"],
        "tensorrt_llm/_torch/models/modeling_gpt_oss.py": ["-GptOss-"],
    ]
    for (file in changedFileList) {
        for (String key : specialFileToTagMap.keySet()) {
            if (file.startsWith(key)) {
                autoTriggerTagList += specialFileToTagMap[key]
            }
        }
    }
    autoTriggerTagList = autoTriggerTagList.unique()
    if (!autoTriggerTagList.isEmpty()) {
        pipeline.echo("Auto trigger tags detected: ${autoTriggerTagList.join(', ')}")
    }
    return autoTriggerTagList
}

// Returns true when the PR touches any file that requires @NVIDIA/trt-llm-oss-compliance review
// (mirrors the patterns in .github/CODEOWNERS under "OSS Compliance & Legal").
def getOssComplianceFileChanged(pipeline, globalVars)
{
    def isOfficialPostMergeJob = (pipeline.env.JOB_NAME ==~ /.*PostMerge.*/)
    if (isOfficialPostMergeJob) {
        pipeline.echo("OSS compliance check: skipped (post-merge).")
        return false
    }

    // Exact file matches
    def exactFiles = [
        "LICENSE",
        "setup.py",
        "pyproject.toml",
        "requirements.txt",
        "requirements-dev.txt",
        "cpp/CMakeLists.txt",
        ".github/CODEOWNERS",
        "jenkins/license_cpp.json",
        "tensorrt_llm/usage/llm_args_golden_manifest.json",
    ] as Set

    // Prefix matches (directory trees and glob-style prefixes from CODEOWNERS)
    def prefixes = [
        "ATTRIBUTIONS-",
        "cpp/cmake/",
        "3rdparty/",
        "triton_kernels/",
        "docker/common/",
        "tensorrt_llm/usage/",
    ]

    def changedFileList = pipeline.mrChanges.getMergeRequestChangedFileList(pipeline, globalVars)
    if (!changedFileList || changedFileList.isEmpty()) {
        return false
    }

    def matched = changedFileList.find { file ->
        exactFiles.contains(file) || prefixes.any { prefix -> file.startsWith(prefix) }
    }
    if (matched) {
        pipeline.echo("OSS compliance file changed: ${matched}")
        return true
    }
    return false
}

def getMultiGpuFileChanged(pipeline, testFilter, globalVars)
{
    if (testFilter[(pipeline.DISABLE_MULTI_GPU_TEST)]) {
        pipeline.echo("Force not run multi-GPU testing.")
        return false
    }
    if (testFilter[(pipeline.ADD_MULTI_GPU_TEST)] || testFilter[(pipeline.ONLY_MULTI_GPU_TEST)] || testFilter[(pipeline.IS_POST_MERGE)]) {
        pipeline.echo("Force run multi-GPU testing.")
        return true
    }

    def relatedFileList = [
        "cpp/include/tensorrt_llm/batch_manager/",
        "cpp/include/tensorrt_llm/executor/",
        "cpp/include/tensorrt_llm/runtime/utils/mpiUtils.h",
        "cpp/include/tensorrt_llm/runtime/utils/multiDeviceUtils.h",
        "cpp/include/tensorrt_llm/runtime/worldConfig.h",
        "cpp/tensorrt_llm/batch_manager/",
        "cpp/tensorrt_llm/executor/",
        "cpp/tensorrt_llm/kernels/communicationKernels/",
        "cpp/tensorrt_llm/kernels/customAllReduceKernels.cu",
        "cpp/tensorrt_llm/kernels/customAllReduceKernels.h",
        "cpp/tensorrt_llm/kernels/fmhaDispatcher.cpp",
        "cpp/tensorrt_llm/kernels/fmhaDispatcher.h",
        "cpp/tensorrt_llm/kernels/gptKernels.cu",
        "cpp/tensorrt_llm/kernels/gptKernels.h",
        "cpp/tensorrt_llm/kernels/moe/communication/",
        "cpp/tensorrt_llm/kernels/moe/loadBalance/",
        "cpp/tensorrt_llm/kernels/moe/utils/moeAlignKernels",
        "cpp/tensorrt_llm/kernels/moeCommKernelsCommon.h",
        "cpp/tensorrt_llm/kernels/moeTopKFuncs.cuh",
        "cpp/tensorrt_llm/kernels/moe_utils.cuh",
        "cpp/tensorrt_llm/kernels/trtllmGenKernels/fmha/",
        "cpp/tensorrt_llm/kernels/unfusedAttentionKernels.cu",
        "cpp/tensorrt_llm/kernels/unfusedAttentionKernels.h",
        "cpp/tensorrt_llm/kernels/userbuffers/",
        "cpp/tensorrt_llm/kernels/xqaDispatcher.cpp",
        "cpp/tensorrt_llm/kernels/xqaDispatcher.h",
        "cpp/tensorrt_llm/nanobind/",
        "cpp/tensorrt_llm/runtime/ipcUtils.cpp",
        "cpp/tensorrt_llm/runtime/ncclCommunicator.cpp",
        "cpp/tensorrt_llm/runtime/utils/mpiUtils.cpp",
        "cpp/tensorrt_llm/runtime/workerPool.h",
        "cpp/tensorrt_llm/runtime/worldConfig.cpp",
        "cpp/tensorrt_llm/thop/allgatherOp.cpp",
        "cpp/tensorrt_llm/thop/allreduceOp.cpp",
        "cpp/tensorrt_llm/thop/reducescatterOp.cpp",
        "cpp/tests/unit_tests/multi_gpu/",
        "jenkins/L0_Test.groovy",
        "jenkins/pipelines/test/",
        "jenkins/pipelines/common/",
        "requirements.txt",
        "security_scanning/pyproject.toml",
        "tensorrt_llm/_ipc_utils.py",
        "tensorrt_llm/_torch/compilation/patterns/ar_residual_norm.py",
        "tensorrt_llm/_torch/compilation/patterns/ub_allreduce.py",
        "tensorrt_llm/_torch/custom_ops/torch_custom_ops.py",
        "tensorrt_llm/_torch/custom_ops/userbuffers_custom_ops.py",
        "tensorrt_llm/_torch/distributed/",
        "tensorrt_llm/_torch/models/modeling_llama.py",
        "tensorrt_llm/_torch/models/modeling_qwen3_next.py",
        "tensorrt_llm/_torch/moe/",
        "tensorrt_llm/_torch/pyexecutor/_util.py",
        "tensorrt_llm/_torch/pyexecutor/cuda_graph_runner.py",
        "tensorrt_llm/_torch/pyexecutor/model_engine.py",
        "tensorrt_llm/_torch/pyexecutor/py_executor.py",
        "tensorrt_llm/_torch/weight_sharing/",
        "tensorrt_llm/_torch/visual_gen/attention/backends/parallel.py",
        "tensorrt_llm/_torch/visual_gen/modules/vae/",
        "tensorrt_llm/_torch/visual_gen/attention/attention.py",
        "tensorrt_llm/_torch/visual_gen/parallel/",
        "tensorrt_llm/_torch/visual_gen/executor.py",
        "tensorrt_llm/_torch/visual_gen/mapping.py",
        "tensorrt_llm/_torch/visual_gen/pipeline.py",
        "tensorrt_llm/_torch/visual_gen/pipeline_loader.py",
        "tensorrt_llm/_torch/visual_gen/models/wan/parallel_vae.py",
        "tensorrt_llm/visual_gen/",
        "tensorrt_llm/bench/benchmark/visual_gen.py",
        "tensorrt_llm/evaluate/json_mode_eval.py",
        "tensorrt_llm/evaluate/mmlu.py",
        "tensorrt_llm/executor/",
        "tensorrt_llm/functional.py",
        "tensorrt_llm/inputs/registry.py",
        "tensorrt_llm/inputs/tokenization.py",
        "tensorrt_llm/llmapi/disagg_utils.py",
        "tensorrt_llm/llmapi/mgmn_leader_node.py",
        "tensorrt_llm/llmapi/mgmn_worker_node.py",
        "tensorrt_llm/llmapi/mpi_session.py",
        "tensorrt_llm/llmapi/trtllm-llmapi-launch",
        "tensorrt_llm/mapping.py",
        "tensorrt_llm/models/llama/",
        "tensorrt_llm/parameter.py",
        "tensorrt_llm/serve/__init__.py",
        "tensorrt_llm/serve/cluster_storage.py",
        "tensorrt_llm/serve/disagg_auto_scaling.py",
        "tensorrt_llm/serve/extensions/",
        "tensorrt_llm/serve/metadata_server.py",
        "tensorrt_llm/serve/openai_client.py",
        "tensorrt_llm/serve/openai_disagg_server.py",
        "tensorrt_llm/serve/openai_disagg_service.py",
        "tensorrt_llm/serve/openai_server.py",
        // The shared prompt-preparation core and its callers (chat, completions,
        // multimodal-encoder routes, count_tokens, KV-cache governor, Responses, router).
        "tensorrt_llm/serve/render/",
        "tensorrt_llm/serve/resource_governor.py",
        "tensorrt_llm/serve/responses_utils.py",
        "tensorrt_llm/serve/router.py",
        "tensorrt_llm/serve/router_utils.py",
        "tensorrt_llm/serve/serving_extensions.py",
        "tests/integration/defs/cpp/test_multi_gpu.py",
        "tests/integration/defs/model_express/",
        "tests/integration/test_lists/test-db/l0_b200_multi_gpus_perf_sanity.yml",
        "tests/integration/test_lists/test-db/l0_b200_visual_gen_perf_sanity.yml",
        "tests/integration/test_lists/test-db/l0_dgx_b200.yml",
        "tests/integration/test_lists/test-db/l0_dgx_b300.yml",
        "tests/integration/test_lists/test-db/l0_dgx_b300_efa_multi_nodes.yml",
        "tests/integration/defs/perf/test_efa_disaggregated.py",
        "tests/test_common/efa_validation.py",
        "tests/scripts/perf-sanity/disaggregated/b300_qwen3_0.6b_efa_functional.yaml",
        "tests/integration/test_lists/test-db/l0_dgx_h100.yml",
        "tests/integration/test_lists/test-db/l0_dgx_h200.yml",
        "tests/integration/test_lists/test-db/l0_dgx_h200_perf_sanity.yml",
        "tests/integration/test_lists/test-db/l0_gb200_multi_gpus.yml",
        "tests/integration/test_lists/test-db/l0_gb200_multi_gpus_perf_sanity.yml",
        "tests/integration/test_lists/test-db/l0_gb200_multi_nodes.yml",
        "tests/integration/test_lists/test-db/l0_gb200_multi_nodes_perf_sanity_ctx1_node1_gpu1_gen1_node1_gpu4.yml",
        "tests/integration/test_lists/test-db/l0_gb200_multi_nodes_perf_sanity_ctx1_node1_gpu1_gen1_node2_gpu8.yml",
        "tests/integration/test_lists/test-db/l0_gb200_multi_nodes_perf_sanity_ctx1_node2_gpu8_gen1_node8_gpu32.yml",
        "tests/integration/test_lists/test-db/l0_gb200_multi_nodes_perf_sanity_node2_gpu8.yml",
        "tests/integration/test_lists/test-db/l0_gb300.yml",
        "tests/integration/test_lists/test-db/l0_gb300_multi_gpus.yml",
        "tests/integration/test_lists/test-db/l0_gb300_multi_gpus_perf_sanity.yml",
        "tests/integration/test_lists/test-db/l0_gb300_multi_nodes_node2_gpu8.yml",
        "tests/integration/test_lists/test-db/l0_gb300_multi_nodes_node4_gpu16.yml",
        "tests/integration/test_lists/test-db/l0_gb300_multi_nodes_perf_sanity_ctx1_node1_gpu2_gen1_node2_gpu8.yml",
        "tests/integration/test_lists/test-db/l0_gb300_multi_nodes_perf_sanity_ctx1_node1_gpu2_gen1_node8_gpu32.yml",
        "tests/integration/test_lists/test-db/l0_model_express.yml",
        "tests/integration/test_lists/test-db/l0_rtx_pro_6000.yml",
        "tests/unittest/_torch/multi_gpu/",
        "tests/unittest/_torch/moe/multi_gpu/",
        "tests/unittest/_torch/multi_gpu_modeling/",
        "tests/unittest/_torch/visual_gen/multi_gpu/",
        "tests/unittest/disaggregated/",
        "tests/unittest/llmapi/test_llm_multi_gpu.py",
        "tests/unittest/llmapi/test_llm_multi_gpu_pytorch.py",
        "tests/integration/defs/accuracy/test_disaggregated_serving.py",
        "tests/unittest/_torch/ray_orchestrator/multi_gpu/",
        "tests/integration/defs/examples/test_ray.py",
        "tests/unittest/llmapi/test_async_llm.py",
        "docker/common/install_ucx.sh",
        "docker/common/install_nixl.sh",
    ]

    def changedFileList = pipeline.mrChanges.getMergeRequestChangedFileList(pipeline, globalVars)
    if (!changedFileList || changedFileList.isEmpty()) {
        return false
    }

    def changedFileListStr = ","
    def relatedFileChanged = false
    try {
        changedFileListStr = changedFileList.join(", ")
        relatedFileChanged = relatedFileList.any { it ->
            if (changedFileListStr.contains(it)) {
                return true
            }
        }
    }
    catch (InterruptedException e)
    {
        throw e
    }
    catch (Exception e)
    {
        pipeline.echo("getMultiGpuFileChanged failed execution. Error: ${e.toString()}")
    }
    if (relatedFileChanged) {
        pipeline.echo("Detect multi-GPU related files changed.")
    }
    return relatedFileChanged
}

def getOnlyOneGroupChanged(pipeline, testFilter, globalVars) {
    def isOfficialPostMergeJob = (pipeline.env.JOB_NAME ==~ /.*PostMerge.*/)
    if (isOfficialPostMergeJob) {
        pipeline.echo("Force set ONLY_ONE_GROUP_CHANGED \"\".")
        return ""
    }
    def groupFileMap = [
        "Docs": [
            // Matched by prefix here, plus any "*.md" file anywhere in the repo (handled below).
            "docs/",
        ],
        "PyTorch": [
            "tensorrt_llm/_torch/",
            "tensorrt_llm/scaffolding/",
            "tests/unittest/_torch/",
            "tests/unittest/scaffolding/",
            "tests/unittest/llmapi/test_llm_pytorch.py",
            "tests/unittest/llmapi/test_llm_multi_gpu_pytorch.py",
            "tests/integration/defs/accuracy/test_llm_api_pytorch.py",
            "tests/integration/defs/disaggregated/",
            "examples/disaggregated",
            "examples/pytorch/",
            "examples/scaffolding/",
            "docs/",
        ],
        "Triton": [
            "tests/integration/defs/triton_server/",
            "triton_backend/",
        ],
    ]

    def changedFileList = pipeline.mrChanges.getMergeRequestChangedFileList(pipeline, globalVars)
    if (!changedFileList || changedFileList.isEmpty()) {
        return ""
    }

    for (group in groupFileMap.keySet()) {
        def groupPrefixes = groupFileMap[group]
        def matchesGroup = { file ->
            // Any *.md file, anywhere in the repo, counts as Docs-only.
            if (group == "Docs" && file.endsWith(".md")) {
                return true
            }
            return groupPrefixes.any { prefix -> file.startsWith(prefix) }
        }
        def allFilesInGroup = changedFileList.every(matchesGroup)

        if (allFilesInGroup) {
            pipeline.echo("Only ${group} files changed.")
            return group
        } else {
            def nonGroupFile = changedFileList.find { file -> !matchesGroup(file) }
            if (nonGroupFile != null) {
                pipeline.echo("Found non-${group} file: ${nonGroupFile}")
            }
        }
    }

    return ""
}

return this
