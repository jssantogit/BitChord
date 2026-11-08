# PR #212 — Apple Music 7.0.0 queue-surface experiment

## Scope from the annotated reference

**Included:** compact current-track header (favorite star, vertical overflow), History and Clear, the continuation/autoplay list and its typography, the recovered four-line reorder grip, vertical scrolling, press-and-drag reorder, horizontal swipe-to-remove, and the existing three-dot song-action sheet limited to supported first-party actions.

**Explicitly excluded:** the crossed-out shuffle/repeat/infinity mode row and the crossed-out scrubber, lossless/badge row, playback/volume controls and bottom navigation. This change does **not** replace the player's existing transport, AutoMix, player sheet, queue logic or sources.

AM-DECOMP evidence: `docs/components/player-panels.md`, `docs/motion/player-panels.md`, `docs/components/player-action-sheet.md`; the recovered original glyphs are from `ui-assets/catalog/svg/{actions,player}/base/drawable`. All timing of new swipe and item-motion effects remains an approximation, not verified Apple runtime. The following four assets are private experimental references and have no verified permission for redistribution:

| MPP drawable | Original AM-DECOMP SVG |
|---|---|
| `apple_queue_favorite.svg` | `actions/base/drawable/ic_nowplaying_favorite-ltr.svg` |
| `apple_queue_favorited.svg` | `player/base/drawable/ic_nowplaying_favorited-ltr.svg` |
| `apple_queue_more.svg` | `actions/base/drawable/ic_nowplaying_more-ltr.svg` |
| `apple_queue_drag_handle.svg` | `player/base/drawable/ic_nowplaying_drag_handle-ltr.svg` |

## Implementation contract

- `InlineQueue` is still the **same** reusable list, using the actual `QueueTier` and `queueEntryId`/timeline indices; only portrait Android Now Playing opts into `appleStyle`.
- The current track exists only once in the Apple variant, in the header inherited from `NowPlayingScreen`. History contains only real entries before `currentIndex`. Clear History calls Media3 `removeMediaItems(0, currentMediaItemIndex)`, so the playing track and all upcoming entries remain. Clear beside the manual queue still clears **only manually queued** entries using the existing callback.
- Active/manual/context/autoplay sections retain semantic identities; manual/context tracks are not silently relabeled as autoplay. The continuation heading is shown only for AutoPlay when enabled or already queued, and never manufactures recommendations.
- The handle is at the **right** of future-track rows; it keeps the existing drag coordinator, boundary checks, per-section reordering and edge auto-scroll. History cannot be reordered or swipe-removed. Swiping a future row left crosses the 72dp threshold to invoke the existing remove callback; a held row and locked-party queue are inert. The legacy presentation (e.g. desktop/landscape) remains unchanged.
- Follow-up: the original star glyph is now a player-wide **native Recording rating entry**, not a separate Favorite. It directly opens `CanonicalSongRatingDialog` for the currently playing song, including first-time ratings. A verified Recording/MediaAsset identity is mandatory; no heuristic match or retired YouTube thumbs rating can be written. A filled glyph indicates a valid first-party local rating, **not** a remote numeric rating. The previous experimental SharedPreferences Favorite toggle and its code were removed without converting old favorites to star scores.
- The existing `SongActionsSheet` remains one modular sheet. The Apple queue context hides **Create Station**, permits the native local playlist picker without a retired provider login, and offers download, Play Next, Add to Queue, open album/artist when ids are known, and music-text sharing. **No rating editor or Favorite toggle appears in the Now Playing/queue ⋮ menu**: the permanent player star replaces both. Track-row menus *outside* the player retain their rating action until they have a direct star. Unsupported Share Station, Suggest Less, Credits and Sleep Timer options are not presented.
- The specialized action-set flag is passed **only** by the queue header's vertical ⋮ action. It is false in the normal Now Playing ⋮ and other track menus. `shouldUseAppleQueueSongActions` has explicit tests for all four combinations of caller location and player context. `shouldShowRatingEditorInSongActions` prevents duplicate rating actions for either player's menu while preserving non-player song-row access.

## Validation

Static tests cover the queue split, duplicate keys, swipe safety, queue-only menu policy, and native-rating selected state. The new first-time click action reuses the existing verified-Recording editor and must also be checked in a physical smoke. Tests of internal sharedUi implementation live in sharedUi's JVM test source set; Android-module tests cannot access Kotlin `internal` across Gradle module boundaries. Android CI must compile the shared Compose SVG painters and finish Android unit tests; code publication is not a physical smoke. APK workflow stays manually triggered by applying `apple-music-smoke` to draft PR #212. A device check should cover real history and clear semantics; manual and autoplay reordering including long lists; gesture conflict with vertical scroll and sheet dismissal; native rating after app restart, including a first rating; menu availability; no change to excluded transport/volume/progress UI.

## Follow-up: player-wide rating star

The Apple SVG variants named `apple_queue_favorite` and `apple_queue_favorited` now indicate **unrated / locally rated**, not a Favorite toggle. The star appears in Android's song, lyrics and queue panels in portrait and in the corresponding landscape states. Existing desktop/non-experimental consumers retain their old rating-strip presentation. The current Recording score lives in Listening Room and updates through the existing canonical rating presentation snapshot. The action is a pure entrance to the existing verifier and modal; tapping never writes a score. The three-dot menu continues to open the same modular sheet with its other supported actions.

No APK build was triggered by this documentation change. The latest code commit must first be compiled/tested; the smoke APK workflow remains a manual PR label action.
