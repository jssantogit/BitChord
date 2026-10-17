# Slice 5 — OpenSubsonic Runtime Playback Access

Date: 2026-10-17

## Goal

Provide the provider-owned half required for future remote playback without coupling Catalog & Providers to Media3, Queue or PlaybackResolver.

Runtime boundary:

`persisted MediaAsset → OpenSubsonic account lookup → secure credential read → ephemeral authenticated direct-audio request`

The slice stops at the request representation. It does not play audio.

## Base and ownership

- Base main: `79797a0f9b800725d28eaf5c6228b8a4a7647a10`.
- Branch: `providers/opensubsonic-playback-access`.
- The seam lives under `data/catalog/provider/opensubsonic/`.
- No type from the unmerged Playback PR is imported or copied.

## Identity roles

- `recordingId`: canonical musical identity.
- `mediaAssetId`: canonical availability/rendition identity.
- `MediaAsset.provider = opensubsonic:<accountId>`: provider/account namespace.
- `MediaAsset.sourceKey`: provider-native track locator.

The runtime seam accepts `mediaAssetId`; UI/playback consumers do not need a server track ID or a legacy `Song.videoId`.

## Account and credential lookup

1. Read the persisted `MediaAsset` by canonical `mediaAssetId`.
2. Require an `opensubsonic:<accountId>` provider key.
3. Read non-secret account metadata and verify the same accountId is configured.
4. Validate the provider source locator.
5. Only then call the existing account repository's `savedConfig()`, which decrypts the credential through the existing Android Keystore credential store.
6. Build an ephemeral runtime request.

Forget connection therefore leaves historical canonical rows intact but makes runtime access fail because account metadata/credential is absent.

## Authentication

OpenSubsonic authentication parameter generation was extracted into one shared helper used by both the catalog adapter and runtime media access.

- API key: `apiKey=<runtime secret>`.
- Password: fresh salt + `u`, `s`, `t=MD5(password + salt)`; raw password is never emitted as `p`.

Authenticated parameters are materialized only when the runtime request is consumed. They are not stored in Room, SharedPreferences, the logical asset URI, the request endpoint field or request `toString()`.

The existing account rule remains authoritative: changing server host or authentication mode requires an explicit replacement credential, so the runtime resolver cannot silently replay an old secret to a new host.

## Direct/original endpoint

The selected endpoint is:

`/rest/stream.view`

with:

`format=raw`

and no bitrate/codec/transcoding parameters.

Reasoning:

- OpenSubsonic defines `raw` as the explicit no-transcode stream format.
- Navidrome handles the raw format as the original-file stream path before player-specific transcoding selection.
- `/download` was not chosen because Navidrome can be configured to transcode downloads (`AutoTranscodeDownload`), making it a weaker guarantee for this direct/original-only slice.

Transcoding capability can remain known by the provider, but no transcoding policy, fallback, quality negotiation, codec choice or bitrate limit is implemented here.

## Runtime request safety

`OpenSubsonicRuntimeAudioRequest` is intentionally a regular non-serializable class rather than a data class/Parcelable.

Safe exposed fields:

- canonical `recordingId`;
- canonical `mediaAssetId`;
- provider key;
- unauthenticated endpoint URL;
- `directOriginal = true`.

Private runtime material:

- provider source locator;
- auth object;
- salt factory.

`toString()` redacts locator and auth. Probe execution converts thrown transport failures into typed secret-free outcomes rather than forwarding exception text that may contain an authenticated query string.

## Failure semantics

Typed resolution failures:

- media asset not found;
- unsupported provider;
- account not configured/forgotten;
- provider/account mismatch;
- credential unavailable/decrypt failure surfaced as absence;
- invalid provider locator.

Provider-level probe outcomes:

- available;
- network unavailable;
- authentication rejected;
- request rejected.

No failure path writes or deletes `Recording`, `Release`, `ReleaseTrack` or `MediaAsset` state.

## TDD

RED was established before production implementation in Android CI #155: compilation reached the new test suite and failed specifically because the new runtime media-access types did not exist.

Coverage includes:

1. known OpenSubsonic asset resolves to runtime access;
2. recording/media/server-track IDs remain distinct;
3. API key exists only in runtime parameters;
4. password auth generates runtime salt/token and no raw password parameter;
5. persisted account/asset state contains no secret;
6. forgotten account makes the asset unavailable without deleting it;
7. wrong provider/account is rejected;
8. missing asset fails without catalog mutation;
9. server change cannot reuse the old credential;
10. network failure does not mutate canonical data;
11. direct/original uses `format=raw` and no transcode parameters;
12. invalid locator is rejected;
13. authentication rejection is represented without exposing secret;
14. missing credential fails safely without catalog mutation;
15. even an exception containing an authenticated URL/API key is reduced to a secret-free runtime result.

## Optional real-server smoke

The existing `OpenSubsonicManualSmokeTest` remains opt-in. Normal CI has no endpoint or credential.

Existing environment variables:

- `BITCHORD_OPENSUBSONIC_URL`
- `BITCHORD_OPENSUBSONIC_API_KEY`, or
- `BITCHORD_OPENSUBSONIC_USERNAME` + `BITCHORD_OPENSUBSONIC_PASSWORD`

Slice 5 adds optional:

- `BITCHORD_OPENSUBSONIC_TRACK_ID`

When the track ID is supplied, the smoke builds the provider-owned runtime access from a canonical MediaAsset fixture and performs a one-byte HTTP Range request against the generated `stream.view?format=raw` request. Secrets remain environment-only and are never printed or committed.

## Explicit non-goals

- PlaybackResolver integration;
- PlayableMediaAsset changes;
- Queue or queue persistence;
- MediaSession/Media3 integration;
- Library click-to-play;
- PlaybackService changes;
- transcoding policy/fallback;
- codec/bitrate preference;
- BitPerfect;
- audio download/cache;
- remote artwork;
- lyrics;
- canonical Artist;
- albums browse;
- playlist sync, ratings or recommendation.

## Integration handoff

A future orchestrated Playback integration can conceptually do:

`selected canonical MediaAsset → OpenSubsonicMediaAccess.resolve(mediaAssetId) → ephemeral OpenSubsonicRuntimeAudioRequest → playback-owned HTTP/Media3 adapter`

Any change required to `PlaybackResolver`, `PlayableMediaAsset`, canonical queue identity, Queue or MediaSession remains an orchestrator decision outside this PR.
