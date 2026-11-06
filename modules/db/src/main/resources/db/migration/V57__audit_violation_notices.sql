-- Telling people that the regular verification found a violation of the audit chain (DMS-169, ADR 0080).
--
-- dms.audit_violation_notice: one row per notice about an open violation. A violation is named by the run that found it
-- first (dms.audit_verification_run); round 0 is the first notice, higher rounds are reminders. The primary key makes
-- sure a repeated run and several instances send each round once. Insert-only, like the runs it refers to. It holds no
-- position, kind or detail: those stay in the run.

create table dms.audit_violation_notice (
    tenant_id uuid        not null references dms.tenant (id),
    run_id    bigint      not null references dms.audit_verification_run (id),
    round     integer     not null check (round >= 0),
    sent_at   timestamptz not null,
    primary key (tenant_id, run_id, round)
);

create trigger audit_violation_notice_guard
    before update or delete on dms.audit_violation_notice
    for each row execute function dms.reject_modification();

alter table dms.audit_violation_notice enable row level security;
create policy tenant_isolation on dms.audit_violation_notice
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.audit_violation_notice to dms_app;
