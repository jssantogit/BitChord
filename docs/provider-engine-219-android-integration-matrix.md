# Provider Engine 2.0 — Android integration audit (PR #219)

Date: 2026-11-16. Scope: Android only. GitHub issue [#218](https://github.com/jssantogit/BitChord/issues/218). This document is an implementation map, **not** a merge approval.

## Provider × capability × runtime integration

| Provider / instance | Capability | Implementation and admission boundary | Status | Tests |
|---|---|---|---|---|
| MusicBrainz | CATALOG_SEARCH / IDENTITY | MusicBrainz catalog/client search + canonical search projections | Connected | MusicBrainz catalog/index tests, ProviderCachedReadAccessTest |
| MusicBrainz | EDITORIAL_DISCOGRAPHY | Catalog graph/index; revocable Room cache read | Connected | ProviderCachedReadAccessTest |
| iTunes | CATALOG_SEARCH / EDITORIAL_DISCOGRAPHY | KtorItunesCatalogTransport + RateLimitedProviderTransport, backed by #216 | Connected; #216 reconciliation required | Apple editorial, ItunesCatalog tests |
| iTunes | RELEASE_ARTWORK | AppleHighResolutionArtwork + probe guard and identity verification | Connected | AppleHighResolutionArtworkTest |
| Cover Art Archive | RELEASE_ARTWORK | PolicyGuardedCoverArtArchiveTransport + cache eligibility; separate Front-v3/v5 DAO leaks blocked | Connected | MusicBrainzPublicMetadataIndexTest, ProviderCachedReadAccessTest |
| Apple Artist Art | ARTIST_ARTWORK | Exact storefront identity; guarded HTML HTTP; independently revocable offline image warming | Connected | AppleArtistArtCapabilityTest, VisualStillFileStoreTest |
| Discogs | EDITION_METADATA | Disabled by default, needs optional encrypted token for authenticated search; authenticated and public index routes both policy guarded | Connected conditionally; explicit opt-in and credentials | DiscogsAuthenticatedDiscoveryTest, DiscogsPublicArtistDiscoveryTest |
| ListenBrainz | PUBLIC_PLAYLISTS | Anonymous Ktor search/detail/recording evidence; revocable network + read cache; separate CAA cover permission | Connected | ListenBrainzPublicPlaylistTest, ListenBrainzPublicPlaylistSingleFlightTest |
| ListenBrainz | CHART_MOST_PLAYED | Anonymous popularity/sitewide/top-artist queries + revocable network and TTL cache | Connected | ListenBrainzPopularityClientTest, ListenBrainzSitewideClientTest |
| ListenBrainz | PERSONAL_RECOMMENDATIONS | Account-scoped experimental CF Recording candidates; independent admission/cancellation, including token validation, retry and in-memory cache | Connected at adapter; current UI wiring requires #212 reconciliation | ListenBrainzRecommendationClientTest |
| ListenBrainz | LISTENING_STATISTICS | Direct playing-now/finished and persistent WorkManager outbox; independent user consent + canonical dispatch | Connected | ListeningSubmissionGateTest, ListenBrainzSubmissionStatusTest |
| Last.fm | LISTENING_STATISTICS | track.scrobble/now playing gated; account authorization remains separate | Connected | ListeningSubmissionGateTest |
| Lyrics providers | LYRICS_LOOKUP / variants | Canonical selection bridged from legacy settings, per-instance opt-out | Connected for offered sources | ProviderRuntimeMigrationSafetyTest, provider preference/compatibility tests |
| Addon | EXTENSION_PLAYBACK and optional DOWNLOAD | SourceRegistry exact instance tickets; isolated playback/download AddonSource instances; revocation cancels detached SharedCalls and underlying OkHttp | Connected at source dispatch; Media3 HTTP byte/call gate wired; source-cache complete-hit provenance and Cast handoff admission now guarded; exact-HEAD CI and device acceptance outstanding | ProviderCatalogSourceRegistryTest, AddonSourceTest, SharedCallsTest |
| Custom module (QuickJS) | EXTENSION_PLAYBACK and optional DOWNLOAD | Isolated playback/download ModuleSource managers; ProviderQueryExecutor cancellation, shared producer cancellation and cancellable JS/index/host fetch HTTP | Connected at source dispatch; Media3 HTTP byte/call gate wired; source-cache complete-hit provenance and Cast handoff admission now guarded; exact-HEAD CI and device acceptance outstanding | ModuleSourcePolicyTest, ModuleNetworkCancellationTest |
| Soulseek | DOWNLOAD | Native manual search now denies without canonical DOWNLOAD ticket as well as legacy pilot readiness; active socket/transfer revocation still requires separate audit | DevDebug manual entrypoint admitted with canonical DOWNLOAD ticket; tracked search listeners/peer sockets revocable. Partial pending device/entrypoint acceptance | ProviderCapabilitySessionTest |
| Local MediaStore | PERSONAL_LIBRARY | Local personal data never depends on remote editorials | Connected locally; not subject to external network gate | provider provenance tests |
| OpenSubsonic / Navidrome account | PERSONAL_LIBRARY / AUDIO_PLAYBACK | Per-instance canonical session descriptors and independently revocable permissions now reach Room availability, Home/Search, Spotify import, sync, artwork cache and Media3 read cancellation; persisted account realm segregates old assets | Implemented in code; exact HEAD CI/physical acceptance not yet verified | ProviderEngineEndToEndIntegrationTest, OpenSubsonicLegacyRealmMigrationTest, OpenSubsonicMediaAccessTest, OpenSubsonicSyncRecoveryRoomTest, OpenSubsonicArtworkAccessTest |
| AcoustID | ACOUSTIC_IDENTIFICATION | No implemented adapter | NOT_IMPLEMENTED | Inventory only |
| Store sales | CHART_SALES | No implemented adapter | NOT_IMPLEMENTED | Inventory only |

## Security / retention invariants

- **Fail closed** if policy not installed or migration failed; no implicit provider fallback.
- No provider is mandatory. The last enabled provider can be disabled; an empty selection persists through restart.
- Revocations are scoped to **(capability, provider instance)**, not all capabilities of one vendor.
- Editorial/artwork cache stays provider-scoped. Source-backed **audio** cache is separate: complete hits require an exact current winning-instance ticket, active reads revoke, and incomplete/unknown-provenance spans are dropped before a fresh resolution; never use a stale cache row to implicitly grant playback.
- CAA Front-v3/v5 artwork must satisfy its own authorization, TTL and URL criteria; listening/public playlist metadata can remain visible with CAA off.
- Apple artist requests and visual-still downloads cancel underlying OkHttp calls. Partial visual-still files are never promoted after cancellation.
- Addon and custom-module source dispatch is capability-scoped; denying playback does not implicitly deny authorized downloads from the same configured instance. Detached producers are cancelled on capability revocation.
- Soulseek active-session sockets, devDebug manual-search listeners and registered peers are closed on canonical DOWNLOAD revocation; credential changes invalidate bound tickets, including a last-moment admission check before tracked registrations and durable publication.
- ListenBrainz personal submissions stay account-scoped. Offline queued occurrences are not discarded simply because a canonical capability is temporarily revoked; do not retry while disabled.

## Validation and merge blockers

- **Android CI SUCCESS on the exact final HEAD remains mandatory.** Intermediate or canceled runs do not count.
- PR #219 is stacked on draft PR #216. Final integration of the latter must not introduce competing catalog routes or duplicate provider authority.
- PR #212 is an independent frozen UI. No new UI, shadow screen or layout changes may replace its pixel parity; the planned native Sources tabs must be integrated only once the UI dependency can be reconciled. Its mergeability may change independently; do not mistake absence of Git conflicts for pixel parity or approval.
- Physical smoke, merge, and release are not authorized at this stage.
- Android CI [#43932559597](https://github.com/jssantogit/BitChord/actions/runs/43932559597) passed on code HEAD `b11610a` for the addon, module and Soulseek cuts above. Android CI [#43934240210](https://github.com/jssantogit/BitChord/actions/runs/43934240210) also passed on the documentation-only former HEAD `f483e82`. Both are historical gates: subsequent code changes require a fresh exact-HEAD SUCCESS.
- Still audit downstream media bytes, server/provider redirects, personal-data provenance in every presentation flow, active P2P pilot entrypoints and final source list UI before closing #218.

This document intentionally distinguishes implemented cuts from incomplete acceptance, and should be revised from code and CI evidence rather than assumed green.

## Hardening continuation — 2026-11-16 (after `f483e82`)

The following changes are code-level cuts, **not** final integration/merge approval:

- **No implicit Soulseek opt-in on upgrade:** `ProviderAuthorityCoordinator.migrateOnce` no longer appends an enabled Soulseek choice to an already-persisted `DOWNLOAD` category. A persisted empty list and an existing addon-only selection stay untouched; only a genuinely absent category receives the first-install import baseline. Regression tests cover both cases.
- **Credential / P2P lifecycle:** `SoulseekCredentialVault.save/forget` invalidates the previously bound P2P session ticket and refreshes canonical Soulseek readiness after the vault transition. Unit tests prove old sockets close before readiness refresh, late socket registration is refused and a subsequent admission must bind a new ticket. This is not evidence of complete A0 entrypoint coverage.
- **Independent dispatch order:** `SourceRegistry.active()` and `activeForDownload()` now select provider instances using their own `EXTENSION_PLAYBACK` and `DOWNLOAD` canonical priorities respectively; legacy rank and persisted list order serve only as stable fallbacks. Regression tests cover opposite priority orders for the same addon and module.
- **Still open:** the `PlaybackService` source-backed stream path hands resolved URLs to generic Media3 `OkHttpDataSource`; in-flight media-byte revocation and redirect scope are not yet proved. The experimental A0 P2P transports require an actual entrypoint audit, including consent, socket lifecycle, durable publication, credential changes and device acceptance. The new native Sources tabs, provider provenance across presentation flows, and frozen #212 / catalog #216 reconciliation remain separate blockers.
- **CI:** this continuation changes code and tests after `f483e82`; the exact final commit must pass Android unit/build and both shared test steps. Earlier green runs must not be transferred to the new SHA.

## Continued hardening: real A0 entrypoint + Media3 bytes — 2026-11-16

- **Real devDebug A0 session admission:** tests `fef36f6`, implementation `2ec1852` + `3424117`. `A0ManualLoginActivity` calls `SoulseekActiveSession.admitManualSession(ProviderRuntime.gateOrNull())` before credentials/network work; refusal is fail-closed. The server, search listener, directly accepted peers and indirect search peer sockets are generation-bound and closed on DOWNLOAD revocation. P/F transfer handoff remains registered separately and A3's durable commit still uses `commitIfCurrent`. No shared UI or prod release entrypoint was changed.
- **Media3 source-backed HTTP bytes:** tests `85eb77a` and `b0037f`, implementation `fb4def8`, `af600a2`, `4a8f6ea`, `811eaf4` and `a64dc9d`. The winning addon/custom-module instance now supplies an in-memory `ProviderPlaybackByteLease` via `DataSpec.customData` to a post-resolution datasource. A disabled instance is denied before opening and on both sides of each read, leaving unrelated providers untouched.
- **Physical cancellation:** because `OkHttpDataSource.close()` alone cannot guarantee stopping `open()` before response headers arrive, a per-datasource `ProviderPlaybackCallFactory` tracks the current OkHttp `Call` and cancels it on capability revocation. A MockWebServer `NO_RESPONSE` regression asserts the suspended request terminates; results remain provisional until this exact HEAD passes CI.
- **Explicit residual limits:** existing source-owned **audio cache** reads can bypass this post-resolver network layer, so full authorization of cached playback still needs verification. Cast receivers use a handed-off remote URL and are outside in-process call cancellation. Redirect target/credential-header boundaries must be audited and tested. The A0 transport still needs final live devDebug/device acceptance. Do not assert all of these are solved based on a unit/build CI.
- **Acceptance:** latest exact-HEAD Android CI (unit, Automix, native UI presentation) is mandatory after these changes. This document and the GitHub issue track code evidence; neither authorizes merging PR #219 or replacing the frozen #212 UI.

## Source-backed playback boundary — further code and test cuts (2026-11-16)

- **Repeatable Media3 opens:** `70d3b12` (regression tests), `8bf9874` (implementation). A normal `DataSource.close()` no longer permanently poisons the per-source OkHttp factory; reuse for seek, next item and explicit opt-in after revocation is tested. Old tickets still fail closed.
- **Unrevocable Cast handoff:** `87ee22b` (tests), `03fd09f` + `5f41962` (implementation). `resolveForCast` rejects `bitchord://source` and `bitchord://canonical` before resolving or exporting a provider URL. Local and ordinary non-provider media admission is unchanged. This is a deliberate fail-closed limit, **not** new authorized Cast support for extensions.
- **Source audio cache:** `e71978a` (policy tests) → `fe7dd0d` (pure ticket admission), `159f085`, `65f4e0f`, `0150d2d`, `3f516fc` (integration). The resolver attaches the *winning* provider instance to `DataSpec.customData`; `AudioCache.recordServed` tags its ephemeral source cache entry with the same non-secret instance identity. A complete hit is admitted only for that instance and subscribed to revocation; partial or unproven cached spans are evicted before another source resolution, preventing mixed-provider audio and bypass of a disabled source. If an entry is in use and eviction fails, playback fails closed. This covers logical `bitchord://source` cache entries, not every historical alternative cache key.
- **HTTP redirects:** `7886262` (MockWebServer regressions) → `b29d6ad` (implementation). Protected source HTTP calls enforce original scheme/host/port **on every actual network hop** via a network interceptor before sending redirect requests. Same-origin redirects continue; ordinary non-provider streams retain normal redirect handling. Custom source headers are not forwarded to a different HTTP origin.
- **Verification gate:** new source/cache/Cast/redirect changes have not inherited the old green CI. Require Android unit tests, APK build, shared Automix and native presentation checks on the exact final documentation/code HEAD. Physical Soulseek and playback/caching acceptance and #212/#216 reconciliation remain independent blockers.


## Retired watch-cache bypass — 2026-11-16

- **Verified code boundary:** `PlaybackService` rejects archival `bitchord://watch?v=...` or other historical video-ID requests, but `AudioCache.playbackFactory` sits *outside* that resolver. Existing persisted spans could satisfy the logical request and play audio before the expected retired-route rejection.
- **TDD cut:** `8b4a3b6` adds regression cases for historical vs local/canonical/source routes; `e94b10c` introduces a pure URI classification helper; `6799ac2` makes historical video-ID routes bypass cache and reach the existing denied resolver; `11b712b` and `8676874` guard opaque URIs against query parsing failures.
- **Behavior:** historical video-ID bytes are not replayed solely from the old cache. Local MediaStore, `content://`, source-backed extensions and canonical remote media follow their existing cache/provider paths. This does not delete cache files as a side effect; separate cleanup remains a storage concern.
- **CI:** require exact final HEAD Android unit/build and shared test SUCCESS before declaring this additional guard validated. No smoke was performed.


## Verified old progressive cache — 2026-11-17 (new code awaiting exact-HEAD CI)

- Typed source preflight now probes a complete, provider/instance-stamped old-style *progressive* file before network selection. Requires exact media identity, content-length-backed full spans, recognized audio container header, and current EXTENSION_PLAYBACK ticket. No URL or credential is reconstructed from cache metadata.
- Such entries are retained through the legacy foreign-cache cleanup, but retention does NOT grant playback permission. At playback, each cache hit is independently reauthorized; if bytes disappear between preflight and open, it fails without a second provider decision or network call.
- Unknown provenance, incomplete spans, unrecognized containers and HLS/DASH manifests cannot use this offline compatibility route. Older untagged entries are deliberately not trusted and need a fresh online resolution.
- The normal HLS/DASH physical-URL selection is unchanged, as is the frozen #212 UI. The new HEAD still requires CI and device-level offline acceptance; no merge/smoke is implied.

## Cache ownership and APK version retention — 2026-11-17 (pending CI)

- SourceRegistry now stores an opaque `playbackCacheEpoch` generation in encrypted preferences, migrating missing historical fields. Endpoint/source-kind edits rotate the generation even when the original URL is later restored. Cosmetic edits, reordering, opt-out and canonical projections preserve it.
- New logical progressive source-cache metadata stores `bc_source_scope_v2`: SHA-256 of provider, instance, kind, base URL and persisted generation. Preflight and cache opens require exact matching stamp; each cached read rechecks scope. An unverified old cache entry cannot be retroactively assigned new provenance after source resolution.
- Complete old progressive entries with a recorded owner but no scope are *quarantined*, not authorized for offline playback. Their bytes cannot be retroactively proven to belong to the current account. Reauthentication/migration requires trustworthy external evidence.
- APK version updates no longer wipe the complete AudioCache indiscriminately. Existing retired-watch gate and selective matching invalidation remain.
- **Still open**: credentials/accounts changed wholly inside an addon/module server with unchanged local config cannot be detected offline; complete isolation requires a provider account-revision/realm contract or fresh authorization. New exact-HEAD CI, Media3/device acceptance and #212/#216 reconciliation remain blockers. No UI changes, smoke or merge.

## New physical progressive cache path — 2026-11-17 (pending exact HEAD CI)

- The typed Media3 preflight emits a physical HTTP root URL and scoped provider ticket; the resulting HTTP DataSpec no longer traverses old `recordServed(bitchord://source)`. The prepared factory now passes original logical recording identity and the selected physical root to AudioCache.
- All source-owned HTTP resources use a SHA-256 key including provider, instance, full URL, effective headers and persisted configuration scope. For the **main progressive root only**, AudioCache attaches `bc_media_id`, source provider, instance, `bc_source_scope_v2`, and `bc_source_physical_progressive_root_v1` to the physical cache bucket. No raw URLs, signed credentials or headers are saved in its metadata or keys. HLS/DASH manifests and segments are not indexed as full progressive recordings.
- Future deferred IO preflight scans the cache's own metadata as a durable self-index, requires full content-length spans, audio container signature, matching current source scope and a fresh EXTENSION_PLAYBACK ticket. It returns a **logical** MediaItem with the verified opaque physical key as `customCacheKey`, allowing Media3's progressive parser to read the exact file without resolving any provider or reconstructing HTTP credentials. Miss/eviction/revoke fail closed.
- A new preparation discards incomplete old HTTP root spans; seeks/range opens of the *same prepared item* may resume that item's own partial spans. Cache LRU removes the resource and its index together. The historical logical cache has a separate non-retroactive account-proof gate.
- SHA-256 fingerprint calculation moved out of playback into `data.providers` to honor `CanonicalRemotePlaybackTest` / OpenSubsonic separation.
- Acceptance remains dependent on exact-HEAD CI, real Media3 playback/offline test, detecting remote account rotation outside local configuration (requires provider account revision), and #212/#216 reconciliation. No UI/merge/smoke.

- Follow-up audit: every HTTP range/segment open now carries the current source configuration scope into its revocable read guard, and the opaque key's embedded provider/instance must match cache provenance. Tests cover scoped logical cache key override, wrong provider identity, and epoch serialization round-trip.

- Playback-variant hardening: the physical root self-index also stores the existing logical source cache partition (mix/stereo and rendition suffix). Offline preflight and open refuse a physical root attributed to the same recording but a different logical playback variant. This preserves the historical Atmos-versus-stereo cache separation.

## Encrypted source preference forward compatibility — 2026-11-17 (exact-HEAD CI required)

A cache-epoch migration may not rewrite a stored source list using only the types that this build can decode. A future SourceKind, extra future fields on a supported source, and corrupted JSON must be preserved without loss. `SourceRegistry.sourcesDocumentPreservingUnknown()` updates decoded source entries, retains unknown JSON entries and fields, discards only recognized retired/removed entries, and refuses to replace a malformed top-level document. All normal registry persistence uses the same lossless merge. Regressions cover future kinds, unknown fields, retired known kinds, malformed JSON and future kinds with colliding IDs. Runtime does **not** enable unknown providers.

## SourceRegistry durable mutation gate (2026-11-17)

The source connection registry now commits preference changes synchronously **before** publishing a modified in-memory source list or rebuilding per-source clients. A failed/throwing commit keeps the old runtime configuration and avoids advertising a new endpoint/cache epoch that would disappear on process restart. First-run migration durably commits the source list and legacy retired-source migration marker together before constructing sources; failed migration is closed instead of activating uncommitted providers. This is independent of the canonical per-capability ProviderPreferenceStore, which already uses durable commits. Focused tests cover false/throwing writes, null/unknown documents and stable opt-out+epoch migration.

## Instance-level quarantine reporting (2026-11-17)

`ProviderCapabilitySession.publishSnapshot()` now reports `quarantinedProviders` (capability, provider instance), not only a coarse capability set. When an opt-out commit fails, the revoked provider is visible as quarantined and its sibling remains authorized. A subsequent successful save clears quarantine without reviving the disabled provider. This is headless state only; it does not change the frozen #212 interface.

## Reconciliation of PR #217's previously missing artifacts — 2026-11-17

Compared complete trees and the 55 changed files of the independent draft PR #217 against the present #219. Seven PR #217-only files were missing from #219, including the `MusicBrainzCachedSearchRepository` domain adapter, its Room/provenance tests, HTTP admission tests and the original implementation plan. These files were restored in a reviewed integration, not a blind merge, with new coverage for personally owned MBID recordings in the **reactive** cached search flow.

The restored MusicBrainz cached search repository uses the provider-gated Room boundary for editorial artists, groups, releases and recordings. Once MusicBrainz `CATALOG_SEARCH` is disabled, it instead returns ONLY recordings with actual canonical `MediaAsset` ownership evidence, without erasing their MBIDs or locally owned data. Its reactive observer re-queries the local-only projection after opt-out; defaulting to an empty snapshot would incorrectly hide local audio and is forbidden. A real Room + StateFlow test asserts initial editorial + personal results, opt-out personal-only, reactivation without reimporting the content.

PR #217's end-to-end capability/session, shared MusicBrainz transport cancellation, client admission, provenance unit tests and architecture plan are restored. This does **not** imply that independently diverging implementations are identical: the remaining overlapping files need code-level comparison and the exact final #219 HEAD needs a full new Android CI. Physical device acceptance is explicitly deferred to **after the REA #212 merge**; no UI changes here.

## Critical MusicBrainz HTTP capability reconciliation — 2026-11-17

The previous #219 baseline retained #216's bounded XML proof transport and metadata HTTP telemetry, but had lost #217's provider admission in `MusicBrainzClient` and explicit capability passed by `MusicBrainzCatalogSearch`/`MusicBrainzCatalogGraph`. Merely restoring #217's cached repository would not address this direct HTTP bypass.

The reconciled client restores a capability+instance ticket before dispatch, checks admission after the shared 1rps limiter wait, before transport, after response and before returning, and cancels shared in-flight HTTP work when a capability is revoked. Requests are coalesced only with matching transport, provider gate, ticket revision, capability, path, parameters, user-agent and bounded/unbounded proof limit. Different capabilities and differently bounded response bodies cannot share a producer response. On Android, missing runtime policy fails closed; plain JVM tests may use explicitly injected transports/gates. The #216 bounded HTTP payload cap, telemetry, backoff and release XML destructive-reconciliation proof remain intact.

All public MusicBrainz searches pass `CATALOG_SEARCH`; release/recording/release-group graph, artist appearances and bounded release XML proof pass `EDITORIAL_DISCOGRAPHY`; standalone canonical identity enrichments and artist URL lookup use `IDENTITY`. Restored #217 admission/single-flight/cancellation tests and new bounded-vs-unlimited flight isolation regression cover these boundaries.

No UI paths change. Device acceptance is deferred until after REA #212 merge, but capability bypass and Android CI remain mandatory before motor merge.

## Strict personal-asset provenance in cached MusicBrainz searches — 2026-11-17

A MusicBrainz MBID with **any** MediaAsset was incorrectly treated as a personally owned recording. A CDN preview or unauthorized remote provider asset cannot grant that proof. The new `musicBrainzRecordingMbidsWithPersonalAssets` DAO method requires exact current personally authorized provider identities. The headless repository defaults to MediaStore only and accepts an explicitly configured, re-evaluated allowlist of `opensubsonic:<account>` instances; unknown/editorial provider IDs are discarded even when supplied. The adapter must be constructed with a live authorized account-key reader to expose personal server assets after editorial opt-out. The old unscoped DAO query remains for non-authorization contexts, but is never used to assert personal ownership in the new repository.

Room regressions verify a real local file stays visible after MusicBrainz is disabled while an iTunes CDN preview does not, and a remote personal asset becomes visible only when its own configured OpenSubsonic account key is present. This is presentation/provenance logic without any Compose UI change, and tests do not treat an MBID as proof of playback.

## MusicBrainz bounded XML retry safety — 2026-11-17

Android CI on `1de0ea9` executed 3,889 unit tests, with two failed bounded XML response tests: oversized declared-body and chunked-stream responses reached the shared HTTP retry policy as generic `IOException`, resulting in unnecessary extra network requests. Deterministic size/declared-length failures now throw `MusicBrainzBoundedResponseException` and are propagated immediately; transient IO and 429/503 retry behavior remain unchanged. Three bounded HTTP fixtures require a specific non-retriable exception and exactly one request. New exact-HEAD CI required; do not project the old green result.

## OpenSubsonic account identity and stale state audit — 2026-11-17

**Implemented in #219 (focused regression, pending exact-HEAD Android CI):**

- A saved connection test captures the account revision before reading credentials. A successful result is discarded if server, credential or account changes during the suspended probe, and a failed obsolete probe cannot overwrite new-account health. Tests cover both success-after-server-edit and error-after-credential-rotation; probe exceptions remain redacted.
- The account repository retains connection health and sync timestamps only when the same authenticated realm is reused without credential replacement. New server/rotated credential clears stale diagnostics, not canonical recordings or personal MediaAssets.
- OpenSubsonicCatalogSync now rechecks generation and account validity after applying snapshot assets and remote rating evidence, before Room transaction commit. A deterministic in-memory Room regression changes the account during canonical ID allocation and asserts full rollback.

**Historical blockers at this checkpoint (superseded for Navidrome by the later implementation below):**

1. The ProviderCapabilityRegistry inventory has no OpenSubsonic/Navidrome instance for PERSONAL_LIBRARY or AUDIO_PLAYBACK. The library provider configuration currently trusts a saved credential; canonical availability, cached browse, sync enumeration and Media3 require complete capability admission, independent opt-out and reactivation tests. Provider-owned revision leases do not replace ProviderCapabilitySession.
2. The account repository intentionally preserves account ID when changing server. Existing MediaAsset.provider = opensubsonic:<accountId> does not carry a persisted authenticated-realm claim. Old server rows must not be attributed to a new server sharing that ID, even offline. Resolve with durable realm binding, conservative migration/quarantine of unproven rows, and tests for A→B→A and process restart. Do NOT delete personal records or rotate IDs blindly.
3. Canonical provider opt-outs must not disable local MediaStore or erase user ratings, playlists and MBIDs; a remote provider slot must be independently revocable for personal browse, sync and audio. Prove cache refusal and real HTTP cancellation with and without network, not only inventory tests.
4. Changes entirely inside an addon or remote server without observable local configuration revisions remain unprovable offline until the provider/account-revision contract supplies trustworthy evidence.

No Compose/UI, REA #212 presentation, public interface or merge is changed. Device validation and Settings → Fontes remain after REA #212 merge; exact-HEAD Android CI and unresolved authorization/provenance review remain pre-merge gates.

## Canonical lyrics divergence against #217 — 2026-11-17

A full-tree comparison found three files changed in #217 but not included in #219's PR patch: CanonicalLyricsSourcePolicy.kt, ScrobbleManager.kt and MusicBrainzCatalogIndexTest.kt. A changed-file list alone cannot determine whether a behavior was preserved; each was compared at both live HEADs.

The canonical lyrics selector had indeed lost #217's per-source policy filtering. It now consults the ProviderRuntime snapshot when installed and admits only the exact eligible LYRICS_LOOKUP provider instances not quarantined, including explicit empty selection and STORAGE_ERROR/BOOTING denial. Old YouTube lyric choices remain **unconditionally retired**. Regressions cover enabled source, last source disabled, quarantine, corrupted startup and missing canonical choices. Pure non-Android test calls with no installed runtime retain explicit requested non-YouTube sources.

ScrobbleManager.kt diverges from #217, but production LastFM.postAndRead on this branch already admits both track.scrobble and track.updateNowPlaying through ListeningSubmissionGate / ProviderQueryExecutor before HTTP. Do not blindly transplant the older manager's logging and HTTP wrapper; any manager-level UX/timer changes require separate tests. MusicBrainzCatalogIndexTest.kt is larger in #219; it cannot be classified as missing solely from PR changed-file intersection.

REA #212 currently shares **four** changed files with #219, not three: CanonicalCatalogDao.kt, MusicBrainzCatalogGraph.kt, AppSettings.kt and CanonicalReleaseDetailFallbackTest.kt. Patch hunks are primarily distinct (including the REA discography retry helper and IDM's explicit MusicBrainz capability dispatch), but import/fixture and cross-branch build compatibility still require a combined-tree compile after orchestration. No merge was made and no Compose UI was edited.

## Navidrome/OpenSubsonic canonical integration — 2026-11-17 (current checkpoint)

Implementation branch: PR #219 (draft, unmerged). The historical gap list above described the pre-integration implementation, not the current state.

- **Distinct durable identities:** the account ID stays stable for user settings, while a generated catalog realm ID is retained for no-op saves and rotated after server/credential/principal changes. The provider key includes the opaque realm, so old MediaAsset rows cannot be falsely resolved or queried under another authenticated server. A→B→A does not reinstate the old provider namespace.
- **Conservative legacy migration:** accounts lacking a stored realm receive a new randomly generated realm, synchronously committed before an authorized read. Unprovable old remote rows remain preserved but quarantined in their historical provider partition until a new authenticated sync. A corrupt/failed metadata write closes availability; no original local media, playlists, ratings or MBIDs are deleted.
- **Crash-safe credential mutation:** revoke existing stream leases, synchronously clear old credential, durably save the new realm, then write new encrypted credential. Failed writes cannot pair a new secret with the old realm. A failed save requires credential re-entry rather than silently reusing old authorization.
- **Independent capability choices:** PERSONAL_LIBRARY and AUDIO_PLAYBACK are real descriptors bound to ProviderKey(opensubsonic, accountId), migrated only if not explicitly disabled. Account save/forget refresh descriptors; subsequent refreshes preserve per-capability opt-outs. Local MediaStore is not controlled by remote authorization.
- **Real dispatch gates:** catalog snapshot network+Room transaction, cached track reads, Library availability, Home remote browse, Server search, Spotify matching, private artwork resolution and bytes, and original/transcoded Media3 playback require current account, exact catalog realm and applicable capability. SQL queries no longer use broad opensubsonic:% eligibility; exact configured provider keys are mandatory.
- **Revocable asynchronous work:** ProviderQueryExecutor cancels OpenSubsonic sync on library opt-out; protected Room reads recheck post-suspension authorization. AUDIO_PLAYBACK ticket invalidates runtime requests and actively closes the in-flight Media3 HTTP DataSource; byte reads check on both sides. Artwork cached/live responses check permission and account revision before publication.
- **Regression coverage:** real Room search across independent realms, A→B→A account rotation, failure of metadata commit, legacy shared-preference migration, missing/disabled provider, live read ticket revocation, cached private cover revocation, remote snapshot rollback and local-media continuity.

**Verification status:** fresh Android CI for the exact final SHA is mandatory; do not infer success from earlier green commits or canceled/superseded runs. The #212 combined-tree build, full remaining provider-bypass audit and device/APK validation after REA #212 merge are independent gates. No merge, Compose UI, provisional Settings page or physical smoke in this work. Remote changes invisible to the local account/extension configuration still require fresh online realm proof; this cannot be promised offline.
