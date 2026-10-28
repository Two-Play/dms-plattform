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
  identity/  authz/       sign-in via ZITADEL, authorization
  <module>/api  <module>/core   templates, documents, workflow, rendering,
                                archive, search, audit, notifications
  integration/            outbox, webhooks, business system adapters
  app/                    wiring, HTTP endpoints, migrations
workers/                  Python workers (rendering, OCR)
frontend/                 web UI
spikes/                   time-boxed technical experiments, not production code
```

Rule: modules depend only on `kernel`, `db` (core modules only) and on `*-api`, never on another module's `*-core`. Only `app` knows the core modules. The build enforces this.

## Development

Provide Java 21 (once per shell):

```bash
eval "$(cs java --jvm temurin:21 --env)"
```

Build and test (the `db` module's integration tests start PostgreSQL with Testcontainers and need Docker):

```bash
sbt test
```

With Docker Desktop on Linux, Testcontainers needs the socket location:

```bash
export DOCKER_HOST=unix://$HOME/.docker/desktop/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

Start local services:

```bash
docker compose up -d
```

Create the ZITADEL project and the OIDC application (idempotent; writes `.local/zitadel/dms.env`):

```bash
scripts/zitadel-setup.sh
```

The ZITADEL console is at `http://localhost:8085/ui/console` (local default admin `zitadel-admin@zitadel.localhost`, password `Password1!`, change on first login). To start ZITADEL from scratch, remove the volume and the token file:

```bash
docker compose down && docker volume rm dms-plattform_zitadel-db && rm -rf .local/zitadel
```

Migrate the database (runs as the schema owner `dms`; the API connects as `dms_app_user`, which is subject to row-level security):

```bash
sbt "app/runMain dms.app.Migrate"
```

Start the API (reads the ZITADEL issuer and project id from `.local/zitadel/dms.env`):

```bash
set -a && . .local/zitadel/dms.env && set +a && sbt app/run
```

For API tests with a real token, register the local ZITADEL organization as a tenant once and fetch a token for the machine user `dms-api-tester` (created on first run):

```bash
scripts/zitadel-test-token.sh
```

```bash
curl -H "Authorization: Bearer $(scripts/zitadel-test-token.sh)" localhost:8080/api/v1/me
```

The tenant row needs the organization id from the token claim `urn:zitadel:iam:user:resourceowner:id` in `dms.tenant.zitadel_org_id`; an unknown organization gets `403 unknown_organization`.

Then open `http://localhost:8080/health` and the API documentation at `http://localhost:8080/docs`. PostgreSQL listens on port 5433, ZITADEL on `http://localhost:8085`.

Check formatting and warnings like CI does:

```bash
SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test
```

## Workflow

- One branch per Jira issue, named after the key, e.g. `DMS-21-spike-db-access`.
- Commit messages start with the Jira key, e.g. `DMS-21: Compare Magnum and ZIO Quill`.
- Merge into `main` via pull request after CI is green.
