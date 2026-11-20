# Architecture and decisions (lightweight ADRs)

Each decision has a status: **Accepted**, **Proposed**, **Superseded**. New decisions are appended
with a progressive number; accepted ones are not rewritten, they are superseded.

---

## ADR-001 — Single app with two modes
**Accepted.** One APK. On launch: if the device has `FEATURE_LEANBACK` (Fire TV/projector) the
receiver starts directly; otherwise the user chooses (default sender).
The manifest declares both `LAUNCHER` and `LEANBACK_LAUNCHER`, `touchscreen required=false`,
`microphone required=false` (implied by `RECORD_AUDIO`, would otherwise hide the app on Fire TV)
and a TV banner.
*Revision (M0)*: "TV" = `amazon.hardware.fire_tv` OR `FEATURE_LEANBACK` OR
`UI_MODE_TYPE_TELEVISION` (some Fire TV/projector builds do not report leanback reliably).
`MainActivity` is a UI-less trampoline (`noHistory`, `excludeFromRecents`); an explicit `mode`
launch extra overrides detection.

## ADR-002 — Transport: TCP with custom framing, no RTP/WebRTC
**Accepted.** On a home LAN, TCP with `TCP_NODELAY` gives sufficient latency and free reliability;
congestion is handled at the application level by dropping non-key frames when the send queue grows
and asking the receiver for a keyframe. UDP/RTP with FEC remains a future option (ADR to be written)
if measurements show head-of-line blocking spikes on Wi-Fi.

Socket: small `sendBufferSize` (≈ 2–4 frames at the current bitrate) so congestion is not hidden in
the kernel; `trafficClass` with DSCP AF41 / video; `keepAlive`.

## ADR-003 — Wire format (v2)
**Accepted** (v1 in M1, v2 in M5; `:core:protocol`). v2 appends the sync mode to START and the
video delay to STATS; versions are not interoperable, and a mismatch ends the handshake with
`INCOMPATIBLE` and an "update both devices" message on the tablet. Control payloads: big-endian, strings = u16 length + UTF-8, lists = u8 count; media packets carry raw codec output. Unknown message types are skipped by readers; invalid field values are `MALFORMED_PAYLOAD`.

Fixed 16-byte header, big-endian:

| Offset | Field | Type | Notes |
|---|---|---|---|
| 0 | magic | u16 | `0x5252` ("RR") |
| 2 | version | u8 | `PROTOCOL_VERSION` = 2 |
| 3 | type | u8 | see message table |
| 4 | flags | u8 | bit0 = keyframe, bit1 = codec config |
| 5 | reserved | u8 | 0 |
| 6 | payloadLen | u32 | configurable maximum (default 8 MiB) → above = protocol error |
| 10 | ptsUs/seq | u48 | pts in µs for frames, sequence number for control messages |

**Timebase.** Media pts are sender `CLOCK_MONOTONIC` microseconds (`System.nanoTime() / 1000`), the
clock `VirtualDisplay`/Surface timestamps use for encoder output. Audio pts (M5) must use the same clock.
PING/PONG timestamps use `SystemClock.elapsedRealtimeNanos()` and only measure RTT and offset; never mix
the two (they differ by the total deep-sleep time since boot).

Messages:

| type | Name | Direction | Payload |
|---|---|---|---|
| 0x01 | HELLO | R→S | version, device name, display resolution, refresh rate, decodable codecs with profiles/levels and low-latency flag, audio capabilities |
| 0x02 | START | S→R | chosen codec, width, height, fps, initial bitrate, audio format, sync mode (v2: `u8`, 0 = lip-sync, 1 = low latency) |
| 0x03 | CONFIG | S→R | CSD (VPS/SPS/PPS) as emitted with `BUFFER_FLAG_CODEC_CONFIG` |
| 0x04 | FRAME | S→R | Annex-B access unit |
| 0x05 | AUDIO_CONFIG | S→R | audio codec (PCM s16le / Opus), sample rate, channels, packet duration; Opus CSD if any |
| 0x06 | AUDIO_FRAME | S→R | audio packet (PCM or Opus) with pts in µs on the same timebase as video |
| 0x10 | REQUEST_KEYFRAME | R→S | — |
| 0x11 | PING / 0x12 PONG | ↔ | sender monotonic timestamp (RTT and clock offset estimate) |
| 0x13 | STATS | R→S | decoded fps, dropped frames, mean/p95 decode latency, audio underruns, A/V offset, video delay (v2: `i32` µs) |
| 0x14 | BITRATE_HINT | R→S | suggested bitrate (adaptation) |
| 0x7F | BYE | ↔ | reason code |

## ADR-004 — Encoder pipeline (sender)
**Accepted.**
- Source: `MediaProjection.createVirtualDisplay` directly onto the encoder input `Surface`
  (zero copies). Resolution = min(tablet display, receiver display), aligned to 16.
- Codec: HEVC if both sides support it in hardware; otherwise AVC. Hardware codecs only
  (`isHardwareAccelerated`).
- Parameters: `KEY_PRIORITY=0` (realtime), `KEY_BITRATE_MODE=CBR` (fallback VBR),
  long `KEY_I_FRAME_INTERVAL` (e.g. 10 s) + `PARAMETER_KEY_REQUEST_SYNC_FRAME` on demand,
  `KEY_REPEAT_PREVIOUS_FRAME_AFTER` for static screens, `KEY_MAX_B_FRAMES=0`, Main/High profile
  without B-frames, `KEY_LATENCY=1` where supported, intra-refresh when available
  (`FEATURE_IntraRefresh`) to avoid keyframe spikes.
- Adaptive bitrate: `PARAMETER_KEY_VIDEO_BITRATE` changed by a pure (testable) policy that reads
  send-queue occupancy + receiver STATS.

## ADR-005 — Decoder pipeline (receiver)
**Accepted.**
- `SurfaceView` (not `TextureView`) for the shortest composition path.
- `KEY_LOW_LATENCY=1` if `FEATURE_LowLatency` (API 30+); on Fire OS 7 try known vendor keys
  (e.g. `vendor.low-latency.enable`) only if the codec advertises them; `KEY_PRIORITY=0`,
  high `KEY_OPERATING_RATE`.
- Asynchronous mode (`setCallback`) on a dedicated thread; outputs released immediately
  (`releaseOutputBuffer(index, true)`), no jitter buffer beyond 1 frame.
- Backlog policy (`DecoderBacklog`): when the oldest access unit inside the decoder has waited
  more than 60 ms, or more than 8 are in flight, drop non-key frames until the next keyframe and
  send REQUEST_KEYFRAME (once per episode, and at most one every 300 ms). Short network bursts
  that the decoder absorbs are not dropped. The in-flight window resyncs on the keyframe that ends
  an episode, so units consumed without output cannot cause permanent dropping.
- CONFIG is sent once per encoder configuration (encoders emit CSD only at start). The receiver
  caches the last CONFIG and replays it after an in-place codec reset (recoverable `CodecException`);
  transient errors are ignored. A REQUEST_KEYFRAME therefore never requires a new CONFIG.
- All control writes on the receiver go through one control thread (PONG, STATS, REQUEST_KEYFRAME,
  BYE); the UI thread and the codec thread never block on the socket.
- Display: `Surface.setFrameRate` / display mode preference aligned with source fps to avoid judder.

## ADR-006 — Discovery: NSD (mDNS)
**Accepted.** The receiver registers `_ririproj._tcp` with TXT records `v=1`, `name`, `codecs`.
The sender uses `NsdManager.discoverServices`; fallback to manual IP:port entry
(some networks/routers filter multicast).

## ADR-007 — Audio (part of the MVP)
**Accepted.** Tablet audio must play on the TV: mirroring without audio is not considered working.
- **Capture**: `AudioPlaybackCapture` (API 29+, fine on the tablet) from the same `MediaProjection` as
  video, `RECORD_AUDIO` permission, usages `MEDIA`, `GAME`, `UNKNOWN`. Known limitation: apps that
  declare `allowAudioPlaybackCapture=false` (or use non-capturable usages, e.g. calls) are not
  captured → shown in the UI, not a bug.
- **Format**: default **PCM s16le 48 kHz stereo** in 10 ms packets (~1.5 Mbps, no codec latency,
  negligible next to video on a LAN). **Opus** (MediaCodec encoder API 29+, decoder available on
  Fire OS 7) as an option for weak networks, selected during negotiation.
- **Transport** *(decided in M5)*: the **same TCP connection** as video. The sender `SendQueue`
  writes by priority (control > audio > video). Priority only holds in user space, so the socket
  send buffer is kept small (64 KB, about two frames at 20 Mbps): congestion then builds in the
  `SendQueue`, and an audio packet waits behind at most the video already in the kernel and the one
  being written (tens of ms on a dip), which the receiver jitter buffer absorbs. A second connection would duplicate connect, handshake
  binding and teardown for a gain the measurements do not need. Revisit (fragmented video or a
  second socket) only if on-device underruns correlate with keyframes. On AUDIO_CONFIG the
  receiver creates a `JitterBuffer` + `AudioPlayer` for that format; audio failures never end the session
  (mirroring continues video-only and the sender UI says so).
- **Opus** is deferred to the bandwidth policy work (M6): PCM adds no codec latency.
- **Playback (receiver)**: `AudioTrack` with `PERFORMANCE_MODE_LOW_LATENCY`, minimal buffer,
  adaptive 20–60 ms jitter buffer; on underrun insert silence, on build-up drop samples to return
  to target. Clock drift between the devices is absorbed by dropping or repeating single frames
  when the smoothed level leaves target ± 10 ms (no audible jumps).
- **A/V measurement**: `AvSync`, for each side, (receiver monotonic time the sample is shown/played)
  − (sender pts), smoothed; the difference is the A/V offset (positive = audio late), shown in the
  overlay and sent in STATS. The unknown clock offset between the devices cancels out. Video "shown"
  comes from `OnFrameRenderedListener` (frames are released with an explicit `System.nanoTime()`
  render time: with `releaseOutputBuffer(i, true)` some decoders echo the pts instead). Audio
  "played" comes only from `AudioTrack.getTimestamp` samples that pass `TimestampValidator`
  (call succeeded, position advanced or the same sample repeated, younger than 200 ms).
- **Sync mode** *(decided in M5, user choice in the tablet settings, sent in START)*. Measured on a
  Tab S9 → Pixel: video is on screen ~10 ms after arrival, audio plays ~190 ms after (capture
  ~75 ms, jitter buffer, track and device output), so audio is late by ~180 ms.
  - **Lip-sync (default)**: the receiver delays video to meet audio. Every video unit goes through a
    `VideoDelayLine` (one byte ring sized for 300 ms + margin at twice the bitrate, ≥ 2 MiB, 64
    descriptors; `due = max(arrival + D, previous due)`; a dedicated urgent-display thread feeds the
    decoder; on overflow it drops to the next keyframe and requests one; never blocks the network
    thread). `VideoDelayController` sets D once per second: it acts only on fresh samples (≥ 10
    video and ≥ 20 audio reports since the last tick, both < 500 ms old), so a static screen holds
    D; the first fresh tick jumps to the estimate, then gain 0.5, ≤ 20 ms per second, 15 ms
    deadband, D in 0..300 ms; after 10 s without audio D slews back to 0. Target: A/V within ±40 ms.
  - **Low latency**: no delay line; video renders immediately and audio is late by the measured
    offset. For interactive use (games, UI).
  - With audio off the delay never leaves 0 (no audio samples), so lip-sync costs nothing.
- **Audio off** *(M5)*: a sender setting (default on). Off means no RECORD_AUDIO request, no capture,
  no AUDIO_CONFIG.
- **To verify on device**: whether audio keeps playing from the tablet speakers during capture and
  how to mute it without affecting the captured stream.

## ADR-008 — Tooling
**Accepted.** Gradle Kotlin DSL + version catalog, latest stable AGP/Kotlin at scaffolding time,
Gradle running on JDK 25 LTS with Java 17 bytecode, Compose for the tablet UI, minimal Views for the
receiver, Spotless (ktlint) + Android Lint, JUnit4 + Truth + kotlinx-coroutines-test for JVM tests,
AndroidX Test for instrumented tests.
*Revision (M0)*: Gradle 9.9.1, AGP 9.5.1 with built-in Kotlin and the new DSL (no
`kotlin-android` plugin; convention plugins configure `ApplicationExtension`/`LibraryExtension`),
Kotlin 2.4.21 pinned from the root build classpath, `compileSdk 37` (required by current AndroidX),
`targetSdk 36` until the app is validated on Android 17 behavior changes. Pure JVM modules compile
with `-Xjdk-release=17` / `options.release = 17` so they cannot use APIs missing on Android.

## ADR-009 — Single-app sharing and its audio
**Accepted** (M5). The choice between the entire screen and one app is made **only in the system
consent dialog** (Android 14+: `createConfigForUserChoice`); the tablet UI has no share setting.
- Playback capture is **device-wide** whatever the projection shows, and Android 14–16 have no API
  that tells what the user picked in the dialog. For a one-app share, capturing everything would
  leak other apps' sound into it.
- **Requirement: the audio follows the choice automatically.** The system brings the chosen app to
  the front right after the dialog (verified on a Tab S9, One UI / Android 16); for the entire screen
  nothing else comes to the front. With the user-granted **Usage access** (`PACKAGE_USAGE_STATS`
  app op) the session reads `UsageEvents.ACTIVITY_RESUMED` between the dialog being shown and
  1.5 s after its result, ignoring RiriProjector, System UI and the launcher (`SharedAppPicker`,
  JVM-tested):
  - an app found → **one app** shared: capture only its uid (`addMatchingUid`, ANDed with the
    usage rules); if its uid cannot be resolved, video only;
  - none → **entire screen**: capture every app.
  Detected once per session: switching apps later does not change it.
- Usage access is asked **once**, in a dialog, the first time the user starts mirroring with audio
  on (API 34+). If declined, every app's sound is mirrored whatever is shared.
- Limitation: sharing the entire screen and opening another app within 1.5 s of the dialog closing
  is taken for a one-app share of that app.
- *Superseded*: a manual "Audio from" picker and a "Share" setting (earlier M5 iterations) were
  rejected by the user: one decision, in the system dialog.
- When the shared app leaves the screen (`onCapturedContentVisibilityChanged`), the tablet shows a
  warning: the projector shows nothing until the user returns to it.
- Revisit when `MediaProjectionConfig` can request app-scoped audio (API 37
  `setAudioRequested`) on the target tablets.
