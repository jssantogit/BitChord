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

The historical baseline column is frozen at 27 October; the PR column below is updated as functionality lands. Implementation of a similarly named field is **not** proof of exact Symfonium field/operator semantics.

| Capability | Symfonium evidence | BitChord main before #192 | #192 PR #193 draft (28 Oct 2026) |
| --- | --- | --- | --- |
| Smart playlist saved as filter definition, dynamically reevaluated | Official smart playlist docs | V1 Room definition + on-demand preview | V2 Room definitions saved, edited, deleted and re-evaluated on explicit Preview/Play/Queue; Room invalidation, account changes and foreground-only relative-time refresh implemented while preview is open |
| Rule groups | Official smart-filter docs | No | Recursive Compose E/OU/NOT editor plus typed AST |
| Nested All / Any | Official smart-filter docs | Only flat AND | Implemented in engine, Compose editor, serialized Room V2 definitions and tests |
| Operator depends on field type | Official smart-filter docs | Hardcoded minimum numeric fields | Numeric and text operators gated by type; wider reference field inventory still pending |
| Presence / missing tests | Official v13.6 | No | V2 engine with 3-valued unknown semantics |
| Sort column, ascending/descending | Official smart-playlist docs | Rating/completions/id fixed | Ratings, plays, duration, last audible, title, artist, IDs, seeded random and local rating update date; deterministic ties, PT-BR labels |
| Maximum count | Official smart-playlist docs | 1..100 | V2 engine supports up to 10,000; policy still to validate |
| Saved reusable smart filters | Official smart-filter docs | Saved playlists only | Independent Room v3 rule filters and Android editor; saved filter dropdown now integrated in provider-scoped canonical OpenSubsonic SONGS browse (supported fields only); Android Navidrome/OpenSubsonic now has explicit Songs/Albums/Artists tabs with context-specific saved filters and canonical album/artist drill-down to currently available source tracks; broader catalog/local media screens and full reference field/chip parity pending |
| Album artist / Artist / Duration | Official smart-filter docs | No / No / minimum duration | Display artist, typed duration and album artist from canonical release artist credits implemented; exact Symfonium semantics remain unverified |
| Additional track fields | Official v13.2 | No | Title, recording-artist credits, track number across linked releases, skips from first-party Listening, local rating/asset-added age; full reference inventory still pending |
| Release / album title membership | Official smart filter docs; field-level comparison needs physical verification | Catalog V1 ReleaseTrack linkage exists but no playlist filter | New V2 RELEASE_TITLE filter checks all existing first-party Recording↔Release links in 400-ID batches; no guessed release; missing means no *known canonical linkage* |
| Display artist(s), composer | Official v12.1 | No | Display artist and canonical track/album artist credits implemented; composer blocked until source-provenanced metadata exists |
| Original release date | Official v12.1 | No | MusicBrainz official release graph audit pending |
| Played percentage | Official v9.1 | No | Event semantics / denominator audit pending |
| Smart playlists referencing smart playlists | Official v9.1 | No | Saved V2 references, bounded DAG/cycle and missing-ID validation, referenced sort/limit applied before membership; tested with Room, live preview and B9 revalidation; exact Symfonium UX still needs device audit |
| Thumbnail and local thumbnail availability | Official v13.6 | No | Positive-only linked-release artwork URI reference implemented; **not** a verified/loaded thumbnail or missing-artwork equivalent |
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
- Do not claim full genre/composer/codec/original-release-date/thumbnail parity without canonical metadata and field/operator evidence; release year, source category and artist credits have narrower implemented semantics.

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

The PR now contains a Compose V2 builder, Room 1→2→3 migration retaining V1/V2 definitions, separate stored rule-only reusable filters, explicit copy-to-V2 workflow, persisted typed nested rules, the V2 preview/Play/Queue revalidation path, canonical recording/release-title batch reads, first-party Listening counters and rating-safe remote asset hints.

**Still not Symfonium parity:** complete field/operator inventory, genre, composer, original release date, release type, cover absence/thumbnail provenance, global provider-scope Inherit, version-specific reference-playlist UI semantics, contextual saved filters on Albums/Artists/Songs lists, album/artist media scopes, measured Android latency/memory at 50k and physical Galaxy A54 smoke remain open. Up to 500 selections have automated B9/B10 validation, but long-queue restoration is bounded and physical playback is unverified. Latest HEAD CI must pass separately. Keep PR draft, no APK or merge.

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

## 28/10/2026 — First-party local rating recency

- Added typed `DAYS_SINCE_LOCAL_RATING`, reading only `recording_rating_current.ratedAtEpochMs` and refreshing foreground previews with relative-age rules. The engine uses whole-day comparisons, rejects invalid/future timestamps and keeps unread evidence UNKNOWN under NOT.
- Missing rating date is only `IS_MISSING` after the local RecordingRating DAO has actually been read. Remote OpenSubsonic ratings and favorite events are **not** timestamps of a local first-party rating. This is **not** a claim of Symfonium Favorite Date parity.
- Unit, Room and foreground clock visitor regressions added. No Catalog schema change, no provider credentials/identity promotion. CI and device benchmarks remain independent gates.

## 28/10/2026 — Canonical release artwork pointer (NOT verified cover presence)

- Added `RELEASE_ARTWORK_REFERENCE` as a **positive-only** `IS_PRESENT` rule. Room reads nonblank `catalog_release.artworkUri` only through `catalog_release_track` links, in <=400-ID chunks and only when the AST references this rule.
- An existing URI is *not* proof the referenced image is reachable, accurate, cached, a local thumbnail or a canonical album cover. Because Catalog #178 still has artwork-provenance gaps, absent/unknown pointers evaluate UNKNOWN; `IS_MISSING`, negative comparisons and NOT(UNKNOWN) cannot yield an invented negative match.
- JVM and Room tests cover multiple releases, absent links, operator validation and selective read. Do not count this as full Symfonium 13.6+ `thumbnail`/`local thumbnail` parity before reliable present/missing provenance and physical comparison.

## 28/10/2026 — Sort by first-party rating-update date

- Added `LOCAL_RATING_NEWEST` and `LOCAL_RATING_OLDEST` with nulls/unknown/future timestamps sorted last and RecordingId ties deterministic. The preview requests local timestamp evidence even if the AST itself contains no rating-age filter.
- Android sort menu now labels the complete V2 sort set in PT-BR rather than exposing enum tokens; Room and pure-engine tests cover date ordering and missing values.
- These are native fork first-party rating-update sort modes, not a claim of Favorite Date equivalence. Symfonium sort options still require version-locked on-device audit.

## 28/10/2026 — Source-scoped local file-added age (not generic server-added date)

- Added `DAYS_SINCE_LOCAL_FILE_ADDED`, using `catalog_media_asset.dateAddedSeconds` **only** for `CanonicalLocalLibrary.LOCAL_MEDIASTORE_PROVIDER` under canonical Recording IDs. The existing configured-source snapshot must explicitly prove LOCAL eligibility; a remote-only or unknown-source Recording never matches even if stale local rows exist. No foreign-account source identifiers, URLs or native IDs enter rule JSON.
- A Recording may have several local files. Positive numeric predicates use any known file date, negative equality requires none matching **and** no missing/invalid dates. Missing data, future timestamps or no scoped local availability are UNKNOWN; `IS_MISSING` is intentionally unavailable. Dates are validated as positive Unix seconds with overflow checking and converted to milliseconds. Time-relative rules refresh in the visible foreground preview only.
- Added JVM tests for multiple assets, negative/NOT semantics and source availability; Room tests prove a remote OpenSubsonic timestamp is not used and NULL local dates remain UNKNOWN; foreground refresh test added. This is local MediaStore file-added time, not Symfonium provider-wide Added Date parity or original music release date.

## 28/10/2026 — Saved smart playlists referencing other saved playlists (PR #193)

- Added serializable `SmartPlaylistRule.Reference(playlistId)` to the existing typed ALL/ANY/NOT AST. References are **first-party saved V2 definition IDs**, not provider/native playlist IDs or free-text title matching. The Compose Android builder offers a saved-playlist selector via `+ Playlist`; unavailable/deleted references are visibly invalid.
- A bounded graph resolver collects shared dependencies once (at most eight nested reference edges / 64 participating definitions), performs postorder evaluation, and rejects self-reference, missing IDs, cycles and ambiguous identifiers *before* evaluating any candidates. This is a **failure**, not an empty playlist or a boolean FALSE that `NOT` can turn into a match.
- A referenced playlist contributes **the exact set of RecordingIds that survives its own filtering, sorting and result limit**. It does *not* inline only the child predicate while silently ignoring its limit. ALL/ANY/NOT and nested dependency composition work over those resulting memberships; an unresolved runtime membership is UNKNOWN.
- Production preview fetches **one current account-scoped candidate/evidence snapshot**, builds the union of all referenced query fields (including remote-rating, local-source and sort evidence), and evaluates each dependency exactly once. Saved Play/Queue revalidates the graph from Room at click time and fails closed if a target is removed or corrupt; editable draft Play/Queue propagates the editing owner ID so self-references are rejected.
- Repository saves reject dangling references and cycles, including cycles introduced by editing an existing definition; deletion blocks when another saved playlist refers to the target. Old V1 definitions and Room schema remain untouched, and no network credential or media URI enters rule JSON.
- While a referenced preview is open, changes to saved V2 definitions and canonical relation metadata invalidate the preview. The foreground-only relative-age refresh inspects referenced rules too; it does not install a background alarm.
- Tests cover nested and shared DAGs, sorted/limited membership semantics, NOT(UNKNOWN), missing/deleted/cyclic/too-deep graphs, JSON/Room roundtrips, live dependent edits, one canonical catalog snapshot and account-scoped source rules. **Exact parity for Symfonium reference syntax, scoped saved filters and UI operations is still subject to on-device version-locked audit.**

## 28/10/2026 — Reusable saved filter definitions (draft PR #193)

**Official UX baseline:** [Symfonium Smart Filters](https://docs.symfonium.app/wiki/other/smart-filters/) says saved filters can be loaded again from the filter button on primary library nodes, applied to context-specific media targets (albums, artists, songs), and exported as smart playlists. [Interface media lists](https://docs.symfonium.app/wiki/settings/settings-interface-media-lists/) also supports surfacing saved smart filters as chips. This is a broader scope than a playlist editor alone.

- Added an **independent** `smart_playlist_v2_filter` Room table with strict serialized `SmartPlaylistRule` AST (no `sort`, `limit`, stored Recording list, provider credentials, media URIs, native IDs or provider-account details). Schema migrates `SmartPlaylistDefinitionDatabase` 2→3 (and 1→2→3), keeping all saved V1/V2 playlists intact.
- The Android V2 editor can **save as filter**, **edit/update a named filter**, **copy its rules**, **combine with existing draft via ALL/E**, **create an editable smart playlist** from a saved filter, or **delete the independent filter**. Imports explicitly COPY immutable rule ASTs. Later edits to the saved filter do **not** mutate pre-existing playlist definitions or drafts. This is an intentional narrow, non-linked variant.
- A reusable rule can produce more than 100 matches: neither filter storage nor combining inserts the V2 default query `limit=100`; the owning playlist decides its own sort and max count. Combiner validates typed AST nesting/field operators with the existing engine. Corrupt/future versions and invalid numeric-text operators fail closed.
- Tests cover named/filter JSON roundtrips, updating under same ID, independent playlist edits, combining a 250-track match set without unintended 100-track limit, invalid payloads, and on-disk migration from V2 with preexisting legacy/V2 rows.
- **Partial parity only:** the saved filters are currently loaded from the V2 builder, NOT from the per-list filter menus/chips for Songs, Albums and Artists. The item-type-specific filter surface and cross-library application remain open. Do not count this feature as complete Symfonium saved-filter parity without on-device reference inventory and those contextual integrations.

## 28/10/2026 — Reduce unnecessary Room reads for large catalogs

- `SmartPlaylistV2Query.requiredEvidenceFields()` now unions the AST's actually referenced fields **and the sort's dependent evidence**: `RATING_DESC` requires rating plus completion count for its tie-break, `RATING_ASC` rating only; completion/last-audible/local-rating-date sorts request their exact evidence. Title/artist/ID/stable-random sorts alone do not require the ratings or Listening Event aggregates.
- `RoomSmartPlaylistBatchEvidenceReader.read(ids, requiredFields)` skips the indexed `recording_rating_current` and `listening_event` aggregate reads entirely when their fields are not used. Canonical recordings and required release/credit relations remain 400-ID scoped; direct `read(ids)` still requests full evidence for existing adapters/tests.
- Room tests record real local ratings and completed listens, assert text-only projection yields no rating/listening evidence while full projection retains both, and check sort dependencies including `RATING_DESC` secondary ordering. This removes unnecessary database traffic in common text-only filters without altering result ranking.
- **Not a physical performance benchmark.** The 50k Galaxy A54 timing/memory gate and bounded reference-DAG evaluation remain open. No uncontrolled cross-account read, migration or provider-network call was added.

## 28/10/2026 — Contextual saved filters by canonical media type (PR #193)

- **Actual Android entry point:** `CanonicalRemoteLibraryScreen`, for one configured OpenSubsonic/Navidrome provider. The screen observes the saved-filter Room Flow, offers a **Filtro: Todas** selector with only compatible filters, recomputes off the main thread when either source rows or definition change, and retains each track's original `RecordingId` and `MediaAssetId` for playback. Selection is reset when `providerKey` changes; no provider or account fallback, no network metadata fetch, and no background polling.
- **SONGS contract:** `ContextualSmartFilter.filterCanonicalBrowseSongs` accepts only `TITLE`, `ARTIST_DISPLAY` and `DURATION_MS` on the persisted provider-scoped `CanonicalBrowseTrack` projection. ALL/ANY/NOT and type-appropriate operators reuse the V2 engine. Ratings, plays, track credits, playlist references and other evidence are **not** invented as zero/empty: filters needing them are not shown, and applying one programmatically is rejected. No implicit V2 query maximum: each 400-row chunk is evaluated separately, and results preserve the source's order; a 10,101-item fixture verifies nontruncation.
- **Fail closed:** if a selected filter is deleted, changed to an unsupported field or its definition cannot be parsed, the screen displays an explicit failure and **does not** silently show every track. An empty match remains an empty result. Filtering never alters the selected track's provider-owned playback asset.
- **ALBUMS / ARTISTS contract:** independent pure typed evaluators operate on `ReleaseEntity.id` and `ArtistEntity.id` respectively; they never manufacture a `RecordingId`. Album-only fields currently allowed: release title and positive linked artwork-URI reference (a reference is NOT proof of valid/correct image). Artist-only field: canonical artist display name. Missing/unverified album cover is UNKNOWN, including through NOT. Other fields are rejected until source-provenanced entity evidence exists. Pure tests cover two distinct Releases with identical titles, canonical artist identities and incomplete-artwork logic.
- **Open integration work:** no album/artist filter menu exists in the catalog UI yet; that requires scoped visible Release/Artist lists from Catalog #178, and should not be guessed from song titles or provider native IDs. Songs in local/other source screens still use other pathways and are not yet wired. Songs currently exposes only a subset of the global filter fields until the browse evidence reader is enriched safely.
- **Future algorithm contract:** these rule-only, source-provenanced filters can later be one constraint stage in the first-party recommendation pipeline; they do not yet import Apple Music or Last.fm signals, overwrite user ratings, change ranking or autonomously queue anything. External hints must stay provenance- and account-aware, and apply only to canonical matches after evidence validation.

## 28/10/2026 — Account-proofed contextual filter input for Albums/Artists

- `CanonicalCatalogDao.providerScopedReleases(providerKey)` returns **distinct canonical ReleaseEntity IDs** only when `catalog_release_track` links the Release to a Recording with an actual `catalog_media_asset.provider = providerKey`. Sorting uses title then ID. Two Releases with the same title remain independent; orphan Releases cannot enter the list.
- `CanonicalCatalogDao.providerScopedArtists(providerKey)` reads **canonical ArtistEntity IDs** proven by recording artist credit or release artist credit linked to an asset of that exact provider/account. It does not invent Artist IDs by comparing display names or read artists from a different provider account.
- `CanonicalContextualFilterLibrary` is a read-only source-aware service composing the DAO projection and the strictly typed Album/Artist filter evaluator, rejecting blank account IDs and unsupported rules. A Room test seeds three same-title Releases and same-name Artists across two accounts plus an unassociated orphan to prove isolation.
- The context-filtered Songs screen now additionally checks every passed `CanonicalBrowseMediaAsset.providerKey` equals the active account key. An incorrectly mixed-source list is an error, never partially accepted and never silently replaced by an unfiltered result. Pure tests cover such mismatched asset lists.
- **Not done:** the catalog's visible Album and Artist list screens still need to call this service and present context-aware controls. Catalog #178 controls the authoritative UI/list availability; do not substitute Recording IDs or guessed artist/title matches. Also pending: fuller field/operator coverage, physical screenshots/latency, streaming library queries if album/artist count grows materially.

## 28/10/2026 — Real album and artist tabs in the OpenSubsonic canonical library

- The provider-scoped Android `CanonicalRemoteLibraryScreen` now has **Músicas / Álbuns / Artistas** tabs. Saved filter choices depend on the current canonical media type; a filter unsupported on Artists or Albums is not silently applied as a Recording filter. Filter selection is reset per account and per media type.
- Albums and Artists use Room `catalog_media_asset` + canonical release/artist links, not display-name grouping or provider-native aliases. The existing pure filter evaluators are now wired to actual UI lists. Room invalidation covers assets, release tracks, credits and entity names; queries and filtering run on IO, never on the composition thread.
- Clicking an album/artist opens its actual **currently available canonical Recording list**. New `providerScopedReleaseRecordingIds(providerKey, releaseId)` and `providerScopedArtistRecordingIds(providerKey, artistId)` recheck source access on drill-down, including release-only artist credits. Each displayed track must still belong to the active browse snapshot and all its playback assets must match that exact account. No synthetic Album/Artist `RecordingId`, title match or cross-account play.
- Invalidated album or artist associations yield no playable tracks (not a fallback to the unfiltered library). The detail view can go back to the filtered entity list, preserves the account and rechecks links on Room invalidation.
- Room regressions verify source-constrained navigation, two accounts with identical titles and names, credit-only artist memberships, orphaned releases, and removal of an entire account's assets without affecting the other account.
- **Still not full Symfonium parity:** the app-wide home/catalog explore UI and local media browse do not yet expose these contextual filters. Content and classification remain dependent on Catalog #178. Verified cover thumbnails, per-type extended fields, version-locked Symfonium operator inventory and physical performance gates remain open.

## 28/10/2026 — Product decision: FUNCTIONAL parity before interface design

The fork owner explicitly deferred all final smart-filter UI decisions until the functional engine is complete. **Do not model the final BitChord interface after Symfonium.** Its documented field/operator/target coverage remains a functional acceptance reference only. Existing Android tabs/editors are provisional wiring and not an approved design. Do not add quick-filter chips, reorganize navigation or invest in UI polish under #192 without a separate user-led design pass.

Prioritize typed rule semantics, canonical Song/Release/Artist scopes, metadata evidence/provenance, account isolation, persistence/migrations, dynamic invalidation, large-catalog benchmarking, and playlist/queue handoff. Last.fm, Apple Music and other external catalog hints are potential **future providers**, not current trusted canonical identity or permission to invent metadata.

**New non-UI functionality:** `CanonicalContextualFilterLibrary.songs(providerKey, filter)` runs the existing validated, no-implicit-limit Song rules on an on-demand canonical `CanonicalLibraryBrowseRepository` snapshot scoped to one exact provider. It returns the *original* `CanonicalBrowseTrack` with that provider's canonical `RecordingId` and playable `MediaAssetId`. The source can be the local reconciled MediaStore provider or a configured OpenSubsonic provider; this API is not coupled to Compose and can be used by future library and recommendation consumers. Unknown rating/listening/credit fields continue to fail eligibility rather than being assumed zero. Tests cover a shared Recording on local+remote with different assets, two distinct remote accounts, saved-filter reuse, removal of a remote account's assets, and the absence of an unfiltered fallback.

## 28/10/2026 — Known source categories and local add-date predicates in all contextual media

- **No UI changes:** The functional engine now recognizes `SOURCE_KIND = local | opensubsonic` and its negative/NOT combinations for canonical Songs, Releases and Artists. The provider identity is checked at the Catalog boundary: `local-mediastore` is local, `opensubsonic:<accountId>` is OpenSubsonic, and other source strings are UNKNOWN, not implicitly local or remote. For Albums/Artists this predicate is evaluated only against Releases/Artists actually joined to MediaAssets in the exact provider. The source rule is usable as a future native recommendation constraint without altering entity IDs.
- **Fail closed on missing artist/release name:** The contextual Album/Artist evaluator does not take empty canonical titles/names as verified evidence of absent metadata. `IS_MISSING`, `NOT(IS_PRESENT)` and related negatives remain UNKNOWN for unresolved names, including nested ALL/ANY/NOT. This is a conservative contextual distinction; the general V2 rule engine's existing text-missing behavior is unchanged.
- **Local-only added date:** `CanonicalContextualFilterLibrary.songs(local-mediastore, filter)` can now evaluate `DAYS_SINCE_LOCAL_FILE_ADDED` from **actual MediaStore `catalog_media_asset.dateAddedSeconds`** with indexed DAO reads in chunks of at most 400 IDs. No import/network/derived Spotify/Apple Music timestamps. Multiple local MediaAssets can contribute dates; absent, invalid, overflow or future values mark incomplete evidence, never known zero. The same rule is incompatible with a remote Song context and throws rather than falling back to every Song; direct pure evaluator refuses to accept it without the persisted date evidence. This field is not exposed by the provisional Navidrome-only Songs tab.
- **Regressions:** source-rule truth across local and OpenSubsonic Songs/Releases/Artists, UNKNOWN under NOT and NOT_EQUAL on unrecognized providers, Room cross-account shared-Recording isolation, local asset removal, blank title/name UNKNOWN and a **50,123-Recording** whole-list filter preserving all matches, source assets and original order without the 10k playlist limit. This 50k unit fixture verifies semantics but is **not** the physical A54 runtime/memory benchmark (still open).
- **Next functional work:** authoritative metadata-backed genre/composer/original release date, richer independent Album/Artist fields and operators where the Catalog can prove them, scoped rule reads and playback revalidation under concurrent catalog changes, physical 50k load testing. The final UI remains deferred explicitly by product decision.
