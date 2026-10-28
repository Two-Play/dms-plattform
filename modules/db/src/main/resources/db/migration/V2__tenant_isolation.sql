-- Tenant isolation with row-level security (ADR 0014) and the application role.
--
-- Roles:
--   * the migration user owns the schema and objects (it is not subject to RLS as owner);
--   * dms_app is a NOLOGIN group role with the privileges the application needs. Login users for the
--     application (e.g. dms_app_user locally) are members of dms_app and must not have BYPASSRLS.

do
$$
begin
    if not exists (select 1 from pg_roles where rolname = 'dms_app') then
        create role dms_app nologin nobypassrls;
    end if;
end
$$;

-- Fails closed: a missing or empty tenant setting raises SQLSTATE DMS01 instead of returning no rows.
create function dms.current_tenant() returns uuid
    language plpgsql stable as
$$
declare
    v text := current_setting('app.tenant_id', true);
begin
    if v is null or v = '' then
        raise exception 'tenant context missing (app.tenant_id is not set)' using errcode = 'DMS01';
    end if;
    return v::uuid;
end
$$;

alter table dms.tenant enable row level security;
create policy tenant_isolation on dms.tenant
    using (id = dms.current_tenant());

alter table dms.outbox_event enable row level security;
create policy tenant_isolation on dms.outbox_event
    using (tenant_id = dms.current_tenant());

alter table dms.audit_event enable row level security;
create policy tenant_isolation on dms.audit_event
    using (tenant_id = dms.current_tenant());

grant usage on schema dms to dms_app;
grant execute on function dms.current_tenant() to dms_app;
grant select on dms.tenant to dms_app;
grant select, insert, update on dms.outbox_event to dms_app;
-- Append-only: no UPDATE or DELETE privilege; the trigger from V1 additionally guards the table owner.
grant select, insert on dms.audit_event to dms_app;
