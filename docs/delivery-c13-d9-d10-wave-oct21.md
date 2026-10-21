# Protected functional tail wave — Catalog C13 + Performance D9/D10

**Prepared 2026-10-21. Manifest publication only; not merge authorization.**

## Validated prerequisite wave

Protected queue [#48](https://github.com/jssantogit/BitChord/actions/runs/39580240285)
merged Playback B12 #129, Catalog C12 #126, Listening L12 #112.
Its original main SHA `7bc787d757e6fe82f89fe63d506d4b1bc4b5587d`
advanced through three Android-post-main-GREEN merges to
`1eaa5c29a9e32baa8f058ce02f64577543130c99`
with final Android push [#1139](https://github.com/jssantogit/BitChord/actions/runs/39582014282)
COMPLETED/SUCCESS. Queue duration was 19m06s.
Prior four optimized waves were also GREEN: 15 functional PRs total.

## Proposed reviewed `delivery-tail-c13-d9-d10-v0`

| Queue order | Slice / PR | Original HEAD | Expected original base | Dependency |
| --- | --- | --- | --- | --- |
| 1 | Catalog C13 #130 | `c65bc1de6ade14d03f1aa760b996ccbaafe76622` | `main` | none |
| 2 | Performance D9 #123 | `1d5c819c26e0380da2392d485b55fa0e46561042` | `main` | none |
| 3 | Performance D10 #124 | `abd7f85ff9e6dc4be902fe435ccf87689acbc2fb` | `perf/d9-functional-recovery-lifecycles-v0` | #123 |

C13 was retargeted only after C12 was merged; D9 was retargeted only
after D8 was merged. Neither retarget changed its original HEAD. D10
remains stacked on D9 so the queue will retarget it after D9 merges,
not before. GitHub currently reports C13 and D9 mergeable/behind,
and D10 mergeable/clean on its stacked base, with 0 review threads.
The three own-file allowlists (9, 2 and 8 paths) are disjoint.
Original exact-head Android CI passed for every candidate; the
queue **must still synchronize against live main and require fresh
Android CI on synchronized heads**, and exact SHA main push GREEN
after each protected serial merge. `docs/DIAGNOSTICS.md` is an
explicitly approved D10 documentation path.

C13 introduces a bounded Catalog-owned lifecycle and playback
adapter seam; it **does not** prove per-asset VERIFIED MP3 or make
Playback B13 applied/complete. Advertised capability cannot be
promoted to VERIFIED. D9 and D10 provide diagnostic projections
and offline triage without changing playback behavior.

## Explicit excludes and gates

- Downloads/Acquisition #63 is separate and not in this wave.
- Playback B13 #133 is a preparatory test/gate only, **DO NOT MERGE**,
  pending verified per-asset quality, 64-kbps policy and a correct
  open-time Media3/Catalog integration contract.
- Listening L13 #127 remains HOLD while inherited Playback B9/B10
  paths persist in its diff; it requires reconciliation, re-audit and
  new CI, and must not be silently merged.
- DI4f #101 is an independent infrastructure track; C2 #49/L3
  are historical holds. No other PR is authorized by this document.

## Manual publishing and integration procedure

1. Complete tests and merge this manifest/test/docs-only PR through
   the normal protected main route **only upon owner authorization**;
   verify Python and Android checks GREEN on exact head and
   post-merge Android main push GREEN.
2. Reverify original live PR SHA/base/file scope, required CI and
   reviews; move only #130/#123/#124 from draft to Ready.
3. Dispatch Controlled Integration Queue on protected `main` with
   `batch=delivery-tail-c13-d9-d10-v0`, `mode=plan`,
   and **the new exact full main SHA** after manifest publication.
   Require `PLAN OK` on a stable main. No direct native functional
   merges and no concurrent batch execution.
4. Separately dispatch `mode=execute` on the same SHA only after
   a successful plan and human `integration-apply` approval.
   Continue fail-closed across CI, rulesets, conflicts, file scope,
   head drift and any partial-failure restart.
5. Record each `QUEUE_METRIC v=1` phase and Android post-main SHA.
   Never blindly rerun a partially integrated manifest.
