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
`allow_update_branch`. This v0 intentionally uses GitHub's **native
update-branch REST operation followed by a protected exact-HEAD direct merge**
rather than asynchronous `--auto`, because an independently completing
auto-merge could bypass the controller's per-merge post-CI serial gate.
Do not enable independent auto-merges on multiple PRs simultaneously.

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
   GREEN. **Only then** does the next PR become eligible.

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
