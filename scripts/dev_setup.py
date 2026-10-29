#!/usr/bin/env python3
"""Helper of scripts/dev-setup.sh: tenant, demo users, tokens and demo content for local development.

Subcommands:
  tenant          ensure the tenant of the local ZITADEL organization (SQL as schema owner, idempotent)
  users           ensure the demo users in ZITADEL and their role assignments (idempotent)
  token USER      print an access token for a demo user (e.g. `author`)
  seed --api URL  create demo field definitions, classes, workflows, number ranges, templates, an access group
                  and a draft through the API; publish the demo central instructions through the approval
                  workflow (approver signs in afresh for the step-up check) and add a Word template with a draft
  check [--dump DIR]            check the demo content offline (no services needed); --dump writes the
                                central instruction contents, values and the Word template to DIR
  approve-tasks USER --api URL  approve all open workflow tasks of a demo user (until the UI can vote, DMS-91)
  search-rebuild --api URL      rebuild the tenant's search index from the database (DMS-51, idempotent)
  intranet        ensure the intranet's technical user: a ZITADEL machine user with the platform role Reader
                  scoped to the class `policy` (DMS-58, idempotent); its client credentials are kept in
                  .local/zitadel/intranet.secret (mode 600) and never printed
  env-file PATH KEY=VALUE...   set keys in a dotenv file, keeping all other lines

Development only. Uses the standard library only. Passwords are kept in .local/dev-users.env (mode 600) and are
never printed.
"""

from __future__ import annotations

import base64
import datetime
import hashlib
import io
import json
import re
import secrets
import string
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ElementTree
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOCAL = ROOT / ".local"
PAT_FILE = LOCAL / "zitadel" / "admin.pat"
DMS_ENV = LOCAL / "zitadel" / "dms.env"
USERS_ENV = LOCAL / "dev-users.env"

TENANT_NAME = "Local ZITADEL org"
TENANT_TIME_ZONE = "Europe/Berlin"
GRANTED_BY = "dev-setup"
EMAIL_DOMAIN = "dms.example.com"
ZITADEL_ADMIN = "zitadel-admin@zitadel.localhost"

# Demo users: username -> (given name, family name, roles in dms.role_assignment).
USERS: dict[str, tuple[str, str, list[str]]] = {
    "admin": ("Ada", "Admin", ["TenantAdmin"]),
    "designer": ("Dora", "Designer", ["TemplateDesigner"]),
    "designer2": ("Dirk", "Designer", ["TemplateDesigner"]),
    "author": ("Anna", "Author", ["Author", "Owner"]),
    "reviewer": ("Rita", "Reviewer", ["Reviewer"]),
    "approver": ("Paul", "Approver", ["Approver"]),
    "reader": ("Rudi", "Reader", ["Reader"]),
    "records": ("Rosa", "Records", ["RecordsManager", "Auditor"]),
    "compliance": ("Carla", "Compliance", ["ComplianceOfficer"]),
}

# Demo access groups for confidential documents (DMS-101): key -> (name, member usernames).
GROUPS: dict[str, tuple[str, list[str]]] = {
    "management": ("Management", ["author", "reviewer", "approver"]),
}


def fail(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    sys.exit(1)


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if path.exists():
        for line in path.read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    return values


def set_env_keys(path: Path, updates: dict[str, str], mode: int | None = None) -> None:
    """Sets keys in a dotenv file; other lines (comments, other keys) stay as they are."""
    lines = path.read_text().splitlines() if path.exists() else []
    seen: set[str] = set()
    out = []
    for line in lines:
        key = line.split("=", 1)[0].strip() if "=" in line and not line.lstrip().startswith("#") else None
        if key in updates:
            out.append(f"{key}={updates[key]}")
            seen.add(key)
        else:
            out.append(line)
    out += [f"{k}={v}" for k, v in updates.items() if k not in seen]
    path.parent.mkdir(parents=True, exist_ok=True)
    if mode is not None and not path.exists():
        path.touch(mode=mode)
    path.write_text("\n".join(out) + "\n")
    if mode is not None:
        path.chmod(mode)


class HttpError(Exception):
    def __init__(self, status: int, body: str, method: str, url: str):
        super().__init__(f"{method} {url} -> {status}: {body[:500]}")
        self.status = status
        self.body = body


def http(method: str, url: str, body=None, token: str | None = None, headers=None, form=None,
         raw: tuple[bytes, str] | None = None):
    """JSON request (`body`), form post (`form`) or a raw body with its content type (`raw`)."""
    data = None
    hdrs = dict(headers or {})
    if token:
        hdrs["Authorization"] = f"Bearer {token}"
    if raw is not None:
        data, hdrs["Content-Type"] = raw
    elif form is not None:
        data = urllib.parse.urlencode(form).encode()
        hdrs["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        hdrs["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, method=method, headers=hdrs)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            text = response.read().decode()
            return json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        raise HttpError(e.code, e.read().decode(errors="replace"), method, url) from None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ARG002
        return None


# ---------------------------------------------------------------------------------------------------------------
# ZITADEL


class Zitadel:
    def __init__(self) -> None:
        if not PAT_FILE.exists() or not DMS_ENV.exists():
            fail(f"missing {PAT_FILE} or {DMS_ENV}; run scripts/zitadel-setup.sh first")
        self.pat = PAT_FILE.read_text().strip()
        env = read_env(DMS_ENV)
        self.issuer = env["ZITADEL_ISSUER"]
        self.project_id = env["DMS_ZITADEL_PROJECT_ID"]
        self.client_id = env["DMS_ZITADEL_CLIENT_ID"]
        me = self.api("GET", "/auth/v1/users/me")["user"]
        self.setup_user_id = me["id"]
        self.org_id = me["details"]["resourceOwner"]

    def api(self, method: str, path: str, body=None):
        return http(method, self.issuer + path, body, token=self.pat)

    def user_id(self, username: str) -> str | None:
        query = {"queries": [{"userNameQuery": {"userName": username, "method": "TEXT_QUERY_METHOD_EQUALS"}}]}
        result = self.api("POST", "/management/v1/users/_search", query).get("result") or []
        return result[0]["id"] if result else None

    def create_human(self, username: str, given: str, family: str, password: str) -> str:
        body = {
            "username": username,
            "organization": {"orgId": self.org_id},
            "profile": {"givenName": given, "familyName": family, "displayName": f"{given} {family}"},
            "email": {"email": f"{username}@{EMAIL_DOMAIN}", "isVerified": True},
            "password": {"password": password, "changeRequired": False},
        }
        return self.api("POST", "/v2/users/human", body)["userId"]

    def set_password(self, user_id: str, password: str) -> None:
        self.api("POST", f"/v2/users/{user_id}/password",
                 {"newPassword": {"password": password, "changeRequired": False}})

    def token(self, username: str, password: str) -> str:
        """An access token of a fresh sign-in, see `tokens`."""
        return self.tokens(username, password)["access_token"]

    def tokens(self, username: str, password: str) -> dict:
        """Signs a user in without a browser: a session checked with the password finalizes an OIDC auth request
        (authorization code flow with PKCE) that the setup machine user created as login client. Returns the token
        response with `access_token` and `id_token`; the ID token carries the sign-in time (`auth_time`)."""
        session = self.api("POST", "/v2/sessions", {
            "checks": {"user": {"loginName": username}, "password": {"password": password}}})
        try:
            verifier = secrets.token_urlsafe(48)
            challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
            redirect_uri = "http://localhost:5173/callback"
            scope = " ".join([
                "openid", "profile", "email",
                f"urn:zitadel:iam:org:project:id:{self.project_id}:aud",
                "urn:zitadel:iam:user:resourceowner",
                "urn:zitadel:iam:org:project:roles",
            ])
            query = urllib.parse.urlencode({
                "client_id": self.client_id, "redirect_uri": redirect_uri, "response_type": "code",
                "scope": scope, "code_challenge": challenge, "code_challenge_method": "S256",
            })
            opener = urllib.request.build_opener(NoRedirect)
            request = urllib.request.Request(f"{self.issuer}/oauth/v2/authorize?{query}",
                                             headers={"x-zitadel-login-client": self.setup_user_id})
            try:
                opener.open(request, timeout=30)
                fail("ZITADEL did not redirect the authorization request to the login client")
            except urllib.error.HTTPError as e:
                location = e.headers.get("Location", "")
            auth_request = urllib.parse.parse_qs(urllib.parse.urlparse(location).query).get("authRequest", [""])[0]
            if not auth_request:
                fail(f"no auth request in redirect '{location}'")
            callback = self.api("POST", f"/v2/oidc/auth_requests/{auth_request}", {
                "session": {"sessionId": session["sessionId"], "sessionToken": session["sessionToken"]}})
            code = urllib.parse.parse_qs(urllib.parse.urlparse(callback["callbackUrl"]).query)["code"][0]
            tokens = http("POST", f"{self.issuer}/oauth/v2/token", form={
                "grant_type": "authorization_code", "code": code, "client_id": self.client_id,
                "redirect_uri": redirect_uri, "code_verifier": verifier,
            })
            return tokens
        finally:
            try:
                self.api("DELETE", f"/v2/sessions/{session['sessionId']}", {})
            except HttpError:
                pass


def generate_password() -> str:
    alphabet = string.ascii_letters + string.digits
    core = "".join(secrets.choice(alphabet) for _ in range(16))
    # Satisfies ZITADEL's default complexity policy (upper, lower, digit, symbol).
    return f"Dms-{core}-9x"


def password_key(username: str) -> str:
    return f"DMS_DEV_PASSWORD_{username.upper()}"


def user_id_key(username: str) -> str:
    return f"DMS_DEV_USER_ID_{username.upper()}"


# ---------------------------------------------------------------------------------------------------------------
# Database (schema owner via psql in the db container)


def psql(sql: str) -> str:
    result = subprocess.run(
        ["docker", "compose", "exec", "-T", "db", "psql", "-U", "dms", "-d", "dms", "-v", "ON_ERROR_STOP=1",
         "-qtA"],
        input=sql, text=True, capture_output=True, cwd=ROOT)
    if result.returncode != 0:
        fail(f"psql failed: {result.stderr.strip()}")
    return result.stdout.strip()


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def ensure_tenant(org_id: str) -> str:
    if not re.fullmatch(r"\d+", org_id):
        fail(f"unexpected organization id {org_id!r}")
    sql = f"""
insert into dms.tenant (id, name, zitadel_org_id, time_zone)
select gen_random_uuid(), {sql_literal(TENANT_NAME)}, {sql_literal(org_id)}, {sql_literal(TENANT_TIME_ZONE)}
where not exists (select 1 from dms.tenant where zitadel_org_id = {sql_literal(org_id)});
update dms.tenant set time_zone = {sql_literal(TENANT_TIME_ZONE)}
where zitadel_org_id = {sql_literal(org_id)} and time_zone <> {sql_literal(TENANT_TIME_ZONE)};
select id from dms.tenant where zitadel_org_id = {sql_literal(org_id)};
"""
    return psql(sql).splitlines()[-1]


# The intranet's technical user (DMS-58, ADR 0035): reads the current version of released policies.
INTRANET_USER = "dms-intranet"
INTRANET_CLASSES = ["policy"]
INTRANET_SECRET = LOCAL / "zitadel" / "intranet.secret"


def tenant_id(org_id: str) -> str:
    out = psql(f"select id from dms.tenant where zitadel_org_id = {sql_literal(org_id)};")
    if not out:
        fail("no tenant for the ZITADEL organization; run `scripts/dev_setup.py tenant` first")
    return out


def grant_roles(tenant: str, grants: list[tuple[str, str]]) -> None:
    """Grants unscoped roles that the user does not hold yet. Development setup only: role assignments have no
    HTTP endpoint yet, so this writes the table as schema owner (not recorded in the audit trail)."""
    statements = []
    for user_id, role in grants:
        if not re.fullmatch(r"\d+", user_id):
            fail(f"unexpected user id {user_id!r}")
        statements.append(f"""
insert into dms.role_assignment (tenant_id, user_id, role, granted_by)
select {sql_literal(tenant)}, {sql_literal(user_id)}, {sql_literal(role)}, {sql_literal(GRANTED_BY)}
where not exists (
    select 1 from dms.role_assignment
    where tenant_id = {sql_literal(tenant)} and user_id = {sql_literal(user_id)} and role = {sql_literal(role)}
      and organizational_unit is null and document_class is null
      and (valid_until is null or valid_until > now()));""")
    psql("begin;" + "".join(statements) + "\ncommit;")


def grant_class_roles(tenant: str, user_id: str, role: str, classes: list[str]) -> None:
    """Grants `role` scoped to each document class (subclasses included) that the user does not hold yet. Development
    setup only, like `grant_roles`."""
    if not re.fullmatch(r"\d+", user_id):
        fail(f"unexpected user id {user_id!r}")
    statements = []
    for cls in classes:
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,62}", cls):
            fail(f"unexpected class key {cls!r}")
        statements.append(f"""
insert into dms.role_assignment (tenant_id, user_id, role, document_class, granted_by)
select {sql_literal(tenant)}, {sql_literal(user_id)}, {sql_literal(role)}, {sql_literal(cls)}, {sql_literal(GRANTED_BY)}
where not exists (
    select 1 from dms.role_assignment
    where tenant_id = {sql_literal(tenant)} and user_id = {sql_literal(user_id)} and role = {sql_literal(role)}
      and organizational_unit is null and document_class = {sql_literal(cls)}
      and (valid_until is null or valid_until > now()));""")
    psql("begin;" + "".join(statements) + "\ncommit;")


# ---------------------------------------------------------------------------------------------------------------
# Subcommands


def cmd_tenant() -> None:
    z = Zitadel()
    tid = ensure_tenant(z.org_id)
    print(f"Tenant {tid} for ZITADEL organization {z.org_id} (time zone {TENANT_TIME_ZONE})")


def cmd_users() -> None:
    z = Zitadel()
    stored = read_env(USERS_ENV)
    updates: dict[str, str] = {}
    grants: list[tuple[str, str]] = []
    for username, (given, family, roles) in USERS.items():
        password = stored.get(password_key(username))
        user_id = z.user_id(username)
        if user_id is None:
            password = password or generate_password()
            user_id = z.create_human(username, given, family, password)
            print(f"Created user {username}")
        elif password is None:
            password = generate_password()
            z.set_password(user_id, password)
            print(f"User {username} exists; set a new password")
        else:
            print(f"Reusing user {username}")
        updates[password_key(username)] = password
        updates[user_id_key(username)] = user_id
        grants += [(user_id, role) for role in roles]
    admin_id = z.user_id(ZITADEL_ADMIN)
    if admin_id:
        grants.append((admin_id, "TenantAdmin"))
    if not USERS_ENV.exists():
        USERS_ENV.parent.mkdir(parents=True, exist_ok=True)
        USERS_ENV.write_text("# Demo users of the local ZITADEL (development only; written by scripts/dev-setup.sh).\n"
                             "# Sign in at http://localhost:5173 with the username (e.g. author) and its password.\n")
    set_env_keys(USERS_ENV, updates, mode=0o600)
    grant_roles(tenant_id(z.org_id), grants)
    print(f"Role assignments ensured; passwords are in {USERS_ENV}")


def cmd_intranet() -> None:
    """Ensures the intranet's technical user: a ZITADEL machine user (client credentials, JWT access tokens) with the
    platform role Reader scoped to the released classes. Reader on a class covers its subclasses; confidential
    documents stay hidden unless the user is made a member of an allowed access group (ADR 0032)."""
    z = Zitadel()
    user_id = z.user_id(INTRANET_USER)
    if user_id is None:
        user_id = z.api("POST", "/management/v1/users/machine", {
            "userName": INTRANET_USER, "name": "Intranet (stable addresses, DMS-58)",
            "accessTokenType": "ACCESS_TOKEN_TYPE_JWT"})["userId"]
        print(f"Created machine user {INTRANET_USER}")
    else:
        print(f"Reusing machine user {INTRANET_USER}")
    if not INTRANET_SECRET.exists() or not INTRANET_SECRET.read_text().strip():
        secret = z.api("PUT", f"/management/v1/users/{user_id}/secret", {})
        INTRANET_SECRET.parent.mkdir(parents=True, exist_ok=True)
        INTRANET_SECRET.touch(mode=0o600)
        INTRANET_SECRET.chmod(0o600)
        INTRANET_SECRET.write_text(f"{secret['clientId']}:{secret['clientSecret']}\n")
        print("Generated a client secret")
    grant_class_roles(tenant_id(z.org_id), user_id, "Reader", INTRANET_CLASSES)
    print(f"Reader on {', '.join(INTRANET_CLASSES)} ensured for {INTRANET_USER} (user id {user_id})")
    print(f"Client credentials (client_id:client_secret) are in {INTRANET_SECRET}; a token:")
    print(f"  curl -u \"$(cat {INTRANET_SECRET.relative_to(ROOT)})\" -d grant_type=client_credentials "
          f"--data-urlencode 'scope=openid urn:zitadel:iam:org:project:id:{z.project_id}:aud "
          f"urn:zitadel:iam:user:resourceowner' {z.issuer}/oauth/v2/token")


def user_password(username: str) -> str:
    password = read_env(USERS_ENV).get(password_key(username))
    if not password:
        fail(f"no password for {username} in {USERS_ENV}; run scripts/dev-setup.sh --seed-only")
    return password


def user_token(z: Zitadel, username: str) -> str:
    return z.token(username, user_password(username))


def cmd_token(username: str) -> None:
    print(user_token(Zitadel(), username))


# ---------------------------------------------------------------------------------------------------------------
# Demo content

FROM_REVIEW = ["InReview", "InApproval", "Approved", "Published", "ReviewDue", "Superseded", "Archived"]

FIELDS = [
    {"key": "partnerId", "label": "Partner number", "fieldType": {"type": "Text", "maxLength": 32}},
    {"key": "partnerName", "label": "Contract partner", "fieldType": {"type": "Text", "maxLength": 200}},
    {"key": "partnerAddress", "label": "Partner address", "fieldType": {"type": "Text", "maxLength": 500}},
    {"key": "monthlyFee", "label": "Monthly fee", "fieldType": {"type": "Amount", "min": 0, "currencies": ["EUR"]}},
    {"key": "termMonths", "label": "Term in months", "fieldType": {"type": "Number", "min": 1, "max": 120, "scale": 0}},
    {"key": "termType", "label": "Term", "fieldType": {"type": "Selection", "options": [
        {"code": "fixed", "label": "Fixed term", "active": True},
        {"code": "open", "label": "Open-ended", "active": True}]}},
    {"key": "startDate", "label": "Start date", "fieldType": {"type": "Date"}},
    {"key": "endDate", "label": "End date", "fieldType": {"type": "Date"}},
    {"key": "noticePeriod", "label": "Notice period", "fieldType": {"type": "Text", "maxLength": 100}},
    {"key": "services", "label": "Services", "fieldType": {"type": "Text", "maxLength": 200}, "multiple": True},
    {"key": "slaLevel", "label": "Service level", "fieldType": {"type": "Selection", "options": [
        {"code": "basic", "label": "Basic", "active": True},
        {"code": "standard", "label": "Standard", "active": True},
        {"code": "premium", "label": "Premium", "active": True}]}},
    {"key": "policyArea", "label": "Policy area", "fieldType": {"type": "Selection", "options": [
        {"code": "it-security", "label": "IT security", "active": True},
        {"code": "data-protection", "label": "Data protection", "active": True},
        {"code": "compliance", "label": "Compliance", "active": True},
        {"code": "hr", "label": "Human resources", "active": True}]}},
    {"key": "appliesTo", "label": "Applies to", "fieldType": {"type": "Text", "maxLength": 200}},
    # Short description shown in the library (DMS-119, ADR 0043: conventional key `summary`).
    {"key": "summary", "label": "Kurzbeschreibung", "fieldType": {"type": "Text", "maxLength": 500}},
]

# key -> (label, description, [(field, required from review)])
CLASSES = {
    "contract": ("Contract", "Contracts with business partners", [
        ("partnerId", False), ("partnerName", True), ("partnerAddress", False), ("monthlyFee", True),
        ("termMonths", False), ("termType", True), ("startDate", True), ("endDate", False),
        ("noticePeriod", False), ("services", False), ("slaLevel", False)]),
    "policy": ("Policy", "Internal policies and guidelines", [("policyArea", True), ("appliesTo", False)]),
    # Central instructions for the library (DMS-120); German like their content.
    "central-instruction": ("Zentralanweisung",
                            "Verbindliche Anweisungen der Geschäftsführung und der Zentralbereiche",
                            [("summary", True), ("appliesTo", False)]),
}

WORKFLOWS = {
    "contract": {"definition": {"steps": [
        {"key": "review", "name": "Legal review", "kind": "Review",
         "lanes": [{"key": "legal", "role": "Reviewer"}]},
        {"key": "approval", "name": "Approval", "kind": "Approval",
         "lanes": [{"key": "approver", "role": "Approver"}]}],
        "resetTo": "review"}},
    "policy": {"definition": {"steps": [
        {"key": "approval", "name": "Approval", "kind": "Approval",
         "lanes": [{"key": "approver", "role": "Approver"}]}]}},
    "central-instruction": {"definition": {"steps": [
        {"key": "approval", "name": "Freigabe", "kind": "Approval",
         "lanes": [{"key": "approver", "role": "Approver"}]}]}},
}


def t(value: str, *marks: str) -> dict:
    return {"type": "Text", "value": value, **({"marks": list(marks)} if marks else {})}


def ph(path: str, fmt: str | None = None) -> dict:
    return {"type": "Placeholder", "path": path, **({"format": fmt} if fmt else {})}


def para(*inlines: dict) -> dict:
    return {"type": "Paragraph", "content": list(inlines)}


def heading(level: int, text: str) -> dict:
    return {"type": "Heading", "level": level, "content": [t(text)]}


def cell(*inlines: dict) -> list[dict]:
    return [para(*inlines)]


def section(sid: str, title: str, locked: bool, *blocks: dict) -> dict:
    return {"id": sid, "title": title, "locked": locked, "blocks": list(blocks)}


SERVICE_CONTRACT = {
    "name": "Service contract",
    "description": "IT service agreement with a partner from the partner master, computed contract value",
    "content": {
        "sections": [
            section("parties", "IT Service Agreement", True,
                    heading(1, "IT Service Agreement"),
                    para(t("Between "), ph("partnerName"), t(" (partner number "), ph("partnerId"), t("), "),
                         ph("partnerAddress"), {"type": "LineBreak"},
                         t("and Sample Managed Services GmbH, starting "), ph("startDate", "date:long"), t(".")),
                    para(t("Contract number: "), ph("documentId"), t(", version "), ph("version"), t("."))),
            section("subject", "1. Subject matter", False,
                    para(t("The provider performs the following services for the client:")),
                    {"type": "Repeat", "collection": "services", "itemName": "service",
                     "body": [{"type": "BulletList", "items": [[para(ph("service"))]]}]},
                    para(t("Service level: "), ph("slaLevel"), t("."))),
            section("term", "2. Term and termination", True,
                    {"type": "Conditional", "condition": "termType == 'fixed'",
                     "body": [para(t("The agreement runs for "), ph("termMonths", "number"),
                                   t(" months and ends on "), ph("endDate", "date:long"), t("."))],
                     "otherwise": [para(t("The agreement is open-ended. Notice period: "), ph("noticePeriod"),
                                        t("."))]}),
            section("fees", "3. Fees", True,
                    {"type": "Table", "headerRows": 1, "rows": [
                        [cell(t("Item")), cell(t("Amount"))],
                        [cell(t("Monthly fee")), cell(ph("monthlyFee", "amount"))],
                        [cell(t("Annual fee")), cell(ph("annualFee", "number"))],
                        [cell(t("Total contract value")), cell(ph("totalValue", "number"))]]},
                    {"type": "Conditional", "condition": "totalValue > 250000.0",
                     "body": [para(t("The contract value requires approval by the management board.", "Italic"))]}),
            section("liability", "4. Liability", True,
                    para(t("The provider is liable for intent and gross negligence without limitation. For slight "
                           "negligence it is liable only for breaches of essential contractual obligations, limited "
                           "to the foreseeable damage typical for this kind of contract."))),
            section("final", "5. Final provisions", False,
                    para(t("Amendments must be made in text form. Should a provision be invalid, the remaining "
                           "provisions stay in force."))),
        ],
        "computed": [
            {"key": "annualFee", "expression": "monthlyFee.amount * 12.0"},
            {"key": "totalValue", "expression": "monthlyFee.amount * termMonths"},
        ],
        "references": [
            {"key": "partner", "label": "Contract partner", "source": "partner-master", "fields": [
                {"attribute": "id", "field": "partnerId"},
                {"attribute": "name", "field": "partnerName"},
                {"attribute": "address", "field": "partnerAddress"}]},
        ],
    },
}

POLICY = {
    "name": "Policy",
    "description": "Internal policy with scope, rules and validity",
    "content": {
        "sections": [
            section("header", "Policy", True,
                    {"type": "Heading", "level": 1, "content": [ph("title")]},
                    para(t("Policy area: "), ph("policyArea"), t(". Organizational unit: "), ph("organizationalUnit"),
                         t(".")),
                    para(t("Valid from "), ph("validFrom", "date:long"), t(". Applies to: "), ph("appliesTo"),
                         t("."))),
            section("purpose", "1. Purpose", False,
                    para(t("This policy describes the binding rules for its area."))),
            section("rules", "2. Rules", False,
                    {"type": "OrderedList", "items": [
                        [para(t("Every employee in scope follows this policy."))],
                        [para(t("Exceptions need the written consent of the policy owner."))]]}),
            section("compliance", "3. Compliance", True,
                    para(t("Violations of this policy may lead to measures under employment law."))),
        ],
    },
}



def bullets(*items: str) -> dict:
    return {"type": "BulletList", "items": [[para(t(item))] for item in items]}


# Central instructions (DMS-120): German demo content for the library (DMS-119). Locked sections carry the
# identification and the entry into force; the three editable sections hold each instruction's rules.
INSTRUCTION_LOCKED_HEADER = section(
    "header", "Zentralanweisung", True,
    {"type": "Heading", "level": 1, "content": [ph("title")]},
    para(t("Zentralanweisung "), ph("documentNumber"), t(" · Herausgebende Stelle: "), ph("organizationalUnit"),
         t(" · Gültig ab "), ph("validFrom", "date:long")),
    para(t("Kurzbeschreibung: ", "Bold"), ph("summary")))
INSTRUCTION_LOCKED_FINAL = section(
    "final", "4. Inkrafttreten", True,
    para(t("Diese Zentralanweisung tritt am "), ph("validFrom", "date:long"),
         t(" in Kraft und ersetzt alle entgegenstehenden Regelungen. Verstöße können arbeitsrechtliche "
           "Maßnahmen nach sich ziehen. Fragen beantwortet die herausgebende Stelle.")))


def instruction_content(scope: list[dict], rules: list[dict], duties: list[dict]) -> dict:
    return {"sections": [
        INSTRUCTION_LOCKED_HEADER,
        section("scope", "1. Geltungsbereich", False, *scope),
        section("rules", "2. Regelungen", False, *rules),
        section("duties", "3. Verantwortlichkeiten", False, *duties),
        INSTRUCTION_LOCKED_FINAL,
    ]}


CENTRAL_INSTRUCTION = {
    "name": "Zentralanweisung",
    "description": "Verbindliche Anweisung mit Geltungsbereich, Regelungen und Verantwortlichkeiten",
    "content": instruction_content(
        [para(t("Diese Zentralanweisung gilt für alle Beschäftigten der Gesellschaft."))],
        [para(t("Die Regelungen dieser Anweisung sind verbindlich."))],
        [para(t("Die Führungskräfte sorgen für die Umsetzung in ihrem Bereich."))]),
}

# Published, currently valid central instructions: title -> metadata and the text of the editable sections.
# `valid_from` is days before today, so the versions are valid at once and the library shows them.
INSTRUCTIONS = [
    {
        "title": "Informationssicherheit am Arbeitsplatz",
        "unit": "IT-Sicherheit",
        "tags": ["Informationssicherheit", "IT", "Passwörter"],
        "summary": "Regeln für Passwörter, Bildschirmsperre, mobile Datenträger und das Melden von "
                   "Sicherheitsvorfällen.",
        "applies_to": "Alle Beschäftigten, Auszubildenden und externen Dienstleister mit IT-Zugang",
        "valid_from": 120,
        "content": instruction_content(
            [para(t("Diese Anweisung gilt für alle Beschäftigten, Auszubildenden und externen Dienstleister, die "
                    "IT-Systeme oder Informationen der Gesellschaft nutzen, unabhängig vom Arbeitsort."))],
            [para(t("Für den Umgang mit Informationen und IT-Systemen gilt:")),
             bullets("Passwörter haben mindestens zwölf Zeichen, werden nicht weitergegeben und nicht für private "
                     "Dienste wiederverwendet; wo möglich ist die Mehrfaktor-Anmeldung zu nutzen.",
                     "Beim Verlassen des Arbeitsplatzes wird der Bildschirm gesperrt (Windows-Taste + L).",
                     "Vertrauliche Informationen werden nur auf freigegebenen, verschlüsselten Datenträgern "
                     "gespeichert; private USB-Sticks sind nicht zulässig.",
                     "Verdächtige E-Mails werden nicht geöffnet, sondern über die Schaltfläche „Phishing "
                     "melden“ weitergeleitet."),
             para(t("Sicherheitsvorfälle, auch Verdachtsfälle, sind unverzüglich dem IT-Service-Desk "
                    "(Durchwahl 4444) zu melden.", "Bold"))],
            [para(t("Die Führungskräfte stellen sicher, dass ihre Beschäftigten jährlich an der "
                    "Sicherheitsschulung teilnehmen. Die IT-Sicherheit überprüft die Einhaltung stichprobenartig "
                    "und berichtet halbjährlich an die Geschäftsführung."))]),
    },
    {
        "title": "Reisekostenrichtlinie",
        "unit": "Finanzen",
        "tags": ["Reisekosten", "Dienstreisen", "Abrechnung"],
        "summary": "Genehmigung, Buchung und Abrechnung von Dienstreisen einschließlich Verkehrsmittel, "
                   "Übernachtung und Verpflegungspauschalen.",
        "applies_to": "Alle Beschäftigten, die Dienstreisen antreten",
        "valid_from": 75,
        "content": instruction_content(
            [para(t("Diese Richtlinie regelt Dienstreisen im In- und Ausland aller Beschäftigten der "
                    "Gesellschaft. Sie gilt nicht für Fahrten zwischen Wohnung und regelmäßiger Arbeitsstätte."))],
            [para(t("Dienstreisen werden vor Antritt im Reiseportal beantragt und von der Führungskraft "
                    "genehmigt.")),
             bullets("Bahnreisen erfolgen in der 2. Klasse; ab einer Fahrzeit von vier Stunden ist die 1. Klasse "
                     "erstattungsfähig.",
                     "Flugreisen sind nur bei einer einfachen Bahnfahrzeit von mehr als sechs Stunden zulässig.",
                     "Übernachtungskosten werden bis 120 EUR je Nacht erstattet, in Städten mit über einer Million "
                     "Einwohnern bis 160 EUR.",
                     "Verpflegungsmehraufwand wird nach den steuerlichen Pauschalen erstattet."),
             para(t("Die Reisekostenabrechnung ist spätestens einen Monat nach Reiseende mit allen Belegen "
                    "einzureichen."))],
            [para(t("Die Führungskraft prüft die Notwendigkeit der Reise. Die Finanzbuchhaltung prüft die "
                    "Abrechnung und zahlt erstattungsfähige Beträge mit der nächsten Gehaltsabrechnung aus."))]),
    },
    {
        "title": "Umgang mit personenbezogenen Daten",
        "unit": "Datenschutz",
        "tags": ["Datenschutz", "DSGVO", "Personenbezogene Daten"],
        "summary": "Grundsätze der DSGVO im Arbeitsalltag: Zweckbindung, Datensparsamkeit, Auskunftsersuchen "
                   "und Meldung von Datenschutzverletzungen innerhalb von 24 Stunden.",
        "applies_to": "Alle Beschäftigten, die personenbezogene Daten verarbeiten",
        "valid_from": 40,
        "content": instruction_content(
            [para(t("Diese Anweisung gilt für jede Verarbeitung personenbezogener Daten von Kundinnen und "
                    "Kunden, Beschäftigten, Bewerberinnen und Bewerbern sowie Geschäftspartnern."))],
            [para(t("Personenbezogene Daten werden nur für festgelegte Zwecke und nur im erforderlichen Umfang "
                    "verarbeitet.")),
             bullets("Neue Verarbeitungen werden vor dem Start dem Datenschutz gemeldet und in das "
                     "Verzeichnis der Verarbeitungstätigkeiten aufgenommen.",
                     "Auskunfts- und Löschersuchen werden noch am Tag des Eingangs an den Datenschutz "
                     "weitergeleitet.",
                     "Daten werden nach Ablauf der Aufbewahrungsfrist gelöscht.",
                     "Personenbezogene Daten werden nicht per unverschlüsselter E-Mail an Externe versandt."),
             para(t("Datenschutzverletzungen sind innerhalb von 24 Stunden an datenschutz@example.com zu "
                    "melden, damit die gesetzliche Meldefrist von 72 Stunden eingehalten werden kann.",
                    "Bold"))],
            [para(t("Die oder der Datenschutzbeauftragte berät, schult und überwacht die Einhaltung. Die "
                    "Fachbereiche sind für die Rechtmäßigkeit ihrer Verarbeitungen verantwortlich."))]),
    },
    {
        "title": "Mobiles Arbeiten und Homeoffice",
        "unit": "Personal",
        "tags": ["Homeoffice", "Mobiles Arbeiten", "Arbeitszeit"],
        "summary": "Voraussetzungen, Umfang und Arbeitsschutz beim mobilen Arbeiten: bis zu drei Tage je Woche "
                   "nach Absprache im Team.",
        "applies_to": "Beschäftigte, deren Tätigkeit mobiles Arbeiten zulässt",
        "valid_from": 10,
        "content": instruction_content(
            [para(t("Diese Anweisung gilt für alle Beschäftigten, deren Tätigkeit sich für mobiles Arbeiten "
                    "eignet. Ausgenommen sind Tätigkeiten, die die Anwesenheit im Betrieb erfordern."))],
            [bullets("Mobiles Arbeiten ist an bis zu drei Tagen je Woche möglich; die Tage werden im Team "
                     "abgestimmt.",
                     "Die Erreichbarkeit während der Kernarbeitszeit von 9 bis 15 Uhr ist sicherzustellen.",
                     "Die Arbeitszeit wird wie im Büro erfasst; Ruhezeiten und Höchstarbeitszeiten gelten "
                     "unverändert.",
                     "Vertrauliche Unterlagen sind vor dem Zugriff Dritter zu schützen; Ausdrucke werden "
                     "im Büro vernichtet."),
             para(t("Für die Ausstattung stellt die Gesellschaft Notebook, Headset und auf Antrag einen zweiten "
                    "Bildschirm bereit."))],
            [para(t("Die Führungskraft entscheidet über den Umfang des mobilen Arbeitens und kann ihn aus "
                    "betrieblichen Gründen einschränken. Der Bereich Personal berät zur Gefährdungsbeurteilung "
                    "des häuslichen Arbeitsplatzes."))]),
    },
]


# A Word template with content controls (DMS-115, ADR 0042) for central instructions, and a draft from it.
WORD_TEMPLATE_NAME = "Zentralanweisung (Word)"
WORD_TEMPLATE_FILE = "zentralanweisung.docx"
WORD_DOCUMENT_TITLE = "Dienstwagenregelung (Word-Entwurf)"
WORD_NAMESPACE = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"


def word_control(tag: str, alias: str, placeholder: str) -> str:
    """A plain text content control: tag `dms:<key>` is filled by the platform, any other tag is a manual
    field; the alias (title) is the label in the form."""
    return (f'<w:sdt><w:sdtPr><w:alias w:val="{alias}"/><w:tag w:val="{tag}"/><w:showingPlcHdr/></w:sdtPr>'
            f'<w:sdtContent><w:r><w:rPr><w:rStyle w:val="PlaceholderText"/></w:rPr><w:t>{placeholder}</w:t>'
            f'</w:r></w:sdtContent></w:sdt>')


def word_paragraph(*parts: str, bold: bool = False) -> str:
    def run(part: str) -> str:
        if part.startswith("<w:sdt>"):
            return part
        props = "<w:rPr><w:b/></w:rPr>" if bold else ""
        return f'<w:r>{props}<w:t xml:space="preserve">{part}</w:t></w:r>'
    return "<w:p>" + "".join(run(p) for p in parts) + "</w:p>"


def word_template_docx() -> bytes:
    """A minimal DOCX (only the parts Word needs to open it) with automatic and manual content controls."""
    body = "".join([
        word_paragraph(word_control("dms:title", "Titel", "Titel der Anweisung"), bold=True),
        word_paragraph("Zentralanweisung ", word_control("dms:documentNumber", "Dokumentnummer", "ZA-…"),
                       " · Index ", word_control("dms:version", "Index", "1.0")),
        word_paragraph("Herausgebende Stelle: ",
                       word_control("dms:issuingUnit", "Herausgebende Stelle", "Bereich")),
        word_paragraph("Gültig ab: ", word_control("dms:validFrom", "Gültig ab", "Datum")),
        word_paragraph("Kurzbeschreibung: ", word_control("dms:summary", "Kurzbeschreibung", "Kurzbeschreibung")),
        word_paragraph("1. Regelungen", bold=True),
        word_paragraph("Dienstwagen stehen Beschäftigten zu, deren Tätigkeit regelmäßige Fahrten erfordert. "
                       "Die Privatnutzung wird nach der Ein-Prozent-Regel versteuert."),
        word_paragraph("Ansprechpartnerin oder Ansprechpartner: ",
                       word_control("contact", "Ansprechpartner", "Name und Durchwahl")),
        word_paragraph("Freigegeben am ", word_control("dms:approvedAt", "Freigabedatum", "Datum"),
                       " durch ", word_control("dms:approvedBy", "Freigegeben durch", "Name")),
    ])
    document = (f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                f'<w:document xmlns:w="{WORD_NAMESPACE}"><w:body>{body}</w:body></w:document>')
    content_types = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/word/document.xml" '
        'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
        '</Types>')
    rels = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" '
        'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
        'Target="word/document.xml"/></Relationships>')
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as z:
        # Fixed timestamps: the same bytes on every run.
        for name, text in [("[Content_Types].xml", content_types), ("_rels/.rels", rels),
                           ("word/document.xml", document)]:
            z.writestr(zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0)), text)
    return buffer.getvalue()


# Header with the OIDC ID token for step-up-protected requests (approval votes, DMS-86).
ID_TOKEN_HEADER = "X-DMS-Id-Token"


class Api:
    def __init__(self, base: str, token: str, id_token: str | None = None):
        self.base = base.rstrip("/")
        self.token = token
        self.id_token = id_token

    def __call__(self, method: str, path: str, body=None):
        headers = {ID_TOKEN_HEADER: self.id_token} if self.id_token else None
        return http(method, self.base + path, body, token=self.token, headers=headers)


def ensure_fields(admin: Api) -> dict[str, int]:
    existing = {f["key"]: f["version"] for f in admin("GET", "/api/v1/admin/field-definitions")}
    for field in FIELDS:
        if field["key"] not in existing:
            created = admin("POST", "/api/v1/admin/field-definitions", field)
            existing[field["key"]] = created["version"]
            print(f"  field {field['key']}: created")
    return existing


def ensure_classes(admin: Api, versions: dict[str, int]) -> dict[str, dict]:
    classes = {c["key"]: c for c in admin("GET", "/api/v1/admin/document-classes")}
    for key, (label, description, bindings) in CLASSES.items():
        if key in classes:
            print(f"  class {key}: exists (version {classes[key]['version']})")
            continue
        body = {"key": key, "label": label, "description": description, "fields": [
            {"key": f, "version": versions[f], "requiredIn": FROM_REVIEW if required else []}
            for f, required in bindings]}
        classes[key] = admin("POST", "/api/v1/admin/document-classes", body)
        print(f"  class {key}: created")
    return classes


def ensure_workflows(admin: Api) -> None:
    for key, definition in WORKFLOWS.items():
        if admin("GET", f"/api/v1/admin/workflows/{key}"):
            print(f"  workflow {key}: exists")
        else:
            admin("POST", f"/api/v1/admin/workflows/{key}", definition)
            print(f"  workflow {key}: created")


def ensure_groups(admin: Api, users: dict[str, str]) -> None:
    """Creates the demo access groups and adds their members; both calls are idempotent."""
    existing = {g["key"] for g in admin("GET", "/api/v1/groups")}
    for key, (name, members) in GROUPS.items():
        if key in existing:
            print(f"  group {key}: exists")
        else:
            admin("POST", "/api/v1/groups", {"key": key, "name": name})
            print(f"  group {key}: created")
        for username in members:
            user_id = users[user_id_key(username)]
            admin("PUT", f"/api/v1/groups/{key}/members/{urllib.parse.quote(user_id, safe='')}")
        print(f"  group {key}: members {', '.join(members)}")


# Number ranges (DMS-113): key -> (name, pattern, start value, yearly reset, classes they are assigned to).
NUMBER_RANGES = {
    "contracts": ("Contracts", "VTR-{YYYY}-{NNNN}", 1, True, ["contract"]),
    "policies": ("Policies", "RL-{NNN}", 1, False, ["policy"]),
    "central-instructions": ("Zentralanweisungen", "ZA-{YYYY}-{NNN}", 1, True, ["central-instruction"]),
}


def ensure_number_ranges(admin: Api, classes: dict[str, dict]) -> None:
    """Creates the demo number ranges and assigns them to their classes; skips what exists (idempotent)."""
    existing = {r["key"]: r for r in admin("GET", "/api/v1/admin/number-ranges")}
    assignments = {a["classKey"]: a for a in admin("GET", "/api/v1/admin/number-range-assignments")}
    for key, (name, pattern, start, yearly, class_keys) in NUMBER_RANGES.items():
        if key in existing:
            print(f"  number range {key}: exists (next {existing[key]['nextNumber']})")
        else:
            existing[key] = admin("POST", "/api/v1/admin/number-ranges", {
                "key": key, "name": name, "pattern": pattern, "startValue": start, "yearlyReset": yearly})
            print(f"  number range {key}: created ({pattern})")
        for class_key in class_keys:
            current = assignments.get(class_key)
            if current and current["mode"] == "range" and current.get("numberRangeId") == existing[key]["id"]:
                continue
            if current and current["mode"] != "inherit":
                print(f"  number range of class {class_key}: kept ({current['mode']})")
                continue
            admin("PUT", f"/api/v1/admin/number-range-assignments/{classes[class_key]['id']}",
                  {"numberRangeId": existing[key]["id"]})
            print(f"  number range {key}: assigned to class {class_key}")


def ensure_template(designer: Api, designer2: Api, spec: dict, cls: dict) -> dict:
    """Creates the template as `designer`, moves it to Testing and releases it as `designer2` (four eyes)."""
    latest = {v["name"]: v for v in designer("GET", "/api/v1/admin/templates")}.get(spec["name"])
    if latest is None:
        body = {**spec, "documentClassId": cls["id"], "documentClassVersion": cls["version"]}
        latest = designer("POST", "/api/v1/admin/templates", body)
        print(f"  template {spec['name']}: created")
    path = f"/api/v1/admin/templates/{latest['templateId']}/versions/{latest['version']}/status"
    if latest["status"] == "Draft":
        latest = designer("POST", path, {"status": "Testing"})
        print(f"  template {spec['name']}: in testing")
    if latest["status"] == "Testing":
        latest = designer2("POST", path, {"status": "Released"})
        print(f"  template {spec['name']}: released by designer2")
    else:
        print(f"  template {spec['name']}: {latest['status']} (version {latest['version']})")
    return latest


def ensure_sample_draft(author: Api, author_id: str, template: dict) -> None:
    title = "Service contract Nordlicht Logistik"
    if any(d["title"] == title for d in author("GET", "/api/v1/documents?limit=100")):
        print(f"  document '{title}': exists")
        return
    today = datetime.date.today()
    values = {
        "monthlyFee": {"type": "Amount", "value": 4800, "currency": "EUR"},
        "termMonths": {"type": "Number", "value": 24},
        "termType": {"type": "Selection", "code": "fixed"},
        "startDate": {"type": "Date", "value": today.replace(day=1).isoformat()},
        "endDate": {"type": "Date", "value": today.replace(year=today.year + 2, day=1).isoformat()},
        "services": {"type": "Many", "values": [{"type": "Text", "value": v} for v in
                                                ["Managed hosting", "Service desk", "Backup and restore"]]},
        "slaLevel": {"type": "Selection", "code": "standard"},
        "owner": {"type": "PersonRef", "userId": author_id},
        "organizationalUnit": {"type": "Text", "value": "Legal"},
        "validFrom": {"type": "Date", "value": today.isoformat()},
        "confidentiality": {"type": "Selection", "code": "Internal"},
        "language": {"type": "Text", "value": "en"},
        "retentionRule": {"type": "Text", "value": "contract-10y"},
        "containsPersonalData": {"type": "YesNo", "value": False},
    }
    doc = author("POST", "/api/v1/documents", {
        "templateId": template["templateId"], "templateVersion": template["version"], "title": title,
        "values": values})
    author("PUT", f"/api/v1/documents/{doc['id']}/references/partner", {"entryId": "P-10001"})
    print(f"  document '{title}': draft created by author")


def instruction_values(spec: dict, author_id: str, today: datetime.date) -> dict:
    """Field values of a central instruction: the core fields needed from review on, tags and the class fields."""
    return {
        "owner": {"type": "PersonRef", "userId": author_id},
        "organizationalUnit": {"type": "Text", "value": spec["unit"]},
        "validFrom": {"type": "Date", "value": (today - datetime.timedelta(days=spec["valid_from"])).isoformat()},
        "confidentiality": {"type": "Selection", "code": "Internal"},
        "language": {"type": "Text", "value": "de"},
        "retentionRule": {"type": "Text", "value": "policy-10y"},
        "containsPersonalData": {"type": "YesNo", "value": False},
        "tags": {"type": "Many", "values": [{"type": "Text", "value": tag} for tag in spec["tags"]]},
        "summary": {"type": "Text", "value": spec["summary"]},
        "appliesTo": {"type": "Text", "value": spec["applies_to"]},
    }


VALID = ("Published", "ReviewDue")


def document_statuses(author: Api) -> dict[str, dict]:
    """The author's documents by title (the latest change wins for equal titles)."""
    documents: dict[str, dict] = {}
    for d in reversed(author("GET", "/api/v1/documents?limit=200")):
        documents[d["title"]] = d
    return documents


def ensure_instructions(z: Zitadel, api_base: str, author: Api, author_id: str, template: dict) -> None:
    """Publishes the demo central instructions through the regular workflow: `author` creates, fills and
    submits each one, `approver` approves it with a fresh sign-in (step-up, as `approve-tasks` does), the
    rendering job seals the rendition and the publication job publishes it (its valid-from date has come).
    Idempotent: documents are found by title and continued from their current status."""
    today = datetime.date.today()
    existing = document_statuses(author)
    waiting: dict[str, str] = {}
    for spec in INSTRUCTIONS:
        title = spec["title"]
        doc = existing.get(title)
        if doc and doc["status"] in VALID:
            print(f"  instruction '{title}': published ({doc.get('documentNumber', 'no number')})")
            continue
        values = instruction_values(spec, author_id, today)
        if doc is None:
            doc = author("POST", "/api/v1/documents", {
                "templateId": template["templateId"], "templateVersion": template["version"], "title": title,
                "values": values})
            doc["status"] = "Draft"
            print(f"  instruction '{title}': created as {doc.get('documentNumber', 'draft without number')}")
        if doc["status"] == "Draft":
            author("PUT", f"/api/v1/documents/{doc['id']}/draft",
                   {"title": title, "values": values, "content": spec["content"]})
            author("POST", f"/api/v1/documents/{doc['id']}/submit")
            print(f"  instruction '{title}': submitted")
        waiting[doc["id"]] = title
    if not waiting:
        return
    approve_documents(z, "approver", api_base, set(waiting))
    wait_for_publication(author, waiting)


def approve_documents(z: Zitadel, username: str, api_base: str, document_ids: set[str]) -> None:
    """Approves the open tasks of `username` on the given documents with a fresh sign-in (ID token for the
    step-up check). Tasks are created with the submission; a short retry covers a slow workflow start."""
    tokens = z.tokens(username, user_password(username))
    api = Api(api_base, tokens["access_token"], tokens.get("id_token"))
    for _ in range(10):
        tasks = [task for task in api("GET", "/api/v1/workflow/tasks")
                 if task["status"] == "Open" and task["documentId"] in document_ids]
        for task in tasks:
            api("POST", f"/api/v1/workflow/tasks/{task['id']}/votes", {"decision": "Approve"})
            print(f"  {username} approved '{task['stepName']}' of document {task['documentId']}")
        if tasks:
            return
        time.sleep(1)
    print(f"  no open tasks of {username} for the instructions (approved already?)")


def wait_for_publication(author: Api, waiting: dict[str, str], seconds: int = 180) -> None:
    """Waits until the instructions are published: the rendering job seals the approved versions (rendering
    worker) and the publication job publishes them, as their valid-from date has come."""
    print("  waiting for rendition and publication ...")
    deadline = time.monotonic() + seconds
    statuses: dict[str, str] = {}
    while time.monotonic() < deadline:
        current = document_statuses(author)
        statuses = {title: current.get(title, {}).get("status", "?") for title in waiting.values()}
        if all(s in VALID for s in statuses.values()):
            break
        time.sleep(3)
    for title, status in statuses.items():
        print(f"  instruction '{title}': {status}")
    if not all(s in VALID for s in statuses.values()):
        print("  not all instructions are published yet: the rendering worker and the background jobs must run "
              "(see .local/logs/backend.log); run the seed again later to continue")


def multipart_file(field: str, filename: str, content_type: str, data: bytes) -> tuple[bytes, str]:
    boundary = "dms-dev-setup-" + secrets.token_hex(12)
    head = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"; filename=\"{filename}\"\r\n"
            f"Content-Type: {content_type}\r\n\r\n").encode()
    return head + data + f"\r\n--{boundary}--\r\n".encode(), f"multipart/form-data; boundary={boundary}"


DOCX_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"


def ensure_word_example(designer: Api, designer2: Api, author: Api, author_id: str, cls: dict) -> None:
    """A Word template for central instructions (DOCX with content controls) released with four eyes, and a
    draft file document from it with its manual field filled (DMS-115). Idempotent by name and title."""
    latest = {v["name"]: v for v in designer("GET", "/api/v1/admin/templates")}.get(WORD_TEMPLATE_NAME)
    if latest is None:
        latest = designer("POST", "/api/v1/admin/templates", {
            "name": WORD_TEMPLATE_NAME,
            "description": "Zentralanweisung als Word-Dokument mit Feldern für Nummer, Index und Freigabe",
            "documentClassId": cls["id"], "documentClassVersion": cls["version"], "content": {"sections": []}})
        print(f"  template {WORD_TEMPLATE_NAME}: created")
    word = f"/api/v1/admin/templates/{latest['templateId']}/versions/{latest['version']}/word"
    status_path = f"/api/v1/admin/templates/{latest['templateId']}/versions/{latest['version']}/status"
    if latest["status"] == "Draft":
        try:
            designer("GET", word)
        except HttpError as e:
            if e.status != 404:
                raise
            body, content_type = multipart_file("file", WORD_TEMPLATE_FILE, DOCX_TYPE, word_template_docx())
            uploaded = http("PUT", designer.base + word, token=designer.token, raw=(body, content_type))
            print(f"  template {WORD_TEMPLATE_NAME}: Word file with fields "
                  f"{', '.join(f['key'] for f in uploaded['fields'])}")
        latest = designer("POST", status_path, {"status": "Testing"})
    if latest["status"] == "Testing":
        latest = designer2("POST", status_path, {"status": "Released"})
        print(f"  template {WORD_TEMPLATE_NAME}: released by designer2")
    if latest["status"] != "Released":
        print(f"  template {WORD_TEMPLATE_NAME}: {latest['status']}; no Word document")
        return
    print(f"  template {WORD_TEMPLATE_NAME}: released (version {latest['version']})")
    if WORD_DOCUMENT_TITLE in document_statuses(author):
        print(f"  document '{WORD_DOCUMENT_TITLE}': exists")
        return
    spec = {"unit": "Einkauf", "valid_from": 0, "tags": ["Dienstwagen", "Fuhrpark"],
            "summary": "Anspruch, Nutzung und Versteuerung von Dienstwagen.",
            "applies_to": "Beschäftigte mit Dienstwagen"}
    doc = author("POST", "/api/v1/documents/word", {
        "templateId": latest["templateId"], "templateVersion": latest["version"], "title": WORD_DOCUMENT_TITLE,
        "values": instruction_values(spec, author_id, datetime.date.today())})
    author("PUT", f"/api/v1/documents/{doc['id']}/word-fields",
           {"values": {"contact": "Fuhrparkmanagement, Durchwahl 2310"}})
    print(f"  document '{WORD_DOCUMENT_TITLE}': Word draft created by author")


def show_library(z: Zitadel, api_base: str) -> None:
    reader = Api(api_base, user_token(z, "reader"))
    library = reader("GET", "/api/v1/library?class=central-instruction")
    print(f"  library: {library['total']} central instruction(s) valid for reader")


def cmd_seed(api_base: str) -> None:
    z = Zitadel()
    users = read_env(USERS_ENV)
    print("Seeding demo content through the API ...")
    try:
        admin = Api(api_base, user_token(z, "admin"))
        designer = Api(api_base, user_token(z, "designer"))
        designer2 = Api(api_base, user_token(z, "designer2"))
        author = Api(api_base, user_token(z, "author"))
        versions = ensure_fields(admin)
        classes = ensure_classes(admin, versions)
        ensure_workflows(admin)
        ensure_groups(admin, users)
        ensure_number_ranges(admin, classes)
        contract = ensure_template(designer, designer2, SERVICE_CONTRACT, classes["contract"])
        ensure_template(designer, designer2, POLICY, classes["policy"])
        if contract["status"] == "Released":
            ensure_sample_draft(author, users[user_id_key("author")], contract)
        instruction = ensure_template(designer, designer2, CENTRAL_INSTRUCTION, classes["central-instruction"])
        if instruction["status"] == "Released":
            ensure_instructions(z, api_base, author, users[user_id_key("author")], instruction)
            show_library(z, api_base)
        ensure_word_example(designer, designer2, author, users[user_id_key("author")],
                            classes["central-instruction"])
    except HttpError as e:
        fail(str(e))
    print("Demo content ready.")


# Keys the demo templates may print besides class fields and computed fields (core fields, ADR 0019).
CORE_KEYS = {"documentId", "documentNumber", "title", "documentClass", "version", "status", "owner",
             "organizationalUnit", "scope", "validFrom", "validUntil", "nextReview", "confidentiality",
             "language", "retentionRule", "containsPersonalData", "tags", "template", "templateVersion"}


def placeholders(node) -> set[str]:
    """Paths of all placeholders in a content tree (repeat item names included)."""
    found: set[str] = set()
    if isinstance(node, dict):
        if node.get("type") == "Placeholder":
            found.add(node["path"].split(".")[0])
        for value in node.values():
            found |= placeholders(value)
    elif isinstance(node, list):
        for value in node:
            found |= placeholders(value)
    return found


def check_demo_content(dump: Path | None) -> list[str]:
    """Offline consistency checks of the demo content (no services needed); returns the problems."""
    problems: list[str] = []
    fields = {f["key"]: f for f in FIELDS}
    for key, (_, _, bindings) in CLASSES.items():
        problems += [f"class {key}: unknown field {f}" for f, _ in bindings if f not in fields]
        if key not in WORKFLOWS:
            problems.append(f"class {key}: no workflow")
    for key, (_, pattern, _, yearly, class_keys) in NUMBER_RANGES.items():
        if len(re.findall(r"\{N+\}", pattern)) != 1:
            problems.append(f"number range {key}: needs exactly one counter")
        if yearly and "{YY" not in pattern:
            problems.append(f"number range {key}: a yearly reset needs the year")
        problems += [f"number range {key}: unknown class {c}" for c in class_keys if c not in CLASSES]
    templates = [(SERVICE_CONTRACT, "contract"), (POLICY, "policy"), (CENTRAL_INSTRUCTION, "central-instruction")]
    for spec, cls in templates:
        known = CORE_KEYS | {f for f, _ in CLASSES[cls][2]} | {c["key"] for c in spec["content"].get("computed", [])}
        repeats = {b.get("itemName") for s in spec["content"]["sections"] for b in s["blocks"] if b.get("itemName")}
        unknown = placeholders(spec["content"]) - known - repeats
        problems += [f"template {spec['name']}: unknown placeholder {p}" for p in sorted(unknown)]
    template_sections = CENTRAL_INSTRUCTION["content"]["sections"]
    titles = set()
    for spec in INSTRUCTIONS:
        sections = spec["content"]["sections"]
        if [s["id"] for s in sections] != [s["id"] for s in template_sections]:
            problems.append(f"instruction {spec['title']}: sections differ from the template")
        for mine, theirs in zip(sections, template_sections):
            if theirs["locked"] and mine != theirs:
                problems.append(f"instruction {spec['title']}: locked section {theirs['id']} changed")
        if spec["title"] in titles:
            problems.append(f"instruction {spec['title']}: title used twice")
        titles.add(spec["title"])
        if len(spec["summary"]) > fields["summary"]["fieldType"]["maxLength"]:
            problems.append(f"instruction {spec['title']}: summary too long")
        if len(spec["applies_to"]) > fields["appliesTo"]["fieldType"]["maxLength"]:
            problems.append(f"instruction {spec['title']}: appliesTo too long")
        problems += [f"instruction {spec['title']}: tag too long" for tag in spec["tags"] if len(tag) > 100]
        if spec["valid_from"] < 0:
            problems.append(f"instruction {spec['title']}: valid from in the future")
    if len({spec["unit"] for spec in INSTRUCTIONS}) < 2:
        problems.append("instructions: use different issuing units")
    docx = word_template_docx()
    if docx != word_template_docx():
        problems.append("Word template: not reproducible")
    with zipfile.ZipFile(io.BytesIO(docx)) as z:
        root = ElementTree.fromstring(z.read("word/document.xml"))
    tags = {e.get(f"{{{WORD_NAMESPACE}}}val") for e in root.iter(f"{{{WORD_NAMESPACE}}}tag")}
    expected = {"dms:title", "dms:documentNumber", "dms:version", "dms:issuingUnit", "dms:validFrom",
                "dms:summary", "contact", "dms:approvedAt", "dms:approvedBy"}
    if tags != expected:
        problems.append(f"Word template: content control tags {sorted(tags)}")
    if dump is not None:
        dump.mkdir(parents=True, exist_ok=True)
        (dump / "central-instruction-template.json").write_text(json.dumps(CENTRAL_INSTRUCTION["content"]))
        for i, spec in enumerate(INSTRUCTIONS):
            (dump / f"instruction-{i}.json").write_text(json.dumps(spec["content"]))
            (dump / f"instruction-{i}-values.json").write_text(
                json.dumps(instruction_values(spec, "author-1", datetime.date.today())))
        (dump / WORD_TEMPLATE_FILE).write_bytes(docx)
    return problems


def cmd_check(dump: Path | None) -> None:
    problems = check_demo_content(dump)
    for problem in problems:
        print(f"problem: {problem}", file=sys.stderr)
    if problems:
        sys.exit(1)
    print(f"Demo content consistent: {len(CLASSES)} classes, {len(INSTRUCTIONS)} central instructions and a "
          f"Word template{f'; written to {dump}' if dump else ''}")


def cmd_search_rebuild(api_base: str) -> None:
    """Rebuilds the tenant's search index from the database as the tenant administrator (DMS-51). Idempotent."""
    admin = Api(api_base, user_token(Zitadel(), "admin"))
    result = admin("POST", "/api/v1/admin/search/rebuild")
    print(f"Search index rebuilt: {result['documents']} documents, {result['versions']} versions "
          f"in {result['index']} ({result['tookMillis']} ms)")


def cmd_approve_tasks(username: str, api_base: str) -> None:
    """Approves every open task of a demo user with a fresh sign-in. Stands in for voting in the web UI until it
    exists (DMS-91). Approval votes need a sign-in within dms.workflow.reauthenticationSeconds (step-up); the API
    reads it from the auth_time of the ID token sent in X-DMS-Id-Token, because ZITADEL access tokens have none."""
    tokens = Zitadel().tokens(username, user_password(username))
    api = Api(api_base, tokens["access_token"], tokens.get("id_token"))
    tasks = [task for task in api("GET", "/api/v1/workflow/tasks") if task["status"] == "Open"]
    if not tasks:
        print(f"No open tasks for {username}")
    for task in tasks:
        api("POST", f"/api/v1/workflow/tasks/{task['id']}/votes", {"decision": "Approve"})
        print(f"{username} approved '{task['stepName']}' of document {task['documentId']}")


def main(argv: list[str]) -> None:
    if not argv:
        fail(__doc__ or "")
    command, args = argv[0], argv[1:]
    if command == "tenant":
        cmd_tenant()
    elif command == "users":
        cmd_users()
    elif command == "token" and len(args) == 1:
        cmd_token(args[0])
    elif command == "seed" and len(args) == 2 and args[0] == "--api":
        cmd_seed(args[1])
    elif command == "check" and not args:
        cmd_check(None)
    elif command == "check" and len(args) == 2 and args[0] == "--dump":
        cmd_check(Path(args[1]))
    elif command == "intranet":
        cmd_intranet()
    elif command == "search-rebuild" and len(args) == 2 and args[0] == "--api":
        cmd_search_rebuild(args[1])
    elif command == "approve-tasks" and len(args) == 3 and args[1] == "--api":
        cmd_approve_tasks(args[0], args[2])
    elif command == "env-file" and args:
        set_env_keys(Path(args[0]), dict(a.split("=", 1) for a in args[1:]))
    else:
        fail(f"unknown command: {' '.join(argv)}")


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except HttpError as error:
        fail(str(error))
