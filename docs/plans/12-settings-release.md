# Plan #12 — M7 settings persistence and signed release
Issue: #12 · Status: APPROVED

## Goal and acceptance criteria (copied from the issue)
Milestone M7: polish and release.

- [ ] Persistent settings and last receiver remembered
- [ ] Release signing from secrets (keystore outside the repo), release workflow on tag attaching the signed APK
- [ ] User README with Fire TV sideloading instructions

Measurable interpretation:
- The tablet's stream settings (codec, max resolution, frame rate, bitrate, audio on/off, sync mode)
  survive an app restart (process killed, app reopened). Invalid or stale stored values fall back to
  the defaults per field instead of crashing.
- The last receiver that reached *Streaming* is offered on the next launch as a one-tap "quick
  start" and pre-fills the manual `host:port` field.
- `./gradlew assembleRelease` with the four `RIRI_*` signing values set produces an APK that
  `apksigner verify` accepts; with none set it still builds (unsigned), so CI and contributors need
  no secrets; with only some set it fails with a clear message.
- Pushing a `v<major>.<minor>.<patch>` tag runs a workflow that builds the signed release APK and
  attaches it (plus the R8 mapping) to a GitHub release for that tag; the workflow fails loudly if a
  secret is missing.
- `README.md` has a user section (install on the Fire TV via Downloader or adb, install on the
  tablet, first use, limitations) and a maintainer section (create the keystore, set the secrets, tag).

## Approach
**Settings.** A pure-Kotlin `SenderSettingsCodec` in `:feature:sender` maps `StreamPreferences` and
the last `ReceiverEndpoint` to and from a flat string key/value store behind a small `KeyValueStore`
interface. The Android implementation wraps a private `SharedPreferences` file
(`riri_sender_settings`). Decoding validates every field against the choices the UI offers (height ∈
{720, 1080, 1440}, fps ∈ {30, 60}, bitrate ∈ {auto, 8, 16, 30} Mbps, enum names) and falls back to the
default per field, so a renamed enum or a future choice list never crashes an older build. A
`settings.version` key is written for future migrations. `preferredAudioCodec` is not persisted: it
always takes the `StreamPreferences` default.

`SenderScreen` keeps its `rememberSaveable` state (rotation works as today) but initialises it from
the loaded settings and persists changes through a single `LaunchedEffect(prefs)` (the
first-composition write stores an identical value: harmless).

The last receiver is saved when `SenderStatus.state` reaches `Streaming` (the endpoint demonstrably
works), observed from the activity's `lifecycleScope` as
`state.mapNotNull { (it as? SenderState.Streaming)?.target }.distinctUntilChanged()`, so it is
written once per target, not on every once-a-second stats update. A recreated activity saves once
more (harmless). Known gap, accepted to keep `ProjectionService` untouched: if the activity is
destroyed in the background and a session reaches Streaming and ends before it comes back, nothing is
saved.

Only TCP-connectable targets are remembered: `SenderSettingsCodec.isRememberable` rejects endpoints
that `ReceiverEndpoint.parse("$host:$port")` rejects and the reserved Wi-Fi Direct placeholder host
`"wifi-direct"` (ADR-011, on `feat/21-wifi-direct`). Quick start resolves against the live NSD list by
service `name` first (Fire TV DHCP leases change the IP), falling back to the stored host only when the
receiver is not advertised. Manual endpoints have `name == host`, so they match by host.

*Rejected:* **DataStore** (in the catalog, unused) — its async first read would render the defaults
for a frame and then jump, or require blocking the main thread; a few small values written on taps do
not need it. Saving the receiver at *tap* time — it would remember a typo'd IP that never connected.
Persisting from `ProjectionService` — that file is heavily changed on `feat/21-wifi-direct`.

**Release signing.** `app/build.gradle.kts` reads four values with
`providers.environmentVariable("RIRI_X").orElse(providers.gradleProperty("riri.x")).orNull`
(configuration-cache tracked inputs): `RIRI_KEYSTORE_FILE` (absolute path), `RIRI_KEYSTORE_PASSWORD`,
`RIRI_KEY_ALIAS`, `RIRI_KEY_PASSWORD`. All-or-none: zero → no signing config (unsigned build, the
default); four → `signingConfigs.create("release")` assigned to the release build type; one to three →
`error(...)` so a typo does not silently yield an unsigned APK. Gradle properties let a developer use
the user-level `~/.gradle/gradle.properties` (plaintext, but outside the repo; environment variables
stay the primary path). No `local.properties` / `keystore.properties` parsing: those files are blocked
by hooks. `versionName` / `versionCode` come from optional properties `riri.versionName` /
`riri.versionCode` (defaults `0.1.0` / `1`); the workflow derives them from the tag
(`code = major*10000 + minor*100 + patch`, minor and patch ≤ 99).

**Release workflow.** `.github/workflows/release.yml` on `push: tags: ['v*.*.*']`, job bound to a
GitHub Environment `release` (the user may scope the secrets to it and add a required reviewer):
- top-level `permissions: contents: read`; `contents: write` only on the job; `concurrency:
  release-${{ github.ref }}`; `timeout-minutes: 30`;
- secrets passed only through `env:`, never interpolated in `run:`; the first step fails with an
  `::error::` naming any missing secret;
- tag validated with `^v([0-9]+)\.([0-9]+)\.([0-9]+)$`, minor/patch ≤ 99; the tagged commit must be
  on `main` (`fetch-depth: 0` + `git merge-base --is-ancestor`);
- keystore decoded with `printf '%s' "$KS_B64" | base64 --decode` into `$RUNNER_TEMP/release.jks`,
  `RIRI_KEYSTORE_FILE` exported through `$GITHUB_ENV`, removed in an `if: always()` step;
- `setup-gradle` with `cache-disabled: true` (a signing build must not read PR caches nor persist a
  configuration-cache entry holding passwords);
- `./gradlew testDebugUnitTest assembleRelease -Priri.versionName=… -Priri.versionCode=…`;
- fail if `app-release.apk` is missing; `apksigner verify --min-sdk-version 28 --print-certs` from the
  newest `$ANDROID_HOME/build-tools/*`;
- `gh release create "$GITHUB_REF_NAME" --verify-tag --generate-notes` with
  `ririprojector-<v>.apk` and `ririprojector-<v>-mapping.txt`, falling back to
  `gh release upload --clobber` on re-runs.

CI (`ci.yml`) additionally runs `assembleRelease` unsigned after `assembleDebug`, so an R8 or
`lintVitalRelease` failure shows up on the PR, not at tag time.

**R8.** Release minify has never run. Runtime risk is low (no app-side reflection, JNI or
`ServiceLoader`; Compose, coroutines and AndroidX ship keep rules; enums persisted by `name` are
kept). `app/proguard-rules.pro` gets `-keepnames class dev.ririprojector.** extends java.lang.Throwable`
(error messages show `javaClass.simpleName`), `-keepattributes SourceFile,LineNumberTable` and
`-renamesourcefileattribute SourceFile`; `mapping.txt` is published with each release so user logcat
traces can be retraced.

**README.** A user part (what it is, requirements, install on the Fire TV via Downloader or
`adb install -r`, per Amazon's docs; install on the tablet; first use; limitations: DRM/FLAG_SECURE,
apps that block audio capture, upgrades need the same key and a higher versionCode), the existing
developer part, and "Releasing": `keytool -genkeypair … -keyalg RSA -keysize 4096 -validity 10000`
stored outside the repo with a backup, `base64 -w0` for the secret, the four secrets, the `release`
environment, tagging, and a smoke-test checklist. Losing the keystore means users must uninstall to
upgrade. The user creates the keystore and secrets themselves.

## Changes per module
### `:feature:sender`
- **New** `SenderSettings.kt` (Android-free):
  ```kotlin
  interface KeyValueStore {
      fun getString(key: String): String?
      fun putAll(values: Map<String, String?>) // null value = remove
  }
  data class SenderSettings(
      val prefs: StreamPreferences = StreamPreferences(),
      val lastReceiver: ReceiverEndpoint? = null,
  )
  /** endpoint = what to connect to; advertised = it matched a live NSD entry (card hidden, entry marked). */
  data class QuickStart(val endpoint: ReceiverEndpoint, val advertised: Boolean)
  object SenderSettingsCodec {
      const val VERSION = 1
      val HEIGHTS: List<Int>; val FPS: List<Int>; val BITRATES_MBPS: List<Int>
      fun decode(read: (String) -> String?): SenderSettings
      fun encodePrefs(prefs: StreamPreferences): Map<String, String?>
      fun encodeReceiver(r: ReceiverEndpoint): Map<String, String?>
      fun isRememberable(endpoint: ReceiverEndpoint): Boolean
      fun resolveQuickStart(last: ReceiverEndpoint?, nsd: List<ReceiverEndpoint>): QuickStart?
  }
  class SenderSettingsRepository(private val store: KeyValueStore) {
      fun load(): SenderSettings
      fun savePrefs(prefs: StreamPreferences)
      fun saveLastReceiver(r: ReceiverEndpoint) // ignored unless isRememberable
  }
  ```
  `decode` wraps the `ReceiverEndpoint` constructor (throws on blank host / bad port) in
  `runCatching` → `lastReceiver = null`.
- **New** `SharedPreferencesStore.kt`: `internal class SharedPreferencesStore(sp: SharedPreferences) : KeyValueStore`.
- **New** `LastReceiverCard.kt`:
  `@Composable internal fun LastReceiverCard(quickStart: QuickStart?, enabled: Boolean, onStart: (ReceiverEndpoint) -> Unit)`
  (renders nothing when `quickStart` is null or advertised).
- **Changed** `SenderActivity.kt`, deliberately minimal because #21 rewrites the same regions: in the
  activity, construct the repository and add the deduplicated collector; in `SenderScreen`, parameters
  appended **at the end** of the signature (`settings: SenderSettings`, `onSettingsChange`), the
  initial values of the `rememberSaveable` lines read from `settings` (manual field pre-filled), one
  `LaunchedEffect(prefs)`, one `LastReceiverCard(...)` call just before the manual-field `Row`, and a
  "last used" line on the matching NSD card. Chip lambdas and choice lists stay as they are; the
  codec's validation lists mirror them.
- **Changed** `strings.xml`: `sender_last_receiver`, `sender_last_used`.

### `:app`
- **Changed** `build.gradle.kts`: signing config (all-or-none), version from properties.
- **Changed** `proguard-rules.pro`: the keep rules above.

### CI / docs
- **New** `.github/workflows/release.yml`; **changed** `ci.yml` (unsigned `assembleRelease` step).
- **Changed** `README.md`, `docs/PLAN.md` (M7 status), `docs/ARCHITECTURE.md` new
  "ADR-012 — Settings persistence and release signing" (ADR-011 is taken by #21's branch).

## Protocol
No change. `PROTOCOL_VERSION` stays.

## Threading and resource lifecycle
`SharedPreferences` is read once in `onCreate` (small file; the activity already reads its own
preferences for `usageAccessAsked`). Writes use `apply()` (async disk write), triggered on chip taps
and once per new streaming target — never on a codec or socket thread. The `SenderStatus` collector
lives in `lifecycleScope` and is cancelled with the activity. No new long-lived resources.

## Risks and mitigations
| Risk | Mitigation |
|---|---|
| Release build (R8) never built so far: may fail to build or break at runtime | built in CI on every PR; keep rules and mapping; README release checklist asks for a smoke test of the signed APK on both devices before announcing (not possible in this task: devices are busy) |
| Debug and release signed with different keys | different application IDs (`.debug` suffix), so both install side by side; documented |
| Secrets leak | only via `env:`; GitHub masks them; keystore in `$RUNNER_TEMP`, deleted; caches disabled for the release job; no `set -x` |
| Any pushed tag can use the key | `release` environment (optional required reviewer) and tag-on-`main` check |
| Merge conflict with `feat/21-wifi-direct` in `SenderActivity.kt` | new UI in new files, appended parameters, no reformatting. Merge checklist (also left as a hand-over note): if #21 renames `DIRECT_HOST = "wifi-direct"`, update the codec's reserved host |
| Stale stored IP after a DHCP change | quick start matches the NSD list by service name first |
| Lost keystore | documented: users must uninstall to upgrade; keep a backup outside the repo |
| Stored values from a future version | per-field validation with defaults; version key |

## Architect review
android-architect: **APPROVED WITH CHANGES**. Integrated: deduplicated Streaming observer, rememberable
targets decided in code, minimal `SenderActivity.kt` diff with UI in new files, NSD-name quick-start
resolution, safe endpoint decoding, workflow secrets/fail-fast/tag checks/cache-disabled, all-or-none
signing, `apksigner` path and re-run handling, R8 keep rules and mapping, plus optional `release`
environment and tag-on-main check, Android-free settings file, README sideloading details. Left as a
follow-up: pinning the release certificate SHA-256 in a repository variable.

## Test strategy
- **JVM** (`feature/sender/src/test`): `SenderSettingsCodecTest` with an in-memory store: empty →
  defaults; round trip of every field and choice; invalid values (unknown enum, height 999, port 0,
  empty host, non-numeric) → per-field defaults / `lastReceiver = null`; `isRememberable` (rejects
  `wifi-direct`, unparsable hosts); `resolveQuickStart` (name match with a fresh host, manual endpoint
  matched by host, no match → stored endpoint, null → null); repository save/load and ignored
  non-rememberable save.
- **Build**: `assembleDebug testDebugUnitTest lintDebug`, unsigned `assembleRelease`; workflow YAML
  checked with `actionlint` if available.
- **Manual on device** (later, by the user — devices are in use by another session): change settings,
  force-stop, reopen → settings and last receiver restored; tap quick start → mirrors. Install the
  signed APK from the first GitHub release on both devices.

## Implementation steps
1. `feat(sender): persist stream settings and last receiver` — `SenderSettings.kt`,
   `SharedPreferencesStore.kt` + JVM tests.
2. `feat(sender): restore settings and quick-start last receiver` — `LastReceiverCard.kt`,
   `SenderActivity.kt`, strings.
3. `chore(app): release signing from environment and tag-derived version` — `app/build.gradle.kts`,
   `app/proguard-rules.pro`.
4. `ci: release workflow on tag and unsigned release build in CI`.
5. `docs: user README, release guide, ADR-012, PLAN M7`.
