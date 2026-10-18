# Diagnostics PR3 — Structured Tracing + Stall & End-to-End Latency

## Goal

Extend the existing bounded Diagnostics foundation into one privacy-safe observability system that can reconstruct feature execution as well as measure performance. This slice is diagnostics-only: it must not change provider selection, playback behavior, canonical identity, startup ordering, caching policy or performance behavior.

## Architectural constraints

- Domain/shared code must not depend on Android Diagnostics.
- Observability is best-effort and must never become a product-work dependency.
- No unbounded registry, per-frame persistence, playback-position tick logging or unrestricted high-cardinality taxonomy.
- Raw canonical IDs, authenticated URLs, tokens, cookies and credentials must not be persisted by structured traces.
- Existing Diagnostics bounded storage remains the sink: 1,000-event memory buffer, bounded writer queue and bounded capture files.
- Standard mode is the default. Legacy free-text DebugLog persistence is opt-in Verbose only; WARN/ERROR remain visible in Standard.
- CI and a physical Android Diagnostics export are required before acceptance.

## Stable vocabulary

Structured records use fixed concepts:

- `feature`: coarse product area, e.g. `playback`, `provider`, `catalog`, `ui`, `database`, `io`.
- `operation`: stable flow name, e.g. `seek`, `stream_resolution`, `queue_rebuild`.
- `stage`: stable step within a flow.
- `outcome`: `started`, `succeeded`, `failed`, `cancelled`, `skipped`, `degraded`.
- `traceId`: opaque correlation ID created by the sink.
- `ref.*`: entity references supplied separately from metadata and pseudonymized by the Android sink before persistence.

## Task 1 — Shared no-op structured tracing seam

**Files**
- Create `shared/src/commonMain/kotlin/com/music/bitchord/observability/StructuredTrace.kt`
- Create `shared/src/commonTest/kotlin/com/music/bitchord/observability/StructuredTraceTest.kt`

**RED**

Add common tests that require:
- a no-op tracer usable without Android;
- fixed trace outcomes and points (`event`, `startTrace`, `checkpoint`, `finish`);
- finish-once semantics;
- a maximum checkpoint budget so one trace cannot grow without bound;
- metadata and entity references represented separately.

Run shared tests in CI and confirm RED because the contract does not exist.

**GREEN**

Implement a small dependency-free common contract:
- `StructuredTracer` interface;
- `StructuredTrace` handle;
- `TraceEvent` / `TracePoint` value objects;
- `TraceOutcome` enum;
- `EntityRef(kind, value)` separated from normal metadata;
- `NoOpStructuredTracer` and no-op handle;
- bounded handle implementation support with a default max checkpoint count.

No global active-trace registry is introduced in `:shared`.

## Task 2 — Android Diagnostics adapter, pseudonymized references and modes

**Files**
- Create `app/src/main/java/com/music/bitchord/diagnostics/DiagnosticMode.kt`
- Create `app/src/main/java/com/music/bitchord/diagnostics/DiagnosticReferencePseudonymizer.kt`
- Create `app/src/main/java/com/music/bitchord/diagnostics/DiagnosticsStructuredTracer.kt`
- Modify `app/src/main/java/com/music/bitchord/diagnostics/Diagnostics.kt`
- Modify `app/src/main/java/com/music/bitchord/diagnostics/DiagnosticsCore.kt` only if the event schema needs fixed trace fields
- Add focused tests under `app/src/test/java/com/music/bitchord/diagnostics/`

**RED**

Tests require:
- the same raw entity reference maps to the same opaque alias within one diagnostics session;
- different reference kinds do not collide;
- raw reference values never appear in formatted structured records;
- metadata still passes through the existing sanitizer;
- Standard mode persists structured traces and WARN/ERROR legacy logs but suppresses legacy DEBUG/INFO free text;
- Verbose mode allows legacy free text;
- trace/checkpoint events expose only stable low-cardinality names plus `traceId`, `feature`, `operation`, `stage`, `outcome` and pseudonymized `ref.*` fields.

**GREEN**

Implement the adapter without changing existing Diagnostics capture bounds. Pseudonymization is per process/session and uses a random session salt plus SHA-256, truncated to a compact opaque alias. The raw value is never inserted into event metadata.

## Task 3 — UI-first Standard/Verbose capture control

**Files**
- Modify `app/src/dev/java/com/music/bitchord/diagnostics/DiagnosticsActivity.kt`
- Modify `app/src/main/java/com/music/bitchord/diagnostics/Diagnostics.kt`
- Add/update tests for mode state where practical.

**RED/GREEN**

Expose the diagnostics mode in the existing Diagnostics screen. Default to Standard. Verbose must be an explicit opt-in and the report metadata must state which mode produced the capture. Do not require ADB.

## Task 4 — Main-thread stall detector

**Files**
- Create `app/src/main/java/com/music/bitchord/diagnostics/MainThreadStallDetector.kt`
- Create `app/src/test/java/com/music/bitchord/diagnostics/MainThreadStallDetectorTest.kt`
- Wire from `BitChordApplication` only for debug diagnostics.

**RED**

Pure detector-state tests require:
- no event below threshold;
- one bounded event for a delayed heartbeat;
- stall duration and severity bucket without stack/content payload;
- no event storm for one continuous stall.

**GREEN**

Use a background watchdog + main-thread heartbeat. Emit only threshold-crossing aggregates; never sample continuously into persistence.

## Task 5 — Playback end-to-end trace vertical

**Files**
- Modify the existing PlaybackService / Media3 integration files located during implementation.
- Add focused unit tests around a small tracker/state machine rather than testing Android Media3 directly.

**RED**

Require bounded traces for:
- seek command accepted → observable ready/playing state;
- prepare command → ready state where the callback boundary exists;
- cancellation/supersession when a newer operation replaces an unfinished one;
- at most one pending trace per operation kind.

**GREEN**

Wire the neutral tracing seam at the playback-owned boundary. Keep existing PR2 synchronous timings; PR3 adds the asynchronous chain rather than redefining them.

## Task 6 — Provider/stream-resolution functional trace vertical

**Files**
- Modify the existing OpenSubsonic stream-resolution path.
- Add focused tests around the trace stages and privacy boundary.

**RED/GREEN**

Trace stable stages such as canonical asset lookup → provider configuration available → request resolved / failed. Use pseudonymized entity references and provider key only. Never trace the authenticated stream URL, token, password, salt or raw canonical IDs.

This vertical proves that tracing is useful for feature debugging, not only latency measurement.

## Task 7 — Legacy DebugLog migration boundary

**Files**
- Modify the Android DebugLog bridge / Diagnostics hook.
- Do not mass-convert every call site in this slice.

In Standard mode, DEBUG/INFO free-text DebugLog entries are not persisted. WARN/ERROR remain available as fallback diagnostics. Verbose explicitly restores legacy detail for temporary investigations. New PR3 instrumentation must use structured tracing, not new free-text DebugLog calls.

## Task 8 — Verification and physical acceptance

Run the repository Android CI until all tests and dev APK build are green. The PR remains draft until physical Android evidence confirms:
- Standard capture exports structured `trace_*` records with correlation IDs;
- no raw recording/media-asset/content URI/auth data appears in structured trace fields;
- seek/prepare/provider flows can be reconstructed from stages/outcomes;
- main-thread stalls produce bounded events when triggered naturally;
- `droppedPersistenceWrites=0` in representative use;
- legacy DEBUG/INFO free-text noise is absent in Standard mode and present only after explicit Verbose opt-in;
- existing `runtime_frame_window` and PR2 probes still work.

## Non-goals

- No performance optimization.
- No provider matching or fallback behavior changes.
- No queue identity/contract changes.
- No broad rewrite of every legacy DebugLog call site.
- No external telemetry/upload service.
- No always-on release analytics.
