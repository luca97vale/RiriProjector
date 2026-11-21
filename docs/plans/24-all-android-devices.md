# Plan #24 — Target all Android devices

Status: IMPLEMENTED (short plan; wording + one UX gap, no protocol or hot-path change)

## Goal
Describe and verify the two roles by API level and device features, not by the reference test
devices (Galaxy Tab S9 sender, Fire TV Stick / Pixel 10 receivers):
- **Sender**: any Android phone or tablet. Video from minSdk 28, audio capture from API 29,
  single-app sharing from API 34.
- **Receiver**: any Android TV, set-top box, projector, phone or tablet (minSdk 28).

## Changes
1. **Mode choice on every device (ADR-001 gap).** Today only TV-like devices or the adb `mode`
   extra reach the receiver. Add a remembered manual choice:
   - `ModeResolver.resolve(extra, remembered, isTv)`: extra > remembered choice > device type.
   - `SenderActivity` / `ReceiverActivity` expose `ACTION_USE_AS_RECEIVER` / `ACTION_USE_AS_SENDER`;
     their screens show a "Use this device as a receiver/sender" control (disabled while a session is
     active). The intent goes to `MainActivity` (package-scoped), which stores the choice in its
     preferences and starts the other activity. Features still do not depend on each other.
   - The adb `mode` extra is not remembered (one-off, used by `scripts/deploy.sh`).
2. **Phone-width layouts.** Sender setting chips use `FlowRow` so four chips wrap at 360 dp.
   Receiver: tap toggles the stats overlay on touch devices (keys unchanged); the waiting text
   uses a smaller size on small screens.
3. **Wording.** CLAUDE.md, README, ARCHITECTURE (ADR-001, platform notes), PLAN, SDLC, LATENCY,
   agents, skills, issue/PR templates, labels script, KDoc/comments, strings, test names.
   Measurements stay, labelled "measured on <device>". Historical plan files keep their content;
   only roles in generic prose are reworded.
4. **Deploy.** `scripts/deploy.sh receiver|sender|both` with `RECEIVER_HOST`/`RECEIVER_PORT`/
   `RECEIVER_SERIAL` and `SENDER_SERIAL`; `firetv|tablet` and `FIRETV_HOST`/`FIRETV_PORT`/
   `TABLET_SERIAL` remain as fallbacks. Skill renamed `/firetv-deploy` → `/device-deploy`.

## Untouched (Wi-Fi Direct branch #21)
`core/net/.../p2p/*`, `EarlyDirectLink.kt`, `ProjectionService.kt`, `ReceiverController.kt`:
device wording in their comments is listed as a follow-up.

## Tests
`ModeResolverTest`: remembered choice beats device type, extra beats remembered choice, action →
mode mapping. Full gate: `assembleDebug testDebugUnitTest spotlessCheck lintDebug`.
No device run in this change (devices busy); on-device check of the switch is a follow-up.
