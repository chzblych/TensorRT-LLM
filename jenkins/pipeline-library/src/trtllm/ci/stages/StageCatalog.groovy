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

package trtllm.ci.stages

/** Expands named stage families into the execution tuples consumed by runners. */
class StageCatalog implements Serializable {
    static Map expandForPipeline(Map catalog) {
        def expanded = expand(catalog)
        def routes = [
            x86TestConfigs: [arch: 'x86', runners: ['kubernetes']],
            x86SlurmTestConfigs: [arch: 'x86', runners: ['slurmAgent', 'sbatch']],
            SBSATestConfigs: [arch: 'sbsa', runners: ['kubernetes']],
            SBSASlurmTestConfigs: [arch: 'sbsa', runners: ['slurmAgent', 'sbatch']],
            multiNodesSBSAConfigs: [arch: 'sbsa', runners: ['slurmAgent', 'sbatch']],
        ]
        require(expanded.keySet() == routes.keySet(),
            "Pipeline stage groups must be exactly ${routes.keySet()}")
        catalog.groups.each { group ->
            def route = routes[group.name]
            require(group.arch == route.arch, "Wrong architecture for ${group.name}")
            require(group.runner in route.runners, "Incompatible default runner for ${group.name}")
            group.stages.each { row ->
                def runner = row.containsKey('runner') ? row.runner : group.runner
                require(runner in route.runners, "Incompatible runner for ${row.name} in ${group.name}")
                require(!(group.arch == 'sbsa' && row.options?.modelExpress == true),
                    "ModelExpress currently requires x86 for ${row.name}")
            }
        }
        return expanded
    }

    static Map expand(Map catalog) {
        require((catalog.version instanceof Integer || catalog.version instanceof Long ||
            catalog.version instanceof BigInteger) && catalog.version == 1 && catalog.groups instanceof List,
            'Expected stage catalog version 1 and groups')
        def groups = [:]
        def names = [] as Set
        catalog.groups.each { group ->
            require(group instanceof Map && group.name instanceof String && group.name,
                'Every stage group needs a name')
            require(!groups.containsKey(group.name), "Duplicate stage group ${group.name}")
            require(group.arch in ['x86', 'sbsa'], "Invalid architecture in ${group.name}")
            require(group.stages instanceof List, "Missing stages in ${group.name}")
            def entries = [:]
            group.stages.each { row ->
                require(row instanceof Map, "Expected a stage definition in ${group.name}")
                ['name', 'platform', 'testList'].each { key ->
                    require(row[key] instanceof String && row[key], "Missing ${key} in ${group.name}")
                }
                positiveInteger(row.splits, "splits for ${row.name}")
                require(row.name.count('{shard}') <= 1 && (row.splits == 1 || row.name.contains('{shard}')),
                    "Stage ${row.name} needs one {shard} placeholder when splits > 1")
                ['gpuCount', 'nodeCount'].each { key ->
                    if (row.containsKey(key)) positiveInteger(row[key], "${key} for ${row.name}")
                }
                require(!row.containsKey('discoverable') || row.discoverable instanceof Boolean,
                    "discoverable must be boolean for ${row.name}")
                def runner = row.containsKey('runner') ? row.runner : group.runner
                require(runner in ['kubernetes', 'slurmAgent', 'sbatch'], "Invalid runner for ${row.name}")
                def options = row.containsKey('options') ? row.options : [:]
                require(options instanceof Map, "Invalid options for ${row.name}")
                options.each { key, value ->
                    require(key in ['modelExpress', 'runWithSbatch', 'useClusterDurations'] && value instanceof Boolean,
                        "Invalid option ${key} for ${row.name}")
                }
                if (runner == 'kubernetes') {
                    require(!row.containsKey('nodeCount') && !options.containsKey('runWithSbatch') &&
                        !options.containsKey('useClusterDurations'), "Slurm options on Kubernetes stage ${row.name}")
                } else {
                    require(!options.containsKey('modelExpress'), "ModelExpress requires Kubernetes for ${row.name}")
                    boolean sbatch = options.containsKey('runWithSbatch') ? options.runWithSbatch :
                        (runner == 'sbatch' && (row.nodeCount ?: 1) == 1)
                    require((runner == 'sbatch') == ((row.nodeCount ?: 1) > 1 || sbatch),
                        "Runner and Slurm topology disagree for ${row.name}")
                }
                for (int shard = 1; shard <= row.splits; shard++) {
                    String name = row.name.replace('{shard}', shard.toString())
                    require(names.add(name), "Duplicate stage name ${name}")
                    entries[name] = tuple(row, runner, options, shard)
                }
            }
            groups[group.name] = entries
        }
        return groups
    }

    private static List tuple(Map row, String runner, Map options, int shard) {
        def values = [row.platform, row.testList, shard, row.splits]
        boolean sbatchDefault = runner == 'sbatch' && (row.nodeCount ?: 1) == 1
        boolean hasSlurmFlags = options.containsKey('runWithSbatch') ||
            options.containsKey('useClusterDurations') || sbatchDefault
        if (row.containsKey('gpuCount') || row.containsKey('nodeCount') ||
            options.containsKey('modelExpress') || hasSlurmFlags) values << (row.gpuCount ?: 1)
        if (runner == 'kubernetes') {
            if (options.containsKey('modelExpress')) values << options.modelExpress
        } else {
            if (row.containsKey('nodeCount') || hasSlurmFlags) values << (row.nodeCount ?: 1)
            if (hasSlurmFlags) values << (options.containsKey('runWithSbatch') ? options.runWithSbatch : sbatchDefault)
            if (options.containsKey('useClusterDurations')) values << options.useClusterDurations
        }
        return values
    }

    private static void positiveInteger(value, String description) {
        require((value instanceof Integer || value instanceof Long || value instanceof BigInteger) && value > 0,
            "${description} must be a positive integer")
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message)
    }
}
