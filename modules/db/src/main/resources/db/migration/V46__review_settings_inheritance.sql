-- Review settings are inherited from parent classes, and a change of a class's interval reaches open review dates
-- (DMS-104, ADR 0068).
--
-- A class version either has its own review settings or takes them from the nearest ancestor. The ancestors count with
-- their latest version, not the pinned parent version: ADR 0030 lets the latest settings of a class apply to all its
-- documents, and a parent's change has to reach its subclasses the same way.
--
-- This migration changes no stored data. document_class_version stays append-only, so review_inherited stays empty on
-- the versions that exist; dms.class_version_inherits_review says what such a version means. No review date is
-- written here: the audit trail is a hash chain written by the application, and every date set later has to be
-- recorded there, so the review check does that (see ReviewService).

alter table dms.document_class_version
    add column review_inherited boolean,
    add constraint document_class_version_review_inherited
        check (review_inherited is not true or (parent_version is not null and review_interval_months is null));

-- Whether a class version takes its review settings from its ancestors. A version from before V46 (empty) does if it is
-- a subclass that never set anything: no interval and the default lead time. Business concept 3.1: classes inherit the
-- rules of their parent. Anything else it set stays its own.
create function dms.class_version_inherits_review(v dms.document_class_version) returns boolean
    language sql immutable as
$$
select coalesce(v.review_inherited,
                v.parent_version is not null and v.review_interval_months is null and v.review_lead_days = 60)
$$;

-- The review settings in force for a class: those of the nearest class on the way to the root that has its own, each
-- class with its latest version. A root class always has its own. Runs as the caller, so RLS limits it to the tenant.
create function dms.class_review_policy(class uuid)
    returns table (interval_months integer, lead_days integer)
    language sql stable as
$$
with recursive chain (class_id, parent_id, depth) as (
    select id, parent_id, 0 from dms.document_class where id = class
    union all
    select c.id, c.parent_id, chain.depth + 1
    from dms.document_class c join chain on c.id = chain.parent_id
    where chain.depth < 64)
select latest.review_interval_months, latest.review_lead_days
from chain
cross join lateral (select * from dms.document_class_version v
                    where v.class_id = chain.class_id order by v.version desc limit 1) latest
where not dms.class_version_inherits_review(latest)
order by chain.depth
limit 1
$$;

-- What a change of a class's interval did to a version whose date it reached: moved, or kept and why. One row per class
-- version and document version, written in the transaction of the audit entry, so a job that runs again records
-- nothing twice (the pattern of dms.document_review_deadline_notice, V39).
create table dms.document_review_recalculation (
    tenant_id       uuid        not null,
    class_id        uuid        not null,
    class_version   integer     not null,
    version_id      uuid        not null,
    outcome         text        not null check (outcome in ('Moved', 'Kept')),
    previous_due_on date,
    next_due_on     date,
    reason          text,
    created_at      timestamptz not null default now(),
    primary key (class_id, class_version, version_id),
    foreign key (tenant_id, class_id) references dms.document_class (tenant_id, id),
    foreign key (class_id, class_version) references dms.document_class_version (class_id, version),
    foreign key (tenant_id, version_id) references dms.document_version (tenant_id, id),
    check ((outcome = 'Moved') = (next_due_on is not null)),
    check ((outcome = 'Kept') = (reason is not null))
);

create trigger document_review_recalculation_guard
    before update or delete on dms.document_review_recalculation
    for each row execute function dms.reject_modification();

alter table dms.document_review_recalculation enable row level security;
create policy tenant_isolation on dms.document_review_recalculation
    using (tenant_id = dms.current_tenant())
    with check (tenant_id = dms.current_tenant());

grant select, insert on dms.document_review_recalculation to dms_app;
