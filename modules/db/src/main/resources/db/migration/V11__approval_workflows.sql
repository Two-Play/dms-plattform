-- Review and approval workflows (business concept 2.4, 6.1; ADR 0006, 0024; DMS-40, DMS-41, DMS-42).
--
-- Flowable routes the steps; its tables live in schema flowable and are created by dms.app.Migrate as the schema
-- owner after this migration (FlowableEngine.migrateSchema). They have no row-level security: tenants are separated
-- by Flowable's tenant id and only workflow-core accesses them. The business truth (cases, tasks with their
-- participants, votes and approvals bound to a content checksum) lives in the tenant-scoped tables below.

create schema if not exists flowable;
grant usage on schema flowable to dms_app;
-- Flowable's DDL runs as the schema owner (the user running the migrations), so these defaults cover its tables.
alter default privileges in schema flowable grant select, insert, update, delete on tables to dms_app;
alter default privileges in schema flowable grant usage, select on sequences to dms_app;

-- Workflow of a document class, one row per version (insert-only). A class without a row of its own uses the
-- workflow of its nearest ancestor, else the built-in default.
create table dms.workflow_definition (
    tenant_id  uuid        not null references dms.tenant (id),
    class_key  text        not null,
    version    integer     not null check (version >= 1),
    -- Steps, lanes and reset step, see dms.workflow.WorkflowDefinition.
    definition jsonb       not null,
    -- Four-eyes rule (business concept 2.4): the author is never a participant. Disabling it needs a reason and is
    -- recorded in the audit trail.
    four_eyes  boolean     not null default true,
    reason     text,
    created_at timestamptz not null default now(),
    created_by text        not null,
    primary key (tenant_id, class_key, version),
    foreign key (tenant_id, class_key) references dms.document_class (tenant_id, key),
    check (four_eyes or length(trim(coalesce(reason, ''))) > 0)
);

-- The review and approval of one document version. Every submission is a round with its own Flowable process
-- instance; all rounds run on the definition and the Flowable process definition of the first round.
create table dms.workflow_case (
    id                    uuid primary key,
    tenant_id             uuid        not null,
    document_id           uuid        not null,
    version_id            uuid        not null unique,
    class_key             text        not null,
    -- null: the built-in default workflow
    definition_version    integer,
    definition            jsonb       not null,
    four_eyes             boolean     not null,
    process_definition_id text        not null,
    process_instance_id   text,
    round                 integer     not null default 1 check (round >= 1),
    status                text        not null check (status in ('Running', 'Returned', 'Approved')),
    current_step          text,
    returned_step         text,
    -- SHA-256 of the content and field values under review in the current round; votes are bound to it.
    content_sha256        text        not null check (content_sha256 ~ '^[0-9a-f]{64}$'),
    author_id             text        not null,
    started_by            text        not null,
    started_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    unique (tenant_id, id),
    foreign key (tenant_id, version_id) references dms.document_version (tenant_id, id),
    foreign key (tenant_id, document_id) references dms.document (tenant_id, id),
    check ((status = 'Running') = (process_instance_id is not null))
);

-- One task per lane and round, mirroring the Flowable user task with the resolved participants.
create table dms.workflow_task (
    id           text primary key,
    tenant_id    uuid        not null,
    case_id      uuid        not null,
    round        integer     not null,
    step_key     text        not null,
    step_kind    text        not null check (step_kind in ('Review', 'Approval')),
    lane_key     text        not null,
    role         text        not null,
    -- User ids (ZITADEL sub) who may complete the task, as a JSON array of strings.
    candidates   jsonb       not null default '[]' check (jsonb_typeof(candidates) = 'array'),
    status       text        not null check (status in ('Open', 'Waiting', 'Completed', 'Cancelled')),
    created_at   timestamptz not null default now(),
    completed_at timestamptz,
    completed_by text,
    foreign key (tenant_id, case_id) references dms.workflow_case (tenant_id, id),
    check (status in ('Completed', 'Cancelled') or (status = 'Waiting') = (jsonb_array_length(candidates) = 0))
);

create index workflow_task_open on dms.workflow_task (tenant_id) where status in ('Open', 'Waiting');
create index workflow_task_case on dms.workflow_task (case_id);

-- Votes (business concept 6.1), bound to the version and its content checksum. Only the invalidation is ever set
-- afterwards, when the content changed (DMS-41).
create table dms.workflow_vote (
    id                  uuid primary key,
    tenant_id           uuid        not null,
    case_id             uuid        not null,
    task_id             text        not null references dms.workflow_task (id),
    version_id          uuid        not null,
    round               integer     not null,
    step_key            text        not null,
    lane_key            text        not null,
    voter               text        not null,
    decision            text        not null
        check (decision in ('Approve', 'ApproveWithConditions', 'RequestChanges', 'Reject')),
    reason              text,
    content_sha256      text        not null check (content_sha256 ~ '^[0-9a-f]{64}$'),
    authenticated_at    timestamptz,
    voted_at            timestamptz not null default now(),
    invalidated_at      timestamptz,
    invalidation_reason text,
    foreign key (tenant_id, case_id) references dms.workflow_case (tenant_id, id),
    check (decision = 'Approve' or length(trim(coalesce(reason, ''))) > 0),
    check ((invalidated_at is null) = (invalidation_reason is null))
);

create index workflow_vote_case on dms.workflow_vote (case_id);

-- The approval of a version: exact version, content checksum, approver and the sign-in the approval was confirmed
-- with (step-up). Insert-only.
create table dms.workflow_approval (
    id               uuid primary key,
    tenant_id        uuid        not null,
    case_id          uuid        not null unique,
    version_id       uuid        not null unique,
    version_label    text        not null,
    content_sha256   text        not null check (content_sha256 ~ '^[0-9a-f]{64}$'),
    author_id        text        not null,
    approved_by      text        not null,
    authenticated_at timestamptz not null,
    approved_at      timestamptz not null default now(),
    foreign key (tenant_id, case_id) references dms.workflow_case (tenant_id, id),
    foreign key (tenant_id, version_id) references dms.document_version (tenant_id, id),
    check (approved_by <> author_id)
);

create trigger workflow_definition_guard
    before update or delete on dms.workflow_definition
    for each row execute function dms.reject_modification();
create trigger workflow_approval_guard
    before update or delete on dms.workflow_approval
    for each row execute function dms.reject_modification();

-- Votes: only the invalidation may be set, once.
create function dms.workflow_vote_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'dms.workflow_vote is append-only: DELETE is not allowed';
    end if;
    if old.invalidated_at is not null
        or (new.id, new.tenant_id, new.case_id, new.task_id, new.version_id, new.round, new.step_key, new.lane_key,
            new.voter, new.decision, new.reason, new.content_sha256, new.authenticated_at, new.voted_at)
        is distinct from (old.id, old.tenant_id, old.case_id, old.task_id, old.version_id, old.round, old.step_key,
                          old.lane_key, old.voter, old.decision, old.reason, old.content_sha256, old.authenticated_at,
                          old.voted_at) then
        raise exception 'a vote cannot change; it can only be invalidated once';
    end if;
    return new;
end
$$;

create trigger workflow_vote_guard
    before update or delete on dms.workflow_vote
    for each row execute function dms.workflow_vote_guard();

alter table dms.workflow_definition enable row level security;
create policy tenant_isolation on dms.workflow_definition
    using (tenant_id = dms.current_tenant());

alter table dms.workflow_case enable row level security;
create policy tenant_isolation on dms.workflow_case
    using (tenant_id = dms.current_tenant());

alter table dms.workflow_task enable row level security;
create policy tenant_isolation on dms.workflow_task
    using (tenant_id = dms.current_tenant());

alter table dms.workflow_vote enable row level security;
create policy tenant_isolation on dms.workflow_vote
    using (tenant_id = dms.current_tenant());

alter table dms.workflow_approval enable row level security;
create policy tenant_isolation on dms.workflow_approval
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.workflow_definition to dms_app;
grant select, insert on dms.workflow_case to dms_app;
grant update (process_instance_id, round, status, current_step, returned_step, content_sha256, updated_at)
    on dms.workflow_case to dms_app;
grant select, insert on dms.workflow_task to dms_app;
grant update (candidates, status, completed_at, completed_by) on dms.workflow_task to dms_app;
grant select, insert on dms.workflow_vote to dms_app;
grant update (invalidated_at, invalidation_reason) on dms.workflow_vote to dms_app;
grant select, insert on dms.workflow_approval to dms_app;
