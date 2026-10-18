# BitChord diagnostics

The diagnostics layer exists to measure before optimizing. It is deliberately separate from feature behavior: adding or removing a probe must not change startup ordering, playback state, source selection, or catalog identity.

## Normal workflow: UI first

Normal diagnostic collection is performed entirely inside BitChord. A tester should not need ADB, a terminal, logcat, Perfetto, or dumpsys to capture and share an application-level report.

Use this path on a dev build:

1. Open **Settings → Advanced Options → Diagnostics**.
2. Tap **Start capture**.
3. Return to BitChord and reproduce the problem.
4. Return to **Settings → Advanced Options → Diagnostics**.
5. Inspect the recent events shown in the panel.
6. Tap **Share report** and send the exported report.

The report exported by **Share report** is the canonical diagnostics artifact for the normal workflow.

The panel also provides **Stop capture** and **Clear**. `Clear` removes the in-memory buffer, persisted capture segments, exports, and retained crash data; it does not stop an active capture window.

Persistent capture survives process restart. To capture a deterministic cold startup without ADB, start capture and use **Restart & capture startup** from Diagnostics. After BitChord relaunches, return to Diagnostics, inspect the startup events, and use **Share report**.

The Diagnostics row is shown only when the dev-only Diagnostics Activity is present in the installed variant. Production variants therefore do not expose the developer control panel.

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

The in-memory ring buffer retains at most 1,000 events. Event text, metadata count, metadata values, thread names, and formatted names are individually bounded so a single oversized log entry cannot defeat the ring-buffer limit. Debug builds can also mirror formatted events to logcat under `BitChordDiag`; that mirror is optional and is not part of the normal collection flow.

The existing shared `DebugLog` API is unchanged. On Android debug builds its sink is routed into Diagnostics, so shared code gets the same sanitization and bounded event model without a cross-platform contract migration.

## Persistent capture

Persistent capture is opt-in and expires automatically after one hour. Starting or stopping capture commits the expiry state synchronously because this is an explicit, rare user action and the next operation may restart the process. That guarantees an active capture can include the next cold launch instead of depending on an asynchronous preferences flush.

Capture writes happen on a single bounded background queue. Disk data is bounded to two capture segments of at most 2 MiB each (`capture.log` and `capture.previous.log`). If the writer queue is saturated, new persistence writes are dropped rather than blocking the caller; the dropped count is included in exports.

The latest uncaught crash is retained separately as a bounded snapshot of the most recent events, then the previous Android crash handler is invoked normally. Exported reports are also bounded by count: only the three newest reports are retained.

## Privacy

Diagnostics redact sensitive metadata keys and common inline credential forms before an event reaches the buffer, logcat mirror, disk, or an exported report. This includes authorization headers, cookies, bearer credentials, token/secret/password/API-key/session-key metadata, and sensitive URL query parameters.

Reports are stored in the app-private files directory. The share action uses a non-exported, dev-only `FileProvider` and grants read access only to the chosen Android share target.

## Dev control panel

The control Activity, share helper, and diagnostics `FileProvider` live physically under the `dev` source set. They are therefore present in dev variants but are not compiled into prod variants. The process-wide diagnostics core remains in the main source set so production builds can retain bounded crash diagnostics and accept future low-overhead probes without exposing a developer UI.

The normal entry point is **Settings → Advanced Options → Diagnostics**. The panel provides:

- Start capture
- Stop capture
- Restart & capture startup
- Clear
- recent in-process events
- retained-crash status
- Share report

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

Use **Restart & capture startup** for the normal persisted cold-start workflow.

## Advanced / optional Android tooling

The following tools are secondary engineering aids. They are not required for normal Diagnostics collection and their output does not replace the report exported by the app UI.

### logcat / ADB

The debug logcat mirror can be useful while actively investigating a device from a development workstation:

```powershell
adb logcat -c
adb logcat -s BitChordDiag
```

ADB may also be used for Android-level investigation when an engineer specifically needs shell access. Do not require `adb force-stop`, `adb am start`, or logcat collection from a normal tester; use the in-app Diagnostics controls instead.

### Perfetto

Use Perfetto only when system scheduling, frame timing, binder activity, I/O, or another Android platform-level trace is needed. Correlate the trace with the application-level Diagnostics report rather than treating Perfetto as the normal report path.

### dumpsys

Use `dumpsys` only for platform state that the application report cannot provide, such as package, activity, power, memory, or graphics service state. It is optional supporting evidence.

## Performance rule

Do not optimize a component because its source looks expensive. Use these spans and, when necessary, the optional Android-level startup/frame measurements to identify a measured bottleneck first. Then change one bounded cause and compare before/after data.
