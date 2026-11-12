# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Validate the installed, same-source wheel before reusing its RNN library."""

import base64
import hashlib
import importlib.metadata
import logging
import os
import re
import subprocess
from pathlib import Path

_NATIVE_INPUTS = [
    "cpp/tensorrt_llm",
    "cpp/include",
    "cpp/kernels",
    "cpp/cmake",
    "cpp/CMakeLists.txt",
    "3rdparty",
    "scripts/build_wheel.py",
    "setup.py",
    "requirements.txt",
    "constraints.txt",
    ":(glob)tensorrt_llm/_torch/_experimental/modeling_v2/models/**/csrc/**",
    ":(glob)requirements-*.txt",
]
_LIBRARY = "tensorrt_llm/libs/libtensorrt_llm.so"


def verified_rnn_library(source_root: Path, build_type: str) -> Path | None:
    """Return a verified artifact or use a source build; corrupt artifacts fail."""
    if build_type != "Release" or os.environ.get("LD_PRELOAD"):
        return None
    try:
        wheel = importlib.metadata.distribution("tensorrt_llm")
        commit = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=source_root, text=True
        ).strip()
        dirty = subprocess.check_output(
            ["git", "status", "--porcelain", "--untracked-files=normal", "--", *_NATIVE_INPUTS],
            cwd=source_root,
            text=True,
        )
    except (importlib.metadata.PackageNotFoundError, OSError, subprocess.SubprocessError):
        logging.info("RNN test: wheel/source provenance unavailable; building from source")
        return None
    if not re.fullmatch(r"[0-9a-f]{40}", commit) or dirty:
        logging.info("RNN test: native source inputs are modified; building from source")
        return None
    source_urls = [
        entry.partition(",")[2].strip().lower()
        for entry in wheel.metadata.get_all("Project-URL", [])
        if entry.partition(",")[0].strip() == "Source Commit"
    ]
    expected = f"https://github.com/nvidia/tensorrt-llm/commit/{commit}"
    if source_urls != [expected]:
        logging.info(
            "RNN test: installed wheel is not from this source commit; building from source"
        )
        return None
    entries = [entry for entry in wheel.files or [] if str(entry) == _LIBRARY]
    if len(entries) != 1 or not entries[0].hash or entries[0].hash.mode != "sha256":
        logging.info("RNN test: SHA256 wheel RECORD entry unavailable; building from source")
        return None
    entry = entries[0]
    if entry.size is None:
        logging.info("RNN test: wheel RECORD size unavailable; building from source")
        return None
    library = Path(wheel.locate_file(entry)).resolve()
    if any(char in str(library) for char in '"`$;\r\n'):
        logging.info(
            "RNN test: library path cannot be passed to the build helper; building from source"
        )
        return None
    if not library.is_file() or library.stat().st_size != entry.size:
        raise RuntimeError("RNN test library does not match its wheel RECORD size")
    digest = hashlib.sha256()
    with library.open("rb") as stream:
        for chunk in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            digest.update(chunk)
    actual = base64.urlsafe_b64encode(digest.digest()).rstrip(b"=").decode()
    if actual != entry.hash.value:
        raise RuntimeError("RNN test library does not match its wheel RECORD SHA256")
    logging.info("RNN test: same-source library verified against wheel RECORD")
    return library
