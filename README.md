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

Build and test (the integration tests start PostgreSQL, SeaweedFS, OpenSearch, the rendering worker and the conversion worker with Testcontainers and need Docker):

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

1. starts the services (`docker compose up -d --build`: PostgreSQL on 5433, ZITADEL on `http://localhost:8085`, SeaweedFS on 9100, rendering worker on 9180, conversion worker on 9181, OpenSearch on 9200) and waits for them,
2. creates the ZITADEL project and OIDC application (`scripts/zitadel-setup.sh`, writes `.local/zitadel/dms.env`),
3. builds the backend (`sbt app/stage`) and migrates the database (`dms.app.Migrate`),
4. registers the ZITADEL organization as tenant (`dms.tenant.zitadel_org_id`, time zone Europe/Berlin),
5. creates the demo users in ZITADEL and their role assignments,
6. seeds demo content through the API as the demo users (so the audit trail stays a valid hash chain),
7. rebuilds the search index from the database (`POST /api/v1/admin/search/rebuild` as `admin`),
8. writes `frontend/.env.local` and starts backend and frontend dev server in the background (logs and process ids in `.local/logs/`).

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
| `compliance` | ComplianceOfficer |

The ZITADEL default admin `zitadel-admin@zitadel.localhost` (password `Password1!`, change on first login; console at `http://localhost:8085/ui/console`) gets TenantAdmin.

Demo content:

- Field definitions and the document classes `contract` (partner number, name and address, monthly fee, term, start and end date, notice period, services, service level), `policy` (policy area, applies to) and `central-instruction` "Zentralanweisung" (short description `summary`, shown in the library, and applies to).
- Workflows: `contract` with a legal review (Reviewer) and an approval (Approver); `policy` and `central-instruction` with one approval (Approver).
- Number ranges (DMS-113): `contracts` (`VTR-{YYYY}-{NNNN}`, yearly reset) assigned to `contract`, `policies` (`RL-{NNN}`) assigned to `policy` and `central-instructions` (`ZA-{YYYY}-{NNN}`, yearly reset) assigned to `central-instruction`; new documents of these classes get the next number. Manage them as `admin` under Admin → Number ranges.
- Released templates "Service contract" (partner from the partner master, computed annual fee and total contract value, conditional term clause, repeated service list, locked sections) and "Policy", created by `designer` and released by `designer2`.
- A draft "Service contract Nordlicht Logistik" of `author` with the partner P-10001 taken over from the partner master.
- Four published central instructions (DMS-120) for the library (`/library`): "Informationssicherheit am Arbeitsplatz" (IT-Sicherheit), "Reisekostenrichtlinie" (Finanzen), "Umgang mit personenbezogenen Daten" (Datenschutz) and "Mobiles Arbeiten und Homeoffice" (Personal), with tags, short descriptions and numbers `ZA-<year>-001` … from the released template "Zentralanweisung". The seed runs the real workflow: `author` submits, `approver` approves with a fresh sign-in (step-up), the rendering and publication jobs publish them; it waits up to three minutes and continues on the next run if the rendering worker was not ready. Readers see them in the library with an open acknowledgement.
- A Word template "Zentralanweisung (Word)" (a DOCX with content controls for number, index, issuing unit, valid-from date, short description, approval and the manual field "Ansprechpartner", DMS-115) and the Word draft "Dienstwagenregelung (Word-Entwurf)" of `author`.
- `scripts/dev_setup.py check [--dump DIR]` checks the demo content offline (fields of the classes, placeholders of the templates, locked sections of the instructions, the Word file's fields) without any running service.

The partner master is the built-in demo directory (`dms.partners.directory = demo`, the default): fictitious partners such as "Nordlicht Logistik GmbH" (P-10001) or "Beispiel Software AG" (P-10002).

#### Walkthrough: from draft to publication

1. Sign in as `author`. Under Documents, open the draft "Service contract Nordlicht Logistik" (or create one with "New document" from the template "Service contract"; pick the partner under "From the partner master"). Complete the fields; the preview updates as you type.
   Under "Attachments" add related files (drag and drop or "Choose files…"; up to 50 MB, `dms.attachments.maxSizeMegabytes`; programs and scripts are refused). They are reviewed, approved and archived with the version and taken over by the next version (DMS-114, ADR 0041). API: `POST /api/v1/documents/{id}/attachments` (multipart, part `file`), `GET …/versions/{versionId}/attachments`, `GET`/`PATCH`/`DELETE …/attachments/{attachmentId}`. No virus scanner is configured locally (`dms.attachments.scanner = none`); ADR 0041 describes plugging in ClamAV.
2. "Submit for review": the document moves to In review and the workflow starts with the legal review.
3. Review as `reviewer`. The web UI cannot cast votes yet (DMS-91); until then vote through the API, which approves all open tasks of the user:

   ```bash
   scripts/dev_setup.py approve-tasks reviewer --api http://localhost:8080
   ```

   The document page (signed in as `author`) shows the vote and the current step under "Review and approval".
4. Approve as `approver`: `scripts/dev_setup.py approve-tasks approver --api http://localhost:8080`. Tasks: `GET /api/v1/workflow/tasks`, vote: `POST /api/v1/workflow/tasks/{taskId}/votes` with `{"decision":"Approve"}`. Approval votes need a sign-in within the last 300 s (step-up, `dms.workflow.reauthenticationSeconds`). ZITADEL access tokens carry no `auth_time`, so the vote request also sends the OIDC ID token of the same sign-in in header `X-DMS-Id-Token` (the script signs in afresh and does this; the backend needs `DMS_ZITADEL_CLIENT_ID` from `.local/zitadel/dms.env`). Without a recent sign-in the API answers `401 reauthentication_required`; the web UI then signs in again with `prompt=login` and the vote has to be cast again (ADR 0024, addendum DMS-86).
5. After the last approval the rendering worker produces the PDF/A rendition; the version becomes Approved once it is attached. Download it with `GET /api/v1/documents/{id}/versions/{versionId}/rendition`.

   Every readable version can be read on the document page (DMS-112, ADR 0039): `GET /api/v1/documents/{id}/versions/{versionId}/html` gives its content (drafts and versions in review or approval marked "Draft – not valid", approved versions from their frozen package). "Download PDF" gives the draft PDF of an open version (`…/draft.pdf`, generated on demand, not archived, audited) or the archived rendition; "Attach change history" appends the history of the versions up to this one (`?changelog=true`; for approved versions as a copy, `…/copy.pdf`, the archived file stays unchanged).
6. The approved version is published at 00:00 of its "Valid from" date in the tenant's time zone (Europe/Berlin). With today's date or a date in the past it is published within a minute (`dms.documents.publicationCheckSeconds`). `reader` then sees the published version.
7. Search as `reader` (DMS-51, DMS-52, ADR 0029): `GET /api/v1/search?q=Nordlicht` finds the published version a few seconds after publication (outbox and job queue; changes without an event within `dms.search.syncIntervalSeconds`). Hits, hit count, facets and snippets only cover versions the caller may read; role assignment changes apply to the next search. Filters: `class`, `status` (default: valid versions; `Superseded` for the history), `lang`; paging with `page` and `size`.
8. Acknowledge as `reader` (DMS-47, ADR 0027): every major version (and minor versions configured to) asks everyone who may read it for an acknowledgement. Open the published document; the page records the opening and the "Acknowledgement" card enables "Acknowledge". After clicking it the card shows the acknowledgement with version and time. Open and done acknowledgements: `GET /api/v1/acknowledgements?status=open|done`. Acknowledging without opening first is rejected (`409 not_opened`), a second time too (`409 already_acknowledged`); when a new version supersedes the published one, the open acknowledgement moves to the new version if it requires acknowledgement.
9. Legal hold as `records` (records manager for the whole tenant; DMS-56, ADR 0031; the web UI has no legal hold section yet): `POST /api/v1/legal-holds` with `{"name":"Litigation Nordlicht","reason":"Claim received","matterReference":"LIT-1","documentIds":["<document id>"]}`, or with `"searchFilter":{"q":"Nordlicht","expectedCount":<total of the search>}` to hold the documents of a search result. The document then shows `legalHold: true` and cannot be destroyed; `POST /api/v1/legal-holds/{id}/release` with `{"reason":"..."}` releases it. The renditions get the S3 object legal hold through the job `archive.legal-hold-sync`.
10. Compliance cockpit as `compliance` (DMS-59, ADR 0036): "Compliance" in the navigation shows the overdue reviews per organizational unit and owner and the acknowledgement rates per version and unit; every figure links to its list. "Who has not acknowledged" asks for a purpose (escalation or reminder) and a reason, recorded in the audit trail. API: `GET /api/v1/compliance/overview`, `…/overdue-reviews?unit=&owner=`, `…/acknowledgements?unit=&document=`, `…/acknowledgements/{documentId}/{versionId}/open?purpose=escalation&reason=…`.

### Manual steps

What the script does, step by step, e.g. to repeat a single step.

Start local services:

```bash
docker compose up -d --build
```

Create the ZITADEL project, the OIDC application and the read-only machine user `dms-directory` that resolves user ids to names (idempotent; writes `.local/zitadel/dms.env` and the directory token `.local/zitadel/directory.pat`, DMS-110):

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

Then open `http://localhost:8080/health` and the API documentation at `http://localhost:8080/docs`. The S3 API of the object storage (SeaweedFS, bucket `dms` with Object Lock, created by `objectstore-init`) is at `http://localhost:9100`. The search index (OpenSearch, single node without security plugin, local only) is at `http://localhost:9200`; it is derived from the database and can be rebuilt at any time:

```bash
scripts/dev_setup.py search-rebuild --api http://localhost:8080   # the tenant of the demo users, through the API
sbt "app/runMain dms.app.SearchRebuild"                           # every tenant (or the tenant ids given)
```

For API tests with the machine user `dms-api-tester` (client credentials, created on first run; it needs role assignments of its own):

```bash
curl -H "Authorization: Bearer $(scripts/zitadel-test-token.sh)" localhost:8080/api/v1/me
```

The intranet reads the valid version of a policy through stable addresses (DMS-58, ADR 0035): `GET /api/v1/documents/{id}/current` (JSON), `/current.pdf` and `/current.html`, optionally `?scope=`. Its technical user `dms-intranet` is a ZITADEL machine user with the platform role Reader scoped to the class `policy`, set up by `scripts/dev-setup.sh` (or `scripts/dev_setup.py intranet`, idempotent). Its client credentials are kept in `.local/zitadel/intranet.secret` (never printed); the command prints the token request. In production, assign Reader with `document_class` for each released class; confidential documents stay hidden unless the user is a member of an allowed access group. Subscriptions for webhooks (`document.published`, `document.superseded`, `document.withdrawn`) are managed at `/api/v1/admin/webhooks`; locally, set `DMS_WEBHOOKS_ALLOWED_HOSTS=localhost` to deliver to a receiver on this machine (see [modules/integration/README.md](modules/integration/README.md)). Tenant administrators manage them in the tab "Webhooks" on `/admin`, including a test event and the delivery log (DMS-107). Signing secrets are encrypted at rest with a platform key: locally the built-in development key is used (warning at startup); production must set `DMS_WEBHOOKS_SECRET_KEYS_FILE` or `DMS_WEBHOOKS_SECRET_KEYS` (key handling and rotation: [modules/integration/README.md](modules/integration/README.md#key-handling-for-the-secret-encryption-production)).

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
