# Kotlin in-process compiler strategy — P7 optional Android CI pilot

## Baseline

The protected Android CI is `./gradlew --build-cache -Pbitchord.bundleWear=false :app:testDevDebugUnitTest` on Temurin JDK 17. The repository pins Gradle 8.11.1, AGP 8.10.1, Kotlin 2.3.20 and KSP 2.3.11. KSP2 runs inside the Gradle daemon while the Kotlin compiler normally uses a separate daemon. Kotlin 2.3.20 adopted the Build Tools API for Kotlin/JVM compilation; current official Kotlin documentation states in-process compilation through BTA also supports incremental recompilation.

References:
- https://kotlinlang.org/docs/whatsnew2320.html
- https://kotlinlang.org/docs/build-tools-api.html
- https://github.com/google/ksp/blob/main/docs/ksp2.md

Observed BitChord P5 [PR #154](https://github.com/jssantogit/BitChord/pull/154), exact-HEAD full suite GREEN: Kotlin/KSP default incremental after an ABI-neutral comment edit 34s versus experiment-only disabled incremental 56s. The repo already uses incrementality by default, so this is not a new production optimization. P6 [PR #155](https://github.com/jssantogit/BitChord/pull/155) tried narrowing the Room KSP classpath to devDebug; both full-suite variants GREEN, but KSP unit-test task still EXECUTED, with post-edit 33s baseline versus 39s experimental. **Do not promote P6 as a speedup**.

## Hypothesis

A Kotlin compiler running inside Gradle's daemon may avoid startup and IPC overhead relative to the default separate Kotlin daemon. But KSP2 already uses the Gradle daemon, and `org.gradle.jvmargs=-Xmx4096m`; concentrating work into a single process could increase heap pressure, GC and OOM risk. Only exact-head full-suite CI plus repeated A/B measurements can justify adoption.

## Method

Non-required `.github/workflows/android-ci-kotlin-in-process-pilot.yml`, PR-only on an exact same-repository pilot branch, with `contents:read`, readonly Gradle cache and non-secret placeholder properties. Two Ubuntu runners with equal Gradle task target:

| Variant | Kotlin compiler policy |
| --- | --- |
| `daemon_default` | Existing default (no override) |
| `in_process` | `-Pkotlin.compiler.execution.strategy=in-process` only for optional pilot |

Both run **the same full** `:app:testDevDebugUnitTest` task once initially and after a harmless comment-only edit to the ephemeral checkout of `PrimaryArtist.kt`. An EXIT trap restores the source. No release/APK, application source commit, Gradle flags in main, artifacts, additional secrets, cache writes, skipped tests or workflow permission changes.

## Acceptance and limitations

- Required Android CI GREEN on exact pilot HEAD and **both** optional full-suite jobs GREEN; any in-process compiler/KSP incompatibility, OOM, GC stall, additional flaky test, non-reproducible output or memory exhaustion means STOP, no promotion.
- Compare both initial and post-comment warm builds, cache task counts, CPU/memory and runner waiting. Cold and warm are distinct strata; one pair is a *pilot only*. Require at least 3 comparable pairs with equal cache hits before estimating a gain.
- Tests under the existing debug target prove nothing about release R8/NowPlaying correctness; before any permanent flag, separately test `prodRelease`, desktop and benchmark if applicable, JVM memory pressure, and physical APK compatibility.
- Do not substitute a CLI override for a safe permanent project-level decision without separate owner authorization. No change is made to `.github/workflows/check.yml`, `gradle.properties`, `MAX_ITEMS=3`, protected `main`, or required CI post-merge exact-SHA gate.

Rollback is deleting/closing this optional draft pilot. No merge authorization follows from GREEN.
