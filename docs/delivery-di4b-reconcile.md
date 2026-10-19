# DI4b — Read-only live resume reconciliation

This slice is intentionally stacked on DI4a, and provides **no new GitHub write capability**.

Given an unapproved DI4a checkpoint, its identical exact-SHA manifest and an injected GET-only reader (DI3 `GitHubReadOnly` is compatible), validate main at the start and end, enumerate PR HEAD/base/repository/draft/mergeability/files and required Android check, and produce allowlisted decisions.

- `preflight_revalidation_required`: observations appear clean; still require DI3 preflight, original owner approval and protected execution.
- `blocked`: missing/invalid/RED/untrusted data or transport error; sanitize exceptions, do not reveal credentials.
- `manual_postmerge_review`: a PR was already merged or closed; **never automatically replay** a merge.
- `halt_main_drift`: main moved during observation; invalidate the entire snapshot.

Every result has `executable=false` and `replay_merge=false`. There is no automatic transition to a write-ready state.

Run `python3 -m unittest tests.test_delivery_journal tests.test_delivery_reconcile -v`.

**Not DI4 production activation:** cross-run persistence, artifact validation, human-approved durable intent, idempotent writer integration, timeout budgets and live A/B crash testing are separate future changes. Retain the current protected serial queue as the only integration controller.