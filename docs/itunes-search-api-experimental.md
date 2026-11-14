# Apple-first catalog — iTunes Search API (experimental)

**Branch:** `catalog/itunes-search-metadata-20261113` · PR #214 · validation base `catalog/itunes-validation-base-744a438b` (frozen snapshot of PR #209).

## Architecture decision — 2026-11-13

**Apple/iTunes is a PRIMARY editorial source with MusicBrainz, not a secondary evidence gate.**

- **Apple owns the displayed commercial catalog:** existence within the chosen storefront, artist releases, edition titles, tracklist/order, commercial release dates and artwork. An Apple release MUST remain browsable when no MusicBrainz ID is known.
- **MusicBrainz owns musical identities:** matched artist/recording/release/release-group MBIDs and musicological relationships. Its primary/secondary release types, names and raw discography MUST NOT be used to construct or override Apple's commercial shelves.
- **Discogs** supplements physical editions, variants and release evidence. Neither Discogs nor MB rows silently create items inside the Apple-led catalog.
- **No destructive cleanup:** records missing from Apple are not proven fake. MB-only archives and private/local libraries remain available separately. Storefront unavailability is not deletion.
- Verified crosswalks may annotate an Apple item but cannot change Apple's title, date, artwork, order or existence. Do not match by title only, merge distinct Apple editions with the same name or invent MBIDs.
- Playback remains its own independently verified concern.

The legacy MusicBrainz-first catalog in PR #209 is not switched by this isolated branch yet.

## Implemented in PR #214

1. `ItunesCatalogClient`: search by artist, album or song; lookup by collection ID, artist ID and UPC; per-country keys `itunes:<country>:<kind>:<id>`.
2. `KtorItunesCatalogTransport`: public API, no developer token, global 3.1-second request spacing, 15-second timeout, body <=512 KiB, no automatic retries.
3. Apple album/track artwork URLs (`artworkUrl100/60/30`) only from validated HTTPS `*.mzstatic.com` URLs; track count and UPC where present. Artwork is **edition-scoped**, not a generic MB ReleaseGroup cover. No previews or playable media.
4. `ItunesEditorialCatalog.assemble`: Apple album rows alone produce the commercial release list; preserves same-title distinct IDs and Apple presentation fields, attaches exact-storefront tracks in disc/track order and accepts optional, explicitly verified MB ReleaseGroup annotations only.
5. Tests guard editorial ownership, correct edition boundaries, storefront isolation, unverified identity rejection and image domain sanitization.

### Important API limits

- The public iTunes Search API does not provide a complete, unambiguous Album/EP/Single type. The Apple-authored display suffixes ` - EP` and ` - Single` are recognized; **otherwise the release remains UNCLASSIFIED**. Do not infer by MusicBrainz type, track count or a guessed title. The `ALBUM` case remains reserved for a future explicit or independently established Apple editorial classification.
- Artist lookup/search result limits and lack of cursor pagination mean the current adapter is **not yet sufficient to build exhaustive large-artist discographies**. A stronger Apple Music Catalog API adapter, with authenticated artist-album relationships and pagination, should be evaluated before production cutover.
- Apple absence may reflect country restrictions, delisting or catalog gaps, not invalid music.
- The current client does not supply rankings; use Apple RSS feeds or Apple Music Catalog Charts separately.
- Apple's promo-art terms remain relevant for any distribution. The owner has specifically chosen a closed personal prototype and considers enforcement risk separate from architectural design; this is not a grant of redistribution rights.

## Next work before a live cutover

1. Replace the MusicBrainz-first live discography source with the Apple-led projection in Home, Search, Explore and native artist/album details, preserving the existing am-descomp composition and local/remote playback.
2. Resolve Apple artist IDs to MusicBrainz artist IDs through verified links; maintain immutable Apple edition keys for navigation and existing MBID-based ratings/library references.
3. Add robust Apple Music API or other official Apple discovery for artists with >30/200 releases, explicit storefront fallback (BR first), failure/cache states and completeness signals.
4. Define edition display when Apple does not differentiate two releases by name; preserve Apple IDs and show verified edition distinctions rather than merging by title.
5. Integrate Apple artwork into a remote image path, and separately verify Apple RSS/Charts for rankings.
6. Rebase on the moving PR #209 once stable and run full Android/Desktop/TV/Wear CI, then manual smoke.

**This PR is still a data-layer/projection implementation. No user-visible Apple-first catalog switch, APK or manual smoke has been performed.**

## References

- https://performance-partners.apple.com/search-api
- https://developer.apple.com/library/archive/documentation/AudioVideo/Conceptual/iTuneSearchAPI/LookupExamples.html
- https://developer.apple.com/documentation/applemusicapi/albums/attributes-data.dictionary
