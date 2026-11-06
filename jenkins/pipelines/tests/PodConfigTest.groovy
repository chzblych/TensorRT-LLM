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
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.CompilerConfiguration
import java.security.MessageDigest

// From the repository root: groovy jenkins/pipelines/tests/PodConfigTest.groovy .
def repoRoot = new File(args ? args[0] : '.')
def loader = new GroovyClassLoader(this.class.classLoader)
loader.addClasspath(new File(repoRoot, 'jenkins/pipeline-library/src').absolutePath)
def factory = loader.loadClass('trtllm.ci.pods.PodFactory').newInstance()

def cases = []
['agent', 'package', 'unknown'].each { type ->
    ['amd64', 'arm64'].each { arch ->
        cases << [id: "mr-${type}-${arch}", kind: 'mr', image: 'synthetic-image', type: type, arch: arch]
    }
}
cases << [id: 'mr-default-agent', kind: 'mr', image: '', type: 'agent', arch: 'amd64', defaultArgs: true]
['agent', 'slurm', 'build', 'cpu'].each { type ->
    ['amd64', 'arm64'].each { arch ->
        cases << [id: "test-${type}-${arch}", kind: 'test', image: 'synthetic-image', type: type, arch: arch, gpuCount: 1, modelExpress: false]
    }
    cases << [id: "test-${type}-overrides", kind: 'test', image: 'override-image', type: type, arch: 'amd64', gpuCount: 1, modelExpress: false,
              overrides: [POD_TIMEOUT_SECONDS_TEST:'111', POD_TIMEOUT_SECONDS_BUILD:'222', POD_TIMEOUT_SECONDS_SLURM:'333',
                          SLURM_CORES_REQUEST:'3', SLURM_CORES_LIMIT:'4', SLURM_MEMORY_REQUEST:'5Gi', SLURM_MEMORY_LIMIT:'6Gi',
                          BUILD_CORES_REQUEST:'7', BUILD_CORES_LIMIT:'9', BUILD_MEMORY_REQUEST:'64Gi', BUILD_MEMORY_LIMIT:'128Gi',
                          TESTER_CPU_ONLY_CORES:'5', TESTER_CPU_ONLY_MEMORY:'24Gi', ARTIFACTORY_IMAGE_PULL_SECRET:'synthetic-pull-secret']]
}
['a10', 'b100-ts2', 'rtx-5080', 'rtx-5090', 'rtx-pro-6000', 'rtx-pro-6000d', 'dgx-h100-x4', 'dgx-h200', 'gh200', 'gb10x'].each { type ->
    ['amd64', 'arm64'].each { arch ->
        [1, 4].each { gpuCount ->
            cases << [id: "test-${type}-${arch}-gpu${gpuCount}", kind: 'test', image: 'synthetic-image', type: type, arch: arch, gpuCount: gpuCount, modelExpress: false]
        }
    }
}
['a10', 'dgx-h100-x4', 'gb10x', 'cpu', 'agent'].each { type ->
    ['amd64', 'arm64'].each { arch ->
        [1, 2].each { gpuCount ->
            cases << [id: "mx-${type}-${arch}-gpu${gpuCount}", kind: 'test', image: 'synthetic-image', type: type, arch: arch, gpuCount: gpuCount, modelExpress: true]
        }
    }
}
cases << [id:'test-default-gpu', kind:'test', image:'default-image', type:'a10', arch:'amd64', gpuCount:1, modelExpress:false, defaultArgs:true]
cases << [id:'test-gpu-resource-overrides', kind:'test', image:'override-image', type:'a10', arch:'amd64', gpuCount:1, modelExpress:false,
          overrides:[TESTER_CORES:'9', TESTER_MEMORY:'128Gi', POD_TIMEOUT_SECONDS_TEST:'444']]
cases << [id:'mx-image-overrides', kind:'test', image:'override-image', type:'a10', arch:'amd64', gpuCount:2, modelExpress:true,
          overrides:[MODEL_EXPRESS_REDIS_IMAGE:'synthetic-redis', MODEL_EXPRESS_SERVER_IMAGE:'synthetic-server']]
['slurm', 'build', 'cpu', 'a10'].each { type ->
    cases << [id: "env-timeout-${type}", kind:'test', image:'timeout-image', type:type, arch:'amd64', gpuCount:1, modelExpress:false,
              envTimeout:'456', overrides:[POD_TIMEOUT_SECONDS_TEST:'456', POD_TIMEOUT_SECONDS_BUILD:'456', POD_TIMEOUT_SECONDS_SLURM:'456']]
}


// Fingerprints include the entire returned map, exact YAML bytes, errors, and dependency call order.
def expected = [
    'mr-agent-amd64': '09d4069cf950a1c65670f39d8e4c848dda912ee31d13e32fdae4b309eb7a9d13',
    'mr-agent-arm64': '58a88e4c7b6ea563b352df7c5ce8047f4534eec20fbfbc755c48099f90f96eb9',
    'mr-package-amd64': '4cd5f5773c50905fd17fb0967ad2fefd4f113b997ecac74307b180b719ed127f',
    'mr-package-arm64': '91854cca381c423d5244239f855e9896be53a595d1132435bf99d7b955cd7871',
    'mr-unknown-amd64': '87f0ec2a948e4f5fccf91a962303d92b19ff4c76addc1f36ee7a9f63ca980862',
    'mr-unknown-arm64': 'f3728bc56bb6d5ec40c2f27c02182a2ea7c3a575fc1887d1ba9729a72fd8ae93',
    'mr-default-agent': '09d4069cf950a1c65670f39d8e4c848dda912ee31d13e32fdae4b309eb7a9d13',
    'test-agent-amd64': '1829c6449f53debf0b39df26c1c73c68e5c2d93d27c9a01ba550db597931b9d3',
    'test-agent-arm64': 'a150b1d7ede1fc2dc629d8f65cab235cbff89aa59ef61a673f4ae906a6dda60c',
    'test-agent-overrides': 'b871e6afeaa200ec34e3c35a2266c1fcbcdc74ffa769f66073bd9006e724736d',
    'test-slurm-amd64': 'ea01c7ff0f59d0e83b4078ce07452ce05292bdf2204e514c2d4003f15fb8f1c4',
    'test-slurm-arm64': 'd44e601463e4848a3f26a7d04637805feecd75748d1e65f015a47381981ab078',
    'test-slurm-overrides': '3faeb783ac58a25cd68ae3d7ccb51d0f3b3a66a3b872d73ac618e179e6d76121',
    'test-build-amd64': 'c07259e1b05173c198e0cadb3cea41b20225dd1b2eebc39dd9a6804333a2d567',
    'test-build-arm64': '3823955ba91e7f3fdfe55b04c4b89e8b8ac1871fdf617d1b426b2d0e08499158',
    'test-build-overrides': '3efe97daea998a97044551c8bda6aa114f8d281296dea00ae8d61cf06a7ce969',
    'test-cpu-amd64': 'd14544a605fca97020ef0e19ace3574fa79a4fc0208acc4109186873a54c6765',
    'test-cpu-arm64': '70051ef4fb9cc7b5fe2fe25cdf3b9202de6339af3e07bddeff32ed518c428ccf',
    'test-cpu-overrides': 'beaea84987664437b181323274e0af6fe4fbef3952015a8a79cdf7437fc331fe',
    'test-a10-amd64-gpu1': '570f7899bae98fd62d8ce887c5ad478c5a7657eb7627f07f6def1b6b50f97505',
    'test-a10-amd64-gpu4': '6a57a569d95394471191c0cb9288189f2537877131c687fb53fdb7ab5640d3e6',
    'test-a10-arm64-gpu1': 'bf9d78d95c498b9f1a67765ef985457744e0bdbab4ee091d5c66dfbad5be0ea7',
    'test-a10-arm64-gpu4': '26cf9f0841b1f724ebb21b5154ae5d209012f6fd62ea60a92c6fa037802f437b',
    'test-b100-ts2-amd64-gpu1': 'a40b2012aad77279fb04bea5e1d879b092a63135bb382b09ace2b292bc382501',
    'test-b100-ts2-amd64-gpu4': '879191fefe1ddc3e734f40f955626f5412e005399c9dd9a2e843bfd648b7b0ef',
    'test-b100-ts2-arm64-gpu1': '45dd41d1db0125ef4b8bf01b77a004610137d8bbafa9ac70d8a17ce8cfb8cdbe',
    'test-b100-ts2-arm64-gpu4': 'a700080b10ea701d5aa010d7c186814f539a56feed53eebca16c4b6b966a8e54',
    'test-rtx-5080-amd64-gpu1': '076faa4ea0483709f5d010309682dd92ccbc03ae468e7c2bea0d3300f8589bc8',
    'test-rtx-5080-amd64-gpu4': 'b90a256a9b38d5e04017337aea07746a7ebd9d61f10319a04a4cdf34c2ac13d9',
    'test-rtx-5080-arm64-gpu1': 'e0ac82e3ac15fe202566fa50fa53cce5f1f1863fc3f2b3a33874fa25317e00d4',
    'test-rtx-5080-arm64-gpu4': 'ca24b07155be7b5806e0d914cf8b8c6cf6a40818923e7808bbce455ebe25f154',
    'test-rtx-5090-amd64-gpu1': '689f66d47a76542fb2742d82994321b308e2e0fca6a19b3806679fa627c36701',
    'test-rtx-5090-amd64-gpu4': '716d3d81b22591781fa33a5f9fba55753d2a92ca6c790e28603af7397c269eda',
    'test-rtx-5090-arm64-gpu1': 'df134010e44d0d3b067193a8389609fc4f4a96583f24940d9379740abe2c4e86',
    'test-rtx-5090-arm64-gpu4': 'd4f5c07a1809f8136b24269a2933ef651005c70dcf53b8a5f9dd0b02ccc6fb55',
    'test-rtx-pro-6000-amd64-gpu1': '449e8baf1fae24ff16b51969bd9f52fda4feb43027b69611919b4934d80f2fec',
    'test-rtx-pro-6000-amd64-gpu4': 'a1e61528ba8e796fbc33a03ec131c9be965789667e825a951b72345de516175e',
    'test-rtx-pro-6000-arm64-gpu1': 'cf0a2774755d3b5ecb2b0eb1ed24aaef3c125e266aff70c755e7f09c92bab72c',
    'test-rtx-pro-6000-arm64-gpu4': '3a2fa9c0d9049c603e6a5178d530b56029e785ff573f976f270636fa5006881c',
    'test-rtx-pro-6000d-amd64-gpu1': '84e4fccb41c06f02c4b100121a6391d16d23778dd21ca67cf027871d8c7da56e',
    'test-rtx-pro-6000d-amd64-gpu4': '42990b5d6d03091437e8b631eed171ff3879046690b91d7ffb087861ffb02ddb',
    'test-rtx-pro-6000d-arm64-gpu1': 'cd8b74f69d7343daa5e39c27e8dbb869fb4b732b516b5b665a546d0687c4ce7e',
    'test-rtx-pro-6000d-arm64-gpu4': 'a8a23bab3311c003106ccad7961e0e7d43c1367bae08e008c0534e36f42a724b',
    'test-dgx-h100-x4-amd64-gpu1': '4b5c2ceca01eccf9c473b3fc014795bdba79d80d65e7475405a74ee303a8df49',
    'test-dgx-h100-x4-amd64-gpu4': '1d57bf93e5a465a5b37d0b6af2366eb4cce1ab63b2b4ca286191ac95ee26e77e',
    'test-dgx-h100-x4-arm64-gpu1': '7e288069b88eae22879e284ba927b743045e26c03bae65904c891a752be6e4b0',
    'test-dgx-h100-x4-arm64-gpu4': '597baa85ac711bb256d621f290c4d028cecc83e1b59698342ece81191b1fd02a',
    'test-dgx-h200-amd64-gpu1': '4eff870e8e5277109adfad4498625c023068367432fcd93368d992e6e648fc00',
    'test-dgx-h200-amd64-gpu4': '05fb91a87bb04225dabbc3edb4374e698b25f22c162955d2a36a3365dc27e4d9',
    'test-dgx-h200-arm64-gpu1': 'af8ad707b19971e9c8c96a5737c9182ac35b6a2dc95093c4459741e3d801cbd8',
    'test-dgx-h200-arm64-gpu4': 'ac2829c098401e77a75fa27acfa89b391ff4148eb52124b95f0e15cf86353efd',
    'test-gh200-amd64-gpu1': '01452fc0133446b8a83ed8a101dfcf95445afb577603ff3a53c0da0422a07ea0',
    'test-gh200-amd64-gpu4': '0fc27abb1a4f6f0ba677387e30d0846d89a71f963ec9820848ccb9c84aa98fa9',
    'test-gh200-arm64-gpu1': 'df6bc984917ea5f32c48423a315f949c35de7102fd05754455f6e07ada618ee7',
    'test-gh200-arm64-gpu4': 'c168781aa197fbe8439ea25e8e3e20170aa392644172fec7031550c899cbe6bc',
    'test-gb10x-amd64-gpu1': 'fe5a9ffa7107787e87b5980b6514a30e7c0079946ac51942bc8cbea7eb3eac7c',
    'test-gb10x-amd64-gpu4': 'a66ad102eb2d9b846e7bd9b71ffebc991143dfb5a5c68beb192d4536a695176a',
    'test-gb10x-arm64-gpu1': '2297f66eb563ee86f7095dec5a85c572dcbb531b9007574a99cb35a00286e85f',
    'test-gb10x-arm64-gpu4': '839e6a65dd15c43aea5cba8fd76243470f50bf8c3f9c1235437cff0f3bf312ce',
    'mx-a10-amd64-gpu1': '275418999caa39ba6bd548876eabae4b23151764ef3c374993a6396cffa3763e',
    'mx-a10-amd64-gpu2': 'e1f7f33b25837355307583426af442c250dfaf8f1cd0f286608f1da394910994',
    'mx-a10-arm64-gpu1': '0d7b5d2f5b29c4e4708967e4baf8938f9c04f1dac50fc6f69cdf3ccebba00b63',
    'mx-a10-arm64-gpu2': '0d7b5d2f5b29c4e4708967e4baf8938f9c04f1dac50fc6f69cdf3ccebba00b63',
    'mx-dgx-h100-x4-amd64-gpu1': '9f5b5297408be1b673ea65bcb105cf5a3e1e1422eaffd9126037a49cd5b9f387',
    'mx-dgx-h100-x4-amd64-gpu2': '760cedf4ded9866cbdbc99fe082347f6f9a910fab513fc6ecb9b31ef1043fea0',
    'mx-dgx-h100-x4-arm64-gpu1': 'a23cb80dac66a32efde4b5576ebffdb7303df385b13bf8b6b4ea730386f4bbec',
    'mx-dgx-h100-x4-arm64-gpu2': 'a23cb80dac66a32efde4b5576ebffdb7303df385b13bf8b6b4ea730386f4bbec',
    'mx-gb10x-amd64-gpu1': '5a783e6b952aedffd1dbd80fd29dcc06cdbc36d69a8886e82c3d641f2837598a',
    'mx-gb10x-amd64-gpu2': '6ac018fc539d7227116b0bd12a6aadfbb986ca5ae00015178d655e91fe5b0f78',
    'mx-gb10x-arm64-gpu1': 'cd24186471a453b88837de999da53173a457e50f99a9577067814b4ae8e50010',
    'mx-gb10x-arm64-gpu2': 'cd24186471a453b88837de999da53173a457e50f99a9577067814b4ae8e50010',
    'mx-cpu-amd64-gpu1': '398387febfddb7265230e4862783cd0843862daf418c6076d7cf1e1358cb45f1',
    'mx-cpu-amd64-gpu2': '398387febfddb7265230e4862783cd0843862daf418c6076d7cf1e1358cb45f1',
    'mx-cpu-arm64-gpu1': 'a50f598a8278003476af4bcccc30e3bee272b19257db6dad7c19cad7a5000197',
    'mx-cpu-arm64-gpu2': 'a50f598a8278003476af4bcccc30e3bee272b19257db6dad7c19cad7a5000197',
    'mx-agent-amd64-gpu1': '7407f87cce309cfac896b5253620dca894e72e56e010c70ab9800fcdddf9fda6',
    'mx-agent-amd64-gpu2': '7407f87cce309cfac896b5253620dca894e72e56e010c70ab9800fcdddf9fda6',
    'mx-agent-arm64-gpu1': 'a50f598a8278003476af4bcccc30e3bee272b19257db6dad7c19cad7a5000197',
    'mx-agent-arm64-gpu2': 'a50f598a8278003476af4bcccc30e3bee272b19257db6dad7c19cad7a5000197',
    'test-default-gpu': '733b46b4bd687ba020c768893f4cd3e3224e0780629a24ce92f90e75a6f74743',
    'test-gpu-resource-overrides': '6041e95d47c82862be2e682739ed86a21b0e76a94ae71350174d8eb6e12395c3',
    'mx-image-overrides': '7f046e4dd45359773c2afdcb803901eb0dfe58316e70cdf4c708e90190be82ff',
    'env-timeout-slurm': '8fe8ea53f1a10c9ddd9f8cdebeb001535a6b5969557b939ed5902b3802a4cb4f',
    'env-timeout-build': '4e7057488b3f7f42d45d478231afa43316437875af5f33df44672c4154a04ea0',
    'env-timeout-cpu': 'ad91944ef4f85dd2e3a2172102ed217f3585f92c4820e59732fc95722e71ec26',
    'env-timeout-a10': 'af0e213c1eaa5fd30d1ca3b57a8ea2ada9e1db4bd6aeffcf17051f588c8ab84a',
]

def canonical
canonical = { value ->
    if (value instanceof Map) return value.collectEntries(new TreeMap()) { key, item -> [(key.toString()): canonical(item)] }
    if (value instanceof List) return value.collect { canonical(it) }
    if (value instanceof CharSequence) return value.toString()
    return value
}

def outputs = [:]
cases.each { c ->
    def calls = []
    def inputs = factory.defaults(c.overrides ?: [:])
    if (c.kind == 'test' && !(c.type in ['agent', 'slurm', 'build', 'cpu'])) {
        calls << ['selectGPU', c.type]
        inputs.gpuType = "gpu-${c.type}"
        inputs.defaultDriverVersion = 'closed-driver'
        inputs.defaultOpenDriverVersion = 'open-driver'
    }
    def output
    try {
        def pod = c.kind == 'mr' ? factory.mergeRequestPod(c.image, c.type, c.arch, inputs) :
            factory.testPod(c.image, c.type, c.arch, c.gpuCount, c.modelExpress, inputs)
        calls << ['generateNodeLabel', pod.label]
        pod.label = "label-${pod.label}"
        output = [pod: canonical(pod), calls: canonical(calls)]
    } catch (Exception error) {
        output = [error: error.class.name, message: error.message, calls: canonical(calls)]
    }
    def data = JsonOutput.toJson(canonical(output))
    def digest = MessageDigest.getInstance('SHA-256').digest(data.getBytes('UTF-8')).encodeHex().toString()
    assert digest == expected[c.id.toString()]: "${c.id} Pod output changed: ${digest}\n${JsonOutput.prettyPrint(data)}"
    outputs[c.id.toString()] = output
    if (output.error) {
        assert c.modelExpress && c.arch == 'arm64'
        assert output.message == 'ModelExpress CI sidecars currently support amd64 test pods only.'
        assert !output.calls.any { it[0] == 'generateNodeLabel' }
    } else {
        def pod = output.pod
        assert pod.namespace == 'sw-tensorrt'
        assert pod.label == "label-${c.kind == 'mr' || c.type in ['agent', 'slurm', 'build', 'cpu'] ? (c.type == 'unknown' ? '' : 'cpu') : c.type}"
        assert pod.yaml.contains("custom_jnlp_images_${c.arch == 'arm64' ? 'arm' : 'amd'}_linux:jdk17")
        if (c.kind == 'test') {
            assert pod.yaml.contains('server: 10.117.145.13') == (c.arch == 'arm64')
            assert pod.yaml.contains('claimName: sw-tensorrt-pvc') == (c.arch != 'arm64')
            assert pod.yaml.contains('MODEL_EXPRESS_URL') == c.modelExpress
            assert pod.yaml.contains('"ipp6"') == (c.type == 'cpu')
            if (!(c.type in ['agent', 'slurm', 'build', 'cpu'])) {
                assert pod.yaml.contains("nvidia.com/gpu: ${c.gpuCount}")
                assert pod.cloud == (c.type.contains('gb10x') ? 'nvks-sparks-cloud' : 'kubernetes')
                assert pod.yaml.contains('server: 10.59.164.111') == (c.type.contains('6000d') || c.type.contains('rtx-5080'))
            }
        } else {
            assert pod.cloud == 'kubernetes-cpu'
            assert pod.yaml.contains('memory: 5Gi')
        }
    }
}
def wrappers = [:]
['MergeRequest', 'Test'].each { kind ->
    def sourceFile = new File(repoRoot, "jenkins/L0_${kind}.groovy")
    def text = sourceFile.text
    def source = SourceUnit.create(sourceFile.name, text)
    source.parse()
    source.completePhase()
    source.nextPhase()
    source.convert()
    def method = source.AST.classes.find { it.script }.methods.find { it.name == 'createKubernetesPodConfig' }
    def body = text.readLines()[(method.lineNumber - 1)..(method.lastLineNumber - 1)].join('\n')
    wrappers[kind] = new GroovyShell(loader).parse(body + '\nreturn this\n')
}
def helper = new GroovyShell(loader).parse('''
def selectGPU(type) { calls << ['selectGPU', type]; return "gpu-${type}" }
def generateNodeLabel(prefix) { calls << ['generateNodeLabel', prefix]; return "label-${prefix}" }
return this
''')
int wrapperCases = 0
cases.findAll { !it.overrides || it.envTimeout }.each { c ->
    def calls = []
    helper.binding = new Binding([calls: calls])
    def wrapper = wrappers[c.kind == 'mr' ? 'MergeRequest' : 'Test']
    wrapper.binding = new Binding([
        podFactory: factory, env: [podTimeoutSeconds: c.envTimeout],
        KubernetesManager: helper, trtllm_utils: helper,
        Constants: [DEFAULT_NVIDIA_DRIVER_VERSION: 'closed-driver', DEFAULT_NVIDIA_OPEN_DRIVER_VERSION: 'open-driver'],
    ])
    def output
    try {
        def pod = c.defaultArgs ? wrapper.createKubernetesPodConfig(c.image, c.type) :
            c.kind == 'mr' ? wrapper.createKubernetesPodConfig(c.image, c.type, c.arch) :
            wrapper.createKubernetesPodConfig(c.image, c.type, c.arch, c.gpuCount, c.modelExpress)
        output = [pod: canonical(pod), calls: canonical(calls)]
    } catch (Exception error) {
        output = [error: error.class.name, message: error.message, calls: canonical(calls)]
    }
    def data = JsonOutput.toJson(canonical(output))
    def digest = MessageDigest.getInstance('SHA-256').digest(data.getBytes('UTF-8')).encodeHex().toString()
    assert digest == expected[c.id.toString()]: "${c.id} entry wrapper changed: ${digest}\n${JsonOutput.prettyPrint(data)}"
    wrapperCases++
}
int bootstrapCases = 0
['MergeRequest', 'Test'].each { kind ->
    def text = new File(repoRoot, "jenkins/L0_${kind}.groovy").text
    def start = text.indexOf('def ciLibrary = library(')
    def closing = kind == 'Test' ? 'stageCatalog = ciLibrary.stages.StageCatalog.new()' : 'parentResults = ciLibrary.results.ParentResults.new()'
    def end = text.indexOf(closing, start) + closing.length()
    assert start >= 0 && end > start
    assert end < text.indexOf('pipeline {')
    def expression = text.substring(start, end)
    [
        [gitlabMergeRequestLastCommit: 'mr-sha', gitlabCommit: 'commit-sha', gitlabBranch: 'topic'],
        [gitlabCommit: 'commit-sha', gitlabBranch: 'topic'],
        [gitlabBranch: 'topic'],
        [:],
    ].each { env ->
        def scm = [repository: 'snapshot-source', revision: 'snapshot-ref']
        def parameters = loader.loadClass('trtllm.ci.parameters.RunParameters').newInstance()
        def catalog = loader.loadClass('trtllm.ci.stages.StageCatalog').newInstance()
        def binding = new Binding([env: env, scm: scm, factory: factory, parameters:parameters, catalog:catalog, parentCollector:[id:'preloaded-parent-results'], calls: []])
        def result = new GroovyShell(loader, binding, new CompilerConfiguration()).evaluate('''
class PodFactoryHandle {
    Object factory
    def 'new'() { return factory }
}
def legacySCM(Map options) { calls << ['legacySCM', options]; return options }
def library(Map options) {
    calls << ['library', options]
    return [trtllm: [ci: [pods: [PodFactory: new PodFactoryHandle(factory: factory)],
        parameters: [RunParameters: new PodFactoryHandle(factory: parameters)],
        stages: [StageCatalog: new PodFactoryHandle(factory: catalog)],
        results: [ParentResults: new PodFactoryHandle(factory: parentCollector)]]]]
}
''' + expression + '\nreturn [factory: podFactory, parameters: runParameters, calls: calls]' +
            (kind == 'MergeRequest' ? ' + [parentCollector: parentResults]' : '') + '\n')
        assert result.factory.is(factory)
        assert result.parameters.is(parameters)
        if (kind == 'MergeRequest') assert result.parentCollector == [id:'preloaded-parent-results']
        assert result.calls.collect { it[0] } == ['legacySCM', 'library']
        def options = result.calls[1][1]
        def ref = env.gitlabMergeRequestLastCommit ?: env.gitlabCommit ?: env.gitlabBranch ?: 'main'
        assert options.identifier.toString() == "trtllm-ci@${ref}"
        assert options.changelog == false
        assert options.retriever.scm.is(scm)
        assert options.retriever.libraryPath == 'jenkins/pipeline-library'
        bootstrapCases++
    }
}
assert factory.defaults([podTimeoutSeconds: '456']).POD_TIMEOUT_SECONDS_TEST == '456'
assert factory.defaults([podTimeoutSeconds: '456']).POD_TIMEOUT_SECONDS_BUILD == '456'
assert factory.defaults([podTimeoutSeconds: '456']).POD_TIMEOUT_SECONDS_SLURM == '456'
assert factory.defaults([podTimeoutSeconds: '']).POD_TIMEOUT_SECONDS_TEST == '21600'
def nullMergeRequest = factory.mergeRequestPod('', null)
nullMergeRequest.label = "label-${nullMergeRequest.label}"
assert canonical(nullMergeRequest) == outputs['mr-unknown-amd64'].pod
def originalDefaults = factory.defaults()
def firstDefaults = factory.defaults()
firstDefaults.REQUIRED_OPEN_DRIVER_TYPES.clear()
assert factory.defaults().REQUIRED_OPEN_DRIVER_TYPES == originalDefaults.REQUIRED_OPEN_DRIVER_TYPES
assert factory.defaults().REQUIRED_NO_DRIVER_TYPES == originalDefaults.REQUIRED_NO_DRIVER_TYPES
['alternate', '', null].each { version ->
    def defaults = factory.defaults([MODEL_EXPRESS_VERSION: version])
    assert defaults.MODEL_EXPRESS_VERSION == version
    assert defaults.MODEL_EXPRESS_SERVER_IMAGE.toString().endsWith(":${version}")
}
assert factory.defaults([MODEL_EXPRESS_VERSION: 'alternate', MODEL_EXPRESS_SERVER_IMAGE: 'explicit-server']).MODEL_EXPRESS_SERVER_IMAGE == 'explicit-server'
println "PASS: ${cases.size()} complete Pod outputs and dependency call sequences match fixed baseline hashes; ${wrapperCases} entry wrapper cases and ${bootstrapCases} pre-agent library loads match."
