---
name: device-deploy
description: Builds the RiriProjector APK and installs it via adb on the receiver (any Android TV, box, projector, phone or tablet), the sender (any Android phone or tablet) or both, launches the app in the right mode and shows the filtered logcat with metrics. Use when a change must be tried on real devices.
argument-hint: "[receiver|sender|both] [--release] [--no-build] [--logs]"
allowed-tools: Bash(scripts/deploy.sh:*), Bash(bash scripts/deploy.sh:*), Bash(adb devices:*), Bash(adb connect:*), Bash(adb -s * logcat:*), Bash(./gradlew assembleDebug:*)
---

# Build + install on devices

Arguments: $ARGUMENTS (default `both`). The legacy targets `firetv` (= receiver) and `tablet`
(= sender) still work.

1. Prerequisites to check first, explaining to the user what is missing:
   - `adb` on PATH or `ANDROID_HOME`/`ANDROID_SDK_ROOT` set (locally `D:\Android\Sdk`);
   - receiver: ADB debugging enabled (on TV devices usually under *Settings → Device preferences /
     My Fire TV → Developer options*; developer options are unlocked by pressing *About → Build* or
     *About → Name* 7 times). Either `RECEIVER_HOST` set to its IP for adb over the network
     (e.g. `export RECEIVER_HOST=192.168.1.50`, port `RECEIVER_PORT`, default 5555) or
     `RECEIVER_SERIAL` for a USB-attached receiver;
   - sender: USB or wireless debugging enabled; `SENDER_SERIAL` if several devices are attached.
   - Legacy variables `FIRETV_HOST`, `FIRETV_PORT` and `TABLET_SERIAL` are used when the new ones are unset.
   The first `adb connect` to a network receiver shows an authorization popup on it: warn the user.
2. Run `bash scripts/deploy.sh $ARGUMENTS`. The script does:
   build (`assembleDebug` or `assembleRelease`) → `adb connect $RECEIVER_HOST:$RECEIVER_PORT` (network
   receivers only) → `adb install -r -d` on the target(s) → launches the app with `--es mode receiver`
   or `--es mode sender`.
3. If installation fails, interpret the error (`INSTALL_FAILED_UPDATE_INCOMPATIBLE` = different
   signature → ask the user before uninstalling; `device unauthorized` → accept the popup;
   `failed to connect` → IP/network/ADB debugging).
4. With `--logs` (or when the user wants to check performance) read a few seconds of logs with
   `adb -s <device> logcat -d -s 'Riri/Stats:*' 'Riri/Sender:*' 'Riri/Receiver:*' '*:E'`
   and summarize fps, bitrate, RTT, dropped frames, A/V offset, errors.
5. Never uninstall apps, clear data or reboot devices without explicit confirmation.
