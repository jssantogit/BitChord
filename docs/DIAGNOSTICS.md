# BitChord diagnostics

The diagnostics layer exists to measure before optimizing. It is deliberately separate from feature behavior: adding or removing a probe must not change startup ordering, playback state, source selection, or catalog identity.

## What is recorded

A diagnostic event carries:

- wall-clock and monotonic timestamps;
- a process-session id;
- severity and broad category;
- event/span name;
- thread name;
- optional duration;
- sanitized key/value metadata;
- sanitized error text.

The in-memory ring buffer retains at most 1,000 events. Event text, metadata count, metadata values, thread names, and formatted names are individually bounded so a single oversized log entry cannot defeat the ring-buffer limit. Debug builds also emit the formatted event to logcat under `BitChordDiag`.

The existing shared `DebugLog` API is unchanged. On Android debug builds its sink is routed into Diagnostics, so shared code gets the same sanitization and bounded event model without a cross-platform contract migration.

## Persistent capture

Persistent capture is opt-in and expires automatically after one hour. Starting or stopping capture commits the expiry state synchronously because this is an explicit, rare user action and the next operation may be an ADB force-stop. That guarantees an active capture can include the next cold launch instead of depending on an asynchronous preferences flush.

Capture writes happen on a single bounded background queue. Disk data is bounded to two capture segments of at most 2 MiB each (`capture.log` and `capture.previous.log`). If the writer queue is saturated, new persistence writes are dropped rather than blocking the caller; the dropped count is included in exports.

The latest uncaught crash is retained separately as a bounded snapshot of the most recent events, then the previous Android crash handler is invoked normally. Exported reports are also bounded by count: only the three newest reports are retained.

`Clear` removes the in-memory buffer, persisted capture segments, exports, and retained crash. It does not turn off an active capture window.

## Privacy

Diagnostics redact sensitive metadata keys and common inline credential forms before an event reaches the buffer, logcat, disk, or an exported report. This includes authorization headers, cookies, bearer credentials, token/secret/password/API-key/session-key metadata, and sensitive URL query parameters.

Reports are stored in the app-private files directory. The share action uses a non-exported, dev-only `FileProvider` and grants read access only to the chosen Android share target.

## Dev control panel

The control Activity, share helper, and diagnostics `FileProvider` live physically under the `dev` source set. They are therefore present in `devDebug` and `devBenchmark`, but are not compiled into `prod` variants. The process-wide diagnostics core remains in the main source set so production builds can retain bounded crash diagnostics and accept future low-overhead probes without exposing a developer UI.

Open the panel on a debug install:

```powershell
adb shell am start -n com.dev.bitchord/com.music.bitchord.diagnostics.DiagnosticsActivity
```

The panel provides:

- Start 1h capture
- Stop capture
- Clear
- Share report
- recent in-process events
- retained-crash status

For the benchmark package use:

```powershell
adb shell am start -n com.dev.bitchord.benchmark/com.music.bitchord.diagnostics.DiagnosticsActivity
```

## Startup measurement

`BitChordApplication.onCreate()` keeps the same initialization order and the same background `join()` behavior. Diagnostics only wraps the existing operations with spans.

Important startup span names include:

- `diagnostics_install`
- `auth_store_init`
- `source_registry_init`
- `innertubex_init`
- `canvas_cache_init`
- `background_init_total`
- `auth_session_read`
- `auth_session_restore`
- `app_settings_init`
- `lossless_output_init`
- `listen_together_init`
- `search_history_init`
- `last_played_init`
- `bounded_context_init`
- `party_queue_stash_init`
- `original_version_init`
- `downloads_init`
- `listening_stats_init`
- `artist_facts_init`
- `audio_cache_init`
- `spotify_token_init`
- `lastfm_init`
- `presence_install`
- `background_join_wait`
- `application_on_create`

To inspect one debug cold launch directly from logcat:

```powershell
adb logcat -c
adb shell am force-stop com.dev.bitchord
adb shell am start -W -n com.dev.bitchord/com.music.bitchord.MainActivity
adb logcat -d -s BitChordDiag
```

For a persisted cold-launch report, open the diagnostics panel first, start capture, return to BitChord, then force-stop and cold-launch the app. The capture expiry survives the force-stop, so the next process records its startup spans to disk. Reopen the panel afterwards and use **Share report**.

## Performance rule

Do not optimize a component because its source looks expensive. Use these spans and the Android-level startup/frame measurements to identify a measured bottleneck first, then change one bounded cause and compare before/after data.
