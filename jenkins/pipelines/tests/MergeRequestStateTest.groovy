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

import groovy.json.JsonOutput

// From the repository root: groovy jenkins/pipelines/tests/MergeRequestStateTest.groovy .
// Re-enter real module methods while their outer calls are suspended at mocked steps.
def root = new File(args ? args[0] : '.')
def loader = new GroovyClassLoader(this.class.classLoader)
loader.addClasspath(new File(root, 'jenkins/pipeline-library/src').absolutePath)
loader.parseClass('''
package com.nvidia.bloom
class Logger { Logger(Object pipeline) {} }
class JobBuilder {
    static List calls = []
    static List build(Object... args) {
        calls << args[2].toString()
        return ['synthetic-build-url', 'SUCCESS']
    }
}
''', 'BuildMocks.groovy')
loader.parseClass('package org.jenkinsci.plugins.workflow.steps; class FlowInterruptedException extends InterruptedException {}',
    'FlowInterruptedException.groovy')
loader.parseClass('package org.jenkinsci.plugins.pipeline.modeldefinition; class Utils { static void markStageSkippedForConditional(String name) {} }',
    'PipelineUtils.groovy')
def shell = new GroovyShell(loader)
def loadModule = { String name, pipeline ->
    def module = shell.parse(new File(root, "jenkins/pipelines/mr/${name}.groovy"))
    module.run()
    module.initialize(pipeline)
    return module
}
def parameters = loader.loadClass('trtllm.ci.parameters.RunParameters').newInstance()

// The nested check's work closure must not replace the outer check's closure.
def checkedFiles = []
def releaseSentinels = [stages: 'existing-stages', ignoreList: 'existing-ignore-list',
                        stageName: 'existing-stage-name', jobDir: 'existing-job-dir']
def releasePipeline = new Expando(
    env: [JOB_NAME: 'LLM/Test', gitlabCommit: 'synthetic-commit'],
    runParameters: parameters, runMode: 'full', LLM_REPO: 'synthetic-repository',
    LLM_ROOT: 'llm', SCAN_REPO: 'synthetic-scanner', SCAN_COMMIT: 'main', SCAN_ROOT: 'scan',
    UPLOAD_PATH: 'synthetic-artifacts', RELEASE_CHECK_CHOICE: 'normal',
    STAGE_CHOICE_SKIP: 'skip', STAGE_CHOICE_IGNORE: 'ignore', DEFAULT_GIT_URL: 'synthetic-git',
)
releaseSentinels.each { key, value -> releasePipeline[key] = value }
releasePipeline.stage = { Object name, Closure body -> body() }
releasePipeline.sh = { Object command -> '' }
releasePipeline.echo = { Object message -> }
releasePipeline.withEnv = { List environment, Closure body -> body() }
releasePipeline.withCredentials = { List credentials, Closure body -> body() }
releasePipeline.gitUsernamePassword = { Map options -> options }
releasePipeline.string = { Map options -> options }
releasePipeline.mrEnvironment = new Expando(echoNodeAndGpuInfo: { Object... ignored -> })
releasePipeline.mrChanges = new Expando(getMergeRequestChangedFileList: { pipeline, Map globals ->
    ["${globals.invocation}.py"]
})
releasePipeline.writeFile = { Map options -> checkedFiles << options.text.toString() }
releasePipeline.trtllm_utils = new Expando(
    llmExecStepWithRetry: { Object... ignored -> },
    checkoutSource: { Object... ignored -> },
    uploadArtifacts: { Object... ignored -> },
    launchKubernetesPod: { pipeline, Map pod, String container, Closure body -> body() },
)
def release = loadModule('Release', releasePipeline)
boolean enteredRelease = false
releasePipeline.createKubernetesPodConfig = { String image, String type ->
    if (!enteredRelease) {
        enteredRelease = true
        release.launchReleaseCheck(releasePipeline, [invocation: 'inner'])
    }
    [:]
}
release.launchReleaseCheck(releasePipeline, [invocation: 'outer'])
assert checkedFiles == ['inner.py', 'outer.py']
releaseSentinels.each { key, value -> assert releasePipeline[key] == value }
// Exercise the scan branch as well: ignore patterns belong only to that scan.
releasePipeline.env.JOB_NAME = 'LLM/PostMerge'
release.launchReleaseCheck(releasePipeline, [:])
releaseSentinels.each { key, value -> assert releasePipeline[key] == value }

// Each planner retains its own branch map even if a log step starts another plan.
def planned = []
def stageSentinels = [stages: 'existing-stages', parallelJobs: 'existing-parallel-jobs']
def stagePipeline = new Expando(env: [JOB_NAME: 'LLM/Test'], runMode: 'full',
    GEN_POST_MERGE_BUILDS_ONLY: false, runParameters: parameters)
stageSentinels.each { key, value -> stagePipeline[key] = value }
def stages = loadModule('Stages', stagePipeline)
boolean enteredPlan = false
stagePipeline.echo = { Object message ->
    if (!enteredPlan && message.toString() == 'Will run Build-Docker-Images job') {
        enteredPlan = true
        stages.launchStages(stagePipeline, null, [:], false, [:])
    }
}
stagePipeline.parallel = { Map jobs -> planned << jobs.keySet().toSet() }
stages.launchStages(stagePipeline, null, [(parameters.TEST_STAGE_LIST): ['Build-Docker-Images']], false, [:])
assert planned.size() == 2
assert planned[0].containsAll(['x86_64-Linux', 'SBSA-Linux'])
assert !planned[1].contains('x86_64-Linux')
assert !planned[1].contains('SBSA-Linux')
assert planned[1].contains('Build-Docker-Images')
stageSentinels.each { key, value -> assert stagePipeline[key] == value }

// A nested target lookup must not change the outer call's target branch.
def environmentPipeline = new Expando(
    env: [gitlabTargetBranch: 'outer', gitlabCommit: 'synthetic-commit'],
    LLM_ROOT: 'llm', runParameters: parameters, targetBranch: 'existing-target',
    LLM_TOT_ROOT: 'existing-tot-root',
)
environmentPipeline.sh = { Object command -> '' }
environmentPipeline.echo = { Object message -> }
environmentPipeline.usernamePassword = { Map options -> options }
environmentPipeline.catchError = { Map options, Closure body ->
    try { body() } catch (RuntimeException expected) {}
}
environmentPipeline.error = { Object message -> throw new RuntimeException(message.toString()) }
def environment = loadModule('Environment', environmentPipeline)
def targets = []
environment.metaClass.checkoutTargetBranchFile = { String branch, String commit, String path, String prefix ->
    targets << branch
    ''
}
boolean enteredTarget = false
Map innerTarget
environmentPipeline.withCredentials = { List credentials, Closure body ->
    if (!enteredTarget) {
        enteredTarget = true
        environmentPipeline.env.gitlabTargetBranch = 'inner'
        innerTarget = environment.mergeWaiveList(environmentPipeline, [:])
    }
    throw new RuntimeException('synthetic API is unavailable')
}
def outerTarget = environment.mergeWaiveList(environmentPipeline, [:])
assert targets == ['inner', 'outer']
assert innerTarget == [targetBranch: 'inner', targetCommit: '']
assert outerTarget == [targetBranch: 'outer', targetCommit: '']
assert environmentPipeline.targetBranch == 'existing-target'
assert environmentPipeline.LLM_TOT_ROOT == 'existing-tot-root'

// Downstream job path construction must not leak its scratch prefix to the entry.
releasePipeline.env.JOB_NAME = 'LLM/main/Parent'
releasePipeline.artifactPaths = new Expando(llmArtifactsPrimary: { 'aws' })
releasePipeline.writeJSON = { Map options -> JsonOutput.toJson(options.json) }
def jobBuilder = loader.loadClass('com.nvidia.bloom.JobBuilder')
assert release.launchJob(releasePipeline, 'Child', null, false, [:]) == 'SUCCESS'
assert release.launchJob(releasePipeline, '/absolute/Child', null, false, [:]) == 'SUCCESS'
releasePipeline.env.JOB_NAME = 'Parent'
assert release.launchJob(releasePipeline, 'Child', null, false, [:]) == 'SUCCESS'
assert jobBuilder.calls == ['LLM/main/Child', 'absolute/Child', 'Child']
assert releasePipeline.jobDir == 'existing-job-dir'
println 'PASS: nested release checks, stage plans and target lookups retain invocation-local state; job prefixes do not leak.'
