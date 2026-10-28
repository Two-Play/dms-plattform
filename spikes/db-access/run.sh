#!/usr/bin/env bash
# Recreates the spike database and runs all three test suites (no Bloop server: it fails with Scala 3.9.0).
set -eo pipefail
cd "$(dirname "$0")"
eval "$(cs java --jvm temurin:21 --env)"
./setup.sh
for c in jdbc magnum quill; do
  echo "=== $c"
  scala-cli test --server=false "$c"
done
