---
name: sdlc-plan
description: SDLC phase 2 for RiriProjector. Given an issue number, writes the implementation plan in docs/plans/, has it reviewed by the android-architect agent and submits it to the user for approval.
argument-hint: <issue number>
---

# Issue → Plan

Issue: #$ARGUMENTS

1. `gh issue view $ARGUMENTS --comments` and read `CLAUDE.md`, `docs/ARCHITECTURE.md`, `docs/PLAN.md`.
2. Explore the relevant code. For broad exploration use an `Explore` agent.
3. Create the branch if missing: `git switch -c feat/$ARGUMENTS-<slug>` from an up-to-date `main`
   (`fix/` for bugs, `chore/` for maintenance).
4. Write `docs/plans/$ARGUMENTS-<slug>.md` with this structure:
   ```
   # Plan #<n> — <title>
   Issue: #<n> · Status: DRAFT
   ## Goal and acceptance criteria (copied from the issue)
   ## Approach (2-4 paragraphs; rejected alternatives and why)
   ## Changes per module (new/changed files, classes and public APIs with signatures)
   ## Protocol (if it changes: messages, PROTOCOL_VERSION bump, compatibility)
   ## Threading and resource lifecycle
   ## Risks and mitigations (latency, device compatibility, regressions)
   ## Test strategy (JVM / instrumented / manual on device with metrics)
   ## Implementation steps (ordered checklist, each = 1 commit)
   ```
5. Invoke the **android-architect** agent with the plan path and the issue.
   Integrate the requested changes; if the verdict is "NEEDS REWORK" iterate once.
6. Show the user a plan summary + the architect's verdict and **ask for approval**.
   After approval set `Status: APPROVED` in the file and commit `docs: add plan for #<n>`.
7. If new architectural decisions emerge, add a "Proposed" ADR to `docs/ARCHITECTURE.md`.
8. Suggest the next step: `/sdlc-impl $ARGUMENTS`.
