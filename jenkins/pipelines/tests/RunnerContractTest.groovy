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
import java.security.MessageDigest
import org.codehaus.groovy.control.SourceUnit

def repoRoot = new File(args ? args[0] : '.')
def loader = new GroovyClassLoader(this.class.classLoader)
loader.addClasspath(new File(repoRoot, 'jenkins/pipeline-library/src').absolutePath)
def methodSource = { File file, String name ->
    def text = file.text
    def source = SourceUnit.create(file.name, text)
    source.parse(); source.completePhase(); source.nextPhase(); source.convert()
    def method = source.AST.classes.find { it.script }.methods.find { it.name == name }
    assert method != null: "Missing ${name} in ${file}"
    text.readLines()[(method.lineNumber - 1)..(method.lastLineNumber - 1)].join('\n')
}
def adapterSource = methodSource(new File(repoRoot, 'jenkins/pipelines/test/JenkinsTestRunner.groovy'), 'runLLMTestlistOnPlatform') + '\n' +
    methodSource(new File(repoRoot, 'jenkins/pipelines/test/JenkinsTestRunner.groovy'), 'executeLLMTestOnSlurm')
def resultSource = ['prepareJenkinsTestResults', 'prepareStageWaives'].collect {
    methodSource(new File(repoRoot, 'jenkins/pipelines/test/Results.groovy'), it)
}.join('\n')
def shell = new GroovyShell(loader)
def adapter = shell.parse(adapterSource + '''
def runLLMTestlistOnPlatformImpl(Object... args) {
    def p = args[0]
    p.events << ['run', args.toList().drop(1)]
    if (p.bodyError != null) throw p.bodyError
    return 'success'
}
return this
''')
def results = shell.parse(resultSource + '''
def cacheErrorAndUploadResult(p, stageName, Closure body, Closure cleanup, skip, tag, fin, retry) {
    p.events << ['cache', stageName, skip, tag, fin, retry]
    def failure = null
    def value = null
    try { value = body() } catch (Exception error) {
        failure = error
        p.events << ['caught', error.class.name, error.message]
    } finally {
        cleanup()
        p.events << ['reported']
    }
    if (failure != null) throw failure
    return value
}
def mergeWaivesTxt(p, src, stageName) { p.events << ['mergeWaives', src, stageName] }
def reusePassedTestResults(src, stageName, path, tag) { pipeline.events << ['reuse', src, stageName, path, tag] }
return this
''')
def mock = shell.parse('''
def sh(arg) {
    def text = arg instanceof Map ? arg.script : arg
    events << ['sh', text.toString().stripIndent()]
    return arg instanceof Map && arg.returnStdout ? '/workspace\\n' : null
}
def withEnv(values, Closure body) { events << ['withEnv', values]; return body() }
def timeout(options, Closure body) { events << ['timeout', options]; return body() }
def input(options) { events << ['input', options]; return null }
def echo(message) { events << ['echo', message.toString()] }
def error(message) { throw new IllegalStateException(message.toString()) }
return this
''')
def canonical
canonical = { value ->
    if (value instanceof Map) return value.collectEntries(new TreeMap()) { k, v -> [(k.toString()): canonical(v)] }
    if (value instanceof List) return value.collect { canonical(it) }
    if (value instanceof CharSequence) return value.toString()
    return value
}
def fingerprint = { value ->
    def json = JsonOutput.toJson(canonical(value))
    MessageDigest.getInstance('SHA-256').digest(json.getBytes('UTF-8')).encodeHex().toString()
}
// Fixed traces cover runner arguments, failure classification, reporting order, and shell commands.
def expected = [
    'k8s/pass/first/true': '7ad49807642f472304df93d5dc8965207f58077778d9bbd3304e3dec67b5da08',
    'k8s/pass/first/false': 'fc72b25be35d88a4f7a190b8be5a37fcb499e6ebee30fab77c07e693868c537f',
    'k8s/pass/-pod-2-attempt-2/true': '164b9098f797b797d308cce502609f81ae53c2e7a3e0761e6897d8395b82610b',
    'k8s/pass/-pod-2-attempt-2/false': '47477ee7b87c098bfff1a2579d808f5ca59362298159522df25e4858fe25de99',
    'k8s/user/first/true': '4caac8511fb0082417a36a9b62ca57b4078777f68e2d03e233dce9f02c824ee3',
    'k8s/user/first/false': 'f9e1295b32068d228244bd3c3bcffd106f5bfb97bc57c5fb6e2d1b00a4531f3e',
    'k8s/user/-pod-2-attempt-2/true': 'c39ddb9eb8068bbb68065d7a00c7ca12157fb60e9cb9a6dd5775663e884664f0',
    'k8s/user/-pod-2-attempt-2/false': '181fefc8bee407cea7b83fceae81694539f29a665e10a6a72a453323354ce6b1',
    'k8s/no-classifier/first/true': '4caac8511fb0082417a36a9b62ca57b4078777f68e2d03e233dce9f02c824ee3',
    'k8s/no-classifier/first/false': 'f9e1295b32068d228244bd3c3bcffd106f5bfb97bc57c5fb6e2d1b00a4531f3e',
    'k8s/no-classifier/-pod-2-attempt-2/true': 'c39ddb9eb8068bbb68065d7a00c7ca12157fb60e9cb9a6dd5775663e884664f0',
    'k8s/no-classifier/-pod-2-attempt-2/false': '181fefc8bee407cea7b83fceae81694539f29a665e10a6a72a453323354ce6b1',
    'k8s/interrupt/first/true': '275729d7ad08c1b9857a79a06eedbb7f39dbde677de0ef853572c46636f359df',
    'k8s/interrupt/first/false': '07a8d51efc011e1e506cd676598c2fb8f5deaeae68dcf0dbb9633083936b294c',
    'k8s/interrupt/-pod-2-attempt-2/true': '17650c6223123b099bd8895143db4d7fd2912bfe49676e2f52cde48749af2aca',
    'k8s/interrupt/-pod-2-attempt-2/false': '9d9ca4eb5e9e770957d21299899aa7c2bee74dbc206fb7d4a2114277f11afd18',
    'k8s/flow-interrupt/first/true': '3d5731dbc140ccc8602e44ffc6f5771b5acaaa5629fe9313b676d37e1e3d8678',
    'k8s/flow-interrupt/first/false': '93a50102f354de67fa0fe86d27476d3888a04ba5d8b4858fa63c8eda5932d214',
    'k8s/flow-interrupt/-pod-2-attempt-2/true': '3288401ee27545edb93187913eb5a377573e4d32fd9c5c7c311a6050ac5c0a99',
    'k8s/flow-interrupt/-pod-2-attempt-2/false': 'acb145e36991bcfdfa09d6fd0b5375a77721940f84ff2020ff0db77b8a42efef',
    'slurm-agent/pass/first/true': '4eb5261851c3cc026865a9d7e4986485280e33f1497a273a3c4cb08ded3fccb5',
    'slurm-agent/pass/first/false': '4eb5261851c3cc026865a9d7e4986485280e33f1497a273a3c4cb08ded3fccb5',
    'slurm-agent/pass/-pod-2-attempt-2/true': '23fc16f583903bc411bcd2869d2b5da7f46b4358091e2572f74ae801ded2fcce',
    'slurm-agent/pass/-pod-2-attempt-2/false': '23fc16f583903bc411bcd2869d2b5da7f46b4358091e2572f74ae801ded2fcce',
    'slurm-agent/user/first/true': 'a5d7aaf0f47c81943c63adf3aba09a752f652b929d4e98c95d5850b37939e30c',
    'slurm-agent/user/first/false': 'a5d7aaf0f47c81943c63adf3aba09a752f652b929d4e98c95d5850b37939e30c',
    'slurm-agent/user/-pod-2-attempt-2/true': '3b9ee49ad5561043c435a082aeb8ebc59db04678c3a30312293d882ad495e573',
    'slurm-agent/user/-pod-2-attempt-2/false': '3b9ee49ad5561043c435a082aeb8ebc59db04678c3a30312293d882ad495e573',
    'slurm-agent/no-classifier/first/true': 'bb67e158fdba9f245a7d6476a389d1e83089fc429e3d08f464169536b14e2596',
    'slurm-agent/no-classifier/first/false': 'bb67e158fdba9f245a7d6476a389d1e83089fc429e3d08f464169536b14e2596',
    'slurm-agent/no-classifier/-pod-2-attempt-2/true': '674c888940d7758657572bf115052cc292b79944dcad81f34dfb4fd5a119281f',
    'slurm-agent/no-classifier/-pod-2-attempt-2/false': '674c888940d7758657572bf115052cc292b79944dcad81f34dfb4fd5a119281f',
    'slurm-agent/interrupt/first/true': '02f949590631db36bffd8e3e70fc2d8a67a9471a06f2fe89d1f196cc1625a629',
    'slurm-agent/interrupt/first/false': '02f949590631db36bffd8e3e70fc2d8a67a9471a06f2fe89d1f196cc1625a629',
    'slurm-agent/interrupt/-pod-2-attempt-2/true': 'f0bc0b154a9cad57187ee65ea2ae9f28574c2b017c11161e9f79aa99529df4a8',
    'slurm-agent/interrupt/-pod-2-attempt-2/false': 'f0bc0b154a9cad57187ee65ea2ae9f28574c2b017c11161e9f79aa99529df4a8',
    'slurm-agent/flow-interrupt/first/true': 'fd5bfe2017dd1634fbaae52c565d07818bac6854f8e8c39e0d084a16ac396d1e',
    'slurm-agent/flow-interrupt/first/false': 'fd5bfe2017dd1634fbaae52c565d07818bac6854f8e8c39e0d084a16ac396d1e',
    'slurm-agent/flow-interrupt/-pod-2-attempt-2/true': 'a7f48a435b6fedf198059e357893f913b2d5e6b32adf570b753f0a3842345f35',
    'slurm-agent/flow-interrupt/-pod-2-attempt-2/false': 'a7f48a435b6fedf198059e357893f913b2d5e6b32adf570b753f0a3842345f35',
    'waives/k8s/false/null': '8b30935ab27fb8cc01cbbc515459bf73dc556a6a2f76f6f538c26f3813399839',
    'waives/k8s/false/false': '94de02f190032c32d159834e3385b0c92f384867897840d8e0990575b85bcf02',
    'waives/k8s/false/true': '8b30935ab27fb8cc01cbbc515459bf73dc556a6a2f76f6f538c26f3813399839',
    'waives/k8s/true/null': '656841dcec4a1f79cee4a8afff1daac7c0553a84d8c65066944af504e1a4fbbd',
    'waives/k8s/true/false': '656841dcec4a1f79cee4a8afff1daac7c0553a84d8c65066944af504e1a4fbbd',
    'waives/k8s/true/true': '656841dcec4a1f79cee4a8afff1daac7c0553a84d8c65066944af504e1a4fbbd',
    'waives/parent/false/null': '8b30935ab27fb8cc01cbbc515459bf73dc556a6a2f76f6f538c26f3813399839',
    'waives/parent/false/false': '94de02f190032c32d159834e3385b0c92f384867897840d8e0990575b85bcf02',
    'waives/parent/false/true': '8b30935ab27fb8cc01cbbc515459bf73dc556a6a2f76f6f538c26f3813399839',
    'waives/parent/true/null': '56184e4ef6fe779f4e8a2bafc1d7d1c58d99edea82d80d1d683b09b1a7c961e2',
    'waives/parent/true/false': '56184e4ef6fe779f4e8a2bafc1d7d1c58d99edea82d80d1d683b09b1a7c961e2',
    'waives/parent/true/true': '56184e4ef6fe779f4e8a2bafc1d7d1c58d99edea82d80d1d683b09b1a7c961e2',
]
int runnerCases = 0
['k8s', 'slurm-agent'].each { path ->
    ['pass', 'user', 'no-classifier', 'interrupt', 'flow-interrupt'].each { scenario ->
        ['', '-pod-2-attempt-2'].each { tag ->
            [true, false].each { fin ->
                def events = []
                def failure = scenario in ['user', 'no-classifier'] ? new IllegalStateException('body-failure') :
                    scenario == 'interrupt' ? new InterruptedException('interrupted') :
                    scenario == 'flow-interrupt' ? new FlowInterruptedException('flow-abort') : null
                def retry = [scope: path == 'k8s' ? 'K8S' : 'SLURM', attempt: 2]
                def parameters = loader.loadClass('trtllm.ci.parameters.RunParameters').newInstance()
                mock.binding = new Binding([events: events, bodyError: failure, LLM_ROOT: 'llm', VANILLA_CONFIG: 'Vanilla',
                    DEBUG_MODE: 'debug', REUSE_TEST: 'reuse_test', parameters: parameters, runParameters:parameters, testFilter: [debug:false],
                    currentBuild: [result:'SUCCESS'], testResults: results, jenkinsRunner: adapter, testPlatform: adapter])
                adapter.binding = new Binding([pipeline:mock])
                results.binding = new Binding([pipeline:mock])
                def classifier = scenario == 'no-classifier' ? null : { error ->
                    events << ['classify', error.class.name, error.message]
                    new IllegalArgumentException('classified-failure')
                }
                def runner = { body ->
                    events << ['runner-enter']
                    try { body() } finally { events << ['runner-exit'] }
                }
                def output
                try {
                    def value = path == 'k8s' ? adapter.runLLMTestlistOnPlatform(mock, 'a10', 'l0', 'Vanilla', 'Stage', 2, 3, true, tag, fin, retry, true) :
                        adapter.executeLLMTestOnSlurm(mock, 'a10', 'l0', 'Vanilla', 'Stage', 2, 3, true, runner, tag, true, retry, classifier)
                    output = [value:value, events:events]
                } catch (Exception error) {
                    output = [error:error.class.name, message:error.message, events:events]
                }
                def id = "${path}/${scenario}/${tag ?: 'first'}/${fin}".toString()
                def digest = fingerprint(output)
                assert digest == expected[id]: "Runner behavior changed for ${id}: ${JsonOutput.prettyPrint(JsonOutput.toJson(canonical(output)))}"
                assert events.findAll { it[0] == 'run' }.size() == 1
                assert events.findAll { it[0] == 'withEnv' }.size() == (path == 'k8s' ? 1 : 0)
                assert events.findAll { it[0] == 'sh' }.size() == 9
                if (path == 'slurm-agent' && scenario == 'user') {
                    assert events.findIndexOf { it[0] == 'classify' } < events.findIndexOf { it[0] == 'caught' }
                    assert output.message == 'classified-failure'
                } else {
                    assert !events.any { it[0] == 'classify' }
                }
                runnerCases++
            }
        }
    }
}
int defaultCases = 0
['k8s', 'slurm-agent'].each { path ->
    def events = []
    def parameters = loader.loadClass('trtllm.ci.parameters.RunParameters').newInstance()
    mock.binding = new Binding([events:events, bodyError:null, LLM_ROOT:'llm', VANILLA_CONFIG:'Vanilla',
        runParameters:parameters, testFilter:[debug:false], currentBuild:[result:'SUCCESS'],
        testResults:results, jenkinsRunner:adapter])
    adapter.binding = new Binding([pipeline:mock])
    results.binding = new Binding([pipeline:mock])
    def value = path == 'k8s' ? adapter.runLLMTestlistOnPlatform(mock, 'a10', 'l0') :
        adapter.executeLLMTestOnSlurm(mock, 'a10', 'l0', { body -> body() })
    assert value == 'success'
    assert events.find { it[0] == 'run' }[1] == ['a10','l0','Vanilla','Undefined',1,1,false,'',false,null]
    assert events.find { it[0] == 'cache' }[4] == true
    defaultCases++
}
int waivesCases = 0
[null, 'parent'].each { parent ->
    [false, true].each { dryRun ->
        [null, false, true].each { reuse ->
            def events = []
            def parameters = loader.loadClass('trtllm.ci.parameters.RunParameters').newInstance()
            mock.binding = new Binding([events:events, parameters:parameters, runParameters:parameters, REUSE_TEST:'reuse_test', testFilter:[reuse_test:reuse], testResults:results])
            results.binding = new Binding([pipeline:mock])
            results.prepareStageWaives(mock, '/source', 'Stage', '/waives.txt', '-attempt-2', dryRun, parent)
            def id = "waives/${parent ?: 'k8s'}/${dryRun}/${reuse}".toString()
            assert fingerprint(events) == expected[id]: "Waive behavior changed for ${id}: ${events}"
            assert events.collect { it[0] } == (dryRun ? ['sh'] : reuse == false ? ['mergeWaives'] : ['mergeWaives','reuse'])
            waivesCases++
        }
    }
}
println "PASS: ${runnerCases} Jenkins runner traces and ${waivesCases} waive preparation traces preserve the original behavior; ${defaultCases} runner default-argument cases pass."

class FlowInterruptedException extends RuntimeException {
    FlowInterruptedException(String message) { super(message) }
}
