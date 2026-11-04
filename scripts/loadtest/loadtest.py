#!/usr/bin/env python3
"""Load test of the DMS platform with a reduced volume model (DMS-60, business concept 14.2).

Runs against a stack of its own (compose project `dms-loadtest`, own ports and volumes) and a backend of its own on
port 18290. Identity is a local stand-in: this script holds an RSA key, serves its public part as JWKS and mints the
tokens of the load users, so neither the development stack nor ZITADEL are involved. See scripts/loadtest/README.md.

Subcommands:
  up        start the services of the load test and migrate the database (needs `sbt app/stage`)
  start     start the key server and the backend in the background
  stop      stop them
  seed      demo content through the API (the seed of scripts/dev_setup.py), then the volume in bulk through SQL
  snapshot  keep a dump of the seeded database in the volume of the load test
  restore   go back to that dump, so runs start from the same data (stop the backend first)
  run       run the virtual users and write the results
  queries   the statements the database spent most time on since the last `run` began
  down      stop the services; `--volumes` also removes the data of the load test

Standard library and the openssl command only. Development only.
"""

from __future__ import annotations

import argparse
import base64
import datetime
import hashlib
import http.client
import http.server
import json
import os
import random
import signal
import statistics
import subprocess
import sys
import threading
import time
import urllib.parse
import uuid
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import dev_setup as ds  # noqa: E402

HERE = Path(__file__).resolve().parent
COMPOSE = ["docker", "compose", "-p", "dms-loadtest", "-f", str(HERE / "compose.yaml")]
STAGE = Path(os.environ.get("DMS_LOADTEST_STAGE", ROOT / "modules" / "app" / "target" / "universal" / "stage")) / "bin"

BACKEND_PORT = 18290
KEYS_PORT = 18295
ISSUER = f"http://localhost:{KEYS_PORT}"
PROJECT = "loadtest-project"
CLIENT = "loadtest-web"
ORGANIZATION = "loadtest-org"
KEY_ID = "loadtest"
API = f"http://localhost:{BACKEND_PORT}"
DB_URL = "jdbc:postgresql://localhost:15433/dms"
DUMP = "/var/lib/postgresql/data/loadtest-seeded.dump"

# Business concept 14.2 (large group example) and the reduction applied here.
CONCEPT = {"tenants": 50, "users": 100_000, "sessions": 5_000, "documents": 2_000_000, "versions": 10_000_000,
           "acknowledgements_peak": 100_000}
DEFAULT_FACTOR = 100

UNITS = ["Legal", "IT", "HR", "Finance", "Sales", "Operations", "Procurement", "Research"]
WORDS = ["Passwort", "Verschlüsselung", "Reise", "Spesen", "Datenschutz", "Aufbewahrung", "Zugriff", "Homeoffice",
         "Lieferant", "Vorfall", "Sicherung", "Mobilgerät", "Cloud", "Schulung", "Interessenkonflikt", "Geschenke",
         "Sanktionen", "Hinweisgeber", "Notebook", "Besucher"]


def state_dir() -> Path:
    path = ROOT / ".local" / "loadtest"
    path.mkdir(parents=True, exist_ok=True)
    return path


def run(cmd: list[str], stdin: str | bytes | None = None, env: dict | None = None) -> str:
    data = stdin.encode() if isinstance(stdin, str) else stdin
    result = subprocess.run(cmd, input=data, capture_output=True, env=env)
    if result.returncode != 0:
        ds.fail(f"{cmd[0]} failed: {result.stderr.decode(errors='replace').strip()[-2000:]}")
    return result.stdout.decode().strip()


def psql(sql: str) -> str:
    """Runs SQL as the schema owner in the database of the load test."""
    return run(COMPOSE + ["exec", "-T", "db", "psql", "-U", "dms", "-d", "dms", "-v", "ON_ERROR_STOP=1", "-qtA"],
               stdin=sql)


def backend_env() -> dict[str, str]:
    return {
        **os.environ,
        "DMS_HTTP_PORT": str(BACKEND_PORT),
        "DMS_DB_URL": DB_URL,
        "DMS_STORAGE_ENDPOINT": "http://localhost:19100",
        "DMS_RENDERING_URL": "http://localhost:19280",
        "DMS_CONVERSION_URL": "http://localhost:19281",
        "DMS_SEARCH_URL": "http://localhost:19201",
        "DMS_WORKER_NAME": "loadtest",
        "DMS_DOCUMENTS_PUBLICATION_CHECK_SECONDS": "5",
        "ZITADEL_ISSUER": ISSUER,
        "DMS_ZITADEL_PROJECT_ID": PROJECT,
        "DMS_ZITADEL_CLIENT_ID": CLIENT,
        "JAVA_OPTS": os.environ.get("DMS_LOADTEST_JAVA_OPTS", "-Xms1g -Xmx3g"),
    }


# ---------------------------------------------------------------------------------------------------------------
# Identity: an RSA key, its JWKS and tokens signed with it


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def key_file() -> Path:
    path = state_dir() / "signing-key.pem"
    if not path.exists():
        run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(path)])
        path.chmod(0o600)
    return path


def jwks() -> dict:
    text = run(["openssl", "rsa", "-in", str(key_file()), "-noout", "-modulus"])
    modulus = bytes.fromhex(text.split("=", 1)[1])
    return {"keys": [{"kty": "RSA", "use": "sig", "alg": "RS256", "kid": KEY_ID, "n": b64url(modulus),
                      "e": b64url((65537).to_bytes(3, "big"))}]}


def sign(claims: dict) -> str:
    header = b64url(json.dumps({"alg": "RS256", "typ": "JWT", "kid": KEY_ID}).encode())
    body = b64url(json.dumps(claims).encode())
    signature = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(key_file())],
                               input=f"{header}.{body}".encode(), capture_output=True, check=True).stdout
    return f"{header}.{body}.{b64url(signature)}"


def mint(user_id: str) -> dict:
    """An access token and the ID token of a fresh sign-in (step-up, DMS-86)."""
    now = int(time.time())
    common = {"iss": ISSUER, "sub": user_id, "iat": now, "exp": now + 12 * 3600}
    return {
        "access_token": sign({**common, "aud": [PROJECT, CLIENT], "urn:zitadel:iam:user:resourceowner:id": ORGANIZATION,
                              "urn:zitadel:iam:org:project:roles": {}}),
        "id_token": sign({**common, "aud": [CLIENT], "auth_time": now}),
    }


class StubZitadel:
    """Stands in for `dev_setup.Zitadel`, so the demo seed runs unchanged against the load test."""

    def tokens(self, username: str, password: str) -> dict:  # noqa: ARG002
        return mint(f"lt-{username}")

    def token(self, username: str, password: str) -> str:
        return self.tokens(username, password)["access_token"]


def cmd_serve_keys() -> None:
    body = json.dumps(jwks()).encode()

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args) -> None:
            pass

    http.server.ThreadingHTTPServer(("127.0.0.1", KEYS_PORT), Handler).serve_forever()


# ---------------------------------------------------------------------------------------------------------------
# up / start / stop / down


def cmd_up() -> None:
    if not (STAGE / "app").exists():
        ds.fail(f"missing {STAGE / 'app'}; run `sbt app/stage` first")
    subprocess.run(COMPOSE + ["up", "-d", "--wait", "--wait-timeout", "180", "db", "objectstore", "opensearch",
                              "rendering", "conversion"], check=True)
    subprocess.run(COMPOSE + ["up", "objectstore-init"], check=True, stdout=subprocess.DEVNULL)
    migrate()


def migrate() -> None:
    print(run([str(STAGE / "migrate")], env=backend_env())[-300:])


def start_process(name: str, cmd: list[str], env: dict | None = None) -> subprocess.Popen:
    log = (state_dir() / f"{name}.log").open("ab")
    process = subprocess.Popen(cmd, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    (state_dir() / f"{name}.pid").write_text(str(process.pid))
    return process


def cmd_start() -> None:
    start_process("keys", [sys.executable, str(Path(__file__).resolve()), "serve-keys"])
    process = start_process("backend", [str(STAGE / "app")], backend_env())
    for _ in range(180):
        try:
            conn = http.client.HTTPConnection("localhost", BACKEND_PORT, timeout=2)
            conn.request("GET", "/health")
            if conn.getresponse().status == 200:
                print(f"Backend {process.pid} answers on port {BACKEND_PORT}")
                return
        except OSError:
            pass
        if process.poll() is not None:
            ds.fail(f"backend exited; see {state_dir() / 'backend.log'}")
        time.sleep(1)
    ds.fail("backend did not answer within 180 s")


def cmd_stop() -> None:
    for name in ("backend", "keys"):
        pid_file = state_dir() / f"{name}.pid"
        if pid_file.exists():
            try:
                os.killpg(int(pid_file.read_text()), signal.SIGTERM)
                print(f"{name} stopped")
            except ProcessLookupError:
                print(f"{name} was not running")
            pid_file.unlink()


def cmd_down(volumes: bool) -> None:
    cmd_stop()
    subprocess.run(COMPOSE + ["down"] + (["--volumes"] if volumes else []), check=True)


# ---------------------------------------------------------------------------------------------------------------
# Seed


def active_users(factor: int) -> dict[str, list[str]]:
    """The load users by id with their roles: the volume model's users, all of them able to read."""
    users = {f"lt-reader-{i:05d}": ["Reader"] for i in range(CONCEPT["users"] // factor)}
    users |= {f"lt-writer-{i:03d}": ["Author", "Owner"] for i in range(40)}
    users |= {f"lt-signer-{i:03d}": ["Approver"] for i in range(10)}
    return users


def demo_users() -> None:
    """The demo users of dev_setup with fixed ids, as if `dev_setup.py users` had created them."""
    env = state_dir() / "users.env"
    ds.USERS_ENV = env
    ds.Zitadel = StubZitadel
    env.touch(mode=0o600)
    ds.set_env_keys(env, {k: v for name in ds.USERS for k, v in
                          {ds.password_key(name): "unused", ds.user_id_key(name): f"lt-{name}"}.items()}, mode=0o600)


def seed_tenant(factor: int) -> str:
    lit = ds.sql_literal
    tenant = psql(f"""
insert into dms.tenant (id, name, zitadel_org_id, time_zone)
select gen_random_uuid(), 'Load test', {lit(ORGANIZATION)}, 'Europe/Berlin'
where not exists (select 1 from dms.tenant where zitadel_org_id = {lit(ORGANIZATION)});
select id from dms.tenant where zitadel_org_id = {lit(ORGANIZATION)};""").splitlines()[-1]
    grants = [(f"lt-{name}", role) for name, (_, _, roles) in ds.USERS.items() for role in roles]
    grants += [(user, role) for user, roles in active_users(factor).items() for role in roles]
    values = ",".join(f"({lit(tenant)}::uuid, {lit(u)}, {lit(r)}, 'loadtest')" for u, r in grants)
    psql(f"""
insert into dms.role_assignment (tenant_id, user_id, role, granted_by)
select g.* from (values {values}) as g(tenant_id, user_id, role, granted_by)
where not exists (select 1 from dms.role_assignment a where a.tenant_id = g.tenant_id and a.user_id = g.user_id
  and a.role = g.role and a.organizational_unit is null and a.document_class is null);""")
    print(f"Tenant {tenant} with {len(grants)} role assignments")
    return tenant


def columns(table: str) -> list[str]:
    return psql(f"""select column_name from information_schema.columns
                    where table_schema = 'dms' and table_name = '{table}' and is_generated = 'NEVER'
                    order by ordinal_position""").splitlines()


def clone(table: str, source: str, overrides: dict[str, str]) -> str:
    """Insert copying every column of `table` from alias `b` of `source`, except the overridden ones: stays right
    when migrations add columns."""
    names = columns(table)
    select = ", ".join(overrides.get(name, f"b.{name}") for name in names)
    return f"insert into dms.{table} ({', '.join(names)})\nselect {select}\n{source};\n"


def bulk_sql(tenant: str, documents: int, versions_per_document: int, users: int) -> str:
    """The volume as copies of the published demo instructions and their workflow history, written with triggers and
    constraints switched off (session_replication_role): a bulk load, not the application's write path."""
    units = "array[" + ",".join(ds.sql_literal(u) for u in UNITS) + "]"
    words = "array[" + ",".join(ds.sql_literal(w) for w in WORDS) + "]"
    sql = f"""
set session_replication_role = replica;
set synchronous_commit = off;
create schema if not exists load;
drop table if exists load.base, load.doc, load.ver;

-- The published demo instructions: their rendition exists, so the copies can be read as PDF.
create table load.base as
select row_number() over (order by v.id) - 1 as n, v.id as version_id, v.document_id, c.id as case_id
from dms.document_version v
join dms.workflow_case c on c.version_id = v.id
where v.status = 'Published' and v.rendition_key is not null;

create table load.doc as
select i, gen_random_uuid() as id,
       (select b from load.base b where b.n = i % (select count(*) from load.base)) as base,
       'Richtlinie ' || lpad(i::text, 6, '0') || ': ' || ({words})[1 + i % 20] || ' und ' ||
           ({words})[1 + (i * 7 + 3) % 20] as title,
       ({units})[1 + i % 8] as unit,
       'lt-writer-' || lpad((i % 40)::text, 3, '0') as author,
       -- 5 % of the documents ask their readers to acknowledge the valid version, like policies (14.2).
       i % 20 = 3 as acknowledged,
       -- 85 %: last version valid; 10 %: valid and a newer draft; 5 %: never released.
       case when i % 20 = 0 then 'draft-only' when i % 10 = 1 then 'open' else 'valid' end as shape,
       -- Old enough that the versions, 30 days apart, all lie in the past.
       now() - (200 + i % 700) * interval '1 day' as created
from generate_series(1, {documents}) as i;

create table load.ver as
select d.id as document_id, gen_random_uuid() as id, (d.base).version_id as base_version, (d.base).case_id as base_case,
       j as major, d.unit, d.title, d.author, d.created + (j - 1) * interval '30 days' as created, d.acknowledged, d.i,
       case
         when d.shape = 'draft-only' then 'Draft'
         when j < {versions_per_document} then 'Superseded'
         else 'Published'
       end as status
from load.doc d
cross join lateral generate_series(1, case when d.shape = 'draft-only' then 1 else {versions_per_document} end) as j;

insert into load.ver
select d.id, gen_random_uuid(), (d.base).version_id, (d.base).case_id, {versions_per_document} + 1, d.unit, d.title, d.author,
       now() - (d.i % 30) * interval '1 day', d.acknowledged, d.i, 'Draft'
from load.doc d where d.shape = 'open';

create index on load.ver (document_id, major);
create index on load.ver (id);
analyze load.ver;
"""
    sql += clone("document", "from load.doc x join dms.document b on b.id = (x.base).document_id", {
        "id": "x.id", "title": "x.title", "author_id": "x.author", "owner_id": "x.author", "created_at": "x.created",
        "updated_at": "x.created", "document_number": "'LT-' || lpad(x.i::text, 7, '0')"})
    sql += clone("document_version", "from load.ver x join dms.document_version b on b.id = x.base_version", {
        "id": "x.id", "document_id": "x.document_id", "major": "x.major", "minor": "0", "status": "x.status",
        "created_at": "x.created", "updated_at": "x.created", "status_changed_at": "x.created", "created_by": "x.author",
        "status_changed_by": "x.author",
        "field_values": """jsonb_set(jsonb_set(b.field_values, '{organizationalUnit,value,value}', to_jsonb(x.unit)),
                                     '{owner,value,userId}', to_jsonb(x.author))""",
        "metadata_snapshot": """case when x.status = 'Draft' then null else
                jsonb_set(jsonb_set(b.metadata_snapshot, '{values,organizationalUnit,value}', to_jsonb(x.unit)),
                          '{values,title,value}', to_jsonb(x.title)) end""",
        "rendition_key": "case when x.status = 'Draft' then null else b.rendition_key end",
        "rendition_version": "case when x.status = 'Draft' then null else b.rendition_version end",
        "rendition_sha256": "case when x.status = 'Draft' then null else b.rendition_sha256 end",
        "released_at": "case when x.status = 'Draft' then null else x.created end",
        "released_by": "case when x.status = 'Draft' then null else 'lt-signer-000' end",
        "published_at": "case when x.status = 'Draft' then null else x.created end",
        "superseded_by": """(select n.id from load.ver n where n.document_id = x.document_id and n.major = x.major + 1
                               and n.status <> 'Draft' and x.status = 'Superseded')""",
        "change_reason": "'Regelmäßige Aktualisierung ' || x.major",
        "requires_acknowledgement": "x.acknowledged",
    })
    case = "from load.ver x join dms.workflow_case b on b.id = x.base_case where x.status <> 'Draft'"
    sql += clone("workflow_case", case, {
        "id": "gen_random_uuid()", "document_id": "x.document_id", "version_id": "x.id", "author_id": "x.author",
        "started_by": "x.author", "started_at": "x.created", "updated_at": "x.created"})
    task = """from dms.workflow_case c join load.ver x on x.id = c.version_id
              join load.base base on base.case_id = x.base_case
              join dms.workflow_task b on b.case_id = base.case_id"""
    sql += clone("workflow_task", task, {
        "id": "'lt-' || c.id::text || '-' || b.id", "case_id": "c.id", "created_at": "x.created",
        "completed_at": "x.created", "completed_by": "'lt-signer-000'"})
    vote = """from dms.workflow_case c join load.ver x on x.id = c.version_id
              join load.base base on base.case_id = x.base_case
              join dms.workflow_vote b on b.case_id = base.case_id"""
    sql += clone("workflow_vote", vote, {
        "id": "gen_random_uuid()", "case_id": "c.id", "task_id": "'lt-' || c.id::text || '-' || b.task_id",
        "version_id": "x.id", "voted_at": "x.created", "voter": "'lt-signer-000'"})
    sql += clone("workflow_approval", vote.replace("workflow_vote b", "workflow_approval b"), {
        "id": "gen_random_uuid()", "case_id": "c.id", "version_id": "x.id"})
    # Acknowledgements: every reader has confirmed the valid versions, except 30 of them (a publication in progress).
    sql += f"""
insert into dms.document_acknowledgement
  (id, tenant_id, document_id, version_id, version_label, user_id, opened_at, acknowledged_at)
select gen_random_uuid(), '{tenant}', x.document_id, x.id, x.major || '.0',
       'lt-reader-' || lpad(r::text, 5, '0'), x.created, x.created + interval '1 hour'
from load.ver x cross join generate_series(0, {users - 1}) as r
where x.status = 'Published' and x.acknowledged and x.i > 600;

drop schema load cascade;
set session_replication_role = origin;
analyze;
"""
    return sql


def cmd_seed(args: argparse.Namespace) -> None:
    factor = args.factor
    documents = args.documents or CONCEPT["documents"] // factor
    users = CONCEPT["users"] // factor
    versions = CONCEPT["versions"] // CONCEPT["documents"]
    tenant = seed_tenant(factor)
    demo_users()
    if psql("select count(*) from dms.document") == "0" or args.demo:
        ds.cmd_seed(API)
    have = int(psql("select count(*) from dms.document"))
    if have >= documents:
        print(f"{have} documents exist")
    else:
        began = time.time()
        psql(bulk_sql(tenant, documents, versions, users))
        print(f"Bulk load took {time.time() - began:.0f} s")
    if args.reindex:
        began = time.time()
        admin = ds.Api(API, mint("lt-admin")["access_token"])
        result = rebuild(admin)
        print(f"Search index: {result} after {time.time() - began:.0f} s")
    print(volume())


def db_exec(*cmd: str) -> None:
    subprocess.run(COMPOSE + ["exec", "-T", "db", *cmd], check=True)


def cmd_snapshot() -> None:
    db_exec("pg_dump", "-U", "dms", "-Fc", "dms", "-f", DUMP)
    print(f"Database kept in {DUMP}")


def cmd_restore() -> None:
    db_exec("psql", "-U", "dms", "-d", "postgres", "-qc", "drop database if exists dms with (force)")
    db_exec("psql", "-U", "dms", "-d", "postgres", "-qc", "create database dms owner dms")
    db_exec("pg_restore", "-U", "dms", "-d", "dms", DUMP)
    psql("create extension if not exists pg_stat_statements; analyze;")
    print("Database restored")


def rebuild(admin: ds.Api) -> dict:
    conn = http.client.HTTPConnection("localhost", BACKEND_PORT, timeout=3600)
    conn.request("POST", "/api/v1/admin/search/rebuild", headers={"Authorization": f"Bearer {admin.token}"})
    response = conn.getresponse()
    return json.loads(response.read())


def volume() -> dict:
    out = psql("""select json_build_object(
      'documents', (select count(*) from dms.document),
      'versions', (select count(*) from dms.document_version),
      'validVersions', (select count(*) from dms.document_version where status in ('Published', 'ReviewDue')),
      'workflowCases', (select count(*) from dms.workflow_case),
      'workflowTasks', (select count(*) from dms.workflow_task),
      'acknowledgements', (select count(*) from dms.document_acknowledgement),
      'roleAssignments', (select count(*) from dms.role_assignment))""")
    return json.loads(out)


# ---------------------------------------------------------------------------------------------------------------
# HTTP client with keep-alive and metrics


class Metrics:
    def __init__(self, warm_up_until: float = 0) -> None:
        self.warm_up_until = warm_up_until
        self.lock = threading.Lock()
        self.samples: dict[str, list[float]] = defaultdict(list)
        self.statuses: dict[str, dict[int, int]] = defaultdict(lambda: defaultdict(int))
        self.failures: dict[str, list[str]] = defaultdict(list)

    def record(self, name: str, seconds: float, status: int, ok: bool, body: str | None = None) -> None:
        if time.time() < self.warm_up_until:
            return
        with self.lock:
            self.samples[name].append(seconds)
            self.statuses[name][status if ok else -status] += 1
            if not ok and len(self.failures[name]) < 5:
                self.failures[name].append(f"{status}: {(body or '')[:200]}")


class Client:
    """One keep-alive connection per virtual user, like a browser tab."""

    def __init__(self, metrics: Metrics, tokens: dict):
        self.metrics = metrics
        self.tokens = tokens
        self.conn: http.client.HTTPConnection | None = None

    def call(self, name: str, method: str, path: str, body=None, raw: tuple[bytes, str] | None = None,
             expect=(200, 201, 204), step_up: bool = False):
        headers = {"Authorization": f"Bearer {self.tokens['access_token']}"}
        if step_up:
            headers[ds.ID_TOKEN_HEADER] = self.tokens["id_token"]
        data = None
        if raw is not None:
            data, headers["Content-Type"] = raw
        elif body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        for attempt in range(2):
            if self.conn is None:
                self.conn = http.client.HTTPConnection("localhost", BACKEND_PORT, timeout=120)
            start = time.perf_counter()
            try:
                self.conn.request(method, path, body=data, headers=headers)
                response = self.conn.getresponse()
                payload = response.read()
            except (http.client.HTTPException, OSError) as error:
                self.conn.close()
                self.conn = None
                if attempt == 1:
                    self.metrics.record(name, time.perf_counter() - start, 599, False, repr(error))
                    return 599, None
                continue
            elapsed = time.perf_counter() - start
            is_json = response.getheader("Content-Type", "").startswith("application/json")
            ok = response.status in expect
            text = payload.decode(errors="replace") if is_json or not ok else ""
            self.metrics.record(name, elapsed, response.status, ok, text)
            return response.status, json.loads(text) if ok and is_json and text else None
        return 599, None


# ---------------------------------------------------------------------------------------------------------------
# Virtual users


class Run:
    def __init__(self, duration: float, think: float, reader_users: list[str], publication_interval: float,
                 warm_up: float):
        self.metrics = Metrics(time.time() + warm_up)
        self.publication_interval = publication_interval
        self.next_publication = 0.0
        self.stop_at = time.time() + warm_up + duration
        self.think = think
        self.reader_users = reader_users
        admin = ds.Api(API, mint("lt-designer")["access_token"])
        latest = {v["name"]: v for v in admin("GET", "/api/v1/admin/templates")}
        self.instruction = latest[ds.CENTRAL_INSTRUCTION["name"]]
        self.word = latest[ds.WORD_TEMPLATE_NAME]
        self.docx = ds.word_template_docx()
        self.pending_render: list[tuple[str, str, float]] = []
        self.lock = threading.Lock()

    def may_publish(self) -> bool:
        """Approvals are paced: each one publishes to every reader, which happens a few times a day (14.2 names one
        policy in 48 hours as the peak), not every few seconds. 0: as fast as the approvers can vote."""
        if not self.publication_interval:
            return True
        with self.lock:
            if time.time() < self.next_publication:
                return False
            self.next_publication = time.time() + self.publication_interval
            return True

    def pause(self, rnd: random.Random) -> None:
        time.sleep(rnd.uniform(0.5, 1.5) * self.think)

    def active(self) -> bool:
        return time.time() < self.stop_at

    def reader(self, user: str, seed: int) -> None:
        """Reading: library, list, search, a document with its PDF, and the acknowledgements that are due."""
        rnd = random.Random(seed)
        c = Client(self.metrics, mint(user))
        while self.active():
            _, library = c.call("library", "GET", "/api/v1/library?size=50")
            self.pause(rnd)
            unit = rnd.choice(UNITS)
            c.call("library.filtered", "GET", f"/api/v1/library?unit={unit}&sort=title&size=50")
            self.pause(rnd)
            c.call("documents.list", "GET", "/api/v1/documents?limit=50")
            self.pause(rnd)
            term = urllib.parse.quote(rnd.choice(WORDS))
            _, result = c.call("search", "GET", f"/api/v1/search?q={term}&size=20")
            self.pause(rnd)
            hits = (result or {}).get("hits") or (library or {}).get("items") or []
            if hits:
                hit = rnd.choice(hits)
                doc, version = hit["documentId"], hit["versionId"]
                c.call("document.view", "GET", f"/api/v1/documents/{doc}")
                c.call("document.html", "GET", f"/api/v1/documents/{doc}/versions/{version}/html")
                self.pause(rnd)
                c.call("pdf.rendition", "GET", f"/api/v1/documents/{doc}/versions/{version}/rendition")
                self.pause(rnd)
            _, due = c.call("acknowledgements.open", "GET", "/api/v1/acknowledgements?status=open")
            if due:
                item = rnd.choice(due)
                base = f"/api/v1/documents/{item['documentId']}/versions/{item['versionId']}"
                c.call("acknowledgement.opened", "POST", f"{base}/opened")
                c.call("acknowledgement.confirm", "POST", f"{base}/acknowledgement")
            self.pause(rnd)

    def author(self, user: str, seed: int) -> None:
        """Writing: a structured draft saved a few times with an attachment and submitted; a Word document uploaded
        and previewed as PDF."""
        rnd = random.Random(seed)
        c = Client(self.metrics, mint(user))
        spec = ds.INSTRUCTIONS[seed % len(ds.INSTRUCTIONS)]
        word_spec = ds.INSTRUCTIONS[(seed + 1) % len(ds.INSTRUCTIONS)]
        today = datetime.date.today()
        preview = f"/api/v1/templates/{self.instruction['templateId']}/versions/{self.instruction['version']}/preview"
        round_no = 0
        while self.active():
            round_no += 1
            values = {**ds.instruction_values(spec, user, today)}
            title = f"Lastlauf {user} {round_no}"
            _, doc = c.call("draft.create", "POST", "/api/v1/documents", {
                "templateId": self.instruction["templateId"], "templateVersion": self.instruction["version"],
                "title": title, "values": values})
            self.pause(rnd)
            if not doc:
                continue
            for _ in range(3):
                c.call("draft.preview", "POST", preview, {"values": values})
                c.call("draft.save", "PUT", f"/api/v1/documents/{doc['id']}/draft",
                       {"title": title, "values": values, "content": spec["content"]})
                self.pause(rnd)
            c.call("pdf.draft", "GET", f"/api/v1/documents/{doc['id']}/versions/{doc['current']['id']}/draft.pdf")
            self.pause(rnd)
            attachment = ds.multipart_file("file", f"anlage-{round_no}.txt", "text/plain",
                                           os.urandom(100_000).hex().encode())
            c.call("upload.attachment", "POST", f"/api/v1/documents/{doc['id']}/attachments", raw=attachment)
            self.pause(rnd)
            c.call("document.submit", "POST", f"/api/v1/documents/{doc['id']}/submit")
            self.pause(rnd)
            _, word = c.call("word.create", "POST", "/api/v1/documents/word", {
                "templateId": self.word["templateId"], "templateVersion": self.word["version"],
                "title": f"Lastlauf Word {user} {round_no}",
                "values": ds.instruction_values(word_spec, user, today)})
            if word:
                c.call("upload.word", "PUT", f"/api/v1/documents/{word['id']}/file",
                       raw=ds.multipart_file("file", f"anweisung-{round_no}.docx", ds.DOCX_TYPE, self.docx))
                self.pause(rnd)
                version = word["current"]["id"]
                c.call("pdf.preview", "GET", f"/api/v1/documents/{word['id']}/versions/{version}/preview.pdf")
            self.pause(rnd)

    def approver(self, user: str, seed: int) -> None:
        """Approving: the inbox and votes with a fresh sign-in (step-up)."""
        rnd = random.Random(seed)
        while self.active():
            c = Client(self.metrics, mint(user))
            refresh = time.time() + 200  # step-up window: 300 s
            while self.active() and time.time() < refresh:
                _, tasks = c.call("workflow.inbox", "GET", "/api/v1/workflow/tasks")
                for task in [t for t in tasks or [] if t["status"] == "Open"][:3]:
                    if not self.may_publish():
                        break
                    # 403, 409: the other approver was faster, the expected end of a race for the same task.
                    status, _ = c.call("workflow.approve", "POST", f"/api/v1/workflow/tasks/{task['id']}/votes",
                                       {"decision": "Approve"}, step_up=True, expect=(200, 201, 204, 403, 409))
                    if status in (200, 201):
                        with self.lock:
                            self.pending_render.append((task["documentId"], task["versionId"], time.time()))
                    self.pause(rnd)
                self.pause(rnd)

    def publication_watch(self) -> None:
        """Asynchronous part of an approval: from the vote until the version has its PDF/A and is published."""
        deadline = self.stop_at + 60
        while time.time() < deadline and (self.active() or self.pending_render):
            with self.lock:
                pending = list(self.pending_render)
            if pending:
                ids = ",".join(f"'{version}'" for _, version, _ in pending)
                rows = psql(f"select id, status from dms.document_version where id in ({ids})").splitlines()
                statuses = dict(row.split("|") for row in rows)
                for entry in pending:
                    _, version, voted = entry
                    done = statuses.get(version) in ("Published", "ReviewDue")
                    if done or time.time() - voted > 120:
                        self.metrics.record("publication.latency", time.time() - voted, 200 if done else 504, done,
                                            None if done else statuses.get(version))
                        with self.lock:
                            self.pending_render.remove(entry)
            time.sleep(1)


def percentile(values: list[float], p: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, min(len(ordered) - 1, round(p / 100 * len(ordered) + 0.5) - 1))]


def report(metrics: Metrics, duration: float) -> dict:
    rows = {}
    for name in sorted(metrics.samples):
        values = metrics.samples[name]
        errors = sum(n for status, n in metrics.statuses[name].items() if status < 0)
        rows[name] = {
            "count": len(values), "errors": errors, "rps": round(len(values) / duration, 2),
            "p50_ms": round(percentile(values, 50) * 1000), "p95_ms": round(percentile(values, 95) * 1000),
            "p99_ms": round(percentile(values, 99) * 1000), "max_ms": round(max(values) * 1000),
            "mean_ms": round(statistics.fmean(values) * 1000),
            "statuses": {str(k): v for k, v in metrics.statuses[name].items()},
            "sample_failures": metrics.failures.get(name, []),
        }
    return rows


def table(rows: dict) -> str:
    lines = ["| Operation | Count | Errors | Req/s | p50 ms | p95 ms | p99 ms | max ms |", "|---|---:|---:|---:|---:|---:|---:|---:|"]
    for name, r in rows.items():
        lines.append(f"| {name} | {r['count']} | {r['errors']} | {r['rps']} | {r['p50_ms']} | {r['p95_ms']} | "
                     f"{r['p99_ms']} | {r['max_ms']} |")
    return "\n".join(lines)


def cmd_run(args: argparse.Namespace) -> None:
    factor = args.factor
    sessions = args.sessions or CONCEPT["sessions"] // factor
    authors = args.authors if args.authors is not None else max(1, sessions * 16 // 100)
    approvers = args.approvers if args.approvers is not None else max(1, sessions * 4 // 100)
    readers = sessions - authors - approvers
    users = active_users(factor)
    rnd = random.Random(60)
    reader_users = rnd.sample([u for u in users if u.startswith("lt-reader")], readers)
    psql("create extension if not exists pg_stat_statements; select pg_stat_statements_reset();")
    before = volume()
    r = Run(args.duration, args.think, reader_users, args.publication_interval, args.warm_up)
    threads = [threading.Thread(target=r.reader, args=(u, i)) for i, u in enumerate(reader_users)]
    threads += [threading.Thread(target=r.author, args=(f"lt-writer-{i:03d}", 1000 + i)) for i in range(authors)]
    threads += [threading.Thread(target=r.approver, args=(f"lt-signer-{i:03d}", 2000 + i)) for i in range(approvers)]
    watcher = threading.Thread(target=r.publication_watch)
    print(f"{sessions} virtual users ({readers} readers, {authors} authors, {approvers} approvers), think time "
          f"{args.think} s, for {args.duration:.0f} s; volume {before}", flush=True)
    began = time.time()
    for t in threads:
        t.start()
        time.sleep(args.ramp / max(1, len(threads)))
    watcher.start()
    for t in threads:
        t.join()
    elapsed = time.time() - began - args.warm_up
    watcher.join()
    rows = report(r.metrics, elapsed)
    requests = sum(row["count"] for name, row in rows.items() if name != "publication.latency")
    errors = sum(row["errors"] for row in rows.values())
    result = {
        "label": args.label, "started": datetime.datetime.fromtimestamp(began).isoformat(timespec="seconds"),
        "factor": factor, "duration_s": round(elapsed), "sessions": sessions, "think_s": args.think,
        "requests": requests, "errors": errors, "throughput_rps": round(requests / elapsed, 1),
        "volume_before": before, "volume_after": volume(), "operations": rows,
    }
    print(f"\n{requests} requests in {elapsed:.0f} s ({requests / elapsed:.1f}/s), {errors} errors\n")
    print(table(rows))
    for name, row in rows.items():
        for failure in row["sample_failures"]:
            print(f"  {name}: {failure}")
    out = HERE / "results" / f"{args.label}.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    print(f"\nResults written to {out}")


def cmd_queries(limit: int) -> None:
    print(psql(f"""select round(total_exec_time)::bigint || ' ms | ' || calls || ' calls | ' ||
                          round(mean_exec_time::numeric, 2) || ' ms mean | ' || rows || ' rows | ' ||
                          left(regexp_replace(query, '\\s+', ' ', 'g'), 180)
                   from pg_stat_statements order by total_exec_time desc limit {limit}"""))


def cmd_tokens(user: str) -> None:
    print(mint(user)["access_token"])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=["up", "start", "stop", "seed", "snapshot", "restore", "run", "queries",
                                            "down", "serve-keys", "migrate", "token"])
    parser.add_argument("--factor", type=int, default=DEFAULT_FACTOR, help="volume of 14.2 divided by this")
    parser.add_argument("--documents", type=int, help="seed: documents instead of the factor's")
    parser.add_argument("--demo", action="store_true", help="seed: run the demo seed even if documents exist")
    parser.add_argument("--reindex", action="store_true", help="seed: rebuild the search index afterwards")
    parser.add_argument("--duration", type=float, default=180, help="run: seconds")
    parser.add_argument("--ramp", type=float, default=20, help="run: seconds until all virtual users are started")
    parser.add_argument("--warm-up", type=float, default=60,
                        help="run: seconds at the start that are not measured (the JVM compiles the hot paths)")
    parser.add_argument("--think", type=float, default=1.0, help="run: mean think time between steps in s")
    parser.add_argument("--publication-interval", type=float, default=30,
                        help="run: seconds between approvals, each publishing to all readers; 0: no limit (stress)")
    parser.add_argument("--sessions", type=int, help="run: virtual users instead of the factor's")
    parser.add_argument("--authors", type=int, help="run: authors among them (default 16 %%)")
    parser.add_argument("--approvers", type=int, help="run: approvers among them (default 4 %%)")
    parser.add_argument("--label", default="run", help="run: name of the result file in scripts/loadtest/results/")
    parser.add_argument("--limit", type=int, default=25, help="queries: statements to show")
    parser.add_argument("--volumes", action="store_true", help="down: also remove the data")
    parser.add_argument("--user", default="lt-admin", help="token: user id")
    args = parser.parse_args()
    try:
        match args.command:
            case "up": cmd_up()
            case "migrate": migrate()
            case "start": cmd_start()
            case "stop": cmd_stop()
            case "seed": cmd_seed(args)
            case "snapshot": cmd_snapshot()
            case "restore": cmd_restore()
            case "run": cmd_run(args)
            case "queries": cmd_queries(args.limit)
            case "down": cmd_down(args.volumes)
            case "serve-keys": cmd_serve_keys()
            case "token": cmd_tokens(args.user)
    except ds.HttpError as e:
        ds.fail(str(e))


if __name__ == "__main__":
    main()
