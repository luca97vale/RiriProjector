---
name: firetv-deploy
description: Compila l'APK di RiriProjector e lo installa via adb sulla Fire TV (receiver), sul Galaxy Tab (sender) o su entrambi, avvia l'app nella modalità giusta e mostra il logcat filtrato con le metriche. Usare quando bisogna provare una modifica su dispositivi reali.
argument-hint: "[firetv|tablet|both] [--release] [--no-build] [--logs]"
allowed-tools: Bash(scripts/deploy.sh:*), Bash(bash scripts/deploy.sh:*), Bash(adb devices:*), Bash(adb connect:*), Bash(adb -s * logcat:*), Bash(./gradlew assembleDebug:*)
---

# Build + install su device

Argomenti: $ARGUMENTS (default `both`)

1. Prerequisiti, da verificare prima e spiegare all'utente se mancano:
   - `adb` nel PATH o `ANDROID_HOME`/`ANDROID_SDK_ROOT` impostato;
   - Fire TV: *Impostazioni → La mia Fire TV → Opzioni sviluppatore → Debug ADB* attivo
     (su Fire OS 8 le opzioni sviluppatore si sbloccano premendo 7 volte su *Informazioni → Nome*);
     variabile `FIRETV_HOST` con l'IP (es. `export FIRETV_HOST=192.168.1.50`);
   - tablet: debug USB o debug wireless attivo; `TABLET_SERIAL` se più device sono collegati.
   La prima `adb connect` alla Fire TV mostra un popup di autorizzazione sulla TV: avvisa l'utente.
2. Esegui `bash scripts/deploy.sh $ARGUMENTS`. Lo script:
   build (`assembleDebug` o `assembleRelease`) → `adb connect $FIRETV_HOST:5555` →
   `adb install -r -d` sul/i target → avvio dell'activity (receiver su TV, sender su tablet).
3. Se l'installazione fallisce, interpreta l'errore (`INSTALL_FAILED_UPDATE_INCOMPATIBLE` = firma
   diversa → chiedi all'utente prima di disinstallare; `device unauthorized` → accettare il popup;
   `failed to connect` → IP/rete/debug ADB).
4. Con `--logs` (o se l'utente vuole verificare le prestazioni) leggi qualche secondo di log con
   `adb -s <device> logcat -d -s 'Riri/Stats:*' 'Riri/Sender:*' 'Riri/Receiver:*' '*:E'`
   e riassumi fps, bitrate, RTT, frame scartati, errori.
5. Non disinstallare app, non cancellare dati e non riavviare device senza conferma esplicita.
