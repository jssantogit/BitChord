# BitChord #210 — Discogs failure register, threat model and validation gates

Date: 2026-11-07. State: IMPLEMENTATION IN PROGRESS, first adapter and tests checked in; no UI or live API enablement. CI/device/license gates remain open. Branch `catalog/210-discogs-provider-evidence-20261107`; single PR, no merge until acceptance. Pair with [architecture design](2026-11-07-discogs-catalog-evidence-design.md).

Classification: **BLOCKER** means do not enable the provider; **HIGH** means required regression coverage; **MEDIUM** means defensive tests and diagnostic treatment. This is a comprehensive *risk inventory*, not a claim that all imaginable external failures are known.

## A. External service / contractual / supply-chain risks

| ID | Severity | Failure or attack | Guard + automated or manual evidence |
| --- | --- | --- | --- |
| A01 | BLOCKER | API permission incompatible with GPLv3 fork/distribution or acquisition workflows | legal/terms review before network feature enabled; no scraping/workaround; kill switch |
| A02 | BLOCKER | Discogs images shown without permission; restricted content copied as CC0 | artwork and image endpoints off; fixtures assert URLs never become artwork candidates |
| A03 | BLOCKER | API facts outlast permitted freshness; cached metadata shown offline after expiry | source-only TTL/expiry at read/render; fake clock, airplane mode and clock-change tests |
| A04 | BLOCKER | Required notice or per-fact link omitted on one Android surface | rendering contract and Compose tests on every consumer; About notice screenshot |
| A05 | HIGH | API token embedded in public APK, GitHub/CI logs, analytics or backups | no built-in token; if future BYO token, Keystore + no-backup + redaction + revoke tests |
| A06 | HIGH | API endpoints change, auth starts being required, key revoked or service discontinued | typed 401/403/404/503/offline, circuit breaker, feature kill switch, current catalog intact |
| A07 | HIGH | Terms/CC0/API-version interpretation changes | versioned provider policy, dated compliance check gate, disable remotely only by packaged local policy/release, never silent privilege escalation |
| A08 | HIGH | User data / collection / marketplace fields collected or sent to third-party | endpoint and DTO allowlists; no marketplace/collection/profile calls; capture proxy tests |
| A09 | HIGH | Cache/server copied to product data lake or backed up indefinitely | scoped ephemeral retention; backup exclusion, purge-only provider state, migration tests |
| A10 | MEDIUM | API rate limit differs from public documentation or depends on shared IP | runtime headers, conservative limiter, 429 no data corruption, live smoke only within authorized quota |
| A11 | HIGH | Open-source dependency license vulnerability or unnecessary SDK | only existing Ktor + serialization; dependency inventory and security audit |
| A12 | HIGH | Source returns misleading/hostile user-contributed claims | validate all external fields, rights, identity, contradictions; never treat editorial fact as music identity |

## B. Identity / matching / musical semantics

| ID | Severity | Failure mode | Required defense / fixture |
| --- | --- | --- | --- |
| B01 | BLOCKER | MB ReleaseGroup treated as Discogs Master 1:1, causing remix/master cross-contamination | distinct IDs/kinds, group context only, Discogs singles-with-remixes counterexample |
| B02 | BLOCKER | Discogs Release linked to wrong MB release by title similarity | exact MB URL relation or strict independent multi-factor match; name-only reject |
| B03 | BLOCKER | External ID promoted to BitChord `RecordingId`/`ReleaseId`/`MediaAssetId` | typed identity boundaries and no MediaAsset writes |
| B04 | HIGH | Linked MusicBrainz Discogs relation was user-edited/wrong | link is anchor, corroborate artist+edition; contradiction leads AMBIGUOUS not verified |
| B05 | HIGH | Duplicate UPC/EAN used across editions, reissues, box sets or multiple labels | preserve leading zeros, checksum, never match by barcode alone |
| B06 | HIGH | UPC-A/EAN-13 representation leading zero or spaces lost on numeric cast | string-only normalization and fixtures for 12/13/8 digits |
| B07 | HIGH | Artist names homonymous, alias/ANV or trailing '(2)' misidentified | MB Artist MBID stays authority; credit non-equivalence and CJK artist tests |
| B08 | HIGH | Compilation 'Various Artists' interpreted as artist main album | publication credit vs track credit, no main artist claims without MB direct edge |
| B09 | HIGH | Featuring / soundtrack participation becomes artist-owned album | type-specific credited artist evidence, unrelated compilation omitted from main shelf |
| B10 | HIGH | Official remix or live album classified as unauthorized merely by title/format | MB status vs Discogs 'Unofficial' typed conflict, display categorization only when corroborated |
| B11 | HIGH | Discogs 'Unofficial Release' interpreted as permanent truth across all editions | edition-scoped adverse fact, expires, no RG-wide deletion |
| B12 | HIGH | Master release entry points to a mix of standard/special/remix/mini-album | never select edition from Master without explicit Release link |
| B13 | HIGH | Discogs side A/B numbering vs MB numbered CD causes false tracklist mismatch | medium-aware parser; compare ordinal semantics; ambiguous fallback |
| B14 | HIGH | Bonus tracks, interludes, hidden tracks, medleys, editions with empty tracklist | compare selected edition only; missing vs empty vs different; no false hard mismatch |
| B15 | HIGH | Same ISRC appears on multiple CDs/digital editions | ISRC is recording candidate, never album identity |
| B16 | HIGH | Different country/format, Japanese/Korean bonus edition, censored/clean/explicit | edition-only fields; no overwrite RG, track-version gate |
| B17 | HIGH | Date reported as first release when actually manufacturing/reissue/year-only | DatePrecision/year vs full date and event source; no January 1 invention |
| B18 | HIGH | Discogs link removed or reassigned while cached | refresh relationship, invalidate source claim; no local-data deletion |
| B19 | HIGH | Multiple links/discogs IDs, MBID collisions, conflicting aliases | explicit AMBIGUOUS/CONFLICT with reasons; never choose first or highest score |
| B20 | MEDIUM | Digital-only new singles absent from Discogs | no-link -> keep MusicBrainz unchanged; test discovery coverage separately |
| B21 | MEDIUM | Artist has 1000+ releases; 1-page result looks complete | bounded, cursor/total/completeness; never mark full discography complete from partial results |
| B22 | MEDIUM | Unicode normalization erases Japanese/Korean distinctions or combining marks | preserve source raw validated UTF-8, use normalization only as hint and test CJK |
| B23 | MEDIUM | Discogs genre/style mistaken for MB album format or user genre preference | advisory tag with source, never drives release type or personal rank |

## C. HTTP, app security, privacy and resilience

| ID | Severity | Failure mode | Defense |
| --- | --- | --- | --- |
| C01 | BLOCKER | Untrusted Discogs URL points to local/private/custom host or redirects there (SSRF) | parse relation host/path, construct fixed REST URL, disallow redirects to non-approved hosts |
| C02 | HIGH | API host spoofing/IDN/userinfo or untrusted content URI | punycode/host allowlist + HTTPS, never use arbitrary URL in transport |
| C03 | HIGH | Huge JSON / deeply nested tracklist / million results cause OOM | max body bytes, JSON validation limits, page/candidate caps; fuzz and load fixtures |
| C04 | HIGH | JSON schema changes, numeric ID overflow, missing fields, HTML 403 body | non-throwing DTO boundary result (MALFORMED), retain canonical state |
| C05 | HIGH | 429 retried aggressively, app/user blocked | shared Discogs token bucket, observed rate headers, bounded Retry-After and cooldown |
| C06 | HIGH | Multiple screens and providers issue N+1 calls / network storm | per-process coalescing, budgets per provider, visible-first scheduling; counts in tests |
| C07 | HIGH | Cancellation aborts another caller's coalesced request, or locks never released | owner/waiter cancellation tests, structured coroutines, finally-unlock |
| C08 | HIGH | Network slow/no DNS/socket timeout stalls UI or player | dispatch IO, timeouts, lazy nonblocking load, independent UI fallback |
| C09 | HIGH | Token leaks via URL, exception body, diagnostics/export/analytics | auth header only, redacted error type and metrics, no remote token in logs |
| C10 | HIGH | Automatic requests expose local library content, Navidrome, Soulseek query, listens | public-catalog-only opt-in scope, no playback-triggered enrichment, network capture test |
| C11 | HIGH | Malicious title/notes inject script/Markdown, bogus link or unicode control into UI | plain text rendering; sanitize unsafe controls, URLs restricted; preserve CJK |
| C12 | HIGH | Clock rollback/increase makes stale content appear fresh | compare monotonic elapsed for in-session, persisted wall-clock with conservative invalidation on clock anomaly |
| C13 | HIGH | Cross-user token scope/cached results leak across account change | no auth in first version; later per-account cache scope, logout purge, explicit tests |
| C14 | MEDIUM | Background refresh / WorkManager loop abuses cellular/battery | visible details only by default, bounded prefetch, no periodic full sync |
| C15 | MEDIUM | DNS/proxy/portal returns HTML 200 or wrong MIME | validate content type, JSON shape, provenance and reply ID |
| C16 | MEDIUM | Flaky internet causes inconsistent subsection flicker | independent loading, no stale Discogs display beyond rights TTL, stable MB fallback |
| C17 | MEDIUM | 404 incorrectly recorded as release never existed | scoped negative TTL and refresh, preserve MB identity |
| C18 | MEDIUM | Caching every failed request exhausts storage | max rows/bodies/eviction, negative transient TTLs, no raw JSON persistence |
| C19 | MEDIUM | Future provider inherits permissive rights from Discogs/Apple by default | rights UNKNOWN/RESTRICTED by default, capability matrix required per adapter |

## D. Local data, UI, CI, rollout

| ID | Severity | Failure mode | Defense |
| --- | --- | --- | --- |
| D01 | BLOCKER | Changing Room identity graph merges/removes existing library entries | provider sidecar only, migration tests from all supported schema versions |
| D02 | BLOCKER | Metadata-only entry becomes playable due to Discogs tracklist | explicit `MediaAsset` gate, playlist/queue/download negative tests |
| D03 | HIGH | Discogs facts contaminate local MediaStore/Navidrome release date or album art | separate provenance, no write-through to canonical/local tags |
| D04 | HIGH | Feature off or Discogs outage deletes cached canonical state | disable only provider, rollback/purge tests, byte-stable core tables |
| D05 | HIGH | Merge with PR #209 reintroduces duplicate artist/album UI | reuse native compositor; test Home/Explore/Search/Artist/Release/Library |
| D06 | HIGH | Competing Room migration numbers between #209 and new branch | rebase after #209 integration, renumber after inspection, upgrade smoke |
| D07 | HIGH | Discogs data shows without adjacent source link after UI refactor | compile-time projection requires attribution; Compose tests on screens |
| D08 | HIGH | Attribution link unsafe or broken; About non-affiliation missing | URL allowlist; test exact linked Discogs record; About check |
| D09 | HIGH | Discogs source event interpreted as personal listening/rating/recommendation | field policy denies edits to ListenBrainz and local Listening state |
| D10 | HIGH | Artist's library section and online 'Mais tocadas' both appear | preserve contextual mutual exclusion from #209, regression tests |
| D11 | HIGH | On-device performance degrades from extra joins/caches/network | p50/p95 and stalled-frame comparison cold/warm with provider ON/OFF |
| D12 | HIGH | New APK breaks old pinned data or playlists | direct DB migration, pre/post contents checksum, main #205 baseline |
| D13 | HIGH | Test suite uses fake fixtures only; live contract wrong | separate controlled live smoke, API statuses/headers, no false CI claim |
| D14 | MEDIUM | CI succeeds for previous commit but PR HEAD changed | exact SHA gate on Android unit, Compose/sharedUi, smoke reference |
| D15 | MEDIUM | User sees a 'verified' label when evidence only supported by two correlated community sources | no certification label; show actual field/source and confidence reason |
| D16 | MEDIUM | Offline mode removes all album cards because Discogs expired | canonical shelf independent from optional Discogs facts |
| D17 | MEDIUM | Branch wrongly based on #209 creates stacked PR or unintentionally includes YouTube migration diff | branch from main, check compare/PR changed filenames |
| D18 | MEDIUM | Multiple PRs created for transport, schema and UI | entire feature/review/checks in single PR linked to #210; commits incremental only |
| D19 | MEDIUM | Third-party artwork fallback changes wrong art in tracks and playlists | no Discogs artwork, keep CAA Front verified and existing native fallback |
| D20 | MEDIUM | No independent option to opt out of new provider | single feature gate, disable without data loss, consent/privacy documentation |

## E. Adversarial gold corpus and exact outcome contract

No acceptance by anecdote. Create reproducible fixtures and a human-reviewed holdout; benchmark denominators/p95 reported by sample stratification, not a promised coverage number.

| Stratum | Expected safe outcome |
| --- | --- |
| Direct MusicBrainz Release → Discogs Release with matching details | edition link + vetted supplemental facts, provenance shown |
| ReleaseGroup → Discogs Master only | group association, **no selected edition** |
| Same master has 12-inch remix and 7-inch single | both remain separate edition candidates |
| Explicit Discogs unofficial pressing, official MB digital release | adverse fact scoped to bootleg edition; official digital unaffected |
| Shared barcode, homonym or sparse/empty tracklist | unresolved/ambiguous rather than VERIFIED |
| Multi-CD / LP A1-B1 numbering and Japan-only bonus track | compare correct medium/edition; do not corrupt order |
| Korean Hangul, Japanese kana/kanji, Chinese scripts, Latin diacritics | preserve text/credits and correct identity |
| Missing Discogs for latest self-published single | unchanged MusicBrainz release tile |
| 429, 503, 401, 404, 200 HTML, malformed JSON, long payload | typed failure; no cache poisoning, playlist loss or UI freeze |
| 5h+ cached API data, airplane mode / clock changes | Discogs subsection disappears/refreshes; MB album remains |
| Concurrent artist/search/album navigation | bounded requests and cancellation, no data assigned to wrong screen |
| Valid MusicBrainz recording with no MediaAsset | zero Play/Queue/Download from Discogs evidence |

Release gates in order: terms and auth capability checked; TDD core boundaries; provider adapter; UI attribution; migration if required; CI at exact HEAD; manual corpus; Galaxy A54 smoke (including offline and failing network); independent regression vs merged #209 and main #205; explicit approval. Any **BLOCKER** unresolved means disable/hold PR, never quietly merge.

## F. Residual uncertainty and choices needing measured evidence

- Published community limits 25/min anonymous and 60/min authenticated are *estimates*, not reliable quotas; follow actual server headers and rate errors.
- Live Discogs API read availability and anonymous GET have not yet been executed in this planning session; provide fixture tests first and verify with authorized live calls prior to production.
- Six-hour terms language applies to API Content including CC0; technical <5h TTL is a conservative *engineering policy*, not legal clearance to bulk ingest or persist long term.
- Raw CC0 dump architecture is a separate decision: substantial data volume, dates/metadata update cycle, attribution and license differentiation, offline storage, integrity and bandwidth. Do not add it under the guise of an API cache.
- Some Discogs links have slugs and older http forms; normalize safely to HTTPS fixed endpoint after exact identifier/kind extraction, without opening arbitrary redirects.
- A provider can never assert that an album is *officially authorized* in every territory; even correlated sources may be simultaneously wrong.

External sources: [Discogs API ToU](https://support.discogs.com/hc/en-us/articles/360009334593-API-Terms-of-Use), [Discogs Master rules](https://support.discogs.com/hc/en-us/articles/360005055493-Database-Guidelines-16-Master-Release), [MusicBrainz API](https://musicbrainz.org/doc/MusicBrainz_API), [Discogs master relationship](https://musicbrainz.org/relationships/release_group-url). Review contemporary terms again before implementation.
