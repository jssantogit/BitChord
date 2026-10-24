# DI5 — First-party Android CI job observations (GET-only)

This slice instruments **actual GitHub Actions timestamps** without modifying app code or required CI. The collected schema intentionally matches DI1 `tools/delivery_metrics.py` input.

Use a least-privileged read-only token:

```bash
python3 tools/delivery_ci_observations.py --repo jssantogit/BitChord --limit 25 > /tmp/ci-jobs.json
python3 tools/delivery_metrics.py --input /tmp/ci-jobs.json > /tmp/ci-metrics.json
```

The fixed-host GitHub reader fetches a bounded list of recent `check.yml` runs and each run's bounded jobs. Only **the exact `Android · test and build` job** contributes `created_at`, `started_at`, `completed_at`, run id, SHA and optional explicitly reported PR number.

It refuses malformed SHA, external repository, duplicate Android job, unsupported event, incomplete page and unsupported repository. No PR is inferred from a branch name or push SHA; ambiguous/missing `pull_requests` yields null. Missing/pending jobs do not receive fabricated duration or success values. All raw GitHub messages, tokens, URLs, user identities and log bodies are omitted.

This produces descriptive CI queue wait and execution metrics only. It does **not** prove two-lane throughput or cache speedup from a single sample: collect multiple comparable runs, compare lane attribution externally and avoid promising linear gains. No workflow mutation, repo settings changes, automatic integration or merge is included.

Test: `python3 -m unittest tests.test_delivery_ci_observations tests.test_delivery_metrics -v`.

Future separately reviewed telemetry work can collect periodic measurements and confidence intervals, with explicit token/retention limits.

## Lane pressure attribution v0 — observational only

The same DI5 GET-only collector now maps the GitHub workflow run's **explicit**
`event` and `head_branch` into a strictly bounded lane label:
`pr_a` (catalog/ or intelligence/), `pr_b` (other PR branches),
`main` (push with exact main ref), or `unknown` (missing or unrelated
source). It does **not** infer PR numbers from branch names or SHAs, and
does not export raw branch names, workflow payloads, tokens or authors.

The existing DI1 report now provides `metrics_by_lane`: number of
observed Android jobs, number with complete measurable durations, median
job queue seconds (`started_at - created_at`) and median execution seconds
(`completed_at - started_at`). Unknown timestamps remain null and
unfinished jobs never receive fabricated durations. Per-run observations
still expose only strictly typed PR/run identity, exact check SHA,
status/conclusion, and UTC timestamps. A missing lane in older snapshots is
`unknown`, so older DI5 artifacts remain consumable.

Interpretation: these measurements describe GitHub's reported **job-level**
timestamps, not an observed scheduler reason or exclusive runner contention.
Cancelled/superseded PR runs, queued jobs without start, overlapping jobs,
main-push scheduling and runner startup must be distinguished from Gradle
task duration. Compare repeated samples and annotate run policy before
making any lane concurrency or cache configuration change.

This is independent of integration-overlap PR #120 and queue phase-logging
PR #125. It does not modify `.github/workflows/check.yml`, add an Actions
workflow, alter required tests, add external telemetry, enable any merge or
expand MAX_ITEMS=3. Production scheduling changes require separate evidence,
review and authorization.

### Candidate third-lane routing (only on the unmerged pilot branch)

When the companion third-lane CI workflow is installed through a protected, separately authorized merge, the observation labels change together: `pr_a` = catalog/intelligence; `pr_b` = playback; `pr_c` = other PR branches. The `main` and `unknown` labels remain unchanged. The deployed two-lane mapping remains the truth until the new workflow actually reaches protected main. Do not compare historical `pr_b` samples to future `pr_b` samples without accounting for the changed routing policy.
