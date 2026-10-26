# Android Kotlin/KSP module-invalidation probe — optional and read-only

## Observed background

Protected main has the required check `Android · test and build`, running the full `:app:testDevDebugUnitTest` suite with `--build-cache`. Three earlier required post-main jobs (#1129, #1162, #1185) each restored the `android` family Gradle dependency cache and reported 73 actionable tasks, 40 executed and 33 retrieved from cache. Kotlin main, KSP main and Kotlin/KSP unit-test compilations nevertheless **executed** in those runs. In the same branch but a small infrastructure PR, Kotlin/KSP were **FROM-CACHE** with 39 cache hits. This establishes *what* was reexecuted, not *which compiler inputs* caused the invalidation.

Static repo census on main: `:app` 637 Kotlin/Java files (392 recognized main, 241 tests), `:shared` 121, `:sharedUi` 72. The Android app has module dependencies `:app -> :sharedUi -> :shared` and `:app -> :shared`. Static size alone does not prove what to modularize. Catalog/Listening/Playback branches are actively editing code, so no cross-owned refactor is authorized here.

## Pilot experiment

The optional GitHub Actions workflow `.github/workflows/android-ci-module-invalidation-pilot.yml` runs only on this PR branch from the same repository. It has `contents:read`, readonly `setup-gradle@v4` shared cache, no raw artifact uploads, no real external credentials, no global compiler switches and no protected-main workflow edits. Six independent matrix jobs are bounded to at most **2 concurrent jobs** (`fail-fast:false`), all JDK17 and the **entire** original Android unit-test Gradle target.

Each job first executes a full suite to prepare a **local same-runner** workspace. Then, for its case, it applies one temporary Kotlin source edit in that runner's checkout and runs the exact same full suite again. The original three module cases append comments only; the two new `*Abi` cases append a uniquely named **unused public top-level function**, altering the exported library API without changing any existing function signature. A shell EXIT trap restores the source regardless of success or failure:

| Case | Temporary source input |
| --- | --- |
| `app` | `app/src/main/java/com/music/bitchord/data/scrobbling/PrimaryArtist.kt` |
| `shared` | `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/MonotonicClock.kt` |
| `sharedUi` | `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/LyricFocus.kt` |
| `sharedAbi` | Add one unused public top-level function in `MonotonicClock.kt`; additive ABI test |
| `sharedUiAbi` | Add one unused public top-level function in `LyricFocus.kt`; additive ABI test |
| `control` | No edit, identical full-suite invocation |

The `tools/android_ci_invalidation_probe.py` parser reads the *local* second-invocation log only and emits the fixed names and outcome categories for seven Android/Kotlin/KSP tasks: shared Kotlin, sharedUi Kotlin, app KSP main, app Kotlin main, app KSP tests, app Kotlin tests and the Android unit-test task. Task states may be `executed`, `from_cache`, `up_to_date`, `no_source` or `skipped`. Unrecognized statuses, failed builds, duplicate/missing tasks, unexpected input mode or incomplete Gradle results **fail closed**. Source paths, raw logs, cache keys, usernames, values from source changes and environment variables are never output by the parser. Results go only into each job's brief GitHub step summary.

## How to interpret

- This is an **ABI-neutral comment edit**, not a realistic public type/API/Room schema change. It probes conservative recompilation reach under stable source/API; it cannot establish all affected tasks from an ABI-breaking change.
- All four jobs are independent hosted runners with potentially different restored cache states. Compare **second build** task-state vectors against the no-change control, not absolute cold wall time or job queue delays.
- `UP-TO-DATE` of a downstream Kotlin task may be correct if its ABI-relevant input did not change; it is not evidence that downstream code is untested.
- `FROM-CACHE` means Gradle validated task inputs against a cached output, not proof of producer identity or that an untrusted PR artifact may be promoted to main.
- The full suite remains mandatory. If one mode RED, diagnose the failure and retain RED rather than skipping tests or force-disable KSP tasks.
- A single full-suite matrix provides an *invalidation taxonomy*, not a production speedup estimate. Any module-split proposal requires representative implementation/API/Room changes, 3+ comparable paired sets, release/Desktop dependency verification and explicit project-owner approval. Avoid moving code while Catalog, Listening and Playback own the same surfaces.

## Safety / rollback

Do not merge this exact-branch optional workflow simply because it turns GREEN: its guard is for this experimental branch. No new secret, cache writer, configuration-cache promotion, APK, build scan, changed application Kotlin source in Git, protected main gate, integration queue `MAX_ITEMS=3`, or branch ruleset. Drop the draft experiment once findings are documented. To retain long-term observability, a separate production-safe proposal must be scoped and approved.


## Actual P8 result — comment-only cases (first HEAD)

On `916f282f154d6c0391f8d423c8dcc1513584479d`, required Android #39776215652 and all four optional matrix jobs in #39776215834 finished GREEN. Each initial run executed the same full task graph (73 actionable, 65 executed, 8 from-cache). Second-run source edit outcomes:

| Case | Second run | All-task outcome | shared Kotlin | sharedUi Kotlin | app KSP main | app Kotlin main | app test KSP | app test Kotlin | app test task |
| --- | ---: | --- | --- | --- | --- | --- | --- | --- | --- |
| control | 3s | 2 exec / 71 up-to-date | U | U | U | U | U | U | U |
| shared comment | 13s | 3 exec / 70 up-to-date | E | U | U | U | U | U | U |
| sharedUi comment | 44s | 9 exec / 64 up-to-date | U | E | E | U | E | U | E |
| app comment | 33s | 9 exec / 64 up-to-date | U | U | E | E | E | U | E |

E=EXECUTED, U=UP-TO-DATE. From real GitHub Actions job logs: shared 119815707627, sharedUi 119815708179, control 119815708216, app 119815708308. In all four repeat runs, the two KMP partially resolved dependencies checkers executed, *including the no-change control*. A **comment-only** shared source change stayed isolated to shared Kotlin; sharedUi's edit also reexecuted app KSP main/unit and Android unit tests, while app Kotlin main remained up-to-date. These are observed Gradle states, not proof of a defective KSP or an avoidable task. Absolute times are single independent hosted-runner measurements, not production build throughput estimates.

## Additive public ABI extension (P8b)

The same draft has been extended to also test `sharedAbi` and `sharedUiAbi` on independent runners, still `max-parallel:2`. Each case locally appends a uniquely named, unused **public** function with no dependencies and no existing code changes. This intentionally changes exported ABI but should leave normal execution semantics intact. It tests whether downstream Kotlin and KSP tasks invalidate for a public addition. It does **not** test removal, signature changes, complex dependency updates, Room schema or cross-platform release builds. The source remains uncommitted and is restored on job EXIT.

**Acceptance:** exact-HEAD required Android CI and all six optional jobs GREEN; task status vector compared with comment-only controls; no inference of production speedup, no cross-owned refactor. A public ABI variant that fails compilation must be fixed or marked inconclusive, not bypassed via `-x` or lowering coverage. Do not merge the branch-specific experimental workflow into protected main.
