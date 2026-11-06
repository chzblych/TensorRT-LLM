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

// From the repository root: groovy jenkins/pipelines/tests/RunParametersTest.groovy .
def root = new File(args ? args[0] : '.')
def loader = new GroovyClassLoader(this.class.classLoader)
loader.addClasspath(new File(root, 'jenkins/pipeline-library/src').absolutePath)
def parameters = loader.loadClass('trtllm.ci.parameters.RunParameters').newInstance()

def bots = [
    [:],
    [reuse_test: true, skip_test: true, add_multi_gpu_test: true, disable_multi_gpu_test: true, debug: true, detailed_log: true],
    [reuse_stage_list: ' A10-1, H100-2 ', stage_list: 'A10-1,A10-2', gpu_type: 'a10, h100', test_backend: 'pytorch,cpp', extra_stage: 'Docs,CPU'],
    [stage_list: '', reuse_stage_list: null, gpu_type: null, extra_stage: ' , , '],
    [stage_list: '  A\\\\B , C  ', test_backend: ' pytorch '],
    [post_merge: true, only_multi_gpu_test: false, multi_gpu_test: true],
    [post_merge: 'false', disable_cbts: 'false', debug: 'false', reuse_test: 'false'],
    [skip_test: null, add_multi_gpu_test: null, disable_multi_gpu_test: null, detailed_log: null, disable_cbts: null],
    [multi_gpu_file_changed: true, only_one_group_changed: 'Docs', auto_trigger_tag_list: ['ignored'], cbts_result: [ignored:true], cbts_coverage:true],
    [only_multi_gpu_test: false, multi_gpu_test: false, disable_cbts: true],
]
def cases = []
bots.each { bot ->
    [false, true].each { postMerge ->
        [false, true].each { dryRun -> cases << [bot:bot, postMerge:postMerge, dryRun:dryRun] }
    }
}
def fingerprint = { value ->
    MessageDigest.getInstance('SHA-256').digest(JsonOutput.toJson(value).getBytes('UTF-8')).encodeHex().toString()
}

// Frozen from f45f06e's original filter expressions, including JSON key order.
def outputs = cases.collect { c -> parameters.parentTestFilter(c.bot, c.postMerge, c.dryRun) }
assert fingerprint(outputs) == '2bb7ddf13f175441305a83522c3f527b0a0ff641ba2145a335a944c4e989ce91'
assert fingerprint(parameters.testFilterDefaults()) == '4f01dcb79969aa8e63bdf92d187a637b0c516e13f9f7822886c207b7e30c56c3'
assert cases.size() == 40
assert parameters.REUSE_TEST == 'reuse_test'
assert parameters.INFRA_DRY_RUN == 'infra_dry_run'
try {
    parameters.REUSE_TEST = 'mutated'
    assert false: 'protocol keys must be read-only'
} catch (ReadOnlyPropertyException expected) { }
def first = parameters.testFilterDefaults()
first[parameters.AUTO_TRIGGER_TAG_LIST] << 'mutated'
assert parameters.testFilterDefaults()[parameters.AUTO_TRIGGER_TAG_LIST] == []
assert parameters.parentTestFilter([:], false, false)[parameters.AUTO_TRIGGER_TAG_LIST] == []
assert !parameters.testFilterDefaults().containsKey(parameters.DISABLE_CBTS)
assert parameters.parentTestFilter([:], false, false).containsKey(parameters.DISABLE_CBTS)
println "PASS: ${cases.size()} parent filters and child defaults match original serialized values; keys and defaults are isolated."
