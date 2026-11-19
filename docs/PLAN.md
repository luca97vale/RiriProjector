# Implementation plan — RiriProjector

Milestone roadmap. Each milestone is a GitHub issue and follows the SDLC cycle (detailed plan in
`docs/plans/` before code). Milestones are ordered to reduce risk: first prove the end-to-end video
chain, then audio, then optimize latency.

**Measurable MVP goal**: 1080p60 HEVC (or H.264) **with audio** from the Galaxy Tab to the Fire TV
over 5 GHz Wi-Fi, glass-to-glass latency ≤ 100 ms p95 (final target ≤ 60 ms), A/V offset within
±40 ms, no freeze or audio gap > 500 ms in 10 minutes, automatic reconnection within 3 s.
The MVP is complete only at the end of M5 (audio included).

| Milestone | Issue |
|---|---|
| M0 Scaffolding | #5 |
| M1 Protocol | #6 |
| M2 Network | #7 |
| M3 Receiver | #8 |
| M4 Sender | #9 |
| M5 Audio | #10 |
| M6 Latency & robustness | #11 |
| M7 Polish & release | #12 |

---

## M0 — Project scaffolding
- Multi-module Gradle Kotlin DSL project as in `CLAUDE.md` (`:app`, `:core:protocol` JVM,
  `:core:net`, `:core:media`, `:feature:sender`, `:feature:receiver`), version catalog,
  convention plugins in `build-logic/` to avoid repeating Android configuration.
- `applicationId dev.ririprojector` (`.debug` suffix for debug), `minSdk 28`, latest stable
  `targetSdk`/`compileSdk`; Gradle on JDK 25 LTS, Java 17 bytecode. Verify that the chosen
  Gradle/AGP/Kotlin versions officially support JDK 25; otherwise use a JDK 21 toolchain.
- Manifest: `LAUNCHER` + `LEANBACK_LAUNCHER`, TV banner, `touchscreen required=false`,
  permissions `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `POST_NOTIFICATIONS`, `RECORD_AUDIO`.
- `MainActivity` reading the `mode` extra (used by `scripts/deploy.sh`) or choosing based on
  `FEATURE_LEANBACK`.
- Spotless + ktlint, Lint, JUnit4/Truth/coroutines-test, one sample test.
- Verification: green CI; `scripts/deploy.sh both` installs and launches the empty app on both devices.

## M1 — Protocol (`:core:protocol`, pure JVM)
- ADR-003 implementation: `FrameHeader`, sealed `Message`, `MessageWriter`/`MessageReader` over
  `InputStream`/`OutputStream` with reusable buffers, `payloadLen` limit, `PROTOCOL_VERSION`.
- Capability model: `CodecCapability(mime, profiles, maxWidth, maxHeight, maxFps, lowLatency)`
  and `CodecNegotiator` (picks codec/resolution/fps/bitrate/audio format from sender + receiver) —
  pure and tested.
- Tests: round-trip of every message, byte-by-byte fragmented stream, corrupted headers, unknown
  version, payload over the limit, capability matrices.

## M2 — Network and discovery (`:core:net`)
- `ReceiverServer` (ServerSocket, one active session at a time) and `SenderClient`
  (connect with timeout, `TCP_NODELAY`, reduced send buffer, video DSCP).
- `NsdPublisher`/`NsdBrowser` with `Flow` APIs, TXT records, manual IP fallback.
- Pure `SessionStateMachine`: Idle → Discovering → Connecting → Handshaking → Streaming →
  Reconnecting → Closed, with timeouts and backoff; PING/PONG for RTT.
- Bounded send queue dropping non-key frames.
- Tests: JVM over loopback sockets (full handshake, remote close, timeout), NSD instrumented.

## M3 — Receiver (Fire TV)
- Full-screen leanback `ReceiverActivity` with `SurfaceView`, keep-screen-on, stats overlay
  toggled from the remote.
- `VideoDecoder` (`:core:media`): hardware decoder selection from `MediaCodecList`, low-latency
  configuration (ADR-005), async mode, CONFIG/keyframe handling, drop until next IDR +
  REQUEST_KEYFRAME on backlog.
- HELLO with the device's real capabilities.

## M4 — Sender (Galaxy Tab) → **first end-to-end**
- Compose UI: discovered receivers, status, settings (codec, resolution, fps, bitrate).
- Foreground `ProjectionService` (`mediaProjection`): consent, `MediaProjection.Callback`,
  `VirtualDisplay` on the encoder Surface, stop from notification.
- `VideoEncoder` (`:core:media`): HEVC/H.264 hardware, ADR-004 parameters, sync frame request,
  live bitrate update.
- Encoder → `MessageWriter` loop on a dedicated thread, bounded queue dropping non-keyframes.
- Verification: `/firetv-deploy both`, stable mirroring for 10 minutes, metrics in overlay.

## M5 — Audio (ADR-007, mandatory for the MVP)
- Protocol: `AUDIO_CONFIG`/`AUDIO_FRAME`, PCM/Opus negotiation in HELLO/START; final transport
  decision (dedicated socket vs priority scheduler) recorded in the ADR.
- Sender: `AudioCapture` with `AudioPlaybackCapture` on the same `MediaProjection`, `RECORD_AUDIO`
  permission, 10 ms packets with pts on the video timebase, optional Opus encoder.
- Receiver: `AudioPlayer` with low-latency `AudioTrack`, pure `JitterBuffer` (JVM tested:
  underrun → silence, build-up → drop, pts realignment), optional Opus decoder.
- A/V sync via pts + `AudioTrack.getTimestamp`; A/V offset in the overlay.
- UI: "audio not capturable" indicator for apps that forbid it; check and handle audio still
  playing from the tablet speakers.
- Verification: clap/flash test video → measured A/V offset ≤ 40 ms; 10 minutes of music with no
  audible gaps.

## M6 — Latency and robustness
- Measurement: sender timestamps in FRAME + clock offset estimate via PING → capture→decode
  latency in overlay; glass-to-glass procedure (stopwatch on the tablet filmed together with the
  TV at 240 fps) documented in `docs/`.
- Adaptive bitrate (pure `BitratePolicy`) based on send queue and STATS.
- Intra-refresh where supported; tuning of `KEY_REPEAT_PREVIOUS_FRAME_AFTER`, operating rate,
  vendor keys (MediaTek on Fire TV, Exynos/Qualcomm on Samsung).
- Rotation and resolution change (`onCapturedContentResize`) with encoder reconfiguration.
- Automatic reconnection, Wi-Fi loss handling, receiver in background.

## M7 — Polish and release
- Persistent settings, last receiver remembered, quick start.
- Release signing (keystore outside the repo, secrets in GitHub Actions), release workflow on tag
  that attaches the signed APK.
- User README: sideloading on the Fire TV.

---

## Main risks
| Risk | Impact | Mitigation |
|---|---|---|
| Fire TV decoder without real low-latency mode on Fire OS 7 | high latency | measure early (M3), vendor keys, at most 1 frame in flight, H.264 fallback |
| Multicast/NSD filtered by the router | no discovery | manual IP entry + QR code with IP:port on the TV |
| Keyframe spikes on Wi-Fi | stutter | intra-refresh, IDR only on request, CBR with small VBV |
| MediaProjection consent per session on Android 14+ | UX | long session with reconnection that keeps the token while the service is alive |
| Protected content (DRM/FLAG_SECURE) shown black | expected | document as a limitation |
| Apps forbidding audio capture (`allowAudioPlaybackCapture=false`) | no audio for those apps | detect and show it in the UI; document as a limitation |
| Fire TV HDMI audio output latency differs from video | lip-sync off | measure with `AudioTrack.getTimestamp`, configurable video delay |
| Audio packets queued behind video keyframes | audio gaps | dedicated audio socket or priority scheduler (ADR-007), adaptive jitter buffer |
