#!/usr/bin/env bash
# Usage: ./run.sh [flowable-version]   (default 7.2.0). Recreates database spike_flowable on every run.
set -eo pipefail
cd "$(dirname "$0")"
VERSION="${1:-7.2.0}"
eval "$(cs java --jvm temurin:21 --env)"
docker exec dms-plattform-db-1 dropdb -U dms --if-exists spike_flowable
docker exec dms-plattform-db-1 createdb -U dms spike_flowable
scala-cli run --server=false src --dep "org.flowable:flowable-engine:${VERSION}" 2>&1 | tee "run-${VERSION}.log"
