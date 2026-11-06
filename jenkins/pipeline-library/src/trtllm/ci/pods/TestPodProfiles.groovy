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

class TestPodProfiles {
    static Map profile(image, type, arch, gpuCount, modelExpress, Map cfg)
    {
        def targetCloud = "kubernetes-cpu"
        def selectors = """
                  nvidia.com/node_type: builder
                  kubernetes.io/arch: ${arch}
                  kubernetes.io/os: linux"""
        def containerConfig = ""
        def nodeLabelPrefix = ""
        def tolerations = ""
        def extraDeviceEnv = ""
        def serviceContainerConfig = ""


        switch(type)
        {
        case "agent":
            containerConfig = PodFactory.agentContainer("10Gi")
            nodeLabelPrefix = "cpu"
            break
        case "slurm":
            containerConfig = """
                  - name: trt-llm
                    image: ${image}
                    command: ['sleep', ${cfg.POD_TIMEOUT_SECONDS_SLURM}]
                    tty: true
                    resources:
                      requests:
                        cpu: ${cfg.SLURM_CORES_REQUEST}
                        memory: ${cfg.SLURM_MEMORY_REQUEST}
                        ephemeral-storage: 100Gi
                      limits:
                        cpu: ${cfg.SLURM_CORES_LIMIT}
                        memory: ${cfg.SLURM_MEMORY_LIMIT}
                        ephemeral-storage: 100Gi
                    imagePullPolicy: Always"""
            nodeLabelPrefix = "cpu"
            break
        case "build":
            containerConfig = """
                  - name: trt-llm
                    image: ${image}
                    command: ['sleep', ${cfg.POD_TIMEOUT_SECONDS_BUILD}]
                    volumeMounts:
                    - name: sw-tensorrt-pvc
                      mountPath: "/mnt/sw-tensorrt-pvc"
                      readOnly: false
                    tty: true
                    resources:
                      requests:
                        cpu: ${cfg.BUILD_CORES_REQUEST}
                        memory: ${cfg.BUILD_MEMORY_REQUEST}
                        ephemeral-storage: 200Gi
                      limits:
                        cpu: ${cfg.BUILD_CORES_LIMIT}
                        memory: ${cfg.BUILD_MEMORY_LIMIT}
                        ephemeral-storage: 200Gi
                    imagePullPolicy: Always"""
            nodeLabelPrefix = "cpu"
            break
        case "cpu":
            containerConfig = """
                  - name: trt-llm
                    image: ${image}
                    command: ['sleep', ${cfg.POD_TIMEOUT_SECONDS_TEST}]
                    tty: true
                    resources:
                      requests:
                        cpu: ${cfg.TESTER_CPU_ONLY_CORES}
                        memory: ${cfg.TESTER_CPU_ONLY_MEMORY}
                        ephemeral-storage: 300Gi
                      limits:
                        cpu: ${cfg.TESTER_CPU_ONLY_CORES}
                        memory: ${cfg.TESTER_CPU_ONLY_MEMORY}
                        ephemeral-storage: 300Gi
                    imagePullPolicy: Always
                    volumeMounts:
                    - name: dshm
                      mountPath: /dev/shm
                    - name: scratch-trt-llm-data
                      mountPath: /scratch.trt_llm_data
                      readOnly: true
                    - name: sw-tensorrt-pvc
                      mountPath: "/mnt/sw-tensorrt-pvc"
                      readOnly: false
                    securityContext:
                      capabilities:
                        add:
                        - SYS_ADMIN"""
            nodeLabelPrefix = "cpu"
            break
        default:
            def hasMultipleGPUs = (gpuCount > 1)
            def memorySize = "${cfg.TESTER_MEMORY}"
            def storageSize = "300Gi"
            def driverVersion = cfg.REQUIRED_OPEN_DRIVER_TYPES.any { type.contains(it) } ? cfg.defaultOpenDriverVersion : cfg.defaultDriverVersion
            def cpuCount = "${cfg.TESTER_CORES}"

            if (hasMultipleGPUs)
            {
                // Not a hard requirement, but based on empirical values.
                // Keep ModelExpress services inside the existing pod resource envelope;
                // otherwise their requests can make an otherwise valid GPU pod unschedulable.
                def serviceCpuReserve = modelExpress ? 6 : 0
                def serviceMemoryReserveGi = modelExpress ? 16 : 0
                def serviceStorageReserveGi = modelExpress ? 8 : 0
                memorySize = "${gpuCount * 150 - serviceMemoryReserveGi}" + "Gi"
                storageSize = "${gpuCount * 150 - serviceStorageReserveGi}" + "Gi"
                cpuCount = "${gpuCount * 12 - serviceCpuReserve}"
            }

            def gpuType = cfg.gpuType
            nodeLabelPrefix = type

            targetCloud = "kubernetes"
            // DGX Spark requires a special setting for accessing the device.
            // It has 128GB unified memory as per spec. Use half of the memory at the CPU side.
            if (type.contains("gb10x")) {
                targetCloud = "nvks-sparks-cloud"
                memorySize = "64Gi"
                tolerations = """
                tolerations:
                - key: "node_for_blossom_trt"
                  operator: "Exists"
                  effect: "NoSchedule"
            """
                extraDeviceEnv = """
                    - name: NVIDIA_VISIBLE_DEVICES
                      value: "all"
                    - name: NVIDIA_DRIVER_CAPABILITIES
                      value: "compute,utility"
            """
            }

            // The following GPU types doesn't support dynamic driver flashing.
            if (cfg.REQUIRED_NO_DRIVER_TYPES.any { type.contains(it) }) {
                if (type.contains("gb10x")) {
                    selectors = """
                    kubernetes.io/arch: ${arch}
                    kubernetes.io/os: linux
                    nvidia.com/gpu.machine: NVIDIA_DGX_Spark
                    nvidia.com/tenant: blossom_trt"""
                } else {
                    selectors = """
                    kubernetes.io/arch: ${arch}
                    kubernetes.io/os: linux
                    nvidia.com/gpu_type: ${gpuType}"""
                }
            } else {
                selectors = """
                    kubernetes.io/arch: ${arch}
                    kubernetes.io/os: linux
                    nvidia.com/gpu_type: ${gpuType}
                    nvidia.com/driver_version: '${driverVersion}'"""
            }

            containerConfig = """
                  - name: trt-llm
                    image: ${image}
                    command: ['sleep', ${cfg.POD_TIMEOUT_SECONDS_TEST}]
                    tty: true
                    resources:
                      requests:
                        cpu: ${cpuCount}
                        memory: ${memorySize}
                        nvidia.com/gpu: ${gpuCount}
                        ephemeral-storage: ${storageSize}
                      limits:
                        cpu: ${cpuCount}
                        memory: ${memorySize}
                        nvidia.com/gpu: ${gpuCount}
                        ephemeral-storage: ${storageSize}
                    imagePullPolicy: Always
                    volumeMounts:
                    - name: dshm
                      mountPath: /dev/shm
                    - name: scratch-trt-llm-data
                      mountPath: /scratch.trt_llm_data
                      readOnly: true
                    - name: sw-tensorrt-pvc
                      mountPath: "/mnt/sw-tensorrt-pvc"
                      readOnly: false
                    securityContext:
                      capabilities:
                        add:
                        - SYS_ADMIN
                        - SYS_PTRACE"""
            break
        }
        if (modelExpress) {
            if (arch != "amd64") {
                throw new Exception("ModelExpress CI sidecars currently support amd64 test pods only.")
            }
            extraDeviceEnv += """
                    - name: MODEL_EXPRESS_URL
                      value: "http://127.0.0.1:8001"
                    - name: TRTLLM_MX_E2E_REQUIRED
                      value: "1"
        """
            // Mirrors the ModelExpress Redis deployment and image contract.
            // The image exposes /app/modelexpress-server and accepts the port/backend settings below.
            // Use regular containers because the Jenkins Kubernetes launcher does not
            // reliably attach to pods containing restartable init-container sidecars.
            // The server waits for Redis, and the E2E preflight waits for port 8001.
            serviceContainerConfig = """
                  - name: redis
                    image: ${cfg.MODEL_EXPRESS_REDIS_IMAGE}
                    args: ["--save", "", "--appendonly", "no"]
                    ports:
                    - containerPort: 6379
                    resources:
                      requests:
                        cpu: '1'
                        memory: 4Gi
                        ephemeral-storage: 2Gi
                      limits:
                        cpu: '1'
                        memory: 4Gi
                        ephemeral-storage: 2Gi
                    imagePullPolicy: Always
                  - name: model-express-server
                    image: ${cfg.MODEL_EXPRESS_SERVER_IMAGE}
                    command: ["/bin/bash", "-c"]
                    args:
                    - |
                      until (echo > /dev/tcp/127.0.0.1/6379) >/dev/null 2>&1; do
                        sleep 1
                      done
                      exec /app/modelexpress-server --port 8001
                    env:
                    - name: MX_METADATA_BACKEND
                      value: "redis"
                    - name: REDIS_URL
                      value: "redis://127.0.0.1:6379"
                    ports:
                    - containerPort: 8001
                    resources:
                      requests:
                        cpu: '4'
                        memory: 8Gi
                        ephemeral-storage: 4Gi
                      limits:
                        cpu: '4'
                        memory: 8Gi
                        ephemeral-storage: 4Gi
                    imagePullPolicy: Always
        """
        }
        // CPU test pods mount the model scratch from 10.117.145.14, which can't be accessed from ipp6/ipp9 DC.
        // Pods placed there stay in ContainerCreating. Build stage pods do not mount the model scratch,
        // so they can still use these nodes.
        def scratchUnreachableNodeAffinity = type == "cpu" ? '''
                              - key: "region"
                                operator: NotIn
                                values:
                                - "ipp6"
                                - "ipp9"''' : ""
        def pvcVolume = """
                - name: sw-tensorrt-pvc
                  persistentVolumeClaim:
                    claimName: sw-tensorrt-pvc
    """
        if (arch == "arm64") {
            // PVC mount isn't supported on aarch64 platform. Use NFS as a WAR.
            pvcVolume = """
                - name: sw-tensorrt-pvc
                  nfs:
                    server: 10.117.145.13
                    path: /vol/scratch1/scratch.svc_tensorrt_blossom
        """
        }
        def llmModelVolume = """
                - name: scratch-trt-llm-data
                  nfs:
                    server: 10.117.145.14
                    path: /vol/scratch1/scratch.michaeln_blossom
    """

        // RTXPro6000D and RTX5080 use the Austin model scratch.
        if (type.contains("6000d") || type.contains("rtx-5080")) {
            llmModelVolume = """
                - name: scratch-trt-llm-data
                  nfs:
                    server: 10.59.164.111
                    path: /vol/scratch58/scratch.trt_llm_data
        """
        }

        return [
            cloud: targetCloud, labelPrefix: nodeLabelPrefix,
            selectors: selectors, containerConfig: containerConfig,
            jnlpMemory: "10Gi",
            affinitySuffix: "${scratchUnreachableNodeAffinity}\n",
            containerSuffix: "                    ${extraDeviceEnv}\n                  ${serviceContainerConfig}\n",
            volumeBlock: """                - name: dshm
                  emptyDir:
                    medium: Memory
                ${llmModelVolume}
                ${pvcVolume}
                ${tolerations}
""",
        ]
    }
}
