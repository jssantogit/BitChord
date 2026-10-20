# DI4e — Cross-run checkpoint evidence pilot (manual, no merge authority)

DI4a/b/c/d provide the local checkpoint, inert portable envelope and strict remote artifact provenance checks. This workflow demonstrates **real persistence across two independent GitHub-hosted runners** using temporary GitHub Actions artifacts. It does **not** create an authoritative durable state database and does not change the Controlled Integration Queue.

## Producer: manual, protected-main only

On the merged workflow under **Actions → DI4 Cross-run Evidence Pilot → Run workflow**, use:
- Branch `main`; `mode=export`
- `batch_slug` = a reviewed slug already committed on protected main (for example `infra-di4-foundations`; historical closed PRs are fine for **transport testing only**)
- `expected_main_sha` = exact current main SHA

The job requires the selected ref to be `refs/heads/main` and the submitted SHA to match `GITHUB_SHA`. It writes a temporary private journal with `approval_required=true`, `executable=false`, exports the DI4c envelope including its immutable Actions source identity, prints the inner evidence SHA-256 in the **run logs**, and uploads a single `di4-checkpoint-evidence` artifact with **one-day retention**. Save the successful run ID, run attempt and exact printed digest in a trusted review record; inspect the run's artifact list to obtain the artifact ID. The run must finish SUCCESS before inspection.

## Consumer: a separate later run

Launch the same workflow on `main` with `mode=inspect`, same reviewed batch and *same exact main SHA*. Enter the **source** run ID, source attempt, artifact ID and independently copied SHA-256. `actions/download-artifact@v4` downloads only the named artifact by exact run ID. The DI4d reader calls only GitHub GET endpoints to recheck source repository/workflow/attempt/ref/event/SHA/finished-success and one unexpired artifact with pinned ID. DI4c independently verifies the **inner envelope bytes**, pinned manifest and journal integrity.

On success the consumer prints only:
- `transport=trusted_metadata_and_pinned_digest`
- `decision=require_live_revalidation`
- `approval_required=true`, `executable=false`, `replay_merge=false`

**Deliberate failures:** different main SHA, wrong source run/workflow/attempt, tampered or expired artifact, wrong digest, duplicate artifact, source in progress/failed, unexpected manifest fields, extra/untrusted origin metadata. Failures never fall back to another run or become execution permission.

## Operational and security limits

- These runs do not call merge, create/update a PR, write Git refs, dispatch the protected queue, access the Integration App private key or enable integration writes.
- `actions/upload-artifact` writes only to time-limited artifact storage; GitHub read permissions are otherwise `contents:read`, `actions:read`.
- A SHA-256 copied from source-run logs is an *operator pin*, not a cryptographic signature or tamperproof independently authenticated approval. A maliciously modified source workflow or compromised runner could generate its own coherent evidence. The trust anchor is a **reviewed workflow already on protected main**, plus external owner review and live ref/check revalidation.
- Artifacts are immutable for one run and have bounded retention; they are **not a cross-run CAS journal**. Nothing prevents separate runs from producing competing evidence. An authoritative, authenticated durable store with compare-and-swap and crash-safe exactly-once merge adjudication still needs a distinct design, review, red/green tests and a protected owner approval.
- Do not issue `execute` from this pilot or infer that successful evidence validation can authorize merges. The existing protected Integration Queue remains the only accepted integration executor.

Regression tests: `python3 -m unittest discover -s tests -v`. A separate **live two-run export → inspect validation after integration** is required before claiming transport proof.