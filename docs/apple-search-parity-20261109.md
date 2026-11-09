# Apple Music Search flow — Bitchord Android handoff (2026-11-09)

## Scope

The Apple Music screenshots provided by the user show three stages, not a replacement for Bitchord's existing genre-only **Explorar** tab:

1. Search entry and stored recent searches.
2. Pre-submit editing: a filled top search field with Back and Clear, a source selector, the keyboard, text suggestions, and optionally cached artist/track matches.
3. Submitted results: category tabs (top results, artists, albums, songs, playlists and where supported videos) above the existing reusable result rows.

In Bitchord, General remains MusicBrainz discovery plus eligible catalog sources; Server remains configured remote addons; Local remains the on-device library. They are **three real sources** and must not be relabeled as an Apple subscription. Only the existing supported result types may appear.

## Source evidence

Consulted `jssantogit/am-descomp` research branch `fase-3-investigacao-visual`:
- `docs/DESIGN.md` and `docs/design-system.md` (search hierarchy, general typography and tokens).
- `docs/screens/top_level_search_page.md` (fragment layout binding, entry field and distinct runtime states).
- `docs/components/search-field.md` (48dp declared inner background, 48dp lateral buttons and 10dp vertical layout padding, but not proof of the fully rendered Compose height).
- `docs/components/search-result-tabs.md` (observed category selector after submitting; class, exact typography and geometry unknown).
- `docs/motion/search-and-library.md` (DEX traces from Search landing to recent-search destination; not proof of every user touch).

## Implementation cut in PR #212

- Reuse the one native `SearchScreen` and `MainViewModel`; do not introduce parallel screens, alternate search backends or direct API calls.
- The phone Search root uses `SearchField(appleSearchChrome=true)` in the floating top bar. The leading icon is a real Back control, not the previous submission magnifier. IME Search still commits through `submitSearch`; Clear remains functional. The account toolbar action is suppressed for this root only.
- The ViewModel exposes its **existing** submission gate as a StateFlow. Editing keeps source tabs visible; committing reveals result-type tabs instead. Source switching while editing does not accidentally commit text. Completed searches re-run on source change only if the prior query had already been submitted.
- The pre-submit selector is `AppleSearchOriginTabs` for Geral, Servidor and Sua Biblioteca, keeping native source identity and callbacks.
- The submitted category tabs use the Apple-style underline/selected-accent variant instead of Material pills. Filtering stays wired to the existing category filter and same local/remote rows.
- Canonical editing has an explicit Search action for the typed text and matching local **stored** search terms. These are not claims of network suggestion coverage; no fabricated Apple search suggestions or copyrighted editorial data.
- Local-device matches continue to arrive during typing and do not have an artificial submit requirement.
- Tests cover the source selector, the Back action not submitting, and the existing Explore behavior. CI is the next validation; no APK or smoke until a requested checkpoint.

## Unverified details

The REA does not prove the result/source selector's final geometry, transition timing, all autocomplete callbacks, theme tint, or input focus behavior on all devices. Bitchord's field height and underline styling are therefore **visual candidates**, not measurements of Apple pixels. Do not convert a capture's screen px to dp without density.

Further physical comparison should confirm keyboard/inset behavior, history entry interactions, source switching without submission, accessibility and the search-result category list under loaded results.

## Resumption checkpoint — 2026-11-09

The original Search implementation remained published through commit `369311f`, with Android CI, Desktop, TV/Wear and YouTube audit green. This is the resumed state, not a second implementation.

A visible pre-submit bug was found during code review: canonical typed-query suggestions were followed by the unrelated generic catalog start hint because `canonicalMusicBrainzResults` was called without a committed result state. `canonicalSearchEditing(query, searchCommitted, detailOpen)` now gates the edit stage: typed text shows actionable suggestions and matching stored terms without the result hint; submission hands off to the canonical results/categories; blank query still shows recent history and opened detail is unaffected. The helper has regression cases for blank, whitespace, editing, submitted and detail state in `CanonicalSearchOriginsTest`.

The user-facing three-source selector remains Geral / Servidor / Sua Biblioteca. It is shown during query editing, the result-type tabs after submission. Server and local sources are not relabeled as Apple Music services; no fictional autocomplete/network suggestions. No alternate search screen or new backend, no merge or smoke triggered in this checkpoint.

- Server search, like General, now has a distinct pre-submit stage: the typed-query Search action plus up to two matching **stored server terms** are shown. No server request or unverified network autocomplete is triggered merely by editing. Server result-empty messaging is deferred until a submitted request. `serverSearchEditing` has tests for empty, whitespace, editing, and committed states.

- Root Search now explicitly excludes the top-bar account avatar and download manager action while the inline Back/Search/Clear field is displayed. This corrects a wiring gap between the documented design and the actual MainActivity condition; Home, Library and opened detail routes retain their former controls.

- General pre-submit now also shows up to five verified cached MusicBrainz artist/release/recording rows beneath text suggestions. It reuses `showCachedCanonicalTypeahead`, `canonicalRowsForQuery`, `NativeSearchResultRow` and existing evidence-gated click actions, with no new network requests. This matches the observed *shape* of Apple pre-submit matches without claiming the same recommendation algorithm.
