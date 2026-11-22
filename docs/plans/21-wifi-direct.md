# Plan #21 — Wi-Fi Direct mode: direct tablet↔Fire TV link from a dedicated button
Issue: #21 · Status: APPROVED (architect: APPROVED WITH CHANGES, changes integrated)

## Goal and acceptance criteria (copied from the issue)
- [ ] Router mode is unchanged: existing tests pass, and the router path is checked on the devices.
- [ ] From the button press to the first video frame on the TV in ≤ 15 s (first time, excluding
      system prompts) and ≤ 8 s on later runs.
- [ ] The receiver is found by P2P service discovery; no IP is typed or hard-coded.
- [ ] Group frequency is 5 GHz when the devices allow it. Otherwise the overlay shows 2.4 GHz and
      the reason is documented.
- [ ] Over Wi-Fi Direct (10-minute YouTube run): ≤ 0.2 audio underruns/min after the first 10 s, and
      no tablet socket write blocks > 50 ms (sender timing log).
- [ ] Capture→played audio ≤ 380 ms over Wi-Fi Direct, with the jitter buffer's cover limit tuned
      for the direct link.
- [ ] Stop and start 10 times in a row: the group is removed every time, no leaked P2P groups or
      listeners, and the TV returns to its router network.
- [ ] Turning the TV off mid-session, or walking out of range, ends the session with a clear message
      on the tablet within 5 s.
- [ ] Decisions recorded as an ADR in `docs/ARCHITECTURE.md` (group owner choice, band, discovery,
      coexistence with the router connection).

Clarification proposed for the issue: the TV runs Android 9 (API 28), and `WifiP2pGroup.getFrequency()`
needs API 29. So the **tablet's status card** shows the frequency, and the TV overlay shows the link
kind. Sending the frequency over the wire would need a protocol bump for a display-only value.

Deviation found on the devices: the tablet lists receivers by P2P **peer** discovery, not service
discovery. The Fire TV never answered the tablet's DNS-SD queries unless it was a group owner
(ADR-011). Discovery is still automatic (no IP typed); the offer itself still travels over P2P DNS-SD.

## Approach
Wi-Fi Direct only replaces **how the two devices reach each other**. Once there is an IP path, the
session is exactly today's:
- the same `ReceiverServer`, which binds the wildcard address and so already accepts on the P2P
  interface;
- the same `SenderClient.connect`, handshake and protocol v3.

The new code produces a `ReceiverEndpoint` and owns the P2P link for the session's lifetime. Below
`ProjectionHost`/`ReceiverController`, the only change is a shorter reconnection outage over P2P.

**Topology A (baseline): the TV is the group owner (GO), the tablet joins with credentials.**
- The TV creates an autonomous group with `createGroup`, the only form available on API 28.
- It waits for `WIFI_P2P_CONNECTION_CHANGED_ACTION` with `groupFormed`, then reads the SSID and
  passphrase from `requestGroupInfo`.
- It registers a P2P Bonjour service `_ririproj._tcp` whose TXT record carries `v`, `name`, `port`,
  `ssid` and `psk`.
- On the tablet, a **"Connect via Wi-Fi Direct"** button runs P2P service discovery and lists the
  receivers found. When the user picks one, the tablet stops discovery and joins with
  `WifiP2pConfig.Builder` (`setNetworkName`, `setPassphrase`, `setDeviceAddress`; API 29+, and the
  tablet is 34+). This join needs **no dialog on either device**.
- The endpoint is the group owner's address learned from `WifiP2pInfo` plus the TXT port. `join()`
  only completes once the tablet's group interface has an IPv4 address in that subnet. A first link
  that never completed its handshake is never retried (ADR-010), so a connect issued before DHCP
  finishes would end the session.
- On API 28, `createGroup` makes a **persistent** group: the SSID and PSK stay the same every time,
  and an app cannot delete them. The exposure is accepted while the group exists. The single-session
  server answers anyone else with BUSY, and the group exists only while the receiver screen is up.
- Backgrounding the TV is final over P2P, because the group goes away with the screen. This differs
  from ADR-010's 30 s retry and is documented in the ADR.

**Topology B′ (fallback): the tablet is a 5 GHz GO, the TV joins as a legacy station.**
- The tablet calls `createGroup(WifiP2pConfig)` with `setGroupOperatingBand(BAND_5GHZ)` and a fresh
  random passphrase per session.
- The TV joins through `WifiManager.addNetwork`/`enableNetwork`, which an API 28 app may still do.
  It gets the credentials by running P2P service discovery while its receiver screen is up.
- On close, the TV removes that network and reconnects to the router.
- This topology is dialog-free and controls the band, but it is more complex. Topology B in its
  plain form is rejected: an API 28 TV can only join through PBC, which shows an accept dialog
  every time.

**Step 1 is a measured spike that decides the topology** before any production code is written.
Variants:
- **A1:** TV GO with its router link up.
- **A2:** TV GO after `WifiManager.disconnect()`, restored with `reconnect()`.
- **B′:** as described above.

Metrics for each variant, recorded in this plan:
- Group frequency.
- Whether the TV GO answers service discovery without calling `discoverPeers`. If it needs
  `discoverPeers`, the TV calls `stopPeerDiscovery` as soon as a session starts, since off-channel
  scans would cause the very stalls this feature removes.
- Whether the credentials persist across two groups (`dumpsys wifip2p`).
- Tablet radio concurrency (`dumpsys wifi`): does the Tab S9 handle 2.4 GHz and 5 GHz at the same
  time (DBS)?
- Power save on the P2P client.
- In A2: the TV's own reconnects and scan stalls.
- The impact of an idle GO on router mode.
- For a 10-minute YouTube run:
  - audio underruns per minute;
  - the largest and p99 write block on the tablet;
  - capture→played audio;
  - the per-phase times from the button press to the first frame.

Decision rule:
- Ship A (A1 if it passes, otherwise A2) if it meets the acceptance numbers.
- Otherwise ship B′.

Everything after the spike sits behind one interface (`P2pLink` → `ReceiverEndpoint` +
`linkLost`), so B′ only swaps the implementations in `core:net`.

**TV side: no toggle by default.** The issue asks for the TV to be discoverable while its screen is
up, with no extra steps. So the TV creates its group (A) or browses for credentials (B′) whenever
the receiver screen is up, **provided** the spike shows an idle group does not hurt router mode.
If it does hurt router mode, the fallback is a toggle on the TV remote, and the issue is amended
(user decision).

**Rejected alternatives**
- **Silent fallback to the router**: the issue requires a clear end and no automatic switching.
- **Typing 192.168.49.1**: discovery is mandatory.
- **Sending the credentials over the router**: this needs both devices on the router, which is the
  situation P2P is meant to leave.

**Jitter buffer.** It already adapts to the gaps it sees, so over a link without stalls it should
settle near its 10–20 ms floor. The cover limit stays at 150 ms unless the run misses 380 ms. In that
case the receiver passes a lower `coverMs` (~60 ms) for sessions that arrived over P2P.

## Changes per module
As built for topology B′ (the spike's decision; ADR-011 has the reasons). Topology A's classes
(`P2pReceiverHost`, `P2pJoiner`, `linkKindOf`) were not needed.
- `core:net`, new package `dev.ririprojector.net.p2p`:
  - `P2pRecords.kt`: `P2pReceiverRecord(name, port)` and `P2pOfferRecord(name, ssid, psk, to)`
    (`toString` hides the PSK); `P2pTxt.encode`/`decode`, `v=1`, with validation (name ≤ 63 bytes,
    `DIRECT-` SSID of 9–32 bytes, PSK of 8–63 printable characters, MAC format).
  - `P2pPolicy.kt`: `P2pFailure` (`Unsupported`, `Busy`, `Timeout`, `PermissionDenied`, `LinkLost`,
    `Error(code)`) with `of(reason)`; `GroupChannel` and `channelFor(stationFreq)`;
    `OUTAGE_LIMIT_MS = 1500`, `ROUTER_OUTAGE_LIMIT_MS = 30000`; `ipv4`, `newSsid`, `newPassphrase`.
  - `P2pCommon.kt`: `P2pChannel` wrapper, suspending `await`, `quietly`, `groupInfo`, receiver
    registration helpers. `P2pLog.kt`: tag `Riri/P2p`, plus `files/p2p.log` in debuggable builds.
  - `P2pSender.kt`:
    - `P2pReceiverBrowser.receivers(): Flow<List<P2pReceiver>>` from peer discovery, re-armed
      every 15 s, stopped on cancel;
    - `P2pGroupOwner.open(target, senderName, 30 s): P2pLink` (API 35): group on the station
      channel → offer → `discoverPeers` → poll the client's IPv4 → stop discovery, clear the offer;
    - `P2pLink(endpoint, frequencyMhz, lost)`.
  - `P2pReceiverSide.kt`:
    - `P2pReceiverAgent`: announces the receiver, requests all services, runs service discovery
      while idle, hands offers addressed to this device to `onOffer`, reopens a lost channel;
    - `P2pStationJoiner.join(context, offer, 15 s): P2pStation`: legacy station on API 28, P2P
      client on API 29+; `P2pStation.lost` and `close()` (restores the router).
  - `WifiDirectSupport`: `MIN_SENDER_SDK = 35`, `requiredPermissions(sdk)`, `usable`,
    `usableAsSender`.
  - `SessionStateMachine(giveUpAfterMs)`: the outage limit per link.
  - Manifest: `CHANGE_WIFI_STATE`, location (`maxSdkVersion 32`), `NEARBY_WIFI_DEVICES`
    (`neverForLocation`), `uses-feature android.hardware.wifi.direct required=false`.
- `feature:sender`:
  - `SenderActivity`: the Wi-Fi Direct section (hint, button, device list) shown when
    `usableAsSender`; `NEARBY_WIFI_DEVICES` requested on demand; the pick goes through the usual
    consent flow and survives recreation (`KEY_DIRECT`).
  - `ProjectionService`: `EXTRA_DIRECT_ADDRESS`; a service-owned scope; the join is guarded like a
    session and cancelled by stop; order projection → group → host with the 1.5 s outage limit;
    link loss ends the session with "Lost <name> over Wi-Fi Direct"; `p2pMessage` maps failures.
  - `ProjectionHost(outageLimitMs)`; `SenderStatus.direct` (`DirectLink(frequencyMhz)`) shown on
    the status card.
- `feature:receiver`:
  - `ReceiverController`: owns the agent and the station with the server's lifetime; joins offers
    only while no session streams; leaves an unused station after 20 s and a used one when the
    session ends; marks sessions from the group owner's address as Wi-Fi Direct.
  - `ReceiverActivity`: "Also reachable over Wi-Fi Direct" on the waiting screen, "· Wi-Fi Direct"
    in the overlay, and the API 29+ permission request.
- `app` (debug only): `P2pSpikeService`, the spike harness.
- `docs/ARCHITECTURE.md`: ADR-011 and a cross-reference from ADR-006.

## Protocol
- Wire format: **no change**. `PROTOCOL_VERSION` stays at 3.
- The P2P TXT record (`v=1`) is separate from the ADR-006 NSD TXT and is documented in ADR-011.

## Threading and resource lifecycle
- `WifiP2pManager` callbacks arrive on the main looper. Each owner has its own `Channel` and wraps
  the callbacks with `suspendCancellableCoroutine`. The codec and socket threads are untouched.
- Each owner registers its broadcast receivers and unregisters them in `close()`.
- `removeGroup`, `clearLocalServices` and `clearServiceRequests` run in `finally`.
- `onChannelDisconnected` counts as link loss.
- Sender ownership:
  - `ProjectionService` owns the browser→joiner hand-off and the `P2pLink` (Android 14 needs
    consent before the mediaProjection foreground service, so the join cannot happen earlier
    without an ownership hand-off);
  - stop runs in reverse order.
- Receiver ownership: `ReceiverController` owns the host, with the same lifetime as the server.

## Risks and mitigations
| Risk | Impact | Mitigation |
|---|---|---|
| TV GO on 2.4 GHz; tablet without DBS time-slices with its 5 GHz router link | stalls continue | Spike measures A1/A2; B′ forces 5 GHz |
| TV GO only discoverable while `discoverPeers` runs | scans cause stalls | Spike checks; stop peer discovery once a session starts |
| DHCP not finished when `groupFormed` arrives | first connect fails, never retried | `join()` waits for the IPv4 address |
| Static PSK (persistent group on API 28) readable over the air | a stranger can join the group | Group only while the receiver screen is up; server is single-session (BUSY) |
| `disconnect()` undone by the TV's auto-join, or its scans add stalls (A2) | TV back on 2.4 / stalls | Spike measures; restore with `reconnect()` in `close()` |
| Power save on the P2P client | latency spikes | Spike measures; the receiver Wi-Fi lock covers the station path in B′ |
| Fire OS alters P2P (Amazon P2P manager), or another app holds P2P (Samsung Quick Share or Smart View) | no group / BUSY | Public API only; clear `Unsupported` / `Busy` messages |
| Router mode regression | default path breaks | Nothing below the endpoint changes; idle-GO impact measured; router path re-checked |

## Test strategy
- JVM:
  - `P2pServiceRecord` round-trip, including a 63-character PSK, a 32-byte SSID, and
    missing/oversize/unknown-version fields;
  - the failure-code mapping;
  - `linkKindOf`;
  - `ReconnectPolicy`/`SessionStateMachine` with the P2P outage limit (gives up within 1.5 s of
    outage).
- Device spike (step 1): the variants and metrics above.
- Device acceptance (Tab S9 → Fire TV):
  - a 10-minute YouTube run per mode;
  - 10 stop/start cycles, checking `dumpsys wifip2p`, that the TV is back on the router, and that no
    listeners leak;
  - TV powered off mid-session, measuring the time until the tablet shows its message;
  - per-phase time from the button press to the first frame;
  - the router path checked once.

## Spike results (2026-11-20/21, Tab S9 SM-X710 API 36 → Fire TV Stick AFTSSS API 28)
Harness: `app/src/debug/.../P2pSpikeService.kt` (debug build only), driven over adb.

Facts that do not depend on distance:
- The TV creates a group in ~0.4 s with no location permission and no prompt. The group is
  persistent: same SSID and PSK every time.
- The tablet finds the TV GO's P2P service in ~1 s, **without the TV calling `discoverPeers`**.
- `setDeviceAddress` on the join must not be the P2P *device* address. The GO's interface address
  differs (`76:…` device vs `7e:…` GO), and the join fails with "failed to find the group". Join
  by SSID and passphrase only.
- The TV GO's channel follows its station link: ch 5 (2432) with the router up. With
  `disconnect()` it moved to ch 1 (2412), and the TV re-joined the router by itself within seconds.
  API 28 cannot choose the band, so **topology A is 2.4 GHz only**.
- The tablet has no DBS (`mIsDbs: false`). A 2.4 GHz group means switching channels with its
  5 GHz router link.
- The tablet as GO: with `setGroupOperatingBand(5GHz)` it picked 5745 (ch 149, a different channel
  from its station on ch 44); with `setGroupOperatingFrequency(5220)` it shares the station
  channel (SCC). On API 35+ the GO sees the client's IP (`WifiP2pDevice.getIpAddress`).

Runs (one tablet log line every 5 s; TV overlay):
| Variant | Placement | Result |
|---|---|---|
| A1: TV GO, 2.4 GHz ch 5 | different rooms | joined in 6.7 s, no prompts; writes blocked 500–1 850 ms, hundreds of queue drops: unusable |
| A2: TV GO after `disconnect()` | different rooms | GO on ch 1, TV back on the router by itself (two channels on the TV): rejected |
| B′: tablet GO 5220 MHz, TV as legacy station | different rooms | TV never saw the SSID in its scans |
| B′: same | **same room** | TV joined in ~6 s; tablet: write max 1–12 ms, 0 queue drops, send age ~100 ms (150 s); TV overlay (~6 min): **audio played 330 ms** (router ~460), video shown 353 ms, latency p95 361, buffer at its 10 ms floor, 2 underruns in total, no audible gaps (user) |

Router reference (M6, TV on 2.4 GHz −68 dBm): writes blocked 50–190 ms, ~1 underrun/min,
~460 ms audio played. The tablet's own router link in the TV's room is weak too (−76…−79 dBm).

**Decision: topology B′** (tablet GO on its station channel, TV joins as a legacy station). It is
the only variant that reaches 5 GHz, and it meets the write-block (≤ 50 ms) and audio (≤ 380 ms)
targets in the same room. Open items for implementation:
- **Credential delivery.** The TV must find the tablet's group offer. Planned: the TV advertises its
  receiver service and runs P2P service discovery while idle; the tablet, after creating its
  group, advertises a service whose TXT carries the SSID, PSK and the target TV's device address.
  **Verified:** with the tablet GO on 5220 MHz advertising `{v, name, port, ssid, psk}` and calling
  `discoverPeers`, the TV's `discoverServices` received the TXT in 4.2 s. The TV runs discovery
  only while idle (no session), so it never overlaps streaming in either mode.
- **The TV leaves the router while mirroring** (accepted in the issue). It is restored with
  `removeNetwork` + `reconnect()`, and it also re-joins by itself if the group disappears.
- **Underrun rate.** 2 underruns in ~6 minutes is not yet proven ≤ 0.2/min after the first 10 s;
  the 10-minute acceptance run decides.

## Acceptance runs (2026-11-21, same room)
Wi-Fi Direct, YouTube, ~13 min (09:01–09:14), TV overlay at the end:

| Metric | Value | Criterion |
|---|---|---|
| Stream | HEVC 1728×1080, 31/59 fps, 6.9 Mbps, group on 5220 MHz | 5 GHz: met |
| Audio underruns | 2 in ~13 min (~0.15/min) | ≤ 0.2/min: met |
| Capture→played audio | 370 ms (video shown 361; latency 343, p95 376; A/V +8 ms) | ≤ 380 ms: met |
| Tablet socket writes | max 155 ms, in the link's first ~16 s only; 4 of 152 five-second windows above 50 ms | no block > 50 ms: **not met at link start**; steady state met as far as the rotated log shows |
| Dropped frames | 2 | — |
| Discovery and join | TV listed ~2 s after the tap; group formed in 0.4 s; TV joined ~10 s after that | — |

Not yet measured: time from the tap to the first frame, 10 stop/start cycles, TV powered off
mid-session, and the router path recheck.

## Implementation steps
1. [x] `chore(spike)`: a debug-only harness, kept out of the release flow. Run A1/A2/B′, record the
   results here and pick the topology (and decide whether the TV needs a toggle).
2. [x] `feat(net)`: `P2pServiceRecord`, failure mapping, `linkKindOf`, outage limit per link kind,
   manifest; tests.
3. [x] `feat(net)`: `P2pReceiverHost`, `P2pBrowser`, `P2pJoiner`/`P2pLink` for the chosen topology.
4. [x] `feat(receiver)`: host ownership, waiting-screen text, link kind in the overlay.
5. [x] `feat(sender)`: button, discovery list, permission flow, `ProjectionService` scope, joining state
   and ownership, link loss, phase timestamps, status card.
6. [x] `docs`: ADR-011, cross-reference from ADR-006, device measurements, issue clarification;
   `coverMs` tuning only if the run needs it.
