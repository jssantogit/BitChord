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
