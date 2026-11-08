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
