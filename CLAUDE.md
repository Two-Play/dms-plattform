# Project conventions

- **Language:** code, comments, identifiers, test names, log and error messages, and all documentation in this repository are written in English. Exception: `docs/fachkonzept.html` (business concept) stays German. Jira issues in project DMS are German.
- **Jira:** project DMS on it-heimnet.atlassian.net. Planning IDs (e.g. T-00.5, US-03.1) map to Jira keys in `docs/jira-mapping.md`. Keep issue status in sync with the work.
- **Branching:** one branch per Jira issue (`DMS-<n>-<slug>`), pull request into `main`, merge only with green CI. Commit messages start with the Jira key.
- **Decisions:** every architecture decision gets an ADR in `docs/adr/` that shows alternatives, differences, positive and negative consequences, follow-ups and reversibility. Change status instead of deleting.
- **Build:** Java 21 via `eval "$(cs java --jvm temurin:21 --env)"`. CI runs `SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test`; warnings fail the build.
- **Module rule:** modules depend only on `kernel`, `db`, `storage` (core modules) and other modules' `*-api`; only `app` depends on `*-core`.
- **Database:** all access through `dms.db.Db.transact(tenant)` / `Db.sql`; no forked fibers inside a transaction; every tenant-scoped table gets an RLS policy using `dms.current_tenant()` and explicit grants to `dms_app`.
- **Tests with Docker:** locally export `DOCKER_HOST=unix://$HOME/.docker/desktop/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` for Testcontainers.
- **Local services:** `docker compose up -d`; PostgreSQL on port 5433, ZITADEL on 8085, S3 object storage (SeaweedFS) on 9100.
