# O2 — Diagnostics Overhead & Backpressure v0 (stacked on O1)

**Integration HOLD:** This change is intentionally stacked on O1 PR #44 at `1ec70c50c13bb92ed0549a94377eb5e030b885ee`. O2 PR #48 targets branch `perf/runtime-performance-baseline-v0`, **not main**. Neither PR may merge without separate, later orchestrator authorizations. O2 must be retargeted/revalidated after O1 merge + post-merge GREEN.

## Scope and semantics

O2 measures real BitChord `StructuredTracer` + `StructuredTraceDiagnosticMapper` + `DiagnosticSanitizer` + `DiagnosticBuffer` + `DiagnosticFormatter` + `DiagnosticWriteQueue` + `DiagnosticFileStore` paths using local JVM fixtures. There is **no runtime Diagnostics change** because no reliable paired before/after bottleneck has yet been established. Measurement code is read-only; no additional Android hot-path probes, privacy-policy change, playback/provider/listening/ranking behavior changes, persistence/schema migration or CI gate change.

**Capture OFF** means the ordinary Diagnostics persisted capture is **off** while existing memory/logcat tracing remains according to build/mode. It is NOT a fictional third `DiagnosticMode` or proof tracing itself is disabled. Separately, an explicit `StructuredTraceSink.NO_OP` control validates the no-sink API but is not the application's active capture-OFF mode.

## Repeatable measurements

```sh
./gradlew -Pbitchord.bundleWear=false :app:testDevDebugUnitTest --tests 'com.music.bitchord.diagnostics.DiagnosticsOverheadBaselineTest' --tests 'com.music.bitchord.diagnostics.DiagnosticsOverheadScenariosTest'
```

The normal Android workflow additionally gates the complete `:app:testDevDebugUnitTest`, `:app:assembleDevDebug` and dev APK artifact. The local JUnit report output contains `O2_JVM_OBSERVATION_ONLY` with sorted, numeric-only measurements (never identities, URLs, metadata or raw log lines).

Fixed fixtures: 5 warmups **excluded** from distributions, 20 measured repetitions for each of capture OFF / Standard / Verbose. Production-code operations are timed using JVM `System.nanoTime` at structured mapping, bounded ring append, event formatting, asynchronous writer submission, drain/flush and export creation. Both capture modes use real bounded background writer and filesystem, 512-byte test capture-segment limit, max two segments and three retained exports. Verbose requests the extra verbose-eligible trace event; Standard does not. The metric taxonomy and mode taxonomy are fixed enums rather than attacker-controlled labels.

Quantiles are nearest-rank p50 (n >= 2) and p95 (n >= 20), with count/min/max/spread and `insufficient_samples` if too few observations. Values are observations on their **specific JVM runner**; no machine-agnostic timing thresholds, speedup assertions or inferred physical latency. No trace IDs, raw UUIDs, URIs, identifiers, credentials or arbitrary attributes enter the measurement summary. Actual persisted exports separately exercise existing redaction and byte bounds.

Backpressure test holds one writer active, saturates its single normal queue slot and checks 50 rejected writes are counted; the producer completes without waiting for the held worker. The drain watermark waits for accepted writes, and the serialized export includes accepted lines and drop counter, not rejected lines. This validates behavior but **does not prove absolute upper-bound latency on hardware**.

## Decision on optimization

**No production optimization in O2:** A fix is prohibited without stable, repeated, paired **before/after** evidence of material overhead in a specified Diagnostics-only hotspot. The O2 analyzer and fixtures establish that measurement baseline; measured CPU time for `DiagnosticFormatter` cannot be assumed to be avoidable without separately validating logcat/privacy/capture semantics. If material overhead is observed under matched conditions, a later narrowly scoped corrective change must include before/after measurements, privacy regression tests, no player-thread disk writes and identical trace semantics. Never label synthetic cases as a speedup.

## Verification and physical notes

CI Android JVM tests exercise the real `DiagnosticWriteQueue` (bounded, async, drop counting) and `DiagnosticFileStore` (bounded two segments, three exports), plus existing sanitizer/mapper. The Android `Diagnostics.event` composition root and on-device scheduler are **not physically exercised** in this slice. Physical smoke O2: `NOT_EXERCISED`; device-level producer latency, logcat and kernel I/O variance require a specific future risk-based gate, not a blanket claim. PR #39: `radio:`/`links:` accepted physically, numeric `motion artwork on album` CI-only (not physically reproduced). O1: JVM baseline only, no prior performance improvement asserted.

## Integration handoff

1. Review PR #44 (O1) separately; only a later individual authorization permits protected merge and exact-SHA post-merge Android CI.
2. Retarget O2 PR #48 to the newly validated main, audit O2-only diff against original stacked base, synchronize with current main as needed preserving commits, run fresh Android tests/build/APK and inspect overlapping changes.
3. Request a separate authorization for O2's exact HEAD + main SHA in another execution. No implicit merge, auto-merge or bundled O1+O2 integration.
