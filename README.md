# DMS Platform

Document and template management for contracts, policies, guidelines, handbooks and forms: template-based authoring, approval workflows, versioning, PDF/A renditions and audit-proof archiving, multi-tenant.

| Document | Content |
|---|---|
| [docs/fachkonzept.html](docs/fachkonzept.html) | Business concept, in German (chapters 1–17 technology-neutral, chapter 18 target architecture) |
| [docs/adr/](docs/adr/README.md) | Architecture decisions with alternatives, consequences and reversibility |
| [docs/backlog.md](docs/backlog.md) | Backlog, planning assumptions and sprint plan |
| [docs/jira-mapping.md](docs/jira-mapping.md) | Mapping of planning IDs to issues in Jira project DMS |

## Stack

Scala 3.9 LTS · ZIO 2 · Tapir on ZIO HTTP · sbt 1.12 · Java 21 (Temurin) · PostgreSQL · Flyway · ZITADEL · Flowable · Python workers for WeasyPrint and veraPDF · TypeScript/React frontend with Tailwind and shadcn/ui. Rationale in the ADRs.

## Layout

```
modules/
  kernel/                 shared types (identifiers, version number)
  db/                     transactions, tenant context, migrations (ADR 0012, 0014)
  storage/                object storage with Object Lock on the S3 API (ADR 0009, 0016)
  identity/  authz/       sign-in via ZITADEL, authorization
  <module>/api  <module>/core   templates, documents, workflow, rendering,
                                archive, search, audit, notifications
  integration/            outbox, job runner, business system adapters (partner master);
  integration/api         ports to business systems (PartnerDirectory)
  app/                    wiring, HTTP endpoints, migrations
workers/                  Python workers (rendering, OCR)
frontend/                 web UI
spikes/                   time-boxed technical experiments, not production code
```

Rule: modules depend only on `kernel`, `db` and `storage` (core modules only) and on `*-api`, never on another module's `*-core`. Only `app` knows the core modules. The build enforces this.

## Development

Provide Java 21 (once per shell):

```bash
eval "$(cs java --jvm temurin:21 --env)"
```

Build and test (the integration tests of `db` and `storage` start PostgreSQL and SeaweedFS with Testcontainers and need Docker):

```bash
sbt test
```

With Docker Desktop on Linux, Testcontainers needs the socket location:

```bash
export DOCKER_HOST=unix://$HOME/.docker/desktop/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

### Local environment in one command

Requires Docker, `cs` (Coursier) or Java 21, sbt, Node 24 with npm, curl and python3.

```bash
scripts/dev-setup.sh
```

The script is idempotent and never deletes data (except `--reset`). It

1. starts the services (`docker compose up -d --build`: PostgreSQL on 5433, ZITADEL on `http://localhost:8085`, SeaweedFS on 9100, rendering worker on 9180) and waits for them,
2. creates the ZITADEL project and OIDC application (`scripts/zitadel-setup.sh`, writes `.local/zitadel/dms.env`),
3. builds the backend (`sbt app/stage`) and migrates the database (`dms.app.Migrate`),
4. registers the ZITADEL organization as tenant (`dms.tenant.zitadel_org_id`, time zone Europe/Berlin),
5. creates the demo users in ZITADEL and their role assignments,
6. seeds demo content through the API as the demo users (so the audit trail stays a valid hash chain),
7. writes `frontend/.env.local` and starts backend and frontend dev server in the background (logs and process ids in `.local/logs/`).

Then sign in at `http://localhost:5173`. Stop the background processes with `scripts/dev-stop.sh` (the docker services keep running; `docker compose stop` stops them).

| Option | Effect |
|---|---|
| `--seed-only` | only tenant, demo users and content (services, schema and ZITADEL setup must exist; starts the backend if it is not running) |
| `--no-start` | set everything up, leave backend and frontend stopped and print the commands to start them |
| `--no-compose` | do not run `docker compose up` (services are managed elsewhere) |
| `--reset` | after typing `reset` at the prompt: stop the services, delete the volumes of PostgreSQL and ZITADEL, `.local/zitadel` and `.local/dev-users.env`, then set up from scratch |

The backend listens on port 8080; set `DMS_HTTP_PORT` if that port is taken (`DMS_HTTP_PORT=8091 scripts/dev-setup.sh`), the script points the frontend's dev proxy (`DMS_BACKEND_URL`) at it. A backend that already answers on the port is reused. The frontend must run on port 5173, the redirect URI registered in ZITADEL; if the port is taken, the script prints the command instead.

`scripts/dev_setup.py token <user>` prints an access token of a demo user for API calls:

```bash
curl -H "Authorization: Bearer $(scripts/dev_setup.py token author)" localhost:8080/api/v1/me
```

The token comes from a ZITADEL session checked with the user's password that finalizes an OIDC authorization request; the setup machine user `dms-setup` acts as login client (session API v2, header `x-zitadel-login-client`). ZITADEL has no password grant.

#### Demo users and data

All users belong to the local ZITADEL organization. Passwords are generated on the first run and stored only in `.local/dev-users.env` (not committed, mode 600).

| Username | Roles |
|---|---|
| `admin` | TenantAdmin |
| `designer` | TemplateDesigner |
| `designer2` | TemplateDesigner (second person for the four-eyes release of templates) |
| `author` | Author, Owner |
| `reviewer` | Reviewer |
| `approver` | Approver |
| `reader` | Reader |
| `records` | RecordsManager, Auditor |

The ZITADEL default admin `zitadel-admin@zitadel.localhost` (password `Password1!`, change on first login; console at `http://localhost:8085/ui/console`) gets TenantAdmin.

Demo content:

- Field definitions and the document classes `contract` (partner number, name and address, monthly fee, term, start and end date, notice period, services, service level) and `policy` (policy area, applies to).
- Workflows: `contract` with a legal review (Reviewer) and an approval (Approver); `policy` with one approval (Approver).
- Released templates "Service contract" (partner from the partner master, computed annual fee and total contract value, conditional term clause, repeated service list, locked sections) and "Policy", created by `designer` and released by `designer2`.
- A draft "Service contract Nordlicht Logistik" of `author` with the partner P-10001 taken over from the partner master.

The partner master is the built-in demo directory (`dms.partners.directory = demo`, the default): fictitious partners such as "Nordlicht Logistik GmbH" (P-10001) or "Beispiel Software AG" (P-10002).

#### Walkthrough: from draft to publication

1. Sign in as `author`. Under Documents, open the draft "Service contract Nordlicht Logistik" (or create one with "New document" from the template "Service contract"; pick the partner under "From the partner master"). Complete the fields; the preview updates as you type.
2. "Submit for review": the document moves to In review and the workflow starts with the legal review.
3. Review as `reviewer`. The web UI cannot cast votes yet (DMS-91); until then vote through the API, which approves all open tasks of the user:

   ```bash
   scripts/dev_setup.py approve-tasks reviewer --api http://localhost:8080
   ```

   The document page (signed in as `author`) shows the vote and the current step under "Review and approval".
4. Approve as `approver`: `scripts/dev_setup.py approve-tasks approver --api http://localhost:8080`. Tasks: `GET /api/v1/workflow/tasks`, vote: `POST /api/v1/workflow/tasks/{taskId}/votes` with `{"decision":"Approve"}`. Approval votes need a sign-in within the last 300 s (step-up, `dms.workflow.reauthenticationSeconds`), read from the access token's `auth_time` claim; otherwise the API answers `401 reauthentication_required` and the client signs in again with `prompt=login`. **Known gap:** the local ZITADEL does not put `auth_time` into access tokens, so approval votes are currently always rejected with `reauthentication_required`, also after a fresh sign-in. Until that is solved, the walkthrough stops here.
5. After the last approval the rendering worker produces the PDF/A rendition; the version becomes Approved once it is attached. Download it with `GET /api/v1/documents/{id}/versions/{versionId}/rendition`.
6. The approved version is published at 00:00 of its "Valid from" date in the tenant's time zone (Europe/Berlin). With today's date or a date in the past it is published within a minute (`dms.documents.publicationCheckSeconds`). `reader` then sees the published version.

### Manual steps

What the script does, step by step, e.g. to repeat a single step.

Start local services:

```bash
docker compose up -d --build
```

Create the ZITADEL project and the OIDC application (idempotent; writes `.local/zitadel/dms.env`):

```bash
scripts/zitadel-setup.sh
```

To start ZITADEL from scratch, remove the volume and the token file (or use `scripts/dev-setup.sh --reset`):

```bash
docker compose down && docker volume rm dms-plattform_zitadel-db && rm -rf .local/zitadel
```

Migrate the database (runs as the schema owner `dms`: Flyway migrations, then the tables of the workflow engine in schema `flowable`; the API connects as `dms_app_user`, which is subject to row-level security):

```bash
sbt "app/runMain dms.app.Migrate"
```

Register the tenant, the demo users and their role assignments (`tenant` and role assignments as schema owner through `psql` in the `db` container; role assignments have no API yet), then seed the demo content through a running backend:

```bash
scripts/dev_setup.py tenant
scripts/dev_setup.py users
scripts/dev_setup.py seed --api http://localhost:8080
```

An unknown organization gets `403 unknown_organization`: the tenant row needs the organization id from the token claim `urn:zitadel:iam:user:resourceowner:id` in `dms.tenant.zitadel_org_id`.

Start the API (reads the ZITADEL issuer and project id from `.local/zitadel/dms.env`):

```bash
set -a && . .local/zitadel/dms.env && set +a && sbt app/run
```

Then open `http://localhost:8080/health` and the API documentation at `http://localhost:8080/docs`. The S3 API of the object storage (SeaweedFS, bucket `dms` with Object Lock, created by `objectstore-init`) is at `http://localhost:9100`.

For API tests with the machine user `dms-api-tester` (client credentials, created on first run; it needs role assignments of its own):

```bash
curl -H "Authorization: Bearer $(scripts/zitadel-test-token.sh)" localhost:8080/api/v1/me
```

Start the web UI (Node 24; details in [frontend/README.md](frontend/README.md)):

```bash
cd frontend
npm ci
cp .env.example .env.local   # client and project id from .local/zitadel/dms.env
npm run dev                  # http://localhost:5173, proxies /api to the backend
```

### Checks

Check formatting and warnings like CI does:

```bash
SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test
```

## Workflow

- One branch per Jira issue, named after the key, e.g. `DMS-21-spike-db-access`.
- Commit messages start with the Jira key, e.g. `DMS-21: Compare Magnum and ZIO Quill`.
- Merge into `main` via pull request after CI is green.
