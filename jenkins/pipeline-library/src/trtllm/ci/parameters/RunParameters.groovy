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

package trtllm.ci.parameters

class RunParameters implements Serializable {
    final String REUSE_TEST = "reuse_test"
    final String REUSE_STAGE_LIST = "reuse_stage_list"
    final String ENABLE_SKIP_TEST = "skip_test"
    final String TEST_STAGE_LIST = "stage_list"
    final String GPU_TYPE_LIST = "gpu_type"
    final String TEST_BACKEND = "test_backend"
    final String IS_POST_MERGE = "post_merge"
    final String ADD_MULTI_GPU_TEST = "add_multi_gpu_test"
    final String ENABLE_MULTI_GPU_TEST = "multi_gpu_test"
    final String ONLY_MULTI_GPU_TEST = "only_multi_gpu_test"
    final String DISABLE_MULTI_GPU_TEST = "disable_multi_gpu_test"
    final String EXTRA_STAGE_LIST = "extra_stage"
    final String MULTI_GPU_FILE_CHANGED = "multi_gpu_file_changed"
    final String ONLY_ONE_GROUP_CHANGED = "only_one_group_changed"
    final String AUTO_TRIGGER_TAG_LIST = "auto_trigger_tag_list"
    final String DEBUG_MODE = "debug"
    final String DETAILED_LOG = "detailed_log"
    final String CBTS_RESULT = "cbts_result"

    // Pipeline-level CBTS coverage eligibility, decided in L0_MergeRequest.groovy.
    final String CBTS_COVERAGE = "cbts_coverage"
    final String DISABLE_CBTS = "disable_cbts"
    final String INFRA_DRY_RUN = "infra_dry_run"
    final String OSS_COMPLIANCE_FILE_CHANGED = "oss_compliance_file_changed"

    // `/bot run` key that opts a single run into BOLT consume.
    final String BOLT_CONSUME = "bolt_consume"
    final String GITHUB_PR_API_URL = "github_pr_api_url"

    // Used to read the git changes when there is no PR or MR to read the changes from (i.e., when trigger from merge queue):
    // the changes are base_commit..gitlabCommit, read from `git diff` in the internal Git mirror.
    final String BASE_COMMIT = "base_commit"
    final String CACHED_CHANGED_FILE_LIST = "cached_changed_file_list"
    final String ACTION_INFO = "action_info"
    final String IMAGE_KEY_TO_TAG = "image_key_to_tag"
    final String TARGET_BRANCH = "target_branch"
    final String TRTLLM_VERSION_OVERRIDE = "trtllm_version_override"
    final String RUN_MODE = "run_mode"
    final String BUILD_BRANCH = "build_branch"
    final String MAINTENANCE_ENTRIES = "maintenance_entries"

    // Whether this pipeline asked for BOLT-optimized binaries. Resolved once by
    // L0_MergeRequest.groovy::resolveBoltConsume and propagated here in globalVars,
    // so the release wheel below follows the same switch as the build tarball
    // instead of inventing a second answer to "is BOLT on".
    final String BOLT_CONSUME_BUILD = "bolt_consume_build"

    // The one BOLT profile bundle every consumer in this pipeline applies. Resolved
    // once (see resolveBoltProfileRef) rather than each consumer reading the mutable
    // `latest` pointer whenever it happens to run -- which made two stages in the
    // same pipeline able to disagree about which profiles they used. Empty means
    // unpinned, i.e. today's read-`latest`-per-consumer behaviour.
    final String BOLT_PROFILE_REF = "bolt_profile_ref"

    // Whether this run's build publishes bolted-<tarName> beside an untouched
    // canonical. Decided once: the build needs it to know what to publish, and the
    // test stages need it to know what to fetch. Two copies of the condition would
    // eventually disagree, and the failure would be tests silently reading the
    // un-BOLTed tarball -- indistinguishable from a healthy run.
    final String BOLT_PUBLISH_VARIANT = "bolt_publish_variant"

    // The branch whose promote directory holds that bundle. A ref alone does not
    // address an object -- the path is <branch>/<triple>/bolt-profile-<ref>-<triple>
    // -- and consumers used to supply the branch themselves from four different
    // expressions. They agree on post-merge main and nowhere guaranteed else, and a
    // disagreement is a 404, which apply_latest.sh reports as "nothing promoted":
    // indistinguishable from a cold start, so it degrades silently. Carrying the
    // branch with the ref means a pinned consumer never has to guess.
    final String BOLT_PROFILE_BRANCH = "bolt_profile_branch"

    // The triples BOLT consume applies to. Stated explicitly rather than inferred
    // from the pin: "which bundle" and "which architectures consume BOLT" are
    // independent questions, and answering the second from the first made the
    // x86_64 path behave differently depending on whether the pin happened to
    // resolve -- an unresolvable pin is non-fatal by design, so x86_64 would then
    // go on to stage llvm-bolt and request a bundle that does not exist before
    // giving up. Both consumers are launched for both architectures off this one
    // map, so the scope has to travel with it.
    //
    // Adding a triple here also needs a per-triple pin: BOLT_PROFILE_REF names one
    // bundle under one promote directory, so a second architecture reading it would
    // demand aarch64's bundle. Change the two together.
    final String BOLT_CONSUME_TRIPLES = "bolt_consume_triples"
    final String RELEASE_TARGET = "release_target"
    final String JOB_TYPE = "job_type"

    // The primary location of build artifacts (see llmArtifactsPrimary), passed to every child job.
    // Child jobs run on other Jenkins controllers, whose own LLM_ARTIFACTS_PRIMARY can differ.
    final String LLM_ARTIFACTS_PRIMARY_KEY = "llm_artifacts_primary"

    // Release scripts repository (nspect.py, kitmaker_wheel.py) as [url: ..., commit: ...],
    // resolved once in setupPipelineEnvironment from the TRTLLM_RELEASE_REPO credential and
    // jenkins/ci_versions.properties.
    final String TRTLLM_RELEASE_REPO = "TRTLLM_RELEASE_REPO"

    Map testFilterDefaults()
    {
        return [
            (REUSE_TEST): null,
            (REUSE_STAGE_LIST): null,
            (ENABLE_SKIP_TEST): false,
            (TEST_STAGE_LIST): null,
            (GPU_TYPE_LIST): null,
            (TEST_BACKEND): null,
            (IS_POST_MERGE): false,
            (ADD_MULTI_GPU_TEST): false,
            (ONLY_MULTI_GPU_TEST): false,
            (DISABLE_MULTI_GPU_TEST): false,
            (EXTRA_STAGE_LIST): null,
            (MULTI_GPU_FILE_CHANGED): false,
            (ONLY_ONE_GROUP_CHANGED): "",
            (DEBUG_MODE): false,
            (AUTO_TRIGGER_TAG_LIST): [],
            (DETAILED_LOG): false,
            (CBTS_RESULT): null,
            (CBTS_COVERAGE): false,
            (INFRA_DRY_RUN): false,
        ]
    }

    Map parentTestFilter(Map bot, boolean postMerge, boolean infraDryRun)
    {
        def filter = testFilterDefaults()
        [REUSE_TEST, ENABLE_SKIP_TEST, ADD_MULTI_GPU_TEST, DISABLE_MULTI_GPU_TEST,
         DEBUG_MODE, DETAILED_LOG].each { key ->
            filter[key] = bot.get(key, filter[key])
        }
        [REUSE_STAGE_LIST, TEST_STAGE_LIST, GPU_TYPE_LIST, TEST_BACKEND, EXTRA_STAGE_LIST].each { key ->
            def stages = bot.get(key, null)?.tokenize(',')
            filter[key] = stages == null ? null : stages.collect { it.trim().replaceAll('\\\\', '') }
        }
        filter[IS_POST_MERGE] = postMerge || bot.get(IS_POST_MERGE, false)
        filter[ONLY_MULTI_GPU_TEST] = bot.get(ONLY_MULTI_GPU_TEST, false) || bot.get(ENABLE_MULTI_GPU_TEST, false)
        filter.remove(INFRA_DRY_RUN)
        filter[DISABLE_CBTS] = bot.get(DISABLE_CBTS, false)
        filter[INFRA_DRY_RUN] = infraDryRun
        return filter
    }
}
