#!/usr/bin/env bash
# Creates the ZITADEL project and the OIDC application for local development.
# Idempotent: existing project and application are reused.
# Requires: docker compose services running, curl, python3.
# Output: .local/zitadel/dms.env with ZITADEL_ISSUER, DMS_ZITADEL_PROJECT_ID, DMS_ZITADEL_CLIENT_ID.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ISSUER="${ZITADEL_ISSUER:-http://localhost:8085}"
PAT_FILE="$ROOT/.local/zitadel/admin.pat"
OUT_FILE="$ROOT/.local/zitadel/dms.env"
PROJECT_NAME="dms-platform"
APP_NAME="dms-web"
REDIRECT_URI="${DMS_WEB_REDIRECT_URI:-http://localhost:5173/callback}"
LOGOUT_URI="${DMS_WEB_LOGOUT_URI:-http://localhost:5173/}"

[[ -s "$PAT_FILE" ]] || { echo "Missing $PAT_FILE. Start ZITADEL with 'docker compose up -d' on an empty zitadel-db volume." >&2; exit 1; }
PAT="$(tr -d '\n' < "$PAT_FILE")"

api() { # method path [json-body]
  curl -sS --fail-with-body -X "$1" "$ISSUER$2" \
    -H "Authorization: Bearer $PAT" -H "Content-Type: application/json" ${3:+-d "$3"}
}
json() { python3 -c "import sys,json; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }

for _ in $(seq 1 60); do
  [[ "$(curl -s -o /dev/null -w '%{http_code}' "$ISSUER/debug/ready")" == 200 ]] && break
  sleep 2
done

PROJECT_ID="$(api POST /management/v1/projects/_search \
  "{\"queries\":[{\"nameQuery\":{\"name\":\"$PROJECT_NAME\",\"method\":\"TEXT_QUERY_METHOD_EQUALS\"}}]}" \
  | json "(d.get('result') or [{}])[0].get('id','')")"
if [[ -z "$PROJECT_ID" ]]; then
  PROJECT_ID="$(api POST /management/v1/projects \
    "{\"name\":\"$PROJECT_NAME\",\"projectRoleAssertion\":true}" | json "d['id']")"
  echo "Created project $PROJECT_NAME ($PROJECT_ID)"
else
  echo "Reusing project $PROJECT_NAME ($PROJECT_ID)"
fi

CLIENT_ID="$(api POST "/management/v1/projects/$PROJECT_ID/apps/_search" \
  "{\"queries\":[{\"nameQuery\":{\"name\":\"$APP_NAME\",\"method\":\"TEXT_QUERY_METHOD_EQUALS\"}}]}" \
  | json "(d.get('result') or [{}])[0].get('oidcConfig',{}).get('clientId','')")"
if [[ -z "$CLIENT_ID" ]]; then
  CLIENT_ID="$(api POST "/management/v1/projects/$PROJECT_ID/apps/oidc" "{
    \"name\": \"$APP_NAME\",
    \"redirectUris\": [\"$REDIRECT_URI\"],
    \"postLogoutRedirectUris\": [\"$LOGOUT_URI\"],
    \"responseTypes\": [\"OIDC_RESPONSE_TYPE_CODE\"],
    \"grantTypes\": [\"OIDC_GRANT_TYPE_AUTHORIZATION_CODE\", \"OIDC_GRANT_TYPE_REFRESH_TOKEN\"],
    \"appType\": \"OIDC_APP_TYPE_USER_AGENT\",
    \"authMethodType\": \"OIDC_AUTH_METHOD_TYPE_NONE\",
    \"accessTokenType\": \"OIDC_TOKEN_TYPE_JWT\",
    \"accessTokenRoleAssertion\": true,
    \"idTokenRoleAssertion\": true,
    \"devMode\": true
  }" | json "d['clientId']")"
  echo "Created OIDC application $APP_NAME ($CLIENT_ID)"
else
  echo "Reusing OIDC application $APP_NAME ($CLIENT_ID)"
fi

cat > "$OUT_FILE" <<EOF
ZITADEL_ISSUER=$ISSUER
DMS_ZITADEL_PROJECT_ID=$PROJECT_ID
DMS_ZITADEL_CLIENT_ID=$CLIENT_ID
EOF
echo "Wrote $OUT_FILE"
