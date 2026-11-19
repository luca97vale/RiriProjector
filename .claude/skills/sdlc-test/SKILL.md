---
name: sdlc-test
description: SDLC phase 4 for RiriProjector. Delegates writing tests for the current diff to the test-writer agent, runs the suite and reports coverage and bugs found.
argument-hint: "[module or area to focus on]"
---

# Tests

Optional focus: $ARGUMENTS

1. Identify issue and plan from the branch name (`feat/<n>-...` → `docs/plans/<n>-*.md`).
2. Invoke the **test-writer** agent with: issue number, plan path, list of touched modules
   (`git diff --stat origin/main...HEAD`), optional focus "$ARGUMENTS".
3. When it finishes, verify yourself: `./gradlew testDebugUnitTest spotlessCheck`.
   If a device is attached and the diff touches `core:media`/`core:net`, propose
   `./gradlew connectedDebugAndroidTest`.
4. If the agent reported production bugs, fix them (commit `fix: ...`) and rerun the tests.
5. Commit the tests: `test(<area>): ...` with `Refs #<n>`.
6. Report: tests added, outcome, bugs fixed, missing coverage. Suggest `/sdlc-review`.
