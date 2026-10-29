-- Notices at the start and the end of a deputyship period (DMS-99, ADR 0028 addendum).
--
-- A periodic job publishes the outbox events workflow.deputyship-started (a scheduled period begins) and
-- workflow.deputyship-ended-by-period (a period runs out without being ended early) to principal and deputy. This
-- table records which notice was published for which deputyship, so the job is idempotent: a notice is published
-- only by the transaction that inserts its row. Rows are never changed or deleted.

create table dms.deputyship_notice (
    tenant_id       uuid        not null references dms.tenant (id),
    deputyship_id   uuid        not null references dms.deputyship (id),
    kind            text        not null check (kind in ('started', 'ended-by-period')),
    noticed_at      timestamptz not null default now(),
    primary key (deputyship_id, kind)
);

alter table dms.deputyship_notice enable row level security;
create policy tenant_isolation on dms.deputyship_notice
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.deputyship_notice to dms_app;

-- Deputyships whose start or end lies before this migration were announced (or not) by the API already; mark them
-- as noticed so the first run of the job does not announce old periods.
insert into dms.deputyship_notice (tenant_id, deputyship_id, kind)
select tenant_id, id, 'started' from dms.deputyship where valid_from <= now();

insert into dms.deputyship_notice (tenant_id, deputyship_id, kind)
select tenant_id, id, 'ended-by-period' from dms.deputyship where valid_until <= now() or ended_at is not null;
