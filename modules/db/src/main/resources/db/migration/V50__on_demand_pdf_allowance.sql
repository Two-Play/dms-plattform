-- Allowance for PDFs printed on demand (DMS-118, ADR 0073).
--
-- dms.on_demand_pdf_allowance: one token bucket per user and one per tenant (user_id = ''), shared by every application
-- instance. `tokens` is the level at `updated_at`; the level now follows from the elapsed time, so no job refills the
-- rows. A bucket that has refilled means the same as no row, so nothing depends on a row staying.

create table dms.on_demand_pdf_allowance (
    tenant_id  uuid             not null references dms.tenant (id),
    user_id    text             not null,
    tokens     double precision not null,
    updated_at timestamptz      not null,
    primary key (tenant_id, user_id)
);

alter table dms.on_demand_pdf_allowance enable row level security;
create policy tenant_isolation on dms.on_demand_pdf_allowance
    using (tenant_id = dms.current_tenant());

grant select, insert, update on dms.on_demand_pdf_allowance to dms_app;

-- The level of a bucket now. `capacity` is both the size of the bucket and what it regains per minute. Transactions may
-- start out of order, so the elapsed time can be slightly negative.
create function dms.on_demand_pdf_tokens(tokens double precision, updated_at timestamptz, capacity double precision)
    returns double precision
    language sql stable as
$$
select least(capacity, tokens + greatest(0, extract(epoch from now() - updated_at)::double precision) * capacity / 60)
$$;
