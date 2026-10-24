# Reviewed functional wave — Listening L13 + Playback B13

Prepared on 2026-10-21. **Manifest publication only: no authorization to merge functional PRs.**

## Latest verified checkpoint

Protected `main=ebf5f486cb214bc024c5c70f18c4902c98fb70d5`, after DI4f #101
MERGED and Android push CI [#1168](https://github.com/jssantogit/BitChord/actions/runs/39609610224)
and integration tests #193 COMPLETED/SUCCESS on exact main.
Prior six protected three-PR functional waves completed GREEN, total 18 functional PRs
integrated. This document does not change playback, Catalog, Listening or existing
queue infrastructure.

## Precisely reviewed batch: `delivery-final-l13-b13-v0`

| Order | PR | Exact original HEAD | Base | Approved own-file diff |
|---|---|---|---|---|
| 1 | [Listening L13 #127](https://github.com/jssantogit/BitChord/pull/127) | `01c5adce17bdb9f4d468c27eb509db5338de80d7` | main | 8 files |
| 2 | [Playback B13 #133](https://github.com/jssantogit/BitChord/pull/133) | `a2d7fb490f72d480919dfcf590252bd7324534c3` | main | 6 files |

Both PRs were verified OPEN/DRAFT/UNMERGED, `mergeable=true` and
`mergeable_state=clean`, and both have 0 open review threads. Their original exact
heads are fully caught up with the latest main, without inherited diffs. The
two independent file allowlists are disjoint. After this manifest is published,
only the native protected queue will synchronize any newly changed main into
the next PR, then check its *new* synchronized SHA and the next main push.

- L13 had prior Playback-owned inheritance and `dirty` merge state; this was
  reconciled non-destructively. Its final eight paths are MainActivity,
  Listening/UI, UI tests, and English/Portuguese strings. **No PlaybackService
  or Playback contract changes**; explicit PLAY_NOW versus ADD_TO_QUEUE, no
  unintended autoplay. [Android CI #1169](https://github.com/jssantogit/BitChord/actions/runs/39611352243)
  COMPLETED/SUCCESS on the exact reviewed HEAD.
- B13 now has next-open Media3 `DataSource.open` runtime wiring to the Catalog C13
  adapter. Six Playback-owned paths including runtime, service, gate and tests.
  ADVERTISED is **request preparation only**, never per-MediaAsset VERIFIED_MP3;
  Catalog enforces the actual asset/decoder decision. LOW=64kbps is deliberately
  unsupported rather than silently substituted with 96; MEDIUM=160/HIGH=320
  can request only. C12 truth remains requested/negotiated/HTTP-observed,
  not evidence of decoding or audibility. Cancels stale account/closed stream and
  rejects late truth callbacks. [Android CI #1177](https://github.com/jssantogit/BitChord/actions/runs/39619361681)
  COMPLETED/SUCCESS on the exact reviewed HEAD, following a test-first RED #1176.
  **Important release limitation:** fake Catalog JVM/runtime tests do not prove
  actual physical server MP3 decoding, audible output, real mobile Wi-Fi switching,
  or USB DAC parity. No device/emulator smoke or real-server acceptance was run.
  This requires an explicit owner decision before B13 is made Ready to integrate;
  this manifest and CI do not silently waive that decision.

## Fail-closed release sequence

1. Complete contract tests and Android CI on the **manifest-only** PR head,
   with exactly 3 allowed files: this document, the manifest, and test.
2. Obtain owner approval before the protected native merge of the manifest PR.
   Check its exact HEAD, main base, review threads, repository ruleset, and
   Android CI again; post-merge Android CI must pass on the new main SHA.
3. Independently release candidates after reviewing their risks; **B13 requires
   a documented acceptance of the untested physical/server behavior or further
   test evidence**. Do not claim codec/audibility guarantees from unit CI.
4. Only after both PRs are authorized for review, move #127/#133 to READY;
   dispatch [Controlled Integration Queue](https://github.com/jssantogit/BitChord/actions/workflows/integration-queue.yml)
   manually on protected main with `batch=delivery-final-l13-b13-v0`,
   `mode=plan`, and `expected_main_sha=<new exact main after manifesto>`.
   Verify read-only PLAN OK, zero writes and no head/manifest drift.
5. Protected `mode=execute` separately needs owner approval and human
   `integration-apply`; merges are serial and gated by fresh Android CI on
   synchronized PR HEAD and each post-merge main SHA. Never merge either
   manually or dispatch overlapping queues. Record queue metrics and errors.
6. Acquisition/Downloads [#63](https://github.com/jssantogit/BitChord/pull/63)
   stays independently DRAFT/owned by Downloads; never include it in this batch.
   C2 #49 and L3 remain HOLD. No APK/device validation is claimed.

If either HEAD, base, file scope, PR state, or main changes before execution,
rerun the audit and deliberately repin/review the manifest through another
protected infrastructure PR; never bypass fail-closed checks.
