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
"""Expand the named stage families shared by Jenkins and stage-discovery tools."""

import json
from pathlib import Path


def stage_config_path(source: Path) -> Path:
    """Resolve known Pipeline paths while keeping standalone legacy sources usable."""
    if source.name == "L0_Test.groovy":
        folder = source.parent / "pipelines/test"
        for name in ("stages.json", "Stages.groovy"):
            if (folder / name).is_file():
                return folder / name
    elif source.name == "Stages.groovy" and source.parent.name == "test":
        catalog = source.with_name("stages.json")
        if catalog.is_file():
            return catalog
    return source


def _positive_integer(value: object, description: str) -> None:
    if type(value) is not int or value < 1:
        raise ValueError(f"{description} must be a positive integer")


def expand_catalog(catalog: dict, discoverable_only: bool = True) -> list[dict]:
    """Expand each family once per shard, preserving group and declaration order."""
    if (
        type(catalog.get("version")) is not int
        or catalog["version"] != 1
        or not isinstance(catalog.get("groups"), list)
    ):
        raise ValueError("Expected stage catalog version 1 and groups")
    records = []
    groups = set()
    names = set()
    for group in catalog["groups"]:
        if (
            not isinstance(group, dict)
            or not isinstance(group.get("name"), str)
            or not group["name"]
        ):
            raise ValueError("Every stage group needs a name")
        if group["name"] in groups:
            raise ValueError(f"Duplicate stage group {group['name']}")
        groups.add(group["name"])
        if group.get("arch") not in ("x86", "sbsa") or not isinstance(group.get("stages"), list):
            raise ValueError(f"Invalid architecture or stages in {group['name']}")
        for row in group["stages"]:
            if not isinstance(row, dict):
                raise ValueError(f"Expected a stage definition in {group['name']}")
            for key in ("name", "platform", "testList"):
                if not isinstance(row.get(key), str) or not row[key]:
                    raise ValueError(f"Missing {key} in {group['name']}")
            _positive_integer(row.get("splits"), f"splits for {row['name']}")
            if row["name"].count("{shard}") > 1 or (
                row["splits"] > 1 and "{shard}" not in row["name"]
            ):
                raise ValueError(
                    f"Stage {row['name']} needs one {{shard}} placeholder when splits > 1"
                )
            for key in ("gpuCount", "nodeCount"):
                if key in row:
                    _positive_integer(row[key], f"{key} for {row['name']}")
            if "discoverable" in row and not isinstance(row["discoverable"], bool):
                raise ValueError(f"discoverable must be boolean for {row['name']}")
            runner = row.get("runner", group.get("runner"))
            if runner not in ("kubernetes", "slurmAgent", "sbatch"):
                raise ValueError(f"Invalid runner for {row['name']}")
            options = row.get("options", {})
            if not isinstance(options, dict) or any(
                key not in ("modelExpress", "runWithSbatch", "useClusterDurations")
                or not isinstance(value, bool)
                for key, value in options.items()
            ):
                raise ValueError(f"Invalid options for {row['name']}")
            if runner == "kubernetes":
                if "nodeCount" in row or options.keys() & {"runWithSbatch", "useClusterDurations"}:
                    raise ValueError(f"Slurm options on Kubernetes stage {row['name']}")
            else:
                if "modelExpress" in options:
                    raise ValueError(f"ModelExpress requires Kubernetes for {row['name']}")
                sbatch = options.get(
                    "runWithSbatch", runner == "sbatch" and row.get("nodeCount", 1) == 1
                )
                if (runner == "sbatch") != (row.get("nodeCount", 1) > 1 or sbatch):
                    raise ValueError(f"Runner and Slurm topology disagree for {row['name']}")
            for shard in range(1, row["splits"] + 1):
                name = row["name"].replace("{shard}", str(shard))
                if name in names:
                    raise ValueError(f"Duplicate stage name {name}")
                names.add(name)
                if discoverable_only and not row.get("discoverable", True):
                    continue
                records.append(
                    {**row, "name": name, "splitId": shard, "runner": runner, "arch": group["arch"]}
                )
    return records


def load_stage_records(source: Path) -> list[dict] | None:
    """Return catalog records, or None when the source uses legacy Groovy syntax."""
    path = stage_config_path(source)
    if path.suffix != ".json":
        return None
    catalog = json.loads(path.read_text())
    if (
        path.name == "stages.json"
        and path.parent.name == "test"
        and path.parent.parent.name == "pipelines"
    ):
        return expand_for_pipeline(catalog)
    return expand_catalog(catalog)


def expand_for_pipeline(catalog: dict, discoverable_only: bool = True) -> list[dict]:
    """Validate the planner's fixed execution routes before exposing its stages."""
    records = expand_catalog(catalog, discoverable_only)
    routes = {
        "x86TestConfigs": ("x86", {"kubernetes"}),
        "x86SlurmTestConfigs": ("x86", {"slurmAgent", "sbatch"}),
        "SBSATestConfigs": ("sbsa", {"kubernetes"}),
        "SBSASlurmTestConfigs": ("sbsa", {"slurmAgent", "sbatch"}),
        "multiNodesSBSAConfigs": ("sbsa", {"slurmAgent", "sbatch"}),
    }
    if {group["name"] for group in catalog["groups"]} != routes.keys():
        raise ValueError(f"Pipeline stage groups must be exactly {list(routes)}")
    for group in catalog["groups"]:
        arch, runners = routes[group["name"]]
        if group["arch"] != arch:
            raise ValueError(f"Wrong architecture for {group['name']}")
        if group.get("runner") not in runners:
            raise ValueError(f"Incompatible default runner for {group['name']}")
        for row in group["stages"]:
            if row.get("runner", group["runner"]) not in runners:
                raise ValueError(f"Incompatible runner for {row['name']} in {group['name']}")
            if arch == "sbsa" and row.get("options", {}).get("modelExpress", False):
                raise ValueError(f"ModelExpress currently requires x86 for {row['name']}")
    return records
