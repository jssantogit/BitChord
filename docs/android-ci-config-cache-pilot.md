# Android Configuration Cache — isolated compatibility gate (P1)

## Motivation and measured P0 baseline

[PR #148](https://github.com/jssantogit/BitChord/pull/148) produced a successful Android Gradle task-profile artifact from the same HEAD as required CI. It is **not** a matched-speed A/B test:

| Measurement | Optional profile run | Required Android CI |
| --- | ---: | ---: |
| Gradle build time | 4m22.48s | 53s |
| Task results | 65 executed / 8 from cache | 34 executed / 39 from cache |
| Configuration | 13.019s | Not separately profiled |
| Task execution | 3m54.15s | Not separately profiled |

Profiled compile tasks: :app:compileDevDebugKotlin 76.36s, :shared:compileDebugKotlinAndroid 41.51s, :app:testDevDebugUnitTest 26.54s, :app:kspDevDebugKotlin 17.45s, :app:compileDevDebugUnitTestKotlin 16.86s. These are **individual** timings, not additive critical-path proof. The cache restored by the optional workflow differs from the cache restored by the required workflow (including separate workflow/job key families), so the time delta cannot be attributed to profiling instrumentation.

## P1 experiment and limits

The standalone optional workflow `.github/workflows/android-ci-config-cache-pilot.yml` runs only on PRs from this repository with exact branch `infra/android-ci-configcache-compat-v0` affecting the pilot files. It does not modify `.github/workflows/check.yml`, protected branch status contexts, Android tests, cache-write behavior, Gradle version, `gradle.properties`, integration queue, release builds, or APKs. Permissions: contents read only. It runs the existing full test target twice with `--configuration-cache` in the **same ephemeral job** and fails unless the second invocation confirms actual cache reuse; no warning mode. It uses placeholder LASTFM values and does not upload Gradle cache entries, files or logs as artifacts. `gradle/actions/setup-gradle@v4` is read-only. Both invocations may additionally benefit from local output cache and incremental results; this is a compatibility/reuse test, **not** a cross-job speedup estimate.

Configuration Cache serializes task configuration and may include task-reachable sensitive values. GitHub Actions persistence requires a controlled encryption key in the official Gradle action. There is intentionally no encryption key or persistent CC artifact in this pilot. Do not promote to required CI or add the secret without a separate owner-authorized security and performance review.

## Pass / fail

- Strict cold test runs complete full :app:testDevDebugUnitTest suite, configuration-cache entry can be stored and reused with no problems.
- Warm invocation explicitly reports configuration cache reuse and succeeds.
- Exact HEAD passes independent existing Android required CI and contract tests.
- Compare cold/warm *within this job* while tracking changing build-output cache hits. Report failure causes honestly (including unsupported Gradle/Kotlin/KSP/AGP task behavior) and do not fix by disabling tests or ignoring CC problems.
- No claimed CI improvement unless separately measured against comparable main-push Android runs with a secure persistent cache and matched workloads.

Rollback: delete the optional workflow/tests/docs PR; protected CI and main stay unchanged.
