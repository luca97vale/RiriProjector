---
name: sdlc-feature
description: Ciclo SDLC completo per RiriProjector, dall'idea alla PR (requisito → piano → implementazione → test → review → PR), con pause per l'approvazione dell'utente ai cancelli. Da invocare esplicitamente.
argument-hint: <descrizione della funzionalità> | #<numero issue esistente>
disable-model-invocation: true
---

# Feature end-to-end

Input: $ARGUMENTS

Esegui le fasi in ordine seguendo le skill corrispondenti (leggi il loro `SKILL.md` in
`.claude/skills/`). Tieni aggiornata la task list con una voce per fase.

1. **Requisito** — se l'input è `#<n>` usa l'issue esistente, altrimenti segui `sdlc-req`.
   ⏸ Cancello: conferma dell'utente per creare l'issue.
2. **Piano** — segui `sdlc-plan` (con revisione di `android-architect`).
   ⏸ Cancello: approvazione esplicita del piano. Non scrivere codice prima.
3. **Implementazione** — segui `sdlc-impl`.
4. **Test** — segui `sdlc-test` (agente `test-writer`).
5. **Review** — segui `sdlc-review` (agente `media-net-reviewer`).
6. **Device** — se la feature è osservabile su device, proponi `/firetv-deploy both` e chiedi
   all'utente di riportare cosa vede (o le metriche dell'overlay).
7. **PR** — segui `sdlc-pr`. ⏸ Cancello: conferma per push e creazione PR.

Se una fase fallisce in modo non banale, fermati, spiega e proponi come procedere invece di
aggirare il problema.
