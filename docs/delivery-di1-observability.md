# Delivery Infrastructure v2 — DI1: Metrics & Failure Taxonomy

Read-only, stdlib-only reporting for explicitly supplied first-party snapshots. It makes **no** network calls, GitHub writes, state changes, branch updates, or integrations. It does not modify the existing protected merge executor.

```bash
python3 -m unittest tests.test_delivery_metrics -v
python3 tools/delivery_metrics.py --input snapshot.json
```

Input is an object with optional arrays `prs`, `jobs`, `blocks`:
- PR: `number`, `created_at`, `merged_at` (nullable).
- CI job: `run_id`, `pr_number` (nullable), `head_sha` (40 hex), `created_at`, `started_at` (nullable), `completed_at` (nullable), `conclusion` (nullable).
- Block: `pr_number` (nullable), `stage` (one of discovery/plan/sync/pre_ci/merge/post_ci), `code` (bounded stable type). Example: `HTTP_403` maps to `permissions`, `SHA_DRIFT` to `stale_sha`, `CI_RED` to `ci_failure`. Unknown codes become `UNKNOWN` rather than copying raw messages.

Output: versioned JSON records; numeric observed PR lifetime **from PR creation**, never mislabeled as ready-to-merge lead time; GitHub CI job queue wait (job created to started) and job execution duration (started to completed) measured separately; medians calculated only from complete known values; blocks show only allowlisted categories, stage, PR number. No actor names, comments, raw API errors, secrets, raw URLs, tokens or titles are copied into the output. Timestamps must contain timezones, must be monotonic, invalid data fails closed. No assumed correlation between independent jobs and merge events.

**Limitation:** This is an offline DI1 contract, not automatic telemetry from the current integration executor yet. Feeding GH REST observations and integration events to the reporter, CI cost/cancellation metrics and an append-only checkpoint store are follow-up work. Do not use the output as an approval manifest.

**Safety:** Existing strict required Android check, protected merge queue, 2 PR lanes, and SHA-based post-merge CI remain unchanged.
