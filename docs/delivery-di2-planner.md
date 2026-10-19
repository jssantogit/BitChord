# DI2 — Integration Planner v1 (read-only)

This planner consumes a **previously collected, first-party snapshot**. It has no GitHub token, no network calls, no side effects, and no authority to approve manifests or run the queue.

```bash
python3 -m unittest tests.test_delivery_planner -v
python3 tools/delivery_planner.py --input snapshot.json
```

Input: `main_sha` (exact 40 hexadecimal characters) and `pull_requests` with `number`, `head_sha`, `head_ref`, `base_ref`, `files` (stack-only paths compared with each PR's **actual base**, not automatically a main diff), `draft`, required check state (`success|pending|failure|missing`) and GitHub mergeable state (`clean|dirty|behind|unknown`). The collector must preserve provenance of these fields and never guess missing statuses.

Output: deterministic, ordered dependency graph, typed blockers, exact HEAD/diff scope and an **unapproved** at-most-three-item manifest proposal compatible with the existing controller. Dependency edges are based only on exact base branch refs. Cycles, unknown parents, drafts, stale bases, missing/pending/failed checks, mergeability uncertainty and independent file overlaps do not become approvals. No title/name heuristics or speculative safety claims. An eligible PR may still have non-file conflicts or become stale: only protected execution's live checks determine mergeability.

Read-only *proposal* is not an approved manifest. An operator must independently compare current GitHub PR state, review the diff, accept the exact SHA/allowed paths and authorize execution through the existing `integration-apply` environment. Proposals never add workflow dispatch choices automatically or change the `main` ruleset.

### Next slice

DI3 will take these immutable results and perform bounded parallel GET-only preflight via injected, explicitly read-only data access. No PR changes, no branch sync, no merges.

### First-party live collection

The separate `tools/delivery_snapshot.py` collector safely bridges current public GitHub REST state into the DI2 snapshot contract with GET-only permissions:

```bash
python3 -m unittest tests.test_delivery_snapshot -v
python3 tools/delivery_snapshot.py --repo jssantogit/BitChord > /tmp/bitchord-open-pr-snapshot.json
python3 tools/delivery_planner.py --input /tmp/bitchord-open-pr-snapshot.json > /tmp/bitchord-plan.json
```

The collector enumerates all open PR pages, reads authoritative detail HEAD/base/draft/mergeability, changed files (bounded to ≤100), required Android checks, re-reads the PR HEAD and verifies protected main SHA before and after the batch. Inconsistent data, missing/duplicate checks, untrusted fork heads, incomplete pagination or a moved main fail closed. The output omits titles, actors, comments, raw API errors and secrets; keep it local, do not commit rapidly stale real snapshots as fixtures. It does not have write methods.

Mergeability values `blocked`, `unstable` and `draft` are now explicit blocked categories, consistent with actual GitHub responses. The collector and Planner are observational only; draft PRs remain ineligible. **The source snapshot is not a reviewed manifest**, even if the Planner yields potential candidates.
