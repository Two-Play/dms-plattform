-- Reminders of jobs that failed for good (DMS-146, ADR 0064).
--
-- dms.job_failure_reminder: one row per reminder of a dead rendering or publication job. A retry brings a job back and it
-- can die again, so a death is the job with the moment it died (dms.job.finished_at); its rounds count from there. The
-- primary key makes the check idempotent: the event is written only by the transaction that inserted the row, so retries
-- and several instances remind once. No foreign key to dms.job: a record of what was sent outlives the job.

create table dms.job_failure_reminder (
    tenant_id uuid        not null references dms.tenant (id),
    job_id    bigint      not null,
    failed_at timestamptz not null,
    round     integer     not null check (round > 0),
    sent_at   timestamptz not null default now(),
    primary key (job_id, failed_at, round)
);

create trigger job_failure_reminder_immutable
    before update or delete on dms.job_failure_reminder
    for each row execute function dms.reject_modification();

alter table dms.job_failure_reminder enable row level security;
create policy tenant_isolation on dms.job_failure_reminder
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.job_failure_reminder to dms_app;

-- The hourly check looks at dead jobs of these kinds only, while dms.job also holds every delivered notification.
create index job_dead_failures on dms.job (tenant_id)
    where status = 'dead' and kind in ('document.render-rendition', 'document.publish');
