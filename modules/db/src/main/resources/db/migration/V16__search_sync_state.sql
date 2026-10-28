-- Search index synchronization state (DMS-51, ADR 0029).
--
-- The search index (OpenSearch) is derived from the database and can be rebuilt at any time (ADR 0009). Besides
-- the outbox events, a periodic synchronization job reindexes every document changed since the last run of the
-- tenant; this table remembers up to when a tenant's index was synchronized. A missing row means: never
-- synchronized, index everything.
create table dms.search_sync_state (
    tenant_id    uuid        primary key references dms.tenant (id),
    synced_until timestamptz not null,
    updated_at   timestamptz not null default now()
);

alter table dms.search_sync_state enable row level security;
create policy tenant_isolation on dms.search_sync_state
    using (tenant_id = dms.current_tenant())
    with check (tenant_id = dms.current_tenant());

grant select, insert, update on dms.search_sync_state to dms_app;

-- The synchronization looks up documents by the time of their last change.
create index document_updated_at on dms.document (tenant_id, updated_at);
create index document_version_updated_at on dms.document_version (tenant_id, updated_at);
