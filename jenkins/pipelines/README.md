<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# L0 pipeline modules

`L0_MergeRequest.groovy` and `L0_Test.groovy` remain the Declarative Pipeline
entry points. They own job configuration, initial agent creation and top-level
stage ordering. Merge-request result collection also stays in the entry so its
`post { always { ... } }` handler works when preparation fails before module
loading. Each entry and functional Groovy module must stay within 1000
lines. Split by responsibility when a module grows; a single CPS-transformed
method can reach the JVM bytecode limit before its file reaches that line limit.

| Directory | Responsibilities |
| --- | --- |
| `common/` | Artifact paths and stage-pattern helpers shared by the pipelines |
| `mr/` | Source changes, test selection, CBTS, preparation helpers, child jobs and result collection |
| `test/` | Test stages, test-db rendering, execution, artifacts, reporting, infrastructure retries and Slurm lifecycle |
| `tests/` | Offline Groovy regression tests for generated shell scripts |

## Loading and context

The merge-request entry loads modules after its existing source checkout has
resolved `env.gitlabCommit`. The test entry uses a sparse checkout of
`jenkins/pipelines` at that same supplied commit, before dispatching test work.
Neither entry loads modules from a moving library branch or needs a new Jenkins
shared-library registration. Initial agent creation stays in the entries because
`load` requires an allocated workspace.

Modules return their script instance and accept the entry script through
`initialize(pipeline)`. Calls to Jenkins steps and shared configuration use this
explicit context. Calls across functional boundaries name the module, for
example `pipeline.testDb.renderTestDB(...)`; calls within a module stay local.
Keep temporary variables local unless they intentionally belong to the original
pipeline binding. Do not introduce `methodMissing`, a routing registry or
concatenated source evaluation to hide dependencies.

`test/State.groovy` owns the single original `GlobalState` class. The entry loads
that Class as `ciState`; reporting, estimates, maintenance and port allocation
continue to share its static collections. This preserves the existing static
state semantics. Changing those collections to durable per-run state is a
separate behavior change.

## Scripts and classes

Use loaded scripts for project-specific Pipeline orchestration. Use a class when
it represents a data model or needs a stable object interface. A class retained
across Pipeline suspension must be serializable and receive the Pipeline steps
explicitly. Constructors and `@NonCPS` methods must not invoke Pipeline steps.
The existing shared libraries continue to own general infrastructure services.

Keep shell generators in `test/SbatchScripts.groovy` independent of Jenkins;
pass the required values explicitly. Preserve generated shell quoting and
whitespace when moving code. Run its regression test from the repository root
with Groovy 2.4, or with a compatible Groovy runtime:

```sh
groovy jenkins/pipelines/tests/SbatchScriptsTest.groovy
```

The stage-mapping CLI and CBTS reader accept the historical `L0_Test.groovy`
path and resolve the stage definitions in this directory. Keep those consumers
and their tests in sync when moving stage catalogs. Offline compilation and
mocked checks do not establish controller approvals, plugin compatibility or a
successful end-to-end Jenkins execution.
