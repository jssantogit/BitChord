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

1. `tools/android_ci_ksp_dirty_probe.py`: strict local-only parser. It expects the **entire** required Android unit-test Gradle command to succeed and finds only four documented KSP log basenames in each of the explicit `app/build/kspCaches/devDebug/logs` and `app/build/kspCaches/devDebugUnitTest/logs` locations. The output includes fixed categories and **numbers of nonblank log records**, never filenames or source text. Missing log files are `unobserved` or `partial`, not fabricated. Log text may contain multiple records per source, so a line count is not a source-file count. A symlink, oversized/unreadable/ambiguous input or failed task aborts with a fixed generic error.
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
