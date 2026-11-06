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

def initialize(pipeline) { return this }

// Check if a stage key matches a pattern.
// Supports exact match and wildcard '*' for glob-style matching.
// Uses Pattern.quote() to safely handle special characters in stage names.
// Examples: "A10-PyTorch-1" (exact), "*PerfSanity*" (contains), "A10-*" (prefix).
def stageMatchesPattern(String key, String pattern) {
    if (!pattern.contains('*')) {
        return key == pattern
    }
    def regex = '^' + pattern.split('\\*', -1).collect { java.util.regex.Pattern.quote(it) }.join('.*') + '$'
    return key ==~ regex
}

// Check if a stage key matches any pattern in the list.
def stageMatchesAnyPattern(String key, List patterns) {
    return patterns.any { pattern -> stageMatchesPattern(key, pattern) }
}

return this
