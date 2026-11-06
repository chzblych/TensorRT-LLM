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

// From the repository root: groovy jenkins/pipelines/tests/ModuleFieldTest.groovy .
def root = new File(args ? args[0] : '.')
def loader = new GroovyClassLoader(this.class.classLoader)
loader.parseClass('package com.nvidia.bloom; class Utils {}; class SlurmCluster {}; class SlurmConfig {}', 'BloomMocks.groovy')
loader.parseClass('package trtllm.exceptions; class InfraFailure extends RuntimeException {}', 'InfraFailure.groovy')
def poison = [pipeline:'unrelated context', buildOptions:[jobs:'unrelated jobs'], testerCores:'unrelated cores',
    modelExpressVersion:'unrelated version', catalog:'unrelated catalog']
def shared = new Binding(new LinkedHashMap(poison))
def shell = new GroovyShell(loader, shared)
def loadModule = { String path ->
    def module = shell.parse(new File(root, "jenkins/pipelines/${path}.groovy"))
    module.run()
    return module
}
def firstContext = [env:[LLM_ARTIFACTS_PRIMARY:'aws', kitmakerCredentialsId:'first-credential'],
    LLM_ARTIFACTS_PRIMARY_FROM_PARENT:'']
def secondContext = [env:[LLM_ARTIFACTS_PRIMARY:'urm', kitmakerCredentialsId:'second-credential'],
    LLM_ARTIFACTS_PRIMARY_FROM_PARENT:'']
def firstPaths = loadModule('common/ArtifactPaths').initialize(firstContext)
def secondPaths = loadModule('common/ArtifactPaths').initialize(secondContext)
assert firstPaths.llmArtifactsPrimary() == 'aws'
assert secondPaths.llmArtifactsPrimary() == 'urm'
assert firstPaths.@pipeline.is(firstContext)
assert secondPaths.@pipeline.is(secondContext)

def firstBuild = loadModule('test/Build').initialize(firstContext)
def secondBuild = loadModule('test/Build').initialize(secondContext)
assert firstBuild.@pipeline.is(firstContext)
assert secondBuild.@pipeline.is(secondContext)
assert firstBuild.@buildOptions.kitmakerCredentialsId == 'first-credential'
assert secondBuild.@buildOptions.kitmakerCredentialsId == 'second-credential'
assert !firstBuild.@buildOptions.is(secondBuild.@buildOptions)
secondBuild.@buildOptions.jobs = '1'
assert firstBuild.@buildOptions.jobs == '4'

def firstExecution = loadModule('test/Execution').initialize(firstContext, '9')
def secondExecution = loadModule('test/Execution').initialize(secondContext, '18')
assert firstExecution.@testerCores == '9'
assert secondExecution.@testerCores == '18'
def firstRunner = loadModule('test/JenkinsTestRunner').initialize(firstContext, 'first-version')
def secondRunner = loadModule('test/JenkinsTestRunner').initialize(secondContext, 'second-version')
assert firstRunner.@modelExpressVersion == 'first-version'
assert secondRunner.@modelExpressVersion == 'second-version'

def firstStages = loadModule('test/Stages').initialize(firstContext, [example:[first:['a10','l0',1,1]]])
def secondStages = loadModule('test/Stages').initialize(secondContext, [example:[second:['a30','l0',1,1]]])
assert firstStages.stageConfigs('example') == [first:['a10','l0',1,1]]
assert secondStages.stageConfigs('example') == [second:['a30','l0',1,1]]
firstStages.stageConfigs('example').first[0] = 'changed-copy'
assert firstStages.@catalog.example.first[0] == 'a10'
assert shared.variables == poison
println 'PASS: Real module fields remain separate across instances sharing a poisoned Binding; local parameters retain their caller context.'
