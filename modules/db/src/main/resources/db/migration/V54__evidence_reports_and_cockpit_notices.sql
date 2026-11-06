-- Evidence reports and the compliance cockpit, the rest of DMS-108 (ADR 0077; follow-ups of ADR 0034 and 0036).
--
-- dms.audit_verification_run: every full verification of a tenant's audit chain, intact or not. The newest run decides
-- whether an evidence report may start after a verified prefix: the prefix ends at a timestamp anchor, and a run that
-- found a violation closes the shortcut. Insert-only, so a violation that was found stays on record.
--
-- dms.evidence_report: a report loses its payload when its retention ended. The row stays as a tombstone with id,
-- document, creator and checksum, as the audit entry of its creation refers to them. Legal holds and documents that
-- still exist stop the removal here too, not only in the maintenance job.
--
-- dms.compliance_notice: what compliance officers sent from the cockpit about an overdue review, so that a click cannot
-- be repeated at will: at most one notice per version, kind and 24 hours, checked by the application under an advisory
-- lock like the manual acknowledgement reminder (V29). Insert-only.

create table dms.audit_verification_run (
    id                 bigint generated always as identity primary key,
    tenant_id          uuid        not null references dms.tenant (id),
    verified_at        timestamptz not null,
    ok                 boolean     not null,
    entries_checked    bigint      not null check (entries_checked >= 0),
    anchors_checked    integer     not null check (anchors_checked >= 0),
    head_seq           bigint,
    violation_seq      bigint,
    violation_kind     text,
    violation_detail   text,
    -- Why the write-once checkpoints could not be compared; the run then covers links, hashes and anchors.
    checkpoint_problem text,
    -- The verified prefix: up to here a report need not read the chain again. Always a timestamp anchor.
    prefix_seq         bigint,
    prefix_hash        bytea check (octet_length(prefix_hash) = 32),
    check ((prefix_seq is null) = (prefix_hash is null)),
    check (ok = (violation_kind is null)),
    check ((violation_kind is null) = (violation_seq is null)),
    check (prefix_seq is null or ok),
    foreign key (tenant_id, prefix_seq) references dms.audit_anchor (tenant_id, anchored_until_seq)
);

create index audit_verification_run_newest on dms.audit_verification_run (tenant_id, id desc);

create trigger audit_verification_run_guard
    before update or delete on dms.audit_verification_run
    for each row execute function dms.reject_modification();

alter table dms.audit_verification_run enable row level security;
create policy tenant_isolation on dms.audit_verification_run
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.audit_verification_run to dms_app;

-- The check of V21 stays: for a removed payload it is not false but unknown, which a check constraint lets pass.
alter table dms.evidence_report
    alter column payload drop not null,
    add column payload_removed_at timestamptz,
    add column payload_removed_by text check (payload_removed_by is null or length(trim(payload_removed_by)) > 0);

alter table dms.evidence_report
    add constraint evidence_report_removal
        check ((payload is null) = (payload_removed_at is not null)
            and (payload_removed_at is null) = (payload_removed_by is null))
        not valid;
alter table dms.evidence_report validate constraint evidence_report_removal;

create function dms.evidence_report_guard() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' then
        raise exception 'dms.evidence_report keeps its registry entries: DELETE is not allowed';
    end if;
    if old.payload is null or new.payload is not null
        or (new.id, new.tenant_id, new.document_id, new.format, new.created_at, new.created_by, new.sha256)
        is distinct from (old.id, old.tenant_id, old.document_id, old.format, old.created_at, old.created_by, old.sha256)
    then
        raise exception 'an evidence report does not change; it only loses its payload once';
    end if;
    if dms.document_under_legal_hold(old.document_id) then
        raise exception 'the document of evidence report % is under legal hold', old.id;
    end if;
    if exists (select 1 from dms.document_version v
               where v.document_id = old.document_id and v.status not in ('Destroyed', 'Discarded')) then
        raise exception 'the document of evidence report % still exists', old.id;
    end if;
    return new;
end
$$;

drop trigger evidence_report_immutable on dms.evidence_report;

create trigger evidence_report_guard
    before update or delete on dms.evidence_report
    for each row execute function dms.evidence_report_guard();

grant update (payload, payload_removed_at, payload_removed_by) on dms.evidence_report to dms_app;

create table dms.compliance_notice (
    id          uuid primary key,
    tenant_id   uuid        not null references dms.tenant (id),
    document_id uuid        not null,
    version_id  uuid        not null,
    -- reminder: the owner and their deputies; escalation: also the escalation targets of the deadline policy.
    kind        text        not null check (kind in ('reminder', 'escalation')),
    sent_at     timestamptz not null,
    sent_by     text        not null check (length(trim(sent_by)) > 0),
    recipients  integer     not null check (recipients > 0),
    foreign key (tenant_id, document_id) references dms.document (tenant_id, id),
    foreign key (tenant_id, version_id) references dms.document_version (tenant_id, id)
);

create index compliance_notice_latest on dms.compliance_notice (tenant_id, version_id, kind, sent_at desc);

create trigger compliance_notice_immutable
    before update or delete on dms.compliance_notice
    for each row execute function dms.reject_modification();

alter table dms.compliance_notice enable row level security;
create policy tenant_isolation on dms.compliance_notice
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.compliance_notice to dms_app;
