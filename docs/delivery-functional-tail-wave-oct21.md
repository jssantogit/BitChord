# Functional tail integration — B12, C12, L12 (2026-10-21)

**Candidate, not authorized to merge until reviewed and owner-gated.**

## Prior verified checkpoint

Four protected queues passed: Playback #40, Listening #42, Catalog #44 and
Performance #46. The Performance execute was COMPLETED/SUCCESS, with Android
main push #1110 GREEN on exact protected main
`980953501b419bbc5cfc3d94ef1bc742607f5d26`.

The preceding baseline of three optimized queues was ~21m19s; Performance
#46 completed in 19m05s. The outcomes support further timing measurement,
not an unconditional claim of causation or parallel merge safety.

## Proposed independent three-PR manifest

`delivery-tail-b12-c12-l12-v0`, all **original bases now main**:

| Order | Candidate | Exact original HEAD | Scope |
| --- | --- | --- | --- |
| 1 | Playback B12 #129 | `826c0b1bf93f3f618c97e638a59fcab008f82dab` | 2 files; read-only network-quality intent, never applied |
| 2 | Catalog C12 #126 | `6418fb9f4f9ad7b83576e780bf170038fe67b2c3` | 3 files; requested/negotiated/observed stream truth |
| 3 | Listening L12 #112 | `818efc8de1f6279e3a0c20cc16b6ebf105e06d7e` | 6 files; saved smart-playlist definitions and editor |

Their former parent PRs B11 #117, C11 #122 and L11 #111 were already
integrated; all three tail PRs were explicitly retargeted to main without
changing original HEADs. GitHub rechecked the expected owner-only diff after
retarget: file paths still match their own isolated footprints; zero unresolved
review threads; each original pinned HEAD had Android · test and build GREEN.
All are still draft pending published-manifest CI and human review.

**Critically**, a pre-existing exact-head GREEN is insufficient for a
new main. The protected queue must synchronize each candidate, wait for fresh
Android GREEN on the synchronized SHA, check native mergeability, do a serial
protected GitHub merge and demand exact main-push Android GREEN after
*every* PR. The next PR may prepare during the previous post-main CI but
cannot merge before its GREEN.

## Exclusions and later work

- Acquisition/Downloads #63 remains outside these integrations and has a
  separate owner/workstream.
- Playback B13 #133 remains explicitly *preparatory-only / DO NOT MERGE*;
  no real remote-quality application, secure open-time integration or proven
  LOW 64kbps Catalog support has been delivered by that PR.
- Listening L13 #127 remains HOLD because inherited B9/B10 Playback paths
  appear in its PR diff. Reconcile against current main and verify its
  Listening-only file list and Android CI before any subsequent wave.
- Catalog C13 #130 waits for C12; Performance D9 #123 and D10 #124 wait
  on D8 and D9 respectively. A later reviewed manifest must rebase/retarget
  children whose parent merged in a preceding wave.
- DI4f #101 remains separate; historical C2 #49/L3 remain HOLD.

## Protected human sequence

1. Merge this **manifest/test/docs-only** PR to main only after its Python
   and Android CI GREEN; do not treat that merge as any feature integration.
2. Recheck live main SHA, original HEADs/bases, exact allowlists, required CI
   and review threads; transition only #129/#126/#112 to Ready for review.
3. Manually dispatch `Controlled Integration Queue` on main with
   `batch=delivery-tail-b12-c12-l12-v0`, `mode=plan` and exact
   `expected_main_sha` of the **new main**. Require `PLAN OK`.
4. Separately dispatch `mode=execute` with the same SHA, the existing
   `integration-apply` approval and write gate. No bypass, simultaneous
   execute, greater-than-three batch, or unattended re-execution.
5. Record `QUEUE_METRIC v=1` and post-main CI checks per PR; stop and
   reconcile after any RED or unexpected SHA, even after partial merges.
