# REA #212 — shared Apple-reference visual foundation (2026-11-09)

## Scope

This is an **Android-phone global component pass** on the existing draft PR #212, not a second screen or a rewrite of providers, playback, playlist metadata, search or library state. Every change below attaches to the shared `FrostedTopBar` or `BitChordTheme`. All selected routes keep the same callbacks.

Source of truth: `jssantogit/am-descomp`, branch `fase-3-investigacao-visual`, reference Android 7.0.0 build 1614, HEAD `7200e849`. Focused files:
- `docs/components/library-toolbar.md` — traced Library More Options modifier input `40dp`, 50% corners, 28dp intrinsic vector, static route to More Options.
- `docs/components/overflow-menu.md` — observed compact, top-right menu, distinct from a playback action sheet, with Settings and Account visible in the Apple app; exact bounds/callbacks unresolved.
- `docs/design/typography-components.md`, `docs/design/font-widget-resolution.md`, `docs/design/font-compose-resolution.md` — XML role chains and conditional DEX font resolution. No proof of a global font or runtime metrics.
- `docs/design/font-assets.md` — 22 font binaries, 77 family XMLs and documented `sf_pro_android_ui` variable resource (private reference), with license and runtime-selection limits.
- `docs/design/composition-surfaces.md` — reused two-layer material composition and resource-qualified night color; does **not** prove all active blur configuration.

## Global Android changes

1. **Floating top bar:** `ArtworkPageActions` now subcomposes an independent rounded background for each visible small action, using the existing `artworkPageSurface` material, per-action semantics and original click callbacks. 48dp is the documented alternative toolbar-token candidate, **not** a claim that every Apple surface uses the same rendered hit target. The Library's separately traced 40dp circles remain unchanged. Zero-size/hidden actions do not leave empty surfaces or gaps. Text-only status badges are not transformed into fake circular icon buttons.
2. **Settings access:** `TopBarAccountButton` is the existing shared entry point for screen-level Settings. Its avatar presentation has been replaced by a 24dp `MoreVert` icon in a 48dp control and the same compact Settings popup used by Apple Library/Explore routes. The former swipe-account gesture no longer runs from this glyph; account management remains in Settings. The popup geometry (180dp width, 17dp radius, 38dp offset) is **Bitchord screenshot calibration**, not XML/DEX-proven Apple measurements.
3. **Global typography:** `BitChordTheme` now supplies all 15 Material3 text roles with a coherent SF Pro Display-based Android type scale. The reference **proves specific role inputs**, not all Compose roles: base miniplayer 13sp/12sp, detail-library Title1 inherited 28sp, a candidate editorial header 20sp and link 17sp. Remaining mapped role sizes and weights are explicitly Bitchord adaptations. Negative global tracking was removed because the reference's letterSpacing calculation is conditional. The previously bundled SF Pro Display assets remain in use: these are **not** identical to the archived `sf_pro_android_ui` reference, and this change does not add or redistribute any extracted font.
4. **Miniplayer typography:** the phone's `AppleMusicChromeMetrics` now uses documented base 13sp/12sp text, while maintaining the previous height, artwork, playback controls and gestures. The 15sp wide-layout variant is documented but not automatically selected without the reference qualifier state.

## Exclusions by design

- Play/Shuffle and other labeled, meaningful **action pills** remain, as in Apple Music's album contexts. Tabs and filters are selections, not floating icon actions. Media content rows, albums, playlists and player playback buttons are not turned into arbitrary circular controls.
- Do not flatten Apple `bg_apple_material` into a global window color or assume a blur radius from XML resource names.
- Do not replace square Library covers with the SearchLanding Epoxy tile's separately traced 1.777 aspect ratio.
- The same theme applies to Android-phone Compose screens; Wear and Desktop are not targets of this private mobile UI experiment.
- Pixel-perfect font metrics, app-wide `sf_pro_android_ui` runtime selection, touch bounds, popup dimensions, RTL and fontScale output are still unverified. The Bitchord fork remains private; extracted artwork and font redistribution rights were not established.

## Regression contract

- `GlobalAppleTopBarChromeTest`: the former profile entry now exposes a three-dot Settings popup; two visible toolbar buttons must draw two independent circular surfaces even when a third hidden slot is present.
- `GlobalAppleTypographyTest`: global type roles use one shared SF Pro Display-based scale with explicit size and weight constraints.
- `AppleMusicBottomBarVisualMetricsTest`: base miniplayer labels follow the traced 13sp/12sp XML style inputs.
- **Acceptance still requires** Android CI and a physical dark/light, RTL/fontScale and multiple-item toolbar screenshot comparison. These tests do not prove visual pixel equivalence.

No changes to #209 or main and no smoke APK auto-dispatch.
