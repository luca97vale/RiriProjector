---
name: sdlc-req
description: Fase 1 SDLC di RiriProjector. Trasforma un'idea o un bug in un'issue GitHub con criteri di accettazione misurabili. Usare quando l'utente descrive una nuova funzionalità, un problema o un miglioramento di prestazioni.
argument-hint: <descrizione della funzionalità o del bug>
allowed-tools: Read, Grep, Glob, Bash(gh issue list:*), Bash(gh issue view:*), Bash(gh label list:*)
---

# Requisito → Issue

Input: $ARGUMENTS

1. Leggi `CLAUDE.md` e `docs/PLAN.md` per capire in quale milestone cade la richiesta.
   Cerca duplicati: `gh issue list --state all --search "<parole chiave>"`.
2. Se mancano informazioni essenziali (dispositivo, comportamento atteso, metrica), fai al massimo
   3 domande mirate all'utente. Altrimenti procedi con assunzioni esplicite.
3. Prepara la bozza dell'issue usando la struttura di `.github/ISSUE_TEMPLATE/feature.yml`
   (o `bug.yml`):
   - **Contesto / problema** (perché serve)
   - **Comportamento atteso**
   - **Criteri di accettazione**: elenco `- [ ]` verificabile; per lavori di prestazioni includi
     numeri (es. "latenza glass-to-glass p95 ≤ 80 ms a 1080p60 su Wi-Fi 5 GHz", "nessun frame
     scartato in 5 minuti di scorrimento").
   - **Fuori scope**
   - **Dispositivi/OS coinvolti** (Galaxy Tab modello/One UI, Fire TV modello/Fire OS)
   - **Area e milestone** → label `type:*`, `area:*`, `priority:*`
4. Mostra la bozza all'utente e **chiedi conferma** prima di crearla.
5. Dopo il sì: `gh issue create --title "<tipo>: <titolo>" --body-file <file temporaneo> --label ...`
   (scrivi il corpo in un file nella scratchpad, non inline).
6. Riporta numero e link dell'issue e suggerisci il passo successivo: `/sdlc-plan <numero>`.
