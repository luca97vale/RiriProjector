# Plan #10 — M5 Audio capture, transport and lip-sync
Issue: #10 · Status: APPROVED

## Goal and acceptance criteria
- Capture with `AudioPlaybackCapture` from the same `MediaProjection`, `RECORD_AUDIO` permission
- PCM s16le 48 kHz stereo in 10 ms packets with pts on the video timebase (Opus deferred, see below)
- Audio transport decision recorded in ADR-007
- Receiver: low-latency `AudioTrack`, pure JVM-tested `JitterBuffer` (underrun → silence, build-up → drop)
- A/V sync with `AudioTrack.getTimestamp`; A/V offset in the overlay and in STATS
- On device: A/V offset ≤ 40 ms, 10 minutes of music without gaps (manual)

## Transport decision (ADR-007 → Accepted)
**Same TCP connection**, audio prioritized by the existing `SendQueue` (control > audio > video).
Head-of-line blocking is bounded by one video packet: a 200 KB keyframe takes ~16 ms at 100 Mbps
effective Wi-Fi throughput, and audio packets are written between video packets. The receiver
jitter buffer (20–60 ms) absorbs it. A second socket would double the session lifecycle (connect,
handshake binding, teardown, NSD) for a gain the measurements do not need. Revisit (video
fragmentation or a second socket) only if on-device underruns correlate with keyframes.

## Approach
Timebase (ARCHITECTURE): media pts are sender `CLOCK_MONOTONIC` µs. Audio pts come from
`AudioRecord.getTimestamp(TIMEBASE_MONOTONIC)` (frame position ↔ nanoTime), falling back to
`System.nanoTime()` minus the buffered duration.

`:core:media`
- `AudioCapture`: `AudioRecord` with `AudioPlaybackCaptureConfiguration` (usages MEDIA, GAME,
  UNKNOWN), 48 kHz stereo PCM16, 10 ms reads into one reused direct buffer on an urgent-audio
  thread; hands each packet to a sink (the `SendQueue` copies it).
- `JitterBuffer` (pure): fixed byte ring. `push(pts, bytes)`, `pull(dst, n)`: below `n` bytes →
  silence + underrun counter; above target + hysteresis → drop whole packets back to target.
  Adaptive target: +10 ms per underrun (max 60 ms), −5 ms after 10 s without underruns (min 20 ms).
  Tracks the pts of the next byte to play.
- `AvSync` (pure): from video (pts, render time) and audio (pts, play time) samples computes the
  smoothed audio-minus-video offset; positive = audio late.
- `AudioPlayer`: `AudioTrack` (`PERFORMANCE_MODE_LOW_LATENCY` on API 26+, `USAGE_MEDIA`, minimal
  buffer) fed in 10 ms blocking writes by an urgent-audio thread pulling from the `JitterBuffer`;
  play time of each chunk = now + frames pending in the track (`getTimestamp`).

`:feature:sender`: request `RECORD_AUDIO`; after START send `AUDIO_CONFIG` and start
`AudioCapture` on the session's projection; if the permission is denied, mirror video only and say
so in the UI (apps with `allowAudioPlaybackCapture=false` are silent: noted in the UI text).

`:feature:receiver`: on `AUDIO_CONFIG` create `JitterBuffer` + `AudioPlayer`; `AUDIO_FRAME` → push;
`VideoDecoder` reports (pts, render time) to `AvSync`; overlay shows A/V offset, audio buffer level
and underruns; STATS carries `audioUnderruns` and `avOffsetUs`.

## Changes per module
- `:core:media`: `AudioCapture`, `JitterBuffer`, `AvSync`, `AudioPlayer`, decoder render-time hook
- `:feature:sender`: permission, `SenderSession` audio path, strings
- `:feature:receiver`: audio path in `ReceiverController`, overlay
- `docs/ARCHITECTURE.md`: ADR-007 transport accepted

## Protocol
No wire change: `AUDIO_CONFIG` and `AUDIO_FRAME` already exist (v1).

## Opus
Deferred to M6 (bandwidth policy): PCM is ~1.5 Mbps, negligible on a LAN, and adds no codec latency.

## Threading and resource lifecycle
Sender: capture thread owned by the session, released before the projection stops. Receiver:
player thread + `AudioTrack` owned by the session, released in its `finally`.

## Risks and mitigations
- Tablet speakers keep playing during capture: verify on device; document.
- `AudioTrack.getTimestamp` unavailable for the first ~100 ms: fall back to the buffer size estimate.

## Test strategy
JVM: `JitterBuffer` (underrun, build-up drop, adaptive target, pts tracking), `AvSync`.
Device: Tab S9 → Pixel 10 with music + a lip-sync clip; overlay offset; 10-minute music run.

## Implementation steps
- [x] JitterBuffer + AvSync (pure) with tests
- [x] AudioCapture + sender path
- [x] AudioPlayer + receiver path + overlay
- [ ] ADR-007 update, review, on-device verification
