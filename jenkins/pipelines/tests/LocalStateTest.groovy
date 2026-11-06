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

// From the repository root: groovy jenkins/pipelines/tests/LocalStateTest.groovy .
// External steps are mocked; these checks invoke the real module methods.

class NestedProperties extends LinkedHashMap {
    Closure beforeLookup
    boolean entered = false

    @Override
    Object get(Object key) {
        if (!this.@entered) {
            this.@entered = true
            this.@beforeLookup.call()
        }
        return super.get(key)
    }
}

class BuildCommandRecorded extends RuntimeException {}

def root = new File(args ? args[0] : '.')
def loader = new GroovyClassLoader(this.class.classLoader)
loader.parseClass('@interface NonCPS {}', 'NonCPS.groovy')
loader.parseClass('package com.nvidia.bloom; class Utils {}', 'Utils.groovy')
loader.parseClass('''
package com.nvidia.bloom
class SlurmPartition { String clusterName; Integer time = 30 }
class SlurmCluster { String host; String containerRuntime }
class SlurmConfig {
    static final int DEFAULT_TIMEOUT_SHORT = 120
    static def clusterConfig = [
        docker: new SlurmCluster(host: 'synthetic-docker', containerRuntime: 'DOCKER'),
        enroot: new SlurmCluster(host: 'synthetic-enroot', containerRuntime: 'ENROOT'),
    ]
    static def containerRuntimeToEntrypoint = [DOCKER: 'docker.sh', ENROOT: 'enroot.sh']
    static SlurmPartition resolvePlatform(String platform) {
        new SlurmPartition(clusterName: platform)
    }
}
class CloudManager {
    static def createNode(Object... ignored) { 'synthetic-secret' }
    static def withSlurmFrontendFailover(Object... ignored) {}
    static def withSlurmSshCredentialRemotes(Object... ignored) {}
}
''', 'SlurmMocks.groovy')
loader.parseClass('''
package trtllm.exceptions
class TrtllmCiException extends RuntimeException {}
class InfraFailure extends TrtllmCiException {
    static final String K8S = 'K8S'
    static final String SLURM = 'SLURM'
    static final String TRANSIENT = 'TRANSIENT'
    InfraFailure(Object... ignored) {}
}
class UserFailure extends TrtllmCiException { UserFailure(Object... ignored) {} }
''', 'FailureMocks.groovy')
loader.addClasspath(new File(root, 'jenkins/pipeline-library/src').absolutePath)
def parameters = loader.loadClass('trtllm.ci.parameters.RunParameters').newInstance()
def shell = new GroovyShell(loader)
def loadModule = { String path, pipeline ->
    pipeline.runParameters = parameters
    def module = shell.parse(new File(root, "jenkins/pipelines/${path}.groovy"))
    module.run()
    module.initialize(pipeline)
    return module
}

// A nested image lookup must not replace the outer invocation's result map.
def imageKeys = [
    'LLM_DOCKER_IMAGE', 'LLM_SBSA_DOCKER_IMAGE', 'LLM_SBSA_WHEEL_DOCKER_IMAGE',
    'LLM_ROCKYLINUX8_PY310_DOCKER_IMAGE', 'LLM_ROCKYLINUX8_PY312_DOCKER_IMAGE',
]
def outerImages = imageKeys.collectEntries { [(it): "outer-${it}"] }
def innerImages = imageKeys.collectEntries { [(it): "inner-${it}"] }
def imagePipeline = new Expando(LLM_ROOT: 'llm')
def imageSentinels = [tagProps: 'existing-properties', uris: 'existing-uris', keys: 'existing-keys']
imageSentinels.each { key, value -> imagePipeline[key] = value }
def environment = loadModule('mr/Environment', imagePipeline)
Map nestedResult
def properties = new NestedProperties()
properties.putAll(outerImages)
properties.beforeLookup = { nestedResult = environment.getContainerURIs() }
int propertyReads = 0
imagePipeline.readProperties = { Map options ->
    assert options == [file: 'llm/jenkins/current_image_tags.properties', interpolate: true]
    return propertyReads++ == 0 ? properties : innerImages
}
assert environment.getContainerURIs() == outerImages
assert nestedResult == innerImages
imageSentinels.each { key, value -> assert imagePipeline[key] == value }
assert !environment.binding.hasVariable('key')

// Enter the ARM build while the x86 build is waiting at withEnv. Each command
// must retain its own architecture flags, and existing Pipeline fields survive.
def commands = []
def buildPipeline = new Expando(
    env: [gitlabCommit: 'synthetic-commit'], LLM_REPO: 'synthetic-repository',
    CCACHE_DIR: '/synthetic-cache', AARCH64_TRIPLE: 'aarch64-linux-gnu',
    buildArgs: 'existing-build-args',
)
buildPipeline.sh = { Object options ->
    if (options instanceof Map && options.script.contains('ls -1 *.whl')) {
        throw new BuildCommandRecorded()
    }
    return ''
}
buildPipeline.sleep = { ignored -> }
buildPipeline.trtllm_utils = new Expando(
    checkoutSource: { Object... ignored -> },
    llmExecStepWithRetry: { Map options, pipeline ->
        if (options.script.contains('scripts/build_wheel.py')) commands << options.script.toString()
    },
)
def build = loadModule('test/Build', buildPipeline)
def defaultBuildOptions = [jobs: '4', kitmakerCredentialsId: 'svc_tensorrt_kitmaker_api_token',
    kitmakerDryRunPicEmail: 'kefu@nvidia.com', kitmakerPublishTo: 'both_devzone_pypi']
assert build.buildOptions == defaultBuildOptions
def emptyBuild = loadModule('test/Build', new Expando(env: [
    kitmakerCredentialsId: '', kitmakerDryRunPicEmail: '', kitmakerPublishTo: '',
]))
assert emptyBuild.buildOptions == defaultBuildOptions
assert !emptyBuild.buildOptions.is(build.buildOptions)
def kitmakerPipeline = new Expando(env: [kitmakerCredentialsId: 'synthetic-credential',
    kitmakerDryRunPicEmail: 'ci@example.invalid', kitmakerPublishTo: 'false'])
def kitmakerBuild = loadModule('test/Build', kitmakerPipeline)
assert kitmakerBuild.buildOptions == [jobs: '4', kitmakerCredentialsId: 'synthetic-credential',
    kitmakerDryRunPicEmail: 'ci@example.invalid', kitmakerPublishTo: 'false']
def previousOptions = kitmakerBuild.buildOptions
previousOptions.jobs = '1'
assert build.buildOptions == defaultBuildOptions
kitmakerBuild.initialize(kitmakerPipeline)
assert !kitmakerBuild.buildOptions.is(previousOptions)
assert kitmakerBuild.buildOptions.jobs == '4'
Closure invokeBuild
invokeBuild = { String architecture ->
    try {
        build.runLLMBuild(buildPipeline, architecture)
        assert false: 'the build should stop after its command is recorded'
    } catch (BuildCommandRecorded expected) {}
}
boolean nestedBuild = false
buildPipeline.withEnv = { List variables, Closure body ->
    assert variables.collect { it.toString() }.contains('TRTLLM_BUILD_SOURCE_COMMIT=synthetic-commit')
    if (!nestedBuild) {
        nestedBuild = true
        invokeBuild('aarch64-linux-gnu')
    }
    body()
}
invokeBuild('x86_64-linux-gnu')
assert commands.size() == 2
assert commands.every { it.contains('-j 4 ') }
assert commands[0].contains("--clean --nixl_root /opt/nvidia/nvda_nixl -a '90;100;103;107;120;121'")
assert commands[1].contains("--clean --nixl_root /opt/nvidia/nvda_nixl -a '80;86;89;90;100;103;107;120'")
assert buildPipeline.buildArgs == 'existing-build-args'
assert build.buildOptions == defaultBuildOptions

// Kitmaker uses the same per-run overrides for publish and its later check.
def kitmakerCommands = []
def kitmakerCredentials = []
kitmakerPipeline.UPLOAD_PATH = 'synthetic-artifacts'
kitmakerPipeline.TRTLLM_RELEASE_REPO = [url: 'synthetic-repository', commit: 'synthetic-commit']
kitmakerPipeline.echo = { ignored -> }
kitmakerPipeline.stage = { String name, Closure body -> body() }
kitmakerPipeline.catchError = { Map options, Closure body -> body() }
kitmakerPipeline.retry = { int count, Closure body -> body() }
kitmakerPipeline.string = { Map options -> options }
kitmakerPipeline.withCredentials = { List credentials, Closure body ->
    kitmakerCredentials += credentials.collect { it.credentialsId }
    body()
}
kitmakerPipeline.sh = { Object options ->
    kitmakerCommands << (options instanceof Map ? options.script : options).toString()
    return '{"release_uuid":"synthetic-release"}'
}
kitmakerPipeline.readJSON = { Map options -> [release_uuid: 'synthetic-release'] }
kitmakerPipeline.trtllm_utils = new Expando(checkoutSource: { Object... ignored -> },
    llmExecStepWithRetry: { Object... ignored -> })
def metadata = kitmakerBuild.runKitmakerWheelDryRun(kitmakerPipeline, 'wheel.whl',
    'python3', kitmakerBuild.buildOptions.kitmakerPublishTo)
kitmakerBuild.checkKitmakerWheelDryRun(kitmakerPipeline, metadata)
assert kitmakerCredentials == ['synthetic-credential', 'synthetic-credential']
assert kitmakerCommands.any { it.contains('--pic-email ci@example.invalid') && it.contains('--publish-to false') }
assert kitmakerCommands.any { it.contains('kitmaker_wheel.py check') && it.contains('synthetic-release') }

// Run an inner rerun while the outer invocation reports its first count.
def messages = []
def resultPipeline = new Expando(
    WORKSPACE: '/synthetic-workspace', UPLOAD_PATH: 'synthetic-artifacts',
    PROGRESS_UPLOAD_INTERVAL_SEC: 600, count: 'existing-count',
)
resultPipeline.fileExists = { String path -> !path.endsWith('unfinished_test.txt') }
resultPipeline.sh = { Object options ->
    if (options instanceof Map && options.returnStdout) {
        boolean inner = options.script.contains('/inner/')
        return options.script.contains('rerun_1.txt') ? (inner ? '3' : '2') : (inner ? '4' : '1')
    }
    return ''
}
resultPipeline.trtllm_utils = new Expando(getFailSignaturesList: { [] })
resultPipeline.usernamePassword = { Map options -> options }
resultPipeline.withCredentials = { List credentials, Closure body -> body() }
def results = loadModule('test/Results', resultPipeline)
boolean nestedRerun = false
resultPipeline.echo = { Object message ->
    messages << message.toString()
    if (!nestedRerun && message.toString().startsWith('Found 2 outer')) {
        nestedRerun = true
        assert results.rerunFailedTests(resultPipeline, 'inner', '/source', ['pytest'],
            'results.xml', 'inner') == [failed: false, infraFailure: null]
    }
}
assert results.rerunFailedTests(resultPipeline, 'outer', '/source', ['pytest'],
    'results.xml', 'outer') == [failed: false, infraFailure: null]
assert messages.findAll { it.startsWith('Found ') } == [
    'Found 2 outer tests to rerun 1 time(s)', 'Found 3 inner tests to rerun 1 time(s)',
    'Found 4 inner tests to rerun 2 time(s)', 'Found 1 outer tests to rerun 2 time(s)',
]
assert resultPipeline.count == 'existing-count'

// The full agent method still dispatches Docker and Enroot to the corresponding
// runner. Provisioning and cleanup are mocked, so no external resources exist.
def runnerCalls = []
def agentPipeline = new Expando(
    env: [BUILD_TAG: 'synthetic-build'], LLM_DOCKER_IMAGE: 'synthetic-image',
    VANILLA_CONFIG: 'Vanilla', slurmRunner: 'existing-runner',
)
agentPipeline.echo = { ignored -> }
agentPipeline.stage = { String name, Closure body -> body() }
agentPipeline.retry = { int attempts, Closure body -> body() }
agentPipeline.infraRetry = new Expando(preservePrimaryFailure: { error, Closure body -> body() })
agentPipeline.slurmResources = new Expando(
    captureSlurmJobNodeList: { Object... ignored -> },
    cleanUpNodeResources: { Object... ignored -> },
    deregisterSlurmResource: { Object... ignored -> },
)
agentPipeline.testRuntime = new Expando(
    runInDockerOnNodeMultiStage: { Object... ignored -> { -> 'docker-runner' } },
    runInEnrootOnNode: { Object... ignored -> { -> 'enroot-runner' } },
)
def agent = loadModule('test/SlurmAgent', agentPipeline)
boolean nestedAgent = false
agentPipeline.testExecution = new Expando(getShortenedJenkinsInstanceName: { 'synthetic' })
agentPipeline.jenkinsRunner = new Expando(
    executeLLMTestOnSlurm: { context, platform, testList, config, stageName, splitId,
            splits, skipInstallWheel, runner, postTag, useClusterDurations,
            retryContext, classifySlurmFailure ->
        if (!nestedAgent) {
            nestedAgent = true
            agent.runLLMTestlistWithAgent(agentPipeline, 'enroot', 'l0', 'Vanilla', 'inner')
        }
        runnerCalls << [stage: stageName, runner: runner()]
    },
)
agent.runLLMTestlistWithAgent(agentPipeline, 'docker', 'l0', 'Vanilla', 'outer')
assert runnerCalls == [[stage: 'inner', runner: 'enroot-runner'], [stage: 'outer', runner: 'docker-runner']]
assert agentPipeline.slurmRunner == 'existing-runner'

// Changes to shared pod classes need the same multi-GPU coverage as test
// orchestration changes; the explicit disable flag still takes precedence.
def changedFiles = ['jenkins/pipeline-library/src/trtllm/ci/pods/PodFactory.groovy']
int changedFileReads = 0
def selectionPipeline = new Expando(
    DISABLE_MULTI_GPU_TEST: 'disable_multi_gpu_test', ADD_MULTI_GPU_TEST: 'add_multi_gpu_test',
    ONLY_MULTI_GPU_TEST: 'only_multi_gpu_test', IS_POST_MERGE: 'post_merge',
    echo: { ignored -> },
    mrChanges: new Expando(getMergeRequestChangedFileList: { pipeline, Map globalVars ->
        changedFileReads++
        return changedFiles
    }),
)
def selection = loadModule('mr/Selection', selectionPipeline)
assert selection.getMultiGpuFileChanged(selectionPipeline, [:], [:])
changedFiles = ['docs/source/developer-guide/ci-overview.md']
assert !selection.getMultiGpuFileChanged(selectionPipeline, [:], [:])
changedFiles = ['jenkins/pipeline-library/src/trtllm/ci/pods/PodFactory.groovy']
assert !selection.getMultiGpuFileChanged(selectionPipeline, [disable_multi_gpu_test: true], [:])
assert changedFileReads == 2

println 'Local state checks passed: invocation isolation, per-run Kitmaker options, and pod-change test selection.'
