---
name: sdlc-test
description: Fase 4 SDLC di RiriProjector. Delega all'agente test-writer la scrittura dei test per il diff corrente, esegue la suite e riporta copertura e bug trovati.
argument-hint: "[modulo o area da privilegiare]"
---

# Test

Focus opzionale: $ARGUMENTS

1. Identifica issue e piano dal nome del branch (`feat/<n>-...` → `docs/plans/<n>-*.md`).
2. Invoca l'agente **test-writer** con: numero issue, percorso del piano, elenco moduli toccati
   (`git diff --stat origin/main...HEAD`), eventuale focus "$ARGUMENTS".
3. Al termine verifica tu stesso: `./gradlew testDebugUnitTest spotlessCheck`.
   Se c'è un device collegato e il diff tocca `core:media`/`core:net`, proponi
   `./gradlew connectedDebugAndroidTest`.
4. Se l'agente ha segnalato bug nel codice di produzione, correggili (commit `fix: ...`)
   e rilancia i test.
5. Commit dei test: `test(<area>): ...` con `Refs #<n>`.
6. Riporta: test aggiunti, esito, bug corretti, copertura mancante. Suggerisci `/sdlc-review`.
