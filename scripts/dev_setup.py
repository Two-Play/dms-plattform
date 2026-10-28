#!/usr/bin/env python3
"""Helper of scripts/dev-setup.sh: tenant, demo users, tokens and demo content for local development.

Subcommands:
  tenant          ensure the tenant of the local ZITADEL organization (SQL as schema owner, idempotent)
  users           ensure the demo users in ZITADEL and their role assignments (idempotent)
  token USER      print an access token for a demo user (e.g. `author`)
  seed --api URL  create demo field definitions, classes, workflows, templates, an access group and a draft
                  through the API
  approve-tasks USER --api URL  approve all open workflow tasks of a demo user (until the UI can vote, DMS-91)
  search-rebuild --api URL      rebuild the tenant's search index from the database (DMS-51, idempotent)
  env-file PATH KEY=VALUE...   set keys in a dotenv file, keeping all other lines

Development only. Uses the standard library only. Passwords are kept in .local/dev-users.env (mode 600) and are
never printed.
"""

from __future__ import annotations

import base64
import datetime
import hashlib
import json
import re
import secrets
import string
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
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


def http(method: str, url: str, body=None, token: str | None = None, headers=None, form=None):
    data = None
    hdrs = dict(headers or {})
    if token:
        hdrs["Authorization"] = f"Bearer {token}"
    if form is not None:
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
]

# key -> (label, description, [(field, required from review)])
CLASSES = {
    "contract": ("Contract", "Contracts with business partners", [
        ("partnerId", False), ("partnerName", True), ("partnerAddress", False), ("monthlyFee", True),
        ("termMonths", False), ("termType", True), ("startDate", True), ("endDate", False),
        ("noticePeriod", False), ("services", False), ("slaLevel", False)]),
    "policy": ("Policy", "Internal policies and guidelines", [("policyArea", True), ("appliesTo", False)]),
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
        contract = ensure_template(designer, designer2, SERVICE_CONTRACT, classes["contract"])
        ensure_template(designer, designer2, POLICY, classes["policy"])
        if contract["status"] == "Released":
            ensure_sample_draft(author, users[user_id_key("author")], contract)
    except HttpError as e:
        fail(str(e))
    print("Demo content ready.")


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
