# Plan #5 — M0 project scaffolding
Issue: #5 · Status: APPROVED

## Goal and acceptance criteria
- Multi-module Gradle Kotlin DSL project: `:app`, `:core:protocol` (pure JVM), `:core:net`, `:core:media`,
  `:feature:sender`, `:feature:receiver`, with convention plugins in `build-logic/`
- Gradle wrapper, version catalog, `minSdk 28`, Java 17 bytecode, Gradle running on JDK 25
- Single manifest with `LAUNCHER` + `LEANBACK_LAUNCHER`, TV banner, `touchscreen required=false`,
  network/NSD/FGS/`RECORD_AUDIO` permissions
- `MainActivity` picks the mode from the `mode` extra or from `FEATURE_LEANBACK`
- Spotless/ktlint, Android Lint, JUnit4/Truth/coroutines-test configured; at least one JVM test
- `./gradlew spotlessCheck testDebugUnitTest lintDebug assembleDebug` green locally and in CI

## Approach
Toolchain (latest stable on 2026-11-19): Gradle 9.9.1, AGP 9.5.1 (built-in Kotlin support, so the
`org.jetbrains.kotlin.android` plugin is not applied), Kotlin 2.4.21, Compose BOM 2026.09.00.
Gradle runs on JDK 25; all modules compile to Java 17 bytecode.

`build-logic/` is an included build exposing three convention plugins so module build files stay
tiny and consistent:
- `riri.android.application` — compileSdk/minSdk/targetSdk, Java 17, lint config, test deps
- `riri.android.library` — same for Android libraries
- `riri.jvm.library` — Kotlin/JVM with Java 17 target and JUnit/Truth (used by `:core:protocol`)

Compose is enabled only where UI needs it (`:feature:sender`); the receiver uses plain Views
(`SurfaceView` later) to keep the TV path minimal, per ADR-005.

`MainActivity` (in `:app`) is a routing activity without UI: it resolves the mode with a pure
`ModeResolver` (explicit `mode` extra wins; otherwise leanback → receiver, else sender) and starts
`SenderActivity` or `ReceiverActivity` from the feature modules. The feature activities are
placeholders in M0.

Alternatives rejected: a single module (would block JVM-only tests for the protocol and blur
boundaries); Hilt/DI framework now (not needed until there are real services, can be added later).

## Changes per module
- root: `settings.gradle.kts`, `build.gradle.kts` (Spotless), `gradle.properties`,
  `gradle/libs.versions.toml`, Gradle wrapper
- `build-logic/convention`: 3 convention plugins
- `:app`: manifest (permissions, features, launchers, banner), `MainActivity`, `ModeResolver` + test,
  icons/banner vector drawables, `applicationIdSuffix ".debug"` for debug
- `:core:protocol`: `ProtocolInfo.PROTOCOL_VERSION` placeholder + test
- `:core:net`, `:core:media`: empty Android libraries with namespaces
- `:feature:sender`: `SenderActivity` with a Compose placeholder screen
- `:feature:receiver`: `ReceiverActivity` full-screen placeholder
- CI: bump actions to the versions proposed by Dependabot (#1–#4)

## Protocol
No change.

## Threading and resource lifecycle
Not applicable (no runtime logic yet).

## Risks and mitigations
- AGP 9 built-in Kotlin / new DSL differences → fix iteratively with a local build.
- JDK 25 support by Gradle/AGP/Kotlin → if the build fails due to JDK 25, configure a Java 21
  toolchain for Gradle daemon (documented in `CLAUDE.md`).

## Test strategy
- JVM: `ModeResolverTest` (extra wins, leanback → receiver, default sender, invalid extra ignored),
  `ProtocolInfoTest`.
- Manual on device: `scripts/deploy.sh both` launches the app in the right mode (when devices are available).

## Implementation steps
- [x] Gradle wrapper, settings, version catalog, root build with Spotless
- [x] Convention plugins in `build-logic`
- [x] Module skeletons (`core:*`, `feature:*`)
- [x] `:app` manifest, `MainActivity`, `ModeResolver` + tests, resources
- [x] CI action bumps; full local check green
