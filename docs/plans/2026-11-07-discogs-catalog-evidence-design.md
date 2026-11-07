# BitChord Android — Discogs as canonical edition evidence (#210)

Status as of 2026-11-07: PERSONAL-USE API ENABLED IN DRAFT PR #211, no merge. The user confirmed BitChord Fork is currently a personal Android app. Public distribution rights/relicensing review is deferred until sharing is planned, not an activation gate for the personal build. Discogs API terms still apply: metadata-only, adjacent linked attribution, non-affiliation notice, bounded request budget and expiry. The API was verified externally with HTTP 200 and a real linked MusicBrainz Release ↔ Discogs edition (MBID dc919562-19c7-42c8-961c-7ed5520f00bb / Discogs release 249504) in CI #42390874505; the optional top-level MusicBrainz media-count regression was corrected. This is not a physical Android smoke; final Android CI and Galaxy validation remain separate gates. Earlier default-off sections below describe the pre-activation design and are superseded by this status.

## 0. Baseline, ownership, and integration strategy

- Repository: https://github.com/jssantogit/BitChord, protected `main` at `f45018878f7effa3e1e38697a2e26a0ad8a0cf66` when this design began. The separate draft #209 (https://github.com/jssantogit/BitChord/pull/209) has Android-native catalog, ListenBrainz and UI work not merged. This branch starts from main rather than stacking on #209. After #209 merges, reconcile with latest main **in this same #211 PR**, adapt to its actual contracts and rerun all gates. Do not copy #209 renderers into an alternative implementation.
- Existing code (main): `app/.../data/catalog/CanonicalCatalogEntities.kt` has typed canonical entities, `ExternalIdentityEntity` and separate `MediaAsset`; `CanonicalMusicBrainzGraphEntities.kt` has `ReleaseGroup` / `Release` and edition evidence; `metadata/ExternalEditorialEvidence.kt` and `CanonicalProviderEnrichmentPolicy.kt` provide read-only provider claims; `CanonicalMetadataEnrichment.kt` explicitly accepts MusicBrainz only; `MusicBrainzCatalogGraph.kt` traverses real MBIDs; `CoverArtArchive.kt` has verified image policy. Catalog Room schema version is **16 at this base SHA**; do not assume it remains 16 on integration with #209.
- Issue: https://github.com/jssantogit/BitChord/issues/210. This PR stays draft until functional implementation, Android CI at the **exact final HEAD**, code/terms audit, reproducible benchmark and on-device Galaxy A54 smoke. No main, #209, Web, Desktop, TV or Wear changes are implied.
- Reject a new catalog store, a new artist/release screen, YouTube playback/discovery, fake recordings, and a new generalized plugin execution system. Reuse native components, Room graph, Ktor client patterns, shared request budgets, and existing UI design.

## 1. Responsibility and non-negotiable invariants

| Concept | Authority | Discogs role |
| --- | --- | --- |
| Artist / Recording / Track / Release / ReleaseGroup identity | BitChord canonical graph + verified MusicBrainz identifiers | source-scoped candidate/verified external link, never primary key |
| Group primary/secondary type and edition status | MusicBrainz, with provenance | advisory description/format/Unofficial flag; never override canonical group type |
| Listen count / charts / personalized intelligence | ListenBrainz and local Listening Event Store, separately labeled | no popularity, recommendation score or listening claim |
| Edition details | MusicBrainz plus verified edition-specific observations | label, catalog no., barcode, country, format, credits, tracklist and release-date evidence |
| Cover images | MediaStore/Navidrome local asset, verified CAA; other sources only with separate display rights | OFF by default; Discogs images are restricted data under its API terms |
| Playback, queue and downloads | real MediaAsset backed by local/authorized remote source | never creates MediaAsset, stream URL, videoId, download or playable state |
| Personal state | local Room (ratings, playlists, pins, history) | never overwrites or transmits personal listening data |

Unknown is **not** rejected, absent, unlicensed, false or unofficial. Evidence is never a master truth across sources. ReleaseGroup != Release != Discogs Master != Discogs Release. Master may group maxi/remix versions of a single. Missing Discogs coverage does not hide a MusicBrainz release. A label-issued remix is not intrinsically unofficial.

## 2. Layered design — no parallel sources of truth

```text
[Existing Android surfaces: Home/Explore/Search/Artist/Release/Library]
                         |
[Existing canonical ViewModel + Room graph + native UI components]
                         |
[Provider-neutral read-only CatalogEvidenceCoordinator]
      |              |                    |
 [MusicBrainz]   [ListenBrainz]       [DiscogsEditionAdapter]
 IDs / group    listen & recs      verified links + edition facts
 types/status       MBIDs             NOT album identity
      |              |                    |
      +----------[Validated source-scoped observations]--------+
                         |
     [IdentityGate] -> [EditionMatchingPolicy] -> [FieldPolicy]
                         |
     [Expiring sidecar cache / no write-through to canonical facts]
                         |
       [Existing UI projections + required Discogs attribution]
```

Design a **small** typed port near `data/catalog/metadata`, not a runtime-selectable plugin engine:

```kotlin
interface CatalogEvidenceProvider {
    val descriptor: ProviderDescriptor
    suspend fun observe(request: EvidenceRequest): EvidenceResult
}

data class ProviderDescriptor(
    val key: String, // stable namespaced key e.g. "discogs"
    val subjects: Set<CatalogTargetKind>,
    val fields: Set<EvidenceField>,
    val supportedLookupKinds: Set<LookupKind>,
    val networkAccess: NetworkAccessPolicy,
    val retention: RetentionPolicy,
    val rights: DisplayRightsPolicy
)

sealed interface EvidenceRequest {
    data class ByCanonicalRelease(val releaseMbid: String): EvidenceRequest
    data class ByCanonicalGroup(val groupMbid: String): EvidenceRequest
    // No free-text mass scanning in initial implementation
}

sealed interface EvidenceResult {
    data class Success(val facts: List<SourceFact>, val completeness: Completeness): EvidenceResult
    data object NotFound: EvidenceResult
    data object Unavailable: EvidenceResult
    data object Unauthorized: EvidenceResult
    data class Throttled(val retryAtMillis: Long?): EvidenceResult
    data object Malformed: EvidenceResult
    data object Ambiguous: EvidenceResult
    data object Restricted: EvidenceResult
    data object Stale: EvidenceResult
}
```

Representative contract, NOT source code yet; choose existing internal naming/package conventions at implementation. Preserve coroutine `CancellationException` rather than treating it as unavailable. Do not turn missing fields into a successful null overwrite. Version any persisted evidence schema. Authentication/rights/capabilities belong to individual providers; no global bit meaning "all remote data safe".

## 3. Source link discovery, identity checks, and matching

**Discovery sequence, strictly bounded and lazily triggered for visible canonical release details:**

1. Starting with **verified** MusicBrainz Release MBID, query `/ws/2/release/{mbid}?inc=url-rels...` or reuse already loaded MB relationships. Accept only the **Discogs relationship type** and links with exact, normalized allowlisted host/path (`discogs.com/release/{positive-id}` / `discogs.com/master/{positive-id}`), not arbitrary URL strings, redirects, embedded credentials, query-tracking IDs or arbitrary hostnames. Validate entity type before fetching.
2. If MusicBrainz Release has an explicit Discogs **Release** relationship, request `GET https://api.discogs.com/releases/{id}`. It gives a strong *identity anchor*, still subject to contradiction checks (artist credit, barcode/tracklist/date/format). Mark `VERIFIED_EDITION` only after corroborating source attributes do not conflict; the external relationship alone is `LINKED`, not a guarantee of authorized publication.
3. For an explicit MusicBrainz **ReleaseGroup** → Discogs **Master** relationship, request `GET /masters/{id}` only to corroborate group-level context. The master never auto-selects a specific MusicBrainz edition or rewrites its ReleaseGroups; inspect versions only if a proven edition is needed and budget allows.
4. If no direct relationship, return `UNKNOWN/NO_LINK` and retain the standard MusicBrainz catalog. No title-only match, no artist-name-only join, no automatic Discogs artist directory search for entire catalogs. Optional future lookup by validated UPC+artist+tracklist requires token/rights and explicit opt-in with ambiguity guards.
5. Discogs link source is always recorded as `MUSICBRAINZ_URL_RELATION` (the API record is separate evidence); do not present the relationship as Discogs independently certifying it. A corrected/upstream-removed link invalidates the association on refresh, but never deletes canonical data or user state.

**Matching rules** (deterministic hard gates, not one global weighted score):
- A Release's Discogs Release ID and an RG's Discogs Master ID are different types and namespaces; 1-to-many is expected, not a uniqueness error. A Discogs Release can point to a Master; a MusicBrainz Release belongs to one RG. **Never infer MB ReleaseGroup equality from Discogs Master equality.**
- Record *claim level*: `DIRECT_RELATION`, `SUPPORTED_BY_BARCODE`, `SUPPORTED_BY_TRACKLIST`, `CONTRADICTED`, `UNRESOLVED`, and whether artist credits actually correspond to verified MB artist identities. Textual name likeness is a consistency hint only.
- Barcode: strip allowed separators; preserve leading zeroes; UPC-A, EAN-8/EAN-13 checksum where applicable. Barcode is not always unique (box sets, repressings, split territories, re-used numbers, no barcode, copied user entries). Never join on barcode alone.
- ISRC identifies recordings, not Release/edition. Discogs Track positions (A1/B1, multiple media, hidden tracks, non-audio tracks, medleys, remasters, bonuses) differ from MB Track ordering. Compare **structured** medium+position, count, credits, track identity where available; a missing tracklist means `UNKNOWN` rather than an empty album.
- Discogs artist names may be suffixed ("(2)"), use aliases/ANVs, include "Various" or language variants. Do not automatically create MB artist links from a Discogs name. Validate exact existing identity proof and distinguish main artist credit from featured/track credit.
- Dates retain precision, market, event meaning (first issue vs reissue vs digital publication). A 1998 vinyl pressing and a 2024 reissue must not overwrite the RG's first-release date. Contradictions stay as alternative dated facts; no silent wins.
- `Unofficial Release` / `Partially Unofficial` are adverse *observations*, not a global deletion flag. Policy may separate from main shelves when corroborated by MB and edition identity; otherwise show canonical MusicBrainz item unchanged and report conflict.
- Many-to-many/conflicting links, stale upstream edits and indistinguishable matches produce `AMBIGUOUS` with reason codes; never "best guess". Support future human link/correction without mutating source authority.

## 4. Field-level provenance and rights

Every SourceFact must include `provider`, `providerObjectKind`, `providerObjectId`, `canonicalTargetKind`, `canonicalMbid`, `fieldKind`, typed `value`, `basis`, `verificationLevel`, `observedAt`, `expiresAt`, `sourceRecordUrl`, `rightsClass`, `attribution`, `schemaPolicyVersion`, optional `market/datePrecision` and `conflicts`. No raw JSON persistence. Separate identity links, evidence facts and UI-resolved projections. Never embed Discogs untrusted text as a clickable/HTML string.

**Authority by field**:
- Identity, group type, canonical release status, playlists, ratings: **not writeable by Discogs**.
- Label, country, format, catalog no., identifier, credits: show as *Discogs supplemental metadata* when verified, current and attributable.
- First-release date: MusicBrainz canonical date; Discogs may show the date of a specific edition (with precision and source), never overwrite the canonical date.
- Genre/style tags: supplemental source-tagging, never automatic group type or primary genre truth.
- Artwork: reject Discogs images until independent rights approval for the target use; even if returned in API JSON or marked "primary", this is **not** an image license. Existing CAA and own library artwork policy stays in place.
- Confirmed commercial availability: neither Discogs entry nor `official` label alone proves an authorized digital stream or regional availability.

Update `ExternalEditorialEvidence.kt` and `CanonicalProviderEnrichmentPolicy.kt` additively: they are already wired to Apple/Last.fm/ListenBrainz capabilities and the MusicBrainz-only `CanonicalMetadataEnrichmentRepository` has an explicit providerKey assertion. **Do not pass Discogs through the MusicBrainz-only repository**; use a separate provider-neutral evidence coordinator. Avoid sweeping existing enum migration until tests prove need.

## 5. Licenses / attribution / retention are release blockers

Official Discogs API terms last updated 2025-05-27:
https://support.discogs.com/hc/en-us/articles/360009334593-API-Terms-of-Use

- Basic release/artist/label metadata are listed as CC0. Images, user data and marketplace data are **restricted**, not automatically CC0. No images / marketplace / user endpoints in initial provider. Fork is GPLv3; verify the licensing and the API's "incompatible software license" restriction for the exact use case. A CC0 fact does not automatically waive API contractual obligations. Document and seek authorization if uncertainty remains.
- API requires prominent non-affiliation notice (in existing About/Attributions) and adjacent `Data provided by Discogs` with hyperlink to the **specific Discogs record** beside displayed API-derived metadata. No unlabeled global collection or "verified" ribbon derived invisibly from Discogs. Existing native detail subsection/footnote only, not a duplicated screen.
- Discogs API terms prohibit displaying content older than six hours relative to their site and storing it longer than needed. For initial API-based implementation: keep **isolated short-lived source evidence only**, propose ≤5h TTL from fetch (tunable shorter; not a guarantee of no upstream edit) and strict render-time expiration, no stale-while-revalidate for Discogs, no copying Discogs fields into persistent canonical tables. Background refresh if displayed and permissible; offline on expiry show MB/source-independent fallback, not stale Discogs facts. Revisit actual legal interpretation before enabling production.
- Do not export Discogs source facts as timeless "canonical album truth" to playlists, downloads, user library rows, backup files or offline caches. If using periodic CC0 dumps later, they require a **separate product/licensing/operational review**; do not conflate dump access with API permission.
- API provider can change the rules, terminate keys, charge for access or revoke permission. Add a hard kill switch / disabled state; support removal of provider-only caches without touching canonical rows. Never scrape web HTML, bypass authentication or rotate keys to evade limits.

## 6. HTTP, security, privacy, quota

- Direct Android Ktor/OkHttp transport, injected interface and one Discogs-specific **process-wide** request budget. HTTPS only; fixed host `api.discogs.com`, fixed allowlisted endpoint patterns, no user-provided URL fetch / SSRF / arbitrary redirects, TLS normal validation. Discogs web URLs for attribution are render-only allowlisted links, not HTTP clients.
- Distinct provider limiter: MusicBrainz's existing process-wide 1 req/s and ListenBrainz's shared budget must remain unchanged. Discogs commonly documents 25 requests/min anonymous and 60 authenticated, **not guaranteed contractual quotas**. Default conservatively lower, consume runtime rate headers when present, bounded concurrency, enforce `429` / `Retry-After` (seconds and HTTP date), bounded backoff with jitter, no retry storm; stop on `401/403` rather than cycling credentials.
- Prefer anonymous exact lookup through MB relationship if API supports it when tested live. Search generally requires auth; in v1 search stays disabled. Never package a global secret in a publicly distributed APK; an optional bring-your-own token must be opt-in, stored locally under Android Keystore encryption, excluded from backup/diagnostics, revocable, not sent to MusicBrainz/ListenBrainz, and not required for the basic app. Do not implement credential input until necessity and UX/privacy are reviewed.
- Source-specific request priority visible detail > visible shelf > prefetch; concurrency coalescing by typed key, cancellations release locks and do not cancel remaining observers; rate budget applies to retries too. Bounded pagination `page` / `per_page` and candidate caps; never crawl every album in an artist's discography on opening one screen.
- Network disabled/timeout/invalid JSON/huge JSON/Unicode controls/malformed numeric IDs/long strings/invalid URLs/redirects/half-replies/429/5xx become typed errors without touching canonical state; use response size cap, depth cap, length cap, request deadlines and strict decoder. Sanitize control chars while preserving Korean, Japanese, Chinese, diacritics and normalization-sensitive identifiers.
- Privacy: no sending the entire local library, downloaded file names, user Navidrome endpoints, auth headers, ListenBrainz token, playback history or Soulseek source data. Remote metadata requests should only run for publicly opened catalog items under a documented provider policy, not by scrobble/playback. Aggregate diagnostics without names, IDs, tokens, URLs, searches or response bodies.
- Cache key includes provider, external kind/id, exact canonical anchor, type of claim, schema/rights policy version and account scope if authorized. Separate positive, verified-no-link, transient and auth failure TTLs. Never cache 401/403 indefinitely or treat 429 as no release.

## 7. Implementation inside this ONE PR (not separate slices/PRs)

1. Contract-first tests for trust/source boundaries, field policy, rights/attribution/expiry, Discogs ↔ MB type separation and failure taxonomy. Extend existing metadata files minimally.
2. Add `data/catalog/metadata/discogs/` with transport, DTOs, parser, link extractor and deterministic matching policy; no dependency on Discogs client SDK or new Android module.
3. Add provider-neutral coordinator/read model if existing seams are insufficient, adapt to #209's final runtime contracts after it merges. Reuse existing request budgets concept, not a universal cross-provider 1 req/s lock.
4. Separate ephemeral cache with typed freshness/expiry. Prefer in-memory initial cache if device cold-start use is acceptable; only add Room sidecar if benchmark justifies it, with non-destructive migration from the **current merged schema version**, account isolation and strict no-stale output.
5. Minimal evidence display in **existing** Android release/artist detail components with source and rights/attribution, async loading and cached canonical fallback. Add non-affiliation notice to existing About. No cover integration without approval.
6. Feature gate off-by-default for first rollout / local experiment. Verify API contract and no-token path against official service when possible, without secrets in CI. Later controlled opt-in only after terms review.
7. Finalize gold corpus, code/tests, CI, QA and diagnostics, then physical Galaxy A54 smoke on final SHA. Keep PR open draft until approved. No auto merge.

Each step is an implementation checkpoint **within the same branch/PR**, not a stand-alone deliverable or separate PR.

## 8. Release acceptance, evidence and rollback

- Baseline comparisons: main #205 Library (no changes in local/Navidrome list, albums/artists, pins, ratings, PlaylistAdd, filters, history), and merged #209 canonical Home/Explore/Search, ListenBrainz and no-YouTube safeguards. Metadata-only track remains non-playable.
- Automated: parser fixtures (release/master/missing/conflicting/unofficial, malformed/huge payload), stable identities, property-based URL and UPC checks, precision dates, quotas/timeouts/cancel/coalescing, TTL, rights attribution and offline; Room migration and prior schema upgrades only if Room changed; Compose visibility/attribution tests without visual redesign.
- Corpus must stratify: modern singles/EPs, older albums, regional editions, deluxe/remaster, multiple discs, bootleg, official remix, live/compilation, group vs main artist collaborations, homonyms, CJK/diacritics, sparse/missing/conflicting links, no-barcode, same-barcode, edited/upstream-deleted relationships. Report numerator/denominator and ambiguous outcomes; **zero confidently mislinked items** in human-audited gold is mandatory; never force coverage by guessing.
- Performance: compare cold/warm/offline p50/p95, MB+LB+Discogs requests per surface, 429 rate, cache hit, UI stalled frames, cover accuracy, attribution and data freshness. Acceptance thresholds set against measured baseline, not invented figures.
- Smoke on Galaxy A54: open artist and releases via Home/Search/Library, rotate/back/scroll/leave/re-enter, airplane mode/restart, expired cache, denied auth, failed JSON, large discographies, concurrent image fetch. Check no unauthorized stream or Discogs art.
- Rollback: provider gate off immediately; purge its sidecar cache only; no downgrade/destructive schema operations; canonical entities and user data remain byte-for-byte unchanged. If #209 is not merged or terms uncertain, DO NOT enable Discogs in production; this PR may remain draft.
- Audit table of case/result/HEAD/fixture or device reference/observed time, explicit FAIL/INCONCLUSIVE, no passing status based on plan or code existing.

## 9. Decisions vs unknowns

**Decisions for this design**: MBID canonical; LB listens only; Discogs editions only; no new UI; no Discogs artwork by default; no forced match; no personal token embedded; independent single PR; offline canonical fallback; Android only.

**Unresolved gates**: live unauthenticated GET behavior & API quota in Android; whether/how a future token flow is appropriate; legal review of GPLv3 + Discogs API and six-hour terms; exact latest #209 interfaces after its merge; observed match coverage and UI performance. Do not misstate any unresolved item as implemented or proven.

## Primary references

- Discogs API ToU (2025-05-27): https://support.discogs.com/hc/en-us/articles/360009334593-API-Terms-of-Use
- Discogs developer specification: https://www.discogs.com/developers/
- Discogs master grouping rules: https://support.discogs.com/hc/en-us/articles/360005055493-Database-Guidelines-16-Master-Release
- MusicBrainz API: https://musicbrainz.org/doc/MusicBrainz_API
- MusicBrainz ReleaseGroup Discogs relationship: https://musicbrainz.org/relationships/release_group-url
- ListenBrainz popularity: https://listenbrainz.readthedocs.io/en/latest/users/api/popularity.html
- Existing fork readiness: ../catalog-178-editorial-artwork-provider-readiness.md
- Existing artwork policy: ../catalog-178-artwork-cache-performance-audit.md
- Baseline acceptance: ../catalog-178-android-55-case-acceptance.md
