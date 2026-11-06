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
import com.nvidia.bloom.FailureEvidenceCollector
import trtllm.FailureClassifier
import trtllm.exceptions.InfraFailure
import trtllm.exceptions.UserFailure

@Field def pipeline

def initialize(pipeline)
{
    this.pipeline = pipeline
    return this
}

def runLLMTestlistWithSbatch(pipeline, platform, testList, config=pipeline.VANILLA_CONFIG, stageName="Undefined", splitId=1, splits=1, gpuCount=1, nodeCount=1, skipInstallWheel=false, String postTag="", boolean useClusterDurations=false, Map placementContext=null, Map retryContext=null)
{
    SlurmPartition partition = SlurmConfig.resolvePlatform(platform)
    SlurmCluster cluster = SlurmConfig.clusterConfig[partition.clusterName]

    // Record which cluster this attempt ran on so a failed node is remembered
    // against its own cluster (auto: platforms pick a cluster at random per attempt).
    if (placementContext != null) {
        placementContext.lastSlurmClusterName = partition.clusterName
    }

    // Create a unique suffix for the job name
    String customSuffix = "${pipeline.testExecution.getShortenedJenkinsInstanceName()}-${pipeline.env.BUILD_TAG}-${UUID.randomUUID().toString().replaceAll("-", "").substring(0, 6)}".toLowerCase()
    def jobUID = "${cluster.host}-multi_node_test-${customSuffix}"
    def jobWorkspace = "/home/svc_tensorrt/bloom/scripts/${jobUID}"
    def disaggMultiNodeMode = stageName.contains("Disagg-PerfSanity")
    def aggMultiNodeMode = !disaggMultiNodeMode && nodeCount > 1 && stageName.contains("PerfSanity")
    def singleNvlinkDomainMode = stageName.contains("SingleNvlinkDomain")
    def infraDryRun = pipeline.testSelection.isInfraDryRun()
    if (infraDryRun) {
        testList = pipeline.INFRA_DRY_RUN_TEST_CONTEXT
        splitId = 1
        splits = 1
    }

    Utils.exec(pipeline, script: "env | sort && pwd && ls -alh")

    // Captured so the finally can suppress this attempt's junit when the failure is
    // a retryable infra failure (a retry follows) -- otherwise a stage that fails
    // an intermediate attempt and passes on retry leaves the build UNSTABLE.
    def caughtStageError = null
    Throwable primaryError = null

    try {
        // Run ssh command to start node in desired cluster via SLURM
        pipeline.withCredentials([
            pipeline.string(credentialsId: 'TRTLLM_HF_TOKEN', variable: 'HF_TOKEN'),
            pipeline.string(credentialsId: 'svc_tensorrt-swift-stack-key', variable: 'S3_SECRET_KEY'),
            pipeline.string(credentialsId: 'github_read_public_only_token', variable: 'GITHUB_CLONE_TOKEN'),
        ]) {
            CloudManager.withSlurmFrontendFailover(pipeline, partition.clusterName, cluster) { remote ->
            def tarName = pipeline.BUILD_CONFIGS[config][pipeline.TARNAME]
            def llmTarArtifactPath = pipeline.testBuild.boltedTarArtifactPath(pipeline, config, tarName)
            def llmPath = pipeline.sh (script: "realpath .", returnStdout: true).trim()
            def resourcePathNode = "/tmp"
            def llmSrcNode = "${resourcePathNode}/TensorRT-LLM/src"
            def llmSrcLocal = "${llmPath}/TensorRT-LLM/src"
            def scriptRunLocalPath = "${llmSrcLocal}/jenkins/scripts/slurm_run.sh"
            def scriptRunPathNode = "${jobWorkspace}/${jobUID}-slurm_run.sh"
            def scriptInstallLocalPath = "${llmSrcLocal}/jenkins/scripts/slurm_install.sh"
            def scriptInstallPathNode = "${jobWorkspace}/${jobUID}-slurm_install.sh"
            def scriptFatBuildLocalPath = "${llmSrcLocal}/jenkins/scripts/fat_build_inline.sh"
            def scriptFatBuildPathNode = "${jobWorkspace}/${jobUID}-fat_build_inline.sh"
            // Fat builder sbatch wrapper: generated inline by Groovy (not a static file).
            def scriptFatBuildSbatchPathLocal = Utils.createTempLocation(pipeline, "./fat_build_sbatch.sh")
            def scriptFatBuildSbatchPathNode = "${jobWorkspace}/${jobUID}-fat_build_sbatch.sh"
            def scriptBashUtilsLocalPath = "${llmSrcLocal}/jenkins/scripts/bash_utils.sh"
            def scriptBashUtilsPathNode = "${jobWorkspace}/${jobUID}-bash_utils.sh"
            def scriptFetchHelperPathNode = "${jobWorkspace}/${jobUID}-fetch_build_artifact.sh"
            def testListPathNode = "${jobWorkspace}/${testList}.txt"
            def waivesListPathNode = "${jobWorkspace}/waives.txt"
            def waivesListPathLocal = infraDryRun
                ? "${llmPath}/infra_dry_run_waives.txt"
                : "${llmSrcLocal}/tests/integration/test_lists/waives.txt"
            def slurmJobLogPath = "${jobWorkspace}/job-output.log"
            def scriptLaunchPathLocal = Utils.createTempLocation(pipeline, "./slurm_launch.sh")
            def scriptLaunchPathNode = "${jobWorkspace}/${jobUID}-slurm_launch.sh"
            def scriptPreparePathLocal = Utils.createTempLocation(pipeline, "./prepare_container.sh")
            def scriptPreparePathNode = "${jobWorkspace}/${jobUID}-prepare_container.sh"
            def scriptFatBuildBodyLocalPath = "${llmSrcLocal}/jenkins/scripts/fat_build_sbatch_body.sh"
            def scriptFatBuildBodyPathNode = "${jobWorkspace}/${jobUID}-fat_build_sbatch_body.sh"
            def scriptPrepareBodyLocalPath = "${llmSrcLocal}/jenkins/scripts/prepare_container.sh"
            def scriptPrepareBodyPathNode = "${jobWorkspace}/${jobUID}-prepare_container_body.sh"
            // The fat sqsh builder's own netrc; see deliverFatBuilderNetrc.
            def fatArtifactoryNetrcPathNode = "${jobWorkspace}/fat-artifactory-netrc"
            def scriptSubmitPathLocal = Utils.createTempLocation(pipeline, "./slurm_submit.sh")
            def scriptSubmitPathNode = "${jobWorkspace}/${jobUID}-slurm_submit.sh"
            def sbatchFailureMarker = "[TRTLLM_SBATCH_FINAL_FAILURE]"
            def scriptTrackPathLocal = Utils.createTempLocation(pipeline, "./slurm_track.sh")
            def scriptTrackPathNode = "${jobWorkspace}/${jobUID}-slurm_track.sh"
            def s3SecretKeyPathLocal = Utils.createTempLocation(pipeline, "./s3_secret_key")
            def s3SecretKeyPathNode = "${jobWorkspace}/s3_secret_key"
            // Per-job enroot config (never shared ~/.config/enroot — that races across jobs).
            def enrootConfigDirNode = "${jobWorkspace}/enroot-config"
            // Per-job netrc for the read helper that slurm_install.sh runs. It is not kept in
            // enrootConfigDirNode, which the ENROOT srun prologue deletes before slurm_install.sh runs.
            // The package's slurm_install.sh deletes it once every node of its step has finished
            // downloading the package, before the tests start; a package whose slurm_install.sh has
            // no remove_artifactory_netrc leaves it to cleanUpSlurmResources.
            def artifactoryNetrcPathNode = "${jobWorkspace}/artifactory-netrc"
            def coverageConfigFile = "${jobWorkspace}/.coveragerc"

            pipeline.stage("Initialize Test") {
                pipeline.println("Selected Cluster: ${cluster.name}")
                // Create Job Workspace folder in Frontend Node
                Utils.exec(pipeline, script: Utils.sshUserCmd(remote, "\"mkdir -p ${jobWorkspace}\""), numRetries: 3)

                // Download and Unzip Tar File. The node downloads it again itself, with the read
                // helper. The URL that --exists resolves is for the older slurm_install.sh inside a
                // package built before the helper existed, which downloads that URL instead.
                def llmTarfileLocation = ""
                pipeline.timeout(time: 30, unit: 'MINUTES') {
                    llmTarfileLocation = pipeline.testBuild.fetchBuildArtifact(pipeline, llmTarArtifactPath, null, "exists")
                    pipeline.testBuild.fetchBuildArtifact(pipeline, llmTarArtifactPath, "${llmPath}/${tarName}")
                }
                pipeline.sh "cd ${llmPath} && tar -zxf ${pipeline.BUILD_CONFIGS[config][pipeline.TARNAME]}"
                def llmTarfile = llmTarfileLocation.tokenize(" ")[0]

                Utils.exec(pipeline, script: "echo \"Script for Slurm srun job to submit: \" && cat ${scriptRunLocalPath}")
                Utils.copyFileToRemoteHost(
                    pipeline,
                    remote,
                    scriptRunLocalPath,
                    scriptRunPathNode,
                    true
                )

                Utils.exec(pipeline, script: "echo \"Script to install TensorRT LLM dependencies: \" && cat ${scriptInstallLocalPath}")
                Utils.copyFileToRemoteHost(
                    pipeline,
                    remote,
                    scriptInstallLocalPath,
                    scriptInstallPathNode,
                    true
                )
                // Image for both the fat sqsh builder and the job launch script below.
                def container = pipeline.LLM_DOCKER_IMAGE
                if (cluster.containerRuntime.toString() == "ENROOT") {
                    container = pipeline.LLM_DOCKER_IMAGE
                        .replace("${pipeline.ARTIFACTORY_DOCKER_HOST}/", "${pipeline.ARTIFACTORY_DOCKER_HOST}#")
                }
                if (cluster.fatBuilderArgs != null) {
                    Utils.exec(pipeline, script: "echo \"Script to build fat sqsh inline: \" && cat ${scriptFatBuildLocalPath}")
                    Utils.copyFileToRemoteHost(
                        pipeline,
                        remote,
                        scriptFatBuildLocalPath,
                        scriptFatBuildPathNode,
                        true
                    )
                    def fatSqshDir = "${cluster.scratchPath}/users/svc_tensorrt/fat_sqsh"
                    def containerDir = "${cluster.scratchPath}/users/svc_tensorrt/containers"
                    def fatBuildLogPath = SlurmConfig.getOutputFilePath("${cluster.homeDir}/slurm-logs", "${jobUID}-fat_build")

                    Utils.copyFileToRemoteHost(pipeline, remote, scriptFatBuildBodyLocalPath, scriptFatBuildBodyPathNode, true)
                    Utils.copyFileToRemoteHost(pipeline, remote, scriptPrepareBodyLocalPath, scriptPrepareBodyPathNode, true)

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
                    Utils.exec(pipeline, script: "echo \"Fat builder sbatch script: \" && cat ${scriptFatBuildSbatchPathLocal}")
                    Utils.copyFileToRemoteHost(
                        pipeline,
                        remote,
                        scriptFatBuildSbatchPathLocal,
                        scriptFatBuildSbatchPathNode,
                        true
                    )
                }
                Utils.exec(pipeline, script: "echo \"Script for Bash utilities: \" && cat ${scriptBashUtilsLocalPath}")
                Utils.copyFileToRemoteHost(
                    pipeline,
                    remote,
                    scriptBashUtilsLocalPath,
                    scriptBashUtilsPathNode,
                    true
                )
                // The read helper from the run's commit, which slurm_install.sh runs to download
                // the packaged build.
                def scriptFetchHelperLocalPath = pipeline.testBuild.readHelperPath(pipeline)
                Utils.copyFileToRemoteHost(
                    pipeline,
                    remote,
                    scriptFetchHelperLocalPath,
                    scriptFetchHelperPathNode,
                    true
                )

                // Generate Test List and Upload to Frontend Node
                def makoArgs = pipeline.testDb.getMakoArgsFromStageName(stageName, true)
                // TODO: currently the options will only be processed if the first
                // line is "Mako options:", maybe we can make it more generic, which
                // if the line cannot be split by "=", just ignore that line.
                def makoOptsJson = pipeline.testDb.transformMakoArgsToJson(["Mako options:"] + makoArgs)
                String clusterNameForDurations = useClusterDurations ? partition.clusterName.replaceAll('[^a-zA-Z0-9]', '_') : null
                def testListPathLocal = pipeline.testDb.renderTestDB(pipeline, testList, llmSrcLocal, stageName, makoOptsJson, clusterNameForDurations)
                // Copy the test list atomically. A retry that reuses a still-active job
                // re-copies over ${testListPathNode} while that job may be reading it via
                // --test-list; scp truncates-then-streams, so a concurrent read could see a
                // partial list and silently run a subset. Stage to a temp path and mv into
                // place (same-dir rename is atomic) so a reader sees the whole old or new file.
                Utils.copyFileToRemoteHost(
                    pipeline,
                    remote,
                    testListPathLocal,
                    "${testListPathNode}.tmp"
                )
                Utils.exec(
                    pipeline,
                    script: Utils.sshUserCmd(remote, "\"mv -f ${testListPathNode}.tmp ${testListPathNode}\"")
                )

                if (infraDryRun) {
                    pipeline.sh "mkdir -p ${llmPath} && : > ${waivesListPathLocal}"
                } else {
                    // Download and Merge waives.txt
                    pipeline.testResults.mergeWaivesTxt(pipeline, llmSrcLocal, stageName)

                    // Add passed test list from previous pipeline run to the waives.txt
                    if (pipeline.testFilter[(pipeline.REUSE_TEST)] != false) {
                        pipeline.testResults.reusePassedTestResults(llmSrcLocal, stageName, waivesListPathLocal, postTag)
                    }
                }

                Utils.copyFileToRemoteHost(
                    pipeline,
                    remote,
                    waivesListPathLocal,
                    waivesListPathNode
                )

                if (pipeline.ENABLE_UPLOAD_TEST_RESULTS) {
                    pipeline.writeFile(file: s3SecretKeyPathLocal, text: pipeline.S3_SECRET_KEY)
                    // Preserve the secret file mode through scp -p instead of issuing
                    // an extra remote chmod, which adds another flaky SSH round trip.
                    Utils.exec(pipeline, script: "chmod 600 ${s3SecretKeyPathLocal}")
                    Utils.copyFileToRemoteHost(
                        pipeline,
                        remote,
                        s3SecretKeyPathLocal,
                        s3SecretKeyPathNode
                    )
                }

                if (cluster.containerRuntime.toString() == "ENROOT") {
                    pipeline.withCredentials([pipeline.usernamePassword(
                        credentialsId: pipeline.ARTIFACTORY_CREDENTIALS_ID,
                        usernameVariable: 'ARTIFACTORY_USER',
                        passwordVariable: 'ARTIFACTORY_PASSWORD'
                    )]) {
                        // Unique per stage so parallel stages do not share one @tmp path.
                        def credsLocal = Utils.createTempLocation(pipeline, "./enroot_credentials-${stageName}")
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
                        Utils.exec(pipeline, script: Utils.sshUserCmd(remote, "\"mkdir -p ${enrootConfigDirNode}\""), numRetries: 3)
                        Utils.copyFileToRemoteHost(
                            pipeline,
                            remote,
                            credsLocal,
                            "${enrootConfigDirNode}/.credentials"
                        )
                    }
                }

                // Record this attempt's job before the token reaches its workspace, so that
                // the off-pod cleanup removes the workspace even if this pod dies before the
                // job is submitted. The registration after submission adds the job ID to the
                // same entry; an earlier attempt's entry stays for its own cleanup.
                pipeline.slurmResources.registerSlurmResource(stageName, [clusterName: partition.clusterName, jobUID: jobUID, usedSbatch: true])
                // Every job gets the helper's netrc: the read helper on the node contacts
                // artifactory.nvidia.com whenever aws is the primary, wherever the package is.
                // A frontend failover reruns this closure, which copies it again; when the submit
                // script then reuses a job that has already downloaded, that copy stays until
                // cleanUpSlurmResources. A job that the submit script resubmits, or that Slurm
                // requeues, after the first run deleted it has no netrc, and its download fails.
                def artifactoryNetrcPathLocal = "${pipeline.pwd(tmp: true)}/artifactory-netrc-${UUID.randomUUID().toString()}"
                try {
                    pipeline.testBuild.writeArtifactoryNetrc(pipeline, artifactoryNetrcPathLocal)
                    // scp -p keeps the netrc's 600 mode on the node.
                    Utils.copyFileToRemoteHost(
                        pipeline,
                        remote,
                        artifactoryNetrcPathLocal,
                        artifactoryNetrcPathNode
                    )
                } finally {
                    pipeline.sh(label: "Remove Artifactory netrc", script: "rm -f '${artifactoryNetrcPathLocal}'")
                }
                if (cluster.fatBuilderArgs != null) {
                    pipeline.testBuild.deliverFatBuilderNetrc(pipeline, remote, fatArtifactoryNetrcPathNode)
                }

                // Generate .coveragerc: CBTS stages render coveragerc.template; all other stages get an empty rcfile (no coverage).
                if (pipeline.testExecution.isCbtsStage(stageName)) {
                    // @TRTLLM_WHEEL_PATH@ stays a placeholder; slurm_run.sh substitutes it on the worker.
                    pipeline.sh """
                        cp ${llmSrcLocal}/jenkins/scripts/cbts/cbts/coverage/collection/coveragerc.template ./.coveragerc
                        sed -i \\
                            -e 's|@JOB_WORKSPACE@|${jobWorkspace}|g' \\
                            -e 's|@STAGE_NAME@|${stageName}|g' \\
                            ./.coveragerc
                        cat ./.coveragerc
                    """
                } else {
                    pipeline.sh "touch ./.coveragerc"
                }

                Utils.copyFileToRemoteHost(
                    pipeline,
                    remote,
                    "./.coveragerc",
                    coverageConfigFile
                )

                // Generate Pytest command
                String pytestUtil = ""
                if (nodeCount > 1) {
                    pytestUtil = "$llmSrcNode/tensorrt_llm/llmapi/trtllm-llmapi-launch"
                }
                def uploadPath = "${pipeline.env.JOB_NAME}/${pipeline.env.BUILD_NUMBER}"

                def clusterDurationsArgsNode = []
                if (useClusterDurations) {
                    def clusterKey = partition.clusterName.replaceAll('[^a-zA-Z0-9]', '_')
                    def clusterDurationsPathNode = "${llmSrcNode}/tests/integration/defs/.test_durations_${clusterKey}"
                    clusterDurationsArgsNode = ["--durations-path ${clusterDurationsPathNode}"]
                }
                def extraArgs = [
                    "--test-list=$testListPathNode",
                    "--splitting-algorithm least_duration",
                    "--splits $splits",
                    "--group $splitId",
                    *clusterDurationsArgsNode,
                ]
                if (pipeline.ENABLE_UPLOAD_TEST_RESULTS && !pipeline.testFilter[(pipeline.DETAILED_LOG)]) {
                    extraArgs += [
                        "--capture=fd",
                        "--s3-upload-path=${uploadPath}/${stageName}",
                        "--s3-upload-mode=deferred",
                    ]
                }
                def pytestCommandParts = pipeline.testExecution.getPytestBaseCommandLine(
                    llmSrcNode,
                    stageName,
                    waivesListPathNode,
                    jobWorkspace,
                    "$jobWorkspace/.coveragerc",
                    pytestUtil,
                    extraArgs,
                    0,
                    0,
                    partition.clusterName,
                )
                pytestCommandParts += pipeline.testDb.getInfraDryRunPytestTargets(testListPathLocal)
                def pytestCommand = pytestCommandParts.join(" ")

                // Generate Job Launch Script
                def mounts = pipeline.testExecution.getMountListForSlurmTest(cluster, true).join(",")
                // Request one Slurm segment for multi-node disaggregated/aggregated perf-sanity
                // and SingleNvlinkDomain stages; the cluster defines its topology constraints.
                String[] taskArgs = pipeline.testExecution.getNodeArgs(nodeCount, gpuCount, disaggMultiNodeMode || aggMultiNodeMode || singleNvlinkDomainMode)
                if (taskArgs == null) {
                    pipeline.error "Invalid Slurm test stage name is set"
                }
                taskArgs = [
                    *taskArgs,
                ]

                def containerImageArg = container
                def srunPrologue = ""
                if (cluster.containerRuntime.toString() == "ENROOT") {
                    // containerImageArg is resolved at bash runtime via $enrootImagePath,
                    // which srunPrologue sets to the fat sqsh (pre-built) or the digest-named
                    // base sqsh (fallback). Base sqsh is shared across jobs by image digest.
                    containerImageArg = "\${enrootImagePath}"
                    def containerDir = "${cluster.scratchPath}/users/svc_tensorrt/containers"
                    def fatSqshDir = "${cluster.scratchPath}/users/svc_tensorrt/fat_sqsh"
                    def fatSqsh = pipeline.testBuild.computeFatSqshPath(fatSqshDir, llmTarArtifactPath, scriptFatBuildLocalPath)
                    def fatBuildScriptHash = fatSqsh.scriptHash
                    def fatHash = fatSqsh.hash
                    def fatSqshPath = fatSqsh.path

                    srunPrologue = pipeline.sbatchScripts.generateSbatchEnrootPrologue(fatBuildScriptHash, fatHash, fatSqshPath, enrootConfigDirNode, containerDir, container)
                }

                // Define environment variables to export
                def envVarNames = [
                    'OPEN_SEARCH_DB_BASE_URL',
                    'OPEN_SEARCH_DB_CREDENTIALS_USR',
                    'OPEN_SEARCH_DB_CREDENTIALS_PSW',
                    'BUILD_ID',
                    'BUILD_URL',
                    'JOB_NAME',
                    'globalVars',
                    'gitlabCommit',
                    'TRTLLM_PERF_SANITY_CHECKPOINT_IO_POLICY'
                ]
                def envVarsToExport = [:]
                envVarNames.each { varName ->
                    envVarsToExport[varName] = pipeline.env."${varName}"
                }

                def srunArgs = [
                    "--container-name=multi_node_test-\${SLURM_JOB_ID}",
                    "--container-image=$containerImageArg",
                    "--container-workdir=$jobWorkspace",
                    "--container-mounts=$mounts",
                    "--no-container-mount-home",
                    "--container-env=NVIDIA_IMEX_CHANNELS",
                    "--container-env=SKIP_INSTALL"
                ]
                if (pipeline.ENABLE_UPLOAD_TEST_RESULTS) {
                    srunArgs.add("--container-env=S3_SECRET_KEY")
                }
                srunArgs.add("--container-env=GITHUB_CLONE_TOKEN")
                envVarsToExport.each { varName, varValue ->
                    srunArgs.add("--container-env=${varName}")
                }

                def exemptionComment = ""
                if (SlurmConfig.needsIdleGpuExemption(cluster)) {
                    exemptionComment = "--comment='${SlurmConfig.IDLE_GPU_EXEMPTION_PAYLOAD}'"
                }
                def slurmExcludeArg = pipeline.trtllm_utils.buildSlurmExcludeArg(placementContext?.excludedSlurmNodeListsByCluster?.get(partition.clusterName))
                def slurmExcludeDirective = slurmExcludeArg ? "#SBATCH ${slurmExcludeArg}" : ""
                if (slurmExcludeArg) {
                    pipeline.echo "[INFRA-RETRY] ${stageName}: requesting SLURM retry placement exclusion: ${slurmExcludeArg}"
                }

                def envExportStatements = envVarsToExport.collect { varName, varValue ->
                    def escapedValue = varValue?.toString() ?: ''
                    escapedValue = escapedValue
                        .replace('\\', '\\\\')    // Backslash
                        .replace('"', '\\"')      // Double quote
                        .replace('$', '\\$')      // Dollar sign (prevent variable expansion)
                        .replace('`', '\\`')      // Backtick (prevent command substitution)
                    "export ${varName}=\"${escapedValue}\""
                }.join('\n')

                def scriptLaunchPrefix = pipeline.sbatchScripts.generateSbatchLaunchPrefix(partition, taskArgs, [
                    exemptionComment: exemptionComment,
                    slurmJobLogPath: slurmJobLogPath,
                    slurmExcludeDirective: slurmExcludeDirective,
                    jobWorkspace: jobWorkspace,
                    tarName: tarName,
                    llmTarArtifactPath: llmTarArtifactPath,
                    artifactoryNetrcPathNode: artifactoryNetrcPathNode,
                    LLM_ARTIFACTS_CREDENTIALS_ID: pipeline.LLM_ARTIFACTS_CREDENTIALS_ID,
                    llmTarfile: llmTarfile,
                    llmSrcNode: llmSrcNode,
                    stageName: stageName,
                    infraDryRun: infraDryRun,
                    resourcePathNode: resourcePathNode,
                    pytestCommand: pytestCommand,
                    coverageConfigFile: coverageConfigFile,
                    HF_TOKEN: pipeline.HF_TOKEN,
                    GITHUB_CLONE_TOKEN: pipeline.GITHUB_CLONE_TOKEN,
                    s3SecretKeyPathNode: s3SecretKeyPathNode,
                    envExportStatements: envExportStatements,
                    srunPrologue: srunPrologue,
                    defaultTimeoutShort: SlurmConfig.DEFAULT_TIMEOUT_SHORT,
                    artifactsPrimary: pipeline.artifactPaths.llmArtifactsPrimary(),
                ])

                if (!pipeline.testSelection.isInfraDryRun() && (disaggMultiNodeMode || aggMultiNodeMode)) {
                    def scriptLaunchPrefixPathLocal = Utils.createTempLocation(pipeline, "./slurm_launch_prefix.sh")
                    def scriptLaunchSrunArgsPathLocal = Utils.createTempLocation(pipeline, "./slurm_srun_args.txt")
                    // The unified submit.py handles both agg and disagg; only the
                    // draft launch script differs between the two paths.
                    def scriptLaunchDraftPathLocal = disaggMultiNodeMode
                        ? "${llmSrcLocal}/jenkins/scripts/perf/disaggregated/slurm_launch_draft.sh"
                        : "${llmSrcLocal}/jenkins/scripts/perf/aggregated/slurm_launch_draft.sh"
                    def scriptSubmitLocalPath = "${llmSrcLocal}/jenkins/scripts/perf/submit.py"

                    srunArgs.removeAll { it == "--mpi=pmi2" || it == "--mpi=pmix" }

                    pipeline.writeFile(file: scriptLaunchPrefixPathLocal, text: scriptLaunchPrefix)
                    pipeline.writeFile(file: scriptLaunchSrunArgsPathLocal, text: srunArgs.join(" "))

                    pipeline.sh """
                        pip3 install 'pyyaml>=6.0.1,<6.0.3' && \\
                        python3 ${scriptSubmitLocalPath} \\
                        --llm-src ${llmSrcLocal} \\
                        --test-list ${testListPathLocal} \\
                        --draft-launch-sh ${scriptLaunchDraftPathLocal} \\
                        --launch-sh ${scriptLaunchPathLocal} \\
                        --run-sh ${scriptRunPathNode} \\
                        --install-sh ${scriptInstallPathNode} \\
                        --script-prefix ${scriptLaunchPrefixPathLocal} \\
                        --srun-args ${scriptLaunchSrunArgsPathLocal} \\
                        --split-group ${splitId} \\
                        --stage-name ${stageName} \\
                        --cluster-name ${partition.clusterName}
                    """
                } else {
                    if(nodeCount > 1) {
                        srunArgs.add("--mpi=pmix")
                    }

                    def scriptContent = pipeline.sbatchScripts.generateSbatchLaunchScript(scriptLaunchPrefix, srunArgs, scriptRunPathNode)
                    pipeline.writeFile(file: scriptLaunchPathLocal, text: scriptContent)
                }

                Utils.exec(pipeline, script: "echo \"Script for Slurm sbatch job to submit: \" && cat ${scriptLaunchPathLocal}")
                Utils.copyFileToRemoteHost(
                    pipeline,
                    remote,
                    scriptLaunchPathLocal,
                    scriptLaunchPathNode,
                    true
                )

                def filesToKeepWhenRetry = [
                    scriptRunPathNode,
                    scriptInstallPathNode,
                    scriptFatBuildPathNode,
                    scriptFatBuildSbatchPathNode,
                    scriptFatBuildBodyPathNode,
                    scriptPrepareBodyPathNode,
                    scriptBashUtilsPathNode,
                    scriptFetchHelperPathNode,
                    artifactoryNetrcPathNode,
                    fatArtifactoryNetrcPathNode,
                    scriptLaunchPathNode,
                    scriptPreparePathNode,
                    scriptSubmitPathNode,
                    scriptTrackPathNode,
                    testListPathNode,
                    waivesListPathNode,
                    coverageConfigFile
                ]
                if (pipeline.ENABLE_UPLOAD_TEST_RESULTS) {
                    filesToKeepWhenRetry += [
                        s3SecretKeyPathNode
                    ]
                }
                if (cluster.containerRuntime.toString() == "ENROOT") {
                    filesToKeepWhenRetry += [
                        enrootConfigDirNode
                    ]
                }
                def findKeepWhenRetryArgs = filesToKeepWhenRetry.collect { " ! -name \"\$(basename \"${it}\")\"" }.join("")

                // Generate Prepare Container script: submits the CPU fat-sqsh builder job
                // (if needed) and polls until it completes. Run Pytest starts only after
                // this stage returns, so the fat sqsh is already on disk when the GPU job
                // starts and the srunPrologue can reuse it (SKIP_INSTALL=1).
                def fatSqshDir = "${cluster.scratchPath}/users/svc_tensorrt/fat_sqsh"
                def scriptPrepareContent = "#!/bin/bash\nset -euo pipefail\necho 'No fat sqsh builder configured; skipping Prepare Container.'\n"
                if (cluster.fatBuilderArgs != null) {
                    def fatBuildLogPath = SlurmConfig.getOutputFilePath("${cluster.homeDir}/slurm-logs", "${jobUID}-fat_build")
                    scriptPrepareContent = pipeline.testBuild.generateFatPrepareScript(
                        fatSqshDir: fatSqshDir,
                        llmTarArtifactPath: llmTarArtifactPath,
                        llmDockerImage: pipeline.LLM_DOCKER_IMAGE,
                        fatBuildScriptPathNode: scriptFatBuildPathNode,
                        fatBuildSbatchPathNode: scriptFatBuildSbatchPathNode,
                        fatBuildLogPath: fatBuildLogPath,
                        artifactoryNetrcPathNode: fatArtifactoryNetrcPathNode,
                        prepareBodyPathNode: scriptPrepareBodyPathNode
                    )
                }
                pipeline.writeFile(file: scriptPreparePathLocal, text: scriptPrepareContent)
                Utils.exec(pipeline, script: "echo 'Prepare Container script:' && cat ${scriptPreparePathLocal}")
                Utils.copyFileToRemoteHost(
                    pipeline,
                    remote,
                    scriptPreparePathLocal,
                    scriptPreparePathNode,
                    true
                )

                def scriptSubmit = pipeline.sbatchScripts.generateSbatchSubmitScript(
                    jobWorkspace, findKeepWhenRetryArgs, slurmJobLogPath, sbatchFailureMarker,
                    SlurmConfig.withGpuBudget(pipeline, partition.clusterName, "sbatch ${scriptLaunchPathNode}", gpuCount))

                pipeline.writeFile(file: scriptSubmitPathLocal, text: scriptSubmit)
                Utils.exec(pipeline, script: "echo \"Script to submit the final Slurm job: \" && cat ${scriptSubmitPathLocal}")
                Utils.copyFileToRemoteHost(
                    pipeline,
                    remote,
                    scriptSubmitPathLocal,
                    scriptSubmitPathNode,
                    true
                )
            }

            // Submit the CPU fat-sqsh builder job (if configured) and poll until it
            // completes. Non-fatal: any failure here lets the GPU job fall back to base
            // sqsh + full pip install. Runs on every attempt: prepare_container.sh exits
            // immediately if the fat sqsh already exists, and a retry whose attempt 1
            // died before the build still needs one.
            pipeline.stage("[${stageName}] Prepare Container") {
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
                    pipeline.echo "[Prepare Container] Non-fatal failure: ${e.message}. GPU job will fall back to base sqsh + full install."
                }
            }

            pipeline.stage("[${stageName}] Run Pytest") {
                // Submit the Slurm job. Submit/metadata/track all run on the one
                // frontend the enclosing withSlurmFrontendFailover pinned, so they
                // share the job workspace (slurm_job_id.txt, scripts) on that login
                // node; a frontend disconnect fails the whole closure over to a fresh
                // frontend as a unit (the submit script reuses an active job).
                try {
                    Utils.exec(
                        pipeline,
                        timeout: false,
                        script: Utils.sshUserCmd(remote, scriptSubmitPathNode),
                        numRetries: 3
                    )
                } catch (InterruptedException e) {
                    throw e
                } catch (Exception submitEx) {
                    // The step log contains set -x command text, including catalog
                    // signatures used by the submit script itself. Prefer the bounded
                    // final-failure line so only the actual final sbatch output is
                    // classified; let the outer retry boundary scan the whole step log
                    // only if no marker exists.
                    def diagnosticEvidence = FailureEvidenceCollector.collectOriginatingStepEvidence(
                        pipeline,
                        submitEx,
                        [[id: "slurm-sbatch-final-failure", anyOf: [sbatchFailureMarker]]]
                    )
                    if (diagnosticEvidence.collectionStatus == "MATCHED") {
                        def finalFailure = new Exception(
                            "SLURM job submission failed for ${stageName}: ${diagnosticEvidence.matchedMessage}",
                            submitEx
                        )
                        throw FailureClassifier.classify(finalFailure, InfraFailure.SLURM)
                    }
                    throw submitEx
                }

                def slurmMetadata = pipeline.slurmResources.captureSlurmWorkspaceMetadata(pipeline, remote, jobWorkspace, placementContext, stageName)
                def slurmJobId = slurmMetadata.slurmJobId
                if (!slurmJobId) {
                    // `|| true` so a missing file (submission never wrote it) yields
                    // an empty string and reaches the typed throw below, instead of a
                    // generic "script returned exit code 1" the classifier can't act on.
                    slurmJobId = Utils.exec(
                        pipeline,
                        script: Utils.sshUserCmd(remote, "\"cat ${jobWorkspace}/slurm_job_id.txt 2>/dev/null || true\""),
                        returnStdout: true,
                        numRetries: 3
                    ).trim()
                    pipeline.slurmResources.recordSlurmPlacementContext(placementContext, slurmJobId, null, stageName)
                }
                if (!pipeline.slurmResources.isValidSlurmJobId(slurmJobId)) {
                    // The job never entered the SLURM queue (sbatch failed or the ID
                    // was never captured), so nothing ran on any node: retryable
                    // infra by construction, and node-avoidance has nothing to avoid.
                    throw new InfraFailure(
                        "SLURM job submission for ${stageName} produced no usable job ID " +
                        "(slurm_job_id.txt missing, empty, or non-numeric in ${jobWorkspace}); " +
                        "the job never entered the queue.",
                        null, InfraFailure.TRANSIENT, InfraFailure.SLURM, "<typed:slurm-submit-no-jobid>")
                }
                Utils.exec(pipeline, script: "echo Slurm job ID: ${slurmJobId}")
                // Record the live SLURM job so a dispatcher-pod death can be reconciled
                // off-pod (the in-pod cleanup can't reach the login node once the pod is
                // gone). Deregistered when cleanup actually runs.
                pipeline.slurmResources.registerSlurmResource(stageName, [clusterName: partition.clusterName, jobUID: jobUID, slurmJobId: slurmJobId, usedSbatch: true])

                def scriptTrack = pipeline.sbatchScripts.generateSbatchTrackScript(slurmJobId, slurmJobLogPath, jobWorkspace, pipeline.UPLOAD_PATH, stageName, postTag)

                pipeline.writeFile(file: scriptTrackPathLocal, text: scriptTrack)
                Utils.exec(pipeline, script: "echo \"Script to track Slurm job and pull the log: \" && cat ${scriptTrackPathLocal}")
                Utils.copyFileToRemoteHost(
                    pipeline,
                    remote,
                    scriptTrackPathLocal,
                    scriptTrackPathNode,
                    true
                )

                // Monitor the job. The track script always exits 0 once it records a
                // verdict, so a re-run can't change a terminal state. A frontend lost
                // mid-monitor is recovered by the enclosing withSlurmFrontendFailover
                // -- it fails the closure over to another frontend and the submit guard
                // reuses the still-active job -- so the monitor needs no same-frontend
                // retries of its own.
                // Track the Slurm job alongside a controller-side watcher
                // that SSH-stats the remote results.xml and SCPs / uploads
                // a progress tar whenever the file's mtime advances. Both
                // run inside a single `sh` step (track in foreground, watcher
                // as a background subshell) so Blue Ocean renders the stage
                // as a single box instead of a nested parallel split.
                def pytestDoneFile = "${pipeline.WORKSPACE}/.pytest-done-${stageName}"
                def progressTar = "results-${stageName}${postTag}-progress.tar.gz"
                def progressUrl = "https://urm.nvidia.com/artifactory/${pipeline.UPLOAD_PATH}/test-results/${progressTar}"
                def remoteWorkspaceTrk = "/home/svc_tensorrt/bloom/scripts/${jobUID}"
                def trackCmd = Utils.sshUserCmd(remote, scriptTrackPathNode)
                // Stat on the compute node via srun --overlap to bypass the login
                // node's NFS attribute cache.  The login node's NFS client can cache
                // a stale directory view for up to acdirmax seconds after the compute
                // node creates results.xml; running stat directly on the node that
                // wrote the file avoids this cross-client visibility window entirely.
                // After the mtime check confirms a change, sshRefreshCacheCmd primes
                // the login node's NFS cache before the SCP step reads the file.
                // Some clusters' srun cli_filter plugin rejects any job step that
                // doesn't carry -A <account> (e.g. "You must specify an account"),
                // so this overlap step needs the same account the sbatch submission
                // used, pulled out of partition.additionalArgs.
                def slurmAccountMatch = (partition.additionalArgs =~ /(?:^|\s)-A\s+(\S+)/)
                def srunAccountArg = slurmAccountMatch.find() ? "-A ${slurmAccountMatch.group(1)} " : ""
                def sshStatCmd = Utils.sshUserCmd(remote, "\"srun --overlap ${srunAccountArg}--quiet --jobid='${slurmJobId}' --ntasks=1 stat -c %Y '${remoteWorkspaceTrk}/results.xml' || echo 0\"")
                def scpXmlCmd = pipeline.slurmResources.scpFromRemoteCmd(remote, "${remoteWorkspaceTrk}/results*.xml", "${stageName}/")
                def scpUnfinishedCmd = pipeline.slurmResources.scpFromRemoteCmd(remote, "${remoteWorkspaceTrk}/unfinished_test.txt", "${stageName}/")
                def sshRefreshCacheCmd = Utils.sshUserCmd(remote, "\"ls '${remoteWorkspaceTrk}/' > /dev/null 2>&1; ls -la '${remoteWorkspaceTrk}/results.xml' > /dev/null 2>&1 || true\"")
                def sshListPerfCmd = Utils.sshUserCmd(remote, "\"find '${remoteWorkspaceTrk}' -maxdepth 1 -type d \\( -name 'aggr*' -o -name 'disagg*' \\) -print 2>/dev/null || true\"")
                def scpPerfTemplate = pipeline.slurmResources.scpFromRemoteCmd(remote, "PERF_FOLDER_PLACEHOLDER", "${stageName}/")
                pipeline.sh "rm -f ${pytestDoneFile}"
                pipeline.withCredentials([pipeline.usernamePassword(
                        credentialsId: 'urm-artifactory-creds',
                        usernameVariable: 'ART_USER',
                        passwordVariable: 'ART_PASS')]) {
                    pipeline.sh """#!/bin/bash
                        set +e
                        export STAGE_NAME='${stageName}'
                        export PROGRESS_TAR='${progressTar}'
                        export PROGRESS_URL='${progressUrl}'
                        export TIMEOUT_XML_SCRIPT='${llmSrcLocal}/jenkins/scripts/generate_timeout_xml.py'
                        export POST_TAG='${postTag}'
                        # ---- background watcher: SSH-stat remote XML, SCP, tar, upload ----
                        PROGRESS_DONE_FILE='${pytestDoneFile}' \\
                        PROGRESS_INTERVAL=${pipeline.PROGRESS_UPLOAD_INTERVAL_SEC} \\
                        LABEL_PREFIX='sbatch checkpoint' \\
                        SLURM_SSH_STAT_CMD='${sshStatCmd}' \\
                        SLURM_SSH_REFRESH_CACHE_CMD='${sshRefreshCacheCmd}' \\
                        SLURM_SCP_XML_CMD='${scpXmlCmd}' \\
                        SLURM_SCP_UNFINISHED_CMD='${scpUnfinishedCmd}' \\
                        SLURM_SSH_LIST_PERF_CMD='${sshListPerfCmd}' \\
                        SLURM_SCP_PERF_TEMPLATE='${scpPerfTemplate}' \\
                        bash '${llmSrcLocal}/jenkins/scripts/progress_upload_watcher.sh' &
                        WATCHER_PID=\$!

                        # ---- foreground track: retry up to 3 times on failure ----
                        attempt=0
                        rc=1
                        while [ \$attempt -lt 3 ]; do
                            ${trackCmd}
                            rc=\$?
                            [ \$rc -eq 0 ] && break
                            attempt=\$((attempt+1))
                            [ \$attempt -lt 3 ] && echo "Track failed (rc=\$rc), retry \$attempt/3" && sleep 30
                        done

                        touch '${pytestDoneFile}'
                        wait \$WATCHER_PID 2>/dev/null || true

                        # ---- immediate final snapshot ----
                        mkdir -p '${pipeline.WORKSPACE}/${stageName}'
                        ${sshRefreshCacheCmd}
                        for _attempt in 1 2 3; do
                            ${scpXmlCmd} && break
                            [ \$_attempt -lt 3 ] && echo "[PROGRESS-UPLOAD] ${stageName}: scp xml failed, retry \$_attempt/3" && sleep 10
                        done || true
                        _unfinished_ok=0
                        for _attempt in 1 2 3; do
                            ${scpUnfinishedCmd} && { _unfinished_ok=1; break; }
                            echo "[PROGRESS-UPLOAD] ${stageName}: scp unfinished failed (attempt \$_attempt/3)"
                            [ \$_attempt -lt 3 ] && sleep 10
                        done
                        [ "\$_unfinished_ok" -eq 0 ] && echo "[PROGRESS-UPLOAD] ${stageName}: scp unfinished not available, skipping"
                        SCP_PERF_TMPL='${scpPerfTemplate}'
                        while IFS= read -r perf_folder; do
                            [ -z "\$perf_folder" ] && continue
                            _perf_ok=0
                            for _attempt in 1 2 3; do
                                eval "\${SCP_PERF_TMPL//PERF_FOLDER_PLACEHOLDER/\$perf_folder}" && { _perf_ok=1; break; }
                                echo "[PROGRESS-UPLOAD] ${stageName}: scp perf \$perf_folder failed (attempt \$_attempt/3)"
                                [ \$_attempt -lt 3 ] && sleep 10
                            done
                            [ "\$_perf_ok" -eq 0 ] && echo "[PROGRESS-UPLOAD] ${stageName}: scp perf \$perf_folder failed after 3 attempts"
                        done < <(eval '${sshListPerfCmd}' 2>/dev/null || true)
                        if [ -f '${pipeline.WORKSPACE}/${stageName}/results.xml' ]; then
                            LABEL='sbatch final snapshot' FINAL_SNAPSHOT=1 \\
                            bash '${llmSrcLocal}/jenkins/scripts/progress_upload_snapshot.sh' || true
                        fi

                        exit \$rc
                    """
                }

                // Verdict: "<STATE>|<EXIT_CODE>"; success is COMPLETED + exit 0.
                // STATE may contain spaces.
                def jobResult = pipeline.slurmResources.readSlurmWorkspaceFile(pipeline, remote, "${jobWorkspace}/slurm_job_result.txt", stageName, 3)
                def resultFields = jobResult ? jobResult.tokenize('|') : []
                def jobState = resultFields ? resultFields[0]?.tokenize(' ')?.getAt(0) : null
                def jobExit = resultFields.size() > 1 ? resultFields[1] : null
                if (jobState != "COMPLETED" || jobExit != "0") {
                    // Verdict unreadable: fall back to an authoritative sacct query.
                    def slurmState = jobState ?: pipeline.slurmResources.querySlurmJobState(pipeline, cluster, partition.clusterName, slurmJobId)
                    // ... and re-confirm success, so a transient read blip on a job
                    // that actually passed doesn't fail the stage.
                    if (jobState == null && slurmState == "COMPLETED") {
                        pipeline.echo "[INFRA-RETRY] ${stageName}: verdict unreadable but sacct reports COMPLETED for ${slurmJobId}; treating as success."
                        return
                    }
                    // TIMEOUT is a walltime kill -- typed UserFailure so neither
                    // retry layer re-runs a job that would just time out again.
                    if (slurmState == "TIMEOUT") {
                        throw new UserFailure(
                            "SLURM job ${slurmJobId} for ${stageName} ended in state TIMEOUT " +
                            "(hit partition walltime ${partition?.time}min); not retrying.",
                            null)
                    }
                    // Verdict unreadable but the job is still alive: a transport blip
                    // dropped the monitor while the job kept running, so this is infra,
                    // not a test failure.
                    if (pipeline.slurmResources.isNonTerminalSlurmState(slurmState)) {
                        throw new InfraFailure(
                            "SLURM job ${slurmJobId} for ${stageName} is still in non-terminal state ${slurmState}; " +
                            "the monitor lost contact while the job was alive (transient infra), so this is not a " +
                            "test failure.",
                            null, InfraFailure.TRANSIENT, InfraFailure.SLURM, "<typed:slurm-job-still-running>")
                    }
                    def jobFailure = new Exception(
                        "SLURM job ${slurmJobId} for ${stageName} failed with state=" +
                        "${slurmState ?: 'unknown'}, exit=${jobExit ?: 'unknown'}")
                    def evidence = pipeline.slurmResources.collectSlurmJobLogEvidence(
                        pipeline,
                        remote,
                        slurmJobLogPath,
                    )
                    def classified = FailureClassifier.classify(jobFailure, InfraFailure.SLURM, evidence)
                    if (classified instanceof InfraFailure) {
                        def classificationSource = evidence.collectionStatus == "MATCHED" ? "job log" : "job state"
                        pipeline.echo "[INFRA-RETRY] ${stageName}: SLURM ${classificationSource} matched " +
                            "${classified.detectedPattern}: " +
                            classified.message
                        throw classified
                    }
                    pipeline.echo "[INFRA-RETRY] ${stageName}: SLURM job ${slurmJobId} state=${slurmState ?: 'unknown'}, exit=${jobExit ?: 'unknown'}; deferring to classifier."
                    throw jobFailure
                }
            }
            pipeline.echo "Finished test stage execution."
            }  // end CloudManager.withSlurmFrontendFailover
        }  // end withCredentials
    } catch (InterruptedException e) {
        primaryError = e
        throw e
    } catch (Exception e) {
        primaryError = e
        caughtStageError = e
        throw e
    } finally {
        pipeline.infraRetry.preservePrimaryFailure(primaryError) {
        // Resource cleanup must run even if metadata capture or result upload is interrupted.
        try {
            pipeline.slurmResources.captureSlurmJobNodeList(pipeline, cluster, partition.clusterName, placementContext?.slurmJobId ?: null, placementContext, stageName, jobWorkspace)
            // Suppress this attempt's junit when a retry is still planned (a retryable
            // infra failure with budget), so a retried-and-passed stage doesn't leave
            // the build UNSTABLE from an intermediate attempt's results. A genuine test
            // failure classifies as UserFailure -> not suppressed -> reported.
            boolean suppressTestReporting = (caughtStageError != null && retryContext != null) &&
                pipeline.infraRetry.retryContextAllowsRetry(pipeline, retryContext, caughtStageError, false)
            pipeline.testResults.uploadResults(pipeline, cluster, partition.clusterName, jobUID, stageName, postTag, suppressTestReporting)
            pipeline.testResults.deleteProgressArtifact(stageName, postTag)
        } finally {
            pipeline.stage("Clean Up Slurm Resource") {
                // Retry transient cleanup failures before leaving recovery to the sweep.
                pipeline.retry(3) {
                    try {
                        pipeline.slurmResources.cleanUpSlurmResources(pipeline, cluster, partition.clusterName, jobUID)
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
            pipeline.slurmResources.deregisterSlurmResource(stageName, jobUID)
        }
        }
    }
}

return this
