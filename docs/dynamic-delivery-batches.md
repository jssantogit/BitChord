# Dynamic batch dispatch and future delivery CI triggers

The existing *Controlled Integration Queue* remains a manually dispatched, protected-main-only controller with **no automatic merge** and no change to `MAX_ITEMS=3`. Its `batch` input now accepts a string rather than a hardcoded Actions dropdown. The controller still rejects unsafe batch names, and loads **only** a JSON manifest committed to `.github/integration-batches/<slug>.json` in its protected-main checkout. It verifies exact PR HEAD/base/diff, requires non-draft PRs, an active ruleset, owner opt-in, dedicated App token and a human-approved `integration-apply` environment for `execute`. This is not permission to merge any PR.

The lightweight Python CI path filters now match future `tools/delivery_*.py` and `tests/test_delivery_*.py` files. This reduces routine changes to the shared `integration-queue-tests.yml` workflow and mitigates the recurring GitHub App workflow-file update-branch 403 during sequential batches.

## Batch preparation, not approval
1. Complete DI4a #90, DI4b #91 (stacked on #90), DI5 #92 and DI6 #93 CI and security review. These PRs are drafts until independently released for review.
2. Remove their now-redundant additions to the shared lightweight CI workflow **after this bootstrap is on protected main**. Preserve and rerun Python/Android CI on their exact new HEADs. Retarget and revalidate the #90→#91 stack; never use a manifest pinned to superseded SHAs.
3. Submit a **separate reviewed infrastructure PR** with exact manifests for bounded batches, for example `delivery-foundations` (#90→#91) and `delivery-reliability` (#92→#93). Do not exceed 3; do not make unrelated feature PRs executable. Overlapping independent files or stale refs must be resolved and checks rerun *before* approval.
4. When the manifest PR is integrated and its main push Android CI is GREEN, run the queue from `main` with `mode=plan`, exact current protected-main SHA and the reviewed slug. A GREEN plan does not authorize execution.
5. After a fresh independent review and the existing owner/environment write gates, execute the approved batch through the **queue**, not direct individual merges. The controller will perform one protected merge at a time internally, waiting for postmerge Android GREEN before proceeding. Recheck a *new* main SHA for the next batch.

**Bootstrap caveat:** this PR changes the manual queue selector itself, so the queue cannot dispatch a batch for this PR until this change is merged into main. It needs an individually reviewed protected bootstrap merge once. Thereafter further infrastructure manifests do not need new workflow `choice` options.

**Not included:** removing owner/environment approval, widening GitHub App permissions, dispatching Actions runs, changing main rules, granting DI4 write authority, or integrating functional slices.
