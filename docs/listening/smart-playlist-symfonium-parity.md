# Smart playlists V2 — Symfonium parity evidence (Android)

Issue: [#192](https://github.com/jssantogit/BitChord/issues/192) · Baseline: 2026-10-27 · Status: **in progress — NOT parity achieved**.

## Primary sources and evidence policy

1. [Official Smart filters](https://docs.symfonium.app/wiki/other/smart-filters/): filter targets depend on the media type; rule configuration depends on data type; nested groups with `All` and `Any`; reusable saved filters and export as smart playlist.
2. [Official Smart playlists](https://docs.symfonium.app/wiki/other/smart-playlists/): continuously refreshed media collection, configurable sort/direction, max result count, and edits.
3. [Official Symfonium v13.6.0](https://symfonium.app/news/version-1360/) (2025-11-21): `Is present` / `Is missing` for most fields; thumbnail/local-thumbnail filter; inherit global visibility of providers and libraries.
4. [Official Symfonium v13.2.0](https://symfonium.app/news/version-1320/) (2025-07-11): additional track fields, grouped filter choices, copy smart playlists to normal playlists.
5. [Official Symfonium v12.1.0](https://www.symfonium.app/news/version-1210/): display artist, display album artists, display composer, original release date filters, stable-random albums/songs sorting.
6. [Official Symfonium v9.1.0](https://symfonium.app/news/version-9-1-0/): smart-playlist references in smart playlists; played-percentage filter.
7. [Official Symfonium v15.1.0](https://symfonium.app/news/version-1510/) (2026-09-30): latest release shown on the official news index at the 2026-10-27 audit. Its release notes do not constitute a complete smart-filter field/operator inventory. The physical reference app version still must be recorded.

**Do not infer a complete operator/field inventory from high-level screenshots or release notes.** An on-device field-by-field audit of the contemporary Symfonium Android app is still required to lock an exact reference version and prove full parity. Any unverified capability remains marked *inspection required*, not implemented.

## Verified feature-level gap matrix

| Capability | Symfonium evidence | BitChord main before #192 | #192 PR #193 draft (27 Oct 2026) |
| --- | --- | --- | --- |
| Smart playlist saved as filter definition, dynamically reevaluated | Official smart playlist docs | V1 Room definition + on-demand preview | V2 Room definitions saved, edited, deleted and re-evaluated on explicit Preview/Play/Queue; continuous/reactive UI refresh pending |
| Rule groups | Official smart-filter docs | No | Recursive Compose E/OU/NOT editor plus typed AST |
| Nested All / Any | Official smart-filter docs | Only flat AND | Implemented in engine, Compose editor, serialized Room V2 definitions and tests |
| Operator depends on field type | Official smart-filter docs | Hardcoded minimum numeric fields | Numeric and text operators gated by type; wider reference field inventory still pending |
| Presence / missing tests | Official v13.6 | No | V2 engine with 3-valued unknown semantics |
| Sort column, ascending/descending | Official smart-playlist docs | Rating/completions/id fixed | Ratings, plays, duration, last audible, title, artist, IDs and seeded random; text-sort keys cached once per candidate |
| Maximum count | Official smart-playlist docs | 1..100 | V2 engine supports up to 10,000; policy still to validate |
| Saved reusable smart filters | Official smart-filter docs | Saved playlists only | Pending UI/storage |
| Album artist / Artist / Duration | Official smart-filter docs | No / No / minimum duration | Canonical display-artist text + numeric duration available; separate verified album-artist credit criteria still pending |
| Additional track fields | Official v13.2 | No | Title and artist string comparisons; early/late user-navigation skips from first-party Listening; full inventory pending |
| Release / album title membership | Official smart filter docs; field-level comparison needs physical verification | Catalog V1 ReleaseTrack linkage exists but no playlist filter | New V2 RELEASE_TITLE filter checks all existing first-party Recording↔Release links in 400-ID batches; no guessed release; missing means no *known canonical linkage* |
| Display artist(s), composer | Official v12.1 | No | Identity/credits provenance audit pending |
| Original release date | Official v12.1 | No | MusicBrainz official release graph audit pending |
| Played percentage | Official v9.1 | No | Event semantics / denominator audit pending |
| Smart playlists referencing smart playlists | Official v9.1 | No | Pending cycle-safe definition graph |
| Thumbnail and local thumbnail availability | Official v13.6 | No | Distinguish verified canonical artwork vs provider placeholder |
| Inherit visible providers/libraries | Official v13.6 | No | Pending source scope policy |
| Copy smart playlist to normal playlist | Official v13.2 | No | Pending explicit user action, never auto-persist |
| Stable random / random albums modes | Official v12.1 | No | Deterministic seed implemented, album-group random modes pending |

## Fork-specific safety and performance contracts (not claims about Symfonium)

- `RecordingId` remains identity of playback/feedback; source-owned IDs and text similarities are never canonical proofs.
- `UNKNOWN` in a predicate does not magically match due to `NOT`. Presence/absence has its own explicit operators. Unknown timestamps with a confirmed completion must not mean *never played*.
- An implicit `rating < 3` veto existed in V1. This is a bug, not an intentional playlist rule. Any rating should remain available when the user's filter does not exclude it.
- An input size of 5,001 must not silently masquerade as an empty playlist; large-catalog reads need batching and measured latency.
- No provider cross-account data leakage, silent provider switching, YouTube fallback, or Media3 autoplay when adding to queue.
- A release-title filter may match multiple distinct canonical Releases associated with one Recording. Positive conditions use ANY known title, negative conditions require NONE matching; the absence filter refers only to the *current catalog links*, not real-world album absence.
- Avoid claiming equivalence for unsupported genre/year/composer/provider/codec or verified artist credits without canonical metadata and confirmed semantics.

## Reproducible reference fixtures for further comparison

1. **Favored or long**: `completions >= 1 AND (rating >= 4 OR duration > 240s)` → two chosen matches.
2. **Unknown under negation**: `NOT(rating < 3)` must not include a track with absent/conflicting rating; an explicit `rating IS MISSING` filter may.
3. **Never completed**: `completions = 0` remains distinct from a real completion long ago.
4. **Sort + count**: ascending completion count, 150 results; exact limit and tie-break verified.
5. **Duplicate assets**: identical canonical copies coalesce; conflicting evidence for one Recording fails closed.
6. **Scale**: 5,001+ candidates still yield valid results; separate Room and physical Android tests prove no N+1 or UI stall.
7. **Persistence**: V1 definitions migration; zero dropped definitions, same preview before/after restart; V2 recursive rules round-trip losslessly.
8. **Mixed providers**: offline local, online Navidrome, account swap, stale availability, Media3 `PLAY_NOW` and `ADD_TO_QUEUE`.
9. **Multiple releases**: a single Recording linked to two Releases must match either positive title condition; a negative title condition must reject it when one linked title matches; true missing means no linked Release.
10. **50k scale**: deterministic 50,000-candidate title sort test, matched against RecordingId tie-break and a capped 100-result slice. This is a correctness test, **not** an Android latency or memory benchmark.
11. **Verified remote hints**: Only a V2 rating condition provably required on every matching path may select a unique currently verified Navidrome asset; an OR with an unrated branch, NOT, incomplete account snapshot or ambiguous assets cannot grant it.

## Current technical boundary

The PR now contains a Compose V2 builder, Room 1→2 migration with unchanged V1 definitions, explicit copy-to-V2 workflow, persisted typed nested rules, the V2 preview/Play/Queue revalidation path, canonical recording/release-title batch reads, first-party Listening counters and rating-safe remote asset hints.

**Still not Symfonium parity:** the filter field matrix is not exhaustive; genre, year, release type, composers, trustworthy album-artist credits, provider-scope rules, reference-playlist nesting, automatic reactive preview refresh, batch playback larger than 100, measured 50k Android latency/memory, physical Galaxy A54 smoke and UI review remain open. A new CI execution must finish at the exact PR HEAD to validate this checkpoint. No APK requested. Keep PR draft, no merge.

The official Symfonium v15.1.0 release notes are dated 2026-09-30 but do not provide a complete, version-locked reference field matrix; full feature parity **must not** be claimed from this document.
