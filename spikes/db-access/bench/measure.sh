#!/usr/bin/env bash
# Clean compile (no Bloop server, dependencies already in the coursier cache), 3 runs each.
set -eo pipefail
export LC_ALL=C
cd "$(dirname "$0")"
eval "$(cs java --jvm temurin:21 --env)"
python3 generate.py
for c in jdbc magnum quill; do
  for run in 1 2 3; do
    rm -rf "$c/.scala-build"
    s=$(date +%s.%N)
    scala-cli compile --server=false "$c" >/dev/null 2>&1
    e=$(date +%s.%N)
    printf '%-7s run %d: %.1f s\n' "$c" "$run" "$(echo "$e - $s" | bc)"
  done
done
