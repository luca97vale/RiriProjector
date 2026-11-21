# RiriProjector

Low-latency screen mirroring, **video and audio**, between Android devices on the LAN, as an
alternative to Miracast. A single app with two roles:

- **Sender**: any Android phone or # Build + install RiriProjector on the receiver and/or the sender (any Android devices).
# Usage: scripts/deploy.sh [receiver|sender|both] [--release] [--no-build] [--logs]
# Env: RECEIVER_SERIAL (USB/already-connected receiver) or RECEIVER_HOST (receiver IP, adb connect),
#      RECEIVER_PORT (default 5555), SENDER_SERIAL (optional), APP_ID (default dev.ririprojector).
# Old names still work as fallbacks: targets firetv/    receiver|sender|both) TARGET="$arg" ;;
    firetv) TARGET="receiver" ;; # legacy name
    tablet) TARGET="sender" ;;   # legacy name, FIRETV_HOST, FIRETV_PORT, TABLET_SERIAL. (MediaProjection → hardware HEVC/H.264 + audio capture → TCP).
- **Receiver**: any Android TV, set-top box, projector, phone or tablet (NSD discovery, low-latency
  hardware decoder, synchronized audio).

## Device support

| Role | Requirement | Notes |
|---|---|---|
| Sender | Android 9 (API 28)+ for video | Audio capture needs Android 10 (API 29)+; sharing a single app needs Android 14 (API 34)+ |
| Receiver | Android 9 (API 28)+ | The `KEY_LOW_LATENCY` decoder mode needs Android 11 (API 30)+; below that, vendor keys are tried |

TV-like devices (Android TV / Google TV, Fire TV, leanback projectors) start as receivers; other
devices start as senders. Any device can switch role with *Use this device as a receiver/sender*, and
the app remembers the choice. The codec is negotiated from what both devices support (HEVC, otherwise
H.264).

Reference test devices so far: Galaxy Tab S9 (sender), Fire TV Stick (Fire OS 7) and Pixel 10
(receivers). Other devices are expected to work within the API levels above but have not been
verified yet; please report results with the bug template.

- Architecture and decisions: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Roadmap: [docs/PLAN.md](docs/PLAN.md)
- Development process (SDLC with Claude Code): [docs/SDLC.md](docs/SDLC.md)
- Instructions for Claude Code: [CLAUDE.md](CLAUDE.md)

## Development requirements
- JDK 25 LTS, Android SDK (platform-tools for `adb`)
- Node.js ≥ 18 (Claude Code hooks), authenticated `gh`
- Receiver and sender with USB or network *ADB debugging* enabled

## Quick start
```bash
export RECEIVER_HOST=192.168.1.50   # receiver IP for adb over the network (or RECEIVER_SERIAL for USB)
# export SENDER_SERIAL=...          # optional: adb serial of the sender if several devices are attached
scripts/deploy.sh both --logs
```
