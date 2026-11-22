# Plan #32 — Wi-Fi Direct fails when the sender's router link is on 2.4 GHz
Issue: #32 · Status: APPROVED (android-architect: approved with changes, integrated; approach chosen by the user: "5 GHz when possible")

## Goal and acceptance criteria (copied from the issue)
"5 GHz when possible":
- Sender with dual-band simultaneous support: the group goes on 5 GHz (station's 5 GHz channel if it has one).
- Sender on a 5 GHz router link: the group shares that channel (unchanged).
- Sender on a 2.4 GHz router link **without** dual-band concurrency: the group shares the 2.4 GHz station channel, and the sender UI suggests moving the phone to the router's 5 GHz network for a faster direct link.
- If the driver refuses the requested frequency, fall back to the same band.

- [ ] `P2pPolicy.channelFor` implements the rule above; JVM tests cover 2.4 GHz/5 GHz/6 GHz/no station × DBS on/off.
- [ ] Pixel 10 on 2.4 GHz router link → Fire TV joins over Wi-Fi Direct in ≤ 15 s in 3/3 attempts and mirrors with audio.
- [ ] Tablet on 5 GHz router link still forms the group on its station channel (no regression).
- [ ] While mirroring over a 2.4 GHz direct link, the sender shows a hint to use the router's 5 GHz band.
- [ ] `docs/ARCHITECTURE.md` ADR-011 updated with the channel rule.

## Approach
The root cause is a single-radio sender (no DBS) asked to run its station link on 2.4 GHz and a
group owner on 5 GHz. It time-slices between bands: the Fire TV's authentication frames arrive while
the radio is on 2.4 GHz and are never answered (Pixel 10 logs: zero join attempts seen, TV at
-55 dBm). The fix keeps the rule "one channel for both links" that the #21 spike established for the
tablet, and extends it to 2.4 GHz station links. 5 GHz is kept whenever it does not force channel
switching: a 5 GHz station link, a radio that can run a 5 GHz group next to a 2.4 GHz station, or no
station link at all.

Cross-band capability (`crossBandGo`) is computed by the caller and passed into the pure policy, so
the decision stays JVM-testable: `isDualBandSimultaneousSupported()` (chip DBS) **and**
`getUsableChannels(ScanResult.WIFI_BAND_5_GHZ, OP_MODE_WIFI_DIRECT_GO)` non-empty (it accounts for the
current concurrency; on `UnsupportedOperationException`/`SecurityException` the DBS flag alone
decides) **and not** `dbsUnreliable`. `dbsUnreliable` is an in-memory flag set when a cross-band
5 GHz group times out with nobody joined: the service's existing retry then uses the station
channel. All APIs are ≤ 34; the sender's Wi-Fi Direct path is API 35+. The decision and its inputs
are logged once per group (`Riri/P2p`).

Rejected alternatives:
- *Always 5 GHz* (today): fails on every single-radio sender on a 2.4 GHz router link, and even a
  successful join would stall the stream (hundreds of ms per channel switch, measured in #21).
- *Always try 5 GHz first, retry on 2.4 GHz after a timeout*: costs a 30 s failed attempt every time
  on the devices that need the fallback; the capability signals make the right choice up front (the
  timeout fallback remains only as a safety net when they lie).
- *Disconnect the sender from its router during the session*: not allowed for apps on API 29+.

## Changes per module
`:core:net` (`net/p2p/P2pPolicy.kt`):
- `GroupChannel`: add `data object Band2Ghz` (driver picks a 2.4 GHz channel); doc of `Frequency`
  generalised to any station channel.
- `fun channelFor(stationFrequencyMhz: Int?, crossBandGo: Boolean): GroupChannel`:
  - station on 5 GHz (4900..5900) → `Frequency(station)`;
  - station on 2.4 GHz (2400..2500) → `Band5Ghz` if `crossBandGo`, else `Frequency(station)`;
    2484 MHz (channel 14, no P2P allowed) → `Band5Ghz` if `crossBandGo`, else `Band2Ghz`;
  - anything else (no link, 6 GHz, invalid) → `Band5Ghz`.
- `fun fallbackFor(channel: GroupChannel.Frequency): GroupChannel` → `Band2Ghz` for a 2.4 GHz
  frequency, else `Band5Ghz` (the "driver refused the channel" path keeps the band, not the channel).

`:core:net` (`net/p2p/P2pSender.kt`, `P2pGroupOwner.open`):
- compute `crossBandGo` as above; log `group: station <f> MHz, crossBand=<b> → <channel>`; map
  `Band2Ghz` to `setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)`; use `fallbackFor` on
  refusal (logged as "different channel, time-slicing expected").
- companion `@Volatile var dbsUnreliable`: set when a group chosen with `crossBandGo` and a 2.4 GHz
  station times out with an empty client list.

`:feature:sender` (`SenderScreen.kt`, `strings.xml`):
- In `Streaming`, when `link` is on 2.4 GHz (`WifiBand.of(link.frequencyMhz) == GHZ_2_4`, from
  `:core:net`), show a hint row under the card: `sender_direct_24ghz_hint` = "Wi-Fi Direct is on
  2.4 GHz because this device uses your router's 2.4 GHz band. Connect it to your router's 5 GHz
  Wi-Fi, if it has one, for a faster direct link." Same style as the existing content-hidden hint.

`docs/ARCHITECTURE.md`: ADR-011 channel rule, capability signal and fallback, channel 12/13/14
limits, receiver-side concurrency limitation, Pixel 10 evidence.

## Protocol
No change (the receiver joins whatever SSID/channel it is offered; Fire OS 7 scans 2.4 and 5 GHz).

## Threading and resource lifecycle
Unchanged: the capability reads are synchronous binder calls made once per `open`, on the same
coroutine as `stationFrequency()`. No new resources.

## Risks and mitigations
- *Capability signals lie* (DBS reported but P2P still time-slices) → timeout with no client sets
  `dbsUnreliable`; the service's immediate retry and later sessions use the station channel.
- *Receiver-side concurrency*: an API 29+ single-radio receiver keeps its router link; if that is on
  2.4 GHz and a cross-band sender picks 5 GHz, the receiver time-slices. Fire OS 7 is unaffected (it
  leaves its router). Known limitation in ADR-011; a fix needs the receiver to advertise its station
  frequency (protocol bump) → follow-up issue.
- *Channels 12/13 (2467/2472)*: receivers set to a US region cannot see the group; documented.
  6 GHz station without cross-band → `Band5Ghz` (unavoidable: Fire OS 7 has no 6 GHz); documented.
- *2.4 GHz throughput (~30–40 Mbps on a 1x1 HT20 stick)*: device run checks for early drops at the
  start bitrate.
- *Tablet regression* → its station is on 5 GHz: same `Frequency(5220)` path, covered by tests and
  a device run.

## Test strategy
- JVM: `P2pPolicyTest` table over station {null, 0, -1, 2412, 2432, 2467, 2472, 2484, 4899, 4900,
  5220, 5900, 5901, 6115} × crossBandGo {true, false}; `fallbackFor` for 2.4, 2484 and 5 GHz.
  Update `P2pRecordsTest`.
- Manual on device (debug sender, release receiver on the Fire TV; protocol identical): Pixel 10 on
  2.4 GHz → 3 Wi-Fi Direct attempts, log line with `crossBand`, join time, audio and video on the TV,
  hint visible; tablet on 5 GHz → group on its station channel.

## Implementation steps
- [ ] `fix(net): share the 2.4 GHz station channel on single-radio senders` — policy, fallback, sender wiring, log, tests.
- [ ] `feat(sender): suggest the router's 5 GHz band on a 2.4 GHz direct link` — hint + string.
- [ ] `docs: ADR-011 channel rule for 2.4 GHz station links`.
