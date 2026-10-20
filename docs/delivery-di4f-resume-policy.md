# DI4f — Fail-closed resume adjudication contract (no writes)

DI4a provides a private local journal, DI4b checks live PR state, DI4c/DI4d/DI4e provide inert portable evidence and experimental cross-run transport. **None constitutes a durable, authoritative CAS database.** DI4f isolates the adjudication policy that a *future* authenticated store and protected controller must obey.

## Inputs (not authority)

`adjudicate_resume(checkpoint, observed, expected_revision=..., expected_manifest_digest=...)` receives an already validated and independently pinned checkpoint and an independently observed GitHub snapshot. Input schemas are strict and fixed to `jssantogit/BitChord`; malformed types and unknown fields are rejected. The checkpoint includes manifest SHA-256, original main/head SHA, PR number, revision, phase, and `approval_required=true`, `executable=false`. Observed state includes exact current main, PR HEAD/status/merge SHA and Android check status. **The caller is responsible for obtaining trusted live observations** and independently authenticating the expected revision/digest. This function never fetches GitHub state.

## Decisions

- **Healthy preflight** on unchanged main/head with Android GREEN returns `require_live_preflight` — not permission to merge.
- **Stale revision/manifest or changed HEAD/main** returns a typed halt, never a retry. A revision check here detects inconsistencies only; it is **not an atomic CAS operation**.
- **`merge_intent` checkpoint** always returns `manual_review_uncertain_merge`, even if GitHub says the PR is still open. A timed-out merge PUT might have succeeded while the response was lost; never blindly repeat it.
- **Observed merged state** requires matching exact merge SHA, GitHub PR closed/merged, current main equal to the recorded merge SHA and Android GREEN. Even then it returns `manual_postmerge_ci_review` or `manual_confirm_completion` rather than auto-advancing the queue.
- **Closed unmerged PR, failed/pending/missing Android, unknown fields, forged executable flags** fail closed. Raw exceptions, credentials, URLs and external messages are not emitted.

All responses contain **`approval_required=true`, `execute=false`, `replay_merge=false`**. There are no write endpoints, storage backends, resumption tokens, GitHub App credentials, queue dispatches or changes to `tools/integration_queue.py`.

## Required future work before real crash-safe DI4

1. Select a suitably protected external storage backend with authenticated per-batch identity, monotonic generation and **server-enforced** compare-and-swap/conditional writes across independent runners. GitHub Actions artifacts do **not** satisfy this.
2. Design and TDD the durable checkpoint publisher and consumer, including replay prevention, lease expiry, concurrent actors, retries, ambiguous merge outcomes, and monotonic state.
3. Integrate with the existing `integration-apply` human-approval and protected queue only after a separate security review and explicit owner authorization. Never derive approval from a checkpoint or artifact alone.
4. Run real multi-run chaos testing: crash before request, after GitHub accepted merge but before response, after response before checkpoint, and during post-merge Android CI. Require reconciled live GitHub evidence and fail-closed recovery.
5. Verify measured reliability and throughput before considering the 21 functional PRs.

Test: `python3 -m unittest tests.test_delivery_resume_policy -v`, then full `python3 -m unittest discover -s tests -v` and Android CI on the exact PR HEAD.

**Status:** policy-only design and tests. No DI4 production writer.