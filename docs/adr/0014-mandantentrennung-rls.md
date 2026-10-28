# 0014: Tenant isolation with a shared database, tenant_id and row-level security

| Field | Value |
|---|---|
| Status | Proposed |
| Date | 2026-10-28 |
| Decided by | Philippe |

## Context

The platform is multi-tenant; tenants correspond to ZITADEL organizations, and a hierarchy (group, subsidiary) is modelled in the platform (0005). A data leak between tenants is the most severe failure imaginable. At the same time, a group-level view across several tenants should be possible, and it must be possible to export and split off a tenant completely. Operated by one person.

## Decision

Proposed: one shared database; every tenant-specific table carries `tenant_id`, and PostgreSQL row-level security enforces the isolation based on a tenant context set per transaction (`SET LOCAL app.tenant_id`). The application connects with a role without `BYPASSRLS`.

## Alternatives considered

| Criterion | Proposal: Shared DB + RLS | Schema per tenant | Database per tenant |
|---|---|---|---|
| Isolation | Logical, enforced by the DB | Logical, via `search_path` | Physical |
| Risk of data leak on programming error | Low (RLS applies even if `WHERE` is missing) | Medium (wrong `search_path`) | Very low |
| Operational effort | One DB | One DB, many schemas | Many DBs, pools, backups |
| Migrations (Flyway) | Once | Per schema, duration grows linearly | Per DB, duration grows linearly |
| Data residency per tenant | Not possible | Not possible | Possible |
| Cross-tenant group view | Via RLS policy over the hierarchy | Queries across schemas, cumbersome | Only via federation/ETL |
| Splitting off / exporting a tenant | Export via `tenant_id` filter | Schema dump | DB dump, simplest |
| Scaling to many small tenants | Very good | Medium (catalogue grows) | Poor |

## Consequences

### Positive

- One schema, one migration, one backup; suits solo operation.
- Isolation is safeguarded by the database even if the application forgets a filter.
- Group-level view via a policy that admits the tenant and its sub-tenants.

### Negative / Cost

- Every query depends on a correctly set context; an error in setting it affects all tenants.
- RLS policies cost performance and complicate query plans; indexes must lead with `tenant_id`.
- A large tenant affects the load of all others (noisy neighbour).
- Exporting a tenant must cover all tables, object storage prefixes and the search index.
- Flowable tables (0006) are not automatically subject to RLS; isolation there via Flowable `tenantId`.

## Follow-ups

- RLS policy template for every new table; migration check that no tenant-specific table is created without a policy.
- DB roles: migration role (owner), application role (without `BYPASSRLS`), worker role.
- Set the context centrally in the transaction wrapper (0012); test that no rows are visible without a context.
- Object storage keys and OpenSearch documents with a tenant prefix or field respectively (0009).
- Plan tenant export as a separate feature.
- Allow for a hybrid: tenants with data residency requirements could later get their own database; therefore do not rule out a "tenant → DataSource" configuration.

## Reversibility

**high**: Switching to schema or DB per tenant affects connection management, migrations, workers and data migration of all existing tenants. The hybrid route (moving individual tenants out) remains possible with medium effort if the `tenant_id` column is kept.

## Open questions from spike DMS-21 (2026-10-28)

- Without a tenant set, the policy `current_setting('app.tenant_id')::uuid` raises an error instead of returning zero rows. Decide whether that fail-closed behaviour is wanted (recommended: yes, it surfaces missing tenant context as a bug) or whether `current_setting('app.tenant_id', true)` should yield zero rows.
- `SET LOCAL` cannot take bind parameters; use `set_config('app.tenant_id', ?, true)`.
- RLS violations and missing grants share SQLSTATE 42501.
