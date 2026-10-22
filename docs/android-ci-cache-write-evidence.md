# Android CI cache-write evidence — P2 read-only gate

## Why

Historically post-main Android check on protected `main` averaged ~4m45s, with ~37s spent in `setup-gradle` post-cache state maintenance across a sampled set of runs. Builds that modify Kotlin/KSP execute more work than configuration-only infrastructure PRs. Cache saves are nontrivial but removing them without evidence may make the next required post-main build much slower. Protect the *next* integration, not just the current job.

Measured source examples:

- Required Android post-main #1185 (job 119321552697): Gradle 3m54s, 73 actionable: 40 executed, 33 FROM-CACHE. Post-action group `Caching Gradle state` ~24s, with cache transforms archive ~677MB and gradle-home archive ~114MB. The timeline is evidence of writing, but not of how many outputs were reused by later runs.
- Required CI PR #148 (job 119532719504): Gradle 53s, 73 actionable: 34 executed, 39 FROM-CACHE; post action explicitly says cache read-only. The difference in tasks is critical: no valid claim that making all main writes read-only would save only these seconds.
- P0 optional profile #148 had a different cache key family and took 4m22s with 65 executed and 8 FROM-CACHE. These are not a controlled A/B test of caching.

The project already has `tools/delivery_ci_observations.py` for allowlisted Android job/run timestamps and `tools/delivery_metrics.py` for queue/execution medians. This tool complements those without duplicating that API reader: `tools/delivery_ci_cache_policy.py` summarizes a **locally supplied, complete GitHub Actions Android job log**, never accesses GitHub itself and never exports raw lines.

## Usage

1. Download a selected `Android · test and build` job's log using the owner's GitHub permissions. Do not publish raw logs; they may contain filenames or other private context.
2. Run `python3 -m tools.delivery_ci_cache_policy --input <local-job-log> --sha <exact-40-char-sha> --lane main` (or `pr`).
3. Output contains bounded task categories, exact SHA, Gradle duration, `executed`/`from_cache` task counts, and a cache-write phase duration only when the `Caching Gradle state` group opens and closes. If `Cache is read-only` is explicitly present, duration is zero; if no evidence exists, duration is null and policy `unobserved`. Cache keys, paths, tokens, freeform strings and unparsed user content are discarded.
4. Pair these results with timestamps from DI5 to distinguish queue waits and execution, and compare subsequent main-push runs with stable Gradle input/toolchain/workloads. Task counts/phase timings alone do not establish next-run cache benefit.

## Decision rubric

Keep current cache-write policy until a representative sample shows that selected main writes can be skipped with *net* savings across the next 2–3 required Android builds **without** creating cold cache misses. Measure >=3 comparable main-push sequences and record cache restore family/hits, output cache hits, post-save phase time, runner queue, failed runs and total cycle. Use a separate reversible, isolated branch if a policy experiment is justified. Never suspend a required check or weaken the exact-SHA post-main gate to buy speed.

The parser is static/read-only and intentionally does not add any required CI job, schedule, remote reporting, token, secrets, workflow permissions or cache policy switch. Its `tests/test_delivery_ci_cache_policy.py` suite is selected by the existing `Integration Queue Tests` workflow's `tests/test_delivery_*.py` path glob; this PR does not edit the workflow. Rollback: remove the parser, tests and docs.

## Controls

- Only explicit, complete logs accepted; malformed task count, unfinished cache-write phase, missing success or invalid SHA fail closed.
- User-supplied job mode does not imply caching behavior: the log itself must prove read-only, written, or unknown.
- Parser never exports unbounded strings/cache identifiers.
- Do not upload the original raw logs to GitHub artifacts or Notion, and do not persist secrets.

## Additional step-phase breakdown (P2b; no extra CI workflow)

The same draft now also includes `tools/delivery_ci_phase_metrics.py` and `tests/test_delivery_ci_phase_metrics.py`. The script reuses the existing GET-only first-party `GitHubCIReader` and queries **only** completed/in-progress required `Android · test and build` jobs. No additional Android build, GitHub Actions workflow, token permission, API writer, artifact, or telemetry upload is introduced.

Use `python3 -m tools.delivery_ci_phase_metrics --repo jssantogit/BitChord --limit 25` with an ordinary GitHub read token if rate limits require it. The output is a bounded `version:1` report sorted by run ID. Each observation contains only the exact run SHA, a fixed lane label, finite/nullable second counts for GitHub job queue wait, total job, `Set up Gradle`, `Run Android unit tests`, `Post Set up Gradle`, and the difference between the job duration and Android-test **step** duration. Unknown/missing steps are null, never zero by assumption. Duplicate/malformed timestamps, unexpected repositories, inconsistent successes, impossible durations and mixed paths fail closed. Raw ref names, user IDs, logs, cache keys, environment variables and error messages never enter the output.

Summaries provide medians by existing lanes (including future `pr_d` for the separate draft #152 proposal) and suppress p90 unless at least three queue-time observations exist. The `non_test_step_seconds` label describes the wallclock outside the unit-test *step*, not wasted or safely removable time; it includes necessary toolchain setup and post-run work. Likewise an observed queue delay is **not** proof that CI concurrency caused it. Use these metrics next to Gradle task-level profiles and cache-write evidence to decide whether the real bottleneck is compiler work, setup, cache persistence or scheduler pressure.

Integration Queue contract tests and the existing required Android workflow must remain GREEN on the final exact SHA. This is instrumentation only; policies are unchanged, and no production speedup is claimed.

## Revision-attested workload strata (P2c; no CI side effects)

`tools/delivery_ci_workload.py` and `tests/test_delivery_ci_workload.py` are also included in this optional observability PR to avoid treating a documentation-only CI as equivalent to a Kotlin/KSP application change.

Read the current PR diff metadata using the read-only first-party GitHub endpoint and require the **actual, exact PR HEAD SHA**:

```bash
python3 -m tools.delivery_ci_workload \
  --repo jssantogit/BitChord \
  --pr 141 \
  --head-sha <exact-head-sha>
```

This tool fetches the PR header before and after the files page, refusing a changed/stale HEAD, a mismatched repository, an incomplete pagination result of 100 files, invalid filenames and unknown file statuses. Its output includes only a stable enum, fixed category counts, `changed_files`, PR number and the attested HEAD. **It does not print changed file paths, diff patches, branch names, users or tokens.**

Enums: `kotlin_production`, `kotlin_tests`, `gradle_configuration`, `ci_workflows`, `ci_scripts`, `documentation`, `mixed`, `other`. Changed paths are classified locally without sharing their values; cross-category renames count both affected kinds while counting the changed file once. This makes it possible to compare comparable CI samples, but there is a crucial temporal limitation:

- **Current PR files describe the current PR HEAD**, not necessarily the historic source revision represented by an old workflow run (GitHub PR CI can test a synthetic merge SHA). Pair metrics only when both the PR state/HEAD and exact run provenance are verified. Otherwise classify the run's workload as UNKNOWN and do not silently backfill it.
- A PR with 100 or more changed files cannot be proven complete by one `per_page=100` GET and is rejected. This is intentional fail-closed behavior; an owner-approved pagination-aware extension is needed for large changes.
- The workload class indicates file categories, not causal Gradle cache invalidation. Kotlin/KSP timings, exact Gradle cache hit counts, runner availability and main-vs-PR cache families still need to be observed, without merging unrelated distributions.

The workload tool is **manual read-only diagnostics**; it adds no remote write, GitHub workflow, required check, token permissions, cache operation, upload or user-facing telemetry. Each pilot must remain draft until exact-head CI passes and owner separately approves any integration.


## P2d: cache-restore workflow-family attribution (2026-10-22)

`tools/delivery_ci_cache_policy.py` now also classifies **only the workflow-family segment** of syntactically verified `setup-gradle` `Cache hit for restore-key` messages. It emits `cache_restore_status: hit|unobserved` and `cache_restore_workflow_family: android_workflow|build_smoke_apk_workflow|other_workflow|null`. Unknown/malformed hits remain **unobserved**; conflicting workflow-family records fail closed. Full cache keys, hash fragments, branch names, paths, usernames and raw source lines are never returned. `tests/test_delivery_ci_cache_policy.py` provides TDD coverage including key-leak prevention.

**Observed topology, not inferred identity:**
- Required Android CI #39754833863, job 119744102559, restored a Gradle-home cache key with `android` workflow family, then finished `BUILD SUCCESSFUL in 50s`, 73 actionable tasks: 34 executed, 39 from cache. Kotlin main and KSP main were `FROM-CACHE`.
- Optional compiler-strategy pilot #39754833997, daemon job 119744102499, restored a Gradle-home cache key with `build-smoke-apk` workflow family, then initial build finished 3m37s, 65 executed / 8 from-cache. In-process job had the same 65/8 initial task counts, took 4m13s, and was slower after a comment-only change (35s vs 27s).
- Historical post-main mandatory Android #1185, job 119321552697, restored an `android` family Gradle-home key but had to execute Kotlin main, KSP main and tests for source changes (3m54s, 40 executed / 33 from-cache).

A restore-key workflow-family label is **not** proof of the actual producer branch, a verified build output, or whether a particular task cache entry was reused. Different task inputs/commits/toolchains still matter. There is no valid comparison of 50s versus 3m37s without matching source revision, action cache state and job context.

**Cross-branch trust boundary:** GitHub Actions dependency caches are scoped to their branch/merge-ref. Cache artifacts written from a PR merge-ref cannot simply be restored by the protected `main` push after a merge. Default-branch cache can be restored by PR builds, while unrestricted promotion of PR-produced build outputs into main creates cache-poisoning risk. See [GitHub cache access restrictions](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching) and [Gradle setup-gradle cache behavior](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md).

**Decision:** keep main's safe cache writes and required full suite unchanged. Do not turn on `cache-mode: write` for low-trust PR triggers, trust PR artifacts blindly, leak cache keys, reuse configuration-cache secrets without encryption, or skip main checks. A cross-job compilation-output cache needs its **own** proposed trusted architecture: exact source tree and toolchain attestation, controlled writer policy, restore verification, security review, and comparable *whole merge-sequence* results before approval. This PR only improves diagnostics and makes no workflow change.


## P2e: conservative trusted-main cache lineage, not a new cache writer

The package now includes `tools/delivery_ci_cache_lineage.py` with regression tests `tests/test_delivery_ci_cache_lineage.py`. It compares **two independently parsed, main-lane required Android job logs** as structured `summarize_cache_job(..., lane="main")` results. It is deliberately a *measurement* and not an attestation or an authorization to restore external artifacts.

Example with locally supplied complete, private logs (never upload or commit the logs):

```python
from tools.delivery_ci_cache_policy import summarize_cache_job
from tools.delivery_ci_cache_lineage import compare_main_cache_evidence

earlier = summarize_cache_job(open("private-earlier.log").read(), EARLIER_EXACT_SHA, "main")
later = summarize_cache_job(open("private-later.log").read(), LATER_EXACT_SHA, "main")
result = compare_main_cache_evidence(earlier, later)
# Return only allowlisted fixed labels and numeric deltas, not the input logs.
```

A comparison is `observations_only` only when the source snapshots both declare the `main` lane, have distinct syntactically valid SHAs, contain complete fixed-class compiler task evidence, explicitly observed `written` cache policy, and separately observed restored `android_workflow` family. On PR/main mixes, different restore families or unknown cache outcomes it returns `incomparable`, with nullable deltas. When task details are incomplete it returns `incomplete_task_evidence`. Invalid provenance types, counts, nonsensical values or contradictory task states fail closed. No underlying branch, actor, user path, cache key or SHA is emitted by the pair report.

The numeric deltas (`from_cache_task_delta`, `executed_task_delta`, `gradle_wall_seconds_delta`) are **descriptive**, not a benefit estimate. Every result explicitly sets `cross_revision_output_reuse_proven: false`, `speedup_attributable_to_cache: false`, and `safe_to_promote_pr_cache: false`. The source logs supplied by an operator still require GitHub first-party verification of their run/job/commit provenance. Cache-key family alone proves neither producer branch nor reuse of a specific Kotlin/KSP output.

### Safe architecture for any later speedup experiment

1. **Keep** `gradle/actions/setup-gradle@v4`'s default read/write behavior: protected `push` to `main` may save Gradle user-home output cache after required tests; low-trust PR events read default-branch caches only. Do not grant PRs `cache-mode: write`, or make privileged `pull_request_target` / `workflow_run` cache writers. GitHub introduced `cache-mode` in September 2026 as an explicit least-privilege access control; any future policy must retain per-event access restrictions. [GitHub cache-mode announcement](https://github.blog/changelog/2026-09-10-control-github-actions-cache-access-with-cache-mode/) and [cache restrictions](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching).
2. Separate three distinct proofs: **workflow origin** verified through first-party run/job metadata; **compiler/toolchain/config identity** (wrapper, JDK, Kotlin, KSP, AGP and Gradle input fingerprints) recorded from the checked-out revision; and **task output suitability** determined by Gradle's input-sensitive build cache, not by matching a filename or trusting a PR archive.
3. New commits on `main` legitimately invalidate Kotlin/KSP task outputs when their inputs change. A cold compilation in that case is not a cache failure; it must not be skipped. Existing required Android tests must always execute their normal task graph on the exact post-merge SHA.
4. A remote write-through cache, trusted pre-merge artifact promotion, or cross-job Configuration Cache persistence is **out of scope** for this read-only PR. Such systems require separate security architecture, authentication isolation, content verification, controlled producer authorization, explicit owner approval and reversible rollout. Configuration Cache may serialize secrets and must not be persisted without encryption.
5. Promotion metrics: sample at least **three comparable sequences** of protected post-main Android runs with source-change/workload class, exact job SHA, task-level cache reuse, write/save overhead and whole queue time; check that gains persist after the next merge and that failure/OOM/privacy behavior does not regress. Quantify the **net** savings, never the isolated restore/save phase alone.

No production cache key, Gradle switch, runner configuration, secret, branch protection, required check, APK or integration queue file was changed here.
