# Running the platform in production

What an installation has to set up and check before going live (DMS-60, [ADR 0056](../adr/0056-production-hardening-and-library-snapshot.md)). The settings are documented in [application.conf](../../modules/app/src/main/resources/application.conf).

## Production checklist

Set `DMS_PROFILE=production`. The application then refuses to start with the settings marked **refused** and names all of them at once; it warns at every start about those marked **warned**.

### Identity

- [ ] **refused** `ZITADEL_ISSUER` is an https address; `DMS_ZITADEL_PROJECT_ID` and `DMS_ZITADEL_CLIENT_ID` are set (approval votes need the client id, ADR 0024).
- [ ] **warned** `DMS_USER_DIRECTORY_TOKEN_FILE` is set: a service user that may read users, one per tenant organization (ADR 0037). Without it, user ids are not resolved and the people picker finds nobody.
- [ ] Every tenant exists in `dms.tenant` with its ZITADEL organization and time zone; the first tenant administrator has a role assignment.

### Data

- [ ] **refused** `DMS_DB_PASSWORD` is not the development password; the application connects as a member of `dms_app` (subject to row-level security), never as the schema owner.
- [ ] `DMS_MIGRATION_USER` and `DMS_MIGRATION_PASSWORD` are available to the deployment only. Migrations run separately (`bin/migrate`), before the new version starts.
- [ ] `DMS_DB_POOL_SIZE` × instances stays below `max_connections`. The load test ran with the default of 10 per instance.
- [ ] **refused** `DMS_STORAGE_ACCESS_KEY` and `DMS_STORAGE_SECRET_KEY` are not the development keys; **warned** `DMS_STORAGE_ENDPOINT` is https.
- [ ] **refused** `DMS_RETENTION_OBJECT_LOCK` stays on, and the bucket was created with Object Lock (it cannot be enabled later).
- [ ] Object Lock mode decided: `governance` (default) or `compliance` (cannot be undone by anyone, ADR 0055).
- [ ] **refused** the virus scanner is on (`DMS_ATTACHMENTS_SCANNER=clamav`), or `DMS_ATTACHMENTS_ALLOW_UNSCANNED_IN_PRODUCTION=true` deliberately (ADR 0041).
- [ ] **refused** `DMS_SEARCH_URL` is https with `DMS_SEARCH_USERNAME` and `DMS_SEARCH_PASSWORD`: the index holds document text. **warned** `DMS_SEARCH_REPLICAS` is at least 1.
- [ ] Retention rules exist for every class in use and are assigned to it (DMS-133): without one, documents of the class cannot be submitted.

### Audit

- [ ] **refused** `DMS_AUDIT_TSA_URL` is a qualified or contractually bound timestamp service and `dms.audit.tsaTrustedCertificates` names its CA, or `DMS_AUDIT_ALLOW_UNANCHORED_IN_PRODUCTION=true` deliberately (ADR 0017).

### Secrets and connections

- [ ] **refused** `DMS_WEBHOOKS_SECRET_KEYS_FILE` holds the key ring (ADR 0035); it is backed up separately from the database. **refused** `DMS_WEBHOOKS_ALLOWED_HOSTS` is empty.
- [ ] **warned** `DMS_SMTP_HOST` is the company's mail relay. With email on, **refused** `DMS_SMTP_SECURITY` is `starttls` or `tls` and `DMS_NOTIFICATIONS_APP_BASE_URL` is the https address of the application.
- [ ] **warned** `DMS_PARTNERS_DIRECTORY` is not `demo`, or no released template has a partner reference (DMS-89).
- [ ] All secrets come from the platform's secret store; no secret is in the image or in the repository.

### Network and front end

- [ ] A reverse proxy terminates TLS, sends `Strict-Transport-Security` and limits request rates per client. The application adds the other security headers itself.
- [ ] The web application is served with its own Content Security Policy; the API's headers only cover the API.
- [ ] `/docs` and `/docs/*` (Swagger UI) are not reachable from outside, unless wanted. `/health` and `/ready` need no sign-in and say nothing beyond the names of the dependencies.
- [ ] The rendering and conversion workers are only reachable from the application; their images are pinned by digest (`DMS_RENDERING_IMAGE`, `DMS_CONVERSION_IMAGE`, ADR 0055).
- [ ] The load balancer checks `GET /ready`; `DMS_HTTP_DRAIN_SECONDS` is at least its interval. The orchestrator's stop timeout is longer than `drainSeconds` + `shutdownGraceSeconds` + 10 s.

### Logs and monitoring

- [ ] `DMS_LOG_APPENDER=JSON` writes one JSON object per line.
- [ ] Alerts on: `/ready` not 200, `level=ERROR`, 503 answers (the database is overloaded), dead jobs (`select kind, count(*) from dms.job where status = 'dead' group by 1`), the age of the oldest unpublished outbox event.
- [ ] Log retention follows the data protection concept. Logs contain ids, not names, titles or field values; database error details are switched off at the driver.

## Health, readiness, shutdown

| Endpoint | Meaning | Answer |
|---|---|---|
| `GET /health` | The process is alive | 200 always; restart the instance if it does not answer |
| `GET /ready` | The instance should get traffic | 200 `ready`, 200 `degraded` (search, rendering, conversion or the virus scanner are down: reading and approving go on, their work queues up or fails closed), 503 `unavailable` (database or object store down), 503 `draining` |

On SIGTERM the instance answers `draining`, waits `drainSeconds`, stops accepting requests and gives those in progress `shutdownGraceSeconds`. Background jobs are interrupted; their leases expire and another instance takes them over, so jobs must stay idempotent (ADR 0010).

Overload answers with 503 `unavailable` and a reference: a transaction waits at most `connectionTimeoutSeconds` for a connection (default 10 s), a statement runs at most `statementTimeoutSeconds` (default 60 s). Quote the reference to find the cause in the log.

## Backup and restore

What has to be saved, and together:

| Data | Holds | Backup |
|---|---|---|
| PostgreSQL | Documents, versions, metadata, workflow (with Flowable's tables), audit trail, jobs | Continuous archiving (WAL) with a base backup per day, for point-in-time recovery; `pg_dump -Fc` is not enough for a recovery point between dumps |
| Object store | Renditions, originals, attachments, audit checkpoints | Bucket replication to a second location with Object Lock, keeping versions. Object Lock protects against deletion, not against losing the store |
| Webhook key ring | Decrypts the webhook signing secrets | In the secret store, with every old key that may still be in use |
| ZITADEL | Users, organizations, roles | Its own database, by the same rules |
| OpenSearch | Derived data | No backup needed: rebuild it |

Restore, in this order:

1. Stop all instances of the application.
2. Restore the object store first, then PostgreSQL to a point in time **before** the object store's state, so that no version points to an object that is missing. Orphaned objects are harmless and removed by the clean-up after its grace period (ADR 0054).
3. Run `bin/migrate` if the application is newer than the backup.
4. Start one instance and check `GET /ready`.
5. Rebuild the search index: `bin/search-rebuild` (or `POST /api/v1/admin/search/rebuild` per tenant). Readers do not depend on it: the library and documents work meanwhile. Time at the load test's volume: about 3.5 minutes for 95,000 versions.
6. Verify the audit chain with an evidence report of a recent document; a broken chain after a restore means the restore point lies before a checkpoint that was written to the object store (ADR 0017). Document the restore in the operations log.
7. Start the remaining instances.

`pg_dump` and `pg_restore` of the complete database were exercised with the load test's data (98,000 versions, 970,000 acknowledgements) for every run. The full procedure above, with point-in-time recovery and object store replication, has not been rehearsed: do so once before going live and record the time it takes.

## Capacity

See [scripts/loadtest/README.md](../../scripts/loadtest/README.md) for the measured load. In short: the library holds about 2 KB per valid version in memory (`DMS_LIBRARY_CACHE_MAX_VERSIONS`, default 200,000, so about 400 MB at most; plan the JVM heap with it). Rendering and conversion run outside the application and are the slowest parts: scale them independently.
