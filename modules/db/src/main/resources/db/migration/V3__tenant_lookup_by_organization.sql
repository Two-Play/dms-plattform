-- Resolves a ZITADEL organization to a tenant before any tenant context exists (sign-in).
-- security definer: runs with the owner's rights, so it can read dms.tenant despite row-level security.
-- It returns only the tenant id for exactly the given organization.
create function dms.tenant_by_zitadel_org(organization_id text) returns uuid
    language sql stable security definer
    set search_path = dms, pg_temp as
$$
select id from dms.tenant where zitadel_org_id = organization_id
$$;

revoke all on function dms.tenant_by_zitadel_org(text) from public;
grant execute on function dms.tenant_by_zitadel_org(text) to dms_app;
