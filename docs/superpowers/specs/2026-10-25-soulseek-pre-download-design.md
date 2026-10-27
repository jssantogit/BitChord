# BitChord Android — Identity-First Soulseek Pre-download Design

**Date:** 2026-10-25  
**Status:** Approved product direction; implementation NOT authorized by this document alone  
**Issue:** https://github.com/jssantogit/BitChord/issues/177  
**Reference PR:** https://github.com/jssantogit/BitChord/pull/181 (draft; baseline preflight-v4)  
**Related:** #178 canonical MusicBrainz catalogue; #184 future default-OFF opt-in automatic selection

## Intent and non-negotiable priority

Return the **right audio candidate for the requested recording, album/edition and version**, not the superficially highest-scoring or highest-bitrate file. Avoid wrong artists, remix, remaster, edit, live/acoustic, karaoke, compilation substitution, wrong album, wrong format. Preserve potentially correct but insufficiently supported offers as **inconclusive manual choices** rather than silently throwing them away.

Ordering is **hard identity/version compatibility > evidence sufficiency > allowed technical format and plausibility > ranking among compatible candidates > peer availability among equivalent claims**. Quality, queue or advertised speed cannot repair a wrong edition. The user asked for a familiar single ranked list (best first), with explanations, rather than category-separated screens.

This is a **pre-transfer** system. MusicBrainz is a reference catalogue, not an oracle proving peer file bytes. Do not claim that a filename, size, ISRC in peer-supplied text, duration, matching album path, or advertised lossless codec proves the actual mix/master/file. Distinguish **canonical catalogue fact** from **untrusted metadata claim** from **independently verified audio** (not available at pre-download stage).

## Explicit decisions from owner

- Without requested album, **prefer the principal album's studio release**, if the available canonical relationships make this unambiguous; otherwise mark target release unresolved, never invent the album. For track released exclusively as a single/EP, retain its real context instead of forcing an album.
- With explicitly requested album: **strict album match**. Release-group membership may establish the album family, but if the request requires one exact edition/Release MBID, a different release in the same group does not establish edition equivalence.
- Remix, remaster, edit, live, sped/slowed, acoustic, instrumental, karaoke, cover, etc. are incompatible when not explicitly requested. Treat uncertain version as **inconclusive**, not automatically as incompatible. Do not treat absence of a version label as proof of an original master.
- Variations in artist credit may be accepted when multiple independent, credible signals converge; explicit conflicting primary artist remains incompatible.
- Reuse Android Settings > Downloads > Download quality, offering understandable presets **Hi-Res, Lossless, High quality, High efficiency**, plus Custom (container/codec, bitrate if meaningful, PCM bit depth and sample rate if meaningful). Preserve the existing download-quality setting and behaviour for other providers unless the migration is explicitly defined, versioned and tested. No assumed transcoding.
- Prefer technically plausible files, not absurd or suspicious advertised quality/size. Availability of a peer is only a **tie-breaker among musically equivalent evidence**.
- MusicBrainz is the canonical reference; when metadata are insufficient, retain a manual inspection option and disclose missing proof.
- Longer bounded searches are acceptable for genuine gains in identity precision; no infinite retries or unbounded memory/network activity.
- Optimize **precision, coverage and latency together**, using a reproducible comparison with **jssantogit/noqlen-flux-core** rather than ungrounded claims of beating Flux.

## Approaches considered

1. Filename/path scoring with hard rule penalties. Simpler but can promote incorrect releases and artist/title collisions; discarded as long-term architecture.
2. **Selected:** canonical identity-first evaluation, typed multi-source evidence, conservative verdicts, independent technical quality and peer availability, explainable ranking.
3. Adaptive, learned weighting on top of option 2. Deferred until the benchmark has enough independently labelled real-world evidence; no online learning from user downloads or peer claims.

## Existing Android baseline (verified in repo)

- Acquisition A0 presently uses A0CandidateRank, A0CandidatePreflight, A0CandidateClaimClusters, A0PeerAvailability and A0ObservedCandidateHandoff; candidate metadata are in memory, manual selection remains mandatory, there is no transfer authority in the preflight simulation.
- The current POLICY_VERSION is preflight-v4 with 70 synthetic calibration cases in four frozen packs (36+17+5+12). They are regression assertions, **not precision or recall measurements**.
- MainActivity routes a Song to SoulseekDownloadRouting with optional display duration. The canonical database stores Recording, Release, ReleaseTrack, MediaAsset and provider-scoped ExternalIdentity; linking an actual MusicBrainz Recording/Release MBID requires explicit resolution, not title string coincidence.
- The existing DownloadQuality enum has Standard, High, Lossless and is persisted by AppSettings, but the Soulseek selector does not use these as filtering requirements. Existing selector supports MP3/FLAC/WAV saving; other container/codec claims must not be surfaced as downloadable merely because they appear in a future preset.
- The catalogue migration on #178 is parallel work. Consume its typed, read-only Android identity contract when stable; DO NOT fork a second canonical catalogue, write conflicting Room identities or depend on unmerged #178 assumptions.

## Proposed contracts and ownership boundaries

### Request snapshot

A read-only CanonicalAcquisitionIntent includes:
- source: canonical-library / Navidrome / local / manually-entered query;
- artist title and credited aliases, recording MBID if confirmed, expected duration and tolerance with provenance;
- release-group MBID (album family), exact Release MBID (edition) if known/requested, track position/disc when available, studio/version intent and whether artist/album was explicitly constrained;
- quality profile snapshot and policyVersion; user corrections remain separate from canonical identity mutation.

States for unknown fields MUST be explicit: absent, unresolved, ambiguous, confirmed. A plain textual query can initiate discovery but never silently become an exact MusicBrainz ID. The resolver reports timeout/unavailable independently of mismatch. No UI or network side effects in policy evaluation.

### Evidence and provenance

Build CandidateEvidence from the *untrusted* Soulseek observed claim: remote filename, nearest/path segments, size, format/container, codec if actually available, advertised bitrate, duration, sample rate/bit depth, peer slot/queue/speed; plus source/provenance for each claim and conflicts with the canonical target. Names are never interpreted as authenticated MBIDs or verified file tags. Claim equality groups offers, not actual audio equality; never mix claims from different peers into a fictitious verified candidate.

CanonicalMusicIdentityResolver is a separate, rate-limited adapter to the catalogue's stable Android interface and MusicBrainz API if necessary, with bounded search/pagination, cancellation, caching and typed failure. It must respect MusicBrainz pacing/User-Agent policies and not let network failure downgrade a contradiction into a STRONG result.

### Verdicts and decision ordering

- **COMPATIBLE_STRONG_UNVERIFIED**: sufficiently consistent, *independent* target/album/version evidence and no contradictory facts; not verified audio. May be recommended at the top of the *manual list*.
- **INCONCLUSIVE_MANUAL**: plausible song/file but missing trustworthy evidence for the requested album/edition/version or canonical lookup unavailable, ambiguous alias, incomplete technical claims. Visible and selectable; not promoted to strong by bitrate, absence of "remaster", or peer speed.
- **INCOMPATIBLE**: affirmative evidence of a forbidden alternate version, different artist or album, an unsupported mandatory format, or obviously impossible technical claims. Excluded from **normal recommendation**, but may remain visible in the manual inspection list with an explanatory warning if safe to expose. Distinguish *unsafe filename/transport* (must reject for security) from *musically incompatible* (not a security hazard).

Run hard compatibility and technical-safety gates *before* score ordering. A source with best availability cannot beat a musically different file. For strict edition mode, a file with only title and artist is inconclusive, not strong; a folder that says the album is supporting context, **not definitive proof of the mastering edition**. An exact MusicBrainz Recording MBID can be shared across mastered releases, so never claim exact master solely from Recording identity.

Preserve the manual transfer gate and all P/F invariants; no automatic transfer, fallback download, external daemon, background monitoring or post-download fingerprinting in #177.

### Quality profiles (proposal; not hard-coded codec equivalences)

- **Hi-Res:** lossless codec with claimed >=24-bit PCM resolution and >48 kHz sample rate (or explicit compatible alternative), only when format/runtime supports it; no guarantee the peer file really contains that resolution.
- **Lossless:** credible lossless codec/container (e.g. FLAC, PCM WAV, ALAC only after actual supported decoding/saving) at normal or higher resolution; "M4A" is a container, not proof of ALAC.
- **High quality:** compatible lossy encodings with high declared bitrate and plausible size; per-codec bitrate thresholds, no common "320 kbps" applied to Opus/AAC/MP3 as if they were equivalent.
- **High efficiency:** prefer acceptable size/quality tradeoff using compatible efficient codecs; do not silently convert MP3 to Opus. No unsupported codec should become selectable merely due to a preset description.
- **Custom:** explicit list of allowable codecs/containers and minimum/maximum constraints appropriate to that codec, sample rate/depth only when semantically applicable, strict vs fallback policy and a clear "no matching file" state.

The user-approved quality settings surface should be reused in Android Compose, but migration must preserve current users' existing preferences/other provider semantics. **Lossless must never silently fall back to lossy** if configured as strict; any fallback is explicitly requested. Bitrate is not PCM bit depth. Bit depth is not a selectable property of arbitrary AAC/Opus/MP3 files. Peer claims may be unknown. Present codec examples as examples, not guarantees.

### Search and ranking

Use targeted bounded variants (canonical artist + title, optional album/version identifiers, aliases, title-only *only as clearly weaker recall exploration*), dedupe and preserve discovery provenance, stop/cancel on lifecycle changes. A broader query must not weaken strict identity gates. Avoid premature early stop before enough peers respond; cap total search time, offers, peers, memory and outstanding socket readers. User-controlled "search longer" may extend an explicit bounded budget; no infinite search.

Within verdict class, order by (1) edition/version confidence, (2) identity evidence, (3) conformance to user technical profile/quality plausibility, (4) candidate source availability **only among equivalent normalized claim + risk**; deterministic ordering for equal evidence. No fabricated real-time ETA. Display "why recommended/why inconclusive" with human-readable typed reasons, never usernames/remote paths in exported diagnostics. One single ordered list, not multiple default sections.

### Failure and recovery

MusicBrainz offline/503, incomplete track links, multiple possible releases, unavailable peer, inconsistent/corrupted announced metadata, album absent from filename, preview top-score ties, malformed Unicode, path traversal, reused query/credentials, disconnected socket, cancellation and late answers all have deterministic typed outcomes. Never return "verified" from peer metadata. Degradation to a manual list is acceptable; failing closed for transfer safety is mandatory. No accidental writes to canonical identity or ratings.

## Acceptance criteria

1. Strict-album / forbidden-version cases never become strong recommendations from contradictory metadata, regardless of codec, bitrate, score or fast peer.
2. Missing album/edition evidence remains visible as **inconclusive manual** (when transport-safe), not dropped and not falsely promoted. Primary-album preference is applied only when canonical evidence justifies it.
3. Artist aliases/collaborations and repeated words do not silently cause wrong artist matches or suppress all legitimate tracks.
4. Presets and Custom persist, explain constraints accurately, and never pretend supported formats/transcoding/Hi-Res verification that do not exist; existing downloads from other providers retain their preferences.
5. Identity evaluation, quality-policy compatibility and source availability remain independently testable pure contracts. No transfer can be initiated by ranking, preview or new preferences.
6. Search lifecycle remains bounded, cancellable and observable, with count-only redacted diagnostics and policy/config version. Missing MusicBrainz is a typed failure, not fabricated success.
7. Benchmark includes labelled difficult cases, blind holdout, Flux baseline at fixed commit, offline identical-candidate comparison and separate live Android discovery measurements. **No claims of superiority or accuracy** absent results.
8. Existing PR #181 green baseline and #178 ownership are preserved; Android unit/instrumentation and physical Galaxy A54 smoke are independent gates before any merge.

## Size, dependencies and authorization

Project-sized design. Implement as one coherent issue-first Android PR only after agreed implementation plan and acknowledgement of catalogue #178 interface, without stacking independent slice PRs. Prefer updating/reusing PR #181 for #177, provided the orchestrator has not already integrated it; revalidate branch/CI first. #184 is a separate opt-in automation feature, default OFF, not part of this spec.

## Supporting references

- Flux code: https://github.com/jssantogit/noqlen-flux-core/blob/main/src/noqlen_flux/services/scoring.py ; contracts https://github.com/jssantogit/noqlen-flux-core/blob/main/src/noqlen_flux/search.py ; provider https://github.com/jssantogit/noqlen-flux-core/blob/main/src/noqlen_flux/providers/slskd.py
- MusicBrainz API: https://musicbrainz.org/doc/MusicBrainz_API ; schema entities: https://musicbrainz.org/doc/MusicBrainz_Database/Schema
- BitChord #177: https://github.com/jssantogit/BitChord/issues/177
