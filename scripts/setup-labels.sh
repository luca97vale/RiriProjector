#!/usr/bin/env bash
# Creates/updates the GitHub labels used by the SDLC flow. Requires an authenticated `gh`.
set -euo pipefail
labels=(
  "type:feature|1d76db|New feature"
  "type:bug|d73a4a|Wrong behavior"
  "type:perf|fbca04|Latency, quality, power"
  "type:chore|cfd3d7|Maintenance, tooling, CI"
  "area:sender|5319e7|Tablet: MediaProjection, encoder"
  "area:receiver|0e8a16|Fire TV: decoder, rendering"
  "area:protocol|b60205|Wire format and versioning"
  "area:media|c5def5|MediaCodec, formats, codecs"
  "area:audio|f9d0c4|Audio capture, transport, playback, lip-sync"
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
