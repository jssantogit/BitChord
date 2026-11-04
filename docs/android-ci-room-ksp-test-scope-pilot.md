# Android CI — Room KSP test-scope experiment (optional)

## Why

Earlier required Android post-main build #1185 included:
- `:app:kspDevDebugKotlin` — production Room symbols.
- `:app:compileDevDebugKotlin` — Kotlin app compilation.
- `:app:kspDevDebugUnitTestKotlin` — test target KSP task, approximately 22s between task boundaries in one observed log.
- `:app:compileDevDebugUnitTestKotlin` — Kotlin unit-test compilation.

The only KSP processor currently wired by the root project is `androidx.room:room-compiler:2.8.5` via the unqualified `ksp` dependency. KSP supports variant-specific configurations for Android, including `kspDevDebug`; test codegen is separately addressable in KSP configuration. However, absence of code-search results does **not** prove tests don't need annotation processing. We must keep the entire suite and expose any hidden dependency by failing the pilot.

Official configuration reference: https://github.com/google/ksp and https://kotlinlang.org/docs/ksp-quickstart.html .

## Code scope

This PR adds **one guarded conditional** in root `build.gradle.kts` inside the existing `:app` Kotlin plugin callback. If `-Pbitchord.roomProcessorMainOnlyPilot=true` is explicitly passed, the Room processor is wired lazily only into the `kspDevDebug` dependency configuration, rather than unqualified `ksp`. Without that exact flag, the existing default unqualified `ksp` dependency is unchanged.

This is **NOT a production-ready replacement**. The opt-in flag is designed for the dev-debug/full-unit-test variant only, and it does not install Room processing for prod/release/benchmark variants. Any later promotion would require a separate, properly mapped variant matrix and a release/benchmark/instrumented test acceptance suite. Normal builds and protected main do not pass the pilot flag.

## Non-required experiment

Standalone workflow `.github/workflows/android-ci-ksp-unit-test-scope-pilot.yml`, same-repository exact pilot branch PR only, permission `contents: read`, cache read-only, no actual LASTFM secrets or artifact uploads, two matrix jobs with `fail-fast: false`:

| Mode | Room compiler configuration |
| --- | --- |
| `global_room` | Exact existing `ksp` |
| `dev_main_only_room` | Opt-in `kspDevDebug` only |

In **both** modes, run the complete `:app:testDevDebugUnitTest` target with `--build-cache`. Then append an ABI-neutral comment only in the disposable runner's copy of `CanonicalCatalogDao.kt`, run the full suite again and restore via shell EXIT trap. Record elapsed times and the safe state category for `:app:kspDevDebugUnitTestKotlin` (`executed`, `from_cache`, `up_to_date`, `skipped`, `no_source`, `absent` or `ambiguous`). No task is excluded, disabled or skipped manually. If Room-generated entities or KSP outputs are actually required by tests, the candidate must fail, not suppress tests.

## Acceptance

- Existing required Android CI GREEN on exact HEAD, without pilot property.
- Both optional full-suite matrix jobs GREEN. The dev-mode job must still generate production Room DAO/database implementations and run the same exact 239+ existing unit tests. No reduced test selection.
- Compare task statuses and timings, considering job cache-hit differences; a single cold or warm pair does not prove general performance gains. Repeat at least three matched pairs if the difference looks meaningful, and follow through with real post-main builds only after owner-approved main promotion.
- Any dependency or variant correctness problem means HOLD and diagnosis, never `-x kspDevDebugUnitTestKotlin` or `--continue` as a shortcut.
- Separate authorized production-ready mapping would have to cover `devDebug`, `devRelease`, `devBenchmark`, `prodDebug`, `prodRelease`, `prodBenchmark`, and all Android test variants that actually require processing. Don't silently remove existing code generation on other variants.

No merge without separate owner authorization. Retaining this pilot on protected main is not necessary; it is safely removable. This PR does not modify the required Android workflow, branch rulesets, integration queue, main post-merge gate, `MAX_ITEMS=3`, or application logic.
