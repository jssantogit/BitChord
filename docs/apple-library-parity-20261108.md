# Apple Music Library (Android) — implementation evidence and parity ledger

**Scope:** PR #212, Android-only Library redesign, reusing native Bitchord modules and data. Baseline: `jssantogit/am-descomp`, branch `fase-3-investigacao-visual`, `docs/DESIGN.md`, `docs/screens/top_level_library_page.md`, `docs/components/library-category-list.md`, `docs/motion/search-and-library.md`, and the five user-provided Apple Music screenshots dated 2026-11-08.

## Evidence boundaries

- **Runtime observed:** Library title + top actions; a 3-column, two-row array of square covers; below it vertically divided categories; below them `Adições Recentes` and larger media cards. The floating miniplayer and five-tab navigation remain in place while scrolling. Category screenshots show Songs and Artists as lists, Albums as a two-column grid, Playlists as a list, a search field on each category, and Play/Shuffle pills on Songs and Albums.
- **Static evidence:** 16dp standard spacing, 8dp secondary spacing, default title insets 16dp/7dp (tablet qualifiers vary). This is **not proof** of pixel-perfect final sizes or of category button callbacks. The README and DEX extraction explicitly mark the Compose tree, row dimensions and category interactions as unknown.
- **Bitchord behavior retained by decision:** native source/star/rating filters, sort/direction, grid/list choice, long-press selection, menu actions, actual local/remote catalog data, pinned library identity and local `Adições recentes` shelf. No fake TV/Films destination or unsupported Library Radio category. Downloads remains a real Bitchord extension.

## Single-source implementation matrix

| Apple reference | Native Bitchord owner | Implementation / decision | Remaining device proof |
|---|---|---|---|
| Library title/sections | `sharedUi/.../LibraryScreen.kt` | Android `navigationHome` renders cover pins → category links → actual local recent additions; 30sp headline and reference-aligned insets. `AppleLibraryLandingPolicy` has unit tests. Desktop/non-Apple path unchanged. | Header spacing / scroll positions under actual floating chrome |
| 3×2 pinned covers | `CanonicalPinnedLibraryShelf.kt` | Reuse existing verified 3-column six-cover shelf; no placeholders invented for empty/missing pins; long-press actions and art via known MediaAssets. | Artwork sizes and display on sparse libraries |
| Category links | `LibraryScreen.LibraryLinkList`, `LibraryPhoneParts.libraryNavigationLinks` | Apple-only 44dp min row, monochrome 22dp glyph, 17sp label, hairline separators; omit chevron only on Apple landing. Playlists, Artists, Albums, Songs, Downloads navigate to existing actions. Nonfunctional Radio category removed. | Actual icon geometry and spacing at Galaxy font scale |
| `Adições Recentes` | `CanonicalLocalRecentAdditions` | Keep existing chronological verified MediaStore shelf and dedicated `Ver todos` controls, as explicitly preferred. The Apple screenshot's larger two-column cards are a reference, not a replacement for native working history. | Category label / layout |
| Songs/Artists/Albums | `CanonicalRemoteLibraryScreen.NativeUnifiedLibraryBody` | Existing native read-only Recording/Release/Artist identity and actions remain. First-visit display is List/List/Grid respectively, with per-category view choice preserved when switching. Search, sorting, star/source filters and selections remain in same screen. | Header/row/card visual fidelity, sorting and gesture test |
| Playlists | `UnifiedPlaylistLibraryShelf` | Keep canonical smart/normal/local data, create, filter, sort and native action sheet; inline search shown on entry without autofocus, as Apple capture. No parallel playlist page. | Header and large-row rhythm, search keyboard & filters |
| Bottom chrome | existing player/miniplayer, 5 tabs | Reuse the prior Apple bottom-tab experiment; no changes to audio/backend in Library phase. | Inset and scrolling coexistence |

## Explicit non-goals / unknown

- Do not copy third-party music art, fonts or protected source assets from the decompilation. Icons and album images must come from Bitchord data and its own assets.
- Do not expose `TV e Filmes` merely because the reference shows it; Bitchord does not have a verified equivalent. Do not fabricate radio items within Library.
- Do not invent runtime dimensions or hidden transitions from layout resource candidates. Only static tokens are known; physical Android screenshots remain the visual acceptance source.
- Preserve Navidrome and local tracks in unified categories even if MediaStore permission is absent; no source coercion or legacy YouTube references.
- CI success proves build/unit tests, **not** 1:1 visual fidelity; compare user screenshots on device after a chosen APK smoke.

## Tests

- `AppleLibraryLandingPolicyTest`: pinned covers → categories → native recent additions and absence of phantom items.
- `LibraryNavigationLinksTest`: functional category order and Downloads, no dead Radio placeholder.
- `AppleLibraryCategoryPresentationTest`: Albums grid / Songs and Artists list defaults, preserving manual changes per category.
- Existing `NativeCanonicalLibraryFiltersComposeTest`, `CanonicalLibraryStarSourceFiltersTest`, `UnifiedPlaylistLibraryShelfTest` and `CanonicalRecentAdditionsFiltersTest` cover native filtering and shelf ownership.

Current state: first visual and architectural pass. **Do not assert device-level parity until screenshots/interaction smoke are available**. Keep PR #212 draft, #209/main unchanged.

## Validation follow-up — 2026-11-08

- Android CI #6013 failed a single UI test after the intentional heading change to Adições Recentes. Commit ac014a2 corrected the old test expectation without reverting the user-visible text.
- Android CI #6014 passed the Android unit tests and shared Automix planner but failed shared JVM compilation: PlatformTextStyle(includeFontPadding = false) was unavailable on JVM. Commit 2eee7c4 removed that Android-specific override in shared PlayerControls while retaining the 11sp badge label and 13sp Apple-style line height. Android CI #6015 was in progress at this checkpoint, not yet proven green.
- The am-descomp category-list investigation explicitly classifies exact row dimensions, icons, and spacing as unknown. The present 44dp rows, 22dp icons, and 17sp labels are provisional choices, not measured Apple geometry.
- Outstanding physical checks: six-cover shelf, header, category rows, recent additions, search, bottom chrome, font scale, and sparse/empty library. Do not merge or declare pixel parity without device evidence.

## Library category localization — 2026-11-08

- The Apple-style landing previously hardcoded Portuguese titles for Artists, Albums, and Songs, despite Android resources already providing translated labels. The existing category destinations were preserved while the caller now supplies `R.string.playlists`, `R.string.artists`, `R.string.albums`, and `R.string.songs` via `LibraryCategoryLabels`.
- The navigation regression asserts that English titles render in the same Playlists → Artists → Albums → Songs → Downloads order, that each item keeps its existing callback, and that Downloads retains its actual local browse ID. The previous Portuguese order check is retained with explicit labels.
- This change affects category text only. It does not change searches, filters, pinned cover shelf, library content, or underlying navigation routes.
- Validation of the localization commit requires the new Android CI run; no device smoke has been completed.

## Visual refinement from paired Android screenshots — 2026-11-08

- Reference discipline: `am-descomp/docs/DESIGN.md`, `docs/design-system.md`, `docs/design/typography.json`, `docs/design/spacing.json`, `docs/components/search-field.md`, and the user-supplied Apple/Bitchord screenshots. Explicit Apple design tokens include 17sp Body, 15sp Subhead, 16/8/4dp shared spacing, and a declared 48dp search background. These do not prove every rendered component's dimensions or font family.
- The main Library toolbar now presents a separate circular create-playlist action and a separate overflow circle with only Settings. The undrilled Songs/Artists/Albums category toolbar uses independent catalog-source, sort/filter, and Settings circles; underlying source, star, sorting and list/grid controls remain intact. Playlist category uses create, filter and Settings circles; its existing playlist sort remains available in existing menus until separately reviewed.
- Root Songs/Artists/Albums list and grid cards and root Playlists rows no longer show inline three-dot overflow. Root grid/list selection and playlist long-press behavior are kept. Detail-page action menus are intentionally unaffected.
- Root Library heading was moved up to share the top-action band; leading title and category/icon/divider insets were adjusted against paired screenshots, not claimed as Apple DEX geometry. Artists and Albums icon silhouettes were updated using Bitchord-owned Material icons, not copied third-party assets.
- The shared native category search uses the documented 48dp background height. The undrilled playlists and unified Library categories now use a filled variant without a permanent leading magnifier or empty-state dismiss control; detail searches retain their original outlined version.
- The Downloads folder still uses its native folder sort/search controls. Do not present a fictitious source selector there until the downloads source model is connected. A physical comparison remains necessary for typography, touch targets, UI-scale variants, mini-player coexistence, and visual equivalence.
- CI must be assessed on the new HEAD, not on prior green runs. No automatic merge or APK deployment.

## Am-descomp REA handoff — 2026-11-08

- Investigated `jssantogit/am-descomp` research branch `fase-3-investigacao-visual` at `4aed515f`, especially `docs/library-reference.md`, `docs/components/library-toolbar.md`, `docs/components/library-iconography.md` and `evidence/library-capture-geometry.json`. The reference traces the Library More Options round modifier to `profile_foryou=40dp`; this is NOT proof of final touch target, total height or view bounds. The original New Playlist toolbar asset `toolbar_new_playlist` is a 24×24dp vector, versioned in the research atlas.
- Adopted the 40dp circular *surface* for the independent Bitchord Library actions, with a 12dp gap chosen as an approximate proportion of the reference capture (21px gap vs 73px circle), NOT a verified Android gap token. Other screens keep their original toolbar presentation.
- Supplied `ic_apple_library_new_playlist.xml` based on the versioned reference vector and wired the root and undrilled Playlists create buttons to it; no speculative source/callbacks added.
- **Unavailable in the published research checkout:** the exact XML paths for `ic_library_link_icon_{playlists,artists,album,songs,downloads}` are local-only in `resources/android/res/drawable/`; only their metadata, resource IDs and SHA-256 are public. Do NOT pretend the Material stand-ins are those original assets. If local licensed resources become available, introduce them deliberately and verify hashes; otherwise retain explicitly labeled substitutes.
- Correction to screenshot attribution: the larger Library title is Apple Music; the smaller title was Bitchord. Reversed the mistaken 30→26sp reduction, and increased the Bitchord Library heading to 32sp as a **provisional visual adjustment**, not a verified Apple typography token. Validate with a new paired device screenshot before declaring parity. Typography still lacks verified per-row actual `lineHeight`, `letterSpacing` and runtime font selection; the category line measures and UI insets are not proven in dp. Do not claim pixel parity. Next acceptance: device comparison after the current CI; no automatic merge.

## Library edit-pins evidence gate — 2026-11-08

- User clarified that the screenshot with the larger Apple Music heading is the reference and requested a 36sp candidate. Root heading moved 32sp → 36sp in `b1a410a`, provisional until physical smoke.
- The `am-descomp/docs/components/library-toolbar.md` DEX trace verifies the *conditional* Edit Pins menu entry: `getLibraryPins()` non-empty guard, icon `actionsheet_pin`, and the callback `scrollToTop()` + `LibraryViewModel.notifyModeChanged(false,false,false,true)`. It does **not** prove the pin editor’s layout, drag gesture, drop persistence, or delete affordance. Do not claim 1:1 Apple editor parity from this trace.
- Bitchord already stores pins in three independent native repositories (`CanonicalLibraryPinRepository`, `LibraryPlaylistPinRepository`, `CatalogMetadataPinRepository`) with a combined shelf. Any edit-mode UI must operate on their real identity/order, not a visual-only temporary list; support removal and persistent cross-family reorder safely. No edit UI was added in this evidence checkpoint.

## REA evidence gating — safe implementation cut, 2026-11-08

The outstanding investigation is tracked in [am-descomp #3](https://github.com/jssantogit/am-descomp/issues/3) (category geometry/typography/search/state), alongside [#2](https://github.com/jssantogit/am-descomp/issues/2) (iconography) and [#1](https://github.com/jssantogit/am-descomp/issues/1) (Edit Pins gestures). This PR remains a draft and does not claim 1:1 visual parity.

| UI item | Verified reference | Implementation decision |
| --- | --- | --- |
| Root sections | A captured state shows title, pinned artwork, vertical category links and Recent Additions | Retain native Bitchord sections, real pin sources and existing navigation; no hardcoded cover contents |
| Root pinned layout | 3-column cover arrangement observed in a reference frame, not proof of universal device layout | Retain existing three-column shelf with up to six initially shown |
| Create playlist action | `toolbar_new_playlist` has 24dp vector and a conditional DEX connection to `new_playlist_button` | Use source-traced resource, preserve Bitchord's real create flow |
| Top action circles | A `profile_foryou=40dp` modifier path and rounded shape are statically traced; final bounds/status inset unknown | 40dp surface remains a candidate, not verified final hit target |
| Category glyphs | `LibrarySections` resource IDs and 40dp intrinsic viewports traced, but source vector path data are not in clean Git | Retain explicitly non-original stand-ins until #2; never label as Apple icons |
| Category row positions | Pixel contrast measurement of 709×1536 screenshot, density/font-scale unknown | Keep provisional screen-specific insets; no fabricated px→dp |
| Typography | Generic Body/Subhead/Title2 tokens, no proven link to all Library rows | 36sp Library heading remains a user-requested visual candidate; no verified Apple font size |
| Search control | Different XML resources declare 48dp internal background or 42dp playlist-individual field; actual category binding unknown | Keep 48dp *only* in Bitchord Library collection variant; restore pre-migration 46dp on other native detail pages, tested in `PlaylistLibraryVisualReuseTest`; 48dp is a candidate, not parity |
| Category destinations | Partial DEX navigation and conditional states | Preserve existing native actions, source filters, ratings and category destinations |
| Edit Pins | Menu condition and mode notification confirmed, drag/remove/persistence not | **Not implemented**, blocked by #1 |

Validation before acceptance: Android CI + shared JVM/desktop gates, a smoke APK from a green commit, then paired physical Android screenshots at identical density/font scale. Leave draft and avoid merge.

## Explore genre cards — Apple Search visual evidence, 2026-11-08

User scope: retain Bitchord **Explorar** as a genre-only destination; adopt ONLY the visual treatment of the illustrated Apple Search cards, large native Explore heading and a single circular three-dot overflow to Settings. **Do not** add the Apple Search field or introduce its editorial/mood shortcuts (Shows, Essenciais, Cantar, etc.). Use only catalog genre identities and their real existing destinations.

Evidence consulted: `am-descomp/docs/DESIGN.md`, `docs/design-system.md`, `docs/screens/top_level_search_page.md`, `docs/design/{typography,radius,spacing}.json`. A runtime frame shows a 2-column grid of illustrated tiles and overlaid lower title. The decoded candidate `search_landing_tile.xml` has a rounded image with constrained aspect and bottom title, but its direct use in the rendered landing list is not proven. The XML remains local-only and full layout values are not demonstrated; generic radius tokens do not prove which radius belongs to the tile.

Implementation on existing shared `ExploreScreen`/`NativeExploreCategorySleeve`: full-bleed tinted artwork instead of the former left stripe, title anchored bottom-left, no new category route or additional search. Original Bitchord `MOOD_CARD_ASPECT` and `MOOD_SPACING` remain. At user request after reviewing paired screenshots, the Explore root applies a **40dp reduction of the top content inset only when its large title is visible** and **10dp rounded card corners**. The 10dp corner is a documented general design radius in `am-descomp`, but its binding to this specific tile is unproven; the 40dp offset is screenshot-informed, not a recovered Apple layout value. Both are deliberate visual candidates awaiting device comparison. Source image and category content remain catalog-derived and are not Apple editorial assets. One standalone round overflow on undrilled Explore root reuses the already implemented Settings-only menu; no account pill and no search field. Tests guard genre-only content and existing navigation.

Neither exact Apple tile geometry, font face, measured color overlay, nor editorial imagery is claimed reproduced 1:1. Acceptance is subject to CI and physical screenshots.

## Root Explore/Library overflow alignment and popup parity — 2026-11-09

Paired device screenshots reveal two deviations: the independent ⋮ circles sit above the large in-list headers, especially in Explore, and Compose Material's rectangular `DropdownMenu` does not match the compact dark Apple popup.

Evidence consulted: `am-descomp/docs/DESIGN.md`, `docs/components/overflow-menu.md`, `docs/components/library-toolbar.md`, and the user-provided Apple/Bitchord screenshot pair. The REA marks the dark rounded top-right popup with opening/closing as `runtime_observed` on Home/New tabs. Exact width, corner radius, anchored offset, animations, and specific use on Explore/Library remain `unknown`. Library More Options has a statically traced 40dp rounded modifier; no final screen placement is established.

Safe Bitchord adaptation: reusable `AppleRootSettingsOverflow` for the root Explore and Library surfaces, showing **only Configurações**. A focusable popup dismisses on outside tap; selecting Settings closes the popup and invokes the existing settings navigation callback. The menu and option have Compose tags to test opening, absence of unrelated actions, click and dismissal. `FrostedTopBar.rootActionsOffset` lowers root controls by a screenshot-guided 20dp in Explore and 10dp in Library to align with their large headings; undrilled category/detail toolbars keep their existing positions. The 236dp popup width, 22dp radius, 38dp popup offset, 68dp row height and the root offsets are **visual approximations**, not purported Apple DEX measurements.

Edit Pins remains blocked by am-descomp issue #1; account-related menu actions are not added. Both tabs preserve their underlying routing, content and filters. Do not claim pixel parity until reviewed on an Android APK.

## Physical feedback — category chrome and glass popup (2026-11-09)

Reference pairs: Apple Music screenshots contain the NewJeans artwork and populated pinned library; the screenshots showing native Bitchord's sparse library, loading artists and the single-option `Configurações` popup are the implementation under test. Do not reverse that attribution.

- **Category header:** Bitchord's 44dp floating Back circle was visibly larger than the independent 40dp source/filter/overflow circles. The undrilled Library category and Playlists roots now request a 40dp Back surface and shift both Back and right-side controls +12dp vertically relative to the prior top-bar slot. Other screens retain the original 44dp Back size and existing placement. This vertical correction is user-screen-comparison-driven; `am-descomp/docs/components/library-toolbar.md` confirms a 40dp modifier input for a More Options action but explicitly does not prove final touch bounds or status-bar insets.
- **Root overflow:** The previous `Popup` had an almost opaque surface, 236dp width, 22dp radius, heavy 12dp shadow and tall 68dp Settings row. It now reuses `artworkPageSurface` (shared Bitchord blur/liquid glass/fallback branch), width 180dp, radius 17dp, row height 52dp, body text, reduced padding and no standalone heavy shadow. This is an aesthetic adaptation from the user screenshots; `am-descomp/docs/components/overflow-menu.md` confirms a compact rounded dark menu, not exact sizes or background sampling. The actual blur result must be verified on device because popup windows may differ from in-window haze layers.
- **Scope:** Only Configurações is present; no editor for pinned items (blocked by am-descomp #1), no extra account actions, no Search field introduced to Explore. Regression test covers the compact Back action and existing overflow Settings action. Do not trigger APK/smoke for this checkpoint at user request.


## REA follow-up — 2026-11-09 (am-descomp issue #3 closed)

Source: `jssantogit/am-descomp`, branch `fase-3-investigacao-visual`, `docs/components/library-category-behavior.md`, `docs/library-reference.md`, `evidence/library-category-investigation-callback.json`. Research issue [#3](https://github.com/jssantogit/am-descomp/issues/3) is closed; iconography [#2](https://github.com/jssantogit/am-descomp/issues/2) and Edit Pins [#1](https://github.com/jssantogit/am-descomp/issues/1) remain open.

**New static findings, not runtime claims:**

- The ordinary Library category callback passes `LibrarySections` through an adapter to `library_details_fragment`; `LibraryDetailsPageFragment` inflates `library_details_page_fragment` with `library_details_search_bar` and `content_layout_library_section`. This path covers Artists, Albums, Songs and Playlists. The separate declared Downloads action has not been proven to be the actual Downloads-row destination.
- `LibraryDetailsViewModel` maps Artists → page type 6, Albums → 3, Songs → 1 and Playlists → 4, and resolves category-specific search hints. The source declares a 64dp search root with 48dp inner background and 8dp top margin. The playlist **item detail**, in contrast, references `collection_search_layout` with a 42dp field. They must not be treated as a single measured UI control.
- The category icon glyph is sourced from `LibrarySections`; XML declares 40×40dp intrinsic vectors. Category-row modifiers consume 5dp horizontal glyph margin and 10dp vertical label margin (12dp for `sw600dp`). Neither final rendered size nor spacing, font, touch target or dynamic tint has been proven.
- The toolbar title's XML style inherits 28sp, exposes a 32sp minimum-text-size property and has overlapping font family references. Do not infer an effective rendered font size from those declarations.
- Pin editor research now identifies removal and position-update callbacks, but no verified gesture, drop/commit semantics or persistent cross-family order. Do not ship a speculative Edit Pins clone.

**Bitchord implementation boundary:** Retain the real native category routes, per-category filter/search state, 48dp category search variant and separate playlist-detail controls; existing Bitchord-specific screen geometry remains provisional. Do not change Downloads routing on an unproven Apple linkage or treat the 40dp intrinsic SVG viewport as a 40dp rendered icon.

**Validation:** The 2026-11-09 Android CI failure on the previous HEAD was a UI test searching `Servidor` while the Apple origin tabs intentionally render uppercase. The test has been updated to assert all three uppercase origin labels and selection/back behavior. New CI and device visual acceptance remain pending; this research handoff does not imply that library pixels are now exact.

## Library category icon implementation — 2026-11-09

After the REA issue #2 partial recovery, five `LibrarySections` vector drawables are now versioned in Bitchord's shared Compose resources: `ic_apple_library_{playlists,artists,albums,songs,downloads}.xml`. Source references: `am-descomp/ui-assets/private-reference/library/ic_library_link_icon_{playlists,artists,album,songs,downloads}.xml`, branch `fase-3-investigacao-visual`; matching static object-to-resource associations in `docs/components/library-iconography.md`.

- `LibraryPhoneParts.libraryNavigationLinks` selects the reference glyphs through the existing `LibraryLink.logo` render slot. The old Material icons remain only as fallback values on the same native links. The downloaded category retains the original `local:downloads` browse ID and original callback behavior.
- `LibraryScreen.LibraryLinkList` renders the compact source icon with its 40dp intrinsic viewport, including the inset glyph, rather than shrinking the entire 40dp drawing to the old 22/24dp Material icon slot. Text spacing stays tied to the native compact list geometry. Noncompact link rendering and unrelated source-folder rows are unchanged.
- The vectors use white path fill with theme tint supplied by the existing `Icon` composable; the path geometry is preserved. Reference resource availability and DEX links are static evidence, **not** proof of runtime tint, size, touch bounds or pixel-perfect equivalence.
- `LibraryNavigationLinksTest` now asserts resource choice and preservation of Downloads routing, complementing the existing localized order/navigation tests. Physical rendering on dark/light themes and A54 font scale is still required.
- No implementation of Edit Pins was inferred from its unresolved gestures or persistence; no extra Apple destinations were fabricated. Rights for redistributing third-party vector geometry remain a separate consideration before making the fork public.

All changes remain on draft PR #212, without a merge into #209/main.
