# Functional integration waves — post Delivery Queue v2 / three Android CI lanes

**Status: reviewed candidate manifests only. Not a merge bypass or automatic execute.**

Checkpoint at staging: protected `main`
`ba23358fe37434522254e6f06b37d211889d02ea`;
Android push CI #1066 and Python push #176 GREEN. All twelve PR original
HEADs below had `Android · test and build` completed/success before this
manifest PR was created; **that does not replace fresh tests after syncing**.

## Four batches (three PRs each, strict dependency order)

| Batch slug | Front | Exact queue order |
| --- | --- | --- |
| `delivery-playback-b9-b11-v0` | Playback & Queue | #113 B9 → #114 B10 → #117 B11 |
| `delivery-listening-l9-l11-v0` | Listening & Intelligence | #109 L9 → #110 L10 → #111 L11 |
| `delivery-catalog-c9-c11-v0` | Catalog & Providers | #115 C9 → #116 C10 → #122 C11 |
| `delivery-performance-d6-d8-v0` | Performance & Optimization | #118 D6 → #119 D7 → #121 D8 |

The manifest pins each original full 40-byte hex commit and exact allowed
file set from the live GitHub API, plus its **original branch base** and
`depends_on` inside its batch. All approved changes are under
`app/src/` or `sharedUi/src/`; no workflow, infrastructure, Soulseek or
download code. All four manifests preserve `MAX_ITEMS=3`.

## Explicit exclusions / unresolved work

- **Downloads/Acquisition #63** excluded at the owner's request. No fetch,
  branch preparation, test dispatch, or merge of this front.
- **Playback B13 #133** is a TEST-ONLY CANDIDATE with explicit
  `DO NOT MERGE` in its live PR body, despite prior Android GREEN. Real
  remote quality application and the secure C13/open-time integration are
  still held; this is **not a finished Playback slice**.
- **Listening L13 #127** has cross-front B9/B10 Playback ancestry and
  inherited Playback files in its PR diff. After B9/B10 are merged by
  Playback, the Listening owner must reconcile its branch against the
  protected main and reduce the L13 PR diff to Listening-owned files,
  then re-run exact-head Android checks. Do not merge #127 from its old
  ancestry or permit it in an allowlist that includes inherited Playback.
- Tail slices B12 #129; Catalog C12 #126, C13 #130; Listening L12 #112;
  Performance D9 #123/D10 #124 are not abandoned. They need separate
  manifests **after these parent batches**: the controller's
  `depends_on` is intentionally scoped to PRs earlier *in the same
  manifest*. Child PRs whose parent is already merged in a previous
  batch must be retargeted to `main`, diff and HEAD re-verified,
  exact approved original HEAD updated if changed, and full Android CI
  rechecked. No implicit cross-batch dependency satisfaction.
- **Infrastructure DI4f #101** is separately reviewable and GREEN on
  original head but is not needed to run these four functional waves.
  It does not authorize or automate cross-run merge resume.
- Historical C2 #49 / Listening L3 stay HOLD.

## Operational gate for each wave

1. First publish this manifest-only PR through a normal protected main
   merge after its exact-HEAD Python and Android checks GREEN. Its
   publication **does not merge any target PR**.
2. Reverify main HEAD, each PR live original pinned HEAD, exact scope,
   original base, upstream dependencies, no unresolved threads and
   required check GREEN. Move only the PRs of the selected batch out of
   draft after owner approval; leave future batches untouched.
3. Dispatch [Controlled Integration Queue](../.github/workflows/integration-queue.yml)
   manually on `main`, `batch=<slug>`, `mode=plan`, with the
   **exact live full `main` SHA**. This is GET-only; require `PLAN OK`.
4. Execute only with explicit owner consent for the selected batch,
   `integration-apply` human approval and dedicated GitHub App
   `INTEGRATION_WRITE_ENABLED` gate. No direct/bypass merges or concurrent
   batch execute jobs. The protected queue first rechecks original
   SHA/base/file scope, then retargets stacked children after their
   parent merges, synchronizes branch using GitHub native update-branch,
   requires Android GREEN on exact new HEAD, uses GitHub's protected native
   merge, and requires a successful exact-head main push Android CI after
   **every** merge. Any RED halts the batch; reconciliation comes before
   any later run; never blindly rerun an already partially merged batch.
5. Record `QUEUE_METRIC v=1` per phase on these subsequent deployments
   (the controller from #120/#125 is now on main), comparing queue
   prepare, premerge CI, protected merge and post-main CI durations.
   Independently capture GitHub job `created_at`, `started_at`,
   `completed_at` and attempts to distinguish runner/job waits from
   actual Android task time. The three-lane scheduler (A Catalog and
   Listening; B Playback; C Performance and other PRs) is deployed but
   neither a 3× speedup nor balanced worker use is assumed.
6. Between waves revalidate `main` and post-merge GREEN, original HEADs,
   draft status, contention, CI state and cross-front interfaces. A
   stale manifest or changed original SHA requires a new reviewed
   manifest, **not silent re-pinning**.

This staged plan makes no claim that B13 or L13 are integration-ready, does
not change the app or existing automation safety gates, and does not itself
dispatch either plan or execute. The GitHub App connection in this chat
does not currently expose manual `workflow_dispatch`; the owner must use
GitHub Actions UI for that step.
