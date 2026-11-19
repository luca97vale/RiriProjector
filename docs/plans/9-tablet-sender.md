# Plan #9 — M4 Galaxy Tab sender, first end-to-end
Issue: #9 · Status: APPROVED

## Goal and acceptance criteria
- Compose UI: discovered receivers (NSD), manual IP entry, status, settings (codec, max resolution, fps, bitrate)
- `mediaProjection` foreground `ProjectionService` compliant with Android 14+ rules, stop from the notification, revocation handling
- Hardware `VideoEncoder` (ADR-004) with sync-frame requests and live bitrate changes
- Encoder → network on dedicated threads with the bounded `SendQueue`
- On device: stable mirroring for 10 minutes (manual), Tab S9 → Pixel 10 / Fire TV

## Approach
`:core:media`
- `EncoderSettings` (pure): builds the encoder parameter set from `VideoConfig` + the chosen
  `CodecDescriptor` — CBR when supported (else VBR), no B-frames, I-frame interval 2 s as a safety
  net (on-demand sync frames do the real work), `KEY_REPEAT_PREVIOUS_FRAME_AFTER` 100 ms so a static
  screen still produces frames, `KEY_PRIORITY` 0, `KEY_LOW_LATENCY` on API 30+, `KEY_OPERATING_RATE`.
  JVM-tested.
- `VideoEncoder`: surface-input `MediaCodec` encoder. Drain loop on a dedicated urgent-display
  thread (synchronous `dequeueOutputBuffer`, 10 ms timeout, reused `BufferInfo`) hands each output
  to a sink without copying (the sink copies into the `SendQueue` pool and the buffer is released
  right after). `requestKeyframe()` and `setBitrate()` via `setParameters` (thread-safe on
  `MediaCodec`). Retries `configure` without low-latency keys if it throws.

`:feature:sender`
- `ProjectionService` (FGS, `foregroundServiceType="mediaProjection"`): `startForeground` with a
  notification carrying a Stop action **before** `getMediaProjection`; registers
  `MediaProjection.Callback` before creating the `VirtualDisplay`; `onStop` (revocation, another
  app's projection, screen lock policy) ends the session. Stops itself when the session ends.
- `SenderSession`: owns connection, encoder, virtual display, send queue, threads. Steps: connect
  → `Handshake.asSender` → encoder → `VirtualDisplay` (`AUTO_MIRROR`) on the encoder surface at the
  negotiated size → threads:
  - writer: `SendQueue.take` → `MessageWriter`; offers a PING every second;
  - reader: REQUEST_KEYFRAME → encoder, PONG → `ClockSync`, STATS → status, BYE → end;
  - encoder drain (owned by `VideoEncoder`).
  Any error → BYE(ERROR) when possible and a clean teardown; automatic reconnection is M6.
- `SenderStatus`: process-wide `StateFlow` (idle / connecting / streaming with stats / error)
  shared by the service and the UI.
- `SenderActivity` (Compose): receiver list from `NsdBrowser`, manual `host[:port]` field,
  settings, Start/Stop, status line with fps / bitrate / RTT / receiver decode latency. Requests
  `POST_NOTIFICATIONS` (API 33+) and the projection consent per session.
- Source size: real display metrics at session start (the encoder size is negotiated for the
  current orientation; rotation handling is M6).

## Changes per module
- `:core:media`: `EncoderSettings`, `VideoEncoder`
- `:feature:sender`: `ProjectionService`, `SenderSession`, `SenderStatus`, `SenderActivity`, strings, manifest (service)

## Protocol
No change. Audio is negotiated (PCM) but not sent until M5.

## Threading and resource lifecycle
Service owns `MediaProjection` and the session; the session owns everything else and releases it
in reverse order in one `close()` (virtual display → encoder → queue → connection). Threads:
encoder drain, socket writer, socket reader. Activity only observes `SenderStatus`.

## Risks and mitigations
- Android 14+ projection rules (single-use token, FGS before projection): followed literally.
- Encoder refuses odd sizes: negotiated sizes are already 8-aligned.
- Static screen = no frames → `KEY_REPEAT_PREVIOUS_FRAME_AFTER`.

## Test strategy
JVM: `EncoderSettings`. Existing `SendQueue`/`Handshake` tests cover the transport.
Device: Tab S9 sender → Pixel 10 receiver over Wi-Fi; check fps, decode latency, keyframe recovery.

## Implementation steps
- [ ] EncoderSettings + VideoEncoder
- [ ] SenderSession + SenderStatus
- [ ] ProjectionService + manifest
- [ ] SenderActivity UI
- [ ] Tests, review, end-to-end on devices
