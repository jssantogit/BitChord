# Kotlin/KSP Incremental Compilation — P4 isolated performance experiment

## Evidence and why this is not a global Gradle flag change

BitChord's protected Android CI is `Android · test and build`, full `./gradlew --build-cache -Pbitchord.bundleWear=false :app:testDevDebugUnitTest`. During earlier P0, Kotlin app compiler cost ~76.36s and shared Kotlin ~41.51s in an optional cold-profile run, with KSP main ~17.45s and Android tests ~26.54s. A required CI run of the same PR retrieved far more tasks from cache (39 vs 8) and finished in 53s vs 4m22s. That difference reflects **different cache state/families**, not an optimizer speedup.

Project baseline: Gradle 8.11.1, AGP 8.10.1, Kotlin 2.3.20, KSP 2.3.11, JDK 17, Room compiler 2.8.5. Root build applies KSP only to `:app`; `:app` depends on `:shared` and `:sharedUi`, and `:sharedUi` also depends on `:shared`. The mandatory full-test Android workflow remains unchanged.

**Kotlin/JVM incremental compilation and KSP incremental processing are both enabled by default** according to official Kotlin Gradle and KSP docs. Thus setting `kotlin.incremental=true` or `ksp.incremental=true` globally is not a new optimization. This pilot instead asks whether the defaults actually save rebuild work after a minimal Kotlin source change in *this project*, with Room/KSP and the full Android suite present.

References:
- https://kotlinlang.org/docs/gradle-compilation-and-caches.html
- https://github.com/google/ksp (KSP Gradle property defaults)

## Experimental design

Standalone, non-required `.github/workflows/android-ci-kotlin-ksp-incremental-pilot.yml`, triggered only by a same-repository PR on the exact experiment branch and changes to its own workflow or Python contract test. It invokes two independent Ubuntu/JDK17 matrix jobs (`fail-fast: false`), both with `gradle/actions/setup-gradle@v4 cache-read-only: true` and non-secret `LASTFM` placeholders. They execute the exact full Android unit-test target on cold/preparation and again after editing **only** the local checkout of:

`app/src/main/java/com/music/bitchord/data/scrobbling/PrimaryArtist.kt`

The edit appends a harmless Kotlin comment. Runtime logic, ABI, Room schema, generated code, release assets, security/credentials and Git history are never modified. The temporary runner patch is restored in `trap` even if a test fails. No APK or build-profile artifact is uploaded.

| Variant | Flags used in **both** builds |
| --- | --- |
| `default_incremental` | Existing Gradle defaults: Kotlin incremental ON and KSP incremental ON |
| `incremental_disabled` | `-Pkotlin.incremental=false -Pksp.incremental=false` (experiment only) |

The `incremental_disabled` invocation is **not** proposed for required CI; it is a controlled negative case to attribute the benefit of the existing defaults. It creates separate build caches only in its transient runner; `cache-read-only` prevents shared cache pollution.

## Interpretation and limits

Each variant records initial full-suite and post-comment-change Gradle wall time in GitHub's job summary. Compare only post-change times **while checking exact commit, equal Gradle task coverage, cache hit counts, runner waiting/CPU/memory and Kotlin/KSP task outcomes**. The initial build is dominated by cold compilation and may differ by runner/cache state; do not conflate it with incremental-source behavior. A comment-only input change is ABI-neutral and does not simulate API/Room schema edits.

- Both variants must complete the same full Android unit tests GREEN. No bypass, build-cache disabling, `--rerun-tasks` or global `gradle.properties` changes.
- One matrix pair only supports a *compatibility/feasibility observation*; at least three comparable paired changes including representative Kotlin and (in a separate safe pilot) Room/KSP cases are required before claiming stable improvement.
- Source changes in a developer PR occur on **fresh GitHub Actions runners**. A local warmed second compilation models reuse within a single workspace, **not** actual cross-run incremental output persistence. Production speedups need stable task output-cache reuse between jobs, with correct cache keys and security review, not merely proving local incremental compiler behavior.
- Kotlin build reports may include filenames and environment information; do **not** upload raw Kotlin reports, Gradle scans, source diffs, or arbitrary compiler logs as artifacts.
- If results are close (or worse with defaults), conclude that this experiment does not justify a global flag; audit modules, compiler input invalidations and cache policy before optimizing.

### Safety and rollback

No required check, `gradle.properties`, main branch rulesets, application source files, release package, integration queue, MAX_ITEMS=3, or post-main exact-SHA GREEN gate is changed. This experiment's standalone branch-specific workflow is temporary and should stay DRAFT unless the owner explicitly wants to retain the diagnostic; no promotion/merge is implicit. Rollback is closing/deleting the optional workflow PR, without changing production CI.
