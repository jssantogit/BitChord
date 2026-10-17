# Remote Canonical Library Browse — implementation plan

> Slice 4 · Catalog & Providers · 2026-10-17

## Goal

Make a successfully synchronized remote canonical catalog browsable from Library without contacting OpenSubsonic/Navidrome, without fabricating `Song.videoId`, and without adding remote playback.

Runtime direction:

`canonical-catalog.db → provider-agnostic browse projection → Library UI`

The OpenSubsonic API remains a sync concern only.

## Constraints

- Base: `2904d739fd9b7fab6d4c457a074032b5ed9adc75`.
- Branch: `catalog/remote-library-browse`.
- Do not alter canonical entity schemas or canonical ID semantics.
- Do not introduce canonical Artist.
- Do not touch PlaybackResolver, Queue, MediaSession, authenticated streaming or direct play.
- Do not adapt remote rows into `LocalFolder`/`Song.videoId`.
- No emulator/Maestro.

## Read model

Add the smallest provider-neutral browse boundary over Room: canonical Recordings currently available through the selected provider plus their canonical MediaAsset references.

The UI model exposes:

- `recordingId` as musical row identity;
- `mediaAssetId` + provider key as availability references;
- title, `artistDisplay`, duration and stored artwork reference.

It deliberately does not expose provider `sourceKey`, server track IDs, stream URIs or `videoId`.

Room returns a `Flow`. Grouping/sorting is performed once per database emission in the repository, not during Compose recomposition. No polling or network dependency is introduced.

## Why Songs only in Slice 4

The canonical catalog already has `Release` + `ReleaseTrack`, but `MediaAsset` does not identify which Release occurrence produced that provider asset, and provider release identities intentionally survive availability loss. A provider-specific album surface could therefore infer availability through the Recording and show a stale/ambiguous Release after later syncs.

Albums/Releases remain canonical catalog data, but this checkpoint does not expose them in remote browse. Adding that surface later should follow a domain decision that can associate availability with release membership without guessing. No shared schema change is introduced here.

## Library integration

When the OpenSubsonic account metadata exists, Library exposes an OpenSubsonic/Navidrome row beside the other Library sources. Its display `ShelfItem` has both `browseId = null` and `videoId = null`; a small optional `LibraryLink.onClick` callback opens the canonical screen directly instead of routing through the legacy browse/detail domain.

Opening the entry renders a Songs-only canonical browse screen backed only by `canonical-catalog.db`. Track rows are intentionally non-clickable because remote playback belongs to Playback & Queue.

Remote logical artwork references remain data/provenance. The browse does not turn opening Library into an authenticated artwork/network fetch; logical remote artwork uses a bounded placeholder until a later cache/artwork-resolver boundary exists.

## Offline behavior

A successful sync persists the complete snapshot before browse. Opening/reopening the browse only observes Room, so server reachability and credential availability do not participate in browse. Forgetting the connection removes the Library entry but does not delete canonical Recording/Release data.

## TDD gates

Regressions written before production implementation cover:

1. synchronized tracks appear in the read model;
2. final row identity is canonical and exposes no `sourceKey`/`videoId` field;
3. browse remains readable after provider/network failure following a successful sync;
4. a provider with no assets yields an empty library;
5. a later resync updates the browse model while preserving canonical IDs;
6. forgetting account configuration leaves canonical Recording/Release data intact.

Release grouping is intentionally not implemented in this slice, so the conditional grouping gate does not apply.

## Verification

Final gate:

- `:app:testDevDebugUnitTest`
- `:app:assembleDevDebug`
- APK artifact upload
- manual Android: Sync Now → Library → OpenSubsonic/Navidrome → verify 364 tracks; force-close/reopen with Navidrome unavailable → verify the same cached library remains browsable.
