# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Read a Jenkins pipeline together with its repository-local functional modules."""

import re
from pathlib import Path


def read_pipeline_source(entry: Path) -> str:
    """Collect the entry and its functional modules without changing their code."""
    folder = {"L0_Test.groovy": "test", "L0_MergeRequest.groovy": "mr"}.get(entry.name)
    paths = [entry]
    if folder is not None:
        root = entry.parent / "pipelines"
        paths.extend(sorted((root / "common").glob("*.groovy")))
        paths.extend(sorted((root / folder).glob("*.groovy")))
    return "\n".join(path.read_text(encoding="utf-8") for path in paths)


def groovy_function_body(source: str, name: str) -> str:
    """Find a top-level method independently of the methods next to it."""
    starts = list(re.finditer(rf"^(?:def|void) {re.escape(name)}\(", source, re.MULTILINE))
    assert len(starts) == 1, f"expected one top-level definition of {name}"
    start = starts[0].start()
    end = re.search(
        r"^(?:(?:def|void) \w+\(|return this\b)", source[starts[0].end() :], re.MULTILINE
    )
    return source[start : starts[0].end() + end.start()] if end else source[start:]
