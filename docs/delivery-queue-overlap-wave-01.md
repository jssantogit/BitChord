# Delivery Queue — staged overlap rollout wave 01

**STATUS: CANDIDATE / DRAFT / UNMERGED / NOT AUTHORIZED TO EXECUTE**

Manifest: `.github/integration-batches/infra-queue-overlap-wave-01.json`.
This file stages exact reviewed candidate inputs **without approving or
executing** an integration. It excludes every functional workstream,
Acquisition, DI4f #101, revoked C2 #49, and three-lane workflow pilot #131.

| Order | PR | Approved candidate SHA | Original base | Depends on |
| --- | --- | --- | --- | --- |
| 1 | #120 overlap | `a7b670ecb5be3e5fde87b6db6eb093eddf8627f9` | `main` | none |
| 2 | #125 metrics | `1b1686332b6f50876c568d67e6bc9316b2c46e41` | `infra/integration-postci-next-preflight-overlap-v0` | #120 |
| 3 | #128 DI1/DI5 observations | `a326f506e507acee90262d7e5adb8fb83cedeb80` | `main` | none |

All three PRs have their original full exact-head Android and Python checks
GREEN at the time of staging. The queue must independently revalidate
reviewed HEAD, source base, allowed files, strict protections, dependencies,
drift and **fresh exact-head Android CI after any synchronization**.

## Mandatory independent gates

1. Review this **manifest-only** PR #132 and its diff/tests first. Publishing
   a manifest is a separate protected bootstrap merge and requires explicit
   owner authorization, plus exact HEAD mandatory Android/Python GREEN and
   post-bootstrap `main` Android GREEN. It does not authorize PR merges.
2. Review each target PR and confirm no scope/head drift, conflicts, unresolved
   threads or required CI failures. Targets are still **DRAFT**; do not mark
   ready without specific independent integration authorization. Verify the
   parent #120 / child #125 stacking. Inspect #128 independently.
3. If owner separately approves the exact *three PR* wave, run the protected
   **read-only** Controlled Integration Queue `plan` on `main`, batch
   `infra-queue-overlap-wave-01`, with exact live 40-char `main` SHA.
   Plan mode must remain GET-only. A ready PR with a changed exact head or
   base is not approved by this stale manifest: stop and require a new
   reviewed manifest/authorization.
4. Only after a GREEN plan and separate owner-approved write authorization,
   protected `integration-apply` human approval and
   `INTEGRATION_WRITE_ENABLED=true`, dispatch `execute` from `main`
   with the approved exact `main` SHA. Queue merges **one PR at a time**,
   never skips any Android pre/post-merge GREEN and halts on failures.
   During bootstrap the active queue remains MAX_ITEMS=3 (40min controller,
   50min Actions job), using the *old* checked-out controller.
5. Revalidate protected `main` and its exact required Android push-run
   after all three integrations. **This bootstrap wave cannot serve as a
   measured new-overlap pilot.** Only a later, separately authorized batch
   with a newer `main` checkout containing #120/#125 can produce real
   `QUEUE_METRIC` stage-level overlap evidence.
6. PR #131 modifies `.github/workflows/check.yml` and is intentionally
   excluded. After #128 is integrated, it requires separate protected
   workflow-file sync/integration outside the restricted App writer,
   exact-head CI and independent owner approval. Do not silently grant
   GitHub App workflow-write permission.

## Scope and uncertainty

Current manifest is a proposed snapshot, not a lease or persistent approval.
Other parallel workstreams may change `main`, branch HEADs, or CI while this
draft awaits review. All current SHA/diff/mergeability observations must be
refreshed immediately before protected publication and again before every
integration. Do not trigger a plan or execute against drafts or against
obsolete main SHAs merely to test a workflow. No branch mutation or direct
merge is part of manifest staging.

The throughput hypothesis (post-main CI overlap, measured timing, CI lane
pressure) remains unproven until a separately approved production batch
demonstrates better wall-clock throughput without weakening required checks.
