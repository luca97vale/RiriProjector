# Plan #6 — M1 wire protocol v1 and codec negotiation
Issue: #6 · Status: APPROVED

## Goal and acceptance criteria
- 16-byte big-endian `FrameHeader` per ADR-003, `PROTOCOL_VERSION`
- Typed messages: HELLO, START, CONFIG, FRAME, AUDIO_CONFIG, AUDIO_FRAME, REQUEST_KEYFRAME, PING,
  PONG, STATS, BITRATE_HINT, BYE
- `MessageWriter`/`MessageReader` over streams with reusable buffers and a `payloadLen` limit
- `CodecNegotiator` picks video codec (HEVC > AVC), resolution, fps, bitrate and audio codec
- Tests: round-trip of every message, byte-by-byte fragmented stream, corrupted header, unknown
  version, payload over the limit, negotiation matrices

## Approach
All code lives in `:core:protocol` (pure Kotlin/JVM, `-Xjdk-release=17`).

Two kinds of traffic with different cost profiles:
- **Media packets** (CONFIG, FRAME, AUDIO_FRAME) are the hot path (60+ fps video, 100 pps audio).
  They are never materialized as objects: `MessageWriter.writeMedia(type, flags, ptsUs, ByteBuffer)`
  writes header + payload straight to the stream; `MessageReader.read()` fills a single reusable
  `Packet` (header fields + growable payload array). Zero allocations per packet in steady state.
- **Control messages** (HELLO, START, AUDIO_CONFIG, REQUEST_KEYFRAME, PING, PONG, STATS,
  BITRATE_HINT, BYE) are rare and modeled as a sealed `ControlMessage` hierarchy encoded with a small
  big-endian `PayloadWriter`/`PayloadReader` (strings = u16 length + UTF-8, lists = u8 count).

Header: magic `0x5252`, version, type, flags (bit0 keyframe, bit1 codec config), reserved,
u32 payload length, u48 pts (µs) or sequence number. The reader validates magic, version and
length before touching the payload; violations raise `ProtocolException` with a typed `Reason`.
Clean EOF exactly at a header boundary returns `null` (peer closed); EOF anywhere else is
`Reason.TRUNCATED`. Unknown message types are surfaced as packets with `type == null` so callers can
skip them (forward compatibility) — `decodeControl()` on them throws.

Negotiation is a pure function: `CodecNegotiator.negotiate(sender, receiver, prefs)` →
`Negotiation.Success(StreamParams)` or `Negotiation.Failure(reason)`. Video codec order: user
preference, then HEVC, then AVC; only hardware encoders on the sender. Resolution: source aspect
ratio fitted inside min(receiver display, encoder/decoder max, preference max height), each side
rounded down to a multiple of 16. fps = min of all limits. Auto bitrate = pixels/s × bpp
(AVC 0.10, HEVC 0.07), clamped to [2, 40] Mbps. Audio: PCM s16le 48 kHz stereo 10 ms by default
(always playable via `AudioTrack`); Opus only if preferred and supported on both sides.

Rejected: protobuf/kotlinx-serialization (dependency + allocations on the hot path for a dozen
fixed messages), varints (fixed-width fields are simpler to validate and fuzz).

## Changes per module
`:core:protocol` (package `dev.ririprojector.protocol`):
- `MessageType`, `FrameHeader`, `HeaderFlags`, `ProtocolException`
- `PayloadWriter`, `PayloadReader`
- `ControlMessage` (+ `ControlCodec`), `Packet`
- `MessageWriter`, `MessageReader`
- `model/`: `VideoCodec`, `AudioCodec`, `VideoCodecCapability`, `AudioCodecCapability`,
  `DisplayInfo`, `VideoConfig`, `AudioConfig`, `SenderCapabilities`, `StreamPreferences`, `StreamParams`
- `CodecNegotiator`, `Negotiation`

## Protocol
Implements v1 as specified in ADR-003, which moves from Proposed to Accepted. `PROTOCOL_VERSION = 1`.

## Threading and resource lifecycle
`MessageWriter`/`MessageReader` are single-threaded by contract (one writer thread, one reader
thread per socket); documented in KDoc. They do not own the streams.

## Risks and mitigations
- Huge or hostile `payloadLen` → checked against `maxPayloadBytes` (default 8 MiB) before allocating.
- Hot-path allocations creeping in → `Packet` reuse + tests asserting the payload array is reused.

## Test strategy
JVM only: round-trip for every control message and media packet; one-byte-at-a-time input stream;
bad magic, bad version, oversize payload, truncated header/payload, clean EOF; unknown type skipped;
negotiation matrix (HEVC both, HEVC only one side, no common codec, software-only encoder,
resolution fitting and alignment, fps limits, bitrate clamp, Opus vs PCM).

## Implementation steps
- [x] Header, flags, exceptions, payload codec
- [x] Control messages and codec
- [x] MessageWriter / MessageReader with reusable Packet
- [x] Capability model and CodecNegotiator
- [x] Tests (test-writer) and ADR-003 → Accepted
