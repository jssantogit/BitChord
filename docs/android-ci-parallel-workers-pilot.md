# Android CI bounded parallel-workers pilot

## Context

The required Android check on protected main remains `Android · test and build` and runs the full `:app:testDevDebugUnitTest` suite. The serial integration controller requires its GREEN on the exact post-merge main SHA before any subsequent merge; this pilot never changes that contract.

P0 [PR #148](https://github.com/jssantogit/BitChord/pull/148) established a usable Gradle task profile. A cold-cache optional run spent 4m22.48s in Gradle, including `:app:compileDevDebugKotlin` 76.36s, `:shared:compileDebugKotlinAndroid` 41.51s and configuration 13.019s. The required Android CI for the same HEAD ran in 53s with a different cache family and 39 hits instead of 8. Comparing those timings as direct profiler overhead or performance gain would be incorrect.

P1 [PR #149](https://github.com/jssantogit/BitChord/pull/149) proved strict Configuration Cache reuse **within** a single job after the SDK/NDK preflight (pilot run #39694804803 GREEN); no reusable cross-job cache has been enabled. P2 [PR #150](https://github.com/jssantogit/BitChord/pull/150) provides read-only cache-write evidence and already passed Python/Android CI.

## Pilot P3: controlled --parallel comparison

Two independent non-required GitHub Actions jobs run on the same optional pilot PR and the **same exact HEAD**. Both use Temurin JDK 17, Ubuntu latest, Gradle wrapper, the *same full Android unit-test target*, a hard Gradle worker ceiling of 2, non-secret LASTFM placeholder values and read-only `setup-gradle@v4` cache. The only intended command difference:

| Variant | Flags |
| --- | --- |
| sequential | `--build-cache --max-workers=2` |
| parallel_two | `--build-cache --parallel --max-workers=2` |

The matrix uses `fail-fast: false`, so a RED in either variant cannot conceal the other result. There are no APKs, artifacts, new secrets, config-cache state, shared-cache writes, permissions elevation, schedule or manual integration trigger. The protected `.github/workflows/check.yml` remains entirely unchanged, as do `gradle.properties` and the integration queue.

### Measurement controls and caveats

- Record both job URLs, SHA, Github created/started/completed timestamps, setup duration, Gradle wall time, executed/from-cache/up-to-date task counts, KSP/Kotlin/test durations where logs permit, and failures.
- A separate GitHub Actions job is **not a perfectly matched runtime environment**. Concurrent runner contention, different restored cache families and cache state can dominate measured differences. The pilot should be repeated over at least 3 run pairs; successful single run is *compatibility* only, not performance proof.
- The bounded worker setting is deliberate: Android unit-test Gradle builds, Kotlin compilation and KSP are memory-heavy. If workers trigger OOM, deadlocks, flaky tests, different outcomes, or materially lower cache hits, do not promote.
- Actual pipeline benefit must include queue delay, setup cache restore/save, test time, and downstream protected-main CI. No reclassification/skip of tests or changes to required check context to improve numbers.
- The pilot jobs consume runners temporarily and can contend with active Catalog/Listening/Playback/Performance PR builds. Do not promote or multiply optional experiments without an observed benefit.

### Acceptance

Both variants complete the exact full target GREEN; Python contract tests and independent required Android CI GREEN on exact HEAD; relative wall-time improvement sustained in comparable cache-hit strata without test/oom regressions. Any promotion to the required check, increasing queue batch size or editing branch rulesets requires a **separate owner-authorized PR and protected merge**. No gain is claimed at PR opening.

Rollback: close/delete optional pilot PR; protected checks and cache policies do not change.
