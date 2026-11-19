---
name: firetv-deploy
description: Builds the RiriProjector APK and installs it via adb on the Fire TV (receiver), the Galaxy Tab (sender) or both, launches the app in the right mode and shows the filtered logcat with metrics. Use when a change must be tried on real devices.
argument-hint: "[firetv|tablet|both] [--release] [--no-build] [--logs]"
allowed-tools: Bash(scripts/deploy.sh:*), Bash(bash scripts/deploy.sh:*), Bash(adb devices:*), Bash(adb connect:*), Bash(adb -s * logcat:*), Bash(./gradlew assembleDebug:*)
---

# Build + install on devices

Arguments: $ARGUMENTS (default `both`)

1. Prerequisites to check first, explaining to the user what is missing:
   - `adb` on PATH or `ANDROID_HOME`/`ANDROID_SDK_ROOT` set (locally `D:\Android\Sdk`);
   - Fire TV: *Settings → My Fire TV → Developer options → ADB debugging* enabled
     (on Fire OS 8 developer options are unlocked by pressing *About → Name* 7 times);
     `FIRETV_HOST` set to its IP (e.g. `export FIRETV_HOST=192.168.1.50`);
   - tablet: USB or wireless debugging enabled; `TABLET_SERIAL` if several devices are attached.
   The first `adb connect` to the Fire TV shows an authorization popup on the TV: warn the user.
2. Run `bash scripts/deploy.sh $ARGUMENTS`. The script does:
   build (`assembleDebug` or `assembleRelease`) → `adb connect $FIRETV_HOST:5555` →
   `adb install -r -d` on the target(s) → launches the activity (receiver on TV, sender on tablet).
3. If installation fails, interpret the error (`INSTALL_FAILED_UPDATE_INCOMPATIBLE` = different
   signature → ask the user before uninstalling; `device unauthorized` → accept the popup;
   `failed to connect` → IP/network/ADB debugging).
4. With `--logs` (or when the user wants to check performance) read a few seconds of logs with
   `adb -s <device> logcat -d -s 'Riri/Stats:*' 'Riri/Sender:*' 'Riri/Receiver:*' '*:E'`
   and summarize fps, bitrate, RTT, dropped frames, A/V offset, errors.
5. Never uninstall apps, clear data or reboot devices without explicit confirmation.
