-- Number ranges and document numbers (DMS-113, ADR 0040).
--
-- A number range (pattern, start value, yearly reset) is assigned to document classes; a class without an own
-- assignment inherits the one of its nearest ancestor, and an assignment without range stops the inheritance.
-- Creating a document of such a class takes the next counter value of the range in the same transaction as the
-- document insert: the counter row is locked (SELECT ... FOR UPDATE) until the transaction ends, so concurrent
-- creations get distinct consecutive numbers, and a creation that rolls back also rolls back its counter
-- increment (no gap). The number is stored with the document (unique per tenant) and copied into the field
-- values of every version (core field documentNumber).

create table dms.number_range (
    id           uuid primary key,
    tenant_id    uuid        not null references dms.tenant (id),
    key          text        not null check (key ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    name         text        not null check (length(trim(name)) between 1 and 200),
    -- Validated by the application (NumberPattern), e.g. POL-{YYYY}-{NNNN}.
    pattern      text        not null check (length(pattern) between 1 and 100),
    start_value  bigint      not null check (start_value between 0 and 999999999999),
    yearly_reset boolean     not null,
    created_by   text        not null,
    created_at   timestamptz not null default now(),
    updated_by   text        not null,
    updated_at   timestamptz not null default now(),
    unique (tenant_id, key),
    unique (tenant_id, id)
);

-- The next counter value per range and period: the year for ranges with yearly reset, otherwise 0.
create table dms.number_range_counter (
    tenant_id  uuid    not null,
    range_id   uuid    not null,
    period     integer not null check (period >= 0),
    next_value bigint  not null check (next_value >= 0),
    primary key (range_id, period),
    foreign key (tenant_id, range_id) references dms.number_range (tenant_id, id)
);

-- The range of a class; range_id null: explicitly no number range (overrides an inherited one).
create table dms.document_class_number_range (
    tenant_id   uuid        not null,
    class_id    uuid        primary key,
    range_id    uuid,
    assigned_by text        not null,
    assigned_at timestamptz not null default now(),
    foreign key (tenant_id, class_id) references dms.document_class (tenant_id, id),
    foreign key (tenant_id, range_id) references dms.number_range (tenant_id, id)
);

alter table dms.document
    add column document_number text check (length(document_number) between 1 and 64);

create unique index document_number_unique on dms.document (tenant_id, document_number)
    where document_number is not null;

-- A number frozen in a version stays: once any version of the document has left Draft (it was submitted, and an
-- approved version carries the number in its metadata snapshot), an existing number cannot change. A document
-- without number can still get one (e.g. one created before its class had a range).
create function dms.document_number_guard() returns trigger
    language plpgsql as
$$
begin
    if old.document_number is not null and new.document_number is distinct from old.document_number
        and exists (select 1 from dms.document_version v where v.document_id = new.id and v.status <> 'Draft') then
        raise exception 'the document number % of document % is frozen in a version', old.document_number, new.id
            using errcode = 'check_violation';
    end if;
    return new;
end
$$;

create trigger document_number_guard
    before update of document_number on dms.document
    for each row execute function dms.document_number_guard();

alter table dms.number_range enable row level security;
create policy tenant_isolation on dms.number_range using (tenant_id = dms.current_tenant());
alter table dms.number_range_counter enable row level security;
create policy tenant_isolation on dms.number_range_counter using (tenant_id = dms.current_tenant());
alter table dms.document_class_number_range enable row level security;
create policy tenant_isolation on dms.document_class_number_range using (tenant_id = dms.current_tenant());

grant select, insert on dms.number_range to dms_app;
grant update (name, pattern, start_value, yearly_reset, updated_by, updated_at) on dms.number_range to dms_app;
grant select, insert on dms.number_range_counter to dms_app;
grant update (next_value) on dms.number_range_counter to dms_app;
grant select, insert, delete on dms.document_class_number_range to dms_app;
grant update (range_id, assigned_by, assigned_at) on dms.document_class_number_range to dms_app;
grant update (document_number) on dms.document to dms_app;
