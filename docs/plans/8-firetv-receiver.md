# Plan #8 — M3 Fire TV receiver with low-latency decoder
Issue: #8 · Status: APPROVED

## Goal and acceptance criteria
- Full-screen leanback `ReceiverActivity` with `SurfaceView`, keep-screen-on, stats overlay toggled from the remote
- Low-latency hardware `VideoDecoder` (ADR-005), async mode, CONFIG/keyframe handling, drop until IDR on backlog + REQUEST_KEYFRAME
- HELLO with the device's real capabilities (`MediaCodecList`)
- NSD registration on start, unregistration on exit
- Backlog/drop logic tested on the JVM

## Approach
`:core:media`
- `CodecCatalog` (pure): from a list of `CodecDescriptor(name, mime, isEncoder, hardware, lowLatency,
  secure, maxWidth, maxHeight, maxFps)` derive the `VideoCodecCapability` lists for HELLO / sender
  negotiation and pick the best codec **name** for a mime: non-secure, hardware first, low-latency
  first (Qualcomm exposes dedicated `*.low_latency` decoders, Fire TV MediaTek exposes the feature).
- `AndroidCodecs`: builds descriptors from `MediaCodecList(REGULAR_CODECS)`
  (`isHardwareAccelerated` on API 29+, name heuristics on API 28; `FEATURE_LowLatency` on API 30+ or
  `low_latency` in the name).
- `DecoderBacklog` (pure): counts access units queued to the codec but not yet rendered; above
  `maxInFlight` (default 3) it drops non-key frames until the next keyframe and requests one once per
  episode. Also drops everything before the first CONFIG+keyframe after (re)configuration.
- `VideoDecoder`: `MediaCodec` in async mode on a dedicated `HandlerThread` (urgent display priority).
  Input buffer indices go to a bounded queue; the network thread copies each access unit straight
  into a codec input buffer (one copy, no intermediate queue). Outputs are released with
  `render = true` immediately. Low-latency format keys: `KEY_LOW_LATENCY` (API 30+ when supported),
  `KEY_PRIORITY = 0`, `KEY_OPERATING_RATE`, plus vendor keys
  `vendor.qti-ext-dec-low-latency.enable` and `vendor.low-latency.enable` (ignored when unknown).
  Decode latency (queue → output callback) feeds a `LatencyStats` window (mean, p95).
  Recoverable/transient `CodecException`s reset the codec and request a keyframe.

`:feature:receiver`
- `ReceiverActivity`: black full-screen, `SurfaceView` in an aspect-fit container sized to the stream,
  `FLAG_KEEP_SCREEN_ON`, overlay `TextView` toggled with DPAD center / menu / info keys, status text
  while waiting.
- `ReceiverController`: owns `ReceiverServer` + `NsdPublisher` for the activity's started lifetime;
  per connection runs a session thread: HELLO (real decoders + display mode) → START → configure
  decoder → read loop (CONFIG/FRAME → decoder, PING → PONG, BYE → end, audio ignored until M5).
  STATS once per second. Writes are serialized with a lock (low volume on the receiver side).
  `Surface.setFrameRate(fps)` on API 30+ to avoid judder.

## Changes per module
- `:core:media`: `CodecCatalog`, `CodecDescriptor`, `AndroidCodecs`, `DecoderBacklog`, `LatencyStats`, `VideoDecoder`
- `:feature:receiver`: `ReceiverActivity`, `ReceiverController`, `AspectFrameLayout`, `ReceiverStats`, strings

## Protocol
No change.

## Threading and resource lifecycle
Activity `onStart` → controller start (server + NSD); `onStop` → stop everything (Fire TV may kill
background apps; a new sender connection simply restarts). Decoder lives between START and session
end; surface loss (surfaceDestroyed) ends the session. Threads: accept, session reader, codec
callback HandlerThread, stats timer.

## Risks and mitigations
- Fire OS 7 without `KEY_LOW_LATENCY`: vendor keys + backlog cap of 3 frames.
- Some decoders reject `KEY_OPERATING_RATE` above their max: set it to the stream fps, retry without
  low-latency keys if `configure` throws.

## Test strategy
JVM: `CodecCatalog` selection/mapping, `DecoderBacklog` drop/keyframe logic, `LatencyStats`.
Device: tablet in receiver mode (`--es mode receiver`) as a stand-in until the Fire TV is attached;
end-to-end verification in M4.

## Implementation steps
- [x] CodecCatalog + AndroidCodecs
- [x] DecoderBacklog + LatencyStats
- [x] VideoDecoder
- [x] Receiver activity, controller, overlay
- [x] Tests and review
