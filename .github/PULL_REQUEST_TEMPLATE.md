## Sintesi
<!-- Cosa cambia e perché, in 2-4 righe. -->

Closes #

Piano: `docs/plans/<n>-<slug>.md`

## Modifiche per modulo
- `core:protocol`:
- `core:net`:
- `core:media`:
- `feature:sender`:
- `feature:receiver`:
- `app` / build / CI:

## Protocollo
- [ ] Nessuna modifica al formato wire
- [ ] Modificato → `PROTOCOL_VERSION` incrementata, ADR aggiornato in `docs/ARCHITECTURE.md`, test di round-trip aggiunti

## Test
- [ ] `./gradlew testDebugUnitTest` verde
- [ ] `./gradlew spotlessCheck lintDebug` verde
- [ ] Test strumentati (`connectedDebugAndroidTest`) su: <!-- device o "non applicabile" -->

### Test su device
| Voce | Esito |
|---|---|
| Galaxy Tab (modello / One UI) | |
| Fire TV (modello / Fire OS) | |
| Scoperta NSD automatica | |
| Avvio/stop proiezione, revoca consenso | |
| Riconnessione dopo perdita Wi-Fi / riavvio receiver | |
| Rotazione tablet | |
| Audio riprodotto sulla TV (musica/video, 10 min senza buchi) | |
| Lip-sync (clap/flash di test) | |

### Prestazioni (obbligatorio se tocca il hot path media/rete)
| Metrica | Prima | Dopo |
|---|---|---|
| Risoluzione / fps | | |
| Bitrate medio | | |
| Latenza (RTT + decode, o glass-to-glass) | | |
| Frame scartati / min | | |
| Scarto A/V (ms) / underrun audio per min | | |

## Review
- [ ] `/sdlc-review` eseguita, nessun blocker aperto
- Follow-up non inclusi:
