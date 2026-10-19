# O1 — Runtime Performance Baseline v0

Status: implementation candidate; integration requires explicit orchestrator authorization.
Scope: **observation only**. No startup ordering, MediaAsset selection, Playback, Provider, Library ranking, schema, persistence, capture policy or Android CI gate changed.

## Instrumentation already available (PR #39)

| Existing operation | Valid duration(s) | What is NOT inferable |
| --- | --- | --- |
| `play_to_audible` | TRACE_END total command-to-audible, including failure/cancellation totals | `command_accepted`, `media3_ready`, `audible` are events without timestamp/duration; no phase latency can be derived from them |
| `opensubsonic_runtime_resolve` | TRACE_END total and `access_lookup` SPAN | No Media3 decoding/network streaming or listener startup latency from the provider-only span |
| `library_rank` | TRACE_END total; `candidate_generation`, `personal_rank`, `session_rerank`, `artist_diversity`, `controlled_exploration` SPANs | Candidate details/events are not timed stages |
| `main_thread_stall` | Recovery-only observation >=250 ms | No observation before recovery; this is not a heartbeat count |

The read-only `RuntimePerformanceBaseline.summarize` processes *existing typed points* and explicitly supplied **recovered** stall observations. It neither installs tracing nor collects raw identifiers. It accepts only the fixed operation/stage taxonomy above. Trace IDs are used transiently to correlate START/END and then discarded; **attributes, UUIDs, URLs, credentials and exception messages are never exported**. Orphan/unpaired traces are counted separately and never treated as completed durations.

Each report contains the typed scenario, declared repetition/warmup/concurrency and trace mode, correlated and incomplete counts, plus per-operation/stage/outcome **count, min, max, spread, p50, p95** in nanoseconds. Quantiles use *nearest rank*. p50 needs >=2 actual observations; p95 needs >=20. Under-sampled quantiles are marked `insufficient_samples`, not invented. Phase distributions are sorted deterministically; same typed traces + configuration produce byte-for-byte identical safe output.

## Reproducible JVM scenarios

Run focused tests from the repository root:

```sh
./gradlew -Pbitchord.bundleWear=false :app:testDevDebugUnitTest --tests 'com.music.bitchord.observability.RuntimePerformanceBaselineTest' --tests 'com.music.bitchord.observability.RuntimeBaselineScenariosTest'
```

CI runs the full `:app:testDevDebugUnitTest` and `:app:assembleDevDebug`, with its usual APK upload. JUnit XML retains captured `O1_*_JVM` standard output in `app/build/test-results/testDevDebugUnitTest/` for a local run. Report content can be reproduced by rerunning those tests; do not compare raw absolute numbers across different machines or CI runners.

| Fixture | Execution and real observation | Limits |
| --- | --- | --- |
| Provider cold/warm | Real `OpenSubsonicRuntimeTransport`, localhost HTTP fake and real OkHttp disk `Cache`; 20 cold cache evictions and 20 warm resolutions; server request count asserts cache hits; `System.nanoTime` spans | No Navidrome, hardware decoder or physical I/O |
| Failure/reconnection | Same real provider transport; fake HTTP 503 then recovery to 200; 8 concurrent coroutine resolutions, safe terminal outcomes | Deterministic fault injection, not a physical network outage |
| Playback E2E | Real `PlaybackE2ETraceObserver`, 20 command/ready/audible callback sequences with injected monotonic clock | Media3 callback model only, not an audio-device measurement |
| Stall recovery | Real `MainThreadStallState`, repeated threshold crossings/recoveries with controlled clock-like samples | Recovery classification, not proof of Android scheduling delay |
| Recommendation | Real typed `StructuredTracer` measured `library_rank/personal_rank` span; existing `LibraryRecommendationTracingTest` exercises actual ranking pipeline | Synthetic JVM work in the timing fixture; cannot claim actual large-library performance |
| Trace privacy and correlation | Whitelisted phases, ID reuse, incomplete traces, hostile attributes, 20-run quantiles | In-memory typed sample processor only |

The **measured** JVM scenarios use real monotonic durations where marked, without thresholds claiming speedups. Injected-clock cases are deterministic correctness fixtures, **not** device speed measurements.

## Comparison protocol for actual app capture

1. Fix app SHA, build variant, hardware/emulator, diagnostics mode, library contents, Navidrome dataset, network, battery/thermal conditions and cache state; do not store server endpoints, device IDs or credentials in the report.
2. Warm up explicitly and exclude warmup samples. Collect >=20 completed traces **per comparable phase/outcome and scenario** when claiming p95. Use a fixed independent repetition count.
3. Separate cold/warm, degraded/failure/reconnect, saturation and controlled stall/recovery. Compare p50, p95, min/max spread and counts; note non-completed/orphan traces and dropped diagnostics writes separately.
4. Use the ordinary in-app dev Diagnostics export for real-device evidence; redact before sharing and never coerce free-form exported text into fabricated typed timings.
5. Repeat in equivalent conditions and record *both* distributions plus run-to-run variability. Do not claim improvements without repeated paired before/after measurements. Performance changes belong in a later authorized slice; O2 only after O1 integration and revalidation.

## Physical evidence boundary

O1 adds no behavior requiring a new default Galaxy A54 smoke. Real Android scheduling, audible output latency, Navidrome reconnect and audio hardware remain **NOT_EXERCISED** by these JVM tests. PR #39's `radio:` / `links:` sanitization remains accepted from its physical capture; numeric Apple Music ID `motion artwork on album` remains **CI-tested only**, not physically exercised. Neither caveat is upgraded by O1.
