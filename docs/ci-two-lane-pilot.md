# Android CI — two-lane PR queue pilot

## Intent

Test a bounded increase from one active PR Android job to at most **two** concurrent PR Android jobs, without changing the required `Android · test and build` context or unit-test scope. GitHub Actions supports at most one running job per concurrency group; `queue: max` retains up to 100 waiting in each group.

## Deterministic routing

- **Lane A**: branches starting `catalog/` or `intelligence/`.
- **Lane B**: all other pull-request branches, including `playback/`, `perf/`, `acquisition/`, `infra/`, and external contributors. Branch identity is stable across commits and retries.
- **Protected main**: SHA-scoped independent group, never waits for lane A/B, and a later post-merge main run must not cancel an earlier main commit's run.
- Existing workflow-level cancel-in-progress is true **only** for superseded runs of the same PR; job-level `queue: max` and `cancel-in-progress: false` are preserved. This is **not** a global FIFO scheduler or a fair balancing algorithm: lane A/B load can differ.

## Prerequisite: single-lane evidence

- PR #79 merged to protected main `c7c48da2a7e9842ae98410e7a18bad4c462a4b5a`, CI #695 GREEN.
- Android #696 (PR #82) started 17:08:44Z, finished 17:13:16Z GREEN; #699 (PR #83) started 17:13:18Z, 2 seconds later while #700 (PR #84) still had no runner. Capture exact #699 and #700 completion/start times before promoting this pilot.

## Post-integration acceptance (not yet exercised)

1. Complete required and lightweight CI GREEN on this pilot PR's exact HEAD; obtain owner authorization for merge. Confirm post-merge protected-main required Android check GREEN before assuming changed CI behavior is deployed.
2. Trigger at least two **different PRs in lane A** and two **different PRs in lane B** using approved, non-functional workflow/documentation modifications or existing suitable PR updates. Never force-push or alter other workstreams simply to generate traffic.
3. Demonstrate via job-level GitHub timestamps that one job from lane A overlaps a job from lane B, while no two jobs in the **same** lane overlap. Verify neither pending job cancels due to unrelated PR activity.
4. Demonstrate an update to a PR HEAD cancels only its older check; a main push remains independent and completes under the same check context. Record exact HEAD/run status, queue wait, runtime and elapsed wall time separately.
5. Baseline one lane vs two lanes on comparable CI workloads: queue time, actual job seconds, total time, cancellations, runner minutes/consumption and full Android unit test failures. If performance regresses, quota costs spike or protection behavior is questionable, revert group mapping by a separate infrastructure PR.

## Safety

No APKs, no extra jobs, no reduction of tests or step timeouts, no main/ruleset changes, no workflow_dispatch that could accidentally block required check, no merges automatically. This is a *pilot*, not a workload-independent optimal scheduler.
## Historical handoff

This document records the deployed two-lane policy and its prior acceptance criteria. The separately proposed third-lane experiment is documented in [ci-three-lane-pilot.md](ci-three-lane-pilot.md) on its **DRAFT, unmerged** branch. Do not consider that proposal active until an independently approved protected workflow merge and post-main Android GREEN.
