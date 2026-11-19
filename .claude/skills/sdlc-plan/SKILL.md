---
name: sdlc-plan
description: Fase 2 SDLC di RiriProjector. Dato il numero di un'issue, produce il piano di implementazione in docs/plans/, lo fa revisionare all'agente android-architect e lo sottopone all'utente per approvazione.
argument-hint: <numero issue>
---

# Issue → Piano

Issue: #$ARGUMENTS

1. `gh issue view $ARGUMENTS --comments` e leggi `CLAUDE.md`, `docs/ARCHITECTURE.md`, `docs/PLAN.md`.
2. Esplora il codice coinvolto. Se l'esplorazione è ampia, usa un agente `Explore`.
3. Crea il branch se non esiste: `git switch -c feat/$ARGUMENTS-<slug>` da `main` aggiornato
   (`fix/` per i bug, `chore/` per manutenzione).
4. Scrivi `docs/plans/$ARGUMENTS-<slug>.md` con questa struttura:
   ```
   # Piano #<n> — <titolo>
   Issue: #<n> · Stato: BOZZA
   ## Obiettivo e criteri di accettazione (copiati dall'issue)
   ## Approccio (2-4 paragrafi; alternative scartate e perché)
   ## Modifiche per modulo (file nuovi/modificati, classi e API pubbliche con firme)
   ## Protocollo (se cambia: messaggi, bump PROTOCOL_VERSION, compatibilità)
   ## Threading e ciclo di vita delle risorse
   ## Rischi e mitigazioni (latenza, compatibilità device, regressioni)
   ## Strategia di test (JVM / strumentati / manuale su device con metriche)
   ## Passi di implementazione (checklist ordinata, ognuno = 1 commit)
   ```
5. Invoca l'agente **android-architect** passandogli il percorso del piano e l'issue.
   Integra le modifiche richieste; se il verdetto è "DA RIVEDERE" ripeti una volta.
6. Mostra all'utente sintesi del piano + verdetto dell'architetto e **chiedi approvazione**.
   Dopo l'approvazione imposta `Stato: APPROVATO` nel file e fai commit
   `docs: add plan for #<n>`.
7. Se emergono decisioni architetturali nuove, aggiungi un ADR "Proposta" in `docs/ARCHITECTURE.md`.
8. Suggerisci il passo successivo: `/sdlc-impl $ARGUMENTS`.
