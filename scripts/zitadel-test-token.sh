#!/usr/bin/env bash
# Prints a JWT access token for local API tests, issued by the local ZITADEL to the machine user
# "dms-api-tester" via client credentials. Creates the user and its secret on first run (idempotent).
# Requires: scripts/zitadel-setup.sh has run (.local/zitadel/dms.env exists), curl, python3.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/.local/zitadel/dms.env"
PAT="$(tr -d '\n' < "$ROOT/.local/zitadel/admin.pat")"
SECRET_FILE="$ROOT/.local/zitadel/api-tester.secret"
USER_NAME="dms-api-tester"

api() { # method path [json-body]
  curl -sS --fail-with-body -X "$1" "$ZITADEL_ISSUER$2" \
    -H "Authorization: Bearer $PAT" -H "Content-Type: application/json" ${3:+-d "$3"}
}
json() { python3 -c "import sys,json; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }

if [[ ! -s "$SECRET_FILE" ]]; then
  USER_ID="$(api POST /management/v1/users/_search \
    "{\"queries\":[{\"userNameQuery\":{\"userName\":\"$USER_NAME\",\"method\":\"TEXT_QUERY_METHOD_EQUALS\"}}]}" \
    | json "(d.get('result') or [{}])[0].get('id','')")"
  if [[ -z "$USER_ID" ]]; then
    USER_ID="$(api POST /management/v1/users/machine \
      "{\"userName\":\"$USER_NAME\",\"name\":\"DMS API tester\",\"accessTokenType\":\"ACCESS_TOKEN_TYPE_JWT\"}" \
      | json "d['userId']")"
  fi
  api PUT "/management/v1/users/$USER_ID/secret" "{}" | json "d['clientId'] + ':' + d['clientSecret']" > "$SECRET_FILE"
  chmod 600 "$SECRET_FILE"
fi

SCOPE="openid urn:zitadel:iam:org:project:id:${DMS_ZITADEL_PROJECT_ID}:aud urn:zitadel:iam:user:resourceowner urn:zitadel:iam:org:project:roles"
curl -sS --fail-with-body -X POST "$ZITADEL_ISSUER/oauth/v2/token" \
  -u "$(cat "$SECRET_FILE")" \
  --data-urlencode grant_type=client_credentials \
  --data-urlencode "scope=$SCOPE" \
  | json "d['access_token']"
