-- Reminder, overdue notice and escalation of periodic review tasks (DMS-141, ADR 0057).
--
-- dms.document_review_deadline_notice: which notice (reminder, overdue, escalated) was published for which review
-- task, so the periodic deadline check publishes every notice once, like dms.workflow_task_deadline_notice (V35).
-- The times are not stored: they follow from the review date and the deadline policy in force. Rows are never
-- changed or deleted.

create table dms.document_review_deadline_notice (
    tenant_id  uuid        not null references dms.tenant (id),
    review_id  uuid        not null references dms.document_review (id),
    kind       text        not null check (kind in ('reminder', 'overdue', 'escalated')),
    noticed_at timestamptz not null default now(),
    primary key (review_id, kind)
);

alter table dms.document_review_deadline_notice enable row level security;
create policy tenant_isolation on dms.document_review_deadline_notice
    using (tenant_id = dms.current_tenant())
    with check (tenant_id = dms.current_tenant());

grant select, insert on dms.document_review_deadline_notice to dms_app;
