# Gradle build cache pilot — Android required check

## Scope

One reversible change: invoke the existing `:app:testDevDebugUnitTest` task with `--build-cache` in the protected required `Android · test and build` job. Gradle task output caching is distinct from the existing `gradle/actions/setup-gradle@v4` Gradle User Home dependency cache. No tasks are skipped unless Gradle proves the task inputs/outputs reusable; no changes to APK/smoke/release. PR job serialization and the required status context remain unchanged.

## Baseline and measurement

- Baseline workflow: GitHub Android CI #682 on PR #79, run started 2026-10-19 16:53:11Z and completed 16:57:26Z (~255 seconds job), before `--build-cache`.
- Protected-main CI #695 runs same tests with the serialized-PR workflow already merged, but without `--build-cache`; record its final result and duration separately.
- Pilot: record wall-clock start/finish timestamps for Android job on this branch. Inspect job logs for `FROM-CACHE`, `UP-TO-DATE`, `NO-SOURCE`, actual test and Kotlin compilation execution, cache restore/save messages, and task graph. First run will often be cold, especially when pull-request caches cannot be written.
- **Do not claim speedup after one run.** Compare at least three relevant pilot and baseline samples with similar Gradle inputs, runner class and queue load, separating time *waiting for runner* from actual Android job runtime. Record median/p90 and failures. PR cache read-only constraints may limit reuse.
- Regression contract `tests/test_android_ci_build_cache_pilot.py` and `tests/test_android_ci_queue.py` preserve exact required test command/context and queue; full Android CI required before approval. If no clear gain or any stale/wrong result, revert the option in a new PR.

## Later experiments are separate

Consider `--parallel` and `--configuration-cache` only after measured evidence, plugin compatibility and cache confidentiality review. Never cache plaintext signing keys or integration app secrets. Integration and smoke gates are not changed.

## Acceptance

Full Android test suite GREEN on exact PR HEAD and post-merge main SHA; serial queue demonstrated separately with two PRs, no global cancellation. This PR has no merge authorization.
