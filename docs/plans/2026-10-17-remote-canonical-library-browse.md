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

Add a provider-neutral browse boundary over Room with two projections:

1. available canonical Recordings + their canonical MediaAsset references;
2. canonical Releases + ReleaseTrack membership for recordings available through the selected provider.

The UI model exposes:

- `recordingId` as musical row identity;
- `mediaAssetId` + provider key as availability references;
- title, `artistDisplay`, duration and stored artwork reference;
- `releaseId`, release metadata and ordered release membership where available.

It deliberately does not expose provider `sourceKey`, server track IDs, stream URIs or `videoId`.

Room queries return `Flow`s. Grouping/sorting is performed once per database emission in the repository, not during Compose recomposition. No polling or network dependency is introduced.

## Library integration

When the OpenSubsonic account metadata exists, Library exposes an OpenSubsonic/Navidrome entry. Opening it renders a canonical browse screen backed only by `canonical-catalog.db`.

The screen prioritizes Songs and also exposes Releases because the existing schema already has canonical `Release` + `ReleaseTrack` relationships. Song rows are intentionally non-playable in this slice.

Remote logical artwork references remain data/provenance. The browse must not turn opening Library into an authenticated artwork/network fetch; unsupported remote artwork schemes fall back to a placeholder until a later cache/artwork-resolver slice exists.

## Offline behavior

A successful sync persists the complete snapshot before browse. Opening/reopening the browse only observes Room, so server reachability and credential availability do not participate in browse. Forgetting the connection removes the Library entry but does not delete canonical Recording/Release data.

## TDD gates

Write regressions before production implementation for:

1. synchronized tracks appear in the read model;
2. final row identity is canonical and exposes no `sourceKey`/`videoId` field;
3. browse remains readable after provider/network failure following a successful sync;
4. a provider with no assets yields an empty library;
5. a later resync updates the browse model while preserving canonical IDs;
6. forgetting account configuration leaves canonical Recording/Release data intact;
7. Release grouping contains the correct tracks in track-number order.

## Verification

Final gate:

- `:app:testDevDebugUnitTest`
- `:app:assembleDevDebug`
- APK artifact upload
- manual Android: Sync Now → Library → OpenSubsonic/Navidrome → verify 364 tracks; force-close/reopen with Navidrome unavailable → verify the same cached library remains browsable.
