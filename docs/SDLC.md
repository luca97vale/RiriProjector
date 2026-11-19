# Processo di sviluppo (SDLC con Claude Code)

## Fasi e cancelli di qualità

```
 requisito ──▶ piano ──▶ implementazione ──▶ test ──▶ review ──▶ PR ──▶ CI verde ──▶ merge
  /sdlc-req   /sdlc-plan   /sdlc-impl      /sdlc-test /sdlc-review /sdlc-pr
     │            │                                      │
  issue GH   approvazione                       nessun finding "blocker"
             dell'utente
```

| Fase | Ingresso | Uscita (Definition of Done) | Chi |
|---|---|---|---|
| Requisito | idea in linguaggio naturale | Issue con: contesto, criteri di accettazione verificabili, metriche (es. latenza p95, fps), dispositivi coinvolti, label | Claude + utente |
| Piano | issue | `docs/plans/<n>-<slug>.md` con moduli toccati, API, rischi, strategia di test; revisionato da `android-architect`; **approvato dall'utente** | Claude + agente |
| Implementazione | piano approvato | codice sul branch `feat/<n>-<slug>`, commit piccoli, `spotlessCheck` + build verdi | Claude |
| Test | codice | unit test JVM per la logica pura, test strumentati per codec/rete dove serve; `testDebugUnitTest` verde | agente `test-writer` |
| Review | diff completo | report di `media-net-reviewer` senza blocker aperti; suggerimenti applicati o motivati | agente |
| PR | branch pulito | PR con template compilato, `Closes #n`, note di test su device | Claude (con conferma) |
| Merge | CI verde + review umana | squash merge su `main` | utente |

## Regole

- Un'issue = un branch = una PR. Se durante l'implementazione emerge lavoro fuori scope,
  si apre una nuova issue invece di allargare la PR.
- Il piano è un contratto: se cambia in modo significativo, si aggiorna il file del piano nella stessa PR.
- Le modifiche al protocollo di rete richiedono sempre: bump di `PROTOCOL_VERSION`,
  aggiornamento di `docs/ARCHITECTURE.md`, test di round-trip e di compatibilità sull'header.
- Le modifiche al hot path media/rete richiedono una misura prima/dopo (overlay statistiche o
  logcat `Riri/Stats`) riportata nella PR.
- Ogni PR che tocca codec, socket o MediaProjection deve essere provata su device reali
  (`/firetv-deploy both`) e la checklist "Test su device" del template va compilata.

## Etichette GitHub suggerite

`type:feature`, `type:bug`, `type:perf`, `type:chore`, `area:sender`, `area:receiver`,
`area:protocol`, `area:media`, `area:audio`, `area:net`, `area:ci`, `priority:high|medium|low`.
Le crea `scripts/setup-labels.sh` (richiede `gh` autenticato).

## Agenti

| Agente | Quando | Modello |
|---|---|---|
| `android-architect` | piano, scelte di modulo/API, decisioni ADR | opus |
| `media-net-reviewer` | review di ogni diff su `core/*`, `feature/*` | opus |
| `test-writer` | scrittura test dopo l'implementazione | sonnet |

## Hook attivi (`.claude/settings.json`)

- **PreToolUse Bash** → `guard-bash.mjs`: blocca push forzati, push su `main`, `--no-verify`,
  `rm -rf` fuori dal repo, `adb` verso dispositivi con `shell rm`/`reboot` non richiesti.
- **PreToolUse Edit|Write** → `protect-files.mjs`: blocca keystore, `*.jks`, `keystore.properties`,
  `local.properties`, `.env`, `gradle-wrapper.jar`.
- **PostToolUse Edit|Write** → `format-kotlin.mjs`: `spotlessApply` sul solo file `.kt/.kts` modificato
  (silenzioso se Gradle non è ancora presente).
- **SessionStart** → `session-context.mjs`: inietta branch corrente, issue collegata e stato del piano.
