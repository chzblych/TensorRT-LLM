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

import groovy.json.JsonSlurper

// From the repository root: groovy jenkins/pipelines/tests/StageAdapterTest.groovy .
def root = new File(args ? args[0] : '.')
def loader = new GroovyClassLoader(this.class.classLoader)
loader.addClasspath(new File(root, 'jenkins/pipeline-library/src').absolutePath)
loader.parseClass('package trtllm.exceptions; class InfraFailure extends RuntimeException {}', 'InfraFailure.groovy')
def catalog = loader.loadClass('trtllm.ci.stages.StageCatalog')
def raw = new JsonSlurper().parse(new File(root, 'jenkins/pipelines/test/stages.json'))
raw.groups.each { it.stages = [] }
raw.groups.find { it.name == 'SBSATestConfigs' }.stages = [
    [name:'Fixture-SBSA-{shard}', platform:'gh200', testList:'l0_gh200', splits:2, gpuCount:2],
]
raw.groups.find { it.name == 'x86SlurmTestConfigs' }.stages = [
    [name:'Fixture-X86-Durations', platform:'auto:dgx-h100-x1', testList:'l0_h100', splits:1,
        options:[useClusterDurations:true]],
]
raw.groups.find { it.name == 'multiNodesSBSAConfigs' }.stages = [
    [name:'Fixture-SBSA-DefaultNodes', platform:'auto:gb200-x1', testList:'l0_gb200', splits:1, runner:'slurmAgent'],
]
def expanded = catalog.expandForPipeline(raw)
def module = new GroovyShell(loader).parse(new File(root, 'jenkins/pipelines/test/Stages.groovy'))
module.run()
def pods = []
def calls = []
def p = new GroovyShell(loader).parse('''
def createKubernetesPodConfig(Object... args) {
    def pod = [image:args[0], type:args[1], arch:args[2], gpuCount:args.size()>3 ? args[3] : 1,
        modelExpress:args.size()>4 ? args[4] : false]
    pods << pod
    return pod
}
def cbtsResizeSplits(values) { return values }
def runLLMTestlistOnPlatform(Object... args) { calls << ['jenkins',args.toList()] }
def runLLMTestlistOnSlurm(Object... args) { calls << ['slurm',args.toList()] }
return this
''')
p.binding = new Binding([
    pods:pods, calls:calls, env:[targetArch:'aarch64-linux-gnu'], AARCH64_TRIPLE:'aarch64-linux-gnu',
    LLM_DOCKER_IMAGE:'test-image', X86_64_DOCKER_IMAGE:'dispatcher-image',
    VANILLA_CONFIG:'Vanilla', LINUX_AARCH64_CONFIG:'linux_aarch64', SINGLE_DEVICE_CONFIG:'SingleDevice', LLVM_CONFIG:'LLVM',
])
p.testSelection = p
p.jenkinsRunner = p
p.infraRetry = p
module.initialize(p, expanded)
def sbsa = module.prepareSbsaTestJobs(p, [] as Set)
assert sbsa.jobs.keySet().toList() == ['Fixture-SBSA-1','Fixture-SBSA-2','Fixture-SBSA-DefaultNodes']
['Fixture-SBSA-1','Fixture-SBSA-2'].eachWithIndex { name, index ->
    assert sbsa.jobs[name][0] == [image:'test-image',type:'gh200',arch:'arm64',gpuCount:2,modelExpress:false]
    sbsa.jobs[name][1]('-pod-2', true, [infraRetryMax:0])
    def call = calls.last()
    assert call[0] == 'jenkins'
    assert call[1][1..6] == ['gh200','l0_gh200','linux_aarch64',name,index+1,2]
    assert call[1][11] == false
}
sbsa.jobs['Fixture-SBSA-DefaultNodes'][1]('', true, [infraRetryMax:0])
assert calls.last()[0] == 'slurm'
assert calls.last()[1][8] == 1
assert calls.last()[1][9] == false
assert calls.last()[1][13] == 0
p.env.targetArch = 'x86_64-linux-gnu'
def x86 = module.prepareX86TestJobs(p)
x86.jobs['Fixture-X86-Durations'][1]('-pod-2', false, [infraRetryMax:0])
assert calls.last()[0] == 'slurm'
assert calls.last()[1][8] == 1
assert calls.last()[1][12] == true
assert calls.last()[1][13] == 0
println 'PASS: Catalog adapters preserve SBSA GPU/shard counts, Slurm duration flags, and omitted node-count defaults.'
