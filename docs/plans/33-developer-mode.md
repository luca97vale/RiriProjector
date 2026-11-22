# Plan #33 — Developer mode: live connection step log on sender and receiver
Issue: #33 · Status: APPROVED (architect: APPROVED WITH CHANGES, changes integrated)

## Goal and acceptance criteria (copied from the issue)
- [ ] Sender settings show a "Developer mode" On/Off choice in a "Developer" section; the choice
      survives an app restart (persisted in the sender settings store, decoded with a default of Off
      for unknown values).
- [ ] Receiver waiting screen shows a "Developer mode: On/Off" button reachable with the D-pad from
      the default focus and by touch; the choice survives an app restart.
- [ ] With developer mode on, starting a share over the router shows, on the sender, at least: target,
      TCP connected (ms), handshake OK with receiver name, negotiated video (codec, size, fps,
      bitrate), encoder name, audio status, "receiver is showing frames"; the receiver shows: incoming
      connection, session params, decoder, first frame shown, audio started.
- [ ] With developer mode on, a Wi-Fi Direct share shows on the sender the group frequency,
      router-offer outcome, receiver joined + IP, and on the receiver the offer received and join
      outcome; a failure (e.g. join timeout) appears as a highlighted line with the reason.
- [ ] Every line carries a timestamp relative to the start of the attempt; the log is bounded (ring
      buffer, ≤ 300 lines) and consecutive duplicate lines are collapsed with a repeat count.
- [ ] The log stays visible after a failed or ended share until the next share starts.
- [ ] "Copy log" copies the full log as plain text.
- [ ] Developer mode off: no log panel, no change to existing screens; the per-frame encoder→socket
      and socket→decoder loops gain no allocation or logging in either mode.
- [x] The event model is pure Kotlin with JVM unit tests, lives in `:core:net` and respects the
      dependency rule.
- [ ] Layouts work at 360 dp width in both orientations; all strings in `strings.xml`, English.
- [x] `assembleDebug testDebugUnitTest spotlessCheck lintDebug` green.

## Approach
A **process-wide, opt-in event sink** in `:core:net` (`dev.ririprojector.net.devlog`):
`DevEventLog` is a pure, thread-safe bounded log (ring buffer of `DevEvent`, timestamps relative to
the current attempt's start, consecutive duplicates collapsed, exposed as `StateFlow<List<DevEvent>>`).
`DevLog` is the singleton the app writes to: a `@Volatile enabled` flag plus `inline` lazy
`info/warn/error(source) { message }` helpers, so a disabled developer mode costs one volatile read
and never builds a string.

`:core:net` is the lowest module that both features already depend on and that has coroutines
(`StateFlow`); `:core:protocol` is about the wire format and has no coroutines. Wi-Fi Direct events
already funnel through the internal `P2pLog` object (`P2pSender`, `P2pReceiverSide`, both sides of the
group and join). **One hook in `P2pLog.i/w`** forwards every one of them to `DevLog`, so
`P2pPolicy.kt`/`P2pSender.kt`/`P2pReceiverSide.kt` (being changed concurrently for #32) are not
touched at all. The features add a few human-readable lines at the control-path points that `P2pLog`
does not cover: share start (target, station band/frequency/RSSI), consent, TCP connect/retries,
handshake, negotiated stream, encoder/decoder, audio, first frame shown, link lost/reconnect/end
reasons.

**Attempt boundaries.** The sender calls `DevLog.begin(title)` when the user picks a receiver
(before the early Wi-Fi Direct group starts, ADR-011), which clears the log and resets t0. The log
stays until the next pick, so a failure stays readable. The receiver has no "start" button: it calls
`DevLog.beginUnlessRecent(title, windowMs = 20 s)` on an incoming connection and on an accepted
offer; a new attempt clears the log only if the previous attempt mark is older than the window
(longer than the 15 s station join), so offer → join → connection → handshake stay in one log, and
the LAN offer connection followed by the P2P join is one attempt too.

Rejected alternatives: (1) a logcat reader (`logcat -d` from the app) — needs `READ_LOGS` or only
sees own lines inconsistently, and Fire OS hides app lines (see `P2pLog`); (2) sending events to the
other device — a protocol change for a diagnostics view, and the failures we care about happen
before any socket exists; (3) instance-scoped logs passed through constructors — `P2pLog` is a
static object and `EarlyDirectLink` starts the group before the service exists; threading a log
through every class would restructure the P2P files under concurrent change.

## Changes per module

### `:core:net` — new package `dev.ririprojector.net.devlog`
```kotlin
enum class DevLevel { INFO, WARN, ERROR }

/** One line; [atMs]/[lastAtMs] relative to the attempt start; [repeat] > 1 for collapsed duplicates. */
data class DevEvent(val atMs: Long, val level: DevLevel, val source: String, val message: String,
                    val repeat: Int = 1, val lastAtMs: Long = atMs)

class DevEventLog(capacity: Int = DEFAULT_CAPACITY /* 300 */, clockMs: () -> Long = monotonic) {
    val events: StateFlow<List<DevEvent>>
    fun begin(title: String)                                   // clear, t0 = now, INFO title
    fun beginUnlessRecent(title: String, windowMs: Long): Boolean
    fun add(level: DevLevel, source: String, message: String, atMs: Long = clockMs())  // absolute ms
    fun clear()
    fun format(): String                                      // plain text, one line per event
    companion object { fun formatLine(e: DevEvent): String }  // "+1.234 s  W  [P2P] …  (×3, last +5.678 s)"
}

object DevLog {
    @Volatile var enabled: Boolean                            // turning off clears the log
    val events: StateFlow<List<DevEvent>>
    inline fun info(source: String, message: () -> String)
    inline fun warn(source: String, message: () -> String)
    inline fun error(source: String, message: () -> String)
    fun begin(title: String); fun beginUnlessRecent(title: String, windowMs: Long)
    fun format(): String
    fun nowMs(): Long                                         // the log's clock (CLOCK_MONOTONIC ms)
}
```
- `P2pLog.kt`: `i()` → also `DevLog.info(SOURCE_P2P) { message }`, `w()` → `DevLog.warn(...)`. Two
  lines; no other P2P file changes.

### `:feature:sender`
- `SenderSettings`: new field `developerMode: Boolean = false`; `SenderSettingsCodec.KEY_DEV_MODE =
  "dev.mode"`, decoded with `toBooleanStrictOrNull() ?: false`, `encodeDeveloperMode(Boolean)`;
  `SenderSettingsRepository.saveDeveloperMode(Boolean)`. Not part of `StreamPreferences` (protocol
  model, sent in START).
- `SenderActivity`: sets `DevLog.enabled` from the stored choice (onCreate and on change);
  `requestProjection` calls `DevLog.begin("Share to <name> (<host:port> | Wi-Fi Direct)")` and logs
  this device's Wi-Fi band/frequency/RSSI (`WifiManager.connectionInfo`, frequency/RSSI only, same
  deprecation suppression as `P2pSender`); consent shown / granted / declined.
- `ProjectionService`: projection obtained or failed; Wi-Fi Direct: claimed early attempt or fresh
  one, early attempt failed + retry, link ready (ms, group frequency + band, receiver IP), group lost,
  Wi-Fi Direct failure with the user-facing reason.
- `ProjectionHost`: per link: connecting to host:port, connect retry with reason, TCP connected (ms),
  handshake OK (receiver name, ms), negotiated video/audio/sync, encoder name and tier, audio status,
  streaming; on the first STATS with decoded frames (`healthy` false→true): "Receiver is showing
  frames"; peer BYE, link failure reason, reconnecting attempt n, session ended (reason or normal).
- `SenderScreen`: new "Developer" settings section with a "Developer mode" On/Off `Choice` and a
  hint; new `DevLogPanel(events, onCopy, onShare)` under the status card when developer mode is on
  and the state is not Idle (Connecting, Reconnecting, Streaming, Ended). Monospace lines, WARN/ERROR
  coloured, bounded height (≤ 360 dp) with its own scroll that follows the newest line, "Copy log"
  (clipboard) and "Share" (`ACTION_SEND` text/plain chooser) buttons. Works at 360 dp (buttons in a
  wrapping row, text wraps).
- `strings.xml`: section/label/hint/panel title/copy/share/copied strings.

### `:feature:receiver`
- `ReceiverDevMode` (tiny, in feature): reads/writes `dev.mode` in a private SharedPreferences file.
- `ReceiverActivity`: `DevLog.enabled` from the stored choice in `onStart`; collects `DevLog.events`
  in `scope` while started and renders the last lines (spannable, WARN/ERROR in coral) into two
  views: the waiting panel's log slot and a streaming log view under the stats overlay (shown only
  with the overlay, i.e. OK/tap, so the stream is not covered by default). The OK/ENTER key handler
  keeps toggling the overlay only when the root has focus, so the new buttons get their click.
- `WaitingPanel`: a second 10-foot button "Developer mode: Off/On" (same focus style as "Use this
  device as a sender", factored into one `pillButton()`), and a "Copy log" button visible when on.
  When on, the log view takes the dog loader's slot (left on landscape, top on portrait, bounded
  height), so nothing overlaps at 360 dp.
- `ReceiverController`: server listening + NSD name; port busy/bind failure; Wi-Fi Direct
  unavailable; offer accepted → `beginUnlessRecent`, join outcome with `P2pFailure` reason; LAN offer
  ignored; incoming connection → `beginUnlessRecent`; handshake outcome (offer or stream params),
  decoder name + low-latency flag, preempt wait, no surface; streaming; first frame shown (exact time,
  see threading); audio config and "audio playing" (first STATS tick with buffered audio); sender
  silent, session failed/ended.
- `strings.xml`: toggle on/off labels, copy log, copied toast, log title.

## Protocol
No change. Each device shows its own log; `PROTOCOL_VERSION` stays 4.

## Threading and resource lifecycle
- `DevEventLog.add/begin/clear` are `@Synchronized`; each publishes an immutable snapshot list to a
  `MutableStateFlow` (one small allocation per event, control path only: a few events per second at
  most during set-up, ~0 while streaming). Callers on any thread (host thread, HostCtl, main, P2P
  callbacks, session/control threads) never block on I/O.
- Hot paths untouched: the sender writer loop, encoder sink and the receiver read loop get no new
  code. The receiver's frame-rendered callback (codec thread, once per frame) gains one
  `if (firstShownUs == 0L) firstShownUs = shownAtUs` field write — no allocation, no logging, no
  `DevLog` access; the 1 Hz control tick logs it once with its exact timestamp
  (`DevLog` clock = `System.nanoTime()` ms, the same clock as `shownAtUs`).
- Collectors: sender UI via `collectAsStateWithLifecycle` (only when developer mode is on);
  receiver via a `Job` started in `onStart`, cancelled in `onStop`. No new threads, no resources to
  release; the singleton holds at most 300 short strings.

## Risks and mitigations
- **Log noise from the receiver agent** (`agent: record …` on every DNS-SD response): consecutive
  duplicates collapse into one line with a count; the buffer is bounded.
- **Concurrent #32 edits in `P2pPolicy.kt`/`P2pSender.kt`**: not touched; the only P2P edit is the
  two-line hook in `P2pLog.kt`.
- **Sensitive data in a copied log**: the passphrase never appears: `P2pOfferRecord.toString()` and
  `ControlMessage.P2pOffer.toString()` already redact it (`psk=***`), and new lines never print
  either record's fields beyond name and SSID.
- **Process-wide state** (sender and receiver activities in one process): each activity applies its
  own stored choice in `onStart`/`onCreate`; only one role runs at a time.
- **TV focus**: OK/ENTER currently toggles the overlay from `onKeyDown` regardless of focus; the
  handler checks that no button has focus first, so D-pad users can press the new buttons.

## Test strategy
- JVM (`core/net/src/test/.../devlog/DevEventLogTest.kt`): relative timestamps with a fake clock;
  capacity bound drops the oldest; `begin` clears and resets t0; `beginUnlessRecent` inside/outside
  the window; consecutive duplicate collapse (count, `lastAtMs`) and non-consecutive kept;
  explicit `atMs` before t0 clamps to 0; `format`/`formatLine` exact output; `DevLog` disabled →
  lambda never invoked and nothing recorded, turning off clears; concurrent adds from several
  threads keep the bound and lose no event under the cap.
- JVM (`SenderSettingsCodecTest`): developer mode default Off, round-trip, invalid value → Off,
  unrelated prefs unchanged.
- Build/lint/spotless green.
- Manual on devices (tablet 192.168.1.6, Fire TV 192.168.1.7 via adb): developer mode on both, a
  router share and a Wi-Fi Direct share; screenshots of both logs (`adb exec-out screencap`); check
  developer mode off shows the unchanged screens.

## Implementation steps
- [x] 1. `:core:net` devlog model (`DevEventLog`, `DevLog`) + `P2pLog` hook — `feat(net): developer-mode event log`
- [x] 2. Sender settings field + codec + repository — `feat(sender): persist developer mode`
- [x] 3. Sender events (activity, service, host) — `feat(sender): log connection steps in developer mode`
- [x] 4. Sender UI: settings section + `DevLogPanel` — `feat(sender): developer mode toggle and live log panel`
- [x] 5. Receiver events (controller) — `feat(receiver): log connection steps in developer mode`
- [x] 6. Receiver UI: toggle, log views, copy — `feat(receiver): developer mode toggle and live log`
- [x] 7. Tests (test-writer) — `test: developer mode event log and settings`
- [x] 8. Docs: ARCHITECTURE note (ADR-013 developer mode) — `docs: developer mode`

## Architect review (android-architect: APPROVED WITH CHANGES — integrated)
1. Sender panel: shown when developer mode is on **and the log is not empty**, whatever the state —
   the early Wi-Fi Direct group and the consent dialog run while `SenderStatus` is still Idle.
2. Receiver attempt boundary: `beginUnlessRecent` compares with the last **milestone**
   (`DevLog.touch()` at handshake, session end and accepted offer), not with every line (the
   repeating `agent: record …` lines must not keep an old log alive). Inside the window the title is
   appended as a separator instead of clearing.
3. `firstShownUs` is a `@Volatile` field of `Session` (per session); a VIDEO_RECONFIG does not log it
   again. Fallback when a decoder never reports render times: after 2 ticks with decoded frames,
   log "first frame decoded (render time unknown)".
4. TV focus: no change to `onKeyDown` (a focused clickable view consumes OK/ENTER). Instead: when
   streaming hides the panel, focus goes back to the root; explicit `nextFocusUp/Down` between the
   panel buttons.
5. `DevLog.enabled` clears only on a true→false transition.
6. The inline helpers reach a `@PublishedApi internal` log.
7. The receiver collector runs only while developer mode is on (started/cancelled in
   `onStart`/`onStop` and on toggle) and renders only the last ~40 lines.
Optional taken: healthy false→true logged on the reader thread; "copied" toast only below API 33;
streaming log view capped at 40 % of the screen height; ADR-013 records scope and redaction.
