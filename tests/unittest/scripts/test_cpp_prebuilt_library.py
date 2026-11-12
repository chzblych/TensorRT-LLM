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

"""Same-source wheel integrity checks for native RNN test reuse."""

import base64
import hashlib
import importlib.metadata
import importlib.util
import subprocess
from email.message import Message
from pathlib import Path
from types import SimpleNamespace

import pytest

pytestmark = pytest.mark.cpu_only
_ROOT = Path(__file__).resolve().parents[3]


@pytest.fixture
def artifact(tmp_path, monkeypatch):
    monkeypatch.delenv("LD_PRELOAD", raising=False)
    spec = importlib.util.spec_from_file_location(
        "prebuilt_library", _ROOT / "tests/integration/defs/cpp/prebuilt_library.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    repo = tmp_path / "source"
    (repo / "cpp/tensorrt_llm").mkdir(parents=True)
    (repo / "cpp/tests").mkdir()
    native = repo / "cpp/tensorrt_llm/native.cpp"
    native.write_text("original native source")
    subprocess.run(["git", "init", "-q", str(repo)], check=True)
    subprocess.run(["git", "add", "."], cwd=repo, check=True)
    subprocess.run(
        [
            "git",
            "-c",
            "user.name=Test",
            "-c",
            "user.email=test@example.invalid",
            "commit",
            "-qm",
            "fixture",
        ],
        cwd=repo,
        check=True,
    )
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
    library = tmp_path / "libtensorrt_llm.so"
    library.write_bytes(b"same-build-library")
    entry = importlib.metadata.PackagePath("tensorrt_llm/libs/libtensorrt_llm.so")
    digest = (
        base64.urlsafe_b64encode(hashlib.sha256(library.read_bytes()).digest())
        .rstrip(b"=")
        .decode()
    )
    entry.hash = importlib.metadata.FileHash("sha256=" + digest)
    entry.size = library.stat().st_size
    metadata = Message()
    metadata["Project-URL"] = (
        f"Source Commit, https://github.com/NVIDIA/TensorRT-LLM/commit/{commit}"
    )
    wheel = SimpleNamespace(metadata=metadata, files=[entry], locate_file=lambda _: library)
    monkeypatch.setattr(module.importlib.metadata, "distribution", lambda _: wheel)
    return SimpleNamespace(
        module=module, repo=repo, native=native, library=library, entry=entry, wheel=wheel
    )


def test_same_source_library_and_test_only_edits_can_reuse(artifact):
    assert artifact.module.verified_rnn_library(artifact.repo, "Release") == artifact.library
    (artifact.repo / "cpp/tests/new_test.cpp").write_text("test-only edit")
    assert artifact.module.verified_rnn_library(artifact.repo, "Release") == artifact.library


@pytest.mark.parametrize("untracked", [False, True])
def test_native_source_edits_force_a_source_build(artifact, untracked):
    changed = artifact.native.with_name("new.cpp") if untracked else artifact.native
    changed.write_text("changed native source")
    assert artifact.module.verified_rnn_library(artifact.repo, "Release") is None


@pytest.mark.parametrize("case", ["missing", "different", "duplicate"])
def test_mismatched_provenance_never_reuses(artifact, case):
    metadata = artifact.wheel.metadata
    old = metadata["Project-URL"]
    del metadata["Project-URL"]
    if case == "different":
        metadata["Project-URL"] = (
            "Source Commit, https://github.com/NVIDIA/TensorRT-LLM/commit/" + "0" * 40
        )
    elif case == "duplicate":
        metadata["Project-URL"] = old
        metadata["Project-URL"] = old
    assert artifact.module.verified_rnn_library(artifact.repo, "Release") is None


@pytest.mark.parametrize("case", ["missing", "duplicate", "no_hash", "wrong_hash_kind", "no_size"])
def test_unverifiable_record_uses_source_build(artifact, case):
    if case == "missing":
        artifact.wheel.files = []
    elif case == "duplicate":
        artifact.wheel.files *= 2
    elif case == "no_hash":
        artifact.entry.hash = None
    elif case == "no_size":
        artifact.entry.size = None
    else:
        artifact.entry.hash = importlib.metadata.FileHash("md5=unused")
    assert artifact.module.verified_rnn_library(artifact.repo, "Release") is None


@pytest.mark.parametrize("case", ["missing", "size", "same_size_digest"])
def test_corrupt_library_is_an_error(artifact, case):
    if case == "missing":
        artifact.library.unlink()
    elif case == "size":
        artifact.library.write_bytes(b"wrong")
    else:
        artifact.library.write_bytes(b"x" * artifact.entry.size)
    with pytest.raises(RuntimeError, match="wheel RECORD"):
        artifact.module.verified_rnn_library(artifact.repo, "Release")


def test_debug_build_does_not_reuse_release_library(artifact):
    assert artifact.module.verified_rnn_library(artifact.repo, "Debug") is None


def test_missing_distribution_uses_source_build(artifact, monkeypatch):
    def missing(_):
        raise importlib.metadata.PackageNotFoundError("tensorrt_llm")

    monkeypatch.setattr(artifact.module.importlib.metadata, "distribution", missing)
    assert artifact.module.verified_rnn_library(artifact.repo, "Release") is None


@pytest.mark.parametrize("name", ["bad$path.so", "bad;path.so", 'bad"path.so'])
def test_unsupported_cmake_path_falls_back(artifact, name):
    path = artifact.library.with_name(name)
    artifact.library.rename(path)
    artifact.wheel.locate_file = lambda _: path
    assert artifact.module.verified_rnn_library(artifact.repo, "Release") is None


def test_loader_preload_falls_back(artifact, monkeypatch):
    monkeypatch.setenv("LD_PRELOAD", "custom-loader.so")
    assert artifact.module.verified_rnn_library(artifact.repo, "Release") is None


@pytest.mark.parametrize(
    "case,expected",
    [
        ("compatible", "ON"),
        ("architecture", "OFF"),
        ("cuda", "OFF"),
        ("abi", "OFF"),
        ("debug", "OFF"),
        ("sanitizer", "OFF"),
        ("compiler_flags", "OFF"),
        ("cuda_release_flags", "OFF"),
    ],
)
def test_native_compatibility_gate(tmp_path, case, expected):
    """Exercise the real CMake gate/probe against a controlled native library."""
    import shutil
    import sys

    if sys.platform != "linux" or shutil.which("cmake") is None:
        pytest.skip("Linux CMake/linker is required for the native compatibility gate")
    source = tmp_path / "source"
    include = source / "include"
    common = include / "tensorrt_llm/common"
    common.mkdir(parents=True)
    namespaces = (
        "namespace tensorrt_llm { inline namespace TRTLLM_ABI_NAMESPACE { namespace common {"
    )
    (common / "cudaArchitectures.h").write_text(
        namespaces + "bool isCudaArchitectureBuilt(int); } } }\n"
    )
    (common / "stringUtils.h").write_text(
        "#include <string>\n#include <unordered_set>\n"
        + namespaces
        + "std::unordered_set<std::string> str2set(std::string const&, char); } } }\n"
    )
    (include / "cuda_runtime_api.h").write_text(
        "#define CUDART_VERSION 13000\nusing cudaError_t = int;\nconstexpr int cudaSuccess = 0;\n"
    )
    cuda_version = 12000 if case == "cuda" else 13000
    (source / "artifact.cpp").write_text(
        '#include "tensorrt_llm/common/stringUtils.h"\n'
        + namespaces
        + "std::unordered_set<std::string> str2set(std::string const&, char) "
        '{ return {"rnn", "compatibility"}; }\n'
        "bool isCudaArchitectureBuilt(int sm) { return sm == 80; } } } }\n"
        + f'extern "C" int cudaRuntimeGetVersion(int* value) {{ *value={cuda_version}; return 0; }}\n'
    )
    cmake_text = (
        "cmake_minimum_required(VERSION 3.27)\nproject(rnn_compatibility LANGUAGES CXX)\n"
        "add_library(artifact SHARED artifact.cpp)\n"
        "target_compile_features(artifact PRIVATE cxx_std_20)\n"
        'target_include_directories(artifact PRIVATE "${PROJECT_SOURCE_DIR}/include")\n'
        "target_compile_definitions(artifact PRIVATE TRTLLM_ABI_NAMESPACE=_v1 _GLIBCXX_USE_CXX11_ABI=1)\n"
    )
    cmake_file = source / "CMakeLists.txt"
    cmake_file.write_text(cmake_text)
    build = tmp_path / "build"
    subprocess.run(["cmake", "-S", str(source), "-B", str(build)], check=True, capture_output=True)
    subprocess.run(["cmake", "--build", str(build)], check=True, capture_output=True)
    shutil.copy(_ROOT / "cpp/tests/prebuiltRnnLibraryCheck.cpp", source)
    gate = (
        (_ROOT / "cpp/tests/CMakeLists.txt")
        .read_text()
        .split("# The integration fixture verifies source provenance and wheel RECORD first.", 1)[1]
        .split("add_subdirectory(unit_tests)", 1)[0]
    )
    config = (
        f'set(TRTLLM_RNN_TEST_LIBRARY "{build}/libartifact.so" CACHE FILEPATH "" FORCE)\n'
        f"set(CMAKE_BUILD_TYPE {'Debug' if case == 'debug' else 'Release'})\n"
        f"set(TRTLLM_ABI_NAMESPACE {'_other' if case == 'abi' else '_v1'})\n"
        f"set(CMAKE_CUDA_ARCHITECTURES_ORIG {90 if case == 'architecture' else 80})\n"
        f"set(SANITIZE {'address' if case == 'sanitizer' else 'OFF'})\n"
        f"set(CMAKE_CXX_FLAGS {'--coverage' if case == 'compiler_flags' else ''})\n"
        f"set(CMAKE_CUDA_FLAGS_RELEASE {'--coverage' if case == 'cuda_release_flags' else ''})\n"
        "set(USE_CXX11_ABI 1)\n"
        'set(CUDAToolkit_INCLUDE_DIRS "${PROJECT_SOURCE_DIR}/include")\n'
        "add_library(CUDA::cudart INTERFACE IMPORTED)\n"
    )
    cmake_file.write_text(
        cmake_text
        + config
        + gate
        + 'file(WRITE "${CMAKE_BINARY_DIR}/compatible.txt" "${TLLM_RNN_TEST_LIBRARY_COMPATIBLE}")\n'
    )
    subprocess.run(["cmake", "-S", str(source), "-B", str(build)], check=True, capture_output=True)
    assert (build / "compatible.txt").read_text() == expected
