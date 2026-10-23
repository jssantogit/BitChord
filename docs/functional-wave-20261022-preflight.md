# Functional integration wave 01 — reviewed, pinned, no merge

**DRAFT / NO MERGE / NO AUTOMATIC WORKFLOW EXECUTION.** This is an inert manifest and tests to prepare a separately owner-approved **three-PR** batch through the protected serial queue. It is not an approval to mark functional PRs ready, alter HEAD/base, or invoke `execute`.

## Immutable read-only checkpoint (2026-10-22)

- Protected `main=8a5f9925ea785925ecc56a53590c3e8eefdc9bfd`.
- The manifest `.github/integration-batches/functional-pending-20261022-wave-01.json` pins three independent, currently **DRAFT** functional PRs, whose exact SHAs, original bases and file lists were re-read from GitHub. All three have `behind_by=0`, GitHub mergeable true and **zero unresolved review threads** at staging, and their file sets have no intersections.
- #138 [D11 Journey Taxonomy](https://github.com/jssantogit/BitChord/pull/138): exact `15f7f5058f66e723b0d869b5505be0c829b56b91`, 2 diagnostics files, Android CI run #39682668776 SUCCESS.
- #139 [L14–L15 My Ratings UI](https://github.com/jssantogit/BitChord/pull/139): exact `32ddc678ecd6fd9f1fa53446a8342dfe2a4e547d`, 5 Listening UI/resources/tests, Android #39683291597 SUCCESS. **Physical light/dark and header verification still required after integration**; code-level Compose test does not prove pixels.
- #141 [C14–C18 Remote Ratings](https://github.com/jssantogit/BitChord/pull/141): exact `10d2e43137b3317b7e75212e91d45bee29154268`, 16 Catalog-only source and tests; Android #39686657076 SUCCESS. Includes Room `canonical-catalog.db` migration v3→v4. Full real post-merge Android tests and later actual Navidrome/on-device coverage are required; no write-back allowed. **Do not conflate existing server ratings with local fractional ratings**.
- The latest functional PRs are not automatically ready merely because historic CI was GREEN. Protected strict checks may need refreshed branch HEADs after prior merges. Actual `execute` stops on drafts, exact SHA drift, unexpected files, conflicts, RED or unknown checks, changed main, or approval gate absence.

## Real queue baseline (not simulated)

Latest protected queue run [#39624352569](https://github.com/jssantogit/BitChord/actions/runs/39624352569), job #119305746848, integrated #127 and #133 with `QUEUE_METRIC v=1`:

| Exact metric | Elapsed |
| --- | ---: |
| #127 `prepare_head` | 24.217 s |
| #127 `premerge_ci` | 236.922 s |
| #127 `postmain_ci` | 322.462 s |
| #133 `prepare_next` | 23.392 s |
| #133 `premerge_ci` | **1,163.524 s / 19m24s** |
| #133 `postmain_ci` | 322.638 s |

The controller went from the first measured event at 15:18:44 UTC to `BATCH GREEN` at 15:53:22 UTC, around 34m38s. That is a valid reference for queue delay, **not** a prediction of this three-PR batch. The huge #133 premerge wait is the main suspect; compare queue runner delay separately from Gradle duration and cache behavior, not merely test wall time.

## Promotion gates — separate owner authorization required

1. Obtain **explicit owner approval for precisely #138 → #139 → #141**, the versioned manifest, and a fresh live `expected_main_sha`. Instruct owners to acknowledge remaining device/Room caveats; no unauthorized claim that those device gates already passed.
2. Review the *three* exact diffs, required Android CI on each checked HEAD, review threads and clean mergeability again. After authorization, mark only the three approved drafts ready. Any branch/commit changes make original pins stale; regenerate and re-review the manifest. No force pushes or hidden conflict resolutions.
3. The manifest must be independently reviewed and merged to protected `main` via the existing approved path **before** Actions can read it in a manual workflow run. Since this bootstrap changes `main`, re-pin `expected_main_sha` to that new exact protected SHA before launching the wave and reconfirm each branch status. Do not attempt to dispatch from the batch PR branch.
4. GitHub Actions → **Controlled Integration Queue** → choose `main`, batch `functional-pending-20261022-wave-01`, mode **plan**. Require `PLAN OK: read-only, zero writes`; if drafts remain, a blocked plan is expected and not a permission to bypass.
5. With a separate approved exact main SHA, active `integration-apply` human review and write gate, dispatch **execute**. Preserve `MAX_ITEMS=3`, same full Android test command, strict post-main exact SHA, fail-closed no skip. Do not use a direct GitHub merge to jump ahead of queue verification.
6. Collect job start/end timestamps and all `QUEUE_METRIC` records. Define total wall duration from executor first event to `BATCH GREEN` plus any queued workflow launch; for each PR compare premerge CI wait, postmain CI wait, speculative `prepare_next` overlap and Gradle task runtime. Report **savings measured against the historic ~34m38s 2-PR reference**, stating workload differs and not asserting causality without matching samples. A repeated production improvement must persist across more than one sequence.

## Subsequent dependencies (not authorized in this manifest)

- [#147](https://github.com/jssantogit/BitChord/pull/147) Listening L16–L18 is **stacked on #141** and carries copies/evolutions of files from #139. After #139 and #141 integrate, perform a non-destructive ownership/diff reconciliation and exact-head CI; do **not** silently retain copied duplicate changes or merge this draft before its explicit physical gate.
- Performance D12 #140 → D13 #142 → D14 #143 → D15 #144 are stacked on D11 #138. Once #138 merges, normalize their bases and re-audit each exclusive diff before creating subsequent reviewed batches, respecting the three-item limit. Do not treat an existing child branch base as `main`.
- Playback B14–B18 [#145](https://github.com/jssantogit/BitChord/pull/145) has exact Android GREEN but real audio/decoder physical verification is partial. Keep it separate until owner explicitly accepts that risk. Acquisition A0 [#63](https://github.com/jssantogit/BitChord/pull/63) is a feasibility scaffold and active Downloads work is progressing separately; exclude from this wave. Infra experiments #148–#158 are not part of functional batches.
- APK smoke remains explicitly triggered **after** integration; the queue does not build or install APKs.

No change to functional code, caching policy, protected workflows, authorizations or integration queue implementation is made by this staging PR. Closing this staging PR is a safe rollback if wave membership changes.
