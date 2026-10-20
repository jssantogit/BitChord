# DI4a — Local checkpoint journal (foundation; NOT an active executor)

DI1 metrics, DI2 Planner and DI3 Preflight are already on protected main. This slice adds **only local storage and conservative restart decisions** for future resumable orchestration. Its execution authority is deliberately absent: `approval_required=true`, `executable=false` on every checkpoint, including after preflight.

## Contract and safety
- Proposed manifests must target only `jssantogit/BitChord` / `main`, contain 1–3 ordered PRs, exact 40-hex HEADs, dependency and reviewed file allowlists. PR #49 remains denied.
- Initialize pins the manifest SHA-256 digest and original main SHA; reinitialization of an unchanged local checkpoint is idempotent. A changed batch or main SHA is rejected.
- Checkpoint JSON is private (0600), sealed with an integrity digest, updated through an atomic temporary file + `fsync` + `os.replace`, and serialized under an advisory lock. Invalid permissions, symlinks, corrupt data, stale revisions, head drift and conflicting stages halt.
- The only state transition in this foundation is `planned → preflight_green`. No API calls, branch updates, workflow dispatch, auto-approval or merge are reachable here.
- After restart, `reconcile_checkpoint` returns typed read-only decisions. Already-observed merge outcomes require explicit human verification; **never replay a merge after an uncertain crash**.
- Local runner disk is NOT shared across different GitHub Actions runs. **Do not treat this as crash persistence across runners**: a separately reviewed external durable store and guarded writer integration are required before activating DI4's executor.

Test: `python3 -m unittest tests.test_delivery_journal -v`.

Next slices will use an evidence-only GET reconciler, a persistent store with optimistic concurrency across workers/runners and explicit owner-approved `integration-apply` gating before any writer is considered. No changes to the existing `tools/integration_queue.py` protected path in DI4a.