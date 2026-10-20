# BitChord integration queue — v0

## Status and defaults

This is a **separate, non-functional infrastructure PR until reviewed and merged**.
It never enables integration by itself. The Actions controller has **no automatic
push/PR/schedule trigger**. Its default manually dispatched mode is **plan**,
which performs GitHub reads only. Its write mode is **disabled unless the
repository owner deliberately configures multiple independent gates**.

The current main branch ruleset remains intact: merge commits only, required
strictly up-to-date `Android · test and build`, unresolved review threads
block integration, and zero bypass actors. This controller does **not** build
or upload APKs. Physical smoke APKs remain explicit/manual by existing policy.
PR #49 (C2) is hard-denied.

The owner has enabled GitHub's `allow_auto_merge` and
`allow_update_branch`. However, **a read-only GitHub Actions token may not
receive these optional flags from the repository metadata API**. The controller
validates exact repository identity and protected-branch ruleset instead of
mistaking missing flags for disabled capabilities. Explicit
`allow_merge_commit=false` remains a blocking condition. Auto-merge
itself is **not used**: this controller uses GitHub's **native update-branch
REST operation followed by a protected exact-HEAD direct merge** rather than
asynchronous `--auto`, because independently completing auto-merge could
bypass its per-merge post-CI serial gate. The actual GitHub endpoints
enforce capabilities and are never bypassed. Do not enable independent
auto-merges on multiple PRs simultaneously.

## Required setup before enabling execute

1. Review and integrate this infrastructure PR **without integrating any
   pending functional slice**; verify the lightweight Python contract tests,
   the Android CI check, and the post-merge main checks. No APK is required.
2. On main, run a **read-only plan** for a reviewed pinned manifest, with the
   **exact current full 40-character main SHA** supplied manually. Never use
   a previously approved SHA after main changes.
3. Create a dedicated GitHub App installed **only on jssantogit/BitChord**.
   Grant only Contents write, Pull requests write and Actions read (plus
   metadata read if automatically granted). It must not be in the main
   ruleset's bypass actors. Configure repository variable
   `INTEGRATION_APP_ID` and secret `INTEGRATION_APP_PRIVATE_KEY`.
   **Never use a regular GITHUB_TOKEN for merging**: its mutations do not
   reliably trigger the main push CI, which is our essential next-PR gate.
4. Create the GitHub Actions environment **integration-apply**, require
   named human reviewers, and restrict it to branch `main`. Verify the
   restriction before enabling the queue.
5. Only after these gates are configured, set repository variable
   `INTEGRATION_WRITE_ENABLED=true`, then authorize the exact batch and
   live main SHA. Missing/false blocks execution. Set back to false to
   disable. The workflow has no schedule and never runs on PR changes.

## Required safe authentication check before any execute

Before enabling `INTEGRATION_WRITE_ENABLED`, verify the GitHub App installation
and the environment approval path using the **separate manual workflow**
[Verify Integration App Authentication](../.github/workflows/integration-auth-check.yml):

1. Keep `INTEGRATION_WRITE_ENABLED` unset or `false` at repository scope.
2. Actions → **Verify Integration App Authentication** → Run workflow →
   branch `main` and `expected_main_sha` set to the live, reviewed
   protected `main` HEAD. The workflow is manual only.
3. The `integration-apply` environment should ask the named human reviewer
   to approve the pending job. An unexpected bypass or no review request
   means stop and inspect environment protection before proceeding.
4. After human approval, the job uses `actions/create-github-app-token@v3`
   with the **same permission set** as the real queue. It makes exclusively
   GET requests to validate the token sees only BitChord, protected main,
   the required active ruleset, PR and Actions read APIs. It never modifies
   branches, PRs, rulesets, secrets or workflows.
5. Require the exact log `AUTH CHECK OK: ... zero writes.`, check the
   workflow status `completed/success`, and independently confirm that
   the protected `main` SHA and relevant PRs did not change.

This authentication check intentionally mints a short-lived GitHub App
**write-capable** installation token, but the workflow performs **only GET**
requests. Approval is still required. A GREEN auth check proves credentials
and read APIs can be accessed, **not** that a real merge or post-merge
gate has been exercised. Keep `INTEGRATION_WRITE_ENABLED` disabled until
there is separate explicit user authorization for the first real integration.

## How to use

GitHub → Actions → **Controlled Integration Queue** → Run workflow:

- Branch: `main` (the script rejects anything else).
- Batch: `pilot-o2` (current reviewed pilot manifest).
- Mode: `plan` (default, reads only) or `execute` (writes are gated).
- Expected main SHA: the **current protected main SHA**, approved for this
  exact execution.

A reviewed JSON manifest under `.github/integration-batches/` pins each
PR's original HEAD SHA, base branch, allowed changed file paths and optional
parent PR number. The controller rejects missing/different HEADs, new files,
unexpected branch base, external forks and draft/closed PRs.

For a stack in a single batch, parent must occur **before** child; after
the parent merges, the controller retargets the child to main and revalidates
its exact diff. For a child in a subsequent batch, normalize and independently
review its new main base before recording a new manifest. Batch size is capped
at 3 PRs; another batch requires another approved current main SHA.

## Exact serial gate

For each authorized PR, the controller:

1. Checks protected main and ruleset (strict status + only merge commits).
2. Checks HEAD, base and diff file allowlist against the reviewed manifest.
3. Uses GitHub's official update-branch endpoint with
   `expected_head_sha` if behind main. Rechecks HEAD, diff and zero behind.
4. Waits for the actual required Android check **completed/success** and
   GitHub mergeable/clean on the resulting exact HEAD. No bypass, disabled
   tests or forced pushes.
5. Calls the protected merge endpoint with `sha=<exact checked HEAD>` and
   `merge_method=merge`. Requires GitHub-confirmed merge SHA to equal
   live protected main HEAD.
6. Waits for the `check.yml` **push** run on the *exact merge SHA* to be
   `completed/success`, and for the required commit check itself to be
   GREEN. **Only then** may the next PR be merged.

### Next-PR preparation overlap pilot (controlled, MAX_ITEMS=3)

After GitHub confirms the current PR's merge and the exact new protected
`main` SHA, the same serial controller may **prepare** the following
manifest-approved PR *before* waiting for post-merge Android CI to finish.
Preparation rechecks the exact reviewed original HEAD/base/file allowlist,
parent dependency, workflow-file sync guard and live protected main SHA;
it retargets and updates the next branch if necessary, which triggers
its ordinary strict required Android PR check. This branch-only
preparation is speculative and may consume a CI runner while the prior
post-main check is RED or pending.

**There is no speculative merge.** The previous `check.yml` main-push
workflow AND its required exact-SHA Android check still must finish GREEN
before the next PR's own exact HEAD/base/diff/check/mergeability can be
revalidated and merged. Any RED, drift, unexpected changed file or timeout
halts. If preparation fails, the controller still verifies the previous
main push before stopping. No background thread, independent writer,
parallel merge, bypass, extra GitHub App permission or replay mechanism
is introduced.

### Phase-level observability (stacked metrics pilot)

The same protected executor emits `QUEUE_METRIC v=1` records with fixed
low-cardinality fields `pr`, `phase`, `outcome` and `duration_ms`.
Phases are `prepare_head`, `premerge_ci`, `protected_merge`,
`prepare_next` and `postmain_ci`. `ok` means that specific phase
returned successfully; `blocked` means an error occurred. Durations are
monotonic, nonnegative and bounded by the controller's total time budget.

The CLI logger attaches a UTC ISO-8601 timestamp and flushes every event
immediately. No auth headers, credential values, media IDs, request payload,
raw GitHub error body, dynamic labels or PR source text are emitted by this
metric contract. The new timings distinguish phase wall-clock waiting from
actual Gradle test execution; use GitHub job timing separately to diagnose
runner wait, cache setup and task execution. In tests, injected logger/clock
give deterministic zero-duration observations.

No performance gain is asserted by emitting these metrics. Compare real
protected-batch runs only after separately approved pilot integration; an
improvement in throughput must be measured without changing mandatory
Android pre- and post-main CI verification.

This pilot does **not** increase `MAX_ITEMS=3`, the 40-minute execution
budget, 50-minute job limit, or the one-hour App token window. Validate
against a separately owner-approved 2–3 PR batch only after this draft
PR is reviewed and integrated with exact-HEAD CI. Measure real wall-clock
batch duration, time from first merge to second PR CI start, runner
queue contention and post-main gates. No speedup or 4+ PR capacity is
claimed before live measurements.

Every surprise, conflict, RED, drift, expiration, timeout or missing CI
**halts** the queue. Post-merge RED does not undo the already merged
commit; a separate reviewed corrective PR must fix that main before
integrations resume. No speculative conflict resolution, rollback or autoplay
changes are performed by this controller.

## Limits and evidence

The execution job lasts at most 50 minutes, while the Python controller
allows a 40-minute total budget. This stays under a GitHub App installation
token's ~one-hour lifetime. Runs are mutually exclusive through the
GitHub Actions concurrency group. External manual merges are not prevented
by that group, but SHA checks detect them and halt the queue.

The included pilot `pilot-o2.json` pins PR #48 at
`8f736da4b1fd50c029bef6254e4b914afd7ed742` and its four specific
diagnostics files. It is **not an instruction to merge O2 automatically**.
Do not select execute until its specific review, dry run, environment approvals
and write gates are complete.

Run local tests with:

```sh
python3 -m unittest discover -s tests -v
```

A separate PR/main workflow runs these contract tests without assembling an
APK or altering the existing required Android check.

## Next proposed two-stage integration window — PREPARATION ONLY

The first real single-PR pilot, **O2 #48**, succeeded through App-authenticated
branch sync, required Android pre-merge CI #581, protected merge
`c986301c836a70e1933eded4c9b5964968a80346`, and main push Android
post-merge CI #582. The new manifests are *not* approvals to run `execute`.

**Do not increase `MAX_ITEMS=3` to fit four PRs.** The controller's job
budget is 40 minutes and its GitHub App installation token is short-lived.
For four pending slices, stage **two separately approved manual dispatches**:

| Batch (run only after the previous batch GREEN) | Exact sequence | Why |
| --- | --- | --- |
| `playback-b2-b3` | B2 #51 → B3 #53 | B3 must follow B2 in the same batch and retarget after B2 |
| `catalog-listening-c3-l2` | C3 #52 → L2 #50 | Independent slices; each must pass its own pre/post Android CI |

Every manifest pins the original SHA and observed exact **stack-only** PR
file allowlist at this checkpoint. Neither batch contains C2 #49. Each
requires separate `mode=plan`, reviewed current main SHA, owner approval
of `mode=execute` through `integration-apply`, and review of all manifest
and live PR checks. The second batch must use the **new** protected main SHA
after the first batch's completed post-merge GREEN.

### Blockers to clear before even running read-only plans

- **B2 #51, C3 #52 and L2 #50 are still draft PRs whose base refs
  are the old B1/C1/L1 stack branches**. Their parent PRs #46/#45/#47
  are merged into main, but those ancestors moved before merging.
  The controller intentionally refuses a non-main `expected_base_ref`
  for a batch root, and refuses draft PRs. Do not attempt execute in
  the current state.
- In a **separate explicit preparation/review step**, retarget only the
  three root PRs to `main` and confirm the same **exact stack-only**
  changed-file sets and original pinned HEADs. Check that their content
  contains no reverted upstream parent changes or regressions. Review
  semantic readiness (especially L2 canonical navigation and L2 Robolectric
  tests) before marking those roots ready for review. If retarget creates
  additional files, conflicts or changes the HEAD, **stop and revise the
  manifest and tests in an independently reviewed infra PR**; never
  silently expand the allowlist or bypass required tests.
- **B3 #53 intentionally stays based on B2's branch at the initial gate**.
  It also starts draft. Its current PR stack-only diff contains three
  files, while a naive comparison of B3 to current main includes six B2
  files too. Retargeting B3 to main *before* B2 merges would violate
  isolation. In the Playback batch, the controller checks B2 has merged
  first, then retargets B3 to main, rechecks exactly three B3-only files,
  syncs its branch and requires fresh CI.
- Only after the review and independent explicit approval of both
  manifests should the infrastructure PR containing them be merged.
  Verify infra post-merge Android CI GREEN before dispatching any batch.
  No automatic trigger, self-merge, skip, forced push, broad App scope,
  or review bypass is permitted.
- `INTEGRATION_WRITE_ENABLED` may still be **true** from the O2 pilot:
  that variable is owner-managed and not introspectable via the current
  connector. Return it to `false` outside the expressly supervised
  execute windows; a future plan must never be treated as write approval.

These manifests and choices merely expose selectable **future** batches;
they do not change live functional PR bases, their draft status, branch
contents or main on their own.
