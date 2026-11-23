#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
pin=821a0ed6d8b30efc7ea83c20e5874cdff969aa75
upstreamDir=upstream/pycozmo
if [ ! -d "$upstreamDir/.git" ]; then
  mkdir -p "$upstreamDir"
  git -C "$upstreamDir" init
  git -C "$upstreamDir" remote add origin https://github.com/Kurtisone/pycozmo.git
  git -C "$upstreamDir" fetch --depth 1 origin "$pin"
  git -C "$upstreamDir" checkout --detach FETCH_HEAD
fi
actual=$(git -C "$upstreamDir" rev-parse HEAD)
if [ "$actual" != "$pin" ]; then
  echo 'Revisione upstream diversa: nessun checkout esistente viene sovrascritto.' >&2
  exit 1
fi
echo "PyCozmo verificato: $actual"
