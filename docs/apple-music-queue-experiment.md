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
- The queue-header star persists SHA-256 hashes of exact playback-media identities in a private Android SharedPreferences set. This is **not** a 5-star rating, not a retired-provider thumbs-up, and not a claim to synchronize with every Library shelf. Existing ratings stay accessible in the three-dot menu.
- The existing `SongActionsSheet` remains one modular sheet. The Apple queue context hides **Create Station**, permits the native local playlist picker without a retired provider login, and offers native Favorite, rating, download, Play Next, Add to Queue, open album/artist when ids are known, and music-text sharing. Unsupported Share Station, Suggest Less, Credits and Sleep Timer options are not presented.

## Validation

Static tests cover the queue split, duplicate keys, swipe safety and favorites identity. Android CI must compile the shared Compose SVG painters and finish Android unit tests; code publication is not a physical smoke. APK workflow stays manually triggered by applying `apple-music-smoke` to draft PR #212. A device check should cover real history and clear semantics; manual and autoplay reordering including long lists; gesture conflict with vertical scroll and sheet dismissal; native favorites after app restart; menu availability; no change to excluded transport/volume/progress UI.
