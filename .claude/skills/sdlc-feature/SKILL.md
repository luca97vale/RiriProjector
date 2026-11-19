---
name: sdlc-feature
description: Full SDLC cycle for RiriProjector, from idea to PR (requirement → plan → implementation → tests → review → PR), pausing for user approval at the gates. Invoke explicitly.
argument-hint: <feature description> | #<existing issue number>
disable-model-invocation: true
---

# End-to-end feature

Input: $ARGUMENTS

Run the phases in order following the corresponding skills (read their `SKILL.md` in
`.claude/skills/`). Keep the task list updated with one item per phase.

1. **Requirement** — if the input is `#<n>` use the existing issue, otherwise follow `sdlc-req`.
   ⏸ Gate: user confirmation to create the issue.
2. **Plan** — follow `sdlc-plan` (reviewed by `android-architect`).
   ⏸ Gate: explicit plan approval. Do not write code before.
3. **Implementation** — follow `sdlc-impl`.
4. **Tests** — follow `sdlc-test` (`test-writer` agent).
5. **Review** — follow `sdlc-review` (`media-net-reviewer` agent).
6. **Device** — if the feature is observable on a device, propose `/firetv-deploy both` and ask the
   user to report what they see (or the overlay metrics).
7. **PR** — follow `sdlc-pr`. ⏸ Gate: confirmation to push and create the PR.

If a phase fails in a non-trivial way, stop, explain and propose how to proceed instead of
working around the problem.
