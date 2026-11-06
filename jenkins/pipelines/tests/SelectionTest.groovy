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

// From the repository root: groovy jenkins/pipelines/tests/SelectionTest.groovy .
def repoRoot = new File(args ? args[0] : '.')
def helper = new GroovyShell().parse(new File(repoRoot, 'jenkins/pipelines/test/Selection.groovy'))
helper.run()
def context = [invalidStageName: 'unrelated caller state']
helper.initialize(context)
helper.checkStageName(['A10-PyTorch-1', 'CPU-Build_Docs', 'Stage[2]+GPU'])
assert context == [invalidStageName: 'unrelated caller state']
try {
    helper.checkStageName(['valid', 'bad/stage'])
    assert false: 'Invalid stage names must fail validation'
} catch (Exception expected) {
    assert expected.message == "Invalid stage name: [[bad/stage]], we only support chars '-+_[]0-9a-zA-Z' ."
}
assert context == [invalidStageName: 'unrelated caller state']
println 'PASS: Stage-name validation preserves accepted names, errors, and caller state.'
