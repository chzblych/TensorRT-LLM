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
import com.nvidia.bloom.SlurmPartition
import com.nvidia.bloom.Utils
import trtllm.exceptions.InfraFailure
import trtllm.exceptions.TrtllmCiException
import trtllm.exceptions.UserFailure

@Field def pipeline

def initialize(pipeline)
{
    this.@pipeline = pipeline
    return this
}

def runLLMTestlistWithAgent(pipeline, platform, testList, config=pipeline.VANILLA_CONFIG, stageName="Undefined", splitId=1, splits=1, gpuCount=1, skipInstallWheel=false, String postTag="", boolean useClusterDurations=false, Map placementContext=null, Map retryContext=null)
{
    SlurmPartition partition = SlurmConfig.resolvePlatform(platform)
    SlurmCluster cluster = SlurmConfig.clusterConfig[partition.clusterName]

    // Record which cluster this attempt ran on so a failed node is remembered
    // against its own cluster (auto: platforms pick a cluster at random per attempt).
    if (placementContext != null) {
        placementContext.lastSlurmClusterName = partition.clusterName
    }

    def entrypoint = SlurmConfig.containerRuntimeToEntrypoint[cluster.containerRuntime]

    // Create a unique suffix for the instance, node name and workspace
    String customSuffix = "${pipeline.testExecution.getShortenedJenkinsInstanceName()}-${pipeline.env.BUILD_TAG}-${UUID.randomUUID().toString().replaceAll("-", "").substring(0, 6)}".toLowerCase()
    def nodeName = "${cluster.host}-test-${customSuffix}"
    def customWorkspace = "/tmp/${nodeName}"
    def nodeSecret = CloudManager.createNode(nodeName, customWorkspace)

    def slurmJobID = null
    def dockerArgs = null
    Throwable primaryError = null

    try {
        // Run ssh command to start node in desired cluster via SLURM.
        // Prepare Container runs first (inside the same SSH session) so that the fat sqsh
        // is on disk before the GPU agent job starts and slurm_install.sh checks for it.
        CloudManager.withSlurmFrontendFailover(pipeline, partition.clusterName, cluster) { remote ->

            def tarName = pipeline.BUILD_CONFIGS[config][pipeline.TARNAME]
            // The fat sqsh replaces the download in runLLMTestlistOnPlatformImpl, so it
            // must bake the same build that function would have fetched.
            def llmTarArtifactPath = cluster.fatBuilderArgs != null ? pipeline.testBuild.boltedTarArtifactPath(pipeline, config, tarName) : null
            def llmPath = pipeline.sh(script: "realpath .", returnStdout: true).trim()
            def llmSrcLocal = "${llmPath}/TensorRT-LLM/src"
            def agentJobWorkspace = "/home/svc_tensorrt/bloom/scripts/${nodeName}"
            def scriptFatBuildLocalPath = "${llmSrcLocal}/jenkins/scripts/fat_build_inline.sh"
            def scriptFatBuildPathNode = "${agentJobWorkspace}/${nodeName}-fat_build_inline.sh"
            def scriptFatBuildSbatchPathLocal = Utils.createTempLocation(pipeline, "./fat_build_sbatch.sh")
            def scriptFatBuildSbatchPathNode = "${agentJobWorkspace}/${nodeName}-fat_build_sbatch.sh"
            def scriptPreparePathLocal = Utils.createTempLocation(pipeline, "./prepare_container.sh")
            def scriptPreparePathNode = "${agentJobWorkspace}/${nodeName}-prepare_container.sh"
            def scriptFatBuildBodyLocalPath = "${llmSrcLocal}/jenkins/scripts/fat_build_sbatch_body.sh"
            def scriptFatBuildBodyPathNode = "${agentJobWorkspace}/${nodeName}-fat_build_sbatch_body.sh"
            def scriptPrepareBodyLocalPath = "${llmSrcLocal}/jenkins/scripts/prepare_container.sh"
            def scriptPrepareBodyPathNode = "${agentJobWorkspace}/${nodeName}-prepare_container_body.sh"
            def scriptFetchHelperPathNode = "${agentJobWorkspace}/${nodeName}-fetch_build_artifact.sh"
            def fatArtifactoryNetrcPathNode = "${agentJobWorkspace}/fat-artifactory-netrc"

            pipeline.stage("Initialize Test") {
                pipeline.println("Selected Cluster: ${cluster.name}")
                Utils.exec(pipeline, script: Utils.sshUserCmd(remote, "\"mkdir -p ${agentJobWorkspace}\""), numRetries: 3)

                if (cluster.fatBuilderArgs != null) {
                    pipeline.timeout(time: 30, unit: 'MINUTES') {
                        pipeline.testBuild.fetchBuildArtifact(pipeline, llmTarArtifactPath, "${llmPath}/${tarName}")
                    }
                    pipeline.sh "cd ${llmPath} && tar -zxf ${tarName}"

                    Utils.exec(pipeline, script: "echo 'Script to build fat sqsh inline:' && cat ${scriptFatBuildLocalPath}")
                    Utils.copyFileToRemoteHost(pipeline, remote, scriptFatBuildLocalPath, scriptFatBuildPathNode, true)

                    // Must match the agent's enroot form, or the two import separate
                    // copies of the same base sqsh.
                    def container = pipeline.LLM_DOCKER_IMAGE
                    if (cluster.containerRuntime.toString() == "ENROOT") {
                        container = pipeline.LLM_DOCKER_IMAGE
                            .replace("${pipeline.ARTIFACTORY_DOCKER_HOST}/", "${pipeline.ARTIFACTORY_DOCKER_HOST}#")
                    }
                    def fatSqshDir = "${cluster.scratchPath}/users/svc_tensorrt/fat_sqsh"
                    def containerDir = "${cluster.scratchPath}/users/svc_tensorrt/containers"
                    def fatBuildLogPath = SlurmConfig.getOutputFilePath("${cluster.homeDir}/slurm-logs", "${nodeName}-fat_build")

                    Utils.copyFileToRemoteHost(pipeline, remote, scriptFatBuildBodyLocalPath, scriptFatBuildBodyPathNode, true)
                    Utils.copyFileToRemoteHost(pipeline, remote, scriptPrepareBodyLocalPath, scriptPrepareBodyPathNode, true)
                    Utils.copyFileToRemoteHost(pipeline, remote, pipeline.testBuild.readHelperPath(pipeline), scriptFetchHelperPathNode, true)
                    pipeline.testBuild.deliverFatBuilderNetrc(pipeline, remote, fatArtifactoryNetrcPathNode)

                    def scriptFatBuildSbatchContent = pipeline.testBuild.generateFatBuildSbatchScript(
                        fatBuildLogPath: fatBuildLogPath,
                        fatBuilderArgs: cluster.fatBuilderArgs,
                        containerDir: containerDir,
                        container: container,
                        fatSqshDir: fatSqshDir,
                        llmTarArtifactPath: llmTarArtifactPath,
                        llmDockerImage: pipeline.LLM_DOCKER_IMAGE,
                        fatBuildScriptPathNode: scriptFatBuildPathNode,
                        tarName: tarName,
                        fetchHelperPathNode: scriptFetchHelperPathNode,
                        artifactoryNetrcPathNode: fatArtifactoryNetrcPathNode,
                        fatBuildBodyPathNode: scriptFatBuildBodyPathNode
                    )
                    pipeline.writeFile(file: scriptFatBuildSbatchPathLocal, text: scriptFatBuildSbatchContent)
                    Utils.exec(pipeline, script: "echo 'Fat builder sbatch script:' && cat ${scriptFatBuildSbatchPathLocal}")
                    Utils.copyFileToRemoteHost(pipeline, remote, scriptFatBuildSbatchPathLocal, scriptFatBuildSbatchPathNode, true)

                    def scriptPrepareContent = pipeline.testBuild.generateFatPrepareScript(
                        fatSqshDir: fatSqshDir,
                        llmTarArtifactPath: llmTarArtifactPath,
                        llmDockerImage: pipeline.LLM_DOCKER_IMAGE,
                        fatBuildScriptPathNode: scriptFatBuildPathNode,
                        fatBuildSbatchPathNode: scriptFatBuildSbatchPathNode,
                        fatBuildLogPath: fatBuildLogPath,
                        artifactoryNetrcPathNode: fatArtifactoryNetrcPathNode,
                        prepareBodyPathNode: scriptPrepareBodyPathNode
                    )
                    pipeline.writeFile(file: scriptPreparePathLocal, text: scriptPrepareContent)
                    Utils.exec(pipeline, script: "echo 'Prepare Container script:' && cat ${scriptPreparePathLocal}")
                    Utils.copyFileToRemoteHost(pipeline, remote, scriptPreparePathLocal, scriptPreparePathNode, true)
                } else {
                    pipeline.echo "No fat sqsh builder configured for cluster ${cluster.name}; skipping fat sqsh script preparation."
                }
            }

            // Runs on every attempt: prepare_container.sh exits immediately if the fat
            // sqsh already exists, and a retry whose attempt 1 died before the build
            // still needs one.
            pipeline.stage("[${stageName}] Prepare Container") {
                if (cluster.fatBuilderArgs != null) {
                    try {
                        Utils.exec(
                            pipeline,
                            timeout: false,
                            script: Utils.sshUserCmd(remote, scriptPreparePathNode),
                            numRetries: 3
                        )
                    } catch (InterruptedException e) {
                        throw e
                    } catch (Exception e) {
                        pipeline.echo "[Prepare Container] Non-fatal failure: ${e.message}. GPU agent job will fall back to base sqsh + full install."
                    }
                } else {
                    pipeline.echo "No fat sqsh builder configured; skipping Prepare Container."
                }
            }

            pipeline.stage('Request Node Via Slurm') {
                def jenkinsSetupPath = Utils.copyLibraryResource(pipeline, entrypoint)

                Utils.exec(pipeline, script: "cat ${jenkinsSetupPath}")

                Utils.copyFileToRemoteHost(pipeline, remote, jenkinsSetupPath, "/home/svc_tensorrt/bloom/scripts/${nodeName}-${entrypoint}", true)

                Utils.exec(pipeline, script: "echo Sleeping before Slurm job submission; sleep \$((RANDOM % 29 + 1))")

                // Enroot needs artifactory auth. Use a per-job config dir (nodeName is
                // unique) — never the shared ~/.config/enroot, which races across jobs.
                // ENROOT_CONFIG_PATH is exported in the sbatch submit shell so Slurm
                // propagates it into the agent setup script (--export=ALL by default).
                def enrootConfigDir = null
                if (cluster.containerRuntime.toString() == "ENROOT") {
                    enrootConfigDir = "${cluster.scratchPath}/users/svc_tensorrt/enroot-config-${nodeName}"
                    pipeline.withCredentials([pipeline.usernamePassword(
                        credentialsId: pipeline.ARTIFACTORY_CREDENTIALS_ID,
                        usernameVariable: 'ARTIFACTORY_USER',
                        passwordVariable: 'ARTIFACTORY_PASSWORD'
                    )]) {
                        def credsLocal = Utils.createTempLocation(pipeline, "./enroot_credentials_slurm-${nodeName}")
                        pipeline.withEnv([
                            "ENROOT_CREDS_PATH=${credsLocal}",
                            "ARTIFACTORY_DOCKER_HOST=${pipeline.ARTIFACTORY_DOCKER_HOST}",
                        ]) {
                            Utils.exec(pipeline, script: '''
                                set +x
                                umask 077
                                cat > "$ENROOT_CREDS_PATH" <<EOF
                                machine ${ARTIFACTORY_DOCKER_HOST} login ${ARTIFACTORY_USER} password ${ARTIFACTORY_PASSWORD}
                                EOF
                            '''.replaceAll("\\n\\s*", "\n"))
                        }
                        Utils.exec(pipeline, script: Utils.sshUserCmd(remote, Utils.bashWrappedRemoteCmd("mkdir -p '${enrootConfigDir}'")))
                        Utils.copyFileToRemoteHost(
                            pipeline,
                            remote,
                            credsLocal,
                            "${enrootConfigDir}/.credentials"
                        )
                    }
                }

                def mounts = pipeline.testExecution.getMountListForSlurmTest(cluster, false).join(",")

                def containerImageForAgent = pipeline.LLM_DOCKER_IMAGE
                if (cluster.containerRuntime.toString() == "ENROOT") {
                    containerImageForAgent = pipeline.LLM_DOCKER_IMAGE
                        .replace("${pipeline.ARTIFACTORY_DOCKER_HOST}/", "${pipeline.ARTIFACTORY_DOCKER_HOST}#")
                }
                // Run the agent inside the pre-built fat sqsh (TRT-LLM + pip deps baked in).
                // ENROOT only: a .sqsh path is not a valid image for a Docker runtime.
                if (cluster.fatBuilderArgs != null && cluster.containerRuntime.toString() == "ENROOT") {
                    def fatSqshDir = "${cluster.scratchPath}/users/svc_tensorrt/fat_sqsh"
                    def fatSqsh = pipeline.testBuild.computeFatSqshPath(fatSqshDir, llmTarArtifactPath, scriptFatBuildLocalPath)
                    def fatHash = fatSqsh.hash
                    def fatSqshPath = fatSqsh.path
                    pipeline.echo "Fat sqsh check: hash=${fatHash} path=${fatSqshPath}"
                    // Simple SSH check: just test the pre-computed literal path.
                    def fatCheckResult = Utils.exec(
                        pipeline,
                        returnStdout: true,
                        script: Utils.sshUserCmd(
                            remote,
                            "\"test -f '${fatSqshPath}' && echo '${fatSqshPath}' || echo MISSING\""
                        )
                    ).trim()
                    if (fatCheckResult != "MISSING" && fatCheckResult) {
                        pipeline.echo "Fat sqsh ready; using it as agent container image: ${fatCheckResult}"
                        containerImageForAgent = fatCheckResult
                    } else {
                        pipeline.echo "Fat sqsh not ready; falling back to base image."
                    }
                }

                def slurmCommand = SlurmConfig.generateCommand(cluster, partition, nodeSecret, nodeName, Jenkins.instance.rootUrl, containerImageForAgent, mounts)
                def clusterExcludes = placementContext?.excludedSlurmNodeListsByCluster?.get(partition.clusterName)
                def slurmCommandWithExclusion = pipeline.trtllm_utils.addSlurmExcludeToCommand(slurmCommand, clusterExcludes)
                def slurmExcludeArg = pipeline.trtllm_utils.buildSlurmExcludeArg(clusterExcludes)
                if (slurmExcludeArg) {
                    if (slurmCommandWithExclusion != slurmCommand) {
                        pipeline.echo "[INFRA-RETRY] ${stageName}: requesting SLURM retry placement exclusion: ${slurmExcludeArg}"
                    } else {
                        pipeline.echo "[INFRA-RETRY] ${stageName}: could not inject ${slurmExcludeArg} into generated SLURM agent command; submitting without node exclusion"
                    }
                }

                def slurmSubmitCommand = slurmCommandWithExclusion
                if (enrootConfigDir) {
                    slurmSubmitCommand = "export ENROOT_CONFIG_PATH='${enrootConfigDir}'; ${slurmCommandWithExclusion}"
                }
                slurmSubmitCommand = SlurmConfig.withGpuBudget(pipeline, partition.clusterName, slurmSubmitCommand, gpuCount)

                def slurmSubmitOutput = Utils.exec(
                    pipeline,
                    timeout: false,
                    script: Utils.sshUserCmd(
                        remote,
                        Utils.bashWrappedRemoteCmd(slurmSubmitCommand)
                    ),
                    returnStdout: true,
                    numRetries: 3
                )

                def jobIDs = slurmSubmitOutput
                    .readLines()
                    .collect { it.trim() }
                    .collectMany { line ->
                        def ids = []
                        def m1 = (line =~ /Submitted batch job (\d+)/)
                        if (m1) ids << m1[0][1]  // Extract the first captured group
                        def m2 = (line =~ /srun: job (\d+) (queued|has been allocated)/)
                        if (m2) ids << m2[0][1]  // Extract the first captured group
                        def m3 = (line =~ /SLURM_JOB_ID=(\d+)/)
                        if (m3) ids << m3[0][1]  // Extract the first captured group
                        def m4 = (line =~ /SLURM_JOBID=(\d+)/)
                        if (m4) ids << m4[0][1]  // Extract the first captured group
                        return ids
                    }

                slurmJobID = jobIDs ? jobIDs[-1] : null

                // Record the live SLURM job + Jenkins node so a dispatcher-pod death
                // can be reconciled off-pod (the in-pod cleanup can't reach the login
                // node once the pod is gone). Deregistered when cleanup actually runs.
                pipeline.slurmResources.registerSlurmResource(stageName, [clusterName: partition.clusterName, nodeName: nodeName, slurmJobId: slurmJobID, usedSbatch: false])

                if (!pipeline.slurmResources.isValidSlurmJobId(slurmJobID)) {
                    pipeline.echo "Slurm job did not submit successfully. No job ID found.\nSubmission output:\n${slurmSubmitOutput}"
                    // The job never entered the SLURM queue, so nothing ran on any
                    // node: retryable infra by construction. Failing fast here also
                    // keeps the node-wait loop below (up to 15h) from polling a
                    // bogus job ID.
                    throw new InfraFailure(
                        "SLURM agent submission for ${stageName} produced no usable job ID " +
                        "(none or a non-numeric value in the submission output); " +
                        "the job never entered the queue.",
                        null, InfraFailure.TRANSIENT, InfraFailure.SLURM, "<typed:slurm-submit-no-jobid>")
                }
                Utils.exec(pipeline, script: "echo Slurm job ID: ${slurmJobID}")
                Utils.exec(pipeline, script: "echo Sleeping to allow agent initialization; sleep 30")
            }
        }

        // Wall-clock at which the SLURM job was first observed RUNNING.
        // Captured locally so it remains usable even when the controller
        // is later unreachable (the case the fallback exists for). Null until
        // Phase 1 confirms RUNNING; callers fall back to executeStartMs.
        def jobRunningStartMs = null

        pipeline.stage('Check If Node Is Online') {
            CloudManager.withSlurmSshCredentialRemotes(pipeline, partition.clusterName, cluster) { remotes ->
                // Check the SLURM job once; if it is no longer active, raise a typed
                // InfraFailure(SLURM) so the retry layer routes it via instanceof (scope=SLURM).
                def checkSlurmJobActive = {
                    try {
                        CloudManager.withSlurmFrontendFailover(pipeline, remotes) { statusRemote ->
                            SlurmConfig.checkJobStatus(pipeline, cluster, slurmJobID, statusRemote)
                        }
                    } catch (InterruptedException e) {
                        throw e
                    } catch (Exception e) {
                        if (e.message?.contains("is no longer active")) {
                            def slurmLogPath = "/home/svc_tensorrt/slurm-logs/slurm-${slurmJobID}-${nodeName}.out"
                            CloudManager.withSlurmFrontendFailover(pipeline, remotes) { logRemote ->
                                pipeline.slurmResources.echoRemoteLogTail(pipeline, logRemote, slurmLogPath)
                            }
                            throw new InfraFailure(
                                "${e.message}. Check SLURM logs at ${slurmLogPath} on ${cluster.host}",
                                e, InfraFailure.TRANSIENT, InfraFailure.SLURM, "<typed:slurm-job-inactive>"
                            )
                        }
                        // Otherwise, log the error but continue (SSH might be temporarily unavailable)
                        pipeline.echo("Warning: Could not check SLURM job status: ${e.message}")
                    }
                }

                // Phase 1: wait for the job to leave the queue (PENDING -> RUNNING), polling
                // every 3 min. The whole loop runs in a SINGLE shell step so a long queue wait
                // only adds one flow-node to the Blue Ocean graph (instead of one per iteration,
                // which overflowed the per-stage step cap). Release the held job every 10
                // iterations (~30 min). 300 iterations * 3 min = 15h budget.
                // Exit codes: 0 = job RUNNING, 3 = job no longer active, 4 = timed out.
                def sacctStateCmd = CloudManager.sshUserCmdWithSlurmFrontendFailover(remotes, "\"sacct -j ${slurmJobID} --format=State -Pn --allocations\"")
                def releaseCmd = CloudManager.sshUserCmdWithSlurmFrontendFailover(remotes, "\"scontrol release ${slurmJobID} || true\"")
                def waitRc = pipeline.sh(returnStatus: true, script: """
                    set +e
                    counter=0
                    while [ \$counter -lt 300 ]; do
                        # Avoid the job being stuck in the held state. Release every 10 iterations (~30 min).
                        if [ \$(( counter % 10 )) -eq 0 ]; then
                            ${releaseCmd} || true
                        fi
                        STATE=\$(${sacctStateCmd} | head -1 | cut -d'|' -f1 | awk '{print \$1}')
                        echo "[node-wait] iteration \$counter: SLURM job ${slurmJobID} state='\$STATE'"
                        case "\$STATE" in
                            RUNNING|COMPLETING)
                                echo "[node-wait] SLURM job ${slurmJobID} is running."
                                exit 0
                                ;;
                            PENDING|CONFIGURING|REQUEUED|RESIZING|SUSPENDED|SIGNALING|STOPPED|"")
                                # Still queued, or a transient sacct/ssh hiccup (empty state): keep waiting.
                                ;;
                            *)
                                echo "[node-wait] SLURM job ${slurmJobID} is no longer active (state='\$STATE')."
                                exit 3
                                ;;
                        esac
                        counter=\$(( counter + 1 ))
                        # Wait 3 minutes before checking the job state again.
                        sleep 180
                    done
                    echo "[node-wait] Timed out waiting for SLURM job ${slurmJobID} to start."
                    exit 4
                """)

                // If the job reached a terminal state while queued, confirm via the canonical
                // status check so the exact typed InfraFailure(SLURM) is raised.
                if (waitRc == 3) {
                    checkSlurmJobActive()
                }
                if (waitRc != 0) {
                    pipeline.error "SLURM job ${slurmJobID} did not reach RUNNING during the queue wait. Terminating the job."
                }

                // Phase 2: job is RUNNING; wait for the Jenkins agent to come online. isNodeOnline()
                // and Thread.sleep() emit no flow-nodes, so poll every 30s without bloating Blue
                // Ocean, and probe job status every ~3 min (every 6th iter) to fail fast if the
                // job dies during bring-up. 60 * 30s = 30 min.
                if (waitRc == 0) {
                    // Job is RUNNING: stamp the walltime-budget origin for the
                    // timeout duration fallback (within Phase 1's ~3min poll
                    // granularity of the true RUNNING transition).
                    jobRunningStartMs = System.currentTimeMillis()
                    def onlineCounter = 0
                    while (!CloudManager.isNodeOnline(nodeName) && onlineCounter < 60) {
                        Thread.sleep(30L * 1000L)
                        if (onlineCounter % 6 == 0) {
                            checkSlurmJobActive()
                        }
                        onlineCounter++
                    }
                }

                if (CloudManager.isNodeOnline(nodeName)) {
                    pipeline.node(nodeName) {
                        pipeline.sh """
                            env | sort
                            pwd && ls -alh
                            ls -alh ${pipeline.env.WORKSPACE}
                            ls -alh ${pipeline.env.WORKSPACE_TMP}
                        """

                        pipeline.sh "nproc && free -g && hostname"
                        if (placementContext != null) {
                            placementContext.lastSlurmNodeList = pipeline.sh(script: "hostname -f || hostname", returnStdout: true).trim()
                            pipeline.echo "[INFRA-RETRY] ${stageName}: SLURM agent is running on ${placementContext.lastSlurmNodeList}"
                        }
                        pipeline.testRuntime.echoNodeAndGpuInfo(pipeline, stageName)
                        pipeline.sh "nvidia-smi && nvidia-smi -q && nvidia-smi topo -m"
                        // Use single quotes to avoid Jenkins variable expansion
                        pipeline.sh 'echo "CUDA_VISIBLE_DEVICES: $CUDA_VISIBLE_DEVICES"'
                        pipeline.sh 'echo "NV_GPU: $NV_GPU"'

                        // Dynamically set GPU arguments based on environment variables
                        // https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/docker-specialized.html
                        // It's intentional to check NV_GPU first.
                        dockerArgs = pipeline.sh(script: """
                            if [ -n "\$NV_GPU" ]; then
                                echo "--gpus '\\"device=\$NV_GPU\\"'"
                            elif [ -n "\$CUDA_VISIBLE_DEVICES" ]; then
                                echo "--gpus '\\"device=\$CUDA_VISIBLE_DEVICES\\"'"
                            else
                                echo "--gpus ${gpuCount}"
                            fi
                        """, returnStdout: true).trim()

                        if (cluster.host.contains("dlcluster")) {
                            dockerArgs += " " + pipeline.sh(script: 'echo " -e NVIDIA_IMEX_CHANNELS=${NVIDIA_IMEX_CHANNELS:-0}"', returnStdout: true).trim()
                            if (pipeline.fileExists('/dev/gdrdrv')) {
                                dockerArgs += " --device=/dev/gdrdrv:/dev/gdrdrv"
                            }
                        }
                        if (pipeline.fileExists('/home/scratch.trt_llm_data_ci')) {
                            dockerArgs += " -v /home/scratch.trt_llm_data_ci:/scratch.trt_llm_data:ro "
                        } else if (pipeline.fileExists('/home/scratch.trt_llm_data')) {
                            dockerArgs += " -v /home/scratch.trt_llm_data:/scratch.trt_llm_data:ro "
                        } else {
                            pipeline.echo "Existing TRT-LLM data scratch mount points cannot be set up in this cluster, ignore..."
                        }
                    }

                    dockerArgs = "${dockerArgs} " +
                        "--cap-add=SYS_ADMIN " +
                        "--ipc=host " +
                        "--entrypoint=\"\" " +
                        "--security-opt seccomp=unconfined " +
                        "-u root:root " +
                        "-v /tmp/ccache:${pipeline.CCACHE_DIR}:rw " +
                        "-v /tmp/pipcache/http-v2:/root/.cache/pip/http-v2:rw " +
                        "--cap-add=SYSLOG"

                    pipeline.echo "Final dockerArgs: ${dockerArgs}"

                    if (cluster.containerRuntime.toString() == "ENROOT" && slurmJobID) {
                        def setupLogPath = "/home/svc_tensorrt/slurm-logs/slurm-${slurmJobID}-${nodeName}.out"
                        def enrootLog = Utils.exec(
                            pipeline,
                            script: CloudManager.sshUserCmdWithSlurmFrontendFailover(remotes, Utils.bashWrappedRemoteCmd("grep '\\[ENROOT\\]' ${setupLogPath} 2>/dev/null || true")),
                            returnStdout: true,
                            numRetries: 3
                        ).trim()
                        if (enrootLog) {
                            pipeline.echo "[Enroot setup log]\n${enrootLog}"
                        }
                    }
                } else {
                    def setupLogPath = "/home/svc_tensorrt/slurm-logs/slurm-${slurmJobID}-${nodeName}.out"
                    try {
                        CloudManager.withSlurmFrontendFailover(pipeline, remotes) { logRemote ->
                            pipeline.slurmResources.echoRemoteLogTail(pipeline, logRemote, setupLogPath)
                        }
                    } catch (InterruptedException e) {
                        throw e
                    } catch (Exception logEx) {
                        pipeline.echo "Ignorable warning: could not retrieve ${setupLogPath}: ${logEx.message}"
                    }
                    throw new InfraFailure(
                        "SLURM agent ${nodeName} for job ${slurmJobID} did not come online within 30 minutes " +
                        "after the job started. Check SLURM logs at ${setupLogPath} on ${cluster.host}.",
                        null, InfraFailure.TRANSIENT, InfraFailure.SLURM, "<typed:slurm-agent-online-timeout>"
                    )
                }
            }
        }

        def slurmRunner = null
        pipeline.echo "${stageName} Slurm partition timeout: ${partition.time}"
        def partitionTimeout = partition?.time ? partition.time : SlurmConfig.DEFAULT_TIMEOUT_SHORT
        if (cluster.containerRuntime.toString() == "DOCKER") {
            slurmRunner = pipeline.testRuntime.runInDockerOnNodeMultiStage(pipeline.LLM_DOCKER_IMAGE, nodeName, dockerArgs, partitionTimeout, true)
        } else if (cluster.containerRuntime.toString() == "ENROOT") {
            slurmRunner = pipeline.testRuntime.runInEnrootOnNode(nodeName, partitionTimeout)
        } else {
            throw new Exception("Unsupported container runtime: ${cluster.containerRuntime}")
        }
        long executeStartMs = System.currentTimeMillis()
        // Reclassify a raw test-execution failure against the SLURM job's terminal
        // state: a walltime kill is a UserFailure (not retryable), while a job still
        // alive when the monitor lost contact is transient infra (retry on a fresh
        // node). Kept as a closure so executeLLMTestOnSlurm can apply it INSIDE the
        // task runner -- that way cacheErrorAndUploadResult suppresses this attempt's
        // junit for the SAME typed failure the retry loop acts on. Deciding it only
        // here (after junit already ran) is what left retried-and-passed agent stages
        // UNSTABLE from an intermediate attempt's results.
        // Classify each SLURM test-execution failure exactly once. The closure sets
        // this when it runs (inside the task runner), so the outer catch reclassifies
        // only failures that never reached it (image pull, node/agent bring-up) --
        // avoiding a second querySlurmJobState round trip that could reach a different
        // verdict as elapsed time and the job's state move on.
        boolean slurmFailureClassified = false
        def classifySlurmFailure = { Throwable err ->
            slurmFailureClassified = true
            // A completed-pytest deterministic failure (tests ran, were re-run via
            // --reruns, and still failed) is a real test failure, not lost-contact
            // infra. On the agent path the SLURM allocation outlives pytest, so the
            // job is still RUNNING here even though pytest already finished -- do NOT
            // let the job state below relabel it as slurm-job-still-running and retry
            // it (which masks the failure whenever the retry happens to pass). Key
            // off the propagated rerun-failure error: it is raised only when reruns
            // genuinely failed and takes precedence over the timeout path. Do not key
            // off failed_results.xml -- generateRerunReport writes it from the first
            // run whenever any rerun occurred, even when the rerun passed or the job
            // timed out, so it would wrongly suppress a legitimate infra retry. Defer
            // so the base classifier treats it as a UserFailure (no retry); a monitor-
            // lost-contact cut raises "terminated unexpectedly" instead and retries.
            if ((err?.toString() ?: "").contains("still failed after rerun attempts")) {
                pipeline.echo "[INFRA-RETRY] ${stageName}: pytest reported deterministic test failure(s); not an infra retry."
                return err
            }
            // Measure elapsed from when the job was first observed RUNNING; fall back
            // to executeStartMs if the RUNNING stamp was never set.
            long timeoutBaselineMs = (jobRunningStartMs ?: executeStartMs) as long
            long elapsedMin = Math.floorDiv(System.currentTimeMillis() - timeoutBaselineMs, 60000L)
            Integer walltimeMin = (partition?.time ?: SlurmConfig.DEFAULT_TIMEOUT_SHORT) as Integer
            def slurmState = pipeline.slurmResources.querySlurmJobState(pipeline, cluster, partition.clusterName, slurmJobID)

            if (slurmState == "TIMEOUT") {
                return new UserFailure(
                    "SLURM job ${slurmJobID} for ${stageName} ended in state TIMEOUT " +
                    "(hit partition walltime ${walltimeMin}min); treating as a test timeout, not retrying. " +
                    "Original failure: ${err.message}",
                    err)
            }

            if (slurmState == null && walltimeMin != null
                    && elapsedMin >= (long)(pipeline.SLURM_TIMEOUT_RETRY_FRACTION * walltimeMin)) {
                return new UserFailure(
                    "SLURM job ${slurmJobID} for ${stageName} ran ${elapsedMin}min " +
                    "(>= ${(int)(pipeline.SLURM_TIMEOUT_RETRY_FRACTION * 100)}% of the ${walltimeMin}min walltime) and " +
                    "the SLURM controller was unreachable for an authoritative state; treating as a likely " +
                    "timeout, not retrying. Original failure: ${err.message}",
                    err)
            }

            if (pipeline.slurmResources.isNonTerminalSlurmState(slurmState)) {
                return new InfraFailure(
                    "SLURM job ${slurmJobID} for ${stageName} is still in non-terminal state ${slurmState} " +
                    "(${elapsedMin}min of ${walltimeMin}min walltime); the monitor lost contact while the job was " +
                    "alive (transient infra), so this is not a test failure. Original failure: ${err.message}",
                    err, InfraFailure.TRANSIENT, InfraFailure.SLURM, "<typed:slurm-job-still-running>")
            }

            pipeline.echo "[INFRA-RETRY] ${stageName}: SLURM job ${slurmJobID} terminal state=${slurmState ?: 'unknown'}, " +
                 "ran ${elapsedMin}min of ${walltimeMin}min walltime; deferring to failure classifier."
            return err
        }
        try {
            pipeline.jenkinsRunner.executeLLMTestOnSlurm(pipeline, platform, testList, config, stageName, splitId, splits, skipInstallWheel, slurmRunner, postTag, useClusterDurations, retryContext, classifySlurmFailure)
        } catch (InterruptedException e) {
            throw e
        } catch (Exception e) {
            // A test-execution failure was already labeled inside the task runner, so
            // this attempt's junit suppression and the retry loop see the same typed
            // failure -- just propagate it. Failures raised outside the task runner
            // (image pull, node/agent bring-up, etc.) were never labeled, so classify
            // them here on the agent.
            Throwable classified = (slurmFailureClassified || e instanceof TrtllmCiException ? e : classifySlurmFailure(e))
            throw classified
        }
    } catch (InterruptedException e) {
        primaryError = e
        throw e
    } catch (Exception e) {
        primaryError = e
        throw e
    } finally {
        pipeline.infraRetry.preservePrimaryFailure(primaryError) {
        // Resource cleanup must run even if SLURM metadata capture is interrupted.
        try {
            pipeline.slurmResources.captureSlurmJobNodeList(pipeline, cluster, partition.clusterName, slurmJobID, placementContext, stageName)
        } finally {
            pipeline.stage("Clean Up Slurm Resource") {
                // Retry transient cleanup failures before leaving recovery to the sweep.
                pipeline.retry(3) {
                    try {
                        pipeline.slurmResources.cleanUpNodeResources(pipeline, cluster, partition.clusterName, nodeName, slurmJobID)
                    } catch (InterruptedException e) {
                        throw e
                    } catch (Exception e) {
                        // retry(3) retries any exception. Keep the cleanup failure typed
                        // so its cause and scope survive if all attempts fail; the outer
                        // preservePrimaryFailure then retains any original stage failure.
                        throw new InfraFailure(
                            "Error during clean up SLURM resources: ${e.getMessage()} and retrying.",
                            e, InfraFailure.TRANSIENT, InfraFailure.SLURM, "<typed:slurm-cleanup-failure>")
                    }
                }
            }
            // Reaching here means cleanup succeeded within its retry budget. If all
            // attempts fail, the throw skips deregistration and leaves the entry for sweep.
            pipeline.slurmResources.deregisterSlurmResource(stageName, nodeName)
        }
        }
    }
}

return this
