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

"""Coverage and report ownership for incremental C++ test builds."""

import importlib.util
import subprocess
import sys
import types
from pathlib import Path
from types import SimpleNamespace

import pytest

pytestmark = pytest.mark.cpu_only
_ROOT = Path(__file__).resolve().parents[3]


def _load(name):
    path = _ROOT / "tests/integration/defs/cpp" / f"{name}.py"
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _item(name, arch="90", **params):
    return SimpleNamespace(
        fixturenames=["build_google_tests"],
        originalname=name,
        callspec=SimpleNamespace(params={"build_google_tests": arch, **params}),
    )


def test_collected_targets_keep_full_stack_groups_and_architecture_boundaries():
    items = [
        _item("test_unit_tests", test_group="batch_manager"),
        _item("test_unit_tests", test_group="executor_bounce"),
        _item("test_unit_tests", "80", test_group="runtime"),
        _item("test_cache_transceiver", nprocs=2),
        _item("test_cache_transceiver", nprocs=8),
        SimpleNamespace(fixturenames=[]),
    ]
    assert _load("build_targets").selected_build_targets(items, "90") == [
        "cacheTransceiverTest",
        "cpp-tests-batch_manager",
        "cpp-tests-executor-bounce",
    ]


@pytest.mark.parametrize(
    "name,target",
    [
        ("test_mpi_utils", "mpiUtilsTest"),
        ("test_kv_cache_manager_v2_numa", "kvCacheManagerV2HostMemNumaTest"),
        ("test_fused_gemm_allreduce", "gemmAllReduceTest"),
        ("test_cache_transceiver", "cacheTransceiverTest"),
        ("test_user_buffer", "userBufferTest"),
        ("test_nccl_utils", "ncclUtilsTest"),
    ],
)
def test_each_multi_gpu_consumer_builds_its_executable(name, target):
    assert _load("build_targets").selected_build_targets([_item(name)], 90) == [target]


def test_new_consumer_falls_back_to_full_coverage():
    assert _load("build_targets").selected_build_targets(
        [_item("test_new_native_feature")], "90"
    ) == ["google-tests"]


def test_empty_architecture_is_an_error():
    with pytest.raises(ValueError, match="No collected"):
        _load("build_targets").selected_build_targets([_item("test_mpi_utils")], "80")


@pytest.fixture
def cpp_common(monkeypatch):
    # Report handling does not use build_wheel; isolate that optional import.
    build_wheel = types.ModuleType("build_wheel")
    build_wheel.get_build_dir = lambda *args: None
    monkeypatch.setitem(sys.modules, "build_wheel", build_wheel)
    return _load("cpp_common")


def test_failed_ctest_without_new_report_cannot_retry_stale_results(
    cpp_common, monkeypatch, tmp_path
):
    current = tmp_path / "results-current.xml"
    current.write_text("stale current report")
    unrelated = tmp_path / "results-other.xml"
    unrelated.write_text("another group")
    calls = []

    def fail(command, **kwargs):
        calls.append(command)
        assert not current.exists()
        raise subprocess.CalledProcessError(1, command)

    monkeypatch.setattr(cpp_common, "run_ctest", fail)
    with pytest.raises(subprocess.CalledProcessError):
        cpp_common.parallel_run_ctest(["ctest", "--output-junit", str(current)], cwd=tmp_path)
    assert len(calls) == 1
    assert unrelated.read_text() == "another group"


@pytest.mark.parametrize("relative_cwd", [False, True])
def test_retry_merges_only_the_current_report(cpp_common, monkeypatch, tmp_path, relative_cwd):
    monkeypatch.chdir(tmp_path.parent)
    cwd = Path(tmp_path.name) if relative_cwd else tmp_path
    current = tmp_path / "results-current.xml"
    report_arg = current.name if relative_cwd else str(current)
    unrelated = tmp_path / "results-other.xml"
    unrelated.write_text("another group")
    calls = []

    def run(command, **kwargs):
        calls.append(command)
        current.write_text(
            '<testsuite failures="0" time="1"><testcase name="current"/></testsuite>'
        )
        if len(calls) == 1:
            raise subprocess.CalledProcessError(1, command)
        assert command[-1] == "--rerun-failed"
        assert "CTEST_PARALLEL_LEVEL" not in kwargs["env"]

    monkeypatch.setattr(cpp_common, "run_ctest", run)
    cpp_common.parallel_run_ctest(["ctest", "--output-junit", report_arg], cwd=cwd)
    assert len(calls) == 2
    assert 'parallel="2"' in current.read_text()
    assert unrelated.read_text() == "another group"


def test_directory_groups_include_legacy_full_stack_and_nested_tests(tmp_path):
    import re
    import shutil

    cmake = shutil.which("cmake")
    if cmake is None:
        pytest.skip("CMake is required for the native group coverage check")
    source = (_ROOT / "cpp/tests/CMakeLists.txt").read_text()
    helper = re.search(
        r"function\(add_tllm_test_directory_group .*?endfunction\(\)", source, re.S
    ).group()
    root = tmp_path / "source"
    nested = root / "tests/unit_tests/executor/bounce"
    nested.mkdir(parents=True)
    (root / "test.cpp").write_text("int main() { return 0; }\n")
    (root / "tests/unit_tests/executor/CMakeLists.txt").write_text(
        "add_executable(legacy ${PROJECT_SOURCE_DIR}/test.cpp)\n"
        "add_executable(full_stack EXCLUDE_FROM_ALL ${PROJECT_SOURCE_DIR}/test.cpp)\n"
        "add_library(non_test INTERFACE)\nadd_subdirectory(bounce)\n"
    )
    (nested / "CMakeLists.txt").write_text(
        "add_executable(transport EXCLUDE_FROM_ALL ${PROJECT_SOURCE_DIR}/test.cpp)\n"
    )
    (root / "CMakeLists.txt").write_text(
        "cmake_minimum_required(VERSION 3.27)\nproject(group_coverage LANGUAGES CXX)\n"
        "add_subdirectory(tests/unit_tests/executor)\n" + helper + "\n"
        'add_tllm_test_directory_group("${PROJECT_SOURCE_DIR}/tests/unit_tests/executor")\n'
        "get_target_property(parent cpp-tests-executor MANUALLY_ADDED_DEPENDENCIES)\n"
        "get_target_property(child cpp-tests-executor-bounce MANUALLY_ADDED_DEPENDENCIES)\n"
        'file(WRITE "${CMAKE_BINARY_DIR}/members.txt" "${parent}\\n${child}\\n")\n'
    )
    build = tmp_path / "build"
    subprocess.run(
        [cmake, "-S", str(root), "-B", str(build)], check=True, capture_output=True, text=True
    )
    parent, child = (build / "members.txt").read_text().splitlines()
    assert set(parent.split(";")) == {"legacy", "full_stack", "cpp-tests-executor-bounce"}
    assert child == "transport"
    subprocess.run(
        [cmake, "--build", str(build), "--target", "cpp-tests-executor"],
        check=True,
        capture_output=True,
        text=True,
    )


@pytest.mark.parametrize("configured_root", [None, "", "/custom/build", "relative-build"])
def test_build_root_matches_configure_rules(cpp_common, monkeypatch, tmp_path, configured_root):
    if configured_root is None:
        monkeypatch.delenv("TRTLLM_BUILD_ROOT", raising=False)
    else:
        monkeypatch.setenv("TRTLLM_BUILD_ROOT", configured_root)
    monkeypatch.setattr(cpp_common, "find_root_dir", lambda: tmp_path)
    calls = []

    def resolve(build_dir, build_type, *, build_root=None):
        calls.append((build_dir, build_type, build_root))
        return tmp_path / "resolved-build"

    monkeypatch.setattr(cpp_common, "get_trt_llm_build_dir", resolve)
    assert cpp_common.find_build_dir("Release") == tmp_path / "resolved-build"
    assert calls == [(None, "Release", configured_root or None)]
