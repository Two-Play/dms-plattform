-- Audit trail hash chain (DMS-29, ADR 0017): one chain per tenant, anchored with RFC 3161 timestamps.
-- The hash format is documented in modules/audit/README.md.
--
-- dms.audit_event had no writer before this migration, so the new not-null column needs no backfill.

alter table dms.audit_event
    add column chain_seq   bigint   not null,
    add column hash_format smallint not null default 1;

alter table dms.audit_event
    -- Position in the tenant's chain: 1, 2, 3, ... without gaps. Also the index for appending and verifying.
    add constraint audit_event_chain_position unique (tenant_id, chain_seq),
    add constraint audit_event_chain_seq_positive check (chain_seq >= 1),
    -- Genesis: the first entry of a chain has no predecessor (prev_hash is null), every other entry has one.
    add constraint audit_event_genesis check ((chain_seq = 1) = (prev_hash is null)),
    add constraint audit_event_hash_length check (
        octet_length(hash) = 32 and (prev_hash is null or octet_length(prev_hash) = 32)
    );

-- RFC 3161 timestamps over the chain head: the entry at anchored_until_seq had hash chain_head_hash, and a
-- timestamp authority confirmed that hash at tsa_time.
create table dms.audit_anchor (
    id                 bigint generated always as identity primary key,
    tenant_id          uuid        not null references dms.tenant (id),
    anchored_until_seq bigint      not null,
    chain_head_hash    bytea       not null check (octet_length(chain_head_hash) = 32),
    tsa_url            text        not null,
    tsa_token          bytea       not null,
    tsa_time           timestamptz not null,
    created_at         timestamptz not null default now(),
    unique (tenant_id, anchored_until_seq)
);

create function dms.reject_modification() returns trigger
    language plpgsql as
$$
begin
    raise exception '%.% is append-only: % is not allowed', tg_table_schema, tg_table_name, tg_op;
end
$$;

create trigger audit_anchor_guard
    before update or delete on dms.audit_anchor
    for each row execute function dms.reject_modification();

alter table dms.audit_anchor enable row level security;
create policy tenant_isolation on dms.audit_anchor
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.audit_anchor to dms_app;
