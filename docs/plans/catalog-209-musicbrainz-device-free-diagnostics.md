# BitChord PR #209 — MusicBrainz diagnostics without APK / physical smoke

**State:** public-API validation instrument, not a change in app behavior or authority.
**Goal:** reproduce the query → diagnose → offline-test → fix → query cycle
already used on the Discogs PR #211, while keeping device smoke reserved for
actual Android behavior that cannot be tested at source level.

## Existing foundation

- `scripts/catalog_178_musicbrainz_live_probe.py` already has an honest public
  transport: meaningful User-Agent, requests spaced >=1 second, retries 429/503,
  real Artist / ReleaseGroup / Release / Track / Recording / search probes.
- Its old `.github/workflows/catalog-178-live-probe.yml` is scoped to a past
  issue branch/PR and cannot automatically inspect current #209 changes.
- `MusicBrainzCatalogGraph`, `MusicBrainzCatalogSearch` and
  `MusicBrainzArtistDiscographyPager` already have typed graph and cursor logic,
  so any proven data bug should be followed by a **focused Kotlin regression**
  on the same canonical client/parser, not a heuristic change to remote truth.

## How to reproduce a catalog data issue

1. Add or revise a *public, exact-MBID* case in
   `docs/plans/catalog-209-musicbrainz-diagnostics-cases.json` (at most three
   cases, at most two editions each). All cases should be traceable to known
   MusicBrainz IDs and expected group types; no private library content.
2. Run `python3 -m unittest discover -s scripts -p 'test_catalog_209_musicbrainz_diagnostics.py' -v`
   to verify offline guards and the suspected failure before changing code.
3. Run `python3 scripts/catalog_209_musicbrainz_diagnostics.py --output catalog-209-musicbrainz-diagnostics.json`
   or dispatch GitHub Actions **Catalog #209 — MusicBrainz live diagnostics**
   from the PR #209 branch. Push of *diagnostic code/manifest only* also triggers
   the workflow; normal Android commits do not poll MusicBrainz.
4. Read artifact `catalog-209-musicbrainz-diagnostics.json`. Per test it reports
   `PUBLIC_API_ONLY`: PASS, FAIL (contradictory source contract), INCONCLUSIVE
   (search miss or partial browse), ERROR (network) and the raw source facts
   needed to investigate. It does not upload full API bodies or personal data.
5. If public data explains a bug, write a Kotlin regression against the current
   parser/projection and fix **that** code in #209. Re-run unit tests/Android CI
   in the same HEAD, then the bounded source probe. Use physical smoke only to
   validate UI/device-only behavior after source-level conditions pass.

## First real case

ILLIT `6b128dd7-e998-4edb-a4f5-400fef190445`, group
`529f87a1-1081-48a4-8ee1-3c08de229858` (SUPER REAL ME / EP), worldwide
edition `541adf5a-48d4-4f7b-a7bb-afd3cea5af27`, Japanese edition
`271b31f8-8ab6-483c-97d9-8f0fabb2d4f4` and original Magnetic Recording
`e614e432-ca77-41c1-82c1-4e3a24ebbb0c` were already part of the
preexisting #178 evidence harness. The diagnostic also tests raw MusicBrainz
browse offset/total, source type, release-to-group relationship, separated
Track/Recording IDs, artist search and ReleaseGroup search.

## ReleaseGroup artist-credit identity gate

The exact `release-group/{MBID}` lookup now includes `artist-credits`
without adding requests to the eight-call budget. A group's type and title
alone do not prove that it belongs to the selected artist.

- **PASS:** the exact group MBID, primary type and a complete structured
  artist credit are present, with the selected Artist MBID among its credits.
  A collaboration may contain multiple MBIDs and joinphrase strings.
- **FAIL:** identity/type contradicts the manifest, or complete structured
  group credits explicitly refer to different Artist MBIDs (even when the
  artist's display name is similar or identical).
- **INCONCLUSIVE:** the exact group has no usable structured artist credit
  or a malformed/partial credit; that does not mean the release is absent.
- **ERROR:** the source could not be consulted. Never turn this into a
  negative identity assertion.

Evidence fields `expected_artist_mbid`, `observed_artist_mbids`,
`group_credit_verified` and `group_credit_complete` make attribution
auditable without a smoke APK, a fuzzy name match or another API request.
This probe does **not** guarantee that the same nested artist-credit is
present in the separate Release/track_artist browse response used by the
app's guest shelf; that runtime edge needs its own response fixture before
claiming full field coverage.

A search index `count=0` is **not** absence in the browse or source catalog;
a page that returns fewer rows than its total is **not** complete. A group
with a Release match does not justify a playable MediaAsset, nor does a CAA
flag guarantee an image rendered in Compose.

## Guest track_artist browse: API shape, not ownership

The bounded live run now performs **one additional GET total** (not per
manifest case) for a known public Artist MBID, NewJeans
`49204a7a-ed85-407a-828f-6fd46f1d8126`:

`/ws/2/release?track_artist=<MBID>&inc=release-groups+artist-credits+recordings+labels+isrcs&limit=5`

The `guest_track_artist_shape` report summarizes *which nested artist-credit
arrays were actually present* in this sample, along with up to five linked
ReleaseGroup MBIDs; it does not upload full JSON bodies. `OBSERVED` means a
valid group was seen, not that a guest/own-group relationship was proved.
`INCONCLUSIVE` covers empty or missing group data. An API error is `ERROR`.

This specifically tests whether `release-group.artist-credit` is embedded in
the response consumed by the Kotlin guest parser. The group may be linked by
MBID without its own credits. A zero count must **never** be read as an
assertion that the target artist is absent from that group. For a definitive
release-group owner check, use an *exact* group lookup with structured credits;
avoid joining by name, title or Lucene search index.

## Actual HTTP attempt accounting

The public source transport logs **every network attempt before I/O**,
including successful responses, HTTP 429/503 retries, timeouts and failed
JSON decoding. A prior source run of the three-case matrix reported **24 PASS
checks, 0 errors, and 28 actual attempts** for 25 logical operations. The
old nominal 25-call description did not count retries; this was a reporting
and rate-bound gap, not an additional catalog identity discovery.

The report now separately records `logical_probe_count`,
`http_request_count`, `max_http_requests` and aggregate
`http_status_counts` without leaking API bodies. The shared #178 transport
keeps its original unbounded default; only the explicitly bounded #209
diagnostic enables the 32-attempt ceiling.

## Real source matrix: Album / EP / Single

The bounded manifest now covers **three exact-MBID artist/group/recording
graphs**, not three title-only search guesses:

| Artist | ReleaseGroup | Primary type | Verified edition examples |
| --- | --- | --- | --- |
| ILLIT / `6b128dd7-e998-4edb-a4f5-400fef190445` | SUPER REAL ME / `529f87a1-1081-48a4-8ee1-3c08de229858` | EP | Worldwide `541adf5a-48d4-4f7b-a7bb-afd3cea5af27`; Japan `271b31f8-8ab6-483c-97d9-8f0fabb2d4f4` |
| aespa / `b51c672b-85e0-48fe-8648-470a2422229f` | Armageddon / `3f5eba8d-4dc1-4599-a936-21f501787f8e` | Album | digital `a46ad810-85f6-4873-8b82-87d6a8006edb`; LP `aa3ced48-ed87-4bfa-bd1b-d198e084bc39` |
| NewJeans / `49204a7a-ed85-407a-828f-6fd46f1d8126` | OMG / `23d1cd75-8dc5-436d-a0c6-cab894dd5f64` | Single | Japan digital `5c9de548-68ef-424d-915d-a7973024d839`; Apple Music `3e73bd0e-636e-46be-9abf-272373a605da` |

The corresponding exact Recording MBIDs are recorded in the JSON manifest.
MusicBrainz pages were consulted before writing the cases:
[ILLIT EP](https://musicbrainz.org/release/541adf5a-48d4-4f7b-a7bb-afd3cea5af27),
[aespa Album](https://musicbrainz.org/release/a46ad810-85f6-4873-8b82-87d6a8006edb),
[NewJeans Single](https://musicbrainz.org/release-group/23d1cd75-8dc5-436d-a0c6-cab894dd5f64).
The actual live API run must still validate that each exact group owns
the edition and type. If a page or lookup is incomplete, report that fact
instead of inserting/removing shelf entities.

## Hard limits and unresolved surface

- 1–3 public cases, up to 8 **logical queries** per case plus one
  guest response-shape probe (25 logical checks for three full cases). HTTP
  429/503 retries are extra attempts: this workflow enforces a **hard ceiling
  of 32 real HTTP attempts** per run, spaced >=1.1 s. If the cap is reached,
  remaining checks report an error rather than hiding an unverified response.
  no tokens and no private endpoints. Reuse the #178 retry/client instead of
  creating a parallel HTTP transport.
- Artifacts expire after seven days; include HEAD / GitHub run provenance.
- It does **not** execute Kotlin or Room, Navidrome, MediaStore, UI navigation,
  playlists, rating linkage or image loading; Android CI and focused Kotlin
  tests cover most logic; a physical smoke remains the final UI/device gate
  when necessary.
- No MusicBrainz metadata is edited upstream and no source precedence flips.
  Discogs #211 remains an independent PR; no changes to that branch/main.

Official references: [MusicBrainz API](https://musicbrainz.org/doc/MusicBrainz_API),
[Search fields](https://musicbrainz.org/doc/MusicBrainz_API/Search),
[Rate limiting](https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting).
