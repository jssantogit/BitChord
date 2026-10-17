# Slice 1 — Canonical local catalog

## Goal

Prove the canonical music model behind one real Android path without rewriting the BitChord player:

`MediaStore -> Room -> canonical rows -> legacy Song adapter -> existing UI/player`

The visible behavior must remain the same. The source of truth for Local Music becomes the new Room database after each MediaStore sync.

## Boundaries

In scope:
- Android phone only.
- A new canonical Room database owned by the app.
- Recording, Release, ReleaseTrack, MediaAsset and ExternalIdentity rows needed by real local files.
- MediaStore sync into Room.
- Reading local tracks back from Room and adapting them to the existing `Song` DTO.
- Stable canonical UUIDs across rescans while a MediaStore source key is unchanged.
- Removing stale local MediaAssets when a MediaStore row disappears.
- Preserving a known YouTube video id only as an external identity / compatibility alias, never as the canonical Recording id.

Out of scope:
- Queue identity / PlaybackResolver changes (Slice 2).
- Event Store and ratings (Slice 3).
- Library redesign (Slice 4).
- Remote providers, MusicBrainz reconciliation, recommendations, slskd, or YouTube removal.
- Full Artist canonicalization. The first slice stores the artist byline as recording/release display metadata.
- AGP/Gradle upgrades unrelated to this slice.

## Data model

### Recording
- `id`: app-owned UUID, primary key.
- `title`.
- `artistDisplay`.
- `durationMs` when MediaStore knows it.

### Release
- `id`: app-owned UUID, primary key.
- `title`.
- `artistDisplay` for the current local evidence.
- `artworkUri` when MediaStore exposes album art.

### ReleaseTrack
- `(releaseId, recordingId)` composite primary key.
- `trackNumber` when tagged.
- This keeps Recording independent from Release; there is deliberately no `Recording.releaseId`.

### MediaAsset
- `id`: app-owned UUID, primary key.
- `recordingId`.
- `provider = local-mediastore`.
- `sourceKey`: MediaStore content URI; unique together with provider.
- `uri`, `path`, added/modified timestamps and fallback artwork URI.
- The MediaAsset/source key is not the Recording id.

### ExternalIdentity
- `(provider, entityType, externalId)` unique identity mapping.
- `entityId` points at our canonical id.
- In this slice it is used when an existing BitChord download lets MediaStore URI -> YouTube video id be proven, and for provisional local release evidence.

## Identity rule

For an already-known `(provider, sourceKey)`, reuse the existing MediaAsset and Recording ids. For a new local source key, allocate new UUIDs. A re-scan updates metadata but does not mint a new canonical Recording id.

The current legacy `Song` edge no longer exposes MediaStore album id, so the first slice may use a clearly provider-local release evidence key derived from album title + artist byline. It is stored as an external/local identity, not treated as universal musical identity. A later scanner DTO can replace that evidence with MediaStore album id without changing canonical ids.

## Compatibility rule

Room-backed local rows are converted back to `Song` only at the edge. The adapter sets:
- `videoId` to a stored YouTube external identity when one is known, otherwise the local URI, matching current playback compatibility.
- `localUri`, `localPath`, title/artist/album/artwork/duration/track metadata from canonical rows.

No caller is allowed to treat `videoId` as the canonical Recording id.

## Build-tool rule

Room stable `2.8.5` uses Kotlin Symbol Processing for this Kotlin module. KSP is pinned to `2.3.11`: that release supports AGP `8.10.0+`, so it is compatible with the current BitChord baseline of AGP `8.10.1` + Gradle `8.11.1` + Kotlin `2.3.20`. KSP `2.3.12` raised its minimum supported AGP to `8.12.0`, so it must not be selected accidentally while this baseline remains unchanged.

The earlier kapt fallback was removed after CI failed in `:app:kaptDevDebugKotlin`; it also contradicted Room's current Kotlin guidance, which uses KSP for Kotlin sources. This correction stays bounded to Room code generation and does not upgrade AGP/Gradle.

## Implementation steps

1. Add Room 2.8.5 + KSP 2.3.11 build support without upgrading AGP/Gradle.
2. Add failing unit tests for the pure local canonical adapter/identity boundary before implementation.
3. Add Room entities, DAO and `CanonicalCatalogDatabase` version 1.
4. Add `CanonicalLocalLibrary` sync/import boundary with a small local candidate/compatibility mapper.
5. Keep the current MediaStore scanner working and insert canonicalization at its existing local-library seam: scanner rows sync into Room, then Local Music returns Room-backed `Song` rows. Leave downloaded-list code and all remote paths untouched.
6. Initialize/open the canonical DB lazily; do not add startup work unrelated to Local Music.
7. Preserve `extraLocalMusic` composition semantics so inactive TV scaffolding is not silently broken by the phone slice.
8. Ensure CI invokes phone builds with `-Pbitchord.bundleWear=false`; the app Gradle file otherwise generates a bundled Wear APK even though Wear is no longer an active target.
9. Keep PR #2 draft while GitHub Actions runs `:app:testDevDebugUnitTest` and `:app:assembleDevDebug`; do not run heavy builds locally.
10. After green CI, install the artifact on a real Android device and verify scan -> show -> play, restart -> show -> play, and deletion/rescan behavior.

## Tests

Pure unit tests must prove:
- a canonical row adapts to a playable legacy `Song` without leaking Recording id into `videoId`;
- known YouTube external identity is used only as the compatibility `videoId`;
- no external identity falls back to the local URI;
- MediaStore source identity is distinct from canonical Recording/MediaAsset ids;
- duration/album/track metadata survives the adapter.

Room compiler/schema checks plus the Android build verify DAO/query shape. Real-device acceptance verifies framework MediaStore + Room + Media3 integration.

## Acceptance

Slice 1 is complete only when:
1. CI is green for unit tests and the dev APK build.
2. A real local track is scanned from MediaStore and persisted in the canonical DB.
3. Local Music is rendered from the Room query, not directly from the cursor-created `Song` list.
4. The same track survives process/app restart with the same canonical identity while its source key is unchanged.
5. The Room-backed legacy `Song` plays through the unchanged BitChord player.
6. Removing a local file and rescanning does not leave a stale playable MediaAsset in Local Music.
