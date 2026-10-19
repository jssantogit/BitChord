# Approved infrastructure integration — Controlled Integration Queue

## Owner approval and exact scope

On 2026-10-19 the repository owner authorized completing and integrating the workflow optimizations without asking for another approval. This document stages **only infrastructure PRs #82, #84, #83, #85**. It does not grant merge permission to any Catalog, Listening, Playback, Performance, or Acquisition slice.

The existing integration controller remains fail-closed: read-only plan, manual execute, GitHub App-scoped writes, protected `integration-apply` approval environment, strict required Android pre/post-merge CI and precise main SHA. **No automatic workflow_dispatch or scheduled merges are introduced.**

## Batch one: `infra-workflows-1`

Order: #82 (owner-only issue close and read-only contributors preview) → #84 (weekly contributors-image trigger) → #83 (opt-in Gradle build cache pilot). Each is an independent PR based on main. Reviewed initial HEADs and file allowlists are pinned in `.github/integration-batches/infra-workflows-1.json`. Max 3 per queue budget; after each merge wait for protected main Android CI GREEN.

## Batch two: `infra-workflows-2`

#85 (bounded two-lane PR Android CI pilot) must merge **after #83**, because #83 changes `check.yml` and `tests/test_android_ci_queue.py`. #85 includes an updated test allowing the existing unit-test command both with and without `--build-cache`; the actual merged workflow after #83 must preserve `--build-cache` *and* both independent queues. If the native update-branch operation causes a conflict or unexpected file diff, stop and repair via separately audited code and re-pin the manifest after fresh CI; no blind force-push or rebase.

## Bootstrap and dispatch

The queue's `workflow_dispatch` input currently has a fixed choice list. This preparatory PR adds these two batch slugs to that list and their SHA/file manifests. **Until this preparatory PR is integrated onto main, the queue cannot select the new batches.** The prep PR itself must pass required Android + lightweight tests on the exact HEAD and be merged through protected GitHub controls; do not represent this bootstrap operation as a Controlled Integration Queue execution.

After this bootstrap is live, the operator uses GitHub Actions → Controlled Integration Queue → Run workflow from `main`:
1. Select `infra-workflows-1`, mode `plan`, expected_main_sha = the fresh protected main HEAD. Validate `PLAN OK` and all three pinned PRs/paths.
2. Select same batch, mode `execute`, with **exact same main SHA** if unchanged. Approve the existing protected environment `integration-apply`; do not remove the human/environment gate even though the owner provided blanket PR-scope approval. Require `BATCH GREEN` and final main push Android CI GREEN.
3. Re-fetch fresh protected main SHA; run `infra-workflows-2` plan and execute separately with that new SHA, same gated procedure. Never reuse batch-one main SHA.
4. Verify #85 merged and postmerge Android GREEN, then run the A/B concurrency experiment with genuine PR work; no functional branch changes solely for test traffic without coordination.

The GitHub connector used to prepare this PR has no workflow-dispatch or environment-approval action. If it remains unavailable, complete manual Actions UI dispatches; do not replace the execution with unreviewed direct GitHub merges.

## Boundaries

No APK or physical smoke requested. Do not touch protected main ruleset, repo secrets, or workflow write gate. Do not set `INTEGRATION_WRITE_ENABLED` automatically or bypass an environment reviewer. Queue execution stops automatically on HEAD, diff, conflict, CI or ruleset drift.

Existing feature workstream PRs are explicitly excluded.
