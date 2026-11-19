---
name: sdlc-review
description: SDLC phase 5 for RiriProjector. Reviews the branch with the media-net-reviewer agent (and android-architect if the architecture changes), applies fixes and repeats until no blockers remain.
---

# Review

1. Make sure build and tests are green: `./gradlew assembleDebug testDebugUnitTest spotlessCheck lintDebug`.
2. Invoke the **media-net-reviewer** agent on the `origin/main...HEAD` diff.
   If the diff changes modules, public APIs or the protocol, also invoke **android-architect** in
   parallel, asking it to compare the implementation with the plan.
3. For each finding:
   - `blocker`/`major` → fix, commit `fix(<area>): address review — <summary>`;
   - `minor` → fix if cheap, otherwise note it in the PR as a follow-up;
   - if you believe a finding is wrong, explain why to the user instead of ignoring it.
4. After the fixes rerun the reviewer **once**, on the new commits only.
5. Show the user a final table: finding, decision, commit. Suggest `/sdlc-pr`.
