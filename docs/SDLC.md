# Development process (SDLC with Claude Code)

## Phases and quality gates

```
 requirement ──▶ plan ──▶ implementation ──▶ tests ──▶ review ──▶ PR ──▶ green CI ──▶ merge
  /sdlc-req   /sdlc-plan    /sdlc-impl     /sdlc-test /sdlc-review /sdlc-pr
     │            │                                       │
  GH issue    user                                 no "blocker"
              approval                               findings
```

| Phase | Input | Output (Definition of Done) | Who |
|---|---|---|---|
| Requirement | natural-language idea | Issue with: context, verifiable acceptance criteria, metrics (e.g. p95 latency, fps), devices involved, labels | Claude + user |
| Plan | issue | `docs/plans/<n>-<slug>.md` with touched modules, APIs, risks, test strategy; reviewed by `android-architect`; **approved by the user** | Claude + agent |
| Implementation | approved plan | code on `feat/<n>-<slug>`, small commits, `spotlessCheck` + build green | Claude |
| Tests | code | JVM unit tests for pure logic, instrumented tests for codec/network where needed; `testDebugUnitTest` green | `test-writer` agent |
| Review | full diff | `media-net-reviewer` report with no open blockers; suggestions applied or justified | agent |
| PR | clean branch | PR with filled-in template, `Closes #n`, on-device test notes | Claude (with confirmation) |
| Merge | green CI + human review | squash merge into `main` | user |

## Rules

- One issue = one branch = one PR. If out-of-scope work shows up during implementation, open a new
  issue instead of widening the PR.
- The plan is a contract: if it changes significantly, update the plan file in the same PR.
- Network protocol changes always require: a `PROTOCOL_VERSION` bump, an update to
  `docs/ARCHITECTURE.md`, round-trip and header compatibility tests.
- Changes to the media/network hot path require a before/after measurement (stats overlay or
  logcat `Riri/Stats`) reported in the PR.
- Every PR touching codecs, sockets, audio or MediaProjection must be tried on real devices
  (`/firetv-deploy both`) and the "On-device tests" checklist in the template must be filled in.

## GitHub labels

`type:feature`, `type:bug`, `type:perf`, `type:chore`, `area:sender`, `area:receiver`,
`area:protocol`, `area:media`, `area:audio`, `area:net`, `area:ci`, `priority:high|medium|low`.
Created by `scripts/setup-labels.sh` (requires an authenticated `gh`).

## Agents

| Agent | When | Model |
|---|---|---|
| `android-architect` | plans, module/API choices, ADRs | opus |
| `media-net-reviewer` | review of every diff in `core/*`, `feature/*` | opus |
| `test-writer` | writing tests after implementation | sonnet |

## Active hooks (`.claude/settings.json`)

- **PreToolUse Bash** → `guard-bash.mjs`: blocks force pushes, pushes to `main`, `--no-verify`,
  `git reset --hard`/`git clean -f`, `rm -rf` on root/home, destructive `adb shell` commands.
- **PreToolUse Edit|Write** → `protect-files.mjs`: blocks keystores, `*.jks`, `keystore.properties`,
  `local.properties`, `.env`, `gradle-wrapper.jar`, build outputs.
- **PostToolUse Edit|Write** → `format-kotlin.mjs`: runs `spotlessApply` on the edited `.kt/.kts` file only
  (silent until Gradle/Spotless exist).
- **SessionStart** → `session-context.mjs`: injects current branch, linked issue and plan status.
