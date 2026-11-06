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

def initialize(pipeline) { return this }

// Shell text for Slurm submission, launch, and tracking.

def generateSbatchLaunchPrefix(partition, taskArgs, Map values)
{
    def exemptionComment = values.exemptionComment
    def slurmJobLogPath = values.slurmJobLogPath
    def slurmExcludeDirective = values.slurmExcludeDirective
    def jobWorkspace = values.jobWorkspace
    def tarName = values.tarName
    def llmTarArtifactPath = values.llmTarArtifactPath
    def artifactoryNetrcPathNode = values.artifactoryNetrcPathNode
    def LLM_ARTIFACTS_CREDENTIALS_ID = values.LLM_ARTIFACTS_CREDENTIALS_ID
    def llmTarfile = values.llmTarfile
    def llmSrcNode = values.llmSrcNode
    def stageName = values.stageName
    def infraDryRun = values.infraDryRun
    def resourcePathNode = values.resourcePathNode
    def pytestCommand = values.pytestCommand
    def coverageConfigFile = values.coverageConfigFile
    def HF_TOKEN = values.HF_TOKEN
    def GITHUB_CLONE_TOKEN = values.GITHUB_CLONE_TOKEN
    def s3SecretKeyPathNode = values.s3SecretKeyPathNode
    def envExportStatements = values.envExportStatements
    def srunPrologue = values.srunPrologue
    def defaultTimeoutShort = values.defaultTimeoutShort
    def artifactsPrimary = values.artifactsPrimary
    return """#!/bin/bash
                    #SBATCH ${exemptionComment}
                    #SBATCH --output=${slurmJobLogPath}
                    ${taskArgs.collect { "#SBATCH $it" }.join('\n')}
                    #SBATCH ${partition.additionalArgs}
                    ${slurmExcludeDirective}
                    ${partition?.time ? "#SBATCH --time=${partition.time}" : "#SBATCH --time=${defaultTimeoutShort}"}
                    ${(partition?.name && partition.name != "unspecified") ? "#SBATCH --partition=${partition.name}" : ""}

                    # SBATCH directives must appear before any executable commands.
                    set -xEeuo pipefail
                    trap 'rc=\$?; echo "Error in file \${BASH_SOURCE[0]} on line \$LINENO: \$BASH_COMMAND (exit \$rc)"; exit \$rc' ERR

                    echo "Starting Slurm job \$SLURM_JOB_ID on \$SLURM_NODELIST"
                    export jobWorkspace=$jobWorkspace
                    export tarName=$tarName
                    export llmTarArtifactPath=$llmTarArtifactPath
                    export LLM_ARTIFACTS_PRIMARY=${artifactsPrimary}
                    export ARTIFACTORY_NETRC=$artifactoryNetrcPathNode
                    export ARTIFACTORY_CREDENTIAL_ID=$LLM_ARTIFACTS_CREDENTIALS_ID
                    export llmTarfile=$llmTarfile
                    export llmSrcNode=$llmSrcNode
                    export stageName=$stageName
                    ${infraDryRun ? "export infraDryRun=true" : ""}
                    export resourcePathNode=$resourcePathNode
                    export pytestCommand="$pytestCommand"
                    export coverageConfigFile="$coverageConfigFile"
                    # Keep xtrace off around the token exports so they are not echoed into the Slurm job log.
                    set +x
                    export HF_TOKEN=$HF_TOKEN
                    export GITHUB_CLONE_TOKEN=$GITHUB_CLONE_TOKEN
                    set -x
                    if [ -f "${s3SecretKeyPathNode}" ]; then
                        set +x
                        export S3_SECRET_KEY="\$(cat "${s3SecretKeyPathNode}")"
                        set -x
                    fi
                    export NVIDIA_IMEX_CHANNELS=\${NVIDIA_IMEX_CHANNELS:-0}
                    export NVIDIA_VISIBLE_DEVICES=\${NVIDIA_VISIBLE_DEVICES:-\$(seq -s, 0 \$((\$(nvidia-smi --query-gpu=count -i 0 --format=csv,noheader)-1)))}
                    # Keep xtrace off here too: the exported pipeline env includes OpenSearch credentials.
                    set +x
                    ${envExportStatements}
                    set -x

                    echo "Env NVIDIA_IMEX_CHANNELS: \$NVIDIA_IMEX_CHANNELS"
                    echo "Env NVIDIA_VISIBLE_DEVICES: \$NVIDIA_VISIBLE_DEVICES"

                    ${srunPrologue}
                """.replaceAll("(?m)^\\s*", "")
}

def generateSbatchLaunchScript(scriptLaunchPrefix, srunArgs, scriptRunPathNode)
{
    return """
                        ${scriptLaunchPrefix}
                        srun --kill-on-bad-exit=1 ${srunArgs.join(" ")} ${scriptRunPathNode}
                    """.replaceAll("(?m)^\\s*", "")
}

def generateSbatchSubmitScript(jobWorkspace, findKeepWhenRetryArgs, slurmJobLogPath, sbatchFailureMarker, budgetedSbatchCommand)
{
    return """#!/bin/bash
                    set -xEeuo pipefail
                    trap 'rc=\$?; echo "Error in file \${BASH_SOURCE[0]} on line \$LINENO: \$BASH_COMMAND (exit \$rc)"; exit \$rc' ERR

                    # Reuse an already-active job after an ambiguous frontend disconnect.
                    if [ -f "${jobWorkspace}/slurm_job_id.txt" ]; then
                        previous_job_id=\$(cat "${jobWorkspace}/slurm_job_id.txt")
                        echo "Found previous Slurm job ID: \${previous_job_id}"
                        previous_state=\$(sacct -j "\${previous_job_id}" --format=State -Pn --allocations 2>/dev/null | head -1 | cut -d'|' -f1 | awk '{print \$1}' || true)
                        if [ -z "\${previous_state}" ]; then
                            previous_state=\$(scontrol show job "\${previous_job_id}" 2>/dev/null | tr ' ' '\\n' | sed -n 's/^JobState=//p' | head -1 || true)
                        fi
                        case "\${previous_state}" in
                            RUNNING|PENDING|CONFIGURING|COMPLETING|REQUEUED|RESIZING|SUSPENDED|SIGNALING|STOPPED)
                                echo "Reusing active Slurm job \${previous_job_id} in state \${previous_state}"
                                exit 0
                                ;;
                            *)
                                echo "Previous Slurm job \${previous_job_id} is not active (state='\${previous_state:-UNKNOWN}'). Cleaning it up before resubmission."
                                scancel "\${previous_job_id}" || true
                                # Wait for 120 seconds to ensure the previous job is canceled
                                sleep 120
                                ;;
                        esac
                    fi

                    # Clean up workspace: remove all files/dirs not in the keep list
                    find "${jobWorkspace}" -maxdepth 1 -mindepth 1 ${findKeepWhenRetryArgs} -exec rm -rf {} +

                    echo "=== [Run Pytest] Submitting GPU SLURM job: \$(date '+%Y-%m-%d %H:%M:%S') on \$(hostname) ==="
                    # Prepare Container stage has already completed; fat sqsh is ready on disk
                    # (or builder failed, in which case the GPU job falls back to base sqsh).
                    touch ${slurmJobLogPath}
                    # Control-plane failover backoff: during a slurmctld failover the
                    # backup controller rejects submissions with "Slurm backup
                    # controller in standby mode" until it promotes, which takes up to
                    # SlurmctldTimeout (upstream default 120s) plus takeover time. The
                    # rejection is instant and burns no partition walltime, so retrying
                    # it here -- every 30s, up to ~3min, inside this one stage attempt
                    # -- rides out a failover without touching the expensive
                    # stage-level SLURM retry budget. This window is sized to a
                    # failover only, NOT to a cluster-wide controller outage or
                    # maintenance: a standby condition that outlasts it falls through
                    # to the failure classifier, whose PATTERN_CATALOG marks
                    # "standby mode" PERSISTENT so the stage does not keep re-running
                    # against a down controller. Any other sbatch failure still fails
                    # immediately.
                    report_sbatch_failure() {
                        local reason="\$1"
                        local summary
                        summary=\$(printf '%s' "\${sbatch_output}" | tr '\\r\\n' '  ')
                        summary="\${summary:0:3000}"
                        set +x
                        printf '${sbatchFailureMarker} %s: %s\\n' "\${reason}" "\${summary}"
                        set -x
                    }

                    sbatch_max_attempts=6
                    sbatch_attempt=1
                    while true; do
                        # Silence xtrace around the capture and prints: it would re-echo the
                        # multi-line sbatch output in every traced command. Restore it only
                        # if it was on, so a caller that turned it off stays off.
                        case \$- in
                            *x*) xtrace_was_on=1 ;;
                            *)   xtrace_was_on=0 ;;
                        esac
                        { set +x; } 2>/dev/null
                        sbatch_rc=0
                        sbatch_output=\$(${budgetedSbatchCommand} 2>&1) || sbatch_rc=\$?
                        printf '%s\\n' "\${sbatch_output}" > "${jobWorkspace}/sbatch_output.txt"
                        if [ "\${xtrace_was_on}" -eq 1 ]; then
                            set -x
                        fi
                        if [ "\${sbatch_rc}" -eq 0 ]; then
                            break
                        fi
                        if [ "\${sbatch_attempt}" -lt "\${sbatch_max_attempts}" ] && printf '%s' "\${sbatch_output}" | grep -qi 'Slurm backup controller in standby mode'; then
                            echo "sbatch rejected by a standby controller (attempt \${sbatch_attempt} of \${sbatch_max_attempts}); retrying in 30s."
                            sbatch_attempt=\$((sbatch_attempt + 1))
                            sleep 30
                            continue
                        fi
                        report_sbatch_failure "exit code \${sbatch_rc}"
                        echo "Error: Slurm job submission failed with exit code \${sbatch_rc}."
                        exit "\${sbatch_rc}"
                    done
                    jobId=\$(printf '%s\\n' "\${sbatch_output}" | awk '/Submitted batch job/ {print \$4; exit}')
                    if [ -z "\$jobId" ]; then
                        report_sbatch_failure "no job ID returned"
                        echo "Error: Slurm job submission failed, no job ID returned."
                        exit 1
                    fi
                    echo "=== [Run Pytest] GPU SLURM job submitted: gpu_job_id=\$jobId time=\$(date '+%Y-%m-%d %H:%M:%S') ==="
                    # Save Slurm job ID for later steps to retrieve
                    echo "\$jobId" > "${jobWorkspace}/slurm_job_id.txt"
                """.replaceAll("(?m)^\\s*", "").trim()
}

def generateSbatchEnrootPrologue(fatBuildScriptHash, fatHash, fatSqshPath, enrootConfigDirNode, containerDir, container)
{
    return """
                    export ENROOT_CACHE_PATH='/home/svc_tensorrt/.cache/enroot'

                    # Fat sqsh: pre-built on CPU node; if present, skip all pip installs.
                    # Path pre-computed on Jenkins controller (hash=${fatBuildScriptHash}, fatHash=${fatHash}).
                    if [ -f "${fatSqshPath}" ]; then
                        echo "Reusing fat sqsh: ${fatSqshPath} (SKIP_INSTALL=1)"
                        export enrootImagePath="${fatSqshPath}"
                        export SKIP_INSTALL=1
                        touch "${fatSqshPath}" || true
                        # The fat sqsh is a local file, so enroot needs no registry credentials.
                        rm -rf "${enrootConfigDirNode}"
                    else
                        # Fat sqsh not ready; use digest-cached base sqsh + full install.
                        # Job-private enroot config (under jobWorkspace). Avoids racing on
                        # the shared ~/.config/enroot/.credentials across concurrent jobs.
                        export ENROOT_CONFIG_PATH="${enrootConfigDirNode}"
                        cleanup_enroot_config() { rm -rf "\${ENROOT_CONFIG_PATH}"; }
                        trap cleanup_enroot_config EXIT
                        containerDir="$containerDir"
                        mkdir -p "\$containerDir"
                        # If the image URI already contains a manifest digest (@sha256:...) use
                        # it directly for content-addressed caching; otherwise hash the tag
                        # string, which is stable for per-build images but will not detect a
                        # re-pushed mutable tag while the cached .sqsh is kept.
                        if printf '%s' "$container" | grep -q '@sha256:'
                        then
                            imageDigest=\$(printf '%s' "$container" | grep -oP '(?<=@sha256:)[a-f0-9]+')
                        else
                            imageDigest=\$(printf '%s' "$container" | sha256sum | cut -d' ' -f1)
                        fi
                        export enrootImagePath="\$containerDir/container-\${imageDigest}.sqsh"

                        importContainerWithRetries() {
                            local docker_uri=\$1
                            local output_path=\$2
                            local max_attempts=\${3:-3}
                            local delay=\${4:-60}
                            local attempt=1
                            local tmp_path

                            # Best-effort lock so racing jobs don't all import the same image.
                            exec 9>"\${output_path}.lock" || true
                            flock 9 || true

                            if [ -f "\$output_path" ]
                            then
                                echo "Reusing cached container image: \$output_path"
                                touch "\$output_path" || true
                                flock -u 9 || true
                                return 0
                            fi

                            tmp_path="\${output_path}.\${SLURM_JOB_ID}.tmp"
                            rm -f "\$tmp_path"

                            until enroot import -o "\$tmp_path" -- "docker://\$docker_uri"
                            do
                                if ((attempt >= max_attempts))
                                then
                                    echo "enroot import failed after \$max_attempts attempts"
                                    rm -f "\$tmp_path"
                                    flock -u 9 || true
                                    return 1
                                fi

                                echo "enroot import failed (attempt \$attempt of \$max_attempts). Retrying in \${delay}s..."
                                rm -f "\$tmp_path"
                                sleep \$delay
                                attempt=\$((attempt + 1))
                            done

                            mv -f "\$tmp_path" "\$output_path"
                            flock -u 9 || true
                        }

                        importContainerWithRetries "$container" "\$enrootImagePath"
                        cleanup_enroot_config
                        trap - EXIT
                    fi
                    """.replaceAll("(?m)^\\s*", "")
}

def generateSbatchTrackScript(slurmJobId, slurmJobLogPath, jobWorkspace, UPLOAD_PATH, stageName, postTag)
{
    return """#!/bin/bash
                    set -xEeuo pipefail
                    trap 'rc=\$?; echo "Error in file \${BASH_SOURCE[0]} on line \$LINENO: \$BASH_COMMAND (exit \$rc)"; exit \$rc' ERR

                    jobId=${slurmJobId}
                    echo "=== [Run Pytest] Tracking GPU SLURM job_id=\$jobId START: \$(date '+%Y-%m-%d %H:%M:%S') ==="
                    tail -n +1 -F -- "${slurmJobLogPath}" &
                    tailPid=\$!

                    noLogTimeoutSecs=7200
                    lastLogSize=-1
                    lastLogChangeEpoch=\$(date +%s)

                    # Wait until Slurm job is done
                    while true; do
                        # Use --allocations to ensure we match the exact job ID and not job steps (like 123.batch, 123.0)
                        # Tolerate transient sacct failures (e.g. slurmdbd unreachable) so the tracker survives controller blips.
                        if ! STATUS=\$(sacct -j \$jobId --format=State -Pn --allocations 2>&1); then
                            echo "Warning: sacct failed for job \$jobId: \$STATUS"
                            sleep 60
                            continue
                        fi

                        if [[ -z \$STATUS || \$STATUS == "RUNNING" || \$STATUS == "PENDING" || \$STATUS == "CONFIGURING" ]]; then
                            echo "Slurm job \$jobId state: \${STATUS:-UNKNOWN}"
                            if [[ \$STATUS == "RUNNING" ]]; then
                                currentLogSize=0
                                if [ -f "${slurmJobLogPath}" ]; then
                                    currentLogSize=\$(stat -c %s "${slurmJobLogPath}" 2>/dev/null || echo 0)
                                fi
                                nowEpoch=\$(date +%s)
                                if [ "\$currentLogSize" != "\$lastLogSize" ]; then
                                    lastLogSize=\$currentLogSize
                                    lastLogChangeEpoch=\$nowEpoch
                                else
                                    staleSecs=\$((nowEpoch - lastLogChangeEpoch))
                                    if [ "\$staleSecs" -ge "\$noLogTimeoutSecs" ]; then
                                        echo "Warning: no new log output for \${staleSecs}s (>= \${noLogTimeoutSecs}s), job \$jobId is likely stuck/timed out. Cancelling it."
                                        scancel \$jobId || true
                                    fi
                                fi
                            fi
                            sleep 300
                        else
                            echo "Slurm job \$jobId finished with state: \$STATUS"
                            break
                        fi
                    done

                    # A failed job can reach a terminal accounting state before
                    # its final output is visible on the login node's shared FS.
                    if [[ \$STATUS != "COMPLETED" ]]; then
                        sleep 15
                    fi

                    # Stop and reap the log follower. It may have already exited
                    # when the remote log stream closes; that is not a test failure.
                    kill \$tailPid 2>/dev/null || true
                    wait \$tailPid 2>/dev/null || true

                    # Wait briefly to ensure accounting is consistent
                    sleep 10

                    # Get exit code (STATUS is already known from loop break)
                    # Retry for exit code if missing
                    for i in {1..3}; do
                        # Use awk to parse exit code from format like "0:0"
                        EXIT_CODE=\$(sacct -j \$jobId --format=ExitCode -Pn --allocations | awk -F: '{print \$1}')

                        if [ -n "\$EXIT_CODE" ]; then
                            break
                        fi
                        echo "Waiting for sacct exit code to update... attempt \$i"
                        sleep 10
                    done

                    if [ -z "\$EXIT_CODE" ]; then
                        echo "Error: Failed to get exit code from sacct after retries, defaulting to 1."
                        EXIT_CODE=1
                    fi

                    # We already have valid STATUS from the loop that caused the break
                    NODE_LIST=""
                    if [ -s "${jobWorkspace}/slurm_node_list.txt" ]; then
                        NODE_LIST=\$(awk 'NF { print; exit }' "${jobWorkspace}/slurm_node_list.txt" || true)
                    fi
                    if [ -z "\$NODE_LIST" ]; then
                        NODE_LIST=\$(sacct -j \$jobId --format=NodeList -Pn --allocations 2>/dev/null | head -1 || true)
                    fi
                    echo "Slurm job \$jobId nodelist: \${NODE_LIST:-UNKNOWN}"
                    printf '%s\n' "\$NODE_LIST" > "${jobWorkspace}/slurm_node_list.txt"

                    echo "=== [Run Pytest] Tracking GPU SLURM job_id=\$jobId END: \$(date '+%Y-%m-%d %H:%M:%S') node=\${NODE_LIST:-UNKNOWN} status=\$STATUS exit_code=\$EXIT_CODE ==="
                    # Record the verdict and always exit 0: a re-run can't change a
                    # terminal state, so numRetries should only fire on transport loss.
                    printf '%s|%s\n' "\$STATUS" "\$EXIT_CODE" > "${jobWorkspace}/slurm_job_result.txt"
                    if [[ "\$STATUS" == "COMPLETED" && \$EXIT_CODE -eq 0 ]]; then
                        echo "Pytest succeed in Slurm job \$jobId"
                    else
                        echo "Pytest failed in Slurm job \$jobId"
                        echo "Full test output (logs not shown above) is uploaded after stage teardown to:"
                        echo "  https://urm.nvidia.com/artifactory/${UPLOAD_PATH}/test-results/results-${stageName}${postTag}.tar.gz"
                    fi
                    echo "Status: \$STATUS | Exit_code \$EXIT_CODE"
                    exit 0
                """.replaceAll("(?m)^\\s*", "").trim()
}

return this
