# Catalog #178 — Artwork performance audit and open-source patterns

**Date:** 2026-10-27 · **Scope:** Android-only BitChord fork · **PR:** #183 · **No APK, no provider integration, no merge**

## Evidence: Galaxy A54, 27 October, 19:38 BRT

Device log `bitchord-diagnostics-20261027-193831.txt`:
- CAA **metadata**: 43 requests; 42 HTTP 200, 1 transient; p50 2,282 ms; p95 3,807 ms; 45 positive cache hits and 1 coalesced call.
- Coil **bitmap**: 206 successful, 0 failures; 190 memory, 2 disk, 14 network; p50 5 ms and p95 1,781 ms.
- UI: 10 recovered stalls (largest 583.7 ms), causality not established.
- Album art already painted but tracks may stay blank. Diagnostic does not record the build SHA and cannot definitively attribute screenshot to the current PR HEAD.

### Do not confuse the three stages

1. `MusicBrainz` discovery of official release/edition/recording relationships (network, shared rate limiter).
2. `CoverArtArchive` metadata discovery (JSON Front/Poster classification; separate HTTP latency, network cache).
3. `Coil` image loading (bitmap memory/disk/network). Its 190/206 memory hits demonstrate that bitmap cache is active; a blank cover may indicate missing URL/identity rather than a failed bitmap cache.

## Similar-project findings (primary-source documentation)

| System | Verifiable practice | Relevance, with limits |
|---|---|---|
| Navidrome | Explicit artwork priority for album, disc, and media file; fallback for a file can reach disc then album. Provides `artwork status`, `explain`, `refresh`, `reprocess` and optional artwork precaching. | Reuse one album identity and maintain explainable source/negative-failure states; should not copy media-file priority into remote MusicBrainz recording without strong edition evidence. |
| Music Assistant | Stores artwork in locally bounded disk cache (default thumbnail ceiling 500 MB), oldest evicted; album art represents a track in full detail/Now Playing, with optional track-specific art in lists. | Album-level canonical artwork and a deliberate cache-budget policy. 500 MB is a reference, not an automatically safe phone default. |
| Auxio | Local Android player; native metadata extraction and on-device cover storage for fast access. | Minimize expensive repeated extraction and metadata work; not a direct comparison for internet CAA latency. |
| Coil 3 | Recommends a single application-wide ImageLoader (shared bitmap memory and disk caches); supports no-target preloading via `imageLoader.enqueue(request)`. | BitChord already uses `SingletonImageLoader.Factory`, 20% memory cache and a 100 MB disk cache. No evidence to replace Coil; consider measured, bounded prefetch *after* URL identity is verified. |
| Cover Art Archive | Release JSON includes typed/approved Front and the actual release URL; group `/front` is a community-selected image, not proof of a specific canonical official edition. | Retain our strict Front-v5 and Official Release→ReleaseGroup proof. Do not fall back blindly to the faster `/release-group/front` shortcut, which could display a video or an alternate sleeve. |

Sources:
- Navidrome: https://www.navidrome.org/docs/usage/library/artwork/ and https://www.navidrome.org/docs/usage/admin/cli/artwork/ and https://www.navidrome.org/docs/usage/configuration-options/
- Music Assistant: https://www.music-assistant.io/metadata/artwork/
- Auxio: https://github.com/OxygenCobalt/Auxio/releases
- Coil: https://coil-kt.github.io/coil/image_loaders/ and https://coil-kt.github.io/coil/faq/
- CAA: https://musicbrainz.org/doc/Cover_Art_Archive/API

## Concrete changes on PR #183 (no new image source)

1. **Exact fanout:** verified Front-v5 URL can propagate across all Recording rows with direct MusicBrainz ReleaseGroup evidence; fallback Room graph uses strong Recording→Track→Official Release→ReleaseGroup, never artist/title equality.
2. **Search fairness:** releases and recordings now interleaved in the fallback queue; no longer force 8 album requests ahead of the first 4 songs.
3. **Revisit refresh:** album entry and return-to-search reinherit from verified Room Front-v5 via bounded query (up to 48 missing recordings); specific track art is retained.
4. **Artist cache-first stage:** `CoverArtArchive.cachedVerifiedGroupCovers` validates positive TTL, secure host and exact MBIDs on a **single indexed Room batch**, independently of the slower initial MusicBrainz official-edition browse. The existing native shelves are patched only for still-blank exact group cards.
5. **Cancellation isolation:** if the owner of a coalesced CAA metadata request navigates away, an unrelated still-active observer reacquires the request instead of inheriting that owner's cancellation; cancelled observer does not keep fetching.
6. **Diagnostics:** anonymous counters distinguish verified artist batch hits from external CAA metadata requests, and report recovered canceled coalescing owners. No MBIDs, album names, URLs, or queries are recorded.

## Regression gates

- Unit tests: 20 Recording rows on one verified group use one cached cover read; unaffected/track-specific rows keep their original image; cache/Room errors do not remove known-good images.
- Cache-first batch test: exactly one store batch for 3 MBIDs (including expired/unsafe records), zero individual store reads, only valid fresh archive URI emitted.
- CAA cancellation test: close owner while another screen is awaiting the same cover; active waiter succeeds with fresh lookup. Own cancellation continues to propagate.
- Performance telemetry test: counter reset and privacy-safe export contract.
- GitHub Catalog/Android CI must pass for **exact HEAD**; CI is not physical performance evidence.
- Physical smoke **deferred by user**. Do not generate APK. After authorization, compare cold/warm/restart, 20 song artworks per album, same URL across tracklist, first 8 and 75-card artist shelves, network RTT, Coil sources, main-thread stalls and wrong Get Up/TWICE covers.

## Remaining engineering opportunities (not implemented / no implied decisions)

- **Near-viewport prefetch:** after MBID+Front-v5 validation, pre-enqueue only a bounded set of URL-identified artwork bitmaps in the singleton Coil loader; avoid eagerly fetching hundreds of nonvisible 1200px images.
- **Resolution provenance / explain mode:** a privacy-conscious diagnostic that describes the winning stage (specific release, official group, track own art, negative cache, transient HTTP), retaining MBIDs only within explicit local debug views, not exports. Following Navidrome's `artwork explain` concept.
- **Cache-size experiment:** measure actual disk hit rate, eviction and storage available on A54 before adjusting the existing 100 MB budget; Auxio and Music Assistant are reference architectures, not evidence of a 500 MB requirement on mobile.
- **Stale-while-revalidate:** keep known-good Front-v5 image visible while refreshing after TTL only after explicit lifecycle and provenance design. Do not silently display pre-policy or wrongly classified covers.
- **First-ever uncached album latency:** cannot be eliminated by Coil cache because MusicBrainz Official edition discovery and CAA metadata genuinely require network responses. Reduce redundant lookups, prioritize visible artwork, and preserve completed results; do not assume source replacement solves local propagation.

**Guardrails:** no Apple Music/iTunes/YouTube provider addition, no synthetic cover identity, no source/edition cross-contamination, no APK or merge until user approval.
