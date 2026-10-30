-- Deadlines, reminders and escalation of review and approval tasks (DMS-87, ADR 0053).
--
-- 1. dms.workflow_task: when a task is due, when its participants are reminded and when it is escalated, computed
--    in working days when the task is created (from the step, the workflow, the tenant's settings or the
--    installation's default). Tasks created before this migration have no deadline.
-- 2. dms.workflow_deadline_settings: the tenant's default deadline policy (one row per tenant).
-- 3. dms.workflow_task_deadline_notice: which notice (reminder, overdue, escalated) was published for which task,
--    so the periodic deadline check publishes every notice once. Rows are never changed or deleted.

alter table dms.workflow_task
    add column due_at      timestamptz,
    add column remind_at   timestamptz,
    add column escalate_at timestamptz,
    add check (remind_at is null or (due_at is not null and remind_at <= due_at)),
    add check (escalate_at is null or (due_at is not null and escalate_at >= due_at));

create index workflow_task_deadline on dms.workflow_task (tenant_id, due_at) where status = 'Open' and due_at is not null;

create table dms.workflow_deadline_settings (
    tenant_id               uuid primary key references dms.tenant (id),
    -- 0: no deadlines.
    due_working_days        integer     not null check (due_working_days between 0 and 60),
    -- 0: no reminder.
    reminder_working_days   integer     not null check (reminder_working_days between 0 and 60),
    -- 0: no escalation after the deadline.
    escalation_working_days integer     not null check (escalation_working_days between 0 and 60),
    escalate_to             text[]      not null default '{}'
        check (escalate_to <@ array ['Owner', 'ComplianceOfficer', 'TenantAdmin']::text[]),
    updated_at              timestamptz not null default now(),
    updated_by              text        not null check (length(trim(updated_by)) > 0)
);

alter table dms.workflow_deadline_settings enable row level security;
create policy tenant_isolation on dms.workflow_deadline_settings
    using (tenant_id = dms.current_tenant());

grant select, insert, update, delete on dms.workflow_deadline_settings to dms_app;

create table dms.workflow_task_deadline_notice (
    tenant_id  uuid        not null references dms.tenant (id),
    task_id    text        not null references dms.workflow_task (id),
    kind       text        not null check (kind in ('reminder', 'overdue', 'escalated')),
    noticed_at timestamptz not null default now(),
    primary key (task_id, kind)
);

alter table dms.workflow_task_deadline_notice enable row level security;
create policy tenant_isolation on dms.workflow_task_deadline_notice
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.workflow_task_deadline_notice to dms_app;

-- The deadline columns of dms.workflow_task are written when a task is inserted (grant from V11) and never updated.
