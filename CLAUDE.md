# RiriProjector — guide for Claude Code

A single Android app (Kotlin) that mirrors the screen **and audio** of a **Galaxy Tab** (sender) to a
**Fire TV Stick / Android projector** (receiver) over the LAN, with lower latency and better quality
than Miracast.

```
Tablet (sender)                                         Fire TV (receiver)
MediaProjection → VirtualDisplay → Surface              NSD discovery (_ririproj._tcp)
  → MediaCodec encoder HEVC|H.264 (realtime, CBR)       TCP server ← HELLO/caps
  → protocol framing → TCP (TCP_NODELAY) ─────────────→ deframing → MediaCodec decoder (low-latency)
  ← REQUEST_KEYFRAME / PING / BITRATE  ←─────────────── → SurfaceView (render immediately)
AudioPlaybackCapture → PCM 48k (or Opus) ────────────→ jitter buffer → low-latency AudioTrack
                                                         (aligned to video pts, A/V ±40 ms)
```

**Audio is mandatory**: mirroring without audio is not considered working (ADR-007).
The receiver advertises itself via NSD; the sender discovers it and connects. Detailed decisions
in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); roadmap in [docs/PLAN.md](docs/PLAN.md).

## Module structure

| Module | Type | Responsibility |
|---|---|---|
| `:app` | Android app | Launch activity, mode selection (auto-receiver on TV/leanback), wiring, single manifest |
| `:core:protocol` | **Pure Kotlin/JVM** | Framing, messages, (de)serialization, versioning, codec negotiation. No Android deps → fast JVM tests |
| `:core:net` | Android lib | TCP transport (client/server), NSD publish/discover, backpressure, RTT measurement |
| `:core:media` | Android lib | MediaCodec encoder/decoder wrappers (video and Opus), codec selection from `MediaCodecList`, low-latency params, audio capture (`AudioPlaybackCapture`) and playback (`AudioTrack`) |
| `:feature:sender` | Android lib | MediaProjection (video + audio), `mediaProjection` foreground service, tablet UI |
| `:feature:receiver` | Android lib | Full-screen leanback activity with `SurfaceView`, audio player, A/V sync, stats overlay |

Dependency rule: `feature → core:media/net → core:protocol`. Features never depend on each other.
All logic that can be tested without a device (framing, bitrate policy, codec choice from a capability
list, session state machine, jitter buffer) lives in pure, injectable code covered by JVM tests.

## Commands

On Windows use `./gradlew` from Git Bash (or `gradlew.bat`). Gradle runs on JDK 25 LTS
(locally `D:\Android\jdk25`); Android SDK in `D:\Android\Sdk`; Gradle cache in `D:\Android\gradle-home`
(`GRADLE_USER_HOME`). App bytecode targets Java 17, which is what Android supports.

```bash
./gradlew assembleDebug              # build the single APK (sender + receiver)
./gradlew testDebugUnitTest          # JVM unit tests (all Android modules)
./gradlew :core:protocol:test        # protocol only, the fastest loop
./gradlew spotlessApply              # ktlint formatting
./gradlew spotlessCheck lintDebug    # what CI runs besides tests and build
./gradlew connectedDebugAndroidTest  # instrumented tests (device attached)
scripts/deploy.sh firetv|tablet|both # build + install via adb (see /firetv-deploy)
```

Local environment variables (never committed): `FIRETV_HOST` (Fire TV IP, port 5555 by default),
`TABLET_SERIAL` (adb serial of the tablet, optional).

## SDLC flow (mandatory)

Every non-trivial change follows: **requirement → plan → implementation → tests → review → PR**.
Details in [docs/SDLC.md](docs/SDLC.md). Available skills:

| Phase | Skill | Output |
|---|---|---|
| Requirement | `/sdlc-req` | GitHub issue with measurable acceptance criteria |
| Plan | `/sdlc-plan <issue>` | `docs/plans/<issue>-<slug>.md` reviewed by the `android-architect` agent |
| Implementation | `/sdlc-impl <issue>` | Small commits on `feat/<issue>-<slug>` |
| Tests | `/sdlc-test` | Tests written by the `test-writer` agent, green suite |
| Review | `/sdlc-review` | Review by the `media-net-reviewer` agent + fixes |
| PR | `/sdlc-pr` | PR with the filled-in template, linked to the issue |
| Full cycle | `/sdlc-feature <description>` | Orchestrates the phases above, pausing for approval |
| Device | `/firetv-deploy` | Build, install on Fire TV/tablet, filtered logcat |

## Conventions

- **Everything in English**: code, identifiers, comments, docs, commits, issues, PRs, UI strings
  (UI strings in `strings.xml`, so they can be localized later).
- Idiomatic Kotlin, coroutines + `Flow`; no `GlobalScope`. Dedicated threads (not `Dispatchers.Default`)
  for blocking codec and socket loops, with high priority (`THREAD_PRIORITY_URGENT_DISPLAY` / `_AUDIO`).
- **Allocation-free hot path**: in the encoder→socket and socket→decoder loops reuse `ByteBuffer`s/arrays,
  no boxing, no per-frame logging (only counters aggregated every second).
- Every resource (MediaCodec, VirtualDisplay, MediaProjection, AudioRecord, AudioTrack, Socket,
  NsdManager listener) has a clear owner and is released in `finally`/`close()`; error states lead to a
  clean session rebuild, not a crash.
- Wire format: big-endian, version in the header, fixed-size fields; every protocol change bumps
  `PROTOCOL_VERSION` and updates `docs/ARCHITECTURE.md`.
- Logging: tag `Riri/<Component>`; metrics (fps, bitrate, RTT, dropped frames, decode latency,
  A/V offset) exposed via `StateFlow` for the overlay.
- Commits: Conventional Commits (`feat:`, `fix:`, `perf:`, `test:`, `chore:`, `docs:`).
  Branches: `feat/<issue>-<slug>`, `fix/<issue>-<slug>`, `chore/<slug>`. Never commit directly to `main`.

## Platform constraints to remember

- Fire TV Stick: Fire OS 7 = Android 9 (API 28), Fire OS 8 = Android 11 (API 30) → `minSdk 28`.
  `KEY_LOW_LATENCY` only from API 30: on API 28 use vendor keys when present, otherwise minimize
  app-side buffering. Requires `LEANBACK_LAUNCHER`, a 320×180 banner,
  `uses-feature touchscreen required=false`.
- Galaxy Tab (Android 14+): MediaProjection consent for every session, foreground service with
  `foregroundServiceType="mediaProjection"` started **before** `getMediaProjection`,
  `MediaProjection.Callback` registered before creating the VirtualDisplay.
- Audio: `AudioPlaybackCapture` requires API 29+ and `RECORD_AUDIO`; apps with
  `allowAudioPlaybackCapture=false` cannot be captured (a limitation to surface in the UI, not a bug).
  The audio jitter buffer is pure logic tested on the JVM; never block the `AudioTrack` thread.
- Do not assume HEVC: negotiate the codec from the capabilities announced by the receiver (HELLO)
  and those of the local encoder; fall back to H.264.

## Don'ts

- Do not add heavy streaming libraries (WebRTC, ffmpeg, ExoPlayer) without a decision recorded in
  `docs/ARCHITECTURE.md` and approved by the user.
- Do not modify `gradle/wrapper/gradle-wrapper.jar`, keystores or `*.properties` files with secrets
  (blocked by hooks).
- No `git push --force` and no push to `main` (blocked by hooks). PRs are opened with `/sdlc-pr`.
