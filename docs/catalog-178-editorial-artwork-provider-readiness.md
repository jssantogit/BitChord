# Catalog #178 — Editorial, artwork and future-provider readiness (proposed)
Date: 2026-10-26
Scope: Android only; PR #183 draft, no merge or removal of YouTube discovery until physical acceptance.
Status: **PROPOSED ROADMAP**, not implemented unless identified in an existing commit. Do not silently convert a proposal into a claim of shipped features.

## Immediate verified defect and fix
- `RoomMusicBrainzCoverArtStore` writes `release:front-v2` and `release-group:front-v2`.
- The offline DAO formerly joined bare `release` and `release-group`, so it could surface pre-policy art or miss already validated artwork.
- On this branch, the Room projection joins the versioned keys. Robolectric regression tests both rejection of old poster evidence and recovery of the validated front on cached group and edition rows.
- This fix affects **offline materialization**; it cannot by itself demonstrate lower network latency or 100% correct online artwork on the Galaxy A54.

## P1 — Authoritative discography editorial projection
Input: MusicBrainz Artist MBID -> ReleaseGroup MBIDs -> Release MBIDs -> Track/Recording MBIDs.
Never infer a type from the title, track count, thumbnail, provider chart rank, or a recording's existence.

Suggested scoped records:
- `ReleaseGroupEvidence`: MBID, primary type, complete secondary types, credited artist MBIDs, first release date/date precision, source and fetched-at; unknown remains unknown.
- `ReleaseEditionEvidence`: Release MBID, group MBID, **status** (Official, Promotion, Bootleg, Pseudo-Release, unknown), territory, format/packaging, language, date precision, barcode/UPC if supplied and valid, whether there is a verified tracklist; no invented release from a recording.
- `DiscographyPlacement`: ALBUMS, SINGLES_EPS, COMPILATIONS, LIVE, SOUNDTRACKS, REMIXES, OTHER_OFFICIAL, UNCERTAIN/NOT_SHOWN with explicit reason codes. Classify on positive evidence. Primary type `Other` does not become `Single` because it contains one recording.
- Only explicitly official **editions** may support a claim of public commercial release; store unknown/unverified separately. Distinguish an artist's *main release-group artist credit* from a featured recording credit.
- A group yields **one** editorial tile by RG MBID; regional/digital/CD/reissues remain selectable editions, not a wall of duplicate tiles. Default tracklist edition is selected by official status + exact RG link + sensible type + verified tracks + non-conflicting artist, then market/language/date; no fuzzy title remapping.
- First-release chronology is date-precision aware. Future releases, collaborations/appearances, and `Other` do not silently enter main studio-album shelves. Preserve releases with evidence that does not fit main shelves in an optional separate section, not by falsely relabeling them.
- Favor bounded MB artist-group browsing with genuine pagination. Track total/consumed/next offset and incomplete coverage; avoid presenting "complete discography" when only the first page was read. Maintain accessible offline metadata.

Suggested gold cases: NewJeans (New Jeans, OMG, Get Up, Supernatural, NJWMX, Ditto, Right Now), ILLIT (SUPER REAL ME, MAMIHLAPINATAPAI), plus artists with albums, official compilations, remix groups, collaborations, homonymous artists, unavailable editions and incomplete metadata.
Failures to test: Other renamed Single; one Release incorrectly duplicated as a Group; unverified edition chosen as official; artist appearance shown as main album; missing edition tracklist treated as a playable recording; 75-group pagination silently losing an EP.

## P2 — Provenance-first, fast artwork
Suggested provider-neutral observation:
`ArtworkEvidence(provider, entityKind, canonicalMbid, sourceReleaseMbid?, imageId?, url, types, approved?, width?, fetchedAt, expiresAt, resultState, confidenceReason)`.
- The **cache key must include** canonical entity, verified release edition/cover ID, provider, artwork policy version and size class. Legacy results must not silently override validated ones. Do not mix recording video stills with audio sleeves.
- Accept a CAA Group front only with the archive's linked release provenance; a Recording should prefer a trusted official audio edition, never a mere title match or a video release. Handle CAA `Front` vs `Poster`, approved and ambiguous/invalid image metadata as separate states.
- Cover grid: request only thumbnail resolution appropriate to 52dp/grid; hero: request 500/1200 if needed. Prefer CAA's pre-generated 250/500/1200 over downloading originals. Inspect existing `artworkAt`/Coil resizing first; do not blindly rewrite provider URLs.
- Speed: render cached metadata/text immediately; asynchronously hydrate visible images; prefetch limited next-visible items; coalesce concurrent identical requests, use bounded concurrency, timeouts and 404 negative TTL. Keep 503/timeouts as transient and retry with backoff; no mass CAA/MB fetch on every recomposition.
- Measure p50/p95 cold/warm art time, disk/Room cache hit, number of MB/CAA requests, empty covers, verified wrong-art rate and cancellation count. Do not assert improvement without device measurements.

## P3 — Contracts ready for future enrichment (NOT a provider integration)
Identity authority:
- MusicBrainz Artist/ReleaseGroup/Release/Recording/Track MBIDs remain canonical; provider IDs live as **scoped external claims** with evidence. `UPC` supports album editions, `ISRC` supports recording candidates (not uniquely sufficient to prove an edition); Apple catalog IDs and storefront are scoped. Crosswalk by title alone is NEVER a canonical merge.
- Each external observation needs provider name, external id, canonical target kind/id, relationship type, storefront/locale where applicable, returned fields, fetched-at/expiry, confidence/verification, data usage constraints and failure state.
- Last.fm: optional artist biography, external popularity/rank, tags and listeners; data are observations, not release primary/secondary type or physical audio. Validate API key and image licensing independently.
- ListenBrainz: recommendation and listen-count evidence keyed to MBIDs for future "Em alta"/personalized discovery; third-party popularity is not a canonical release claim and should never imply playback availability.
- Apple Music Catalog: future editorial matching of official releases, catalog types, album/track relationships and art under a **permitted API integration**. Requires developer auth and storefront; check MusicKit/API content rights and display rules. Do not take artwork from the iTunes Search API as arbitrary replacement art.
- All providers are disabled by default until credentials, licensing, caching and test fixtures are ready. Provider outages never remove canonical identities or reset the local/remote MediaAsset associations.

## Proposed implementation order before next physical smoke
1. Fix mismatched CAA front-v2 offline Room joins and regression test (this session).
2. Persist complete RG secondary types and edition status with explicit Room migration and offline/online parity tests.
3. Build a deterministic `EditorialDiscographyClassifier` with reason codes; use it in Android artist shelves and search; no UI redesign.
4. Introduce artwork provenance/resolution policy in a separate pure decision layer; align Room+Coil cache and bound refresh.
5. Add provider-neutral external-evidence read model + fake-provider tests, no network integrations yet.
6. Build new APK only after Android CI green; measure the actual Galaxy A54 behavior and preserve PR #183 as DRAFT until signoff.

## References
- https://musicbrainz.org/doc/Release_Group
- https://musicbrainz.org/doc/Release%20Group/Type
- https://musicbrainz.org/doc/Style/Release_Group
- https://musicbrainz.org/doc/Cover_Art_Archive/API
- https://musicbrainz.org/doc/MusicBrainz_API
- https://developer.apple.com/documentation/AppleMusicAPI
- https://listenbrainz.readthedocs.io/en/latest/users/api/metadata.html
- https://listenbrainz.readthedocs.io/en/latest/users/api/popularity.html
- https://www.last.fm/api/rest
