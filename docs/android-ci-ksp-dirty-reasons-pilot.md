# P9 — KSP2 dirty-set evidence without publishing source identities

## Why this experiment

PR [#157](https://github.com/jssantogit/BitChord/pull/157) delivered a GREEN six-case full Android unit-suite matrix (HEAD `7b0af67f1754558011827106247be2010537e093`). A comment-only change in `:shared` recompiled shared Kotlin but left dependent Android Kotlin/KSP tests up-to-date (13s / 3 tasks). The same harmless comment in `:sharedUi` reexecuted app KSP main and unit-test processors and the Android unit-test task, while the app Kotlin compiles were up-to-date (29s / 9 tasks). An added unused public declaration in sharedUi additionally reexecuted Kotlin app main and unit compiles (46s / 11 tasks); a public addition in shared propagated even farther (43s / 13 tasks).

These are real **task outcomes**, not evidence that KSP processed excess source files, a KSP regression, or that a cache key was wrong. We need a privacy-safe view of the KSP dirty-set signals before changing interfaces or Gradle flags.

## Grounded KSP behavior

Official Kotlin/KSP incremental documentation says `ksp.incremental=true` by default and `-Pksp.incremental.log=true` enables additional diagnostics. KSP records associations between source files and generated outputs; resolution traces and aggregating processors can propagate dirty files. Documented paths include `build/kspCaches/<source set>/logs/kspDirtySet.log`, `kspDirtySetByDeps.log`, `kspDirtySetByOutputs.log` and `kspSourceToOutputs.log`. These files can expose absolute paths, Kotlin file names, package/symbol identities, project structure and possibly user-local environment state. **Never upload or paste their raw contents**.

References:
- https://kotlinlang.org/docs/ksp-incremental.html (official, July 2026)
- https://github.com/google/ksp (Gradle property reference and source compatibility)

KSP's historical `ksp.incremental.intermodule=true` is an **experimental** flag in older documentation; this pilot neither enables it nor asserts its current support with KSP 2.3.11. There is no default branch configuration change.

## What P9 adds

Three layers, all in a standalone draft branch:

1. `tools/android_ci_ksp_dirty_probe.py`: strict local-only parser. It expects the **entire** required Android unit-test Gradle command to succeed and finds only four documented KSP log basenames in each of the explicit `app/build/kspCaches/devDebug/logs` and `app/build/kspCaches/devDebugUnitTest/logs` locations. The output includes fixed categories, **numbers of nonblank log records** and a separate allowlisted **per-log presence boolean**, never source filenames or contents. A missing record and a present-but-empty record both count as zero lines but have different presence bits; neither constitutes proof that a KSP task skipped processing. Missing log files are `unobserved` or `partial`, not fabricated. Log text may contain multiple records per source, so a line count is not a source-file count. A symlink, oversized/unreadable/ambiguous input or failed task aborts with a fixed generic error.
2. `tests/test_ci_ksp_dirty_reasons_pilot.py`: TDD tests for positive, absent and partial evidence; path/symbol/secret suppression; task status validation; symlink and size limits; fixed workflow permissions and exact full-test target.
3. `.github/workflows/android-ci-ksp-dirty-reasons-pilot.yml`: optional, same-repository PR on exact pilot branch only, `contents:read`, JDK17, read-only Gradle cache, **two** independent matrix cases (`sharedUi` comment-only vs unchanged `control`). Each runs `:app:testDevDebugUnitTest` twice with `-Pksp.incremental.log=true`, sending all Gradle output to `$RUNNER_TEMP` and parsing KSP logs locally. All raw outputs stay in the ephemeral runner and are deleted by its lifecycle; none is uploaded to GitHub as an artifact. Only allowlisted JSON summary goes to `GITHUB_STEP_SUMMARY`. Temporary source comment is restored via EXIT trap. All tests remain intact; failures fail closed with no raw log leak.

### Acceptance and limits

- Required Android CI must pass on exact HEAD **without** any added KSP logging flag; both optional full-suite matrix jobs must pass independently.
- Validate availability of KSP log categories, Gradle KSP main/test task outcome and before-vs-after record counts. The summary explicitly prints `ksp_causal_reason_proven:false`; log presence alone is not input provenance and cannot prove over-invalidation.
- An `unobserved` log signal may be due to KSP2 changing paths or not emitting a particular file. Do not convert absence into claims of zero dirtiness. Correct the parser only with evidence, preserving the fail-closed privacy contract.
- Do not use `--info`, `--debug`, build scans, raw log uploads, class/symbol names, or a new reusable cache writer. The important follow-on question is whether dirty-set attribution distinguishes classpath change, aggregating output or source-dirty roots **without leaking specific identifiers**. This pilot's counts alone cannot answer that.
- This is one pair of within-run warm compiler builds on different hosts; it cannot predict a post-merge `main` speedup. Additional representative public API and Room schema cases and 3+ matched sequences would be necessary before considering compiler/module changes.

## Protected invariants

Never modify required `.github/workflows/check.yml`, `gradle.properties`, Kotlin/KSP plugin versions, Room annotation code, app source code in Git, cache trust/write policy, `main`, integration queue MAX_ITEMS=3 or required post-main exact-SHA Android testing. This is a **draft-only measurement** branch; no merge is authorized by GREEN. Rollback is closing the optional pilot once its evidence is documented.


## Evidence from the first P9 matrix on 2026-10-22

On HEAD `ea97bee87aba4acf6a267da5ecca4ecd5cbadb89`, both the exact-HEAD required Android CI #39785404332 and optional two-job matrix #39785406877 finished GREEN. Job IDs control 119847562188 and sharedUi 119847562852. Observed **sanitized** counters from the completed full-suite matrix:

| Scenario | Gradle initial → second | devDebug dirty-set lines initial → second | devDebug source/output record lines initial → second | app KSP main in second | app KSP unit in second | app Kotlin main in second |
| --- | --- | --- | --- | --- | --- | --- |
| control, no edit | 269s → 3s | 806 → 806 | 31 → 31 | UP-TO-DATE | UP-TO-DATE | UP-TO-DATE |
| sharedUi, ABI-neutral comment | 255s → 42s | 806 → 409 | 31 → 14 | EXECUTED | EXECUTED | UP-TO-DATE |

The original parser reported `partial` diagnostic availability. Every `devDebugUnitTest` log type had **zero counted lines**, but a zero alone did not distinguish a missing file from an empty one. The new per-log presence flags address that ambiguity. The exact-HEAD Android and optional matrix must be GREEN **again** after this parser enhancement before the new HEAD is marked validated.

Interpretation: the control retained historical KSP logs across an up-to-date run, so log presence is not proof of fresh processing. Changed line counts in sharedUi indicate distinct diagnostic files, **not** that precisely 409 files were dirty or that source/output mapping count equals a number of sources. We still cannot attribute the exact KSP invalidation to classpath snapshot, aggregating Room output or transitive symbol dependencies without additional bounded evidence. Do not claim an end-to-end merge speedup.


## P10 — Gradle input-change category evidence (opt-in extension)

The same draft now captures task up-to-date diagnostics from the **second** full Android suite using `--info` only in the optional pilot. The **complete verbose Gradle output remains in a temporary file on the runner**, never streamed to the GitHub job log or uploaded. The Python tool parses the Gradle task diagnosis for exactly two KSP tasks, `:app:kspDevDebugKotlin` and `:app:kspDevDebugUnitTestKotlin`, and exports only the following allowlisted categories when their corresponding task-scoped Gradle diagnostic is present:

- `classpath_changed`: a Gradle input property whose name denotes a classpath
- `sources_changed`: source input properties
- `config_changed`: compiler/processor option or argument properties
- `outputs_changed`: Gradle output property evidence
- `history_missing`: explicit missing prior task execution history

For each KSP task it separately reports `reason_availability` of `observed`, `observed_unclassified`, `unobserved` or `not_applicable`. `unobserved` is the correct result if this Gradle/KSP version does not emit a compatible `--info` task-reason message. **Do not infer that a missing category means an input was unchanged.** Exact inputs, paths, property names beyond a classification test, source names, symbols and Gradle verbose lines are never included. Failures cause a generic sanitized error.

The output explicitly sets `root_cause_proven: false`. Even a Gradle `classpath_changed` reason is *task input invalidation evidence*, not proof of which KSP symbol, Room processor, ABI declaration or file caused a reprocessing wave. Prior P9 dirty-set counters remain bounded and independently reported; the P10 report is an additional safe classification, not a speedup claim.

Only the optional pilot's second invocation uses `--info`; the original required Android CI workflow and production Gradle flags remain untouched. A new exact-HEAD required Android GREEN and both optional full-suite matrix checks GREEN are required after this extension, followed by a side-by-side review of the categories and no-change control. An unsupported format must stay `unobserved`; no raw diagnostic should be copied to a PR, Notion or chat to force a conclusion.
