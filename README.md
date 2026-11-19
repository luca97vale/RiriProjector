# RiriProjector

Screen mirroring a bassa latenza da Galaxy Tab a Fire TV Stick / proiettori Android in LAN,
come alternativa a Miracast, **video e audio**. Un'unica app: **sender** sul tablet (MediaProjection →
HEVC/H.264 hardware + cattura audio → TCP) e **receiver** sulla TV (scoperta NSD, decoder hardware
in low-latency, audio sincronizzato).

- Architettura e decisioni: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Roadmap: [docs/PLAN.md](docs/PLAN.md)
- Processo di sviluppo (SDLC con Claude Code): [docs/SDLC.md](docs/SDLC.md)
- Istruzioni per Claude Code: [CLAUDE.md](CLAUDE.md)

## Requisiti di sviluppo
- JDK 25 LTS, Android SDK (platform-tools per `adb`)
- Node.js ≥ 18 (hook di Claude Code), `gh` autenticato
- Fire TV con *Debug ADB* attivo; tablet con debug USB/wireless

## Avvio rapido
```bash
export FIRETV_HOST=192.168.1.50   # IP della Fire TV
scripts/deploy.sh both --logs
```
