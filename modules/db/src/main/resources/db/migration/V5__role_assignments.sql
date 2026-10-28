-- Role assignments per tenant, optionally scoped to an organizational unit and a document class (ADR 0014,
-- business concept 2.3). Revocation sets valid_until; rows are kept for the history of permissions.

create table dms.role_assignment (
    id                  uuid primary key default gen_random_uuid(),
    tenant_id           uuid not null references dms.tenant (id),
    user_id             text not null,
    role                text not null check (role in ('Reader', 'Author', 'Owner', 'Reviewer', 'Approver',
                                                      'TemplateDesigner', 'RecordsManager', 'Auditor', 'TenantAdmin')),
    organizational_unit text,
    document_class      text,
    valid_from          timestamptz not null default now(),
    valid_until         timestamptz,
    granted_by          text not null,
    granted_at          timestamptz not null default now(),
    check (valid_until is null or valid_until > valid_from)
);

create index role_assignment_user on dms.role_assignment (tenant_id, user_id);

alter table dms.role_assignment enable row level security;
create policy tenant_isolation on dms.role_assignment
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.role_assignment to dms_app;
-- Only valid_until may change (revocation); everything else is immutable for the application.
grant update (valid_until) on dms.role_assignment to dms_app;
