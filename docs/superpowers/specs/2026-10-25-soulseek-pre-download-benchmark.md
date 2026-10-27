# BitChord vs Noqlen Flux — Pre-download Benchmark Protocol

**Date:** 2026-10-25  
**Status:** Protocol approved by autonomous design authority; **NO measured results yet**  
**Design:** docs/superpowers/specs/2026-10-25-soulseek-pre-download-design.md  
**Issue:** https://github.com/jssantogit/BitChord/issues/177  
**Comparator:** https://github.com/jssantogit/noqlen-flux-core — reference main SHA observed 2026-10-25: 3e6fa935fc139ed80e4e75ae3aa7c88ebd36747d. Pin this exact SHA + default_v1 profile for the first frozen comparison (or document a newly agreed SHA **before** evaluation); current results remain **unmeasured**.

## Why two experiments

Flux currently uses a Python candidate scorer and a slskd provider adapter. BitChord Android talks directly to Soulseek native peers. Different peer responses or network timing can confound the quality of the ranking itself.

**Experiment A — identical offline manifest:** Compare deterministic ranking and typed incompatibility/review decisions over **the exact same candidate claims**, with identical expected canonical intent and technical preference. This isolates candidate analysis. No network or download required.

**Experiment B — Android live discovery:** Separately observe how reliably and quickly BitChord gathers peers on a real device; optionally compare with Flux/slskd in a same-session controlled setup where practical. Do not compare live ranked lists as if all systems received the same population. Record environmental differences, connection mode, locale, Soulseek search attempts and individual search time budgets. No unauthorized peer scraping or audio downloading.

The comparator must be pinned to actual source SHA/profile, not an undocumented notion of "Flux": 
- src/noqlen_flux/search.py defines SearchQuery, SearchCandidate, SearchProviderResult with response_count, timeout_reached;
- src/noqlen_flux/services/scoring.py defines explainable components;
- src/noqlen_flux/scoring.py defines DEFAULT_SCORING_PROFILE default_v1 with artist=20, title/album=20, textual=40, folder=10, quality=5 and availability=5; this is a score, not proof of correct recording.
- src/noqlen_flux/providers/slskd.py isolates the slskd search adapter from the provider-independent scorer.

Pin commit SHA, config/version and any override before first run. Benchmark should not claim that an old Flux profile represents its current state indefinitely.

## Ground truth and privacy

Each test query is an independently annotated **intended recording + release/edition + version**, ideally with MusicBrainz Recording MBID and Release MBID, track position, release-group MBID and artist aliases. MusicBrainz linkage establishes reference context, **not the content identity of a remote peer's files**.

A candidate receives one reference label:
1. EXACT_EDITION: independently verified correct recording and requested edition, where edition can genuinely be established.
2. CORRECT_RECORDING_EDITION_UNKNOWN: known same recording but release/master not provable.
3. WRONG_EDITION_VERSION: demonstrably different album/remaster/edit/remix/live/instrumental etc.
4. WRONG_ARTIST_OR_RECORDING: a different performance or song.
5. TECHNICALLY_INELIGIBLE: fails explicitly required supported codec/quality or safety gate.
6. UNKNOWN_UNVERIFIABLE: insufficient evidence to determine correctness.
7. NO_OFFER: there were no results in the given bounded search (search outcome, not file label).

Do **not** label an unaudited Soulseek filename EXACT_EDITION. Use independently licensed/owned reference copies with verified metadata (and actual audio inspection where lawful and needed) or auditable evidence; never move/ship a peer's binary file or credentials into the repo. For unavailable peer truth, use UNKNOWN_UNVERIFIABLE, not WRONG. Album edition claimed only in directory remains a **claim**, not independently confirmed truth.

Two annotators label cases blind to algorithm result. Record agreement rates; conflicts are adjudicated by a third party or held out as UNKNOWN. Gold set is frozen before benchmark run. All labels must record an evidence class and any uncertainties. Store sensitive labels and original peer paths only in private, access-controlled research fixtures as allowed; public test fixtures use invented peer names, synthetic paths and no raw query/player activity logs. Official app diagnostics stay aggregated and count-only.

## Dataset: 120 real-intent queries + adversarial synthetic regression

**Target collection size (not currently completed): 120 independently sourced musical intents**, stratified into:
- 20 ordinary album-studio tracks with a confirmed release target.
- 20 version collisions (remaster, remix, live, edit, acoustic, instrumental, cover).
- 20 exact-album and exact-edition conflicts (same recording across releases; compilation vs main album; deluxe vs original).
- 20 artist/credit collisions (featured artists, renamed artists, title overlaps, homonyms).
- 20 ambiguous or incomplete evidence (title-only, unknown release, missing duration/codec, weak folder evidence).
- 20 format/quality and availability conflicts (lossless claims, codec/container ambiguity, impossible size/bitrate, multiple peers, locked/slow sources).

Within each stratum allocate **10 calibration/development and 10 sealed holdout intents** (60+60); keep multiple peer offers per intent if available. The 70 existing synthetic v1–v4 fixtures stay as separate unit regressions, not as 70 real gold cases. Freeze and hash the real holdout before tuning thresholds. Report actual completed sample N, not projected 120 as achieved.

Require both positive and negative cases: a query for which the right file is available; a query with only wrong versions; and a query with credible-but-inconclusive files. One can only compute recall when the denominator has a verified correct candidate **among the exact input offers**.

## Normalized schema (JSONL; controlled private manifest)

One request object per line:
- case_id (opaque), stratum, split, query_intent: target MBIDs, artist/title/album, required exactness, version, duration/tolerance, quality_profile_id;
- canonical_resolution: result (confirmed/unresolved/ambiguous/unavailable), provenance class and snapshot version (no API secrets);
- observations: array of offer_id (opaque), basename/path tokens only in private fixture, format/container/codec claim, bitrate, duration, sample rate/bit depth, size, advertised queue/free slot/speed, and normalized claim hash; always provenance="peer_declared";
- ground_truth per offer: label, independent provenance/evidence class, adjudication state; no actor/user ID;
- acquisition metadata: collection mode=offline/live, search_attempt, search budget, response_count, timeout_reached, cancellation, environment and policy/commit hashes.

For public evidence export ONLY aggregated error counts and deliberately synthetic file paths. Generate deterministic, resettable fixtures, anonymize stable real references across runs if possible, protect any recordings under user rights. No emails, credentials, Soulseek usernames, socket addresses or full real remote paths in public build/test logs.

## Experiment A procedure

1. Freeze BitChord commit + policyVersion, Flux commit + scorer profile/config, catalogue snapshot, track identity, quality profile and gold split.
2. Convert **identical normalized offers** to BitChord CandidateEvidence and Flux SearchCandidate; preserve semantically available fields exactly. If a feature is unavailable in Flux, record **not supported**, do not secretly inject an artificial matching signal. Run both scorers without network or transfers.
3. Emit deterministic per-case outcome: ranked offer IDs, first strong/unverified recommendation if system has that concept, inconclusive/rejected flags where available, reasons, confidence/score (model-specific; never compare raw scale) and whether the manual list retains candidates.
4. Compute **conditional precision@1** = verified correct first recommendations / all first recommendations with independently settled ground truth. Report unknown-outcome sample count separately: never omit UNKNOWN silently to inflate precision.
5. Compute **recommendation coverage** = queries receiving an actionable top candidate / all queries; **correct coverage** = queries with a verified right candidate returned as first / all queries with a verified right candidate present.
6. Compute **recall@1 / recall@5** only on queries with at least one known right offer in the input set. Keep exact-edition recall distinct from recording-only recall.
7. Compute **wrong-version promotion rate**, **wrong-artist promotion rate** and **false-strong / abstention appropriateness** on cases with independent truth. A claim the user did not authorize is not a false negative merely because it is high bitrate.
8. Score explicit uncertainties: Brier/calibration only if model yields an actual calibrated probability; a heuristic "confidence" label or Flux total score is **not** a probability. Instead provide confusion matrices and rank bins.
9. Report per-stratum, micro and macro totals with Wilson confidence intervals for proportions, paired per-query wins/losses/ties; compute 95% paired bootstrap intervals when enough cases exist. Do not tune on the sealed holdout.
10. Replay shuffled equivalent offers for deterministic ordering and check ranking invariance, source tie-breaker isolation, cancellation and output redaction.

## Experiment B procedure (real Galaxy A54, opt-in smoke separately authorized)

- Control device, network transport (Wi-Fi vs mobile), account, app build, warm/cold canonical cache and reproducible queries; *do not* assume peers still publish the same data between runs.
- At least three runs per allowed test query/session in alternated order, subject to actual budget/consent; record only aggregate per-run counts and elapsed phase timings in public diagnostics. Cancellation must stop readers and clear state.
- Measure response_count / candidates admitted / duplicate offers / cap drops / no-slot / timeouts / per-peer pressure, fraction of no-peer replies and rank stability **conditional on observation overlap**.
- Measure end-to-end request-to-ranked-results elapsed time p50/p90/p95 and fraction of searches with correct independently verified candidate visible (when ground truth exists).
- Report BitChord native-client discovery separately from slskd provider polling. If live baseline unavailable, publish as **BitChord discovery only**, not "Beat Flux in discovery".
- Never attempt automated audio downloads to prove preflight. Galaxy A54 manual smoke remains a separate owner-controlled gate.

## Success metrics, order and target-setting policy

The owner selected a **balanced precision/coverage/latency objective**, with strict correctness of explicit album/version requirements.

**Immediate non-numeric hard gates (testable now):**
- Zero intentional promotion of **known, affirmative wrong-version/wrong-artist/forbidden-format** gold candidates to STRONG on deterministic safety fixtures. Each discovered counterexample becomes a versioned regression.
- A plausible but unverifiable edition remains inconclusive/selectable by hand, never silently STRONG/confirmed and never silently rejected solely because album text is absent.
- No peer availability or bitrate may overturn a higher-priority identity incompatibility. No simulated rank or preset initiates P/F.
- Safety, redaction, bounded search, other provider preference migration and reproducible benchmark manifest checks remain PASS.

**Numeric release gates are deliberately NOT invented before baseline.** After collecting an adequate development sample:
- Establish observed BitChord/Flux precision@1, correct coverage, wrong-version promotion and p50/p95 for the **same** offline manifest; quote counts and confidence intervals.
- Propose pre-registered improvement goals for the sealed holdout, preserving the owner's stated balance: fewer serious identity errors than Flux at a comparable recommendation coverage, improvement or at least non-regression in recall/correct coverage, and bounded acceptable tail latency agreed with product constraints. Never mask a large coverage drop by reporting only conditional precision.
- Choose acceptable search-budget defaults and "search longer" range from the observed Android latency distribution and resource envelope; the owner's willingness to wait does not authorize unbounded delays.
- Fail evaluation if one adverse stratum (notably explicit wrong album/remaster) is masked by excellent ordinary cases. Present paired cases where Flux won, BitChord won and both abstained.

Any statement "BitChord beats Flux" must specify the measured dimension, sample size, confidence interval, comparator pinned SHA, input parity and date. No metric is authorized to trigger auto-download; that belongs to #184.

## Exit criteria for benchmark readiness

- The selected canonical ID/read-only #178 boundary is confirmed in Android and mockable without network.
- The frozen normalized manifest schema parses, sanitizes and replays identically through both scoring adapters.
- 120 intents are actually annotated (or the completed smaller N and limitations are reported without extrapolation); annotator disagreements are documented.
- The offline comparator passes leak checks, identity/quality invariants and seeded shuffle tests.
- A documented owner-approved live Android smoke procedure exists for discovery/timing; nothing has been executed automatically.
- Pre-registration records the trial version and planned reporting table *before* reading sealed holdout scores.

## Canonical report template

- Environment: BitChord SHA/policy; Flux SHA/profile; canonical snapshot; Android model/network; timestamp
- Dataset: N total, N labelled per stratum, N UNKNOWN, N sealed holdout, evidence quality/agreement
- Ranking: first recommendation precision (exact edition / recording-only separately); wrong edition count; top-5 recall; recommend/correct coverage; abstention truth breakdown
- Search: p50/p90/p95 elapsed; no peers, no eligible files, timed out; mean peer responses; rank overlap by runs
- Safety: forbidden version false-strong counterexamples; transfer gate unchanged; logs redacted; format policy violations
- Pair comparison: wins/losses/ties at same input, CIs, known limitations and remediation decisions
- Conclusion: supported claim / not supported / cannot evaluate, never an unsupported "win"

## References
- https://github.com/jssantogit/noqlen-flux-core/blob/main/src/noqlen_flux/scoring.py
- https://github.com/jssantogit/noqlen-flux-core/blob/main/src/noqlen_flux/services/scoring.py
- https://github.com/jssantogit/noqlen-flux-core/blob/main/src/noqlen_flux/providers/slskd.py
- https://musicbrainz.org/doc/MusicBrainz_Database/Schema
