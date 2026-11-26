# Architecture and decisions (lightweight ADRs)

Each decision has a status: **Accepted**, **Proposed**, **Superseded**. New decisions are appended
with a progressive number; accepted ones are not rewritten, they are superseded.

---

## ADR-001 — Single app with two modes
**Accepted.** One APK. On launch: if the device has `FEATURE_LEANBACK` (Android TV, Fire TV, projector) the
receiver starts directly; otherwise the user chooses (default sender).
The manifest declares both `LAUNCHER` and `LEANBACK_LAUNCHER`, `touchscreen required=false`,
`microphone required=false` (implied by `RECORD_AUDIO`, would otherwise hide the app on TV devices)
and a TV banner.
*Revision (M0)*: "TV" = `amazon.hardware.fire_tv` OR `FEATURE_LEANBACK` OR
`UI_MODE_TYPE_TELEVISION` (some TV/projector builds, e.g. Fire TV, do not report leanback reliably).
`MainActivity` is a UI-less trampoline (`noHistory`, `excludeFromRecents`); an explicit `mode`
launch extra overrides detection.
*Revision (#24, all Android devices)*: the roles are not tied to a model or vendor. **Sender** = any
phone or tablet (video from minSdk 28, audio capture from API 29, single-app sharing from API 34);
**receiver** = any TV, set-top box, projector, phone or tablet (minSdk 28). The promised user choice
now exists: the sender screen offers *Use this device as a receiver* and the receiver waiting screen
*Use this device as a sender*. They send `dev.ririprojector.action.USE_AS_RECEIVER` / `USE_AS_SENDER`
(package-scoped) to a non-exported `activity-alias` of `MainActivity`, which stores the choice. Precedence
at launch: a running sender session (its notification reopens the app) > `mode` extra (not
stored, used by `scripts/deploy.sh`) > stored choice > TV detection. The receiver toggles its stats
overlay with a tap as well as with D-pad keys. Reference test devices so far: Galaxy Tab S9 (sender),
Fire TV Stick on Fire OS 7 and Pixel 10 (receivers); figures below say which device they come from.

## ADR-002 — Transport: TCP with custom framing, no RTP/WebRTC
**Accepted.** On a home LAN, TCP with `TCP_NODELAY` gives sufficient latency and free reliability;
congestion is handled at the application level by dropping non-key frames when the send queue grows
and asking the receiver for a keyframe. UDP/RTP with FEC remains a future option (ADR to be written)
if measurements show head-of-line blocking spikes on Wi-Fi.

Socket: small `sendBufferSize` (≈ 2–4 frames at the current bitrate) so congestion is not hidden in
the kernel; `trafficClass` with DSCP AF41 / video; `keepAlive`.

## ADR-003 — Wire format (v5)
**Accepted** (v1 in M1, v2 in M5, v3 in M6, v4 in #21, v5 in #34; `:core:protocol`). v5 appends `i32 maxPixels` and `i64 maxPixelRate` to every video codec capability (HELLO decoders; ADR-004 Auto resolution). v4 adds P2P_OFFER (ADR-011). v2 appended the sync mode to START
and the video delay to STATS. v3 makes PING/PONG NTP-style on `CLOCK_MONOTONIC` (4 timestamps; PING
carries the sender's offset and RTT estimate), adds capture→shown latency to STATS and adds
VIDEO_RECONFIG. Versions are not interoperable, and a mismatch ends the handshake with
`INCOMPATIBLE` and an "update both devices" message on the sender.

Unknown values: latency fields use `-1` (`ControlMessage.UNKNOWN`), the signed A/V offset uses
`Int.MIN_VALUE` (`UNKNOWN_SIGNED`), and `Ping.offsetUs` uses `Long.MIN_VALUE` (`NO_OFFSET`). Control payloads: big-endian, strings = u16 length + UTF-8, lists = u8 count; media packets carry raw codec output. Unknown message types are skipped by readers; invalid field values are `MALFORMED_PAYLOAD`.

Fixed 16-byte header, big-endian:

| Offset | Field | Type | Notes |
|---|---|---|---|
| 0 | magic | u16 | `0x5252` ("RR") |
| 2 | version | u8 | `PROTOCOL_VERSION` = 5 |
| 3 | type | u8 | see message table |
| 4 | flags | u8 | bit0 = keyframe, bit1 = codec config |
| 5 | reserved | u8 | 0 |
| 6 | payloadLen | u32 | configurable maximum (default 8 MiB) → above = protocol error |
| 10 | ptsUs/seq | u48 | pts in µs for frames, sequence number for control messages |

**Timebase.** Media pts are sender `CLOCK_MONOTONIC` microseconds (`System.nanoTime() / 1000`), the
clock `VirtualDisplay`/Surface timestamps use for encoder output. Audio pts (M5) use the same clock.
Since v3 PING/PONG use it too, so the receiver can place a frame's pts on its own clock:
`rtt = (t4−t1) − (t3−t2)`, `offset (receiver − sender) = ((t2−t1) + (t3−t4)) / 2`, where t1/t4 are
the sender's send/receive times and t2/t3 the receiver's read/write times (t3 is stamped by the
control thread right before writing, so outbox queuing does not bias the estimate). `ClockSync`
keeps the lowest-RTT sample of the last 8; the estimate is per link (suspend shifts the clocks).

Messages:

| type | Name | Direction | Payload |
|---|---|---|---|
| 0x01 | HELLO | R→S | version, device name, display resolution, refresh rate, decodable codecs (per codec: `u8` codec, `u16` max width, max height, max fps, `bool` hardware, low latency, v5: `i32` max padded area, `i64` max pixel rate), audio capabilities |
| 0x02 | START | S→R | chosen codec, width, height, fps, initial bitrate, audio format, sync mode (v2: `u8`, 0 = lip-sync, 1 = low latency) |
| 0x03 | CONFIG | S→R | CSD (VPS/SPS/PPS) as emitted with `BUFFER_FLAG_CODEC_CONFIG` |
| 0x04 | FRAME | S→R | Annex-B access unit |
| 0x05 | AUDIO_CONFIG | S→R | audio codec (PCM s16le / Opus), sample rate, channels, packet duration; Opus CSD if any |
| 0x06 | AUDIO_FRAME | S→R | audio packet (PCM or Opus) with pts in µs on the same timebase as video |
| 0x07 | VIDEO_RECONFIG | S→R | v3: `u16` generation + video block (codec, width, height, fps, bitrate). Sent in order with video: every unit after it is the new size, starting with CONFIG and a keyframe |
| 0x08 | P2P_OFFER | S→R | v4, in place of START: `string` SSID, `string` passphrase, `string` sender name. The sender's Wi-Fi Direct group, sent over the router (ADR-011 fast path); the connection then closes and no stream follows |
| 0x10 | REQUEST_KEYFRAME | R→S | — |
| 0x11 | PING | S→R | v3: `i64` t1, `i64` offset estimate (`NO_OFFSET` until known), `i32` best RTT µs |
| 0x12 | PONG | R→S | v3: `i64` t1 echoed, `i64` t2 (PING read), `i64` t3 (PONG written) |
| 0x13 | STATS | R→S | decoded fps, dropped frames, mean/p95 decode latency, audio underruns, A/V offset, video delay (v2), capture→shown mean/p95 (v3); all `i32` µs |
| 0x14 | BITRATE_HINT | R→S | reserved, ignored; no v3 peer sends it (the sender's `BitratePolicy` decides) |
| 0x7F | BYE | ↔ | reason code |

## ADR-004 — Encoder pipeline (sender)
**Accepted** (revised in M6).
- Source: `MediaProjection.createVirtualDisplay` directly onto the encoder input `Surface`
  (zero copies). Resolution: see *Auto resolution* below (`CodecNegotiator`; `refit` recomputes it
  when the source size changes).
- **Auto resolution (#34).** fps is decided first (min of both refresh rates, codec max fps, the
  user cap). The size is then the largest one with the source aspect ratio and orientation, each
  side a multiple of 8, never upscaled, that fits: the receiver display, every codec axis limit,
  the *padded* area (`ceil16(w)·ceil16(h)`, codecs work in 16×16 blocks) and `area × fps` of both
  the encoder (local) and the decoder (from HELLO), the user's optional height cap (Auto = none,
  the default), and the local encoder's exact `areSizeAndRateSupported`. Area and rate limits are
  measured at startup by `CodecProbe` (binary search of the largest 16-aligned size per aspect ratio
  with `isSizeSupported`; rate = that area × `getSupportedFrameRatesFor`), because per-axis maxima
  overstate them (Pixel 10 HEVC encoder: 3840×2176 axes, ~1920×1088 area). If no probe passes, the
  per-axis area is used. A `configure` failure that still happens keeps the ADR-010 retry path.
  `VideoLimits` and `VideoFit.fitWithin` are public for the second-screen mode (#35).
- Codec: HEVC if both sides support it in hardware; otherwise AVC. Hardware codecs only
  (`isHardwareAccelerated`).
- Parameters: `KEY_PRIORITY=0` (realtime), `KEY_BITRATE_MODE=CBR` (fallback VBR),
  `KEY_REPEAT_PREVIOUS_FRAME_AFTER` for static screens, `KEY_MAX_B_FRAMES=0`, `KEY_LOW_LATENCY`
  where supported, `PARAMETER_KEY_REQUEST_SYNC_FRAME` on demand.
- **Configure tiers** (`EncoderSettings.Tier`), tried in order until `configure` succeeds:
  1. *intra-refresh* (only with `FEATURE_IntraRefresh`): `KEY_INTRA_REFRESH_PERIOD = fps` (one
     full refresh per second spread over every frame, no keyframe spikes) and
     `KEY_I_FRAME_INTERVAL = 10` s as a safety net;
  2. *realtime*: the realtime keys with a 2 s I-frame interval;
  3. *plain*: only keys every encoder accepts.
  The accepted tier is logged and shown in the sender's stats; a name denylist exists for encoders
  whose intra-refresh output shows artefacts on device.
- **Adaptive bitrate** (`BitratePolicy`, `:core:net`, pure and clock-injected, AIMD). It lives in
  `ProjectionHost`, so its value survives reconnections.
  - *Decrease ×0.75*, at most once per 2 s, on:
    - a send-queue video drop (event-driven; the new bitrate is applied **before** the recovery
      keyframe is requested, so the IDR is already smaller);
    - queued video older than 100 ms;
    - the receiver's dropped-frame count rising;
    - the last RTT above its 30 s floor + 40 ms on 2 consecutive ticks.
  - *Increase +5 %/s* after 5 s without congestion, up to the ceiling.
  - Ceiling = negotiated (Auto) or the user's fixed bitrate; floor = max(1 Mbps, 15 % of ceiling).
  - Ticked once per second by the sender's writer thread; a new value is applied to the encoder
    on the host's `HostCtl` thread (never on the writer), and re-applied to each new encoder.
  - A size change refits the ceiling and clamps the current value; a new link resets the
    receiver-drop baseline and RTT history.

## ADR-005 — Decoder pipeline (receiver)
**Accepted.**
- `SurfaceView` (not `TextureView`) for the shortest composition path.
- `KEY_LOW_LATENCY=1` if `FEATURE_LowLatency` (API 30+); below API 30 (e.g. Fire OS 7) try known vendor keys
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
(some networks/routers filter multicast). For a direct link without the router see ADR-011
(Wi-Fi Direct).

## ADR-007 — Audio (part of the MVP)
**Accepted.** Sender audio must play on the receiver: mirroring without audio is not considered working.
- **Capture**: `AudioPlaybackCapture` (API 29+; API 28 senders mirror video only) from the same `MediaProjection` as
  video, `RECORD_AUDIO` permission, usages `MEDIA`, `GAME`, `UNKNOWN`. Known limitation: apps that
  declare `allowAudioPlaybackCapture=false` (or use non-capturable usages, e.g. calls) are not
  captured → shown in the UI, not a bug.
- **Format**: default **PCM s16le 48 kHz stereo** in 10 ms packets (~1.5 Mbps, no codec latency,
  negligible next to video on a LAN). **Opus** (MediaCodec encoder API 29+, decoder available from
  API 21, e.g. on Fire OS 7) as an option for weak networks, selected during negotiation.
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
- **Playback (receiver)**: `AudioTrack` with `PERFORMANCE_MODE_LOW_LATENCY`, minimal buffer. Measured on a Fire
  TV Stick, it still lands on the normal HDMI mixer: the audio policy has no fast output, and the
  path after our track adds ~220 ms we cannot reduce. Other receivers differ; `AvSync` measures it.
- **Jitter buffer** *(revised in M6, after device measurements)*. Over Wi-Fi, audio arrives in
  clumps 40–80 ms apart, so the level is a sawtooth. The first design (20–60 ms target, anything
  above target + 20 ms discarded at once) threw each clump away and underran on the next gap
  (~1 per second, measured on a Fire TV Stick). `JitterBuffer` now controls the **low-water mark**:
  - arrival gaps are measured in played time (audio pulled between two pushes); gaps over 150 ms
    are stalls, met with an underrun rather than covered (covering them would add their length to
    the latency for good);
  - `target` (10–60 ms) is the margin kept below the deepest recent dip: +10 ms per underrun,
    −5 ms after 30 s without one (stalls do not raise it); after an underrun, playback restarts at
    target + largest recent coverable gap;
  - every 2 s the lowest level of the last 8 s is compared with the target. The difference is
    corrected by dropping or repeating up to 4 single frames per 10 ms pull (≤ 0.8 %, inaudible),
    which also absorbs clock drift between the devices;
  - a history whose low-water is more than 50 ms above target jumps back at once, and a stall's
    backlog arriving during the restart is trimmed as it arrives.
- The receiver holds a Wi-Fi lock (`FULL_LOW_LATENCY`, `HIGH_PERF` on API 28) while its screen is
  up, so power save does not batch packets around beacons. It is a small gain; it costs nothing on
  a mains-powered TV.
- **A/V measurement**: `AvSync`, for each side, (receiver monotonic time the sample is shown/played)
  − (sender pts), smoothed; the difference is the A/V offset (positive = audio late), shown in the
  overlay and sent in STATS. The unknown clock offset between the devices cancels out. Video "shown"
  comes from `OnFrameRenderedListener` (frames are released with an explicit `System.nanoTime()`
  render time: with `releaseOutputBuffer(i, true)` some decoders echo the pts instead). Audio
  "played" comes only from `AudioTrack.getTimestamp` samples that pass `TimestampValidator`
  (call succeeded, position advanced or the same sample repeated, younger than 200 ms).
- **Sync mode** *(decided in M5, user choice in the sender settings, sent in START)*. Measured on a
  Galaxy Tab S9 → Pixel 10: video is on screen ~10 ms after arrival, audio plays ~190 ms after (capture
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
- **To verify on device**: whether audio keeps playing from the sender speakers during capture and
  how to mute it without affecting the captured stream.

## ADR-008 — Tooling
**Accepted.** Gradle Kotlin DSL + version catalog, latest stable AGP/Kotlin at scaffolding time,
Gradle running on JDK 25 LTS with Java 17 bytecode, Compose for the sender UI, minimal Views for the
receiver, Spotless (ktlint) + Android Lint, JUnit4 + Truth + kotlinx-coroutines-test for JVM tests,
AndroidX Test for instrumented tests.
*Revision (M0)*: Gradle 9.9.1, AGP 9.5.1 with built-in Kotlin and the new DSL (no
`kotlin-android` plugin; convention plugins configure `ApplicationExtension`/`LibraryExtension`),
Kotlin 2.4.21 pinned from the root build classpath, `compileSdk 37` (required by current AndroidX),
`targetSdk 36` until the app is validated on Android 17 behavior changes. Pure JVM modules compile
with `-Xjdk-release=17` / `options.release = 17` so they cannot use APIs missing on Android.

## ADR-009 — Single-app sharing and its audio
**Accepted** (M5). The choice between the entire screen and one app is made **only in the system
consent dialog** (Android 14+: `createConfigForUserChoice`); the sender UI has no share setting.
- Playback capture is **device-wide** whatever the projection shows, and Android 14–16 have no API
  that tells what the user picked in the dialog. For a one-app share, capturing everything would
  leak other apps' sound into it.
- **Requirement: the audio follows the choice automatically.** The system brings the chosen app to
  the front right after the dialog (verified on a Galaxy Tab S9, One UI / Android 16); for the entire screen
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
- When the shared app leaves the screen (`onCapturedContentVisibilityChanged`), the sender shows a
  warning: the receiver shows nothing until the user returns to it.
- Revisit when `MediaProjectionConfig` can request app-scoped audio (API 37
  `setAudioRequested`) on target senders.

## ADR-010 — Reconnection and in-session reconfiguration
**Accepted** (M6). The sender keeps mirroring across network hiccups, receiver restarts and size
changes without asking the user to share again.

**Sender: two lifetimes.**
- `ProjectionHost` lives as long as the `MediaProjection`. It owns:
  - the **single** VirtualDisplay (Android 14+ allows one `createVirtualDisplay` per projection,
    and a consent token is single-use);
  - audio capture and the share scope (both decided once, ADR-009);
  - the `BitratePolicy`;
  - the current source size.
- `StreamLink` is one TCP connection: socket, handshake, encoder, `SendQueue`, writer, reader and
  `ClockSync`.
- Between links the VirtualDisplay is detached with `setSurface(null)` before the encoder closes.
  Captured audio is dropped (its sink points at the streaming link's queue, or nowhere), and
  `AUDIO_CONFIG` is sent again on each link.

**Reconnection.**
- The existing `SessionStateMachine` drives the loop. Backoff is 250 ms, then 500 ms (capped).
  Retries use a 1 s connect and a 2 s handshake timeout (the receiver may first have to preempt
  its previous session).
- It gives up when one outage lasts 30 s, counted from the first failure since the last
  *healthy* link: one whose receiver reported decoded frames (STATS). A handshake alone does not
  reset the outage, so a receiver that accepts and then fails cannot keep the loop alive forever.
- A first connection that never completed its handshake is not retried: a receiver that never
  answered is a user error. Once one handshake succeeded, later failures are retried.
- The sender shows "Reconnecting…" while it keeps the projection.
- `ReconnectPolicy.classify` decides between giving up (*final*) and trying again (*retry*):

| Cause | Decision |
|---|---|
| Local stop (user, projection revoked or stopped, including screen lock on Android 15 QPR1+) | Final (decided by the host) |
| Receiver `BYE(USER_STOPPED)`, `BYE(INCOMPATIBLE)`, `BYE(PROJECTION_REVOKED)`, incompatible handshake | Final |
| Encoder failure | Retry, at most 3 times per session |
| Receiver `BYE(NORMAL / BUSY / ERROR)`, I/O error, read timeout | Retry |

**Receiver.**
- A **3 s read timeout** after the handshake: the sender PINGs every second, so 3 s of silence
  means it is gone.
- **Preemption** in `ReceiverServer`: a new connection from the active session's own address
  replaces that session instead of being refused as `BUSY`. Its old link may still look alive for
  up to 3 s. The new session first completes its handshake (so the sender's handshake timeout
  never waits on it), then waits up to 1.5 s for the old one to release its decoder (a latch counted
  right after the decoder closes), since a Surface takes one codec at a time.
- Leaving the receiver (Back) sends `BYE(USER_STOPPED)`, so the sender stops. Backgrounding it
  sends `BYE(NORMAL)`, so the sender keeps retrying and resumes if the receiver app returns within 30 s.

**Size changes (rotation, one-app window resize).**
- *Source.* On API 34+ the only source is `MediaProjection.Callback.onCapturedContentResize`
  (entire screen and one app). Below 34 it is a `DisplayListener` on the default display.
- *Debounce.* Changes equal to the current size are ignored. Others are debounced 300 ms on the
  host's `HostCtl` thread.
- *Sender swap* (under the host's video lock, so it never interleaves with a link starting or
  ending):
  1. `CodecNegotiator.refit` computes the new size (same codec and fps) with the Auto rule of ADR-004 (and the
     local encoder check) and the policy refits its
     ceiling.
  2. `setSurface(null)` → `encoder.close()` → `queue.resetVideo(requestKeyframe = false)`.
  3. `VIDEO_RECONFIG` is queued in the **video lane**, so it is ordered after old frames and never
     dropped.
  4. `VirtualDisplay.resize` → new encoder at the policy's current bitrate → `setSurface(new)`.
  5. A rotation during a handshake is applied right after it.
- *Receiver rebuild.* On VIDEO_RECONFIG the receiver:
  1. closes the delay line and the decoder;
  2. creates a new decoder, plus a new delay line sized for the new bitrate;
  3. keeps the lip-sync delay and the A/V sync state;
  4. updates the screen's aspect ratio.

  Video still in the delay line (up to D ms of the old size) is discarded.

*Rejected:*
- re-running consent on reconnect: it needs the user, and tokens are single-use;
- a dummy 1×1 SurfaceTexture between links: a never-drained consumer stalls the producer;
- a `ConnectivityManager.NetworkCallback` to retry as soon as Wi-Fi returns: deferred; the 500 ms
  backoff already reconnects within ~1 s of the network coming back;
- relying on adaptive playback for size changes: unreliable on the Fire TV Stick decoder, and it mixes sizes in the
  delay line.

## ADR-011 — Wi-Fi Direct link (opt-in)
**Accepted** (#21). Router mode (ADR-006) stays the default. A **"Connect via Wi-Fi Direct"**
button on the tablet links it straight to the TV; once the devices have an IP path, the session is
exactly the router one (same `ReceiverServer`, handshake and protocol). The only wire addition is
P2P_OFFER (v4), the router fast path below.

**Topology B′: the tablet is the group owner, the TV joins it.** Measured in the #21 spike
(Tab S9 SM-X710 API 36 → Fire TV Stick AFTSSS API 28):
- A TV-owned group (topology A) is persistent and 2.4 GHz only on Fire OS 7, and the tablet (no
  DBS) time-slices it with its 5 GHz router link: the stalls this feature removes would remain.
- The tablet creates the group with `WifiP2pConfig.Builder` on **its own station channel**
  (`setGroupOperatingFrequency`, 5220 MHz in the measurements), so it never hops channels. Same
  room: audio played ~330 ms (router ~460), writes ≤ 12 ms, 2 underruns in ~6 min.
- **Channel rule, "5 GHz when possible" (#32).** Most senders have one radio: a group on another
  band than the station link makes it time-slice. A Pixel 10 (API 37, no DBS) on a 2.4 GHz router
  link with a 5 GHz group never answered the Fire TV's authentication (TV at -55 dBm, three tries,
  nothing logged on the Pixel). `P2pPolicy.channelFor(station, crossBandGo)`:
  station on 5 GHz → that frequency; station on 2.4 GHz → `GROUP_OWNER_BAND_5GHZ` only if
  `crossBandGo`, else that 2.4 GHz frequency (channel 14 allows no P2P → `GROUP_OWNER_BAND_2GHZ`);
  no station or 6 GHz → `GROUP_OWNER_BAND_5GHZ` (Fire OS 7 cannot join on 6 GHz). `crossBandGo` =
  `isDualBandSimultaneousSupported()` and `getUsableChannels(5 GHz, OP_MODE_WIFI_DIRECT_GO)` not
  empty, unless a cross-band group already timed out with nobody joined in this process (the
  signals lied; the service's retry then shares the station channel). A refused frequency (e.g.
  DFS) falls back to the same **band**, not the same channel: time slicing is then expected. On a
  2.4 GHz direct link the sender suggests moving to the router's 5 GHz Wi-Fi.
- Known limits of the rule: channels 12/13 (2467/2472 MHz) are invisible to receivers set to a US
  region; an API 29+ single-radio receiver keeps its router link, so if that link is on 2.4 GHz and
  a cross-band sender picks 5 GHz the receiver time-slices (fixing it needs the receiver to
  advertise its station frequency: a protocol change). Fire OS 7 receivers leave their router and
  are unaffected.
- On API 28 the TV joins as a **legacy station** (`addNetwork`/`enableNetwork`, no dialog) and so
  **leaves its router while mirroring** (accepted in the issue); `removeNetwork` + `reconnect()`
  restore it when the session ends, or 60 s after a join no session used (a slow consent dialog). API 29+ receivers join as
  P2P clients with the same credentials and keep their router link.
- The sender needs API 35 (`WifiP2pDevice.getIpAddress`) to learn the TV's address in its group.

**Discovery and credential delivery (P2P DNS-SD, `_ririproj._tcp`, TXT `v=1`).** This record is
separate from the ADR-006 NSD TXT.
1. The tablet lists nearby devices with **plain peer discovery** (`discoverPeers` +
   `requestPeers`). Its DNS-SD queries never got an answer from a Fire TV that is not a group
   owner, while peer discovery finds it in seconds. Whether a device runs the receiver shows when
   it takes the offer (timeout message: keep the RiriProjector screen open).
2. **At the pick** (not after consent: the dialog takes several seconds), the tablet advertises an
   **offer** `{role=offer, name, ssid, psk, to=<TV's P2P device address>}`, creates the group and
   runs `discoverPeers` (listen state), restarting it only when it stops: every restart begins with
   a channel scan, during which the TV cannot reach it. The service claims this early attempt once
   consent is given; a declined consent or 45 s unclaimed closes it.
3. The TV runs service discovery while its receiver screen is up and no session streams. It
   requests **all** services: a request by type only asks for the PTR record, so the TXT carrying
   the offer never arrives. It joins an offer addressed to itself, once per SSID.
   The framework queries each peer once per round, so the TV replaces its request every 2 s, which
   re-queries known peers without a new scan (without it, 0 of 3 offers arrived in 60 s); the old
   request goes only after the new one is in, and NO_SERVICE_REQUESTS restores it.
4. The tablet polls its group until the client has an IPv4 address, then stops discovery, clears
   the offer and connects, retrying a failed first connect for 10 s (the TV appears in the group
   before its network is up). Measured: TV listed in 2–18 s, offer arrival 4–24 s after the group
   formed.
- The TV also advertises `{role=rx, name, port}` for API 29+ senders that may answer it; the
  current sender does not need it.
**Router fast path (P2P_OFFER, v4).** When the picked device is also on the tablet's router list
(same name over NSD), the tablet also sends the offer there right after the group forms: a normal
connection to the receiver's server, HELLO, then P2P_OFFER instead of START (3 attempts, 1.5 s
connect and 2 s handshake each, since the TV's 2.4 GHz link stalls). The TV accepts it only for an
`DIRECT-xx-Riri` SSID with an 8–63 character passphrase, marks it seen so the P2P copy is ignored,
and joins. Measured (5 runs): offer delivered 0.6–2 s after the pick, TV joined 4.5–6.9 s after it
(it was 8–25 s), streaming 1–2 s after consent; once the router attempt timed out and P2P discovery
delivered the offer instead. P2P discovery stays mandatory and unchanged: the fast path only races it.

- The framework can drop the TV's P2P channel (standby, Wi-Fi restart): the agent reopens it after
  2 s.

**Credentials.** The SSID (`DIRECT-xx-Riri`) and a 16-character passphrase are random per session
and live only while the session's group exists. The TXT is readable by any nearby P2P device during
the ~10 s offer window; that exposure is accepted, since the group serves one session and the
single-session server answers anyone else with BUSY. Residual risk: a device that reads the
offer and joins before the TV would get the stream, since the tablet connects to the first client
with an address (an API 28 legacy client cannot be matched to the picked P2P device address). The
tablet's status card shows the name the receiver gives in its HELLO, so a wrong receiver is visible;
binding the session to a nonce in the offer would need a protocol change and is left for later.
Logs never print the passphrase, and the tablet removes only groups it created; the TV removes saved
`DIRECT-xx-Riri` networks left behind by a power loss when its receiver screen starts.

**Session end over P2P.** The outage limit is **1.5 s** instead of 30 s (ADR-010): when the TV
leaves the group (powered off, out of range, Back) the group cannot come back by itself, so a lost
link is final and the tablet says "Lost <name> over Wi-Fi Direct". With the 3 s read timeout the
worst case is about 4.5 s.

**Where the frequency is shown.** The tablet's status card shows "Wi-Fi Direct · <MHz>". The TV
(API 28) cannot read its group's frequency, so its overlay shows only the link kind.

**Diagnostics.** Fire OS hides app log lines, so debuggable builds also append the P2P control
events to `files/p2p.log` (`run-as <package> cat files/p2p.log`).

*Rejected:*
- topology A (TV group owner): 2.4 GHz only, persistent credentials;
- topology B with PBC: an accept dialog on the TV every time;
- typing 192.168.49.1: discovery is mandatory;
- delivering the credentials over the router only: it must work without the router (the fast path
  above only races P2P discovery);
- silently falling back to the router: the user picks the link.

## ADR-012 — Settings persistence and release signing
**Accepted** (M7).

**Sender settings.**
- Stored in a private SharedPreferences file `riri_sender_settings`, as strings, with a
  `settings.version` key for future migrations. `SenderSettingsCodec` (pure, JVM-tested) validates
  every field against the choices the screen offers and falls back to the default per field, so a
  value from another version never crashes the app. `preferredAudioCodec` is not persisted.
- Max resolution (#34): `AUTO` (the default, also for an absent or unknown value) or 720/1080/1440.
  A value saved by an older version is kept, including the old default 1080 (indistinguishable from
  an explicit choice): those users switch to Auto by hand. Intent extras and saved state use 0 for
  Auto.
- The screen keeps its `rememberSaveable` state (rotation) and initialises it from the stored values;
  they are written with `apply()` whenever the selection changes (plus an idempotent write on first
  composition), never on a codec or socket thread.
- The **last receiver** is saved by `SenderActivity` once a session reaches `Streaming` with it
  (deduplicated per target), so an address that never connected is not remembered. Only
  TCP-connectable targets are kept: the Wi-Fi Direct placeholder host (ADR-011) is excluded.
  Known gap, accepted to keep `ProjectionService` out of it: if the activity is destroyed in the
  background and a session starts and ends before it returns, nothing is saved.
- **Quick start** matches the stored receiver against the live NSD list by exact host and port
  first (receiver names are device names and may repeat), then by name (a receiver's DHCP lease may
  change its IP); without a match the stored address is offered as a "Last receiver" card, hidden
  while mirroring. IPv6 literals are not remembered: the manual `host:port` field cannot express them.

**Release signing.**
- Four values, environment variables first and Gradle properties second (user-level
  `~/.gradle/gradle.properties`, outside the repo): `RIRI_KEYSTORE_FILE` (absolute path),
  `RIRI_KEYSTORE_PASSWORD`, `RIRI_KEY_ALIAS`, `RIRI_KEY_PASSWORD`. All or none: none gives an unsigned
  release build (CI, contributors); a partial set fails the build.
- `versionName` / `versionCode` come from `riri.versionName` / `riri.versionCode`; the release
  workflow derives them from the `vX.Y.Z` tag, `versionCode = X*10000 + Y*100 + Z` (Y, Z ≤ 99).
- `.github/workflows/release.yml` runs on `vX.Y.Z` tags in a `release` environment that holds the
  secrets and **requires a reviewer**. That approval is the security boundary: a tag push runs the
  workflow file of the tagged commit, so the in-workflow "tag is on `main`" check only guards against
  mistakes. Secrets are scoped to the steps that use them, checkout does not persist its token,
  Gradle caches and the configuration cache are disabled (passwords are never serialized), and the
  keystore is decoded to `$RUNNER_TEMP` and deleted afterwards. It verifies the signature with
  `apksigner` and attaches the versioned APK, a stable `ririprojector.apk` (for
  `releases/latest/download/…` links typed on a TV remote) and the R8 `mapping.txt`.
- Local signed builds should use `--no-configuration-cache` for the same reason.
- R8 full mode is on for release, with exception class names and line numbers kept. CI builds the
  release variant unsigned on every PR.

*Rejected:*
- DataStore: its asynchronous first read would show the defaults for a frame, or block the main
  thread; a handful of values written on taps does not need it;
- saving the receiver when tapped: a mistyped address would be remembered;
- `keystore.properties` / `local.properties` for secrets: blocked by repository hooks, and
  environment variables cover both CI and local builds.

## ADR-013 — Developer mode (on-device connection log)
**Accepted** (#33). Diagnosing a failed connection used to need `adb logcat` on both devices.
Developer mode, switched on **before** a share, shows each device's own connection steps on screen.

- **Model.** `DevEventLog` (`:core:net`, `dev.ririprojector.net.devlog`, pure, JVM-tested) is a
  thread-safe ring buffer of at most 300 `DevEvent`s exposed as `StateFlow<List<DevEvent>>`. Each
  event has a level (INFO/WARN/ERROR), a source and a message, and a timestamp relative to the start of
  the attempt (`CLOCK_MONOTONIC` ms, the clock of frame render times). Consecutive identical lines
  collapse into one with a repeat count. `DevLog` is the process-wide sink. It is off by default:
  each helper then costs one volatile read, and its `inline` lambda never builds the string. Turning
  it off clears the log.
- **Sources.** Every `P2pLog` line, which covers both sides of Wi-Fi Direct (ADR-011), is
  forwarded, so the P2P classes have no hooks of their own. The sender's activity, service and host,
  and the receiver's controller, add human-readable control-path lines: target, this device's band
  and RSSI, consent, TCP connect, handshake, negotiated stream, encoder and decoder, audio, first
  frame on screen, link loss and why the session ended.
- **Attempts.** The sender starts a new log when the user picks a receiver, before the early group,
  and keeps it after a failure until the next pick. The receiver has no start button, so a connection
  or an accepted offer starts a new log only if the last milestone is older than 20 s. Milestones are
  the handshake, the session end and an accepted offer, and ordinary lines never count. That way
  offer → join → connection, and a quick reconnect after a drop, stay in one log.
- **Hot path.** The encoder→socket and socket→decoder loops are unchanged. The receiver's
  frame-rendered callback stores the first render time in one field, and the 1 Hz control tick logs
  it.
- **UI.** On the sender, "Developer mode" sits in a Developer settings section (persisted as
  `dev.mode`, not part of START). The log panel shows under the status card while the log is not
  empty, with Copy and Share. On the receiver, a D-pad focusable toggle on the waiting screen
  (persisted in `riri_receiver_settings`) puts the newest 40 lines in the dog's place. While
  streaming, they show under the stats overlay. "Copy log" copies everything.
- **Scope and privacy.** Each device shows only its own log. Nothing is sent over the wire (no
  protocol change) or written to disk beyond the existing debug-only `p2p.log`. The group
  passphrase never appears, because both offer types redact it in `toString()`.
- Rejected: reading logcat from the app (needs `READ_LOGS`, and Fire OS hides app lines); sending
  events to the peer (a protocol change, and most failures happen before any socket exists);
  instance-scoped logs threaded through constructors (the early group starts before the service
  exists, and it would restructure the P2P classes).
