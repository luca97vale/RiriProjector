# Plan #35 — Second-screen mode (apps at the receiver resolution, shell helper)
Issue: #35 · Status: APPROVED (android-architect: APPROVED WITH CHANGES, integrated in "Review changes" below)

## Goal and acceptance criteria (copied from the issue)
- [ ] Without the setup, nothing changes: the mode is hidden or disabled with an explanation, and mirroring behaves exactly as in the latest release.
- [ ] One-time setup works on Android 11+ senders without a PC, guided by in-app screens (strings in `strings.xml`); after a reboot or Wi-Fi change the app detects that shell access is gone and guides the user to restore it in ≤ 3 steps.
- [ ] The second display uses the receiver's resolution capped by the real codec limits (same rule as #34), landscape, and fills the receiver screen with no black bars for resizable apps: verified at 1920×1080 on Fire TV Stick and at > 1080p on a Pixel 10 / Galaxy Tab S9 receiver.
- [ ] The receiver app picker lists the sender's launchable apps and opens the chosen one on the second display within 2 s; fully usable with a D-pad.
- [ ] Each forwarded input event is injected on the second display within 20 ms of reaching the sender (p95, from aggregated counters); touch coordinates map correctly in both receiver orientations.
- [ ] Video: ≥ 58 fps for a 60 Hz display over 5 minutes of scrolling on 5 GHz Wi-Fi; glass-to-glass latency p95 within +10 ms of mirroring at the same resolution. Audio: A/V offset ≤ 40 ms.
- [ ] Ending the session (either side, or link loss) removes the virtual display, stops the helper's session resources, and leaves the launched app on the sender's main display or closed: no crash, no orphaned display.
- [ ] Apps with `FLAG_SECURE` content (DRM video) show black and portrait-locked / non-resizable apps are letterboxed, without crashing; the UI explains both.
- [ ] The helper only accepts commands from RiriProjector (local, authenticated channel; nothing listens on network interfaces) and runs only while needed.
- [ ] New messages (app list, launch, input events) bump `PROTOCOL_VERSION`; `docs/ARCHITECTURE.md` gets an ADR for the helper approach, including Google Play policy considerations.
- [ ] Verified on Galaxy Tab S9 (One UI, API 36) and Pixel 10 (API 37) as senders.

## Approach
**Shell access without a second app (owner decision, 2026-11-26).** RiriProjector embeds a renamed
fork of Shizuku's Apache-2.0 Wireless-debugging client: `AdbKey` (RSA-2048 key, AES-GCM-wrapped with
an AndroidKeyStore key, BouncyCastle self-signed X.509 cert, ADB public-key encoding),
`AdbPairingClient` (TLS 1.3 on the platform `SSLContext`, keying material from the platform
Conscrypt reached through hiddenapibypass, SPAKE2 + HKDF + AES-128-GCM in a small JNI library over
BoringSSL), `AdbMdns` (NSD discovery of `_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp`) and
`AdbClient` (CNXN → STLS → `shell:` service). All code moves to `dev.ririprojector.adb`, the JNI
class name and library are renamed (`libriri_adb.so`), and nothing uses the name "Shizuku", its icons,
its application id or its permissions. Shizuku's native starter is **not** copied: we start the
helper with a plain shell command (below), which removes `libshizuku.so`, SELinux/cgroup code and an
executable in `nativeLibraryDir`.

**Helper.** Over the ADB shell the app runs
`CLASSPATH=<sourceDir> setsid app_process /system/bin --nice-name=riri_helper
dev.ririprojector.helper.HelperMain <token-less args> >/dev/null 2>&1 &`. The helper runs as uid 2000
(shell), which may create trusted virtual displays, start any activity on them and inject input
(`INJECT_EVENTS`): the scrcpy "new display" technique, using reflection on platform classes from
inside `app_process` (no hidden-API restrictions there). It hands its binder to the app the way
Shizuku does: a `call()` on an exported `ContentProvider` of the app guarded by
`android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"` (held by shell, never by
third-party apps). Every helper binder method checks `Binder.getCallingUid() == our app uid`.
Nothing listens on a socket. The helper exits when the app's binder dies (`linkToDeath`) or after
60 s without a session, so it runs only while needed; the app restarts it on demand while the ADB
connection is possible.

**Helper interface (AIDL, `IRiriHelper`).** `createDisplay(Surface, w, h, dpi, fps): int displayId`;
`releaseDisplay(id)`; `launch(component, displayId)`; `injectKey(displayId, action, keyCode, meta,
downTime)`; `injectMotion(displayId, action, pointer arrays, source)`; `version()`; `exit()`. The
display uses flags PUBLIC | OWN_CONTENT_ONLY | PRESENTATION | TRUSTED (API 33+) |
SUPPORTS_TOUCH | DESTROY_CONTENT_ON_REMOVAL (moves/finishes the app when released), and
`ALWAYS_UNLOCKED` where available. The app list comes from the app itself (`PackageManager`
LAUNCHER query, declared in `<queries>`), not from the helper.

**Session.** A "Second screen" session differs from mirroring only in the video source: the
encoder's input `Surface` goes to the helper's display instead of a `MediaProjection`
VirtualDisplay. Its size is `VideoFit.fitWithin(receiver display w, h, encoder ∩ decoder limits,
fps, cap, encoderAccepts)` from #34, so it has the receiver's own aspect ratio (no bars).
Density = `shortSide × 160 / 540` (a 960×540 dp TV layout at 1080p, 320 dpi; 480 dpi at 2160p).
Audio keeps today's `AudioPlaybackCapture` on a `MediaProjection` (consent per session, ADR-007), so
audio needs no new code; helper-side REMOTE_SUBMIX capture is a later option. The receiver shows a
D-pad app picker (overlay over the stream) and forwards keys/touch.

Rejected: the Shizuku app + API (two apps; the owner prefers one); writing an ADB TLS/SPAKE2 client
from scratch (more security-sensitive code than forking a maintained one); Shizuku's native starter
(unneeded for a single short-lived helper); `VirtualDeviceManager` (privileged apps only); using
the helper for app listing (the app can do it itself).

## Changes per module
New `:core:adb` (Android lib, NDK + CMake, prefab):
- `AdbKey`, `AdbKeyStore` + `PreferenceAdbKeyStore`, `AdbPairingClient`, `PairingContext` (JNI),
  `AdbMdns`, `AdbClient`, `AdbMessage`/`AdbProtocol` (pure, JVM-tested), `AdbException`s.
- `src/main/jni/adb_pairing.cpp` + `CMakeLists.txt` (`boringssl::crypto_static`, `cxx::cxx`).
- `ShellAccess` (pure state machine, JVM-tested): `Unsupported(API<30) → NotPaired → Paired(needs
  Wireless debugging) → Connecting → Ready(helper) | Error(reason)`.
- Dependencies (owner-approved 2026-11-26): `io.github.vvb2060.ndk:boringssl`, `org.lsposed.libcxx:libcxx`,
  `org.lsposed.hiddenapibypass:hiddenapibypass`, `org.bouncycastle:bcpkix-jdk18on`. Exact versions as in
  Shizuku's `manager/build.gradle` at the fork commit.
- `LICENSE-Shizuku` (Apache-2.0) + file headers "Derived from Shizuku (Apache-2.0), modified".
New `:core:helper` (Android lib):
- `IRiriHelper.aidl`, `HelperMain` (runs as shell; display/launch/input via reflection, version
  checks per API), `HelperProvider` (exported, `INTERACT_ACROSS_USERS_FULL`), `HelperClient`
  (app side: start over ADB, await binder with timeout, `linkToDeath`), `HelperCommand` (pure:
  builds the shell command, JVM-tested), `DisplayDensity` (pure).
`:core:protocol` (v6):
- START gains `u8 mode` (0 mirror, 1 second screen).
- `APP_LIST` S→R: `u16 count` × (`string id`, `string label`, `bytes icon` PNG ≤ 96×96).
- `APP_LAUNCH` R→S: `string id`.
- `INPUT_KEY` R→S: `u8 action`, `u16 keyCode`, `i32 metaState`.
- `INPUT_TOUCH` R→S: `u8 action`, `u8 pointerCount` × (`u8 id`, `u16 x`, `u16 y` in stream pixels).
- `InputMapper` (pure): receiver view coordinates → stream pixels (letterbox-aware, both orientations).
`:feature:sender`:
- Settings: "Second screen (advanced)" section: setup wizard screens (Developer options → Wireless
  debugging → "Pair device with pairing code" → code entered in a notification RemoteInput, as
  Shizuku, because the Settings screen covers the app), status and "restore access" flow.
- Start button splits: "Mirror" / "Second screen" (enabled only when `ShellAccess` is Ready-able).
- `ProjectionHost`: video source abstraction (`MirrorSource` = today, `HelperDisplaySource`);
  negotiates with the receiver display as source; on START sends APP_LIST; handles APP_LAUNCH /
  INPUT_* on the control thread → helper binder (never on the writer thread); releases the display
  in `finally`.
`:feature:receiver`:
- App picker overlay (RecyclerView/Compose list, D-pad), Menu/long-press to reopen it.
- Key forwarding (D-pad, back, home → `KEYCODE_HOME` on the display, media keys) and touch
  forwarding through `InputMapper`; counters for the overlay.
`:app`: provider and `<queries>` in the manifest; ProGuard keep rules for `HelperMain` and AIDL.

## Protocol
v6: START `mode`, APP_LIST, APP_LAUNCH, INPUT_KEY, INPUT_TOUCH. Mirror sessions send mode 0 and
none of the new messages. v5 peers get INCOMPATIBLE as usual. ADR-003 table updated; new ADR-014.

## Threading and resource lifecycle
- ADB pairing/connect run on a dedicated IO thread owned by a `ShellAccessService` scope; sockets
  closed in `finally`. The key is created once and kept wrapped.
- Helper binder calls happen on the sender's control thread; injection is one-way (`oneway` AIDL) so
  a slow helper never blocks it. Latency counters (receipt → injected ack batch) are aggregated per
  second.
- Ownership: `ProjectionHost` owns the display id; release on session end, link loss and `close()`;
  the helper also releases every display it created when the app's binder dies (no orphan display).

## Risks and mitigations
- *Hidden API drift* (display flags, `injectInputEvent`, `startActivity` with display id): per-API
  reflection paths like scrcpy, version-gated; failures surface as a clear "not supported on this
  device" state, never a crash. Covered by device runs on API 36/37.
- *Vendor blocks* (e.g. Xiaomi extra toggle): documented in the setup screen.
- *Play policy*: Wireless-debugging self-pairing and shell helpers are allowed for Shizuku-style apps
  but must be disclosed; the ADR records it; the mode is opt-in and off by default.
- *Security*: provider guarded by a signature-level-equivalent system permission; uid check on every
  call; no network listener; the pairing code is never logged; key wrapped by AndroidKeyStore.
- *APK size*: BouncyCastle + BoringSSL; R8 shrinks BC; measure and record in the PR.
- *Mirroring regression*: mirror path untouched apart from the source abstraction; existing tests.

## Test strategy
- JVM: `AdbMessage` encode/decode + checksum, pairing packet header, ADB public-key encoding against a
  known vector, `ShellAccess` transitions, `HelperCommand`, `DisplayDensity`, protocol v6 round trips
  and validation, `InputMapper` (letterbox, both orientations), second-screen size via `fitWithin`.
- Instrumented (device attached): `PairingContext` JNI round trip (two contexts, alice/bob).
- Device: setup on Tab S9 and Pixel 10 (fresh, after reboot, after Wi-Fi change); sessions to Fire
  TV (1920×1080) and Pixel/Tab (> 1080p); input latency counters; 5-min fps/latency/A-V runs; end of
  session from both sides and by link loss with `dumpsys display` showing no leftover display;
  FLAG_SECURE app and a portrait-locked app.

## Implementation steps
- [ ] `docs: ADR-014 shell helper via embedded Wireless-debugging client` (+ license file).
- [ ] `feat(adb): fork the pairing client and ADB connection (renamed, Apache-2.0)` — `:core:adb`, NDK.
- [ ] `feat(adb): shell access state machine and setup service`.
- [ ] `feat(helper): shell helper with display, launch and input over a private binder`.
- [ ] `feat(sender): second-screen setup wizard and restore flow`.
- [ ] `feat(protocol): second-screen messages (v6)`.
- [ ] `feat(sender): second-screen session on the helper display`.
- [ ] `feat(receiver): app picker and input forwarding`.
- [ ] `docs: ADR-003/014 updates and device results`.
Delivered as two PRs: (1) steps 1–5 (setup and helper, visible as "Shell access: ready" with a
self-test), (2) steps 6–9 (the session).

## Review changes (android-architect: APPROVED WITH CHANGES, integrated; they override the sections above where they differ)
**Owner decisions (2026-11-26).**
- *Audio:* captured by the helper, not by MediaProjection. On API 33+ it uses AudioPlaybackCapture as shell; on API 30–32 it uses `AudioRecord(REMOTE_SUBMIX)`. PCM 48 kHz stereo reaches the app through a `ParcelFileDescriptor` socketpair returned by the binder, so nothing listens on the network. It feeds the existing PCM/Opus path and A/V sync. Mode 1 has no MediaProjection, no consent dialog and no SharedAppPicker or Usage access. The FGS type is `connectedDevice`. Locking the phone does not stop the session (Android 15 QPR1+ would stop a projection on lock). Sender playback is muted while capturing.
- *Density:* "the maximum possible" layout space gives a 600 dp short side, `dpi = max(160, shortSide × 160 / 600)`: 192 dpi at 720p, 288 at 1080p, 576 at 2160p. On Android 16+ (apps targeting 36 on a screen with sw ≥ 600 dp) orientation and resizability locks are ignored, so apps fill the TV. Check on Pixel 10 (API 37) that this also applies to virtual displays.

**Display.**
- *Flags:*
  - always: PUBLIC | PRESENTATION | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | DESTROY_CONTENT_ON_REMOVAL;
  - API 33+: also TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED | TOUCH_FEEDBACK_DISABLED;
  - API 34+: also OWN_FOCUS | DEVICE_DISPLAY_GROUP;
  - never ROTATES_WITH_CONTENT or SECURE (FLAG_SECURE content shows black by design).
- *API 30–32:* the display is untrusted (no IME, no decorations). That path is best effort, enabled only after the helper self-test passes.
- *PRESENTATION:* phone apps can see the display through MediaRouter (known effect).
- *Size:* the receiver's full native resolution, always landscape (aspect = (max, min) of the HELLO display; 3840×2160 on a 4K TV), lowered only where the sender encoder or the receiver decoder cannot take it (`fitWithin`, #34). On API 34+ fps goes through `setRequestedRefreshRate`.
- *End of session:* `DESTROY_CONTENT_ON_REMOVAL` **destroys** the content. An app already open on the phone may be moved to the display and then finished: documented, plus a device test.

**Helper.**
- *Command:* `CLASSPATH='<sourceDir>' setsid app_process /system/bin --nice-name=riri_helper dev.ririprojector.helper.HelperMain <pkg> <userId> </dev/null >/dev/null 2>&1 &`.
  - The helper takes the app uid from `IPackageManager.getPackageUid(pkg, 0, userId)`, never from argv, and uses the provider authority `<pkg>.helper`.
  - It uses a FakeContext with package `com.android.shell`.
- *Provider:* `HelperProvider` (manifest of `:core:helper`, `${applicationId}.helper`) accepts the binder only from calling uid 2000 or 0.
- *Version check:* the helper checks `version()` and the APK path, and exits on mismatch (app updated).
- *Interface:*
  - `createDisplay(Surface?, w, h, dpi, fps): int`
  - `setSurface(id, Surface?)`
  - `releaseDisplay(id)`
  - `launch(component, id): int`
  - `oneway injectKey(id, action, keyCode, repeat, meta, source, receivedAtNanos)`
  - `oneway injectMotion(…, receivedAtNanos)`
  - `oneway injectScroll(…)`
  - `openAudio(): ParcelFileDescriptor`
  - `stats(): long[]`
  - `version(): int`
  - `exit()`
  - listener `IRiriHelperListener.onDisplayEmpty(id)`
- *Injection rules:* the helper injects only into displays it created. Event times come from `SystemClock.uptimeMillis()` in the helper.
- *Lifetime:* the helper exits on binder death of the app, on `exit()` (mode disabled), or after 10 min idle.
  - Device test: helper survival when Wireless debugging turns off (`pidof riri_helper`).
  - App side: helper binder death = clean session end, no retry on the same display.

**Session / ADR-010.**
- The helper display lives for the `ProjectionHost` lifetime. Links call `setSurface(null)` and then `setSurface(new)`, so a reconnect keeps the app running.
- Resize sources and the default-display listener are off in mode 1.

**Security (LAN receiver threat model).**
- APP_LAUNCH is accepted only for an id in the APP_LIST sent on this link (LAUNCHER query, RiriProjector itself excluded).
- INPUT_* and APP_LAUNCH are ignored, and counted, unless the session is mode 1.
- Coordinates are clamped to the display size, and the display id comes from the host, never from the wire.

**Protocol v6 (ADR-003).**
- START `u8 mode`.
- APP_LIST 0x09 S→R: `u16` count (an exception to the u8 list rule), entries (`string id`, `string label`).
- APP_ICON 0x0A S→R on the lowest lane: `string id`, `u16 len` + 64×64 WebP, after the first keyframe, re-sent on every link.
- DISPLAY_EMPTY 0x0B S→R: the receiver shows the picker.
- APP_LAUNCH 0x15 R→S: `string id`.
- INPUT_KEY 0x16 R→S: `u8 action`, `u16 keyCode`, `u16 repeat`, `i32 meta`, `u16 source`.
- INPUT_TOUCH 0x17 R→S: `u8 actionMasked`, `u8 actionIndex`, `i32 buttonState`, `u8 count` × (`u8 id`, `u8 toolType`, `u16 x`, `u16 y`).
- INPUT_SCROLL 0x18 R→S: `u16 x`, `u16 y`, `i16 v×100`, `i16 h×100`.
- No timestamps on the wire.

**Receiver key routing in mode 1 (pure function, JVM-tested; revises ADR-001/005).**
- Every key is forwarded, except:
  - MENU opens the picker (which holds Stop and Stats);
  - a long press on BACK leaves the receiver;
  - volume keys stay local;
  - HOME is never injected ("home" = show the picker).
- `InputMapper` lives in `:feature:receiver` (view math, pure).

**Pairing.**
- `SSLSockets.exportKeyingMaterial` (public) on API 31+; hiddenapibypass and Conscrypt reflection only on API 30.
- Pairing runs in an FGS (`shortService` on 34+, else `connectedDevice`) started from the wizard.
- If notifications are denied, the code is typed in an in-app field instead of the notification.

**Threading.**
- Synchronous helper calls run on `HostCtl`; one-way injections run straight from the reader thread.
- The app polls `stats()` once per second for the p95 input latency.

**Modules and build.**
- CLAUDE.md module table and dependency rule gain `:core:adb` and `:core:helper` with the edge `feature:sender → core:helper → core:adb`. `core:adb` has no internal dependencies (it has its own small NSD browse); `feature:receiver` depends on neither.
- CI installs the pinned NDK and CMake. Spotless stays off `.cpp`. ABIs: armeabi-v7a and arm64-v8a.
- Receivers (API 28) never load the native library, BouncyCastle or hiddenapibypass (lazy, behind the API ≥ 30 sender path).
- R8 keep rules cover `HelperMain` and everything it reflects on. The APK size delta is recorded.
- The ADB key prefs are excluded in `data_extraction_rules`.

**Tests added.**
- JVM:
  - `HelperCommand` (quoting, debug package);
  - `DisplayDensity` vectors;
  - APP_LAUNCH whitelist and mode gating;
  - key routing;
  - protocol v6.
- Device:
  - phone locked and screen off during a session;
  - Wireless debugging turned off with the helper alive;
  - reconnect keeps the app;
  - app already open on the phone;
  - BACK on the app's root activity (DISPLAY_EMPTY → picker);
  - FLAG_SECURE and portrait-locked apps;
  - `scripts/helper-selftest.sh` (`HelperMain --selftest` over USB adb, without pairing).

**PR split.**
- PR1: setup and helper (mode hidden; CI NDK; APK size measured).
- PR2: session, protocol v6, helper audio, receiver picker and input.
