#!/usr/bin/env bash
# Spike DMS-23: build the image, run all determinism variants and the veraPDF validation.
set -euo pipefail
cd "$(dirname "$0")"

docker build -q -t dms-render-spike:dms-23 .
rm -rf out results
./determinism.sh default 10
./determinism.sh naive 10
# Second container with a different hash seed, time zone, locale, CPU and memory limit.
SUFFIX=-varied-env B_ARGS="-e PYTHONHASHSEED=random -e TZ=Pacific/Auckland -e LANG=de_DE.UTF-8 --cpus 1 --memory 512m" \
  ./determinism.sh deterministic 10
./determinism.sh deterministic 10
./validate.sh
