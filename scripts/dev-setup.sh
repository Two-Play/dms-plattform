#!/usr/bin/env bash
# One-command local development setup with demo data (DMS-95). Development only.
#
#   scripts/dev-setup.sh              services, ZITADEL, migrations, tenant, demo users and content;
#                                     then backend and frontend dev server in the background
#   scripts/dev-setup.sh --seed-only  only tenant, demo users and content (services and schema must exist)
#   scripts/dev-setup.sh --no-start   set everything up, but leave backend and frontend stopped
#   scripts/dev-setup.sh --no-compose do not run `docker compose up` (services are already running)
#   scripts/dev-setup.sh --reset      remove the database and ZITADEL volumes and local state, then set up anew
#
# Environment: DMS_HTTP_PORT (backend port, default 8080). Every step is idempotent and never deletes data,
# except --reset after confirmation. Logs and process ids of background processes: .local/logs/.
# Stop the background processes with scripts/dev-stop.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL="$ROOT/.local"
LOGS="$LOCAL/logs"
PORT="${DMS_HTTP_PORT:-8080}"
FRONTEND_PORT=5173
STAGE="$ROOT/modules/app/target/universal/stage"
HELPER=(python3 "$ROOT/scripts/dev_setup.py")

RESET=false SEED_ONLY=false START=true COMPOSE=true
for arg in "$@"; do
  case "$arg" in
    --reset) RESET=true ;;
    --seed-only) SEED_ONLY=true ;;
    --no-start) START=false ;;
    --no-compose) COMPOSE=false ;;
    -h | --help) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $arg (see --help)" >&2; exit 2 ;;
  esac
done

step() { printf '\n==> %s\n' "$*"; }
need() { command -v "$1" > /dev/null || { echo "Missing required command: $1" >&2; exit 1; }; }
healthy() { curl -fsS --max-time 2 "http://localhost:$1/health" 2> /dev/null | grep -q '"status":"ok"'; }
# Dev servers may listen on IPv4 or only on IPv6 localhost ([::1]), so try both.
port_in_use() { (exec 3<> "/dev/tcp/127.0.0.1/$1") 2> /dev/null || (exec 3<> "/dev/tcp/::1/$1") 2> /dev/null; }

java21() {
  if command -v cs > /dev/null; then
    # The generated exports read JAVA_HOME, which may be unset (set -u).
    export JAVA_HOME="${JAVA_HOME:-}"
    eval "$(cs java --jvm temurin:21 --env)"
  fi
  java -version 2>&1 | grep -q 'version "21' || { echo "Java 21 is required (cs java --jvm temurin:21)" >&2; exit 1; }
}

wait_for() { # description seconds command...
  local what="$1" seconds="$2"
  shift 2
  for _ in $(seq 1 "$seconds"); do
    "$@" && return 0
    sleep 1
  done
  echo "Timed out waiting for $what" >&2
  return 1
}

for c in docker curl python3; do need "$c"; done
mkdir -p "$LOCAL/zitadel" "$LOGS"

if $RESET; then
  echo "This stops the services and permanently deletes the local database and ZITADEL volumes,"
  echo "$LOCAL/zitadel and $LOCAL/dev-users.env. All local documents, templates and users are lost."
  [[ -r /dev/tty ]] || { echo "No terminal for the confirmation; aborting." >&2; exit 1; }
  read -r -p "Type 'reset' to continue: " answer < /dev/tty
  [[ "$answer" == "reset" ]] || { echo "Aborted."; exit 1; }
  "$ROOT/scripts/dev-stop.sh" || true
  project="$(cd "$ROOT" && docker compose config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["name"])')"
  (cd "$ROOT" && docker compose down)
  docker volume rm -f "${project}_dms-db" "${project}_zitadel-db"
  rm -rf "$LOCAL/zitadel" "$LOCAL/dev-users.env"
  mkdir -p "$LOCAL/zitadel"
fi

if ! $SEED_ONLY; then
  if $COMPOSE; then
    step "Starting services (docker compose up -d --build --wait)"
    (cd "$ROOT" && docker compose up -d --build --wait db zitadel-db objectstore rendering opensearch mailpit)
    # zitadel has no health check; objectstore-init runs once and exits.
    (cd "$ROOT" && docker compose up -d zitadel objectstore-init)
  fi

  step "Waiting for ZITADEL"
  wait_for "ZITADEL (http://localhost:8085/debug/ready)" 180 \
    bash -c '[[ "$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8085/debug/ready)" == 200 ]]'
  wait_for "$LOCAL/zitadel/admin.pat" 60 test -s "$LOCAL/zitadel/admin.pat"

  step "ZITADEL project and OIDC application"
  "$ROOT/scripts/zitadel-setup.sh"

  step "Building the backend and migrating the database"
  java21
  need sbt
  (cd "$ROOT" && sbt -batch app/stage)
  "$STAGE/bin/migrate"
fi

step "Tenant"
"${HELPER[@]}" tenant

step "Demo users and role assignments"
"${HELPER[@]}" users

step "Intranet technical user (Reader on class policy)"
"${HELPER[@]}" intranet

# The backend: reuse one that already answers on the port, otherwise start the staged build in the background.
# A backend started here is stopped again when the script fails or runs with --no-start.
started_backend=false
stop_started_backend() {
  local status=$?
  if $started_backend && { [[ $status -ne 0 ]] || ! $START; }; then
    "$ROOT/scripts/dev-stop.sh" --backend-only
  fi
}
trap stop_started_backend EXIT
if healthy "$PORT"; then
  echo "Using the backend already running on port $PORT"
else
  if port_in_use "$PORT"; then
    echo "Port $PORT is in use by something else; set DMS_HTTP_PORT to a free port." >&2
    exit 1
  fi
  if [[ ! -x "$STAGE/bin/app" ]]; then
    java21
    need sbt
    (cd "$ROOT" && sbt -batch app/stage)
  fi
  step "Starting the backend on port $PORT (log: $LOGS/backend.log)"
  java21
  (
    set -a
    # shellcheck disable=SC1091
    . "$LOCAL/zitadel/dms.env"
    set +a
    export DMS_HTTP_PORT="$PORT"
    # Notification emails go to Mailpit from compose.yaml (DMS-130); links point at the dev server.
    export DMS_SMTP_HOST="${DMS_SMTP_HOST:-localhost}" DMS_SMTP_PORT="${DMS_SMTP_PORT:-1025}"
    export DMS_SMTP_SECURITY="${DMS_SMTP_SECURITY:-none}"
    export DMS_NOTIFICATIONS_APP_BASE_URL="${DMS_NOTIFICATIONS_APP_BASE_URL:-http://localhost:$FRONTEND_PORT}"
    cd "$ROOT"
    setsid nohup "$STAGE/bin/app" > "$LOGS/backend.log" 2>&1 < /dev/null &
    echo $! > "$LOGS/backend.pid"
  )
  started_backend=true
  wait_for "the backend on port $PORT" 120 healthy "$PORT" || { tail -n 30 "$LOGS/backend.log" >&2; exit 1; }
fi

step "Demo content"
"${HELPER[@]}" seed --api "http://localhost:$PORT"

# The search index is derived from the database (ADR 0029); rebuilding it is idempotent.
step "Search index (rebuild from the database)"
"${HELPER[@]}" search-rebuild --api "http://localhost:$PORT"

step "Frontend configuration (frontend/.env.local)"
# shellcheck disable=SC1091
. "$LOCAL/zitadel/dms.env"
[[ -f "$ROOT/frontend/.env.local" ]] || cp "$ROOT/frontend/.env.example" "$ROOT/frontend/.env.local"
"${HELPER[@]}" env-file "$ROOT/frontend/.env.local" \
  "VITE_OIDC_ISSUER=$ZITADEL_ISSUER" \
  "VITE_OIDC_CLIENT_ID=$DMS_ZITADEL_CLIENT_ID" \
  "VITE_OIDC_PROJECT_ID=$DMS_ZITADEL_PROJECT_ID" \
  "DMS_BACKEND_URL=http://localhost:$PORT"
echo "Wrote $ROOT/frontend/.env.local"

backend_cmd="set -a && . .local/zitadel/dms.env && set +a && DMS_HTTP_PORT=$PORT DMS_SMTP_HOST=localhost DMS_SMTP_PORT=1025 DMS_SMTP_SECURITY=none $STAGE/bin/app"
frontend_cmd="cd frontend && npm ci && npm run dev"

if ! $START; then
  step "Done. Start the applications with:"
  echo "  $backend_cmd      # or: sbt app/run"
  echo "  $frontend_cmd"
else
  step "Starting the frontend dev server (log: $LOGS/frontend.log)"
  if port_in_use "$FRONTEND_PORT"; then
    echo "Port $FRONTEND_PORT is already in use; not starting the dev server. It must listen on $FRONTEND_PORT,"
    echo "the only redirect URI registered in ZITADEL. Stop the other process and run: $frontend_cmd"
  else
    need npm
    [[ -d "$ROOT/frontend/node_modules" ]] || (cd "$ROOT/frontend" && npm ci)
    (
      cd "$ROOT/frontend"
      setsid nohup npm run dev -- --port "$FRONTEND_PORT" --strictPort > "$LOGS/frontend.log" 2>&1 < /dev/null &
      echo $! > "$LOGS/frontend.pid"
    )
    # The backend keeps running if the frontend does not come up; only show the frontend log.
    if ! wait_for "the frontend on port $FRONTEND_PORT" 60 port_in_use "$FRONTEND_PORT"; then
      tail -n 30 "$LOGS/frontend.log" >&2
      echo "The frontend did not start; the backend is still running. See $LOGS/frontend.log." >&2
    fi
  fi
  step "Done"
  echo "  Web UI:   http://localhost:$FRONTEND_PORT"
  echo "  API:      http://localhost:$PORT (docs: http://localhost:$PORT/docs)"
  echo "  Stop:     scripts/dev-stop.sh"
fi
echo "  Demo users: admin, designer, designer2, author, reviewer, approver, reader, records"
echo "  Passwords:  $LOCAL/dev-users.env"
echo "  Partner master: built-in demo partners (dms.partners.directory = demo)"
echo "  Search index:   OpenSearch on http://localhost:9200 (rebuild: scripts/dev_setup.py search-rebuild --api URL)"
echo "  Emails:         Mailpit on http://localhost:8025 (SMTP localhost:1025; notifications, DMS-130)"
