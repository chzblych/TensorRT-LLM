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

import groovy.transform.Field
import groovy.json.JsonOutput

@Field def pipeline

def initialize(pipeline)
{
    this.@pipeline = pipeline
    return this
}

def getInfraDryRunPytestTargets(testListPath) {
    if (!this.@pipeline.testSelection.isInfraDryRun()) {
        return []
    }

    // --test-list filters after collection, so also pass the exact rendered
    // nodeid positionally to avoid importing unrelated product tests.
    def targets = this.@pipeline.readFile(file: testListPath).readLines()
        .collect { it.trim().split(/\s+/, 2)[0] }
        .findAll { it.contains("::") }
    def expectedTarget =
        "test_infra_dry_run_benchmark.py::test_infra_dry_run_benchmark"
    if (targets != [expectedTarget]) {
        this.@pipeline.error "Unexpected pytest targets in infrastructure dry-run list ${testListPath}: ${targets}"
    }
    return targets
}

def processShardTestList(llmSrc, testDBList, splitId, splits, durationsPath="") {
    // Preprocess testDBList to extract ISOLATION markers
    this.@pipeline.echo "Preprocessing testDBList to extract ISOLATION markers..."

    def originalTestLines = this.@pipeline.readFile(file: testDBList).readLines()

    def cleanedTestLines = []
    def isolationTestLines = []

    originalTestLines.each { originalLine ->
        def trimmedLine = originalLine.trim()
        if (trimmedLine && trimmedLine.contains('ISOLATION')) {
            // Remove ISOLATION marker and nearby comma from the line
            def cleanedLine = trimmedLine

            // Handle different comma patterns around ISOLATION
            if (trimmedLine.contains('ISOLATION,')) {
                // Case: "ISOLATION,OTHER_MARKER" -> remove "ISOLATION,"
                cleanedLine = cleanedLine.replace('ISOLATION,', '').trim()
            } else if (trimmedLine.contains(',ISOLATION')) {
                // Case: "OTHER_MARKER,ISOLATION" -> remove ",ISOLATION"
                cleanedLine = cleanedLine.replace(',ISOLATION', '').trim()
            } else {
                // Case: standalone "ISOLATION" -> remove " ISOLATION"
                cleanedLine = cleanedLine.replace(' ISOLATION', '').trim()
            }

            // Add the cleaned line to isolationTestLines if original line had ISOLATION
            isolationTestLines.add(cleanedLine)
            cleanedTestLines.add(cleanedLine)

        } else if (trimmedLine) {
            // Line doesn't contain ISOLATION, add as-is
            cleanedTestLines.add(originalLine.trim())
        }
    }

    // Create cleaned testDBList file (without ISOLATION markers)
    def cleanedTestDBList = testDBList.replaceAll('\\.txt$', '_cleaned.txt')
    if (cleanedTestLines.size() > 0) {
        def cleanedContent = cleanedTestLines.join('\n')
        this.@pipeline.sh "echo '${cleanedContent.replace("'", "'\\''")}' > ${cleanedTestDBList}"
        this.@pipeline.echo "Created cleaned testDBList: ${cleanedTestDBList} with ${cleanedTestLines.size()} lines (ISOLATION markers removed)"
    } else {
        this.@pipeline.sh "touch ${cleanedTestDBList}"
        this.@pipeline.echo "No tests found, created empty cleaned testDBList: ${cleanedTestDBList}"
    }

    this.@pipeline.sh "cat ${cleanedTestDBList}"
    this.@pipeline.echo "Original testDBList contains ${isolationTestLines.size()} tests that had ISOLATION markers"

    def shardTestList = []

    def testListCmd = [
        "LLM_ROOT=${llmSrc}",
        "LLM_BACKEND_ROOT=${llmSrc}/triton_backend",
        "pytest",
        "--collect-only",
        "--splitting-algorithm least_duration",
        "--test-list=${cleanedTestDBList}",
        "--quiet",
        "--splits ${splits}",
        "--group ${splitId}",
    ]
    if (durationsPath) {
        testListCmd += ["--durations-path ${durationsPath}"]
    }
    testListCmd += getInfraDryRunPytestTargets(cleanedTestDBList)

    try {
        // First execute the pytest command and check if it succeeds
        def pytestOutput = this.@pipeline.sh(
            script: "cd ${llmSrc}/tests/integration/defs && ${testListCmd.join(' ')}",
            returnStdout: true
        ).trim()

        // Debug: Show the raw pytest output
        this.@pipeline.echo "<<<START_PYTEST_OUTPUT>>>"
        this.@pipeline.echo "${pytestOutput}"
        this.@pipeline.echo "<<<END_PYTEST_OUTPUT>>>"

        // Filter the output to get only test lines with '::' that occur after "Running X items in this shard"
        def lines = pytestOutput.split('\n')
        def foundRunningLine = false
        def lineIndex = 0
        shardTestList = lines.findAll { line ->
            lineIndex++

            if (line.matches(/.*Running \d+ items in this shard.*/) || line.matches(/.*\[pytest-split\] Running group.*/)) {
                foundRunningLine = true
                return false  // Don't include the "Running" line itself
            }
            // Stop collecting when we hit the warnings/errors summary separator
            if (foundRunningLine && line.contains('======================')) {
                foundRunningLine = false  // Stop collecting
                return false
            }

            def hasDoubleColon = line.contains('::')
            def shouldInclude = foundRunningLine && hasDoubleColon
            return shouldInclude
        }
        this.@pipeline.echo "Filtering complete. shardTestList size: ${shardTestList.size()}"
    } catch (Exception e) {
        this.@pipeline.echo "Error: Failed to execute pytest command for test collection: ${e.getMessage()}"
        this.@pipeline.error "Test collection failed for shard ${splitId}/${splits}. Cannot proceed without valid test list."
    }

    if (shardTestList) {
        // Split the shard test list into regular and isolate tests
        def shardRegularTests = []
        def shardIsolateTests = []

        // Process each test from shardTestList
        shardTestList.each { test ->
            def trimmedTest = test.trim()
            if (trimmedTest) {
                // Process test_unittests.py::test_unittests_v2[xxxx] pattern
                if (trimmedTest.startsWith('test_unittests.py::test_unittests_v2[') && trimmedTest.endsWith(']')) {
                    // Extract content between [ and ]
                    def startIndex = trimmedTest.indexOf('[') + 1
                    def endIndex = trimmedTest.lastIndexOf(']')
                    trimmedTest = trimmedTest.substring(startIndex, endIndex)
                }

                // Check if this test is in the isolation list
                def isolationTestLine = isolationTestLines.find { it.contains(trimmedTest) }
                if (isolationTestLine) {
                    // This test needs isolation
                    shardIsolateTests.add(isolationTestLine)
                } else {
                    // This test is a regular test - find the actual line from cleanedTestLines
                    def cleanedTestLine = cleanedTestLines.find { it.contains(trimmedTest) }
                    shardRegularTests.add(cleanedTestLine)
                }
            }
        }

        // Define file paths for regular and isolate tests
        def regularTestList = testDBList.replaceAll('\\.txt$', '_regular.txt')
        def isolateTestList = testDBList.replaceAll('\\.txt$', '_isolate.txt')

        // Create shard-specific test files
        if (shardRegularTests.size() > 0) {
            def shardRegularContent = shardRegularTests.join('\n')
            this.@pipeline.sh "echo '${shardRegularContent.replace("'", "'\\''")}' > ${regularTestList}"
            this.@pipeline.echo "Created ${regularTestList} with ${shardRegularTests.size()} regular tests for this shard"
        } else {
            this.@pipeline.sh "touch ${regularTestList}"
            this.@pipeline.echo "No regular tests in this shard, created empty file: ${regularTestList}"
        }
        this.@pipeline.sh "cat ${regularTestList}"

        if (shardIsolateTests.size() > 0) {
            def shardIsolateContent = shardIsolateTests.join('\n')
            this.@pipeline.sh "echo '${shardIsolateContent.replace("'", "'\\''")}' > ${isolateTestList}"
            this.@pipeline.echo "Created ${isolateTestList} with ${shardIsolateTests.size()} isolate tests for this shard"
        } else {
            this.@pipeline.sh "touch ${isolateTestList}"
            this.@pipeline.echo "No isolate tests in this shard, created empty file: ${isolateTestList}"
        }
        this.@pipeline.sh "cat ${isolateTestList}"

        // Return preprocessed lists object for compatibility
        return [
            regular: regularTestList,
            isolate: isolateTestList,
            regularCount: shardRegularTests.size(),
            isolateCount: shardIsolateTests.size()
        ]
    } else {
        this.@pipeline.echo "No tests found in current shard or failed to list tests"
        // Create empty files and preprocessed lists object
        def regularTestList = testDBList.replaceAll('\\.txt$', '_regular.txt')
        def isolateTestList = testDBList.replaceAll('\\.txt$', '_isolate.txt')
        this.@pipeline.sh "touch ${regularTestList}"
        this.@pipeline.sh "touch ${isolateTestList}"

        return [
            regular: regularTestList,
            isolate: isolateTestList,
            regularCount: 0,
            isolateCount: 0
        ]
    }
}

def transformMakoArgsToJson(optList) {
    def makoOpts = [:]
    def startedMakoOpts = false
    def param = null
    def value = null
    optList.each { val ->
        if (startedMakoOpts) {
            // Handle case where value is missing
            param = null
            value = null
            try {
                (param, value) = val.split("=")
            } catch (ArrayIndexOutOfBoundsException ex) {
                param = val.split("=")[0]
                value = null
            }

            // Try to convert nulls, booleans, and floats into the correct type
            if (value != null) {
                if (value.toLowerCase() == "none") {
                    this.@pipeline.echo "Converted mako param '${param}' value '${value}' to 'null'"
                    value = null
                } else if (value.toLowerCase() in ["true", "false"]) {
                    this.@pipeline.echo "Converted mako param '${param}' value '${value}' to Boolean '${value.toBoolean()}'"
                    value = value.toBoolean()
                }
            }
            makoOpts[(param)] = value
        }
        if (val.equals("Mako options:")) {
            startedMakoOpts = true
        }
    }

    def makoOptsJson = JsonOutput.toJson(makoOpts)

    // Print and return the Test DB Query as a JSON string
    this.@pipeline.echo "Test DB Mako opts: ${makoOptsJson}"
    return makoOptsJson
}

def getMakoOpts(getMakoScript, makoArgs=[]) {
    // We want to save a map for the Mako opts
    def makoOutput = ""

    // Echo the command
    // NOTE: We redirect stderr to stdout so that we can capture
    //  both stderr and stdout streams with the 'returnStdout' flag
    //  in sh command.
    def listMakoCmd = [
        "python3",
        getMakoScript,
        "--device 0"].join(" ")

    if (makoArgs) {
        def makoOptArgs = makoArgs.collect { "--mako-opt " + it }
        listMakoCmd += " " + makoOptArgs.join(" ")
    }
    // Add the withCredentials step to access gpu-chip-mapping file
    this.@pipeline.withCredentials([this.@pipeline.file(credentialsId: 'gpu-chip-mapping', variable: 'GPU_CHIP_MAPPING')]) {
        listMakoCmd = [listMakoCmd, "--chip-mapping-file ${this.@pipeline.GPU_CHIP_MAPPING}"].join(" ")
        listMakoCmd = [listMakoCmd, "2>&1"].join(" ")

        this.@pipeline.echo "Scripts to get Mako list, cmd: ${listMakoCmd}"

        // Capture the mako output, add timeout in case any hang
        this.@pipeline.timeout(time: 30, unit: 'MINUTES'){
            makoOutput = this.@pipeline.sh(label: "Capture Mako Parameters", script: listMakoCmd, returnStdout: true)
        }
    }

    // Validate output
    assert makoOutput: "Mako opts not found - could not construct test db test list."

    // Split each line of mako output into a list
    def outputList = makoOutput.split("\n")

    def makoOptsJson = transformMakoArgsToJson(outputList)

    return makoOptsJson
}

def parseTaskConfigFromStageName(String stageName) {
    def taskConfig = null
    def matcher = (stageName =~ /([^-]+)(?:-(\d+)_GPUs)?(?:-(\d+)_Nodes)?/)
    if (matcher.find()) {
        taskConfig = [
            gpu: "${matcher.group(1)}",
            system_gpu_count: matcher.group(2) ?: "1", // Default to 1 if _GPUs not present
            node_count: matcher.group(3) ?: "1" // Default to 1 if _Nodes not present
        ]
    }
    return taskConfig
}

def getMakoArgsFromStageName(stageName, parseSysinfo=false) {
    def makoArgs = []
    def isPostMerge = stageName.contains("Post-Merge")
    makoArgs += [isPostMerge ? "stage=post_merge" : "stage=pre_merge"]
    // Determine the backend type based on keywords in stageName
    if (stageName.contains("-PyTorch-")) {
        // If stageName contains "-PyTorch-", add "backend=pytorch" to makoArgs
        // At this point, only tests with backend=pytorch or unspecified backend will be run
        makoArgs += ["backend=pytorch"]
    } else if (stageName.contains("-CPP-")) {
        // If stageName contains "-CPP-", add "backend=cpp" to makoArgs
        // At this point, only tests with backend=cpp or unspecified backend will be run
        makoArgs += ["backend=cpp"]
    } else if (stageName.contains("-Triton-")) {
        // If stageName contains "-Triton-", add "backend=triton" to makoArgs
        // At this point, only tests with backend=triton or unspecified backend will be run
        makoArgs += ["backend=triton"]
    } else if (stageName.contains("-FMHA-")) {
        // If stageName contains "-FMHA-", add "backend=fmha" to makoArgs
        // At this point, only tests with backend=fmha or unspecified backend will be run
        makoArgs += ["backend=fmha"]
    } else if (stageName.contains("-Generic-")) {
        // Generic stages select tests by marker expression rather than backend ownership.
        makoArgs += ["backend=generic"]
    } else {
        // If stageName does not contain "-PyTorch-", "-CPP-", "-Triton-", or "-FMHA-", do not add any backend
        // At this point, all tests will be run
        // For cases where backend is not specified in makoArgs, we will match all types of backends and tests without specified backend
    }
    if (stageName.contains("-DeepSeek-")) {
        makoArgs += ["auto_trigger=deepseek"]
    } else if (stageName.contains("-GptOss-")) {
        makoArgs += ["auto_trigger=gpt_oss"]
    } else {
        makoArgs += ["auto_trigger=others"]
    }
    if (stageName.contains("-Ray-")) {
        // If stageName contains "-Ray-", add "orchestrator=ray" to makoArgs
        // At this point, only tests with orchestrator=ray or unspecified orchestrator will be run.
        // Mark tests with orchestrator=mpi to exclude them from Ray stage.
        makoArgs += ["orchestrator=ray"]
    } else {
        // Otherwise select tests with orchestrator=mpi or unspecified orchestrator
        makoArgs += ["orchestrator=mpi"]
    }

    if (parseSysinfo) {
        def taskConfig = parseTaskConfigFromStageName(stageName)
        if (taskConfig) {
            makoArgs += [
                "gpu=${taskConfig.gpu}",
                "system_gpu_count=${taskConfig.system_gpu_count}"
            ]
        }
    }

    return makoArgs
}

def renderTestDB(pipeline, testContext, llmSrc, stageName, preDefinedMakoOpts=null, String clusterName=null) {
    def makoOpts = preDefinedMakoOpts

    if (!makoOpts) {
        def makoArgs = getMakoArgsFromStageName(stageName)
        if (stageName.startsWith("CPU-")) {
            def cpuName = pipeline.env.targetArch == pipeline.AARCH64_TRIPLE ? "aarch64" : "x86_64"
            makoOpts = transformMakoArgsToJson(
                ["Mako options:"] + makoArgs + [
                    "system_gpu_count=0",
                    "cpu=${cpuName}",
                    "linux_distribution_name=ubuntu"
                ])
        } else {
            def scriptPath = "${llmSrc}/tests/integration/defs/sysinfo/get_sysinfo.py"
            makoOpts = getMakoOpts(scriptPath, makoArgs)
        }
    }

    // Log the resolved mako match on every render, tagged with stage+context.
    // transformMakoArgsToJson already echoes the bare JSON ("Test DB Mako opts:"),
    // but unlabeled and far upstream of the render; the preDefinedMakoOpts path
    // skips it entirely. This co-locates the match with the stage/context and the
    // "-> N tests" summary below under one greppable renderTestDB: prefix, so a
    // wrong-but-non-empty render (a stale/unexpected sysinfo value selecting the
    // wrong block) is diagnosable per stage, not just the "na"/empty cases.
    pipeline.echo "renderTestDB: stage=${stageName} context=${testContext} mako match: ${makoOpts}"

    if (makoOpts.contains('"na"')) {
        // "na" is a sysinfo probe failure sentinel (see get_sysinfo.py). Blocks
        // conditioned on the failed property silently drop out of the render, so
        // even a non-empty list may be missing tests. Warn here, where every
        // sysinfo-based stage passes through, not just when the list ends up empty.
        pipeline.echo "WARNING: renderTestDB: some sysinfo probes returned \"na\": ${makoOpts}. " +
             "Test-db blocks conditioned on those properties (e.g. linux_distribution_name: ubuntu*) " +
             "will NOT be selected."
    }
    // Artifacts built before ci_versions.properties existed (e.g. via reuse_build)
    // lack the file; fall back to the version those artifacts were pinned to.
    def ciVersionsPath = "${llmSrc}/jenkins/ci_versions.properties"
    def ciVersions = pipeline.fileExists(ciVersionsPath)
        ? pipeline.readProperties(file: ciVersionsPath)
        : [TRT_TEST_DB_VERSION: "1.8.5+bc6df7"]
    pipeline.sh "pip3 install --extra-index-url https://urm.nvidia.com/artifactory/api/pypi/sw-tensorrt-pypi/simple --ignore-installed trt-test-db==${ciVersions.TRT_TEST_DB_VERSION}"
    // CBTS Layer 3: download the pre-built cbts_test_db/ tarball that the
    // orchestrator uploaded to Artifactory (see getCbtsResult in
    // L0_MergeRequest.groovy). This avoids re-running main.py locally and
    // avoids passing large PR-diff payloads as Jenkins parameters (env vars).
    // If the download or extraction fails we swallow the error: the override
    // directory will be absent below, the overrideYaml check will fail, and
    // renderTestDB falls back to the source test-db.
    def cbts = pipeline.testFilter[(pipeline.runParameters.CBTS_RESULT)]
    if (cbts != null && cbts.test_db_dir_override && cbts.cbts_test_db_artifact_path) {
        try {
            // Always re-fetch: a reused workspace may hold a stale cbts_test_db/ shadowing this build's YAMLs.
            def artifactUrl = "${pipeline.URM_ARTIFACTORY_BASE}/${cbts.cbts_test_db_artifact_path}"
            pipeline.trtllm_utils.llmExecStepWithRetry(pipeline, script: "wget -nv '${artifactUrl}' -O /tmp/cbts_test_db.tar.gz && tar xzf /tmp/cbts_test_db.tar.gz -C ${llmSrc}")
            pipeline.echo "CBTS Layer 3: extracted cbts_test_db from artifact"
        } catch (Exception e) {
            pipeline.echo "CBTS Layer 3: artifact download failed " +
                 "(${e.class.simpleName}: ${e.message}); falling back to source test-db"
        }
    }
    def testDBPath = "${llmSrc}/tests/integration/test_lists/test-db"
    if (cbts != null && cbts.test_db_dir_override) {
        def overrideYaml = "${llmSrc}/${cbts.test_db_dir_override}/${testContext}.yml"
        def overrideOk = pipeline.sh(returnStdout: true, script: "test -s ${overrideYaml} && echo yes || echo no").trim()
        if (overrideOk == "yes") {
            testDBPath = "${llmSrc}/${cbts.test_db_dir_override}"
            pipeline.echo "CBTS [${cbts.scope}]: rendering test list from filtered test-db at ${testDBPath}"
        } else {
            pipeline.echo "CBTS [${cbts.scope}]: ${overrideYaml} missing/empty -- falling back to source test-db"
        }
    }
    def testList = "${llmSrc}/${testContext}.txt"
    def testDBQueryCmd = [
        "trt-test-db",
        "-d",
        testDBPath,
        "--context",
        testContext,
        "--test-names",
        "--output",
        testList,
        "--match",
        "'${makoOpts}'"
    ].join(" ")

    pipeline.sh(label: "Render test list from test-db", script: testDBQueryCmd)
    // Count non-empty lines, not newlines: trt-test-db writes the test names
    // with no trailing newline, so `wc -l` undercounts by one -- it reports 0
    // for a single-test render, which would trip the empty-list guard below
    // (and mis-report every stage's count by one). `grep -c .` is agnostic to
    // the missing terminator. It exits 1 for no matches (a legitimately empty
    // render -> count "0"); accept only that, so a read failure (exit 2:
    // missing file, unreadable, etc.) still aborts the step instead of being
    // silently masked.
    def testCount = pipeline.sh(returnStdout: true, script: "grep -c . -- ${testList} || test \$? -eq 1").trim()
    def testDBLabel = (cbts != null && cbts.test_db_dir_override) ? "CBTS-narrowed [${cbts.scope}]" : "source"
    pipeline.echo "renderTestDB: stage=${stageName} context=${testContext} test-db=${testDBLabel} dir=${testDBPath} -> ${testCount} tests"
    pipeline.sh(script: "cat ${testList}")
    if (testCount == "0") {
        // An empty render is never legitimate here: every launched stage must
        // have tests (CBTS drops stages with an empty selection before launch).
        // Fail now with the match query rather than letting pytest --collect-only
        // exit 5 later with an unattributable "Test collection failed" message.
        def hint = makoOpts.contains('"na"') ?
            " Some sysinfo probes returned \"na\" (see the match JSON above); a broken probe" +
            " (e.g. the python 'distro' module missing) makes conditions like" +
            " linux_distribution_name: ubuntu* match nothing." : ""
        pipeline.error("renderTestDB: rendered EMPTY test list for stage=${stageName} " +
              "context=${testContext} test-db=${testDBLabel}. Match query: ${makoOpts}.${hint}")
    }
    pipeline.infraRetry.recordRenderedStageAttemptEstimate(pipeline, llmSrc, testList, stageName, testCount, clusterName)

    return testList
}

def getSSHConnectionPorts(portConfigFile, stageName)
{
    def type = stageName.split('-')[0]
    this.@pipeline.echo "The type is: ${type}"
    def fileContent = this.@pipeline.sh(script: "cat ${portConfigFile}", returnStdout: true).trim()

    // Get available VM port list from portConfigFile based on stage name (e.g. A10: [10022, 10023])
    def portList = []
    fileContent.split('\n').each { line ->
        def matcher = (line =~ /(.+?)=\[(.+?)\]/)
        if (matcher) {
            def key = matcher[0][1].replaceAll("\\s","")
            def values = matcher[0][2].replaceAll("\\s","").split(',').collect { it.replaceAll("\\s","") }
            if (key == type) {
                portList.addAll(values)
            }
        }
    }
    this.@pipeline.echo "Port List for ${type}: ${portList}"

    // Get current port usage status
    def portUsage = ""
    this.@pipeline.withCredentials([
        this.@pipeline.usernamePassword(credentialsId: 'tensorrt_llm_infra_debug_vm_01_credentials', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD'),
        this.@pipeline.string(credentialsId: 'DEBUG_HOST_NAME', variable: 'HOST_NAME')
        ]) {
        portUsage = this.@pipeline.sh(script: "ssh -v ${this.@pipeline.USERNAME}@${this.@pipeline.HOST_NAME} ${this.@pipeline.COMMON_SSH_OPTIONS} 'netstat -tuln'", returnStdout: true)
    }
    this.@pipeline.echo "Port Usage: ${portUsage}"

    // Get an available VM port
    def userPort = 0
    while (portList.size() > 0) {
        def randomIndex = (int)(Math.random() * portList.size())
        def curPort = portList[randomIndex].toInteger()
        if (!portUsage.contains(":${curPort}")) {
            userPort = curPort
            break
        }
        portList.remove(randomIndex)
    }

    if (userPort == 0) {
        this.@pipeline.echo "There is no available port for ${type}"
        return [0, 0]
    }

    this.@pipeline.echo "The chosen port is: ${userPort}"

    // Calculate autossh monitor port by subtracting 9000 from VM port (e.g. 10022 -> 1022)
    // If monitor port is already in use, randomly assign a value between 2000-3000
    def monitorPort = userPort - 9000
    while (portUsage.contains(":${monitorPort}")) {
        monitorPort = 2000 + (int)(Math.random() * 1000)
    }

    this.@pipeline.echo "The monitor port is: ${monitorPort}"

    return [userPort, monitorPort]
}

return this
