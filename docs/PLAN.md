# Piano di implementazione — RiriProjector

Roadmap a milestone. Ogni voce `#?` diventa un'issue con `/sdlc-req` e segue il ciclo SDLC
(piano dettagliato in `docs/plans/` prima del codice). Le milestone sono ordinate per ridurre
il rischio: prima si dimostra la catena video end-to-end, poi si ottimizza la latenza.

**Obiettivo misurabile del MVP**: 1080p60 HEVC (o H.264) **con audio** dal Galaxy Tab alla Fire TV
su Wi-Fi 5 GHz, latenza glass-to-glass ≤ 100 ms p95 (target finale ≤ 60 ms), scarto audio/video
entro ±40 ms, nessun freeze o buco audio > 500 ms in 10 minuti, riconnessione automatica entro 3 s.
Il MVP è completo solo alla fine di M5 (audio incluso).

---

## M0 — Scaffolding del progetto (1 PR)
- Progetto Gradle Kotlin DSL multi-modulo come in `CLAUDE.md` (`:app`, `:core:protocol` JVM,
  `:core:net`, `:core:media`, `:feature:sender`, `:feature:receiver`), version catalog,
  convention plugin in `build-logic/` per non ripetere la configurazione Android.
- `applicationId dev.ririprojector` (`.debug` per il debug), `minSdk 28`, `targetSdk`/`compileSdk`
  ultimi stabili; Gradle eseguito con JDK 25 LTS, bytecode target Java 17. Verificare che le
  versioni di Gradle/AGP/Kotlin scelte supportino ufficialmente JDK 25; se no, toolchain JDK 21.
- Manifest: `LAUNCHER` + `LEANBACK_LAUNCHER`, banner TV, `touchscreen required=false`,
  permessi `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `POST_NOTIFICATIONS`, `RECORD_AUDIO`.
- `MainActivity` che legge l'extra `mode` (usato da `scripts/deploy.sh`) o sceglie in base a
  `FEATURE_LEANBACK`.
- Spotless + ktlint, Lint con baseline vuota, JUnit4/Truth/coroutines-test, un test di esempio.
- Verifica: CI verde, `scripts/deploy.sh both` installa e avvia l'app vuota su entrambi i device.

## M1 — Protocollo (`:core:protocol`, puro JVM)
- Implementazione di ADR-003: `FrameHeader`, `Message` sealed, `MessageWriter`/`MessageReader`
  su `InputStream`/`OutputStream` con buffer riusabili, limite `payloadLen`, `PROTOCOL_VERSION`.
- Capability model: `CodecCapability(mime, profiles, maxWidth, maxHeight, maxFps, lowLatency)`
  e `CodecNegotiator` (sceglie codec/risoluzione/fps/bitrate dati sender + receiver) — puro, testato.
- Test: round-trip di ogni messaggio, stream frammentato byte-per-byte, header corrotti, versione
  sconosciuta, payload oltre il limite, negoziazione su matrici di capability.

## M2 — Rete e scoperta (`:core:net`)
- `ReceiverServer` (ServerSocket, una sessione attiva alla volta) e `SenderClient`
  (connect con timeout, `TCP_NODELAY`, send buffer ridotto, DSCP video).
- `NsdPublisher`/`NsdBrowser` con API `Flow`, TXT record, fallback IP manuale.
- `SessionStateMachine` pura: Idle → Discovering → Connecting → Handshaking → Streaming →
  Reconnecting → Closed, con timeout e backoff; PING/PONG per RTT.
- Test: JVM su socket loopback (handshake completo, chiusura remota, timeout), strumentato NSD.

## M3 — Receiver (Fire TV)
- `ReceiverActivity` leanback full-screen con `SurfaceView`, keep-screen-on, overlay statistiche
  attivabile dal telecomando.
- `VideoDecoder` (`:core:media`): selezione decoder HW da `MediaCodecList`, configurazione
  low-latency (ADR-005), modalità asincrona, gestione CONFIG/keyframe, drop fino al prossimo
  IDR + REQUEST_KEYFRAME in caso di backlog.
- HELLO con le capability reali del device.
- Verifica intermedia: un test strumentato/tool di debug che invia uno stream H.264 registrato
  (fixture) al receiver e lo mostra.

## M4 — Sender (Galaxy Tab) → **primo end-to-end**
- UI Compose: lista receiver scoperti, stato, impostazioni (codec, risoluzione, fps, bitrate).
- `ProjectionService` foreground (`mediaProjection`): consenso, `MediaProjection.Callback`,
  `VirtualDisplay` sulla Surface dell'encoder, stop da notifica.
- `VideoEncoder` (`:core:media`): HEVC/H.264 HW, parametri ADR-004, richiesta sync frame,
  aggiornamento bitrate a caldo.
- Loop encoder → `MessageWriter` su thread dedicato, coda limitata con drop dei non-keyframe.
- Verifica: `/firetv-deploy both`, proiezione stabile 10 minuti, metriche in overlay.

## M5 — Audio (ADR-007, obbligatorio per il MVP)
- Protocollo: `AUDIO_CONFIG`/`AUDIO_FRAME`, negoziazione PCM/Opus in HELLO/START, bump di
  `PROTOCOL_VERSION`; decisione finale sul trasporto (socket dedicato vs scheduler a priorità)
  registrata nell'ADR.
- Sender: `AudioCapture` con `AudioPlaybackCapture` sulla stessa `MediaProjection`, permesso
  `RECORD_AUDIO`, pacchetti da 10 ms con pts sulla base tempi del video, encoder Opus opzionale.
- Receiver: `AudioPlayer` con `AudioTrack` low-latency, `JitterBuffer` puro (testato su JVM:
  underrun → silenzio, accumulo → drop, riallineamento ai pts), decoder Opus opzionale.
- Sincronizzazione A/V tramite pts + `AudioTrack.getTimestamp`; scarto A/V nell'overlay.
- UI: indicatore "audio non catturabile" per le app che lo vietano; verifica e gestione
  dell'audio che esce ancora dagli speaker del tablet.
- Verifica: video con clap/flash di test → scarto A/V misurato ≤ 40 ms; 10 minuti di musica senza
  buchi udibili.

## M6 — Latenza e robustezza
- Misura: timestamp sender nei FRAME + stima offset di clock via PING → latenza
  capture→decode in overlay; procedura glass-to-glass (cronometro sul tablet filmato insieme
  alla TV a 240 fps) documentata in `docs/`.
- Bitrate adattivo (`BitratePolicy` pura) basato su coda di invio e STATS.
- Intra-refresh dove supportato; tuning di `KEY_REPEAT_PREVIOUS_FRAME_AFTER`, operating rate,
  chiavi vendor (MediaTek su Fire TV, Exynos/Qualcomm su Samsung).
- Rotazione e cambio risoluzione (`onCapturedContentResize`) con riconfigurazione dell'encoder.
- Riconnessione automatica, gestione Wi-Fi che cade, receiver in background.

## M7 — Rifinitura e rilascio
- Impostazioni persistenti, ultimo receiver ricordato, avvio rapido.
- Firma release (keystore fuori dal repo, secret in GitHub Actions), workflow di release su tag
  che allega l'APK firmato.
- README utente: installazione sideload sulla Fire TV.

---

## Rischi principali
| Rischio | Impatto | Mitigazione |
|---|---|---|
| Decoder Fire TV senza vera modalità low-latency su Fire OS 7 | latenza alta | misurare presto (M3), chiavi vendor, ridurre a 1 frame in volo, fallback H.264 |
| Multicast/NSD filtrato dal router | scoperta assente | inserimento IP manuale + QR code con IP:porta sulla TV |
| Picchi da keyframe su Wi-Fi | stutter | intra-refresh, IDR solo su richiesta, CBR con VBV ridotto |
| Consenso MediaProjection Android 14+ ad ogni sessione | UX | sessione lunga con riconnessione senza perdere il token finché il service è vivo |
| Contenuti protetti (DRM/FLAG_SECURE) mostrati neri | atteso | documentarlo come limite |
| App che vietano la cattura audio (`allowAudioPlaybackCapture=false`) | audio muto per quelle app | rilevarlo e mostrarlo in UI; documentarlo come limite |
| Latenza di uscita audio HDMI della Fire TV diversa dal video | lip-sync fuori | misura con `AudioTrack.getTimestamp`, ritardo video configurabile |
| Pacchetti audio in coda dietro keyframe video | buchi audio | socket audio dedicato o scheduler a priorità (ADR-007), jitter buffer adattivo |
