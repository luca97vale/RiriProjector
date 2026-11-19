#!/usr/bin/env bash
# Crea/aggiorna le label GitHub usate dal flusso SDLC. Richiede `gh` autenticato.
set -euo pipefail
labels=(
  "type:feature|1d76db|Nuova funzionalità"
  "type:bug|d73a4a|Comportamento errato"
  "type:perf|fbca04|Latenza, qualità, consumo"
  "type:chore|cfd3d7|Manutenzione, tooling, CI"
  "area:sender|5319e7|Tablet: MediaProjection, encoder"
  "area:receiver|0e8a16|Fire TV: decoder, rendering"
  "area:protocol|b60205|Formato wire e versioning"
  "area:media|c5def5|MediaCodec, formati, codec"
  "area:audio|f9d0c4|Cattura, trasporto, riproduzione audio, lip-sync"
  "area:net|bfdadc|TCP, NSD, backpressure"
  "area:ci|ededed|GitHub Actions, build"
  "priority:high|b60205|"
  "priority:medium|fbca04|"
  "priority:low|0e8a16|"
)
for l in "${labels[@]}"; do
  IFS='|' read -r name color desc <<< "$l"
  gh label create "$name" --color "$color" --description "$desc" --force
done
