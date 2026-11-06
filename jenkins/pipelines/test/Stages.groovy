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
@Field Map catalog

def initialize(pipeline, Map expandedCatalog)
{
    this.@pipeline = pipeline
    this.@catalog = expandedCatalog
    return this
}

def stageConfigs(String group)
{
    return this.@catalog[group].collectEntries { name, values -> [(name): new ArrayList(values)] }
}

def prepareX86TestJobs(pipeline)
{
    def x86TestConfigs = stageConfigs('x86TestConfigs')

    x86TestConfigs = pipeline.testSelection.cbtsResizeSplits(x86TestConfigs)
    def parallelJobs = x86TestConfigs.collectEntries{key, values -> [key, [pipeline.createKubernetesPodConfig(pipeline.LLM_DOCKER_IMAGE, values[0], "amd64", values[4] ?: 1, values.size() > 5 ? values[5] : false), { attemptTag, isFinalAttempt, retryContext = null ->
        def config = pipeline.VANILLA_CONFIG
        if (key.contains("single-device")) {
            config = pipeline.SINGLE_DEVICE_CONFIG
        }
        if (key.contains("llvm")) {
            config = pipeline.LLVM_CONFIG
        }
        pipeline.jenkinsRunner.runLLMTestlistOnPlatform(pipeline, values[0], values[1], config, key, values[2], values[3], false, attemptTag, isFinalAttempt, retryContext)
    }]]}
    def fullSet = parallelJobs.keySet()

    def x86SlurmTestConfigs = stageConfigs('x86SlurmTestConfigs')
    x86SlurmTestConfigs = pipeline.testSelection.cbtsResizeSplits(x86SlurmTestConfigs)
    fullSet += x86SlurmTestConfigs.keySet()

    def parallelSlurmJobs = x86SlurmTestConfigs.collectEntries{key, values -> [key, [pipeline.createKubernetesPodConfig(pipeline.X86_64_DOCKER_IMAGE, "slurm", "amd64"), { attemptTag, isFinalAttempt, retryContext = null ->
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
        pipeline.infraRetry.runLLMTestlistOnSlurm(pipeline, values[0], values[1], config, key, values[2], values[3], values[4] ?: 1, values[5] ?: 1, values[6] ?: false, false, attemptTag, values[7] ?: false, retryContext?.infraRetryMax)
    }, [singleAttempt: true, slurmDispatcher: true]]]}
    // SLURM dispatcher pods run their own inner retry loop
    // (runLLMTestlistOnSlurm with SLURM_INFRA_RETRY_MAX). Disabling the outer
    // K8s pod retry (singleAttempt:true) here caps total attempts at
    // SLURM_INFRA_RETRY_MAX+1 instead of (SLURM_INFRA_RETRY_MAX+1) *
    // (K8S_INFRA_RETRY_MAX+1). Each SLURM attempt that hits the partition
    // walltime burns ~4h, so nesting the two layers cost up to ~36h per stage
    // on consistently-timing-out tests before this cap.

    parallelJobs += parallelSlurmJobs
    return [jobs: parallelJobs, fullSet: fullSet]
}

def prepareSbsaTestJobs(pipeline, fullSet)
{
    // SBSA machines from the Blossom machine pool
    def SBSATestConfigs = stageConfigs('SBSATestConfigs')
    SBSATestConfigs = pipeline.testSelection.cbtsResizeSplits(SBSATestConfigs)
    fullSet += SBSATestConfigs.keySet()

    def SBSASlurmTestConfigs = stageConfigs('SBSASlurmTestConfigs')
    SBSASlurmTestConfigs = pipeline.testSelection.cbtsResizeSplits(SBSASlurmTestConfigs)
    fullSet += SBSASlurmTestConfigs.keySet()

    def multiNodesSBSAConfigs = stageConfigs('multiNodesSBSAConfigs')
    multiNodesSBSAConfigs = pipeline.testSelection.cbtsResizeSplits(multiNodesSBSAConfigs)
    fullSet += multiNodesSBSAConfigs.keySet()

    def parallelJobs = null
    if (pipeline.env.targetArch == pipeline.AARCH64_TRIPLE) {
        parallelJobs = SBSATestConfigs.collectEntries{key, values -> [key, [pipeline.createKubernetesPodConfig(pipeline.LLM_DOCKER_IMAGE, values[0], "arm64", values[4] ?: 1, values.size() > 5 ? values[5] : false), { attemptTag, isFinalAttempt, retryContext = null ->
            pipeline.jenkinsRunner.runLLMTestlistOnPlatform(pipeline, values[0], values[1], pipeline.LINUX_AARCH64_CONFIG, key, values[2], values[3], false, attemptTag, isFinalAttempt, retryContext, false)
        }]]}

        // Add SBSA Slurm jobs
        // singleAttempt:true disables the outer K8s pod retry; see the x86
        // SLURM closure above for the full rationale (cap nested retry budget
        // so consistently-timing-out tests don't burn ~36h on retry cascades).
        def parallelSlurmJobs = SBSASlurmTestConfigs.collectEntries{key, values -> [key, [pipeline.createKubernetesPodConfig(pipeline.X86_64_DOCKER_IMAGE, "slurm", "amd64"), { attemptTag, isFinalAttempt, retryContext = null ->
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
        parallelJobs += parallelSlurmJobs

        // Add SBSA multi node Slurm jobs
        // singleAttempt:true disables the outer K8s pod retry; see above.
        def parallelMultiNodesSBSAJobs = multiNodesSBSAConfigs.collectEntries{key, values -> [key, [pipeline.createKubernetesPodConfig(pipeline.X86_64_DOCKER_IMAGE, "slurm", "amd64"), { attemptTag, isFinalAttempt, retryContext = null ->
            def config = pipeline.LINUX_AARCH64_CONFIG
            if (key.contains("single-device")) {
                config = pipeline.SINGLE_DEVICE_CONFIG
            }
            if (key.contains("llvm")) {
                config = pipeline.LLVM_CONFIG
            }
            pipeline.infraRetry.runLLMTestlistOnSlurm(pipeline, values[0], values[1], config, key, values[2], values[3], values[4] ?: 1, values[5] ?: 1, values[6] ?: false, false, attemptTag, values[7] ?: false, retryContext?.infraRetryMax)
        }, [singleAttempt: true, slurmDispatcher: true]]]}

        parallelJobs += parallelMultiNodesSBSAJobs
    }
    return [jobs: parallelJobs, fullSet: fullSet]
}

def launchTestJobs(pipeline, testFilter, globalVars)
{
    def versionOverride = globalVars[pipeline.runParameters.TRTLLM_VERSION_OVERRIDE] ?: ""
    def x86 = prepareX86TestJobs(pipeline)
    def sbsa = prepareSbsaTestJobs(pipeline, x86.fullSet)
    def parallelJobs = sbsa.jobs == null ? x86.jobs : sbsa.jobs
    def fullSet = sbsa.fullSet

    // Doc build is pure CPU work (checkout + wheel install + doxygen/sphinx
    // `make html` + upload); it never touches a GPU, so run it on the CPU
    // "build" pod. Mirrors the CPU-only agent-flow job below.
    def docBuildSpec = pipeline.createKubernetesPodConfig(pipeline.LLM_DOCKER_IMAGE, "build")
    def docBuildConfigs = [
        "CPU-Build_Docs": [docBuildSpec, {
            pipeline.sh "rm -rf **/*.xml *.tar.gz"
            pipeline.testRuntime.runLLMDocBuild(pipeline, pipeline.VANILLA_CONFIG)
        }],
    ]

    fullSet += docBuildConfigs.keySet()

    if (pipeline.env.targetArch == pipeline.AARCH64_TRIPLE) {
        docBuildConfigs = [:]
    }

    def docBuildJobs = docBuildConfigs.collectEntries{key, values -> [key, [values[0], { attemptTag, isFinalAttempt, retryContext = null ->
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
    def agentFlowTestSpec = pipeline.createKubernetesPodConfig(pipeline.LLM_DOCKER_IMAGE, "build")
    // Single source of truth for the stage name: it is both the map key (which
    // cacheErrorAndUploadResult uses for the results dir it tars and feeds to
    // junit) and the name passed to runLLMAgentFlowTest (which writes
    // results.xml into that dir). If the two ever diverge, junit finds no
    // report ("No test report files were found").
    def agentFlowStageName = "CPU-AgentFlow-UnitTest"
    def agentFlowTestConfigs = [
        (agentFlowStageName): [agentFlowTestSpec, {
            pipeline.sh "rm -rf **/*.xml *.tar.gz"
            pipeline.testRuntime.runLLMAgentFlowTest(pipeline, agentFlowStageName)
        }],
    ]

    fullSet += agentFlowTestConfigs.keySet()

    // agent-flow tests are architecture-independent; run them once on x86 only.
    if (pipeline.env.targetArch == pipeline.AARCH64_TRIPLE) {
        agentFlowTestConfigs = [:]
    }

    def agentFlowTestJobs = agentFlowTestConfigs.collectEntries{key, values -> [key, [values[0], { attemptTag, isFinalAttempt, retryContext = null ->
        pipeline.stage("[${key}] Run") {
            pipeline.testResults.cacheErrorAndUploadResult(pipeline, "${key}", values[1], {}, false, attemptTag, isFinalAttempt, retryContext)
        }
    }]]}

    // Python version and OS for sanity check
    // Slots: [buildImage, gpuType, cpuArch, reinstallDependencies, isDlfw, pipInstallImage, extraPytorchInstall, platName]
    def x86SanityCheckConfigs = [
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

    def aarch64SanityCheckConfigs = [
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
    fullSet += x86SanityCheckConfigs.collectEntries{ key, values -> [toStageName(values[1], key), null] }.keySet()
    fullSet += aarch64SanityCheckConfigs.collectEntries{ key, values -> [toStageName(values[1], key), null] }.keySet()

    def sanityCheckConfigs = x86SanityCheckConfigs
    if (pipeline.env.targetArch == pipeline.AARCH64_TRIPLE) {
        sanityCheckConfigs = aarch64SanityCheckConfigs
    }

    def sanityCheckJobs = sanityCheckConfigs.collectEntries {key, values -> [toStageName(values[1], key), {
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
            def boltConsume = globalVars[pipeline.runParameters.BOLT_CONSUME_BUILD]?.toString() == "true"
            // The bundle this pipeline pinned, so the released wheel is optimized
            // with the same profiles the tested build was. Empty means unpinned:
            // apply_latest.sh then takes whatever `latest` is, i.e. today's
            // behaviour.
            def boltProfileRef = globalVars[pipeline.runParameters.BOLT_PROFILE_REF]?.toString() ?: ""
            def boltProfileBranch = globalVars[pipeline.runParameters.BOLT_PROFILE_BRANCH]?.toString() ?: ""
            def kitmakerDryRun = testFilter[(pipeline.runParameters.IS_POST_MERGE)] ?: false

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
                    def pipInstallSanitySpec = pipeline.createKubernetesPodConfig(values[5], gpu_type, k8s_arch)
                    pipeline.testRuntime.runKubernetesPodWithInfraRetry(pipeline, pipInstallSanitySpec, "trt-llm", toStageName(values[1], key), { attemptTag, isFinalAttempt, retryContext = null ->
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
                            pipeline.jenkinsRunner.runLLMTestlistOnPlatform(pipeline, gpu_type, "l0_sanity_check", config, toStageName(values[1], key), 1, 1, true, "-SubJob-RunTest" + attemptTag, isFinalAttempt, retryContext)
                        }
                    })
                }
            }
        }, {}, true)
    }]}

    // OnDemand stages are available through --stage-list/--extra-stage only.
    def multiGpuJobs = parallelJobs.findAll{(it.key =~ /\d+_GPUs/) && !it.key.contains("Post-Merge") && !it.key.contains("-OnDemand-")}
    pipeline.println multiGpuJobs.keySet()
    def multiGpuJobsPostMerge = parallelJobs.findAll{(it.key =~ /\d+_GPUs/) && it.key.contains("Post-Merge")}

    parallelJobs += docBuildJobs
    parallelJobs += sanityCheckJobs
    parallelJobs += agentFlowTestJobs

    def onDemandJobs = parallelJobs.findAll {it.key.contains("-OnDemand-")}
    def postMergeJobs = parallelJobs.findAll {it.key.contains("Post-Merge")}

    // Start as a normal pre-merge job
    def parallelJobsFiltered = parallelJobs - multiGpuJobs - postMergeJobs - onDemandJobs

    // Check if the multi GPU related file has changed or not. If changed, add multi GPU test stages.
    if (testFilter[(pipeline.runParameters.MULTI_GPU_FILE_CHANGED)]) {
        parallelJobsFiltered += multiGpuJobs
    }

    if (testFilter[(pipeline.runParameters.AUTO_TRIGGER_TAG_LIST)]) {
        pipeline.echo "AUTO_TRIGGER_TAG_LIST mode is true. Auto trigger tags: ${testFilter[(pipeline.runParameters.AUTO_TRIGGER_TAG_LIST)].join(', ')}."
        def autoTriggerTagStages = [:]
        for (tag in testFilter[(pipeline.runParameters.AUTO_TRIGGER_TAG_LIST)]) {
            autoTriggerTagStages += (parallelJobs - onDemandJobs).findAll { it.key.contains(tag) }
        }
        parallelJobsFiltered += autoTriggerTagStages
        if (autoTriggerTagStages.size() > 0) {
            pipeline.echo "Auto trigger will force run stages: ${autoTriggerTagStages.keySet().join(', ')}."
        }
        pipeline.println parallelJobsFiltered.keySet()
    }

    // Check --post-merge or post-merge pipelines.
    // If true, add post-merge only test stages and multi-GPU test stages.
    if (testFilter[(pipeline.runParameters.IS_POST_MERGE)]) {
        parallelJobsFiltered += multiGpuJobs
        parallelJobsFiltered += postMergeJobs
    }

    // Check --skip-test, only run doc build and sanity check stages.
    if (testFilter[(pipeline.runParameters.ENABLE_SKIP_TEST)]) {
        pipeline.echo "All test stages are skipped."
        parallelJobsFiltered = docBuildJobs + sanityCheckJobs
    }

    // Check --add-multi-gpu-test, if true, add multi-GPU test stages back.
    if (testFilter[(pipeline.runParameters.ADD_MULTI_GPU_TEST)]) {
        parallelJobsFiltered += multiGpuJobs
    }

    // Check --only-multi-gpu-test, if true, only run multi-GPU test stages.
    if (testFilter[(pipeline.runParameters.ONLY_MULTI_GPU_TEST)]) {
        if (testFilter[(pipeline.runParameters.IS_POST_MERGE)]) {
            parallelJobsFiltered = multiGpuJobsPostMerge
        } else {
            parallelJobsFiltered = multiGpuJobs
        }
    }

    // Check --disable-multi-gpu-test, if true, remove multi-GPU test stages.
    if (testFilter[(pipeline.runParameters.DISABLE_MULTI_GPU_TEST)]) {
        parallelJobsFiltered -= multiGpuJobs
    }

    // Check --gpu-type, filter test stages.
    if (testFilter[(pipeline.runParameters.GPU_TYPE_LIST)] != null) {
        pipeline.echo "Use GPU_TYPE_LIST for filtering. GPU types: ${testFilter[(pipeline.runParameters.GPU_TYPE_LIST)]}."
        parallelJobsFiltered = parallelJobsFiltered.findAll {it.key.tokenize('-')[0] in testFilter[(pipeline.runParameters.GPU_TYPE_LIST)]}
        pipeline.println parallelJobsFiltered.keySet()
    }

    // Check --backend-mode, filter test stages.
    if (testFilter[(pipeline.runParameters.TEST_BACKEND)] != null) {
        pipeline.echo "Use TEST_BACKEND for filtering. Backend mode: ${testFilter[(pipeline.runParameters.TEST_BACKEND)]}."
        def backendMode = testFilter[(pipeline.runParameters.TEST_BACKEND)].collect { it.toLowerCase() }
        def changeMap = [
            "pytorch": "-PyTorch-",
            "cpp": "-CPP-",
            "triton": "-Triton-",
            "fmha": "-FMHA-",
        ]
        def backendModeList = backendMode.collect { changeMap.get(it) }.flatten()
        def parallelJobsNoBackend = parallelJobsFiltered.findAll { key, _ ->
            !changeMap.values().any { backend -> key.contains(backend) }
        }
        def parallelJobsBackendMode = parallelJobsFiltered.findAll { key, _ ->
            backendModeList.any { backend -> key.contains(backend) }
        }
        parallelJobsFiltered = parallelJobsNoBackend + parallelJobsBackendMode
        pipeline.echo "parallelJobsBackendMode: ${parallelJobsBackendMode.keySet()}"
        pipeline.println parallelJobsFiltered.keySet()
    }

    if (testFilter[(pipeline.runParameters.ONLY_ONE_GROUP_CHANGED)] == "Docs") {
        pipeline.echo "Only docs files are changed, run doc build stage only."
        parallelJobsFiltered = docBuildJobs
        pipeline.println parallelJobsFiltered.keySet()
    } else if (testFilter[(pipeline.runParameters.ONLY_ONE_GROUP_CHANGED)] != "") {
        if (testFilter[(pipeline.runParameters.TEST_BACKEND)] != null) {
            pipeline.echo "Force disable ONLY_ONE_GROUP_CHANGED mode. Backend mode set by flag: ${testFilter[(pipeline.runParameters.TEST_BACKEND)]}."
        } else {
            pipeline.echo "ONLY_ONE_GROUP_CHANGED mode is true. The group is: ${testFilter[(pipeline.runParameters.ONLY_ONE_GROUP_CHANGED)]}."
            def excludedBackends = new HashMap()
            excludedBackends["PyTorch"] = ["-CPP-", "-FMHA-"]     // Only pytorch file change also need to run triton tests
            excludedBackends["Triton"] = ["-PyTorch-", "-CPP-", "-FMHA-"]
            excludedBackends["FMHA"] = ["-PyTorch-", "-CPP-", "-Triton-"]
            def group = testFilter[(pipeline.runParameters.ONLY_ONE_GROUP_CHANGED)]
            if (excludedBackends.containsKey(group)) {
                parallelJobsFiltered = parallelJobsFiltered.findAll { key, value ->
                    !excludedBackends[group].any { backend -> key.contains(backend) }
                }
            }
            pipeline.println parallelJobsFiltered.keySet()
        }
    }

    // Keep manually triggered stages out of every automatic selection path.
    parallelJobsFiltered -= onDemandJobs

    // Check --stage-list, only run the stages in stage-list. Supports wildcard '*'.
    if (testFilter[pipeline.runParameters.TEST_STAGE_LIST] != null) {
        pipeline.echo "Use TEST_STAGE_LIST for filtering. Stages: ${testFilter[(pipeline.runParameters.TEST_STAGE_LIST)]}."
        parallelJobsFiltered = parallelJobs.findAll { pipeline.stagePatterns.stageMatchesAnyPattern(it.key, testFilter[(pipeline.runParameters.TEST_STAGE_LIST)]) }
        pipeline.println parallelJobsFiltered.keySet()
    }

    // Check --extra-stage, add the stages in extra-stage. Supports wildcard '*'.
    if (testFilter[pipeline.runParameters.EXTRA_STAGE_LIST] != null) {
        pipeline.echo "Use EXTRA_STAGE_LIST for filtering. Stages: ${testFilter[(pipeline.runParameters.EXTRA_STAGE_LIST)]}."
        parallelJobsFiltered += parallelJobs.findAll { pipeline.stagePatterns.stageMatchesAnyPattern(it.key, testFilter[(pipeline.runParameters.EXTRA_STAGE_LIST)]) }
        pipeline.println parallelJobsFiltered.keySet()
    }

    pipeline.testSelection.checkStageName(fullSet)

    if (testFilter[(pipeline.runParameters.TEST_STAGE_LIST)] != null) {
        pipeline.testSelection.checkStageNameSet(testFilter[(pipeline.runParameters.TEST_STAGE_LIST)], fullSet, pipeline.runParameters.TEST_STAGE_LIST)
    }
    if (testFilter[(pipeline.runParameters.EXTRA_STAGE_LIST)] != null) {
        pipeline.testSelection.checkStageNameSet(testFilter[(pipeline.runParameters.EXTRA_STAGE_LIST)], fullSet, pipeline.runParameters.EXTRA_STAGE_LIST)
    }

    parallelJobsFiltered = pipeline.testSelection.filterCbtsStageJobs(
        parallelJobs, parallelJobsFiltered, multiGpuJobs, testFilter)

    if (globalVars[pipeline.runParameters.RUN_MODE] == "nightly_release") {
        parallelJobsFiltered = sanityCheckJobs
    }

    pipeline.echo "Check the passed GitLab bot testFilter parameters."
    def keysStr = parallelJobsFiltered.keySet().join(",\n")
    pipeline.echo "Now we will run stages: [\n${keysStr}\n]"
    def maintenanceConfig = globalVars[pipeline.runParameters.MAINTENANCE_ENTRIES] ?: [reason: "", patterns: []]
    def maintenancePatterns = maintenanceConfig.patterns ?: []
    pipeline.testSelection.warnUnmatchedMaintenancePatterns(
        parallelJobsFiltered.keySet(), maintenancePatterns)

    // Per-stage execution scope for infra-scoped fail-fast (runBranchesWithInfraDefer).
    // A stage carrying opts.slurmDispatcher runs its work through a SLURM dispatcher
    // pod, so its post-retry failures can be SLURM-scoped; everything else is K8s.
    // Built here, where the config tuple's opts (3rd element) is still visible, and
    // keyed by stage name so the K8s/SLURM group subsets can look each branch up.
    def stageInfraScope = [:]
    parallelJobsFiltered = parallelJobsFiltered.collectEntries { key, values ->
        def stageOpts = (values instanceof List && values.size() >= 3 && values[2] instanceof Map) ? values[2] : [:]
        stageInfraScope[key] = stageOpts.slurmDispatcher ? InfraFailure.SLURM : InfraFailure.K8S
        [key, {
        pipeline.stage(key) {
            if (key in testFilter[pipeline.runParameters.REUSE_STAGE_LIST]) {
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

    return [jobs: parallelJobsFiltered, infraScopes: stageInfraScope]
}

return this
