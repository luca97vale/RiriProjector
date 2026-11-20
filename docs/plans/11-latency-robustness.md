# Plan #11 — M6: latency measurement, adaptive bitrate, robustness
Issue: #11 · Status: APPROVED (architect: APPROVED WITH CHANGES, changes integrated)

## Goal and acceptance criteria (copied from the issue)
- [ ] Capture→decode latency in the overlay (timestamps + clock offset)
- [ ] Pure, tested `BitratePolicy` driven by send queue and STATS
- [ ] Intra-refresh where supported, low-latency vendor keys, operating rate
- [ ] Rotation/resolution change with encoder reconfiguration
- [ ] Automatic reconnection within 3 s after network loss or receiver restart
- [ ] Glass-to-glass measurement procedure documented in `docs/`

Already in place from M4 (kept, only verified): `KEY_PRIORITY 0`, `KEY_OPERATING_RATE`,
`KEY_LOW_LATENCY` (API 30+), the Qualcomm vendor low-latency key, `KEY_MAX_B_FRAMES 0`.

## Approach
**Latency.** Video pts are sender `CLOCK_MONOTONIC` µs at composition (≈ capture); the receiver
knows when each frame reaches the screen (`OnFrameRenderedListener`, receiver `CLOCK_MONOTONIC`).
v3 moves PING/PONG to `CLOCK_MONOTONIC` and makes them NTP-style with 4 timestamps
(`Pong(senderTimeUs, receiverRxUs, receiverTxUs)`, `receiverTxUs` stamped by the control thread
right before writing), so queuing inside the receiver no longer biases the offset:
`rtt = (t4−t1) − (t3−t2)`, `offset = ((t2−t1) + (t3−t4)) / 2`. PING carries the sender's current
best `offsetUs`/`rttUs`; the receiver computes capture→shown = `shownAt − (pts + offset)` per frame
into the existing `LatencyStats` (DecoderBacklog.kt, capacity = fps, locked snapshot), shows
`latency X ms (p95 Y, ±rtt/2)` in the overlay and returns mean/p95 in STATS for the tablet. In
lip-sync mode it includes the delay D (shown separately). `ClockSync` and the receiver's offset and
stats reset per link/session (suspend changes the monotonic offset).

**Adaptive bitrate.** Pure `BitratePolicy` in `core:net` (next to `SendQueue`/`ClockSync`), owned by
the sender's `ProjectionHost` so its state survives links. AIMD:
- *Decrease ×0.75* (hold-off 2 s) on: a send-queue video drop — event-driven from the
  `onKeyframeNeeded` path, applying `setBitrate` **before** `requestKeyframe()` so the recovery IDR
  is already smaller; oldest queued video older than 100 ms sampled at the tick; receiver dropped
  frames rising; `lastRtt > floorRtt + 40 ms` on 2 consecutive ticks (`ClockSync.lastRttUs` new,
  floor = min over 30 s).
- *Increase +5 %/tick* after 5 s clean, up to the ceiling.
- Ticked by a 1 s timer on HostCtl with the latest STATS snapshot (not on STATS arrival).
- Ceiling = negotiated (Auto) or the user's fixed bitrate; floor = max(1 Mbps, 0.15 × ceiling); on a
  refit the value is clamped to the new ceiling; new encoders start at the policy's current value.
- The live `BITRATE_HINT` handler is removed (message kept in the protocol, ignored).

**Encoder.** With `FEATURE_IntraRefresh`: `KEY_INTRA_REFRESH_PERIOD = fps` and
`KEY_I_FRAME_INTERVAL = 10` (with 2 s IDRs intra-refresh saves nothing). Configure in 3 tiers:
full + intra-refresh → full → plain, logging the tier and showing it in sender stats. Recovery stays
request-driven (REQUEST_KEYFRAME → IDR). Tab S9 artefact check; denylist by codec name if needed.

**Rotation / resolution change.** Source: API ≥ 34 `MediaProjection.Callback.onCapturedContentResize`
only (it covers both entire screen and one app, and fires right after capture starts); API < 34
`DisplayListener.onDisplayChanged(DEFAULT_DISPLAY)` only. Compare with the last *source* size
(so the initial identical callback is a no-op); debounce 300 ms. For a one-app share the first
callback normally differs from the screen-sized handshake: a reconfig 100–300 ms after start is
expected. Sender sequence on HostCtl:
`vd.setSurface(null)` → `oldEncoder.close()` (joins drain) → `queue.resetVideo(requestKeyframe=false)`
→ `vd.resize(w,h,dpi)` → new encoder at the policy bitrate → `queue.offerVideoControl(VideoReconfig)`
(**video lane**, never dropped, so it stays ordered after old frames) → `vd.setSurface(newSurface)`.
Receiver: on VIDEO_RECONFIG close delay line → close decoder → new decoder → new line sized by
`capacityFor(newBitrate)`, keeping `VideoDelayController` D and `AvSync`; drop non-config units
until CONFIG + keyframe; call `onStreamStarted(newParams)` so the aspect frame updates; refresh
`ReceiverState.Streaming.params`. The swap is measured on device (budget: audio gap < 150 ms; up to
D ms of old-orientation video in the delay line is discarded).

**Reconnection.** Android 14+ allows one `createVirtualDisplay` per projection, so the session is
split into a long-lived **`ProjectionHost`** (projection, one VirtualDisplay, AudioCapture, cached
`detectShare()` result, `BitratePolicy`, last source size) and a per-connection **`StreamLink`**
(socket, handshake, encoder, writer, reader). Between links the VirtualDisplay is detached with
`setSurface(null)` (documented; no compositing cost); audio is dropped (sink repointed via a
volatile queue reference) and `AudioSetup` is re-sent per link. Fallback if detach misbehaves:
`ImageReader` drained on HostCtl. The existing `SessionStateMachine` is wired in with backoff
`[250, 500]` ms capped at 500 and a time-based 30 s give-up; a pure `ReconnectPolicy.classify(cause)`
decides:

| Cause | Decision |
|---|---|
| Receiver `BYE(USER_STOPPED)`, `INCOMPATIBLE`, projection revoked/stopped (incl. screen lock on Android 15 QPR1+), user stop | Final |
| Receiver `BYE(NORMAL/BUSY/ERROR)`, IOException, read timeout | Retry |
| Encoder error | Retry, at most 3 times |

Retries use a 1 s connect and 1 s handshake timeout. The sender's read loop decodes the BYE reason.
Receiver side (the review found it never notices a vanished sender): 3 s read timeout after the
handshake (sender PINGs every 1 s); `ReceiverServer` preemption — a new connection from the same
remote address as the active session ends it and takes the slot, the new session waiting for the old
one's `done` latch before creating its decoder (one codec per Surface); otherwise BUSY.
`ReceiverActivity` sends `USER_STOPPED` when finishing (Back) and `NORMAL` when backgrounded, so the
tablet resumes if the TV app comes back within 30 s. Optional: `ConnectivityManager.NetworkCallback`
on the sender to fail fast on `onLost` and retry at once on `onAvailable`.

**Glass-to-glass.** `docs/LATENCY.md`: millisecond stopwatch page on the tablet filmed together
with the TV at 240 fps; 20 samples, median and p95, next to the overlay number.

*Rejected*: a second receiver-initiated PING (duplicates ClockSync); receiver-driven bitrate hints
(the sender sees the queue first); relying on adaptive playback for size changes (unreliable on Fire
TV, mixes sizes in the delay line); a dummy 1×1 SurfaceTexture between links (never-drained consumer
stalls the producer); re-running consent on reconnect (needs the user; tokens are one-shot).

## Changes per module
- `core:protocol` (v3): `PROTOCOL_VERSION = 3`; `Ping(senderTimeUs, offsetUs = NO_OFFSET, rttUs = 0)`;
  `Pong(senderTimeUs, receiverRxUs, receiverTxUs)`; `Stats` + `captureToShownUs`,
  `captureToShownP95Us`; new `VIDEO_RECONFIG(0x07)` `VideoReconfig(generation: Int /*u16*/, video)`;
  sentinel convention: `-1` = unknown for all latency/offset fields (avOffsetUs included, documented);
  `CodecNegotiator.refit(...)`.
- `core:net`: `ClockSync.addSample(t1, t2, t3, t4)`, `lastRttUs`, `reset()`; `BitratePolicy`;
  `ReconnectPolicy`; `SessionStateMachine` backoff/time-based give-up; `SendQueue.offerVideoControl`,
  `resetVideo(requestKeyframe)`, `oldestQueuedVideoAgeUs(nowUs)`; `SenderClient.connect(…, timeoutMs)`;
  `Handshake` timeout parameter; `ReceiverServer` same-address preemption.
- `core:media`: `EncoderSettings` intra-refresh + I-frame interval 10, tiered keys; `VideoEncoder`
  3-tier configure and reported tier; `LatencyStats` reused.
- `feature:sender`: `ProjectionHost` (HostCtl), `StreamLink`, `SenderState.Reconnecting`, stats
  (latency, bitrate, configure tier); `ProjectionService` owns the host; callbacks on HostCtl.
- `feature:receiver`: read timeout, latency, VIDEO_RECONFIG rebuild, BYE reasons, overlay.
- `docs/`: ADR-003 v3 (and the Timebase paragraph), ADR-004 revision, ADR-010 (reconnection and
  reconfiguration), `docs/LATENCY.md`.

## Protocol
v3, not interoperable with v2 (M5 mismatch path shows "update both devices"; NSD TXT `v` becomes 3
automatically). PING/PONG timestamps become `CLOCK_MONOTONIC` µs; PING +`i64 offsetUs` +`i32 rttUs`;
PONG +`i64 receiverTxUs`; STATS +`i32 captureToShownUs` +`i32 captureToShownP95Us`; VIDEO_RECONFIG
0x07 S→R (`u16 generation` + video block), transported in order with video. Round-trip and
malformed tests for each.

## Threading and resource lifecycle
- **HostCtl** `HandlerThread` (URGENT_DISPLAY), owned by `ProjectionHost`: the only thread that
  mutates the VirtualDisplay, encoders and link lifecycle; runs the debounce, retry scheduling
  (`postDelayed`, no sleeping loops) and the 1 s bitrate tick. `MediaProjection.Callback` and
  `DisplayListener` are registered on its handler.
- **Per-link I/O thread**: connect, handshake, read loop; posts `onLinkFailed(cause)` to HostCtl.
  Writer thread and encoder drain thread as today, owned by the link.
- `stop()` cancels pending retries and closes an in-flight connect socket.
- Release order per link: detach VD → close encoder → close socket; host release: link → VD →
  audio → projection.
- Receiver: VIDEO_RECONFIG on the session thread (measured; see above); preempting session waits for
  the old `done` latch.

## Risks and mitigations
- Detach semantics on One UI: verify a 30 s detach keeps the projection alive and does not trigger
  visibility callbacks; ImageReader fallback.
- Intra-refresh artefacts on Qualcomm: tiered configure + denylist.
- Reconnect storms after a deliberate stop: `ReconnectPolicy` finals.
- Decoder rebuild gap on Fire TV: measured; if over budget, move the rebuild off the read thread.
- AIMD oscillation on Wi-Fi: hold-off, clean window, RTT margin; JVM trace tests.

## Test strategy
- JVM: `BitratePolicy` (event decrease before keyframe, hold-off, recovery, floor/ceiling, refit
  clamp, noisy trace); `ClockSync` 4-timestamp with asymmetric delay; latency math with a synthetic
  offset; `ReconnectPolicy` classification; `SessionStateMachine` time-based give-up; `SendQueue`
  `offerVideoControl` ordering/never dropped and `resetVideo(false)` without callback;
  `ReceiverServer` preemption over loopback; `EncoderSettings` intra-refresh ⇒ interval 10 and tiers;
  `refit` with odd one-app sizes (1234×987) and portrait↔landscape; protocol v3 round-trips/malformed.
- Device (Tab S9 → Pixel 10, Fire TV when available): overlay latency vs glass-to-glass; throttled
  Wi-Fi → bitrate drops and recovers, no freeze > 1 s; rotate 10× → re-fits each time, swap time
  logged; kill/relaunch receiver → video back within 3 s; Wi-Fi off 10 s → back within 3 s of the
  network; 20 reconnect cycles with stable thread and fd counts; 10-minute run with stable PSS.

## Implementation steps
1. `feat(protocol)!`: v3 messages, sentinels, `refit`; tests.
2. `feat(net)`: 4-timestamp `ClockSync` on CLOCK_MONOTONIC, `lastRttUs`; SendQueue video-lane
   control, reset variant, queue age; connect/handshake timeouts; tests.
3. `feat(receiver)`: capture→shown latency (LatencyStats), overlay + STATS; 3 s read timeout.
4. `feat(net)`: `BitratePolicy` + tests; `feat(sender)`: wire it (event + timer), drop BITRATE_HINT
   handler, show bitrate.
5. `perf(media)`: intra-refresh + I-frame interval 10, 3-tier configure; tests.
6. `refactor(sender)`: `ProjectionHost` (HostCtl) / `StreamLink` split with VD detach semantics.
7. `feat(sender,receiver)`: reconnection — `SessionStateMachine` + `ReconnectPolicy`, BYE reasons,
   receiver preemption + `done` wait, `USER_STOPPED` on finish; tests.
8. `feat(sender,receiver)`: rotation/resize → VIDEO_RECONFIG; receiver rebuild and aspect update.
9. `docs`: ADR-003 v3, ADR-004 revision, ADR-010, `docs/LATENCY.md`.

## Deviations (recorded after implementation and review)
- The bitrate tick runs on the sender's writer thread (once per second, next to PING), not on a
  separate timer; the resulting bitrate is applied on the host's `HostCtl` thread.
- A first link that never completed its handshake is not retried (a receiver that never answered
  is a user error); once one did, every later failure is retried within the outage window.
- Encoder swaps, link start and link end are serialized by a `videoLock` in `ProjectionHost`
  instead of being confined to `HostCtl`, because the link's own thread starts the first encoder.
- The receiver stamps PING `t2` when the message is decoded from the stream (not at socket read);
  the difference is a few µs of parsing.
- Only a *healthy* link (receiver reported decoded frames) restarts the 30 s outage window, so a
  receiver that accepts and then fails cannot keep the loop alive.
- Retry handshake timeout is 2 s (not 1 s): the receiver completes the handshake first and then
  waits up to 1.5 s for its previous session to release the decoder.
- No `NetworkCallback`: the 500 ms backoff already reconnects within ~1 s of Wi-Fi returning.

Follow-ups (not in this milestone): cancel an in-flight `connect` on stop; wake the old receiver
writer immediately on preemption; an instrumented preemption test on device.
- Deferred from the second review: a config+key IDR is still sent as CONFIG only, so the receiver
  waits for the requested keyframe (sender requests one after each reconfigure; that doubles the
  rotation IDR); the preemption latch covers one step back only (A→B→C with B failing early).
