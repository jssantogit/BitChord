# Controlled Android CI fourth-lane replay — offline diagnostic only

## Why this exists

[PR #152](https://github.com/jssantogit/BitChord/pull/152) proposes routing `perf/` pull requests from the current bounded lane C to an independent fourth lane D, while leaving Catalog/Listening on A, Playback on B, and infrastructure/acquisition/other branches on C. On 13 recent exact-HEAD Android PR jobs, lane C had waits as high as 510 seconds. **An observed wait is not proof that lane serialization, rather than runner capacity or missing work, caused it.**

This diagnostic compares a synthetic replay of the existing **three-lane job serialization** with the proposed fourth-lane routing, using first-party GitHub Actions `created_at`, `started_at`, and `completed_at` timestamps. It is implemented in `tools/delivery_ci_lane_replay.py`. It never modifies `.github/workflows/check.yml`, app code, cache policy, protected-branch rules, or the integration queue, and performs no GitHub writes.

## Usage

```bash
python3 -m tools.delivery_ci_lane_replay --repo jssantogit/BitChord --limit 30
```

The tool uses the existing GET-only `GitHubCIReader` (optionally authenticated with `GITHUB_TOKEN`). It reads up to 30 recent workflow runs from the first page, validates same-repository pull-request provenance and exact SHA syntax, and observes the single existing required `Android · test and build` job per run. Only successful completed PR jobs with coherent timestamps may be modeled; pending, failed or missing jobs are counted separately and block a positive calibrated result.

**No raw branch, filename, commit SHA, actor, token, job log, cache key or arbitrary API string is emitted.** Branch paths are consulted only inside memory for the existing A/B/C policy and `perf/` eligibility.

## Model

For each observed PR job, its `created_at` is treated as the arrival time and observed job duration as the service time. Both candidate timelines keep identical arrival/order/duration inputs. Baseline: A/C/B routing. Candidate: same except `perf/` => D.

Assumptions are deliberately optimistic: single FIFO worker per lane, no limit on total hosted runners, no inter-repository contention, no approval delays, no new build failure/OOM and no effect on job duration from adding more concurrent Kotlin compilers. The predicted `idealized_total_wait_reduction_seconds` is **not** a measured production speedup or a defensible forecast of protected-queue completion time.

### Fail-closed calibration

A positive model output is suppressed unless all hold:

- At least 8 complete PR jobs, with at least 3 `perf/` jobs and 3 other C-lane jobs.
- No incomplete/missing required Android jobs in the examined subset.
- Replaying the **unchanged legacy three-lane policy** approximates observed queue delays with median absolute error at most 30 seconds **and** worst-case error at most 90 seconds.
- Timestamps, run IDs, repository provenance, success status and required job identity are complete and consistent; malformed/incomplete first-page results fail closed.

The status is one of `insufficient_sample`, `uncalibrated`, or `calibrated_sample_only`. If status is not `calibrated_sample_only`, reported hypothetical gains are null. Even a calibrated sample represents only observed first-page runs, not the entire queue history; a successful model cannot verify scheduler fairness, CI runner capacity, or reduced overall cost.

The strict test suite (`tests/test_delivery_ci_lane_replay.py`) covers replay arithmetic, one-sided/incomplete samples, stale or foreign runs, invalid timestamps, reader errors, privacy and outlier rejection.

## Decision gate and next actions

Do not increase production concurrency based solely on this replay. For owner-approved rollout, combine real on-main evidence: exact-SHA required CI GREEN after each merge; queue median/p90 by lane over at least three comparable waves; Android test and setup durations; runner contention, memory/OOM/retries and total protected integration time. Compare the resulting measurements with the calibrated replay's limitations. Any workflow change or merge is a distinct owner-authorized action.

If this sample fails calibration, **that is useful evidence of unobserved traffic or external runner constraints**. It is not a license to disable the accuracy checks or claim that lane D will solve the queue.
