# Plan #7 — M2 TCP transport, NSD discovery, session state machine
Issue: #7 · Status: APPROVED

## Goal and acceptance criteria
- `ReceiverServer` (one session at a time) and `SenderClient` with `TCP_NODELAY`, timeouts, reduced send buffer
- HELLO → START handshake over a real socket, PING/PONG with RTT and clock offset estimate
- `NsdPublisher`/`NsdBrowser` (`_ririproj._tcp`) with Flow APIs and manual IP fallback
- Pure `SessionStateMachine` with timeouts and reconnection backoff
- Bounded send queue dropping non-key frames and requesting a keyframe
- JVM tests over loopback sockets: handshake, remote close, timeout, backpressure

## Approach
Everything except NSD is plain `java.net` + pure Kotlin inside `:core:net`, so it is unit-tested
on the JVM over loopback sockets. NSD is a thin Android adapter exposing `Flow`s.

- `SocketTuning.apply(socket, sendBufferBytes)`: `TCP_NODELAY`, small `SO_SNDBUF`, DSCP AF41
  (`trafficClass = 0x88`), `SO_KEEPALIVE`. Failures to set QoS are ignored (not all stacks allow it).
- `Connection` owns one `Socket` plus its `MessageReader`/`MessageWriter`; `close()` is idempotent
  and unblocks the reader thread.
- `ReceiverServer`: accept loop on a dedicated thread. While a session is active, extra clients get
  `BYE(BUSY)` and are closed. `stop()` closes the server socket and the active connection.
- `SenderClient.connect(endpoint, timeoutMs)` → `Connection`.
- `Handshake`: receiver sends HELLO immediately after accept; sender reads it (timeout), negotiates
  via `CodecNegotiator`, sends START (or `BYE(INCOMPATIBLE)`); receiver waits for START (timeout).
  Implemented as two pure-ish functions over a `Connection`, testable over loopback.
- `ClockSync` (pure): NTP-style estimate from PING/PONG samples, keeping the min-RTT sample in a
  sliding window of 8; exposes `rttUs` and `offsetUs` (receiver clock − sender clock).
- `SendQueue` (pure, thread-safe): bounded by packet count and bytes, priority to control and
  audio packets over video; on overflow drops queued non-key video frames and enters
  "wait for keyframe" mode (all further non-key frames dropped, `onKeyframeNeeded` fired once)
  until a keyframe is enqueued. Uses a `BufferPool` of reusable byte arrays so the encoder thread
  can release codec buffers immediately.
- `SessionStateMachine` (pure): `Idle → Connecting → Handshaking → Streaming → Reconnecting →
  Closed`; events `Connect`, `Connected`, `HandshakeDone`, `IoError`, `Stop`, `Tick(nowMs)`;
  backoff 250/500/1000/2000 ms (cap) so a reconnection fits in the 3 s target; gives up after
  a configurable number of attempts. Emits side effects as returned `Action`s, so it has no I/O.
- `NsdPublisher` / `NsdBrowser`: `NsdManager` wrappers with `callbackFlow`, TXT records
  `v`, `name`; browse holds a `WifiManager.MulticastLock` while active; resolves serialized.
  `ReceiverEndpoint(name, host, port)` is the shared model, also used for manual IP entry.

## Changes per module
`:core:net` (package `dev.ririprojector.net`): `SocketTuning`, `Connection`, `ReceiverServer`,
`SenderClient`, `Handshake`, `ClockSync`, `BufferPool`, `SendQueue`, `SessionStateMachine`,
`ReceiverEndpoint`, `nsd/NsdPublisher`, `nsd/NsdBrowser`.

## Protocol
No change.

## Threading and resource lifecycle
Accept loop and per-connection reader on dedicated threads owned by the caller's session object;
`Connection.close()` from any thread. `SendQueue` is the only cross-thread structure
(lock + condition). NSD listeners are unregistered when the flow collector is cancelled.

## Risks and mitigations
- Multicast filtered → manual endpoint entry; MulticastLock while browsing.
- `NsdManager.resolveService` concurrency limits on API < 34 → resolve one service at a time.

## Test strategy
JVM: loopback handshake success/incompatible/timeout, busy second client, remote close → reader
returns null, `ClockSync` math, `SendQueue` priority/drop/keyframe-wait/bytes bound,
`SessionStateMachine` transitions and backoff schedule. NSD: manual on device.

## Implementation steps
- [x] Socket tuning, Connection, server/client
- [x] Handshake and ClockSync
- [x] BufferPool and SendQueue
- [x] SessionStateMachine
- [x] NSD publisher/browser
- [x] Tests and review
