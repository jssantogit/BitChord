# Slice 6 — Secure OpenSubsonic Artwork Access

Date: 2026-10-18
Base: `00d3ebab1f5d3b269b9e3f12134e54fb74fb6951`
Branch: `providers/opensubsonic-artwork-access`

## Goal

Show real artwork in `Library → OpenSubsonic / Navidrome` without allowing an authenticated image URL, password, API key, salted token or authentication headers to cross into Room or Compose state.

Runtime boundary:

`canonical mediaAssetId → persisted MediaAsset.artworkUri → provider-owned artwork resolution → runtime credential lookup → authenticated getCoverArt request → bounded image bytes → provider-owned credential-free cache → Compose Image`

Metadata browse remains Room-first. Artwork is fetched lazily per visible row and falls back to the existing placeholder on any unavailable/error state.

## Persisted artwork locator

No schema change is required. The existing OpenSubsonic sync already persists a logical reference:

`opensubsonic://artwork/<base64url(accountId)>/<base64url(coverArtId)>`

`coverArtId` is selected from the track's OpenSubsonic `coverArt` field, with the album `coverArt` as fallback. The reference contains no credential and is not musical identity.

The artwork seam starts from `mediaAssetId`, reads the current `MediaAsset` through the existing `mediaAssetById` DAO query, then parses `artworkUri` internally. The UI never receives the decoded provider cover-art locator.

## Endpoint verification

Current Navidrome registers `getCoverArt` as a binary media-retrieval route. Its internal Subsonic API examples call `getCoverArt?id=<coverArtId>`. The client therefore targets:

`/rest/getCoverArt.view`

with standard OpenSubsonic `v`, `c` and runtime auth parameters, provider cover-art `id`, plus a bounded `size=256` request for the 48dp list thumbnail. No JPEG/PNG assumption is made: the response is accepted only when `Content-Type` is `image/*`.

## Security boundary

`OpenSubsonicArtworkAccess.resolve(mediaAssetId)` reads only canonical/provider state and plain account metadata. It does not read the credential store.

`OpenSubsonicArtworkRequest.fetch(...)` is the first point that calls `OpenSubsonicAccountRepository.savedConfig()`. The existing Keystore-backed credential store therefore remains the owner of the secret until network request time.

The request descriptor is non-serializable and exposes only:

- `recordingId`;
- `mediaAssetId`;
- provider key;
- a credential-free cache key.

Decoded `coverArtId`, account/auth objects and authenticated parameters stay private. `toString()` redacts locator/auth. Transport exceptions are collapsed to typed failures because exception text can contain a full authenticated URL.

The artwork transport reuses the shared OkHttp connection infrastructure but explicitly clears inherited application and network interceptors before issuing an OpenSubsonic request. This prevents credentials owned by another provider (for example WebDAV) or a future global request logger from observing the authenticated artwork query. HTTP and HTTPS redirects are also disabled so authentication query parameters cannot be forwarded to another host.

## Cache

Persistent cache key:

`opensubsonic-artwork:v1:<mediaAssetId>:<sha256(logical artwork reference)>:256`

The disk filename is another SHA-256 of that safe key. Passwords, API keys, salts, tokens, authenticated URLs and raw provider cover-art IDs cannot enter the cache key or filename.

The cache lives under Android `cacheDir/opensubsonic-artwork-v1`, is best-effort, and trims least-recently-used files after the retained set exceeds 128 MiB. A cache hit is served before the credential store or network is touched, allowing previously viewed covers to remain visible while Navidrome is offline.

## Failure behavior

These states degrade to the existing placeholder and never mutate the canonical catalog:

- media asset missing;
- no artwork reference;
- malformed/non-OpenSubsonic artwork reference;
- wrong provider/account;
- forgotten account;
- unavailable credential;
- network failure;
- authentication rejection;
- non-image or oversized response;
- HTTP request rejection;
- image decode failure.

The network body is bounded to 20 MiB before it can enter memory/cache.

## UI integration

`CanonicalRemoteLibraryScreen` keeps its canonical `recordingId` LazyColumn key. For artwork, each row passes only its canonical `mediaAssetId` to `OpenSubsonicArtworkLoader`. The credential-free `artworkUri` is used only as a Compose restart/revision key so resync can invalidate the displayed bitmap when the logical artwork reference changes.

Compose state contains only the decoded `ImageBitmap` and canonical/safe keys. No authenticated URL is ever produced for the UI.

This slice does not alter the existing playback click path, PlaybackResolver, Queue, MediaSession or Now Playing metadata.

## TDD

Initial RED was established in Android CI #218 from the test-only commit `9510a0d05ee7b29b3cc21945d67c17813a40ce21`: compilation reached the new unit test and failed specifically because the artwork access/cache/transport types were absent.

A second security-hardening RED was established in Android CI #254 on `f7edd4106ca14e380e81b34a7a60a8b46374f6f1`: the suite failed specifically because the isolated artwork HTTP client did not yet exist. The corresponding implementation commit `45c4598bf04cda2cc2ab266f299cfb24ebf49009` passed `testDevDebugUnitTest` in Android CI #260.

Regression coverage includes:

- correct account resolution from known artwork;
- credential untouched during logical resolution;
- API-key runtime materialization/redaction;
- password salted-token runtime materialization/redaction;
- provider/account mismatch;
- forgotten account;
- missing/malformed artwork;
- network failure without catalog mutation;
- stable credential-free cache key and artwork-revision invalidation;
- cached image use after provider outage without another credential-store read;
- artwork HTTP client does not inherit global application/network interceptors and does not follow redirects;
- typed authentication rejection and missing credential;
- canonical browse identity without `Song.videoId` or `sourceKey`.

## Manual Android gate

1. Install the final dev APK as an update so the existing account/catalog remain present.
2. Run the already-existing Sync Now if needed.
3. Open `Library → OpenSubsonic / Navidrome` and scroll through tracks that have artwork.
4. Confirm real covers appear and scrolling/browse remain responsive.
5. Reopen the same rows once to warm/verify the local artwork cache.
6. Make Navidrome unreachable without using Forget connection.
7. Reopen Library. Metadata must remain available from Room. Previously cached covers should remain visible; uncached/error rows must show the placeholder without crashing or deleting catalog data.
8. Restore the server and confirm uncached covers can load again.

No real credential is committed to source, tests, PR text or documentation.
