# Identity-First Soulseek Pre-download — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Deliver an Android-only canonical MusicBrainz identity-first, explainable Soulseek preflight with strict edition/version gates, safe quality presets and reproducible measurement against Flux, without initiating automatic transfers.

**Architecture:** Keep a pure immutable candidate policy independent of catalogue network, Android Compose and P/F. Inject a typed read-only canonical intent and typed evidence; evaluate compatibility before optional quality filtering or source availability. Benchmark identical observation manifests offline and native search separately.

**Tech Stack:** Kotlin/Android (existing Compose, Room, coroutine, JUnit4 and Gradle); existing MusicBrainz provider #178 once stable; optional Python CLI limited to offline developer benchmark against pinned Noqlen Flux source.

**Spec:** docs/superpowers/specs/2026-10-25-soulseek-pre-download-design.md  
**Benchmark:** docs/superpowers/specs/2026-10-25-soulseek-pre-download-benchmark.md

## Global Constraints

- **Scope Android only**; no Web/Desktop daemon stack or slskd dependency in the shipped application.
- One coherent issue #177 PR (prefer existing draft #181 if still unmerged). Tasks are engineering/checkpoint steps **inside the same PR**, not independent stacked releases.
- Do not merge, run physical phone operations or claim live correctness without owner/orchestrator authorization.
- No auto selection, scheduling, background downloads or non-manual transfer: #184 remains default-OFF future work.
- Preserve existing P/F transfer gate, private credential handling, local SAF/publication, playback, other providers and #178 canonical owner.
- MusicBrainz is reference identity, not acoustic/peer audio proof; no STRONG for unsupported album/edition evidence.
- Peer filename, username, remote path, credentials, query, socket address never leave private ephemeral search context or enter public logs.
- Enforce upper bounds and cancelability on all search/lookup flows; avoid unbounded network, memory and repeated retries.
- No fixed accuracy claims or invented numeric launch targets until an independently labelled, frozen dataset supplies a baseline.
- Prior CI #40196319003 SUCCESS corresponds to preflight-v4 HEAD b86aac8579f30d8763492e4c7c6ce8b248b11595; any newer HEAD must revalidate Android CI before readiness.

## Ownership and file map (planned, names subject to no-conflict check)

Read-only canonical adapter:
- Create app/src/main/java/com/music/bitchord/acquisition/a0/A0CanonicalAcquisitionIntent.kt — immutable structured request and typed lookup status.
- Create app/src/main/java/com/music/bitchord/acquisition/a0/A0CanonicalAcquisitionResolver.kt — adapter consuming only stable #178 identity contract/MBIDs.
- Modify app/src/main/java/com/music/bitchord/download/SoulseekDownloadRouting.kt and the existing Android entry point in MainActivity.kt **only for immutable acquisition context**.

Pure policy:
- Create A0CandidateIdentityEvidence.kt — provenance, explicit unknown/ambiguous values and normalized offers.
- Create A0CanonicalIdentityPolicy.kt — strict artist/album/edition/version status independent of numeric score.
- Create A0DownloadQualityPolicy.kt — codec-aware format/bitrate/rate/depth requirements and preset structure.
- Create A0CandidateDecision.kt — typed final verdict, ordered explanations and secondary musical scoring.
- Modify A0CandidateRank.kt, A0CandidatePreflight.kt, A0CandidateClaimClusters.kt, A0PeerAvailability.kt only to bridge/deprecate obsolete score use without breaking earlier frozen regression packs.
- Keep A0ObservedCandidateHandoff.kt manual-only, with a safe immutable snapshot of typed decision data; never add transfer authority to A0CandidateDecision.

UI/preferences:
- Modify data/settings/AppSettings.kt and ui/screens/SettingsSheet.kt for compatible persisted preset/custom settings, with migration.
- Modify the devDebug A0ManualLoginActivity only to show human-readable top candidate reasons and manual override. Preserve one list.
- Modify A0SmokeTrace.kt only for count-only policy/decision evidence within StructuredTrace's 24-attribute cap.

Benchmark:
- Create tests under app/src/test/java/com/music/bitchord/acquisition/a0/ for all policy/resolver/settings invariants; reuse existing v1–v4 frozen suites.
- Create docs/benchmarks/soulseek/SCHEMA.md + synthetic examples with no real peer names.
- Create an offline replay runner (JVM test harness) under app/src/test/.../a0 and optional developer-only Flux adapter under tools/benchmarks/soulseek/; ship no Python runtime in Android.

## Review Focus — five highest-risk inputs

1. "Queen — Queen of Hearts" in a folder called Queen of Hearts: must not take artist credit from title or album phrase alone. Test in Task 3.
2. Exact original 1994 CD Release vs later remaster in same Release Group/Recording: **must not** become STRONG solely via shared Recording MBID. Test in Task 2/3.
3. A 192-kbps M4A/ALAC/FLAC/WAV request: container or sample rate alone must not impersonate codec/quality; unsupported runtime format must not be selectable. Test in Task 4.
4. MusicBrainz outage while a plausible same-title mp3 is returned: continue as INCONCLUSIVE_MANUAL, do not reinterpret network failure as negative identity evidence. Test in Task 2/5.
5. Search results arriving after cancel/logout or user changing a quality profile: immutable preflight snapshot/no ghost promotion or manual transfer; test in Task 6.

---

### Task 1: Canonical acquisition intent and compatibility snapshot

**Files:**
- Create: app/src/main/java/com/music/bitchord/acquisition/a0/A0CanonicalAcquisitionIntent.kt
- Modify: app/src/main/java/com/music/bitchord/download/SoulseekDownloadRouting.kt
- Test: app/src/test/java/com/music/bitchord/acquisition/a0/A0CanonicalAcquisitionIntentTest.kt
- Test: app/src/test/java/com/music/bitchord/download/SoulseekDownloadRoutingTest.kt

**Interfaces:**
- Produce immutable CanonicalAcquisitionIntent with recordingMbid:String?, releaseMbid:String?, releaseGroupMbid:String?, artistCredits:List<String>, title:String, requestedAlbum:String?, strictExactRelease:Boolean, requestedVersion:Set<VersionTag>, expectedDurationSeconds:Int?, origin:AcquisitionOrigin, qualityProfileSnapshot:QualityProfileSnapshot.
- Producer must expose structured explicit known/missing/ambiguous flags, not infer MBIDs from text. Existing plain-text manual entry remains supported and cannot assert an exact release by accident.

- [ ] Step 1: Add failing unit tests: explicit album survives intent creation; title-only has no fictitious canonical MBID; unknown artist stays unknown; credit placeholders excluded; old route decision behaviour unchanged.
- [ ] Step 2: Run :app:testDevDebugUnitTest with --tests for A0CanonicalAcquisitionIntentTest and SoulseekDownloadRoutingTest; expect explicit RED.
- [ ] Step 3: Implement immutable snapshot and bridge only. Do not replace the #178 database or modify media playback.
- [ ] Step 4: Run same tests GREEN plus existing routing tests.
- [ ] Step 5: Commit this tested subset inside #177 PR.

### Task 2: Read-only MusicBrainz release/recording resolution

**Files:**
- Create: app/src/main/java/com/music/bitchord/acquisition/a0/A0CanonicalAcquisitionResolver.kt
- Reuse: app/src/main/java/com/music/bitchord/data/catalog/CanonicalCatalogDao.kt and the stable provider contracts accepted by #178.
- Test: app/src/test/java/com/music/bitchord/acquisition/a0/A0CanonicalAcquisitionResolverTest.kt

**Interfaces:**
- Produce suspend fun resolve(intent:CanonicalAcquisitionIntent): CanonicalAcquisitionResolution, with sealed statuses CONFIRMED, AMBIGUOUS, UNAVAILABLE, MISSING (names are illustrative exact sealed cases in code). Carry release-group/Release/Recording MBIDs separately and evidence source; never coerce group into exact edition.
- Consume confirmed canonical IDs and release-track relationships, not raw free-text similarity masquerading as unique IDs. Inject fake repositories and bounded timeout.

- [ ] Step 1: First inspect #178 current issue/PR; stop only this integration task if its Android interface is unstable, then adapt dependency contract through an explicit read-only bridge rather than modifying #178.
- [ ] Step 2: Write RED tests: same recording across two Releases, exact Release required, group-only album context, unresolved original studio priority, alias credit, MusicBrainz timeout/503/cancel.
- [ ] Step 3: Run targeted unit test RED.
- [ ] Step 4: Implement adapter using existing MusicBrainz pacing/cache; reuse canonical snapshots; do not persist unverified association.
- [ ] Step 5: Run GREEN and test cancellation/rate limit; commit.

### Task 3: Identity and version evidence gate (no score compensation)

**Files:**
- Create: app/src/main/java/com/music/bitchord/acquisition/a0/A0CandidateIdentityEvidence.kt
- Create: app/src/main/java/com/music/bitchord/acquisition/a0/A0CanonicalIdentityPolicy.kt
- Modify: app/src/main/java/com/music/bitchord/acquisition/a0/A0CandidateRank.kt
- Test: app/src/test/java/com/music/bitchord/acquisition/a0/A0CanonicalIdentityPolicyTest.kt

**Interfaces:**
- fun assessIdentity(intent:CanonicalAcquisitionIntent, resolution:CanonicalAcquisitionResolution, observed:CandidateEvidence): IdentityVerdict; return COMPATIBLE_STRONG_UNVERIFIED, INCONCLUSIVE_MANUAL or INCOMPATIBLE + typed reason codes.
- Typed CandidateEvidence records per-field provenance and original normalized claim, with no raw names in toString/error logs.

- [ ] Step 1: Write RED tests: exact album match; wrong album/remaster despite high bitrate; only artist+title but no album -> INCONCLUSIVE; track explicitly released as single; artist name appearing inside song title; repeated-word title; feat alias; missing duration; remaster explicitly requested is allowed.
- [ ] Step 2: Verify RED in Android unit task.
- [ ] Step 3: Implement hard gate before legacy rank; preserve transport safety rejection even when musical flag is REVIEW.
- [ ] Step 4: Verify GREEN, add property-style assertions that bitrate and peer speed cannot promote INCOMPATIBLE to STRONG; commit.

### Task 4: Codec-aware user quality preferences

**Files:**
- Create: app/src/main/java/com/music/bitchord/acquisition/a0/A0DownloadQualityPolicy.kt
- Modify: app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt
- Modify: app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt
- Test: app/src/test/java/com/music/bitchord/acquisition/a0/A0DownloadQualityPolicyTest.kt
- Test: app/src/test/java/com/music/bitchord/download/SoulseekQualityPreferenceMigrationTest.kt

**Interfaces:**
- Produce fun evaluate(profile:QualityProfileSnapshot, offer:CandidateEvidence, supportedCodecs:Set<Codec>): QualityVerdict.
- Persist versioned preset/custom constraints without interpreting M4A as AAC or ALAC by extension alone; only expose actually implemented transfer/playback codecs as valid choices. Technical unknown => REVIEW, explicit forbidden codec => incompatible per user strict profile.

- [ ] Step 1: RED tests: Hi-Res 24-bit >=88.2k claim, CD-rate lossless, lossy 192 kbps vs depth=32 misuse, M4A ambiguous codec, unsupported format hidden/disabled, strict lossless with zero matching file does not downgrade to Opus, custom min bitrate/format; existing downloadQuality migration unchanged for other providers.
- [ ] Step 2: Run relevant Gradle tests RED.
- [ ] Step 3: Implement pure codec-aware policy plus Android Compose presets/custom fields; reuse current Settings hierarchy, explain unavailable format; no file conversion.
- [ ] Step 4: Run GREEN plus settings/other download regressions; commit.

### Task 5: Integrate explainable manual ordering and abstention

**Files:**
- Create: app/src/main/java/com/music/bitchord/acquisition/a0/A0CandidateDecision.kt
- Modify: app/src/main/java/com/music/bitchord/acquisition/a0/A0ObservedCandidateHandoff.kt
- Modify: app/src/main/java/com/music/bitchord/acquisition/a0/A0CandidateClaimClusters.kt
- Modify: app/src/devDebug/java/com/music/bitchord/acquisition/a0/A0ManualLoginActivity.kt
- Test: app/src/test/java/com/music/bitchord/acquisition/a0/A0CandidateDecisionTest.kt
- Test: app/src/test/java/com/music/bitchord/acquisition/a0/A0CandidateDiscoveryTest.kt

**Interfaces:**
- fun assessCandidate(intent:CanonicalAcquisitionIntent, canonical:CanonicalAcquisitionResolution, offer:CandidateEvidence): CandidateDecision.
- fun orderManual(decisions:List<CandidateDecision>):List<CandidateDecision>; stable ranking keyed by identity verdict, profile plausibility, musical evidence, and A0PeerAvailability compareSource **only when same claim+typed risk**. No transfer method on CandidateDecision.

- [ ] Step 1: RED tests: strict album wins before bitrate; same recorded claim from two peers uses queue; distinct claim does not get availability promotion; one long ambiguous title still appears manually; unknown album never STRONG; selectors preserve explicit choice only.
- [ ] Step 2: Run RED unit test.
- [ ] Step 3: Implement pure decision and readable explanatory reason mapping; integrate manual list without changing transferChoice API or P/F.
- [ ] Step 4: Run GREEN, test representative UI state semantics without real network; commit.

### Task 6: Bounded canonical search exploration

**Files:**
- Modify: app/src/main/java/com/music/bitchord/acquisition/a0/A0SearchQuery.kt
- Modify: app/src/devDebug/java/com/music/bitchord/acquisition/a0/A0ManualLoginActivity.kt
- Modify: app/src/main/java/com/music/bitchord/acquisition/a0/A0ObservedCandidateHandoff.kt
- Test: app/src/test/java/com/music/bitchord/acquisition/a0/A0SearchQueryTest.kt
- Test: app/src/test/java/com/music/bitchord/acquisition/a0/A0CandidateDiscoveryTest.kt

**Interfaces:**
- fun variants(intent:CanonicalAcquisitionIntent, budget:SearchBudget):List<StructuredSearchVariant>; preserve request intent across search paths and mark broad/alias results as weaker recall only. SearchBudget caps time, variants, in-flight offers and memory, with explicit cancellation hooks.

- [ ] Step 1: RED tests: too-long names normalization; album-specific version term; no blind title-only promotion; same peer flood; late reply after cancel/logout; no infinite timeout; no automatic transfer on retry/variant.
- [ ] Step 2: Run RED.
- [ ] Step 3: Implement bounded variants and cancellation, using existing native Soulseek client path and no slskd daemon. Tie budget defaults to empirically measured UX and explicit user extension.
- [ ] Step 4: Run GREEN and verify existing A0 P/F test suite remains unchanged; commit.

### Task 7: Diagnostics + conservative UX explanation

**Files:**
- Modify: app/src/main/java/com/music/bitchord/acquisition/a0/A0SmokeTrace.kt
- Modify: app/src/devDebug/java/com/music/bitchord/acquisition/a0/A0ManualLoginActivity.kt
- Test: app/src/test/java/com/music/bitchord/acquisition/a0/A0CandidateDiagnosticCoverageTest.kt
- Test: app/src/test/java/com/music/bitchord/acquisition/a0/A0CandidateDecisionTest.kt

**Interfaces:**
- Count-only statuses: exact edition supported, review for missing album, incompatible version, technical filter incompatible, canonical unavailable; policyVersion and search budget in event attributes, max **24 fields** per StructuredTrace event. UI uses reasons but diagnostics never export raw peer data.

- [ ] Step 1: RED test captures StructuredTrace sink and checks every required field survives 24-entry ceiling, no query/path/user/credential leakage.
- [ ] Step 2: Run RED.
- [ ] Step 3: Implement compact count-only observability and clear Portuguese reason copy in ranked selector, leaving manual selection intact.
- [ ] Step 4: Run GREEN; check bounds, storage/logging redaction, and commit.

### Task 8: Offline ranking benchmark versus pinned Flux

**Files:**
- Create: docs/benchmarks/soulseek/SCHEMA.md
- Create: docs/benchmarks/soulseek/synthetic-example.jsonl (invented peers, no real user data)
- Create: app/src/test/java/com/music/bitchord/acquisition/a0/A0CanonicalBenchmarkReplayTest.kt
- Create: tools/benchmarks/soulseek/run_flux_baseline.py (developer-only; not bundled in APK)
- Test: tools/benchmarks/soulseek/test_run_flux_baseline.py

**Interfaces:**
- Read normalized manifest; output ranked opaque offer IDs and typed reasons/abstentions; optional Flux bridge maps the same normalized data to pinned SearchCandidate/ScoringProfile. Never compare raw score magnitudes across distinct models.

- [ ] Step 1: RED tests: malformed manifest rejected safely, replay deterministic after offer shuffle, no usernames/paths in report, wrong-edition counterexample is not STRONG, fixtures remain synthetic and identical for both scorers.
- [ ] Step 2: Run Kotlin/JVM + Python benchmark tests RED.
- [ ] Step 3: Implement harness, schema/version pinning, annotation adjudication status, Wilson proportion interval calculator or trusted library, paired per-intent result export.
- [ ] Step 4: Run GREEN on synthetic examples; do NOT declare 120 measured gold cases from placeholders. Collect/curate real corpus only with rights/privacy and label review.
- [ ] Step 5: Commit and publish benchmark protocol with missing-real-data flag.

### Task 9: Final Android checks, truth-preserving gates and owner handoff

**Files:**
- Modify: docs/superpowers/specs/2026-10-25-soulseek-pre-download-design.md only for implementation-aligned clarifications.
- Update: #177 issue and #181 PR description; Notion Current State/Decisions after verification.
- No unapproved local/audio media collection.

- [ ] Step 1: Verify all frozen v1-v4 calibration suites plus newly added identity/quality/benchmark suites with ./gradlew :app:testDevDebugUnitTest and release build where available; spot-check Bluetooth/playback/local/Navidrome contexts not disrupted.
- [ ] Step 2: Verify required Android CI SUCCESS for **exact final HEAD**. Resolve merges with #178 only after checking stable interfaces.
- [ ] Step 3: Produce a two-part benchmark report: offline controlled ranking outcomes and independent live-discovery readiness, with N, unknown labels, dataset provenance and confidence intervals; do not invent scores or Flux advantage.
- [ ] Step 4: Leave PR draft if physical Galaxy A54 smoke has not been authorized/performed; clearly separate green CI from physical acceptance, no auto selection or auto transfer.
- [ ] Step 5: Submit to orchestrator for protected merge authorization only after code review, benchmark gate and owner QA.

## Self-review and acceptance map

- Intent/album/version contracts: Tasks 1–3.
- Quality preferences/format truth: Task 4.
- Clear recommended vs inconclusive manual decisions + availability tie: Task 5.
- Native discovery robustness and finite budgets: Task 6.
- Explainability and privacy: Task 7.
- Measured improvement versus Flux without cross-provider input confounding: Task 8.
- Android CI/QA, branch and #178 ownership: Task 9.
- Future auto opt-in #184: explicitly excluded; no task should implement it.

**Execution decision:** No code execution authorized by the current design session. Continue only on explicit implementation request; the owner has delegated design judgments, not rescinded the preceding request to stop feature implementation after CI success.
