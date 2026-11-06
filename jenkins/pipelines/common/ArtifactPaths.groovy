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

@Field def pipeline

def initialize(pipeline)
{
    this.pipeline = pipeline
    return this
}

def llmArtifactsPrimary()
{
    def primary = pipeline.LLM_ARTIFACTS_PRIMARY_FROM_PARENT ?: pipeline.env.LLM_ARTIFACTS_PRIMARY ?: "aws"
    if (!(primary in ["aws", "urm"])) {
        pipeline.error("LLM_ARTIFACTS_PRIMARY must be 'aws' or 'urm', not '${primary}'")
    }
    return primary
}

def llmArtifactsLayoutPath(String legacyPath)
{
    def layoutPath = legacyPath.replaceFirst(/^sw-tensorrt-generic(-local)?\//, "")
    if (!layoutPath.startsWith("llm-artifacts/")) {
        pipeline.error("Not an llm-artifacts path: ${legacyPath}")
    }
    return layoutPath
}

def llmArtifactsRepoPath(String location, String legacyPath)
{
    return "${pipeline.LLM_ARTIFACTS_LOCATIONS[location].repo}/${llmArtifactsLayoutPath(legacyPath)}"
}

def llmArtifactsUrl(String location, String legacyPath)
{
    return "${pipeline.LLM_ARTIFACTS_LOCATIONS[location].root}/${llmArtifactsRepoPath(location, legacyPath)}"
}

return this
