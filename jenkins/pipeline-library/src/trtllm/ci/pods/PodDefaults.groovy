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

package trtllm.ci.pods

class PodDefaults {
    static Map values(Map overrides = [:])
    {
        def timeout = overrides.podTimeoutSeconds
        def modelExpressVersion = overrides.get('MODEL_EXPRESS_VERSION', '0.5.1')
        def defaults = [
            ARTIFACTORY_IMAGE_PULL_SECRET: "trtllm-artifactory",
            MODEL_EXPRESS_VERSION: modelExpressVersion,
            MODEL_EXPRESS_SERVER_IMAGE: "urm.nvidia.com/docker/nvidia/ai-dynamo/modelexpress-server:${modelExpressVersion}",
            MODEL_EXPRESS_REDIS_IMAGE: "artifactory.pdx.nvidia.com/docker-remote/redis:7-alpine",
            BUILD_CORES_REQUEST: "8",
            BUILD_CORES_LIMIT: "8",
            BUILD_MEMORY_REQUEST: "48Gi",
            BUILD_MEMORY_LIMIT: "96Gi",
            SLURM_CORES_REQUEST: "1",
            SLURM_CORES_LIMIT: "1",
            SLURM_MEMORY_REQUEST: "8Gi",
            SLURM_MEMORY_LIMIT: "12Gi",
            TESTER_CORES: "12",
            TESTER_MEMORY: "96Gi",
            TESTER_CPU_ONLY_CORES: "2",
            TESTER_CPU_ONLY_MEMORY: "12Gi",
            REQUIRED_OPEN_DRIVER_TYPES: ["b100-ts2", "rtx-5080", "rtx-5090", "rtx-pro-6000", "rtx-pro-6000d"],
            REQUIRED_NO_DRIVER_TYPES: ["dgx-h100", "dgx-h200", "gh200", "gb10x"],
            POD_TIMEOUT_SECONDS_TEST: timeout ?: "21600",
            POD_TIMEOUT_SECONDS_BUILD: timeout ?: "43200",
            POD_TIMEOUT_SECONDS_SLURM: timeout ?: "79200",
        ]
        return defaults + overrides
    }
}
