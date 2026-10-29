-- Recipients per document class and reminders for open acknowledgements (DMS-100, ADR 0048; follows ADR 0027).
--
-- 1. dms.acknowledgement_settings: who must acknowledge (a restriction of the readers to roles, access groups
--    and organizational units; empty: every reader) and after how many days open acknowledgements are reminded.
--    One row per document class, inherited by its subclasses, and at most one tenant default (class_id null).
-- 2. dms.version_acknowledgement_audience: the settings that applied when a version that requires
--    acknowledgement became valid (snapshot, insert-only). Membership is still evaluated when asked, so people who
--    join a role, group or unit later are asked as well.
-- 3. dms.acknowledgement_reminder: every reminder sent, automatic (one per version, reader and round; the unique
--    index makes the reminder job idempotent) or manual (by an owner or compliance officer, rate-limited per
--    version by the application). Insert-only.

create table dms.acknowledgement_settings (
    id               uuid primary key     default gen_random_uuid(),
    tenant_id        uuid        not null references dms.tenant (id),
    -- The class the settings belong to (and its subclasses); null: the tenant default.
    class_id         uuid,
    recipient_roles  text[]      not null default '{}',
    recipient_groups text[]      not null default '{}',
    recipient_units  text[]      not null default '{}',
    -- Days after publication until the first automatic reminder, and between reminders; 0: none; null: inherit.
    reminder_days    integer check (reminder_days between 0 and 365),
    updated_at       timestamptz not null default now(),
    updated_by       text        not null check (length(trim(updated_by)) > 0),
    unique nulls not distinct (tenant_id, class_id),
    foreign key (tenant_id, class_id) references dms.document_class (tenant_id, id)
);

alter table dms.acknowledgement_settings enable row level security;
create policy tenant_isolation on dms.acknowledgement_settings
    using (tenant_id = dms.current_tenant());

grant select, insert, update, delete on dms.acknowledgement_settings to dms_app;

create table dms.version_acknowledgement_audience (
    version_id       uuid primary key,
    tenant_id        uuid        not null,
    -- Where the settings came from: the version's class or an ancestor ('class'), the tenant default ('tenant'),
    -- or nothing configured ('default': every reader, the installation's reminder interval).
    source           text        not null check (source in ('class', 'tenant', 'default')),
    source_class_id  uuid,
    recipient_roles  text[]      not null default '{}',
    recipient_groups text[]      not null default '{}',
    recipient_units  text[]      not null default '{}',
    -- null: the installation's reminder interval.
    reminder_days    integer check (reminder_days between 0 and 365),
    recorded_at      timestamptz not null default now(),
    check ((source = 'class') = (source_class_id is not null)),
    foreign key (tenant_id, version_id) references dms.document_version (tenant_id, id)
);

create trigger version_acknowledgement_audience_immutable
    before update or delete on dms.version_acknowledgement_audience
    for each row execute function dms.reject_modification();

alter table dms.version_acknowledgement_audience enable row level security;
create policy tenant_isolation on dms.version_acknowledgement_audience
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.version_acknowledgement_audience to dms_app;

-- Versions valid before this migration keep every reader as recipients. Their reminder interval starts now, so
-- the first run of the reminder job does not remind of every old version at once.
insert into dms.version_acknowledgement_audience (version_id, tenant_id, source)
select id, tenant_id, 'default'
from dms.document_version
where status in ('Published', 'ReviewDue')
  and requires_acknowledgement;

create table dms.acknowledgement_reminder (
    id         uuid primary key     default gen_random_uuid(),
    tenant_id  uuid        not null,
    version_id uuid        not null,
    user_id    text        not null check (length(trim(user_id)) > 0),
    kind       text        not null check (kind in ('automatic', 'manual')),
    -- Automatic reminders: the number of reminder intervals since publication (1, 2, ...).
    round      integer check (round > 0),
    sent_at    timestamptz not null default now(),
    -- Manual reminders: who sent them.
    sent_by    text,
    check ((kind = 'automatic') = (round is not null)),
    check ((kind = 'manual') = (sent_by is not null)),
    foreign key (tenant_id, version_id) references dms.document_version (tenant_id, id)
);

create unique index acknowledgement_reminder_round
    on dms.acknowledgement_reminder (version_id, user_id, round) where kind = 'automatic';
create index acknowledgement_reminder_version
    on dms.acknowledgement_reminder (tenant_id, version_id, kind, sent_at);

create trigger acknowledgement_reminder_immutable
    before update or delete on dms.acknowledgement_reminder
    for each row execute function dms.reject_modification();

alter table dms.acknowledgement_reminder enable row level security;
create policy tenant_isolation on dms.acknowledgement_reminder
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.acknowledgement_reminder to dms_app;
