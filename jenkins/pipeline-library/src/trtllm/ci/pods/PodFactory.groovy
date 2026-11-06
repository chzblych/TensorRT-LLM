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

class PodFactory implements Serializable {
    Map defaults(Map overrides = [:])
    {
        return PodDefaults.values(overrides)
    }

    Map mergeRequestPod(image, type, arch = "amd64", Map inputs = [:])
    {
        def cfg = defaults(inputs)
        def selectors = """
                  nvidia.com/node_type: builder
                  kubernetes.io/os: linux"""
        def containerConfig = ""
        def nodeLabelPrefix = ""

        switch(type)
        {
        case "agent":
            containerConfig = agentContainer("5Gi")
            nodeLabelPrefix = "cpu"
            break
        case "package":
            containerConfig = """
                  - name: trt-llm
                    image: ${image}
                    command: ['cat']
                    tty: true
                    resources:
                      requests:
                        cpu: '2'
                        memory: 20Gi
                        ephemeral-storage: 25Gi
                      limits:
                        cpu: '2'
                        memory: 20Gi
                        ephemeral-storage: 25Gi
                    imagePullPolicy: Always"""
            nodeLabelPrefix = "cpu"
            break
        }
        def profile = [
            cloud: "kubernetes-cpu", labelPrefix: nodeLabelPrefix,
            selectors: selectors, containerConfig: containerConfig,
            jnlpMemory: "5Gi", affinitySuffix: "", containerSuffix: "",
            volumeBlock: """                - name: sw-tensorrt-pvc
                  persistentVolumeClaim:
                    claimName: sw-tensorrt-pvc

""",
        ]
        return render(profile, arch, cfg)
    }

    Map testPod(image, type, arch = "amd64", gpuCount = 1, modelExpress = false, Map inputs = [:])
    {
        def cfg = defaults(inputs)
        return render(TestPodProfiles.profile(image, type, arch, gpuCount, modelExpress, cfg), arch, cfg)
    }

    static String agentContainer(memory)
    {
        return """
                  - name: alpine
                    image: artifactory.pdx.nvidia.com/docker-remote/alpine:latest
                    command: ['cat']
                    tty: true
                    resources:
                      requests:
                        cpu: '2'
                        memory: ${memory}
                        ephemeral-storage: 25Gi
                      limits:
                        cpu: '2'
                        memory: ${memory}
                        ephemeral-storage: 25Gi
                    imagePullPolicy: Always"""
    }

    private static Map render(Map profile, arch, Map cfg)
    {
        def archSuffix = arch == "arm64" ? "arm" : "amd"
        def jnlpImage = "artifactory.pdx.nvidia.com/sw-ipp-blossom-sre-docker-local/lambda/custom_jnlp_images_${archSuffix}_linux:jdk17"
        return [
            cloud: profile.cloud,
            namespace: "sw-tensorrt",
            label: profile.labelPrefix,
            yaml: """
            apiVersion: v1
            kind: Pod
            spec:
                qosClass: Guaranteed
                affinity:
                    nodeAffinity:
                        requiredDuringSchedulingIgnoredDuringExecution:
                            nodeSelectorTerms:
                            - matchExpressions:
                              - key: "tensorrt/taints"
                                operator: DoesNotExist
                              - key: "tensorrt/affinity"
                                operator: NotIn
                                values:
                                - "core"
                                - "qa_only"
${profile.affinitySuffix}                nodeSelector: ${profile.selectors}
                imagePullSecrets:
                  - name: ${cfg.ARTIFACTORY_IMAGE_PULL_SECRET}
                containers:
                  ${profile.containerConfig}
                    env:
                    - name: HOST_NODE_NAME
                      valueFrom:
                        fieldRef:
                          fieldPath: spec.nodeName
${profile.containerSuffix}                  - name: jnlp
                    image: ${jnlpImage}
                    args: ['\$(JENKINS_SECRET)', '\$(JENKINS_NAME)']
                    resources:
                      requests:
                        cpu: '2'
                        memory: ${profile.jnlpMemory}
                        ephemeral-storage: 25Gi
                      limits:
                        cpu: '2'
                        memory: ${profile.jnlpMemory}
                        ephemeral-storage: 25Gi
                qosClass: Guaranteed
                volumes:
${profile.volumeBlock}        """.stripIndent(),
        ]
    }
}
