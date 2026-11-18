# REA #212 — safe motion subset after AM-DECOMP audit

Reference: [am-descomp motion handoff](https://github.com/jssantogit/am-descomp/blob/fase-3-investigacao-visual/docs/rea212-motion-gap-handoff.md), branch `fase-3-investigacao-visual`, SHA `9c13c97d085961f98329a5aac0e61c89d44d08c0`.

This change deliberately implements only motion safeguards with known BitChord callsites, **not** inferred Apple timing.

| Scope | Current cut | Provenance and limits |
|---|---|---|
| Root tabs | Already in #212: content fade in/out, linear 150 ms; Search/wizard atomic replacement where an outgoing page ghosted | AM-DECOMP traced `@animator/nav_default_*` in a conditional root-tab transaction. The separate indicator tint remains BitChord's. No further change here. |
| Bottom queue capsule | One outgoing actionable capsule retires immediately; the incoming capsule uses the existing 180-ms fade, 220-ms resize and original slide | Avoids content double-exposure while retaining functions, geometry and accessibility; these are **BitChord** durations, not Apple's queue controls/gradient animator. |
| Reduced motion | Queue pill, panel settling/reveal and player drawers skip optional animation when Reduce animations is enabled. Direct drag handlers, dismiss actions, screen state and selection remain functional. | AM-DECOMP handoff demands accessible alternatives; does not prove Apple's settings behavior. |
| ⋮ player | Earlier fix kept the plate opaque while its 170-ms scale runs; unchanged | AM-DECOMP did not verify the menu's runtime animation or binding to generic bottom-sheet XMLs. |

Deferred pending surface-specific evidence: continuous H/L/M background rotations (performance regression risk), player OverScroller physics, conditional 300/500-ms shared-element targets, queued alpha-gradient animator, SearchLanding-specific smooth scroll and playlist inline-search branches (not proven equivalent to BitChord's query reset or wizard). No APK or physical smoke performed by this code change.
