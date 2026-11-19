## Summary
<!-- What changes and why, in 2-4 lines. -->

Closes #

Plan: `docs/plans/<n>-<slug>.md`

## Changes per module
- `core:protocol`:
- `core:net`:
- `core:media`:
- `feature:sender`:
- `feature:receiver`:
- `app` / build / CI:

## Protocol
- [ ] No wire format change
- [ ] Changed → `PROTOCOL_VERSION` bumped, ADR updated in `docs/ARCHITECTURE.md`, round-trip tests added

## Tests
- [ ] `./gradlew testDebugUnitTest` green
- [ ] `./gradlew spotlessCheck lintDebug` green
- [ ] Instrumented tests (`connectedDebugAndroidTest`) on: <!-- device or "not applicable" -->

### On-device tests
| Item | Result |
|---|---|
| Galaxy Tab (model / One UI) | |
| Fire TV (model / Fire OS) | |
| Automatic NSD discovery | |
| Start/stop mirroring, consent revoked | |
| Reconnection after Wi-Fi loss / receiver restart | |
| Tablet rotation | |
| Audio played on the TV (music/video, 10 min without gaps) | |
| Lip-sync (clap/flash test) | |

### Performance (required if the media/network hot path is touched)
| Metric | Before | After |
|---|---|---|
| Resolution / fps | | |
| Average bitrate | | |
| Latency (RTT + decode, or glass-to-glass) | | |
| Dropped frames / min | | |
| A/V offset (ms) / audio underruns per min | | |

## Review
- [ ] `/sdlc-review` run, no open blockers
- Follow-ups not included:
