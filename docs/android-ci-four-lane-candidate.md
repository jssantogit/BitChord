# Android CI bounded fourth-lane candidate — do not merge without live pilot

## Observed problem

The protected Android check currently uses **three** job concurrency lanes and a separate exact-SHA main group. Catalog and intelligence branches route to lane A, Playback to B, every other PR including `perf/`, infrastructure and Acquisition to C. The job concurrency queue is lossless (`queue: max`, no cancellation within lanes); obsolete PR-level workflows can be cancelled, and exact-SHA main-push checks are never cancelled.

On 2026-10-21, the owner-authorized CI audit used first-party GitHub Actions `created_at`, `started_at` and `completed_at` timestamps for the required Android job on **13 then-current exact PR HEADs**:

| Lane | Relevant PRs sampled | n | Median queue wait | Max queue wait |
| --- | --- | ---: | ---: | ---: |
| A (catalog/intelligence) | #139, #141, #147 | 3 | 4s | 86s |
| B (playback) | #145 | 1 | 2s | 2s |
| C (perf/infra) | #138, #140, #142, #143, #144, #148, #149, #150, #151 | 9 | 4s | 510s |

Lane C included individual queue waits of 98s, 175s, 394s, and **510s**. Four infrastructure-only heads in the sample had short warm-cache Android jobs; five Performance heads were typically slower. This sample is **not** an exhaustive period-wide analysis and does not prove causes from timestamps alone; lane serialization is a plausible contributor given the current routing and overlapping PR activity.

The core Android CI compilation remains a separate critical path. [PR #151](https://github.com/jssantogit/BitChord/pull/151) ran the full Android test suite both ways with two workers and unchanged cache counts (65 tasks executed, 8 cached): sequential 260s and parallel 258s. That 2s difference is insufficient to justify enabling Gradle parallelism globally. A lane change addresses **queue wait**, not per-job compilation.

## Reversible candidate (not active on protected main)

Split only `perf/` PR heads into a fourth bounded lane D. Retain:
- A: catalog + intelligence.
- B: playback.
- C: infrastructure, Acquisition and every other PR.
- D: Performance diagnostics branches prefixed `perf/`.
- Protected `main`: separate group per exact merge SHA, without cancellation.
- Required status `Android · test and build`; the full existing `:app:testDevDebugUnitTest` target; default `--build-cache`, same JDK, same runner type, same permissions/timeouts.
- Lossless per-lane job queue. No global `org.gradle.parallel`, extra test suites, skip conditions, APK or automatic merges.

Update read-only diagnostics **atomically** with the workflow: `_lane_for_run('pull_request', 'perf/...') -> pr_d`; allowlist `pr_d` in `tools/delivery_metrics.py` so metrics cannot silently misclassify Performance as C.

## Risk and approval gates

A fourth simultaneous Android test job may increase runner usage or compiler memory/IO contention (especially during KSP/Kotlin compilation) and could slow existing PR and main checks. No effective fourth-lane throughput gain can be proven until this workflow candidate is published on `main` under independent owner approval and observed with real workload.

Before **any** merge:
1. Python contract tests and Android CI GREEN on the exact candidate PR HEAD. Audit only expected workflow/metrics/tests/docs diff, branch sync, Github permission constraints and existing strict main status context.
2. Owner separately approves workflow/ruleset-sensitive protected integration; candidate may need manual branch synchronization if `.github/workflows/check.yml` is behind main and the integration App cannot write workflows.
3. After protected merge and post-main Android GREEN, compare **at least 3 comparable waves** of queue waits (median/p90), job execution durations, CI failure/oom/retry frequency and post-main exact-SHA total latency. Do not assume a branch draft is a live traffic experiment.
4. Rollback via another owner-approved workflow PR if main checks slow or contention/instability rises. Existing three-lane behavior is the fallback; never bypass required post-main CI.

This is a **candidate PR**, not authorization to expand runner concurrency in production.
