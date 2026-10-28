-- Access groups for confidential documents (business concept 2.3; DMS-101, ADR 0032).
--
-- From confidentiality "Confidential" on, a document version names the groups that may see it besides its author
-- and owner (core field allowedGroups). Groups and their members are kept per tenant in the platform, so that the
-- workflow can tell which role holders may read a confidential version when it assigns review and approval tasks.
-- Groups are never deleted (versions keep naming them); members leave by setting removed_at, the row is kept for
-- the history of permissions.

create table dms.access_group (
    tenant_id  uuid        not null references dms.tenant (id),
    -- Stable key stored in document versions, e.g. board or hr-management.
    key        text        not null check (key ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    name       text        not null check (length(trim(name)) between 1 and 200),
    created_by text        not null,
    created_at timestamptz not null default now(),
    primary key (tenant_id, key)
);

create table dms.access_group_member (
    id         uuid primary key default gen_random_uuid(),
    tenant_id  uuid        not null,
    group_key  text        not null,
    user_id    text        not null check (length(trim(user_id)) > 0),
    added_by   text        not null,
    added_at   timestamptz not null default now(),
    removed_by text,
    removed_at timestamptz,
    foreign key (tenant_id, group_key) references dms.access_group (tenant_id, key),
    check ((removed_at is null) = (removed_by is null)),
    check (removed_at is null or removed_at >= added_at)
);

-- At most one current membership per user and group.
create unique index access_group_member_current
    on dms.access_group_member (tenant_id, group_key, user_id) where removed_at is null;
create index access_group_member_user on dms.access_group_member (tenant_id, user_id);

-- A membership can only be ended, once; it never changes otherwise and is never deleted.
create function dms.access_group_member_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'dms.access_group_member keeps its history: DELETE is not allowed';
    end if;
    if old.removed_at is not null
        or (new.id, new.tenant_id, new.group_key, new.user_id, new.added_by, new.added_at)
        is distinct from (old.id, old.tenant_id, old.group_key, old.user_id, old.added_by, old.added_at) then
        raise exception 'a group membership cannot change; it can only be ended once';
    end if;
    return new;
end
$$;

create trigger access_group_member_guard
    before update or delete on dms.access_group_member
    for each row execute function dms.access_group_member_guard();

alter table dms.access_group enable row level security;
create policy tenant_isolation on dms.access_group
    using (tenant_id = dms.current_tenant());

alter table dms.access_group_member enable row level security;
create policy tenant_isolation on dms.access_group_member
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.access_group to dms_app;
-- Only the display name of a group may change.
grant update (name) on dms.access_group to dms_app;
grant select, insert on dms.access_group_member to dms_app;
grant update (removed_by, removed_at) on dms.access_group_member to dms_app;
