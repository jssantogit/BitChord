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

## 28/10/2026 — Trusted source / release-year filters (draft PR #193)

The Android V2 engine now offers two additional fields. Both are defined from
current **canonical evidence**; their semantics are deliberately narrower than
an unverified one-to-one claim of Symfonium parity:

- **SOURCE_KIND** (`local` / `opensubsonic`): the scope-aware Catalog availability
  reader checks only configured and credential-eligible providers. A Recording
  with both sources may match both positive conditions. `NOT_EQUAL` means
  the selected source is definitely absent from the **known current active
  source set**, not that the song never existed on that server. Unknown
  availability is UNKNOWN, not a match. Native `sourceKey`, `MediaAssetId`,
  credentials and provider account IDs do not enter the serialized AST.
  UI presents a bounded source dropdown, not a free-text provider identifier.
- **RELEASE_YEAR** (1000–9999): only MusicBrainz `releaseDate` values on
  first-party Recording→Release links count; validates `YYYY`, `YYYY-MM`
  and `YYYY-MM-DD` including Gregorian leap years. A Recording may be
  linked to several Releases: positive year comparisons accept a match from
  any known release; `NOT_EQUAL` requires every linked release to have
  trustworthy year data and no equality. Absent, malformed or unenriched
  release dates remain UNKNOWN. `IS_MISSING` is deliberately not offered
  because a missing MusicBrainz cache is not proof that a release has no date.
  Local file dates and media-added timestamps are **not** release years.
- All new checks are Android-only and use bounded batch projections and
  scoped source availability; no new storage migration, network fetch or
  YouTube dependency. The benchmark test with 50k candidate Recordings
  remains a correctness fixture, not an on-device latency measurement.

Before READY: verify the Android CI on the exact new HEAD, then audit the
remaining Symfonium 15.1.0 operator/field inventory; check account switches
and absent MusicBrainz enrichment on Galaxy A54; measure 50k actual on-device
latency/energy; evaluate >100 queue operations without implicit autoplay.


## 28/10/2026 — Atomic smart playlist Play/Queue window (draft #193)

The Android **canonical collection** route now accepts **up to 500 RecordingIds
in one MediaSession batch**, instead of 100. This is a bounded increase, not
unlimited playback. The original V1 rule definition limit is still 100; V2
definitions may preview 10,000 but a single action currently accepts at most
500. The V2 preview UI distinguishes these limits and disables dispatch above
500. Saved V2 definitions above 500 currently receive a safe limit rejection
from the handoff.

- One explicit action produces one process-local one-shot token and **one**
  B10 MediaSession command. Never send five sequential 100-song edits: if a
  later batch fails that would leave a partially queued playlist.
- B9 validates the complete requested list, deduplicates canonical Recordings,
  resolves each exact current rendition, and rejects ambiguous or unavailable
  tracks **before** submitting anything.
- B10 checks the hard 500 limit again before database access, reads media
  assets and canonical Recording metadata by ID in chunks of at most 400,
  checks provider/account scope and URI identity, then checks the current
  session/timeline generation before a **single** Media3 \`addMediaItems\`.
  A changed/unresolvable item prevents the entire insertion.
- \`ADD_TO_QUEUE\` and \`ADD_NEXT\` never call \`play()\`; \`PLAY_NOW\` alone
  explicitly starts the selected first song. Existing history, context and
  pending queue tiers are preserved as before.
- JVM tests cover 500 accepted items in order; 501 rejected; incomplete 251
  song playlist rejected atomically; a single paused 500-song policy decision,
  and one-shot queue request behavior. Room tests cover 500 exact assets
  resolved in SQLite-safe chunks without provider cross-contamination.

**Not yet validated on a Galaxy A54:** responsiveness, memory, audio transitions
and persistence of a 500-song Media3 insertion. This extension is CI test
coverage, not permission to claim physical smoke. Keep PR draft and unmerged.

## 28/10/2026 — Symfonium 15.1.0 parity is a release BLOCKER for #192

**Target:** Android Symfonium 15.1.0 (2026-09-30 is the latest release listed by the official news index at audit). The official smart-filter and smart-playlist manuals explain rules, groups, sorting and limits, but **do not enumerate every field/operator available in the reference 15.1.0 Android build**. This is a grounded *minimum* matrix: capture the actual per-type field and operator menus before closing the issue. Do not claim complete parity from older changelogs alone.

Primary official evidence:
- https://docs.symfonium.app/wiki/other/smart-filters/
- https://docs.symfonium.app/wiki/other/smart-playlists/
- https://symfonium.app/news/version-7-2-0/ (album mood/label, stable random)
- https://symfonium.app/news/version-1200/ (Favorite Date filter/sort; date presence/absence)
- https://symfonium.app/news/version-1210/ (display artists/composer, track styles, original release date, random albums)
- https://symfonium.app/news/version-1320/ (new track fields, grouped choices)
- https://symfonium.app/news/version-1360/ (thumbnail / local thumbnail presence, most IS_PRESENT/IS_MISSING, provider/library Inherit)
- https://symfonium.app/news/ (release version chronology)

| Symfonium capability | Fork state on draft PR #193 (subject to HEAD CI) | Evidence dependency / gap |
|---|---|---|
| Nested ALL / ANY group rules | Implemented | AST, UI and Room persistence; physical editor semantics pending |
| Typed numeric and text operators | Partial | Audit actual per-field operator choices in reference app |
| Dynamic saved smart playlists; sorting; result limits | Partial | V2 persisted/reactive, but sort options and media types incomplete |
| Title, artist display, duration, ratings, plays/skips | Partial | Current first-party canonical evidence only; provider listening semantics can differ |
| Album/release title, release year | Implemented with strict scope | Many linked releases; MusicBrainz releaseDate only, no guessing |
| **Album artist** | **Implemented on branch** | Linked canonical release artist credits, multiple releases/credits; missing data UNKNOWN |
| Local vs current OpenSubsonic source | Partial | Credential-scoped; not an arbitrary provider list or Inherit UI |
| **Genre and multi-valued genres** | **MISSING** | No normalized trustworthy genre on canonical Recording/Release yet |
| **Mood, style, record label, album category** | **MISSING** | Need canonical metadata role/provenance per media type |
| **Composers; multi-artist display composer** | **MISSING/PARTIAL** | Artist credits exist, but Composer role is not known from those credits |
| **Favorite Date and favorite-specific sort** | **MISSING** | Rating timestamp is NOT Favorite Date |
| **Original release date** | **MISSING** | MusicBrainz edition releaseDate is NOT the first/original release date |
| **Random albums / stable random albums** | **MISSING** | Per-song seed exists; requires canonical release grouping |
| **Referenced smart playlists inside another filter** | **MISSING** | Requires cycle detection, bounded dependency graph and refresh rules |
| **Thumbnail and local-thumbnail present/missing** | **MISSING** | Need trustworthy presence and local-art-specific metadata |
| **IS_PRESENT/IS_MISSING for most fields, including dates** | **PARTIAL** | Must distinguish absent fact from missing metadata |
| **Album- and Artist-targeted smart filters** | **MISSING** | V2 currently filters canonical Recordings only |
| **Inherit visible providers/libraries** | **MISSING** | Need per-playlist scope contract and current global visibility |
| **Copy a dynamic playlist to a static playlist** | **MISSING** | Separate snapshot action; not V1→V2 copy |
| **500-track Play/Queue** | **Implemented on last green CI** | One bounded Media3 insertion; Galaxy A54 smoke pending |
| **Selected-only B9 browsing** | **Implemented on branch** | Needs exact-HEAD CI and 50k on-device profiling |

### Mandatory closing gates

1. Capture the full Symfonium 15.1.0 Android Song, Album, Artist smart-filter menus, **all per-field operator choices**, sorting choices and provider-scope settings; update this matrix with evidence. No claims of exhaustive inventory from release notes.
2. For every entry, implement supported field semantics end to end: typed rule, validation, trusted data source, Compose UI, persistence, engine tests and real Room tests. When catalog metadata is missing, open a concrete Catalog/provider dependency and keep #192 open rather than inventing behavior.
3. Verify nested E/OU/NOT, multi-valued tags, missing/unknown distinctions, remote account switching, current scope, ratings, release/artist links, random sort, large playlists and physical UI.
4. Require Android CI GREEN on exact HEAD and final integration base. **Full parity is NOT yet achieved.** Current V2 is a functional subset, not release-ready equivalence.

## 28/10/2026 — Canonical release track position (new draft feature)

- Added typed V2 `TRACK_NUMBER` with integer comparison, operator-aware validation, and `IS_PRESENT`; the field uses only `catalog_release_track.trackNumber` scoped by canonical RecordingId and explicit release membership, in batches <=400.
- A Recording linked to multiple editions can match any **known** positive track number. Negative comparisons (and NOT) require complete linked-position evidence; NULL/nonpositive positions and absent links produce UNKNOWN, never track zero and never an unjustified negative match. `IS_MISSING` is intentionally unavailable because absent canonical links do not prove global absence.
- Android rule builder exposes **Número da faixa no lançamento**. Added pure-engine tests for multiple editions, unknown under NOT, positive presence and target validation, plus Room test for a partially missing edition.
- This is a BitChord capability; an exact matching Symfonium 15.1.0 field/operator claim still requires an on-device audit. No change to Android Room schema, Media3 handoff or source accounts. CI and physical smoke are separate gates.

## 28/10/2026 — Track artist from canonical credits (new draft feature)

- V2 field `TRACK_ARTIST` uses `catalog_recording_artist_credit` and `catalog_artist`, never `artistDisplay` or title matching. This is different from `ALBUM_ARTIST` (release credits).
- Positive text comparisons use ANY known credited artist; negative comparisons require all known credits to disprove a match. No credits or unresolved names produce UNKNOWN and cannot satisfy a NOT or `IS_MISSING`; valid `IS_PRESENT` uses known credits only.
- UI exposes **Artista da faixa (créditos canônicos)**. Pure and Room regression tests cover multiple credits and false-positive avoidance from a display artist string. No schema migration and no remote-per-item lookup. Parity status still *not verified* until exact Symfonium 15.1.0 field/operator audit.

## 28/10/2026 — AST-scoped relation reads (50k library safeguard)

- Each preview derives the set of fields referenced by its entire nested ALL/ANY/NOT rule AST. The production Room reader conditionally reads release titles, release years, release album artists, linked track numbers, and credited track artists **only when selected**; duration/rating/count-only rules avoid all five relation queries per 400 RecordingIds.
- Direct calls to the batch reader preserve the full-evidence contract for existing tests/adapters. Source/account eligibility and remote rating reads retain their separate policies. Added nested AST projection and Room selective-read regressions. This reduces unnecessary SQL reads but **does not** substitute for measured 50k Android latency/memory.
