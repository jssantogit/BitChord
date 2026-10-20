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