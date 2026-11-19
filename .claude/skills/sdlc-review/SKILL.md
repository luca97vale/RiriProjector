---
name: sdlc-review
description: Fase 5 SDLC di RiriProjector. Esegue la review del branch con l'agente media-net-reviewer (e android-architect se cambia l'architettura), applica le correzioni e ripete finché non restano blocker.
---

# Review

1. Verifica che build e test siano verdi: `./gradlew assembleDebug testDebugUnitTest spotlessCheck lint`.
2. Invoca l'agente **media-net-reviewer** sul diff `origin/main...HEAD`.
   Se il diff cambia moduli, API pubbliche o protocollo, invoca in parallelo anche
   **android-architect** chiedendogli di confrontare l'implementazione con il piano.
3. Per ogni finding:
   - `blocker`/`major` → correggi, con commit `fix(<area>): address review — <sintesi>`;
   - `minor` → correggi se costa poco, altrimenti annota nella PR come follow-up;
   - se ritieni un finding errato, spiega perché all'utente invece di ignorarlo.
4. Dopo le correzioni rilancia il reviewer **una volta** solo sui commit nuovi.
5. Mostra all'utente una tabella finale: finding, decisione, commit. Suggerisci `/sdlc-pr`.
