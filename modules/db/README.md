# Module `db`

Shared database infrastructure (ADR 0012, 0014): `Db.transact` (one connection per transaction in a FiberRef, joined by Magnum and Flowable), tenant context via `set_config('app.tenant_id', ?, true)`, typed `DbError`, the `Jsonb` codec and the Flyway migrations in `src/main/resources/db/migration`.

Row-level security fails closed: without tenant context, tenant-scoped tables raise `DbError.TenantContextMissing`.
