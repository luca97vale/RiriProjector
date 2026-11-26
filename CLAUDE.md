# RiriProjector — guide for Claude Code

A single Android app (Kotlin) that mirrors the screen **and audio** of any **Android phone or tablet** (sender) to any
**Android TV, set-top box, projector, phone or tablet** (receiver) over the LAN, with lower latency
and better quality than Miracast. Nothing is tied to a vendor or model: roles and limits follow API
levels and device features (see "Platform constraints"). Reference test devices so far: Galaxy Tab S9
(sender), Fire TV Stick and Pixel 10 (receivers).

```
Sender (phone/tablet, API 29+ for audio)                 Receiver (TV/box/projector/phone, API 28+)
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
| `:app` | Android app | Launch activity, mode selection (auto-receiver on TV-like devices, remembered manual choice everywhere), wiring, single manifest |
| `:core:protocol` | **Pure Kotlin/JVM** | Framing, messages, (de)serialization, versioning, codec negotiation. No Android deps → fast JVM tests |
| `:core:net` | Android lib | TCP transport (client/server), NSD publish/discover, backpressure, RTT measurement |
| `:core:media` | Android lib | MediaCodec encoder/decoder wrappers (video and Opus), codec selection from `MediaCodecList`, low-latency params, audio capture (`AudioPlaybackCapture`) and playback (`AudioTrack`) |
| `:core:ui` | Android lib | Brand palette (from the launcher artwork), the dog artwork and the animated `DogLoaderView` shared by sender and receiver; timing in pure `DogLoaderMotion` |
| `:core:adb` | Android lib (NDK) | Wireless-debugging pairing and ADB client, renamed fork of Shizuku (Apache-2.0, ADR-014); `libriri_adb.so` over BoringSSL |
| `:core:helper` | Android lib | Second-screen shell helper (`app_process` as uid 2000) and its binder: display, launch, input, audio (ADR-014) |
| `:feature:sender` | Android lib | MediaProjection (video + audio), `mediaProjection` foreground service, phone/tablet UI (Compose) |
| `:feature:receiver` | Android lib | Full-screen activity (D-pad and touch) with `SurfaceView`, audio player, A/V sync, stats overlay |

Dependency rule: `feature → core:media/net → core:protocol`, `feature → core:ui`, and `feature:sender → core:helper → core:adb` (no other deps; `core:adb` has no internal deps, receivers never load it). Features never depend on each other.
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
scripts/deploy.sh receiver|sender|both # build + install via adb (see /device-deploy)
```

Local environment variables (never committed): `RECEIVER_HOST` (receiver IP for `adb connect`, port
`RECEIVER_PORT`, 5555 by default) or `RECEIVER_SERIAL` (USB-attached receiver), and `SENDER_SERIAL`
(adb serial of the sender, optional). The old names `FIRETV_HOST`/`FIRETV_PORT`/`TABLET_SERIAL` still
work as fallbacks.

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
| Device | `/device-deploy` | Build, install on the receiver/sender devices, filtered logcat |

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

- Roles, not models: **sender** = any phone or tablet (video from `minSdk 28`, audio capture from API 29,
  single-app sharing from API 34); **receiver** = any TV, set-top box, projector, phone or tablet
  (`minSdk 28`). Never branch on `Build.MODEL`/manufacturer; gate on API level, `MediaCodecList`
  capabilities and system features. Vendor-specific codec keys are best effort and always have a
  plain fallback.
- `minSdk 28` keeps older TV sticks (e.g. Fire OS 7 = Android 9) as receivers. `KEY_LOW_LATENCY` only
  from API 30: below, try vendor keys when present, otherwise minimize app-side buffering.
- TV devices: `LEANBACK_LAUNCHER`, a 320×180 banner, `uses-feature touchscreen required=false`, D-pad
  navigation. "TV-like" = `FEATURE_LEANBACK` or `UI_MODE_TYPE_TELEVISION` (or the Fire TV feature flag,
  since some Fire OS builds report neither) → auto-receiver; every device can switch role manually.
- Small screens: layouts must work at 360 dp width in both orientations (phones as sender or receiver).
- Android 14+ senders: MediaProjection consent for every session, foreground service with
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
