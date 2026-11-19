# Architettura e decisioni (ADR leggere)

Ogni decisione ha uno stato: **Accettata**, **Proposta**, **Superata**. Le nuove decisioni si
aggiungono in fondo con numero progressivo; non si riscrivono quelle accettate, si superano.

---

## ADR-001 — App unica con due modalità
**Accettata.** Un solo APK. All'avvio: se il dispositivo ha `FEATURE_LEANBACK` (Fire TV/proiettore)
parte direttamente il receiver; altrimenti si mostra la scelta (default sender).
Il manifest dichiara sia `LAUNCHER` sia `LEANBACK_LAUNCHER`, `touchscreen required=false`,
banner per la TV.

## ADR-002 — Trasporto: TCP con framing proprio, niente RTP/WebRTC
**Accettata.** In una LAN domestica TCP con `TCP_NODELAY` dà latenza sufficiente e affidabilità
gratuita; la gestione della congestione si fa a livello applicativo scartando frame non-chiave
quando la coda di invio cresce e chiedendo un keyframe al receiver. UDP/RTP con FEC resta
un'opzione futura (ADR da scrivere) se le misure mostrano picchi da head-of-line blocking su Wi-Fi.

Socket: `sendBufferSize` piccolo (≈ 2–4 frame al bitrate corrente) per non nascondere la congestione
nel kernel; `trafficClass` con DSCP AF41 / video; `keepAlive`.

## ADR-003 — Formato wire v1
**Proposta** (da finalizzare nel milestone M1).

Header fisso 16 byte, big-endian:

| Offset | Campo | Tipo | Note |
|---|---|---|---|
| 0 | magic | u16 | `0x5252` ("RR") |
| 2 | version | u8 | `PROTOCOL_VERSION` = 1 |
| 3 | type | u8 | vedi tabella messaggi |
| 4 | flags | u8 | bit0 = keyframe, bit1 = codec config |
| 5 | reserved | u8 | 0 |
| 6 | payloadLen | u32 | limite massimo configurabile (default 8 MiB) → oltre = errore di protocollo |
| 10 | ptsUs/seq | u48 | pts in µs per i frame, numero di sequenza per i controlli |

Messaggi:

| type | Nome | Direzione | Payload |
|---|---|---|---|
| 0x01 | HELLO | R→S | versione, nome dispositivo, risoluzione display, refresh rate, lista codec decodificabili con profili/livelli e flag low-latency |
| 0x02 | START | S→R | codec scelto, larghezza, altezza, fps, bitrate iniziale |
| 0x03 | CONFIG | S→R | CSD (VPS/SPS/PPS) così come escono da `BUFFER_FLAG_CODEC_CONFIG` |
| 0x04 | FRAME | S→R | access unit Annex-B |
| 0x05 | AUDIO_CONFIG | S→R | codec audio (PCM s16le / Opus), sample rate, canali, durata pacchetto; CSD Opus se presente |
| 0x06 | AUDIO_FRAME | S→R | pacchetto audio (PCM o Opus) con pts in µs sulla stessa base tempi del video |
| 0x10 | REQUEST_KEYFRAME | R→S | — |
| 0x11 | PING / 0x12 PONG | ↔ | timestamp monotono del mittente (RTT e stima offset di clock) |
| 0x13 | STATS | R→S | fps decodificati, frame scartati, latenza di decode media/p95 |
| 0x14 | BITRATE_HINT | R→S | bitrate suggerito (adattamento) |
| 0x7F | BYE | ↔ | codice motivo |

## ADR-004 — Pipeline encoder (sender)
**Accettata.**
- Sorgente: `MediaProjection.createVirtualDisplay` direttamente sulla input `Surface` dell'encoder
  (zero copie). Risoluzione = min(display tablet, display receiver), allineata a 16.
- Codec: HEVC se entrambi supportano HW; altrimenti AVC. Solo codec hardware (`isHardwareAccelerated`).
- Parametri: `KEY_PRIORITY=0` (realtime), `KEY_BITRATE_MODE=CBR` (fallback VBR),
  `KEY_I_FRAME_INTERVAL` lungo (es. 10 s) + `PARAMETER_KEY_REQUEST_SYNC_FRAME` su richiesta,
  `KEY_REPEAT_PREVIOUS_FRAME_AFTER` per schermi statici, `KEY_MAX_B_FRAMES=0`, profilo Main/High
  senza B-frame, `KEY_LATENCY=1` dove supportato, intra-refresh se disponibile
  (`FEATURE_IntraRefresh`) per evitare picchi di keyframe.
- Bitrate adattivo: `PARAMETER_KEY_VIDEO_BITRATE` modificato da una policy pura (testabile) che
  legge occupazione coda di invio + STATS del receiver.

## ADR-005 — Pipeline decoder (receiver)
**Accettata.**
- `SurfaceView` (non `TextureView`) per avere il percorso di composizione più corto.
- `KEY_LOW_LATENCY=1` se `FEATURE_LowLatency` (API 30+); su Fire OS 7 si provano chiavi vendor
  note (es. `vendor.low-latency.enable`) solo se annunciate dal codec; `KEY_PRIORITY=0`,
  `KEY_OPERATING_RATE` alto.
- Modalità asincrona (`setCallback`) con thread dedicato; output rilasciati subito
  (`releaseOutputBuffer(index, true)`), nessuna sincronizzazione A/V, nessun jitter buffer oltre 1 frame.
- Se si accumulano più di N access unit in ingresso → si scartano fino al prossimo keyframe e si
  invia REQUEST_KEYFRAME.
- Display: `Surface.setFrameRate` / preferenza di modalità del display allineata agli fps sorgente
  per evitare judder.

## ADR-006 — Scoperta: NSD (mDNS)
**Accettata.** Receiver registra `_ririproj._tcp` con TXT record `v=1`, `name`, `codecs`.
Il sender usa `NsdManager.discoverServices`; fallback inserimento manuale IP:porta
(alcune reti/router filtrano il multicast).

## ADR-007 — Audio (parte del MVP)
**Accettata.** L'audio del tablet va riprodotto sulla TV: senza audio la proiezione non è considerata
funzionante.
- **Cattura**: `AudioPlaybackCapture` (API 29+, ok sul tablet) dalla stessa `MediaProjection` del video,
  permesso `RECORD_AUDIO`, usage `MEDIA`, `GAME`, `UNKNOWN`. Limite noto: le app che dichiarano
  `allowAudioPlaybackCapture=false` (o usano usage non catturabili, es. chiamate) non vengono catturate
  → va segnalato in UI, non è un bug.
- **Formato**: default **PCM s16le 48 kHz stereo** in pacchetti da 10 ms (~1,5 Mbps, nessuna latenza
  di codec, trascurabile in LAN accanto al video). **Opus** (encoder MediaCodec API 29+, decoder
  disponibile su Fire OS 7) come opzione per reti deboli, selezionato in negoziazione.
- **Trasporto**: **Proposta da validare nel piano dell'audio** — connessione TCP dedicata all'audio,
  associata alla sessione video tramite id in HELLO/START, per non far aspettare i pacchetti audio
  dietro un keyframe video di centinaia di KB. Alternativa: stesso socket con scheduler a priorità
  (audio prima) e frame video frammentati.
- **Riproduzione (receiver)**: `AudioTrack` con `PERFORMANCE_MODE_LOW_LATENCY`, buffer minimo,
  jitter buffer adattivo 20–60 ms; in caso di underrun si inserisce silenzio, in caso di accumulo
  si scartano campioni per tornare al target.
- **Sincronizzazione A/V**: il video resta "render immediato" (ADR-005); è l'audio ad allinearsi al
  video usando i pts comuni e `AudioTrack.getTimestamp` per stimare la latenza di uscita HDMI.
  Obiettivo: scarto A/V entro ±40 ms (soglia di percezione del lip-sync). Se l'uscita audio della
  TV è più lenta del video, si applica un ritardo video configurabile (default 0).
- **Da verificare su device**: se l'audio continua a uscire anche dagli speaker del tablet durante
  la cattura e come silenziarlo senza alterare il flusso catturato.

## ADR-008 — Strumenti
**Accettata.** Gradle Kotlin DSL + version catalog, AGP/Kotlin stabili più recenti al momento
dello scaffolding, Compose per la UI del tablet, View minimale per il receiver,
Spotless (ktlint) + Android Lint, JUnit4 + Truth + kotlinx-coroutines-test per i test JVM,
AndroidX Test per gli strumentati.
