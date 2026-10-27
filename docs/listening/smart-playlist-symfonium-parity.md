# Smart playlists V2 — Symfonium parity evidence (Android)

Issue: [#192](https://github.com/jssantogit/BitChord/issues/192) · Baseline: 2026-10-27 · Status: **in progress — NOT parity achieved**.

## Primary sources and evidence policy

1. [Official Smart filters](https://docs.symfonium.app/wiki/other/smart-filters/): filter targets depend on the media type; rule configuration depends on data type; nested groups with `All` and `Any`; reusable saved filters and export as smart playlist.
2. [Official Smart playlists](https://docs.symfonium.app/wiki/other/smart-playlists/): continuously refreshed media collection, configurable sort/direction, max result count, and edits.
3. [Official Symfonium v13.6.0](https://symfonium.app/news/version-1360/) (2025-11-21): `Is present` / `Is missing` for most fields; thumbnail/local-thumbnail filter; inherit global visibility of providers and libraries.
4. [Official Symfonium v13.2.0](https://symfonium.app/news/version-1320/) (2025-07-11): additional track fields, grouped filter choices, copy smart playlists to normal playlists.
5. [Official Symfonium v12.1.0](https://www.symfonium.app/news/version-1210/): display artist, display album artists, display composer, original release date filters, stable-random albums/songs sorting.
6. [Official Symfonium v9.1.0](https://symfonium.app/news/version-9-1-0/): smart-playlist references in smart playlists; played-percentage filter.

**Do not infer a complete operator/field inventory from high-level screenshots or release notes.** An on-device field-by-field audit of the contemporary Symfonium Android app is still required to lock an exact reference version and prove full parity. Any unverified capability remains marked *inspection required*, not implemented.

## Verified feature-level gap matrix

| Capability | Symfonium evidence | BitChord main before #192 | #192 first stage |
| --- | --- | --- | --- |
| Smart playlist saved as filter definition, dynamically reevaluated | Official smart playlist docs | V1 Room definition + on-demand preview | Preserve, revalidate refresh semantics |
| Rule groups | Official smart-filter docs | No | V2 typed AST initial engine |
| Nested All / Any | Official smart-filter docs | Only flat AND | V2 engine initial support |
| Operator depends on field type | Official smart-filter docs | Hardcoded minimum numeric fields | Typed numeric comparisons first; full field matrix pending |
| Presence / missing tests | Official v13.6 | No | V2 engine with 3-valued unknown semantics |
| Sort column, ascending/descending | Official smart-playlist docs | Rating/completions/id fixed | V2 engine first sorting |
| Maximum count | Official smart-playlist docs | 1..100 | V2 engine supports up to 10,000; policy still to validate |
| Saved reusable smart filters | Official smart-filter docs | Saved playlists only | Pending UI/storage |
| Album artist / Artist / Duration | Official smart-filter docs | No / No / minimum duration | Catalog-backed attributes blocked until verified |
| Additional track fields | Official v13.2 | No | Catalog capability inventory pending |
| Display artist(s), composer | Official v12.1 | No | Identity/credits provenance audit pending |
| Original release date | Official v12.1 | No | MusicBrainz official release graph audit pending |
| Played percentage | Official v9.1 | No | Event semantics / denominator audit pending |
| Smart playlists referencing smart playlists | Official v9.1 | No | Pending cycle-safe definition graph |
| Thumbnail and local thumbnail availability | Official v13.6 | No | Distinguish verified canonical artwork vs provider placeholder |
| Inherit visible providers/libraries | Official v13.6 | No | Pending source scope policy |
| Copy smart playlist to normal playlist | Official v13.2 | No | Pending explicit user action, never auto-persist |
| Stable random / random albums modes | Official v12.1 | No | Deterministic seed prototype, album grouping pending |

## Fork-specific safety and performance contracts (not claims about Symfonium)

- `RecordingId` remains identity of playback/feedback; source-owned IDs and text similarities are never canonical proofs.
- `UNKNOWN` in a predicate does not magically match due to `NOT`. Presence/absence has its own explicit operators. Unknown timestamps with a confirmed completion must not mean *never played*.
- An implicit `rating < 3` veto existed in V1. This is a bug, not an intentional playlist rule. Any rating should remain available when the user's filter does not exclude it.
- An input size of 5,001 must not silently masquerade as an empty playlist; large-catalog reads need batching and measured latency.
- No provider cross-account data leakage, silent provider switching, YouTube fallback, or Media3 autoplay when adding to queue.
- Avoid claiming equivalence for unsupported genre/album/artist/codec/year without canonical metadata and confirmed semantics.

## Reproducible reference fixtures for further comparison

1. **Favored or long**: `completions >= 1 AND (rating >= 4 OR duration > 240s)` → two chosen matches.
2. **Unknown under negation**: `NOT(rating < 3)` must not include a track with absent/conflicting rating; an explicit `rating IS MISSING` filter may.
3. **Never completed**: `completions = 0` remains distinct from a real completion long ago.
4. **Sort + count**: ascending completion count, 150 results; exact limit and tie-break verified.
5. **Duplicate assets**: identical canonical copies coalesce; conflicting evidence for one Recording fails closed.
6. **Scale**: 5,001+ candidates still yield valid results; separate Room and physical Android tests prove no N+1 or UI stall.
7. **Persistence**: V1 definitions migration; zero dropped definitions, same preview before/after restart; V2 recursive rules round-trip losslessly.
8. **Mixed providers**: offline local, online Navidrome, account swap, stale availability, Media3 `PLAY_NOW` and `ADD_TO_QUEUE`.

## Current technical boundary

The first code milestone adds a **standalone, pure Kotlin V2 expression evaluator** and V1 planner regressions. It is not wired to the UI or V1 persistence yet; users do **not** have a complete Symfonium editor at this checkpoint. Future milestones need indexed multi-source reads, schema V2, a Compose rule builder, final field-by-field comparison, exact-HEAD Android CI and a real Galaxy A54 smoke. PR must remain draft and unmerged.
