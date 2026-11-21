---
name: sdlc-req
description: SDLC phase 1 for RiriProjector. Turns an idea or a bug into a GitHub issue with measurable acceptance criteria. Use when the user describes a new feature, a problem or a performance improvement.
argument-hint: <feature or bug description>
allowed-tools: Read, Grep, Glob, Bash(gh issue list:*), Bash(gh issue view:*), Bash(gh label list:*)
---

# Requirement → Issue

Input: $ARGUMENTS

1. Read `CLAUDE.md` and `docs/PLAN.md` to see which milestone the request belongs to.
   Look for duplicates: `gh issue list --state all --search "<keywords>"`.
2. If essential information is missing (device, expected behavior, metric), ask the user at most
   3 targeted questions. Otherwise proceed with explicit assumptions.
3. Draft the issue following `.github/ISSUE_TEMPLATE/feature.yml` (or `bug.yml`):
   - **Context / problem** (why it is needed)
   - **Expected behavior**
   - **Acceptance criteria**: verifiable `- [ ]` list; for performance work include numbers
     (e.g. "glass-to-glass latency p95 ≤ 80 ms at 1080p60 on 5 GHz Wi-Fi", "no dropped frames in
     5 minutes of scrolling", "A/V offset ≤ 40 ms").
   - **Out of scope**
   - **Devices/OS involved** (sender and receiver model, Android version / API level, vendor OS such as
     One UI or Fire OS if relevant)
   - **Area and milestone** → labels `type:*`, `area:*`, `priority:*`
4. Show the draft to the user and **ask for confirmation** before creating it.
5. After the yes: `gh issue create --title "<type>: <title>" --body-file <temp file> --label ...`
   (write the body to a scratchpad file, not inline).
6. Report the issue number and link and suggest the next step: `/sdlc-plan <number>`.
