# Android CI — third bounded PR lane (opt-in pilot)

## Status

**DRAFT / stacked on DI5 lane-pressure PR #128 / NOT ACTIVE on protected main.**
This branch changes `.github/workflows/check.yml`, and installing workflow-file changes
requires separately authorized protected synchronization/merge. The limited Integration
Queue GitHub App must **not** be granted additional workflow-write capability or used to
force `update-branch` for this PR.

## Evidence and hypothesis

The original 2-lane scheduler routes Catalog/Listening to A and Playback/Performance/
Acquisition/infra/other to B. GitHub first-party recent job observations include:

| Run | Effective 2-lane group | Wait (created→started) | Job (started→completed) |
|---|---|---:|---:|
| Android #1002 | B, infra PR #120 | 325s | 81s |
| Android #1006 | B, infra PR #125 | 167s | 162s |
| Android #1015 | B, infra PR #128 | 79s | 110s |
| Android #1024 | B, Playback | 460s | 237s |
| Android #1023 | B, Performance | 259s | 246s |
| Android #1022 | A, Catalog | 3s | 250s |

This small, nonrandom sample shows variable job waiting, not a runner-scarcity diagnosis.
Additional PR concurrency may make jobs slower due to CPU/resource/quota contention.
A third lane is an experiment, not a proven performance improvement.

## Exact routing for the pilot

- PR lane **A**: `catalog/`, `intelligence/`
- PR lane **B**: `playback/`
- PR lane **C**: every other PR branch, including `perf/`, `infra/`,
  `acquisition/` and external PRs; bounded fallback (no unbounded groups)
- Protected `main`: existing independent SHA group, unchanged

Keep **exactly** `Android · test and build`, Gradle
`--build-cache -Pbitchord.bundleWear=false :app:testDevDebugUnitTest`,
45-minute timeout, `queue: max` and job-level `cancel-in-progress: false`.
Workflow-level `cancel-in-progress` remains true **only for superseded
pull_request runs of the same GitHub PR ref**; main push runs are not
cross-cancelled. The protected merge controller, App scopes, batch size,
strict ruleset, pre/post Android GREEN, and `integration-apply` remain unchanged.

Update DI5 `_lane_for_run` and DI1 `_LANES` in the **same PR as the
workflow**: a three-lane scheduler with two-lane metrics would produce
misleading reports. Missing source/ref stays `unknown`; raw branch names
must not leave the GET-only collector.

## Verification before any production installation

1. Test-first RED, then Python contract GREEN plus required Android GREEN
   **on exact final PR HEAD**. Assert fixed three groups, correct fallback,
   main-push isolation, unchanged Gradle task and status-check name, and
   DI1/DI5 labels.
2. Owner must explicitly authorize independent protected workflow-file
   merge procedure (the restricted integration App cannot resync workflow
   PRs), verify no conflict, ruleset/branch protection and exact-HEAD GREEN.
3. Merge only with separate approval; require post-main Android check GREEN
   before asserting the new routing is live.
4. On naturally occurring PR CI activity, compare measurable job wait,
   execution, Gradle time, cancellation rate, Android failures and total
   wall-clock **in each group** before/after. Demonstrate jobs from different
   groups overlap and no two in the same group overlap. Do not add synthetic
   commits to functional owners' branches just to generate traffic.
5. If median and tail waits do not fall, or runner minute use, test time,
   resource contention, failures or costs worsen, revert to the previously
   reviewed two-lane mapping through another protected PR.

**No merge, queue execute, bypass, APK, automatic CI dispatch or batch-size
increase is authorized by this documentation.**
