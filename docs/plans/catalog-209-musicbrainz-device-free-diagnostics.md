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

A search index `count=0` is **not** absence in the browse or source catalog;
a page that returns fewer rows than its total is **not** complete. A group
with a Release match does not justify a playable MediaAsset, nor does a CAA
flag guarantee an image rendered in Compose.

## Hard limits and unresolved surface

- 1–3 public cases, up to 8 calls per case, max 24 calls, spacing >=1.1 s,
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
