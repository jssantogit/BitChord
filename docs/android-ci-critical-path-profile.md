# Android CI critical-path pilot (non-required, read-only)

## Why

Controlled Integration Queue keeps the full required Android check after each protected merge. Six completed batches (#40, #42, #44, #46, #48, #51) integrated 18 PRs, but their post-main Android jobs dominated wallclock. Previously observed baseline: about 4m45s per required Android job, roughly 37s in the Gradle setup post-step; individual Kotlin/KSP compilation tasks recur. These are **historical measurements**, not proof that any optimization is faster.

This pilot **does not edit** the required `.github/workflows/check.yml`, Gradle settings, protected rulesets, integration-queue controller, cache-writing policy of main, or required tests. It is diagnostic-only.

## Scope and safety

The optional workflow `.github/workflows/android-ci-critical-path-profile.yml` runs **only** for same-repository pull requests whose branch starts with `infra/android-ci-` **and** whose PR changes its own workflow or pilot contract test. It uses read-only repository permissions and `gradle/actions/setup-gradle@v4` with `cache-read-only: true`, so test runs cannot update the shared GitHub Actions Gradle cache. The required `Android · test and build` still runs independently on the same SHA.

The pilot invokes precisely the required Gradle unit test target with `--build-cache --profile`, without changing application behavior, compilation inputs, or test selection. Gradle creates a task timing HTML report at `build/reports/profile/*.html`; the workflow uploads it under a named artifact with three-day retention. Do not upload Gradle caches, secrets, `local.properties`, build scans, or worker logs.

## Measurement protocol

1. Record exact PR HEAD, Gradle wrapper, JDK, AGP/Kotlin/KSP versions, workflow run ID and status, runner queue time, required Android job time, Gradle test-step time, and Gradle post-step time. Separate workflow queue and environment waits from Gradle execution.
2. Read task timings from the profile artifact: `:app:kspDevDebugKotlin`, `:app:compileDevDebugKotlin`, `:app:kspDevDebugUnitTestKotlin`, `:app:compileDevDebugUnitTestKotlin`, `:app:testDevDebugUnitTest`, and total configuration time. Count tasks executed/from-cache/up-to-date. Report tasks on the critical path instead of blindly summing concurrent durations.
3. Use three or more comparable PR HEADs and three or more comparable main-push Android runs before making an optimization claim. Explicitly record whether caches were readable and the effective queue lane; do not claim speedup on a single noisy PR.
4. Follow-up isolated pilots may try `--configuration-cache`, cache-write policy, and Gradle parallel workers **one at a time**. Test both cold and repeat invocation for configuration-cache reuse; do not change `gradle.properties` globally until plugin compatibility (AGP 8.10.1, Kotlin 2.3.20, KSP 2.3.11, Gradle 8.11.1) and CI evidence pass.
5. Promote a candidate to required CI only after exact-HEAD GREEN, repeatable net wallclock improvement (including post-steps), no reduced test coverage, cache pollution, secret exposure, runner contention or status-context drift, and separate owner approval for protected integration.

## Regression gate

`python3 -m unittest discover -s tests -p test_android_ci_profile_pilot.py -v` checks the pilot remains scoped, read-only and identical in test target to the required check. This is a **contract test**, not proof that Gradle profiling works; the real optional Android workflow and its uploaded task report provide that verification.

## Rollback

The pilot is a standalone workflow. Removing it leaves required CI, protected serial merges and caches unchanged. Do not increase `MAX_ITEMS=3` or relax per-merge post-main required GREEN as part of this experiment.
