---
name: sdlc-pr
description: SDLC phase 6 for RiriProjector. Pushes the branch and opens the Pull Request with the filled-in template, linked to the issue. Invoke explicitly; asks for confirmation before pushing and creating the PR.
disable-model-invocation: true
---

# Pull Request

1. Preliminary checks (stop if one fails):
   - branch ≠ `main`; clean working tree;
   - `./gradlew assembleDebug testDebugUnitTest spotlessCheck lintDebug` green;
   - plan in `docs/plans/<n>-*.md` with all steps ticked;
   - `/sdlc-review` run with no open blockers (ask the user if it is not in the conversation).
2. Fill in the body following `.github/PULL_REQUEST_TEMPLATE.md`:
   summary, `Closes #<n>`, changes per module, protocol impact, tests run (commands + outcome),
   before/after measurements if the hot path is touched, "On-device tests" checklist
   (leave unverified items unticked and say so), follow-ups.
   End the body with the attribution line required by the session.
3. Show the title (Conventional, e.g. `feat(receiver): low-latency HEVC decoder`) and body to the
   user and **ask for confirmation**.
4. After the yes: `git push -u origin HEAD` and `gh pr create --base main --title ... --body-file <file>`
   with the issue's labels.
5. Report the PR link. CI status is followed from the PR, without polling.
