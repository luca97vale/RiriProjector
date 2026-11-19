# RiriProjector — guida per Claude Code

Un'unica app Android (Kotlin) che proietta lo schermo di un **Galaxy Tab** (sender) su una
**Fire TV Stick / proiettore Android** (receiver) in LAN, con latenza e qualità migliori di Miracast.

```
Tablet (sender)                                         Fire TV (receiver)
MediaProjection → VirtualDisplay → Surface              NSD discovery (_ririproj._tcp)
  → MediaCodec encoder HEVC|H.264 (realtime, CBR)       TCP server ← HELLO/caps
  → framing protocollo → TCP (TCP_NODELAY) ───────────→ deframing → MediaCodec decoder (low-latency)
  ← REQUEST_KEYFRAME / PING / BITRATE  ←─────────────── → SurfaceView (render immediato)
AudioPlaybackCapture → PCM 48k (o Opus) ─────────────→ jitter buffer → AudioTrack low-latency
                                                         (allineato ai pts del video, A/V ±40 ms)
```

L'**audio è obbligatorio**: una proiezione senza audio non è considerata funzionante (ADR-007).

Il receiver pubblica il servizio via NSD; il sender lo scopre e si connette. Decisioni di dettaglio
in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); roadmap in [docs/PLAN.md](docs/PLAN.md).

## Struttura dei moduli (target)

| Modulo | Tipo | Responsabilità |
|---|---|---|
| `:app` | Android app | Activity di avvio, scelta modalità (auto-receiver su TV/leanback), DI, manifest unico |
| `:core:protocol` | **Kotlin/JVM puro** | Framing, messaggi, (de)serializzazione, versioning. Zero dipendenze Android → test JVM veloci |
| `:core:net` | Android lib | Trasporto TCP (client/server), NSD publish/discover, backpressure, misure RTT |
| `:core:media` | Android lib | Wrapper encoder/decoder MediaCodec (video e Opus), selezione codec da `MediaCodecList`, parametri low-latency, cattura audio (`AudioPlaybackCapture`) e riproduzione (`AudioTrack`) |
| `:feature:sender` | Android lib | MediaProjection (video + audio), foreground service `mediaProjection`, UI tablet |
| `:feature:receiver` | Android lib | Activity leanback full-screen con `SurfaceView`, player audio, sincronizzazione A/V, overlay statistiche |

Regola di dipendenza: `feature → core:media/net → core:protocol`. Le `feature` non dipendono tra loro.
Tutta la logica testabile senza dispositivo (framing, policy di bitrate, scelta codec da una lista
di capabilities, macchina a stati di sessione) sta in codice puro, iniettabile e coperto da test JVM.

## Comandi

Su Windows usa `./gradlew` da Git Bash (o `gradlew.bat`). JDK 25 LTS (in locale `D:\Android\jdk25`)
per eseguire Gradle, Android SDK in `D:\Android\Sdk` con platform-tools. Il bytecode dell'app
resta target Java 17 (`jvmTarget`/`compileOptions`), che è quello supportato da Android.

```bash
./gradlew assembleDebug              # build APK unico (sender+receiver)
./gradlew testDebugUnitTest          # unit test JVM (tutti i moduli)
./gradlew :core:protocol:test        # solo protocollo, il ciclo più veloce
./gradlew spotlessApply              # formattazione ktlint
./gradlew spotlessCheck lint         # quello che gira in CI
./gradlew connectedDebugAndroidTest  # test strumentati (tablet collegato)
scripts/deploy.sh firetv|tablet|both # build + install via adb (vedi /firetv-deploy)
```

Variabili d'ambiente locali (mai committate): `FIRETV_HOST` (IP della Fire TV, porta 5555 di default),
`TABLET_SERIAL` (seriale adb del tablet, opzionale).

## Flusso SDLC (obbligatorio)

Ogni modifica non banale segue: **requisito → piano → implementazione → test → review → PR**.
Dettagli in [docs/SDLC.md](docs/SDLC.md). Skill disponibili:

| Fase | Skill | Output |
|---|---|---|
| Requisito | `/sdlc-req` | Issue GitHub con criteri di accettazione misurabili |
| Piano | `/sdlc-plan <issue>` | `docs/plans/<issue>-<slug>.md` validato dall'agente `android-architect` |
| Implementazione | `/sdlc-impl <issue>` | Commit piccoli sul branch `feat/<issue>-<slug>` |
| Test | `/sdlc-test` | Test scritti dall'agente `test-writer`, suite verde |
| Review | `/sdlc-review` | Review dell'agente `media-net-reviewer` + correzioni |
| PR | `/sdlc-pr` | PR con template compilato, collegata all'issue |
| Tutto il giro | `/sdlc-feature <descrizione>` | Orchestrazione delle fasi sopra, con pause per l'approvazione |
| Device | `/firetv-deploy` | Build, install su Fire TV/tablet, logcat filtrato |

## Convenzioni

- Kotlin idiomatico, coroutines + `Flow`; niente `GlobalScope`. Thread dedicati (non `Dispatchers.Default`)
  per i loop bloccanti di codec e socket, con priorità alta (`THREAD_PRIORITY_URGENT_DISPLAY` / `_AUDIO`).
- **Hot path senza allocazioni**: nel loop encoder→socket e socket→decoder riusa `ByteBuffer`/array,
  niente boxing, niente log per-frame (solo contatori aggregati ogni secondo).
- Ogni risorsa (MediaCodec, VirtualDisplay, MediaProjection, Socket, NsdManager listener) ha un
  proprietario chiaro e viene rilasciata in `finally`/`close()`; gli stati di errore portano a
  una ricostruzione pulita della sessione, non a un crash.
- Formato wire: big-endian, versione nell'header, campi a lunghezza fissa; ogni modifica al protocollo
  incrementa `PROTOCOL_VERSION` e aggiorna `docs/ARCHITECTURE.md`.
- Log: tag `Riri/<Componente>`; metriche (fps, bitrate, RTT, frame scartati, latenza decode)
  esposte via `StateFlow` per l'overlay.
- Commit: Conventional Commits (`feat:`, `fix:`, `perf:`, `test:`, `chore:`, `docs:`), in inglese.
  Branch: `feat/<issue>-<slug>`, `fix/<issue>-<slug>`, `chore/<slug>`. Mai commit diretti su `main`.
- Documentazione e discussione con l'utente in italiano; codice, identificatori e commit in inglese.

## Vincoli di piattaforma da ricordare

- Fire TV Stick: Fire OS 7 = Android 9 (API 28), Fire OS 8 = Android 11 (API 30) → `minSdk 28`.
  `KEY_LOW_LATENCY` solo da API 30: su API 28 usare chiavi vendor se presenti, altrimenti
  ridurre il buffering lato app. Serve `LEANBACK_LAUNCHER`, banner 320×180,
  `uses-feature touchscreen required=false`.
- Galaxy Tab (Android 14+): consenso MediaProjection a ogni sessione, foreground service con
  `foregroundServiceType="mediaProjection"` avviato **prima** di `getMediaProjection`,
  callback `MediaProjection.Callback` registrata prima di creare il VirtualDisplay.
- Audio: `AudioPlaybackCapture` richiede API 29+ e `RECORD_AUDIO`; le app con
  `allowAudioPlaybackCapture=false` non sono catturabili (limite da mostrare in UI, non un bug).
  Il jitter buffer audio è logica pura testata su JVM; mai bloccare il thread di `AudioTrack`.
- Non dare per scontato HEVC: negozia il codec in base alle capabilities annunciate dal receiver
  (HELLO) e a quelle dell'encoder locale; fallback H.264.

## Cose da non fare

- Non aggiungere librerie di streaming pesanti (WebRTC, ffmpeg, ExoPlayer) senza una decisione
  registrata in `docs/ARCHITECTURE.md` e approvata dall'utente.
- Non modificare `gradle/wrapper/gradle-wrapper.jar`, keystore o file `*.properties` con segreti
  (bloccato dagli hook).
- Non fare `git push --force` né push su `main` (bloccato dagli hook). Le PR si aprono con `/sdlc-pr`.
