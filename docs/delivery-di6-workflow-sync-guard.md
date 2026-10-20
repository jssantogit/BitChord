# DI6 — Early workflow-sensitive update-branch guard

Observed incident: workflow-changing PR #85 hit a protected queue `PUT /pulls/85/update-branch` HTTP 403. The GitHub App used by the queue intentionally has `contents:write`, `pull-requests:write` and `actions:read`, **not** `workflows:write`. We will not silently broaden its privileges.

When `compare(main, HEAD).behind_by > 0` and the manifest-reviewed file allowlist includes `.github/workflows/`, `workflow_sync_guard()` now halts **before** attempting the known permission-sensitive update endpoint, emitting `workflow_file_sync_requires_manual_preparation`.

The owner may use GitHub's native branch-update mechanism or a separately reviewed non-destructive two-parent branch sync, then wait for the exact new HEAD's Android CI GREEN, repin a reviewed manifest and rerun plan/execute with a **fresh main SHA and explicit owner approval**. Never reuse the old expected HEAD after syncing. The queue still blocks on all GitHub protection rules; no bypass, branch force update, automatic workflow permissions or token disclosure.

The guard does not claim every 403 is caused by workflow permissions and does not recover such errors automatically. It merely classifies and avoids this known unsafe path. Workflow PRs already fully up-to-date (`behind_by=0`) remain eligible for the other existing gates.

Verify: `python3 -m unittest tests.test_workflow_sync_guard tests.test_integration_queue -v`.

This PR modifies the protected controller's **pre-sync check only**, not its merge, manifest approval, pre/post Android CI or environment approval logic. Draft until comprehensive CI review.