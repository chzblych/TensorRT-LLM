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

def root = new File(args ? args[0] : '.')
def loader = new GroovyClassLoader(this.class.classLoader)
loader.addClasspath(new File(root, 'jenkins/pipeline-library/src').absolutePath)
def collector = loader.loadClass('trtllm.ci.results.ParentResults').newInstance()
def contextSource = '''
def createKubernetesPodConfig(image, type) { events << ['pod',image,type]; return [cloud:'synthetic-cpu'] }
def launchKubernetesPod(p, spec, container, Closure body) { events << ['launch',spec,container]; return body() }
def stage(name, Closure body) { events << ['stage',name]; return body() }
def sh(arg) {
    events << ['sh', arg instanceof Map ? arg : arg.toString()]
    return arg instanceof Map && arg.returnStdout ? '0\\n' : null
}
def echo(value) { events << ['echo',value.toString()] }
def llmExecStepWithRetry(Object... args) { events << ['exec',args.find { it instanceof Map }] }
def checkoutSource(Object... args) { events << ['checkout',args.toList()] }
def junit(Map options) { events << ['junit',options] }
def parallel(Map tasks) { events << ['parallel',tasks.keySet().collect { it.toString() }] }
def load(String path) { throw new AssertionError('Workspace modules are unavailable') }
return this
'''
def expected = [
    'SUCCESS':'76f3970a11c014a336e668253cd92e036655da957e34ae952d865a957e59d70b',
    'FAILURE':'647ffc54929450c3a9ff650c062fb59a190f4694262c099214a8825c05efcad5',
]
['SUCCESS','FAILURE'].each { status ->
    def events = []
    def p = new GroovyShell(loader).parse(contextSource)
    p.binding = new Binding([events:events, LLM_REPO:'synthetic-source', LLM_ROOT:'llm', UPLOAD_PATH:'synthetic-artifacts',
        env:[gitlabCommit:'source-sha'], currentBuild:[currentResult:status]])
    p.trtllm_utils = p
    def before = p.binding.variables.keySet().collect().sort()
    collector.collectTestResults(p, [:], [:])
    def json = JsonOutput.toJson(events)
    def digest = MessageDigest.getInstance('SHA-256').digest(json.getBytes('UTF-8')).encodeHex().toString()
    assert digest == expected[status]: "Parent collection changed for ${status}: ${JsonOutput.prettyPrint(json)}"
    assert events.findAll { it[0]=='checkout' } == [['checkout',['synthetic-source','source-sha','llm',true,true]]]
    assert events.any { it[0]=='junit' && it[1].allowEmptyResults }
    assert events.last() == ['parallel',status=='FAILURE' ? ['Rerun Report','Test Coverage','AI Failure Analysis'] : ['Rerun Report','Test Coverage']]
    assert p.binding.variables.keySet().collect().sort() == before
}
println 'PASS: Parent collection remains available without workspace modules and preserves preparation-failure reporting steps.'
