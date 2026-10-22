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
