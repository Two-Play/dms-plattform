#!/usr/bin/env bash
# Stops the backend and the frontend dev server that scripts/dev-setup.sh started in the background
# (process ids in .local/logs/*.pid). Leaves the docker compose services running; stop them with
# `docker compose stop`.
#
#   scripts/dev-stop.sh                 backend and frontend
#   scripts/dev-stop.sh --backend-only  backend only
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$ROOT/.local/logs"
names=(backend frontend)
[[ "${1:-}" == "--backend-only" ]] && names=(backend)

for name in "${names[@]}"; do
  pid_file="$LOGS/$name.pid"
  [[ -f "$pid_file" ]] || continue
  pid="$(cat "$pid_file")"
  if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2> /dev/null; then
    # dev-setup.sh starts each process with setsid, so its id is also the id of its process group.
    kill -TERM -- "-$pid" 2> /dev/null || kill -TERM "$pid"
    for _ in $(seq 1 20); do
      kill -0 "$pid" 2> /dev/null || break
      sleep 0.5
    done
    kill -0 "$pid" 2> /dev/null && kill -KILL -- "-$pid" 2> /dev/null
    echo "Stopped $name (pid $pid)"
  fi
  rm -f "$pid_file"
done
