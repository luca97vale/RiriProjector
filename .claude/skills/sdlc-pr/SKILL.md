---
name: sdlc-pr
description: Fase 6 SDLC di RiriProjector. Pubblica il branch e apre la Pull Request con il template compilato, collegata all'issue. Da invocare esplicitamente; chiede conferma prima di push e creazione PR.
disable-model-invocation: true
---

# Pull Request

1. Controlli preliminari (fermati se uno fallisce):
   - branch ≠ `main`; working tree pulito;
   - `./gradlew assembleDebug testDebugUnitTest spotlessCheck lint` verde;
   - piano in `docs/plans/<n>-*.md` con tutti i passi spuntati;
   - `/sdlc-review` eseguita senza blocker aperti (chiedi all'utente se non risulta in conversazione).
2. Compila il corpo seguendo `.github/PULL_REQUEST_TEMPLATE.md`:
   sintesi, `Closes #<n>`, modifiche per modulo, impatto sul protocollo, test eseguiti
   (comandi + esito), misure prima/dopo se tocca il hot path, checklist "Test su device"
   (lascia non spuntate le voci non verificate e dillo), follow-up.
   Termina il corpo con la riga di attribuzione richiesta dalla sessione.
3. Mostra titolo (Conventional, es. `feat(receiver): low-latency HEVC decoder`) e corpo all'utente
   e **chiedi conferma**.
4. Dopo il sì: `git push -u origin HEAD` e `gh pr create --base main --title ... --body-file <file>`
   con le label dell'issue.
5. Riporta il link della PR. Lo stato della CI si segue dalla PR, senza polling.
