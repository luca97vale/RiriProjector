# RiriProjector

Low-latency screen mirroring, **video and audio**, between Android devices on the LAN, as an
alternative to Miracast. A single app with two roles:

- **Sender**: any Android phone or tablet (MediaProjection → hardware HEVC/H.264 + audio capture → TCP).
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

- [Using RiriProjector](#using-ririprojector) — install and first use
- [Development](#development) — building from source
- [Releasing](#releasing) — signed APKs on GitHub releases

---

## Using RiriProjector

### What you need
- **Receiver:** a Fire TV Stick (Fire OS 7 or later) or an Android TV / projector with Android 9+.
- **Sender:** an Android 9+ tablet or phone (built and tested on a Galaxy Tab S9). Audio mirroring
  needs Android 10+; picking a single app to share needs Android 14+.
- Both on the **same Wi-Fi network**, ideally 5 GHz, with the router not blocking device-to-device
  traffic (guest networks and "AP isolation" usually do).

The same APK runs on both: it opens as a receiver on a TV and as a sender on a tablet. Download
`ririprojector-<version>.apk` from the
[latest release](https://github.com/luca97vale/RiriProjector/releases/latest).

### Install on the Fire TV (sideloading)
Fire TV only installs apps from the Amazon Appstore unless you allow it for a specific app.

1. **Enable Developer Options** (newer Fire OS versions hide it): *Settings › My Fire TV › About*,
   select your device name **7 times** until "You are now a developer" appears.
2. Install **Downloader** (by AFTVnews) from the Appstore: search for "Downloader".
3. *Settings › My Fire TV › Developer Options › Install unknown apps* and turn it **on for
   Downloader** (on older Fire OS this is a single "Apps from Unknown Sources" switch).
4. Open Downloader, type the APK link from the latest release
   (`https://github.com/luca97vale/RiriProjector/releases/latest` opens the page; select the
   `.apk` asset), let it download, then select **Install**. Delete the APK afterwards when asked.
5. RiriProjector appears under *Your Apps & Channels*. Open it: it shows the TV name and its
   `IP:port`, waiting for a tablet.

**Alternative, with a computer and adb** (see Amazon's
[Connect to Fire TV through ADB](https://developer.amazon.com/docs/fire-tv/connecting-adb-to-device.html)):
turn on *Developer Options › ADB debugging*, find the IP under *Settings › My Fire TV › About ›
Network*, then:

```powershell
adb connect 192.168.1.50:5555
```
```powershell
adb install -r ririprojector-1.0.0.apk
```

Accept the "Allow USB debugging?" prompt on the TV the first time.

### Install on the tablet
Open the release page on the tablet, download the `.apk` and open it. Android asks you to allow
installs from your browser (or file manager) once: allow it, then install.

### First use
1. Open RiriProjector on the TV (it waits for a connection).
2. Open it on the tablet. The TV appears under **Receivers** within a few seconds; if not (some
   routers filter discovery), type the `IP` or `IP:port` shown on the TV and tap **Connect**.
3. Pick the stream settings if you want (codec, resolution, frame rate, bitrate, audio, sync), then
   tap the receiver.
4. Allow the microphone permission (needed to capture app audio; nothing is recorded from the
   microphone) and choose **Entire screen** or **A single app** in the system dialog.
5. Tap **Stop** on the tablet, or press Back on the TV remote, to end mirroring.

The tablet remembers your settings and the last receiver: next time, tap the receiver marked
**Last used**, or the **Last receiver** card if the TV is not discovered.

### Known limitations
- Protected content (Netflix, Prime Video and other DRM or `FLAG_SECURE` apps) is shown as black:
  Android does not allow capturing it.
- Apps that opt out of audio capture are mirrored without sound; the tablet says so.
- Android asks for screen-sharing consent at every new session; this is a system requirement.
- To share one app's sound only, grant *Usage access* when the tablet asks once; without it, every
  app's sound is mirrored.
- **Updates** install over the previous version only if signed with the same release key and with a
  higher version number. The development build (`dev.ririprojector.debug`) is a separate app and can
  stay installed side by side.

---

## Development
- Architecture and decisions: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Roadmap: [docs/PLAN.md](docs/PLAN.md)
- Development process (SDLC with Claude Code): [docs/SDLC.md](docs/SDLC.md)
- Instructions for Claude Code: [CLAUDE.md](CLAUDE.md)

### Requirements
- JDK 25 LTS, Android SDK (platform-tools for `adb`)
- Node.js ≥ 18 (Claude Code hooks), authenticated `gh`
- Receiver and sender with USB or network *ADB debugging* enabled

### Quick start
```bash
export RECEIVER_HOST=192.168.1.50   # receiver IP for adb over the network (or RECEIVER_SERIAL for USB)
# export SENDER_SERIAL=...          # optional: adb serial of the sender if several devices are attached
scripts/deploy.sh both --logs
```

`./gradlew assembleRelease` without signing settings builds an unsigned release APK (as CI does).

---

## Releasing
Releases are built by [`.github/workflows/release.yml`](.github/workflows/release.yml) when a
`vMAJOR.MINOR.PATCH` tag is pushed. It signs the APK with the release key and attaches
`ririprojector-<version>.apk` and its R8 `…-mapping.txt` to a GitHub release. The keystore and its
passwords never enter the repository.

### One-time setup
1. **Create the release keystore** somewhere outside the repository, and keep a backup (a password
   manager or an encrypted drive). Losing it means users must uninstall to get updates.
   ```bash
   keytool -genkeypair -v -keystore ~/keys/riri-release.jks -keyalg RSA -keysize 4096 \
     -validity 10000 -alias riri
   ```
2. **Add the GitHub secrets** (*Settings › Secrets and variables › Actions*, or scoped to an
   environment named `release`, which the workflow uses; adding a required reviewer there makes
   every release wait for approval):

   | Secret | Value |
   |---|---|
   | `RELEASE_KEYSTORE_BASE64` | output of `base64 -w0 ~/keys/riri-release.jks` |
   | `RIRI_KEYSTORE_PASSWORD` | keystore password |
   | `RIRI_KEY_ALIAS` | `riri` (the `-alias` above) |
   | `RIRI_KEY_PASSWORD` | key password (same as the keystore password unless you chose another) |

### Cutting a release
1. Merge everything to `main` (the workflow refuses tags on other branches).
2. Tag and push: `git tag v1.0.0 && git push origin v1.0.0`. The version code is
   `major*10000 + minor*100 + patch`, so minor and patch must stay ≤ 99.
3. When the workflow is green, install the APK from the release on **both** devices and check a
   mirroring session with audio before announcing it (release builds are shrunk by R8).

### Signing locally (optional)
Set the same four values as environment variables before `./gradlew assembleRelease`:
`RIRI_KEYSTORE_FILE` (absolute path), `RIRI_KEYSTORE_PASSWORD`, `RIRI_KEY_ALIAS`,
`RIRI_KEY_PASSWORD`. Alternatively put `riri.keystore.file`, `riri.keystore.password`,
`riri.key.alias`, `riri.key.password` in your user-level `~/.gradle/gradle.properties` (outside the
repo, but plaintext). Set all four or none: a partial setup fails the build on purpose.
