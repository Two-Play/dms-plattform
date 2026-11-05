# Module `db`

Shared database infrastructure (ADR 0012, 0014): `Db.transact` (one connection per transaction in a FiberRef, joined by Magnum and Flowable), tenant context via `set_config('app.tenant_id', ?, true)`, typed `DbError`, the `Jsonb` codec and the Flyway migrations in `src/main/resources/db/migration`.

Row-level security fails closed: without tenant context, tenant-scoped tables raise `DbError.TenantContextMissing`.

## Migrations

Migrations run with `dms.app.Migrate` (`bin/migrate`) as the schema owner, one transaction per migration, before the new version of the application starts. The latest number is the highest file in `src/main/resources/db/migration`. An applied migration is never changed: its checksum is checked.

### Migrations without a transaction

`create index` in a normal migration stops every write to the table until it is done. That is fine for a new or empty table, in the migration that creates it. **An index on a table that holds data in production is built concurrently**, which PostgreSQL does not allow in a transaction (ADR 0066). V38 and earlier were written for an empty production and stay as they are.

Write one migration per index, with nothing else in it:

```sql
-- V50__workflow_task_candidates.sql
-- Why this index is needed.
drop index concurrently if exists dms.workflow_task_candidates;

create index concurrently workflow_task_candidates on dms.workflow_task using gin (candidates);
```

- Flyway recognizes `create [unique] index concurrently` and `drop index concurrently` and runs the file without a transaction. Nothing else needs declaring.
- The `drop` makes the migration repeatable: a failed build leaves an invalid index behind.
- Do not mix: Flyway refuses a file with both kinds of statement (`Detected both transactional and non-transactional statements`). Put the other statements into a migration of their own, and leave `mixed` off.
- Other statements that cannot run in a transaction and that Flyway does not recognize, such as `reindex index concurrently`, fail with `cannot run inside a transaction block`. Add a file `V50__name.sql.conf` beside the migration with the line `executeInTransaction=false`. Then every statement commits on its own and Flyway no longer refuses a mix: keep it to one statement.
- The build waits for all older transactions and reads the table twice, so it takes longer than a plain one and does not stop writers. Partitioned tables cannot be indexed concurrently; there are none today.
- Flyway must connect directly to PostgreSQL, not through a transaction pooler, and its lock must stay on the session (`Migrations.flyway`): with Flyway's default the build waits for Flyway's own transaction and never ends.

If such a migration fails, see "When a migration fails" in [docs/operations/production.md](../../docs/operations/production.md). `MigrationsSpec` tests all of this against the real configuration; its migrations under `src/test/resources/db/nontransactional` exist only for the tests.
