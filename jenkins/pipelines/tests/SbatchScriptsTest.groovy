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

import java.nio.ByteBuffer
import java.security.MessageDigest

// From the repository root:
// java -cp /path/to/groovy-all-2.4.21.jar groovy.ui.GroovyMain \
//     jenkins/pipelines/tests/SbatchScriptsTest.groovy .

def repoRoot = new File(args ? args[0] : '.')
def helper = new GroovyShell().parse(new File(repoRoot, 'jenkins/pipelines/test/SbatchScripts.groovy'))
helper.run()

// These fixed render fingerprints pin shell text, escaping, and whitespace.
// Each digest covers 36 UTF-8 scripts, each prefixed by its four-byte length,
// so a lost newline or a changed script boundary cannot pass unnoticed.
def expected = [
    prefix: 'c2fdd3f3aea42c62caff82892b5fbd05f02dcf66c165102c2ca59c0cf84802c2',
    launch: 'c3e216cf4452b90bf981f7fca2d1a94d954451df931b4f7119fc674b48c6f95a',
    submit: '00875344c7472662c633b2c7fcde260035bca53977d7b359ec443bbf3ef019f9',
    track: '75a41984ddbfc56bada500b8ab163a83bb43f9e6b7c4d359d8abf03c116d6dd5',
    enroot: '6d1d6f3b095bdbbdcc00e6494c1505b9e8047310ae68c195387702cc4c5b9fa8',
]
def digests = expected.collectEntries { name, _ -> [(name): MessageDigest.getInstance('SHA-256')] }
def record = { String name, String script ->
    byte[] bytes = script.getBytes('UTF-8')
    digests[name].update(ByteBuffer.allocate(4).putInt(bytes.length).array())
    digests[name].update(bytes)
}
def names = [
    'exemptionComment', 'slurmJobLogPath', 'slurmExcludeDirective', 'jobWorkspace',
    'tarName', 'llmTarArtifactPath', 'artifactoryNetrcPathNode', 'LLM_ARTIFACTS_CREDENTIALS_ID',
    'llmTarfile', 'llmSrcNode', 'stageName', 'infraDryRun', 'resourcePathNode', 'pytestCommand',
    'coverageConfigFile', 'HF_TOKEN', 'GITHUB_CLONE_TOKEN', 's3SecretKeyPathNode',
    'envExportStatements', 'srunPrologue',
]

(0..<36).each { int index ->
    def values = names.collectEntries { [(it): "${it}-${index}"] }
    values.putAll([
        partition: [additionalArgs: index % 2 ? '-A account -q urgent' : '',
                    time: [null, 0, 30, 125][index % 4],
                    name: [null, '', 'unspecified', 'gpu-general'][index % 4],
                    clusterName: "cluster-${index}"],
        taskArgs: index % 2 ? ['--nodes=2', '--gpus-per-node=8'] as String[] : ['--nodes=1', '--gpus-per-node=1'],
        infraDryRun: index % 2 == 0,
        defaultTimeoutShort: 120,
        artifactsPrimary: index % 2 ? 'aws' : 'urm',
        exemptionComment: index % 3 ? '--comment=standard' : '',
        slurmExcludeDirective: index % 3 ? '#SBATCH --exclude=node-001' : '',
        pytestCommand: "python3 -m pytest 'some test' --opt=\$VALUE",
        HF_TOKEN: "synthetic-hf-${index}",
        GITHUB_CLONE_TOKEN: "synthetic-git-${index}",
        envExportStatements: "export SAMPLE='quoted space'\nexport UNICODE='测试'",
        srunPrologue: "# prologue\necho synthetic-${index}",
    ])
    def prefix = helper.generateSbatchLaunchPrefix(values.partition, values.taskArgs, values)
    record('prefix', prefix)
    assert prefix.indexOf('#SBATCH') < prefix.indexOf('set -xEeuo pipefail')
    assert !prefix.substring(prefix.indexOf('set -xEeuo pipefail')).contains('#SBATCH')
    assert prefix.contains("export LLM_ARTIFACTS_PRIMARY=${values.artifactsPrimary}")
    assert prefix.contains('export infraDryRun=true') == values.infraDryRun
    assert prefix.contains('#SBATCH --partition=') == (values.partition.name == 'gpu-general')

    def launchValues = [scriptLaunchPrefix: prefix,
                        srunArgs: index % 2 ? ['--mpi=pmix', '--container-image=image.sqsh'] : [],
                        scriptRunPathNode: "/tmp/job-${index}/run.sh"]
    record('launch', helper.generateSbatchLaunchScript(
        launchValues.scriptLaunchPrefix, launchValues.srunArgs, launchValues.scriptRunPathNode))

    def submitValues = [jobWorkspace: "/tmp/job-${index}", findKeepWhenRetryArgs: "! -name run.sh ! -name 'space name'",
                        slurmJobLogPath: "/tmp/job-${index}/job.log", sbatchFailureMarker: '[SYNTHETIC_SBATCH_FAILURE]',
                        clusterName: values.partition.clusterName,
                        scriptLaunchPathNode: "/tmp/job-${index}/launch.sh", gpuCount: index % 2 ? 8 : 1]
    def budgetedCommand = "budget ${submitValues.clusterName} ${submitValues.gpuCount} -- sbatch ${submitValues.scriptLaunchPathNode}"
    record('submit', helper.generateSbatchSubmitScript(
        submitValues.jobWorkspace, submitValues.findKeepWhenRetryArgs, submitValues.slurmJobLogPath,
        submitValues.sbatchFailureMarker, budgetedCommand))

    def trackValues = [slurmJobId: index % 2 ? "${1000 + index}" : 3000 + index,
                       slurmJobLogPath: "/tmp/job-${index}/job.log", jobWorkspace: "/tmp/job-${index}",
                       UPLOAD_PATH: "artifacts/job-${index}", stageName: "Stage-${index}",
                       postTag: index % 2 ? '-infra-attempt-2' : '']
    record('track', helper.generateSbatchTrackScript(
        trackValues.slurmJobId, trackValues.slurmJobLogPath, trackValues.jobWorkspace,
        trackValues.UPLOAD_PATH, trackValues.stageName, trackValues.postTag))

    def enrootValues = [fatBuildScriptHash: "scriptHash-${index}", fatHash: "fatHash-${index}",
                        fatSqshPath: "/tmp/sqsh-${index}/fat.sqsh", enrootConfigDirNode: "/tmp/job-${index}/enroot",
                        containerDir: "/tmp/containers-${index}",
                        container: index % 2 ? 'registry#image@sha256:012345abcdef' : 'registry#image:tag']
    record('enroot', helper.generateSbatchEnrootPrologue(
        enrootValues.fatBuildScriptHash, enrootValues.fatHash, enrootValues.fatSqshPath,
        enrootValues.enrootConfigDirNode, enrootValues.containerDir, enrootValues.container))
}
expected.each { name, expectedHash ->
    def actualHash = digests[name].digest().encodeHex().toString()
    assert actualHash == expectedHash: "${name} shell rendering changed: ${actualHash}"
}
println 'PASS: 180 shell renderings match fixed golden hashes across 36 configurations.'
