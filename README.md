# RiriProjector

Low-latency screen mirroring, **video and audio**, from a Galaxy Tab to Fire TV Sticks / Android
projectors on the LAN, as an alternative to Miracast. A single app: **sender** on the tablet
(MediaProjection → hardware HEVC/H.264 + audio capture → TCP) and **receiver** on the TV
(NSD discovery, low-latency hardware decoder, synchronized audio).

- Architecture and decisions: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Roadmap: [docs/PLAN.md](docs/PLAN.md)
- Development process (SDLC with Claude Code): [docs/SDLC.md](docs/SDLC.md)
- Instructions for Claude Code: [CLAUDE.md](CLAUDE.md)

## Development requirements
- JDK 25 LTS, Android SDK (platform-tools for `adb`)
- Node.js ≥ 18 (Claude Code hooks), authenticated `gh`
- Fire TV with *ADB debugging* enabled; tablet with USB/wireless debugging

## Quick start
```bash
export FIRETV_HOST=192.168.1.50   # Fire TV IP address
scripts/deploy.sh both --logs
```
