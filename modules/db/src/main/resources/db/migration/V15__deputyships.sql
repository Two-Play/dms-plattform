-- Deputies for workflow participants (business concept 6.1, US-10.1, DMS-49, ADR 0028).
--
-- A deputyship lets the deputy complete the open workflow tasks of the principal during a limited period. Tasks are
-- not copied or reassigned: the workflow resolves the deputies of a task's candidates when the task is read or voted
-- on, so the period starts and ends without a job. Votes and approvals record whom the deputy acted for.
-- (V14 is reserved for DMS-47.)

create table dms.deputyship (
    id             uuid primary key,
    tenant_id      uuid        not null references dms.tenant (id),
    principal_id   text        not null,
    deputy_id      text        not null,
    valid_from     timestamptz not null,
    -- Always time-limited (an absence, not a permanent delegation).
    valid_until    timestamptz not null,
    -- Business roles of workflow lanes the deputyship covers, as a JSON array of strings.
    roles          jsonb       not null check (jsonb_typeof(roles) = 'array' and jsonb_array_length(roles) > 0),
    -- Restricts the deputyship to documents of this class and its subclasses; null: all classes.
    document_class text,
    reason         text,
    created_by     text        not null,
    created_at     timestamptz not null default now(),
    -- Ending a deputyship early keeps the row for the history.
    ended_at       timestamptz,
    ended_by       text,
    foreign key (tenant_id, document_class) references dms.document_class (tenant_id, key),
    check (principal_id <> deputy_id),
    check (valid_until > valid_from),
    check ((ended_at is null) = (ended_by is null))
);

create index deputyship_principal on dms.deputyship (tenant_id, principal_id);
create index deputyship_deputy on dms.deputyship (tenant_id, deputy_id);

-- Only the early end may be set, once.
create function dms.deputyship_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'dms.deputyship keeps its history: DELETE is not allowed';
    end if;
    if old.ended_at is not null
        or (new.id, new.tenant_id, new.principal_id, new.deputy_id, new.valid_from, new.valid_until, new.roles,
            new.document_class, new.reason, new.created_by, new.created_at)
        is distinct from (old.id, old.tenant_id, old.principal_id, old.deputy_id, old.valid_from, old.valid_until,
                          old.roles, old.document_class, old.reason, old.created_by, old.created_at) then
        raise exception 'a deputyship cannot change; it can only be ended once';
    end if;
    return new;
end
$$;

create trigger deputyship_guard
    before update or delete on dms.deputyship
    for each row execute function dms.deputyship_guard();

alter table dms.deputyship enable row level security;
create policy tenant_isolation on dms.deputyship
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.deputyship to dms_app;
grant update (ended_at, ended_by) on dms.deputyship to dms_app;

-- Votes and approvals of a deputy name the participant they acted for ("deputy on behalf of principal").
alter table dms.workflow_vote add column acting_for text;
alter table dms.workflow_vote add constraint workflow_vote_acting_for check (acting_for is null or acting_for <> voter);

alter table dms.workflow_approval add column acting_for text;
alter table dms.workflow_approval
    add constraint workflow_approval_acting_for
        check (acting_for is null or (acting_for <> approved_by and acting_for <> author_id));

-- Votes: only the invalidation may be set, once; acting_for is as immutable as the rest of the vote.
create or replace function dms.workflow_vote_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'dms.workflow_vote is append-only: DELETE is not allowed';
    end if;
    if old.invalidated_at is not null
        or (new.id, new.tenant_id, new.case_id, new.task_id, new.version_id, new.round, new.step_key, new.lane_key,
            new.voter, new.decision, new.reason, new.content_sha256, new.authenticated_at, new.voted_at,
            new.acting_for)
        is distinct from (old.id, old.tenant_id, old.case_id, old.task_id, old.version_id, old.round, old.step_key,
                          old.lane_key, old.voter, old.decision, old.reason, old.content_sha256, old.authenticated_at,
                          old.voted_at, old.acting_for) then
        raise exception 'a vote cannot change; it can only be invalidated once';
    end if;
    return new;
end
$$;
