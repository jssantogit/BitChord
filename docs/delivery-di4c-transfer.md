# DI4c — Portable checkpoint evidence across runner boundaries (read-only)

DI4a journals live on one runner's local disk; DI4b reconciles live GitHub observations but does not persist anything. This slice adds a **versioned, deterministic, size-bounded envelope** that can be exported from a DI4a checkpoint and inspected by a later process or runner **after an independently trusted transport provides its bytes and SHA-256**.

## Source and trust
- Export source must be first-party `jssantogit/BitChord`, event `workflow_dispatch`, ref `refs/heads/main`, exact protected main SHA, positive workflow-run ID and attempt. The artifact includes only allowlisted checkpoint data, not tokens or user identities.
- The envelope contains DI4a manifest digest and local checkpoint integrity, but **its internal hash is not an authentication signature**. A consumer must obtain the *artifact SHA-256, run ID, attempt, main SHA and reviewed manifest* from an independent trusted source, not from within the same artifact. Otherwise abort.
- The receiver validates every expected identity, manifest/HEAD correspondence, checkpoint state shape and digest before returning only `require_live_revalidation`. It always reports `approval_required=true`, `executable=false`, `replay_merge=false`.
- This does **not** automatically upload/download artifacts, extend a GitHub App token, mutate a Git ref, resume an Action, approve merges or replay an uncertain merge. A separately reviewed Actions transport with retention, artifact provenance and guarded writer must be implemented and tested before DI4 can be called a production executor.
- After loading evidence, rerun DI3 live preflight and DI4b reconciliation. If a PR was already merged, detect and manually review the postmerge CI; never issue a second merge based on this evidence alone.

Contract: `python3 -m unittest tests.test_delivery_transfer -v`. No change to current protected queue or CI workflow. This slice is stacked on DI4b (#91) and DI4a (#90).
