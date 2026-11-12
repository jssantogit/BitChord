# Second-phase experiment — bottom chrome motion (PR #212)

## Evidence and scope

Source: `jssantogit/am-descomp`, branch `fase-3-investigacao-visual`, `docs/MOTION.md`, `docs/motion/bars-and-indicators.md`, `docs/motion/navigation.md` and `docs/motion/player-expansion.md`.

- **Runtime observed:** the bottom navigation and miniplayer persist across tab changes and content scrolling; the active tab changes blue.
- **Static verified:** the selected tint comes from `selector_bottom_navigation_text`, and a bottom-sheet callback interpolates artwork position, scale and radius relative to the mini-player `video_surface_container`. A release-settle helper uses `1+(t-1)^5` under specific conditions.
- **Unknown:** animation duration/easing on individual tab selection, mini-player transport, tab switching and mini-player visibility. The generic Android navigation fade (150 ms) is **not proven** to be the tab animation.
- **Existing BitChord motion retained:** `PlayerSheetMotion`, `PlayerDock`, and `miniPlayerGestures` already handle player expansion/collapse, artwork flight, vertical pull and horizontal track skip. The approximation in BitChord must not be labeled a proven Apple animation.

## Implemented in this isolated experiment

1. Active tab tint now interpolates, while the selected glyph scales subtly with spring feedback and a pressed glyph briefly contracts. Radio stays grey and disabled, with no press or route.
2. Mini-player artwork and its two text rows crossfade when the song changes. The artwork's dock coordinate reporter is on the stable outer box, not duplicated inside the crossfade.
3. The miniplayer's play/pause glyph and loading state crossfade. Both transport buttons receive a short press/release response; previously present haptics and controls remain attached to the same handlers.
4. A horizontal track-skip gesture gives the mini-player contents limited lateral motion while dragging, then springs them back on release. The shared gesture recognizer has a default no-op callback, preserving legacy behavior elsewhere.
5. Mini-player creation/removal fades/slides as one unit with its spacer, retaining the last song during exit so it does not blink away. The two capsules themselves remain anchored and visible during scroll as documented, rather than inventing folding behavior.
6. System **Reduce animation** removes the tab, track, transport, visibility and horizontal drag effects. The existing reduced-motion fallback for player expansion remains intact.

All durations and spring constants added here are **experimental approximation choices**, not recovered values. The factual AM-DECOMP claims above must not be expanded by these UI defaults.

## Review gates

- Android unit tests and compile must pass on this exact HEAD; check the CI result.
- Confirm on device: switching tabs, inactive Radio, quick repeated taps, play/pause, loading, rapid track skips by button and swipe, upward pull into the full player, back-to-mini, track removal, and reduced-motion mode. Pay special attention to artwork docking during rapid track swaps.
- Do not claim pixel-identical Apple motion without a comparable device video. Smoke APK generation is intentionally manual (PR label `apple-music-smoke`), not triggered on every commit.
