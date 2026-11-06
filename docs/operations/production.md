# Running the platform in production

What an installation has to set up and check before going live (DMS-60, [ADR 0056](../adr/0056-production-hardening-and-library-snapshot.md)). The settings are documented in [application.conf](../../modules/app/src/main/resources/application.conf).

## Production checklist

Set `DMS_PROFILE=production`. The application then refuses to start with the settings marked **refused** and names all of them at once; it warns at every start about those marked **warned**.

### Identity

- [ ] **refused** `ZITADEL_ISSUER` is an https address; `DMS_ZITADEL_PROJECT_ID` and `DMS_ZITADEL_CLIENT_ID` are set (approval votes need the client id, ADR 0024).
- [ ] **warned** `DMS_USER_DIRECTORY_TOKEN_FILE` is set: the token of a service user that may read users in every tenant's organization ([steps below](#service-user-of-the-user-directory), ADR 0037, ADR 0067). Without it, user ids are not resolved and the people picker finds nobody.
- [ ] Every tenant exists in `dms.tenant` with its ZITADEL organization and time zone; the first tenant administrator has a role assignment; the service user of the user directory passed the check for this organization.

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
- [ ] The reverse proxy's access log holds method, path and status, but no request bodies and no query strings of `/api` requests: the stated purpose and reason for viewing named people travel in a request body (ADR 0060), and filters in query strings may name organizational units.
- [ ] The web application is served with its own Content Security Policy; the API's headers only cover the API.
- [ ] `/docs` and `/docs/*` (Swagger UI) are not reachable from outside, unless wanted. `/health` and `/ready` need no sign-in and say nothing beyond the names of the dependencies. `/metrics` needs no sign-in either and gives totals only, but they show how busy the installation is, and every call reads the database: let only the monitoring reach it (ADR 0079).
- [ ] The rendering and conversion workers are only reachable from the application; their images are pinned by digest (`DMS_RENDERING_IMAGE`, `DMS_CONVERSION_IMAGE`, ADR 0055).
- [ ] The rendering worker is rolled out before or with an application that prints plain draft PDFs (ADR 0073): an older worker refuses them and draft PDFs fail with 422.
- [ ] The limits of on-demand PDFs fit the deployment (`DMS_ON_DEMAND_PDF_*`, ADR 0073): the in-flight limits hold per application instance, so the worker can get as many times the value as there are instances. The rates hold over all instances.
- [ ] The rendering and conversion workers restart by themselves after a crash, in compose with `restart: unless-stopped` (ADR 0081); elsewhere set the same, for example `restartPolicy: Always`. A restart policy acts on a process that ended, not on an unhealthy one.
- [ ] The limits of a render fit the deployment (ADR 0081): `DMS_RENDER_TIME_LIMIT_SECONDS` of the worker (default 120) is below `DMS_RENDERING_TIMEOUT_SECONDS` of the application (default 150), which stays below the 270 s of a job with the check of the PDF on top. `DMS_RENDER_MAX_PAGES` (default 300 estimated pages) fits the memory of the worker: about 1.2 MiB per page on top of 120 MiB. An older application sees both refusals as `rejected` and retries them, so roll out the application first or with the worker.
- [ ] **refused** The rendering and conversion workers have no outbound network: no route and no proxy to the company network or the internet, for example a Docker network with `internal: true` or a Kubernetes NetworkPolicy without egress (ADR 0076). Documents can name external content, and for HTML stylesheets only the network stops LibreOffice. Try it from inside the worker: `python -c "import socket; socket.create_connection(('<a host of your network>', 443), 3)"` must fail. Then confirm with `DMS_CONVERSION_OUTBOUND_BLOCKED=true`; the application cannot see the network itself, so this setting is your statement, not a measurement.
- [ ] The load balancer checks `GET /ready`; `DMS_HTTP_DRAIN_SECONDS` is at least its interval. The orchestrator's stop timeout is longer than `drainSeconds` + `shutdownGraceSeconds` + 10 s.

### Logs and monitoring

- [ ] Heap: reading an Office file can take about 1 GiB; one runs at a time per 2 GiB of JVM heap, at most four (ADR 0076). Give the application at least 2 GiB beyond the library cache.

- [ ] `DMS_LOG_APPENDER=JSON` writes one JSON object per line.
- [ ] Alerts on: `/ready` not 200, `level=ERROR` (also the signal of a violation of the audit chain, ADR 0080), 503 answers (the database is overloaded), dead jobs (`select kind, count(*) from dms.job where status = 'dead' group by 1`), the age of the oldest unpublished outbox event, and `dms_rendering_queue_oldest_seconds` of `GET /metrics`: above 60 for several minutes means renditions come in faster than they are printed (see Capacity).
- [ ] Log retention follows the data protection concept. Logs contain ids, not names, titles or field values; database error details are switched off at the driver.

## Service user of the user directory

The backend reads names and emails from ZITADEL and searches people for the picker with one read-only service user (ADR 0037, ADR 0067). The platform uses one token for all tenants. Tenants stay apart because every request is limited to the organization of the caller's tenant and the answers are checked, not because of the token. The token can therefore read every organization it was given rights in: keep it in the secret store.

The steps were tried out on ZITADEL v4.19.5. They need an administrator's token (`ADMIN_TOKEN`, `ORG_OWNER` of the organization or `IAM_OWNER`), which the platform never sees.

**Once per installation:** create the machine user, best in an organization of the operator that is not a tenant. Machine users never show up in the people picker.

```bash
curl -sS -X POST "$ZITADEL_ISSUER/management/v1/users/machine" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "x-zitadel-orgid: $OPERATOR_ORG_ID" -H "Content-Type: application/json" \
  -d '{"userName":"dms-directory","name":"DMS user directory","accessTokenType":"ACCESS_TOKEN_TYPE_BEARER"}'

# The answer holds the token once: send it straight to the secret store, not into a log or a shell history.
curl -sS -X POST "$ZITADEL_ISSUER/management/v1/users/$DIRECTORY_USER_ID/pats" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "x-zitadel-orgid: $OPERATOR_ORG_ID" -H "Content-Type: application/json" \
  -d '{"expirationDate":"2027-12-31T00:00:00Z"}'
```

Mount the token as a file and point `DMS_USER_DIRECTORY_TOKEN_FILE` at it (or set `DMS_USER_DIRECTORY_TOKEN` from the secret store).

**For every tenant:** make the service user a member of the tenant's organization, the same id as in `dms.tenant.zitadel_org_id`. The user may belong to another organization.

```bash
curl -sS -X POST "$ZITADEL_ISSUER/management/v1/orgs/me/members" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "x-zitadel-orgid: $TENANT_ORG_ID" -H "Content-Type: application/json" \
  -d "{\"userId\":\"$DIRECTORY_USER_ID\",\"roles\":[\"ORG_OWNER_VIEWER\"]}"
```

**Check every tenant.** Do not skip this: without read rights ZITADEL answers **200 with an empty list**, not an error. The platform cannot tell this from an organization without matching people, so user ids stay unresolved and the picker says that nobody was found.

```bash
curl -sS -X POST "$ZITADEL_ISSUER/v2/users" \
  -H "Authorization: Bearer $(cat "$DMS_USER_DIRECTORY_TOKEN_FILE")" -H "Content-Type: application/json" \
  -d "{\"query\":{\"limit\":1},\"queries\":[{\"organizationIdQuery\":{\"organizationId\":\"$TENANT_ORG_ID\"}}]}"
```

The answer must have a `result` with one user and a `details.totalResult`. Rights take effect within seconds. Names the platform has already looked up as unknown stay unknown for up to 600 seconds (`DMS_USER_DIRECTORY_CACHE_SECONDS`), search answers for up to 60 seconds (`DMS_USER_DIRECTORY_SEARCH_CACHE_SECONDS`).

**Instead of a member entry per organization**, the instance role `IAM_OWNER_VIEWER` also works and needs no step per tenant, but a stolen token then reads every organization and the instance's settings. Use it only if adding tenants cannot include the member step.

**Running it:**

- The token is read at start-up: after rotating it, restart the application. Create the new token before the old one expires; an expired token makes names and searches fail with 503 `user_directory_unavailable`, and the UI falls back to user ids.
- When a tenant leaves, remove the member entry from its organization.
- The role only reads. The service user needs no role in the DMS project and no role assignment in the platform.

## Health, readiness, shutdown

| Endpoint | Meaning | Answer |
|---|---|---|
| `GET /health` | The process is alive | 200 always; restart the instance if it does not answer |
| `GET /ready` | The instance should get traffic | 200 `ready`, 200 `degraded` (search, rendering, conversion or the virus scanner are down: reading and approving go on, their work queues up or fails closed), 503 `unavailable` (database or object store down), 503 `draining` |
| `GET /metrics` | Business figures for monitoring (business concept chapter 13) | Text format of Prometheus: `dms_rendering_queue_depth{state="due\|delayed\|running"}` (rendition jobs of all tenants) and `dms_rendering_queue_oldest_seconds` (how long the oldest due job has been waiting). All instances answer with the same numbers, as the queue is shared: scrape one, or take the maximum. 503 without the database |

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

## When a migration fails

A normal migration rolls back, and `bin/migrate` can simply run again once the cause is fixed.

A migration without a transaction (an index built concurrently, see [modules/db/README.md](../../modules/db/README.md), ADR 0066) cannot roll back. It stays in the history as failed, and `bin/migrate` refuses to continue with a message about a failed migration; an index it was building stays behind as invalid. The running application is not affected: it does not use an invalid index. As schema owner:

1. Look at the failure: `select version, description, installed_on from dms.flyway_schema_history where not success;`
2. Fix its cause, for example duplicate rows for a unique index.
3. Remove the failed row: `delete from dms.flyway_schema_history where not success;`
4. Run `bin/migrate` again. The migration starts by dropping what the failed build left.

Do not use `flyway repair` here: it also realigns the checksums of applied migrations. A build that takes long is usually waiting for a long transaction; `pg_stat_activity` shows it, `pg_stat_progress_create_index` shows the progress. Stopping the build counts as a failure of the migration.

## Capacity

See [scripts/loadtest/README.md](../../scripts/loadtest/README.md) for the measured load. In short: the library holds about 2 KB per valid version in memory (`DMS_LIBRARY_CACHE_MAX_VERSIONS`, default 200,000, so about 400 MB at most; plan the JVM heap with it). Rendering and conversion run outside the application and are the slowest parts: scale them independently.

**Renditions.** A worker prints one page at a time, and a rendition job waits for its answer, so the number of rendition jobs at a time decides the throughput (ADR 0079). Run as many worker replicas as you want in parallel and set `DMS_RENDERING_PARALLEL_JOBS` so that instances times jobs does not exceed the replicas; on-demand PDFs (`DMS_ON_DEMAND_PDF_*`) take their turn on the same workers. More jobs than replicas gain nothing: they wait in the worker, for each other and for a render whose caller has given up (DMS-161), and run into `DMS_RENDERING_TIMEOUT_SECONDS`. A job also validates with veraPDF in the application, which costs CPU and heap there. Add a replica when `dms_rendering_queue_oldest_seconds` stays above zero.

**Long documents.** A worker refuses documents of more than about 300 pages before it renders them, and kills a render after 120 s or when the kernel ends it at the memory limit (ADR 0081). The worker stays up, the job ends at once without retries, and owner and records managers get a notice with the reason (`too_long`, `time_limit`). To print such a document, raise `DMS_RENDER_MAX_PAGES`, the memory of the worker and if needed the time limits, then retry it from the failure list. The worker restarts by itself if it dies all the same.

**Acknowledgement reminders.** The hourly check of a tenant runs as a chain of jobs of `DMS_DOCUMENTS_ACKNOWLEDGEMENT_REMINDER_PAGE_SIZE` versions (default 200), with one short transaction per version, in a lane of their own (ADR 0079). The lane handles about 30 versions a second per instance; if the checks of all tenants take longer than the hour, add instances or raise `DMS_DOCUMENTS_ACKNOWLEDGEMENT_REMINDER_CHECK_SECONDS`. Runs that overlap do no harm.
