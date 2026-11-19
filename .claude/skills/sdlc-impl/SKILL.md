---
name: sdlc-impl
description: SDLC phase 3 for RiriProjector. Implements an approved plan from docs/plans/ in small commits, keeping build and formatting green.
argument-hint: <issue number>
---

# Plan → Code

Issue: #$ARGUMENTS

1. Find the plan: `docs/plans/$ARGUMENTS-*.md`. If missing or not `Status: APPROVED`, stop and
   suggest `/sdlc-plan $ARGUMENTS`.
2. Make sure you are on the issue branch (`git branch --show-current`) with a clean working tree.
3. For each step of the "Implementation steps" checklist:
   a. implement the minimum needed following `CLAUDE.md` conventions
      (allocation-free hot path, resources released in `finally`, dedicated threads);
   b. add the obvious JVM tests for the pure logic you just wrote (thorough tests come in `/sdlc-test`);
   c. `./gradlew spotlessApply` then the narrowest possible check
      (`./gradlew :<module>:testDebugUnitTest` or `:<module>:assembleDebug`);
   d. tick the step in the plan file and commit with a Conventional message (`feat(sender): ...`,
      `perf(receiver): ...`) referencing `Refs #$ARGUMENTS`.
4. If a step requires leaving the plan (new module, unplanned protocol change, new library)
   **stop and ask the user**; then update the plan.
5. At the end: `./gradlew assembleDebug testDebugUnitTest spotlessCheck` must be green.
6. Summarize what was done and suggest `/sdlc-test` (and `/firetv-deploy both` if the change is
   visible on device).
