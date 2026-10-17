# OpenSubsonic / Navidrome canonical catalog slice

Date: 2026-10-17
Branch: `providers/opensubsonic-catalog`
PR: #3

## Goal

Prove the first remote-provider path without making the server the source of musical identity:

```text
OpenSubsonic / Navidrome
        ↓
provider adapter
        ↓
provider-neutral snapshot
        ↓
canonical Recording / Release / ReleaseTrack
        ↓
provider ExternalIdentity + MediaAsset availability
        ↓
Room
```

The provider is deliberately not coupled to the in-progress `PlaybackResolver`.

## Why OpenSubsonic first

OpenSubsonic/Navidrome remains the preferred first remote provider because it has a mature Subsonic-compatible library API, ID3-oriented album/track endpoints, explicit OpenSubsonic extensions, server-side transcoding support, and is already the preferred remote-library direction in the project decisions.

The adapter uses the stable OpenSubsonic/Subsonic surface rather than Navidrome's separate v1 API.

## Provider contract

`RemoteCatalogProvider` exposes only:

- account-scoped `providerKey`;
- `health()`;
- `capabilities()`;
- `librarySnapshot()`.

The snapshot contains normalized remote track/album data and provider evidence. It does not allocate canonical IDs.

Current explicit capabilities:

- library sync;
- health check;
- direct play availability;
- server transcoding when advertised by the OpenSubsonic `transcoding` extension;
- API-key authentication when advertised by the corresponding extension.

## Account / authentication representation

`OpenSubsonicAccountConfig` contains a local account key, server URL and authentication method.

Supported authentication forms:

1. OpenSubsonic API key;
2. legacy-compatible username + salted MD5 token (`MD5(password + salt)`).

Plain passwords are never placed in request parameters. Persisted `MediaAsset.uri` values are logical `opensubsonic://...` URIs and never contain the server URL, API key, password, token or salt.

Secret persistence/settings UI is intentionally outside this slice. The config object is the minimum connection representation needed by the adapter.

## Library enumeration and mapping

The adapter:

1. pages `getAlbumList2` using the ID3 album model;
2. fetches each album through `getAlbum`;
3. maps songs and albums into a provider-neutral snapshot;
4. carries optional MusicBrainz recording/release IDs as identity evidence;
5. carries remote artist IDs and richer audio properties in the provider snapshot, without forcing them into incompatible canonical fields.

A complete snapshot is returned only after all pages/albums were successfully read. A partial network result is never presented to the sync layer as an authoritative empty or complete library.

## Canonical identity rules

Provider IDs are never canonical IDs.

For a remote track, canonical Recording selection prefers:

1. an existing external identity for this provider/account + track ID;
2. the Recording already linked by an existing MediaAsset;
3. an existing canonical Recording linked to the supplied MusicBrainz recording ID;
4. a new locally generated canonical UUID.

Release identity follows the same principle using provider release aliases and MusicBrainz release evidence.

New MusicBrainz evidence is stored only when unclaimed. A conflicting MBID mapping is counted as an identity conflict and is not silently remapped; broad cross-provider reconciliation is intentionally deferred.

## Remote availability

Every synchronized remote track produces a `MediaAsset` whose:

- `provider` is account-scoped, e.g. `opensubsonic:home`;
- `sourceKey` is the remote track ID;
- `recordingId` is the canonical Recording ID;
- `uri` is a credential-free logical provider URI;
- local filesystem `path` is null.

A future `PlaybackResolver` can use `provider + sourceKey` to request direct play or transcoding without this slice depending on resolver interfaces that are still being developed in parallel.

## Sync and offline behavior

Network enumeration happens before the Room transaction starts.

Only a complete provider snapshot is applied. The Room apply is transactional, so a failed apply rolls back. A network/auth/server failure occurs before any Room mutation and therefore preserves the previously synchronized provider catalog.

On a successful complete sync:

- existing provider aliases are reused;
- existing MediaAsset IDs are reused;
- canonical metadata is updated in place;
- new provider assets are inserted;
- MediaAssets no longer present in the complete provider snapshot are removed;
- canonical Recordings/Releases and provider ExternalIdentity evidence are retained when availability disappears.

`providerRows(providerKey)` plus `OpenSubsonicCatalogSync.cachedTracks(...)` reads the last successful snapshot directly from Room without contacting the server, providing the minimum offline-browse path for this slice.

## TDD / validation

The provider behavior was specified in tests before the production implementation commit. The initial RED workflow run was superseded by later pushes because Android CI cancels in-progress runs for the same PR; final correctness is judged by the final-head CI.

Fixture tests cover:

- password token authentication without plain-password transport;
- API-key authentication shape;
- capability discovery;
- paginated album enumeration;
- track/album/MBID/audio-property mapping;
- canonical identity reuse;
- provider IDs remaining aliases rather than canonical IDs;
- stale availability removal while preserving identity evidence;
- network failure preserving the prior catalog;
- re-sync metadata updates while canonical Recording and MediaAsset IDs remain stable.

A real-server smoke test is present but opt-in. Normal CI skips it when no endpoint is configured.

### Optional real Navidrome/OpenSubsonic smoke test

API-key authentication:

```bash
BITCHORD_OPENSUBSONIC_URL='https://your-server.example' \
BITCHORD_OPENSUBSONIC_API_KEY='...' \
./gradlew -Pbitchord.bundleWear=false :app:testDevDebugUnitTest \
  --tests com.music.bitchord.OpenSubsonicManualSmokeTest
```

Password-token fallback:

```bash
BITCHORD_OPENSUBSONIC_URL='https://your-server.example' \
BITCHORD_OPENSUBSONIC_USERNAME='...' \
BITCHORD_OPENSUBSONIC_PASSWORD='...' \
./gradlew -Pbitchord.bundleWear=false :app:testDevDebugUnitTest \
  --tests com.music.bitchord.OpenSubsonicManualSmokeTest
```

No credential or endpoint is committed or assumed.

## Known limitations

- Sync is currently a complete album-list snapshot with one `getAlbum` fetch per album. Differential/incremental provider sync can be added later.
- Connection settings/secret storage UI is not implemented in this slice.
- Logical `opensubsonic://` media/artwork URIs are intentionally not wired into playback or image loading yet.
- Audio properties such as codec, bitrate, bit depth and sample rate are captured by the provider adapter but are not added to the current shared `MediaAsset` schema in this PR.
- MBID conflicts are surfaced/countable but are not automatically reconciled.
- Ratings, playlists, lyrics and writes back to the provider are outside this first provider-catalog slice.
- Artist names are mapped into the current `artistDisplay` fields, but the current canonical schema has no first-class Artist entity to receive provider artist IDs/MBIDs.

## ORCHESTRATOR DECISION REQUIRED — canonical Artist identity

**Current state**

The shared canonical schema currently has `Recording`, `Release`, `ReleaseTrack`, `MediaAsset` and `ExternalIdentity`, but no first-class canonical `Artist` entity/credit relation. `Recording` and `Release` only expose `artistDisplay` strings.

**Proposed future change**

Decide whether/when to introduce canonical Artist identity and artist-credit relations so provider artist IDs and MusicBrainz artist IDs can become aliases/evidence of an Artist rather than transient adapter metadata.

**Why the provider needs it**

OpenSubsonic exposes stable provider artist IDs and optional MusicBrainz artist identity, but assigning either to a Recording/Release external-identity row would misrepresent the entity type. This PR therefore refuses to persist them into the wrong canonical object.

**Impact on Playback & Queue**

No immediate playback requirement. A future Artist entity should remain metadata/navigation identity and should not change queue item or PlaybackResolver identity.

**Impact on Listening & Intelligence**

First-class Artist identity would improve artist-level aggregation, preference modelling and cross-provider deduplication. Until then those systems should use canonical Recording/Release identity and display artist metadata without treating provider artist IDs as canonical.

**Compatibility alternative used by this PR**

Persist `artistDisplay` on existing Recording/Release shapes, carry provider artist IDs only inside the provider snapshot, and make no shared schema migration. This keeps the slice independently mergeable while the orchestrator decides the future Artist model.
