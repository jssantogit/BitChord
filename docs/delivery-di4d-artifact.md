# DI4d — GitHub Actions checkpoint artifact provenance (read-only)

DI4a local journal, DI4b live reconciliation and DI4c portable evidence remain the source contracts. This DI4d slice adds **cross-run artifact provenance checking**, not durable merge authority.

## Safety contract
- A caller supplies an independently reviewed run ID, run attempt, protected-main commit SHA, artifact ID, and exact SHA-256 of the **inner DI4c evidence bytes**, plus the reviewed manifest. Never trust a digest embedded in the same artifact as sufficient independent evidence.
- GitHub REST reads use only `GET https://api.github.com/repos/jssantogit/BitChord/actions/runs/<run_id>` and its bounded artifact collection. The adapter cannot call POST/PUT, cannot follow artifact download URLs, and never prints GitHub API errors or tokens.
- A trusted source must be a successful, completed `workflow_dispatch` run on `main` of `.github/workflows/delivery-checkpoint-evidence.yml` in the same repository and with the exact source run ID, attempt, and SHA. Reject forks, failed/in-progress runs, wrong workflows, SHAs, or attempts.
- Artifact index must contain exactly one unexpired `di4-checkpoint-evidence` object, with exact artifact ID and bounded archive metadata; reject missing/duplicate/corrupt records, pagination ambiguities and oversized payload.
- Use a trusted read-only mechanism such as `actions/download-artifact@v4` to extract the named artifact into a clean directory. Then pass **only the single DI4c JSON evidence file** to the verifier. Never follow URLs from metadata and never run code embedded in artifacts.
- `inspect_evidence` (DI4c) rechecks canonical SHA-256, journal integrity, manifest/head pins and source identity. Returned result **always** sets `approval_required=true`, `executable=false`, `replay_merge=false`, and `decision=require_live_revalidation`.
- Treat GitHub Actions artifacts as a bounded, retention-limited cross-run **transport**, not an authoritative durable CAS database. Reruns, deleted/expired artifacts, a superseded main, ambiguous merges or revoked approvals must halt. No automatic fallback to older runs.

Example after separately obtaining the exact artifact bytes and reviewed pins:

```bash
python3 -m tools.delivery_artifact \
  --repo jssantogit/BitChord \
  --manifest .github/integration-batches/<reviewed-batch>.json \
  --content /tmp/di4-evidence/delivery-checkpoint.json \
  --run-id <reviewed-run-id> --attempt 1 --artifact-id <reviewed-artifact-id> \
  --expected-main-sha <source-main-sha> \
  --sha256 <independently-pinned-evidence-sha256>
```

Use read-scoped `GITHUB_TOKEN` for the run metadata/index. No token is needed to verify already acquired bytes when injecting a reader.

**This PR does not upload artifacts or change the controlled integration workflow.** A follow-up manual read-only producer/consumer workflow can validate real cross-run transport. A future protected writer must still prove durable CAS, idempotency, uncertain-merge handling, human approval and exact post-merge Android CI; it must never treat this artifact as authorization to merge.