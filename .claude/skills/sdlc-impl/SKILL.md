---
name: sdlc-impl
description: Fase 3 SDLC di RiriProjector. Implementa un piano approvato in docs/plans/ con commit piccoli, mantenendo build e formattazione verdi.
argument-hint: <numero issue>
---

# Piano → Codice

Issue: #$ARGUMENTS

1. Trova il piano: `docs/plans/$ARGUMENTS-*.md`. Se manca o non è `Stato: APPROVATO`, fermati e
   proponi `/sdlc-plan $ARGUMENTS`.
2. Verifica di essere sul branch dell'issue (`git branch --show-current`) e che il working tree sia pulito.
3. Per ogni passo della checklist "Passi di implementazione":
   a. implementa il minimo necessario rispettando le convenzioni di `CLAUDE.md`
      (hot path senza allocazioni, risorse rilasciate in `finally`, thread dedicati);
   b. aggiungi i test JVM ovvi per la logica pura che hai appena scritto (i test approfonditi
      arrivano in `/sdlc-test`);
   c. `./gradlew spotlessApply` e poi il comando di verifica più stretto possibile
      (`./gradlew :<modulo>:testDebugUnitTest` o `:<modulo>:assembleDebug`);
   d. spunta il passo nel file del piano e fai commit Conventional (`feat(sender): ...`,
      `perf(receiver): ...`) con riferimento `Refs #$ARGUMENTS`.
4. Se un passo richiede di uscire dal piano (nuovo modulo, cambio di protocollo non previsto,
   nuova libreria) **fermati e chiedi all'utente**; poi aggiorna il piano.
5. Alla fine: `./gradlew assembleDebug testDebugUnitTest spotlessCheck` devono essere verdi.
6. Riassumi cosa è stato fatto e suggerisci `/sdlc-test` (e `/firetv-deploy both` se il cambiamento
   è visibile su device).
