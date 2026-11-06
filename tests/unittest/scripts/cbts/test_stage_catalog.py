# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
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
"""Canonical catalog expansion and its legacy-compatible discovery contract."""

import hashlib
import json
from dataclasses import asdict
from pathlib import Path

import pytest

__extra_import_path__ = ["~/jenkins/scripts/cbts"]
from cbts.blocks import parse_stages_from_groovy
from cbts.stage_catalog import (
    expand_catalog,
    expand_for_pipeline,
    load_stage_records,
    stage_config_path,
)

pytestmark = pytest.mark.cpu_only
ROOT = Path(__file__).resolve().parents[4]
CATALOG = ROOT / "jenkins/pipelines/test/stages.json"
ROW = {"name": "A10-PyTorch-{shard}", "platform": "a10", "testList": "l0_a10", "splits": 3}


def _catalog(rows, runner="kubernetes"):
    return {
        "version": 1,
        "groups": [{"name": "example", "arch": "x86", "runner": runner, "stages": rows}],
    }


def _pipeline_catalog(rows=(), group_name="x86TestConfigs"):
    catalog = json.loads(CATALOG.read_text())
    for group in catalog["groups"]:
        group["stages"] = list(rows) if group["name"] == group_name else []
    return catalog


def test_current_catalog_preserves_original_discovery_metadata_and_order():
    entry = ROOT / "jenkins/L0_Test.groovy"
    assert stage_config_path(entry) == CATALOG
    stages = parse_stages_from_groovy(entry, include_post_merge=True)
    assert len(stages) == 123
    payload = json.dumps(
        [asdict(s) for s in stages.values()], sort_keys=True, separators=(",", ":")
    )
    assert (
        hashlib.sha256(payload.encode()).hexdigest()
        == "522fe987636c7eaec574b7f97b6f046768f3a8a852bd10b742738a8b1bf81a15"
    )
    raw = json.loads(CATALOG.read_text())
    assert len(expand_catalog(raw, discoverable_only=False)) == 156
    assert len(load_stage_records(entry)) == 123
    pre_merge = parse_stages_from_groovy(entry)
    assert len(pre_merge) == 69
    assert all(stage.mako["stage"] == "pre_merge" for stage in pre_merge.values())


def test_new_family_expands_shards_once_and_preserves_standalone_name(tmp_path):
    entry = tmp_path / "jenkins/L0_Test.groovy"
    catalog = entry.parent / "pipelines/test/stages.json"
    catalog.parent.mkdir(parents=True)
    catalog.write_text(
        json.dumps(_pipeline_catalog([ROW, {**ROW, "name": "Unsharded", "splits": 1}]))
    )
    stages = parse_stages_from_groovy(entry)
    assert list(stages) == ["A10-PyTorch-1", "A10-PyTorch-2", "A10-PyTorch-3", "Unsharded"]
    assert [(s.split_id, s.total_splits) for s in stages.values()] == [
        (1, 3),
        (2, 3),
        (3, 3),
        (1, 1),
    ]
    assert all(s.cpu_arch == "x86" and s.yaml_stem == "l0_a10" for s in stages.values())


@pytest.mark.parametrize(
    "runner,options",
    [
        ("kubernetes", {"modelExpress": True}),
        ("slurmAgent", {"useClusterDurations": True}),
        ("sbatch", {"runWithSbatch": True}),
    ],
)
def test_runner_options_survive_expansion(runner, options):
    records = expand_catalog(_catalog([{**ROW, "options": options}], runner))
    assert len(records) == 3
    assert all(r["runner"] == runner and r["options"] == options for r in records)


@pytest.mark.parametrize(
    "change",
    [
        {"splits": 0},
        {"splits": -1},
        {"splits": 1.5},
        {"splits": True},
        {"name": "MissingShard"},
        {"gpuCount": 0},
        {"options": {"typo": True}},
        {"options": {"modelExpress": "true"}},
        {"options": {"runWithSbatch": True}},
        {"options": None},
        {"options": False},
        {"options": []},
        {"runner": None},
        {"runner": ""},
    ],
)
def test_invalid_catalog_is_rejected_before_discovery(change):
    with pytest.raises(ValueError):
        expand_catalog(_catalog([{**ROW, **change}]))


def test_duplicate_stage_names_are_rejected_even_when_hidden():
    with pytest.raises(ValueError, match="Duplicate stage name"):
        expand_catalog(_catalog([ROW, {**ROW, "discoverable": False}]))


@pytest.mark.parametrize("version", [True, 1.0])
def test_version_requires_an_integer(version):
    with pytest.raises(ValueError, match="version 1"):
        expand_catalog({"version": version, "groups": []})


@pytest.mark.parametrize(
    "change", ["unknown", "missing", "arch", "default_runner", "row_runner", "sbsa_model_express"]
)
def test_pipeline_routes_reject_unimplemented_catalog_combinations(tmp_path, change):
    catalog = _pipeline_catalog([ROW])
    if change == "unknown":
        catalog["groups"][0]["name"] = "unknown"
    elif change == "missing":
        catalog["groups"].pop()
    elif change == "arch":
        catalog["groups"][0]["arch"] = "sbsa"
    elif change == "default_runner":
        catalog["groups"][0].update(runner="sbatch", stages=[])
    elif change == "row_runner":
        catalog["groups"][0]["stages"] = [{**ROW, "runner": "sbatch"}]
    else:
        catalog = _pipeline_catalog([{**ROW, "options": {"modelExpress": True}}], "SBSATestConfigs")
    with pytest.raises(ValueError):
        expand_for_pipeline(catalog)
    path = tmp_path / "jenkins/pipelines/test/stages.json"
    path.parent.mkdir(parents=True)
    path.write_text(json.dumps(catalog))
    with pytest.raises(ValueError):
        load_stage_records(path)


def test_standalone_catalog_stays_generic(tmp_path):
    path = tmp_path / "custom.json"
    path.write_text(json.dumps(_catalog([ROW])))
    assert len(load_stage_records(path)) == 3


def test_sbsa_kubernetes_gpu_count_is_supported():
    records = expand_for_pipeline(_pipeline_catalog([{**ROW, "gpuCount": 2}], "SBSATestConfigs"))
    assert len(records) == 3
    assert all(record["arch"] == "sbsa" and record["gpuCount"] == 2 for record in records)
