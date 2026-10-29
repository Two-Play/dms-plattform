-- Evidence reports (US-12.1, DMS-57, ADR 0034): registry of the generated reports of a document.
--
-- A report is stored as the exact UTF-8 bytes of its canonical JSON payload together with their SHA-256, so the
-- same report can be downloaded again (JSON or re-rendered PDF/A) and verified later: the checksum printed in
-- the PDF, returned with the JSON and recorded in the audit trail must equal SHA-256(payload). Reports are
-- never changed or deleted.

create table dms.evidence_report (
    id          uuid primary key,
    tenant_id   uuid        not null references dms.tenant (id),
    document_id uuid        not null,
    -- Payload format version, see modules/audit/README.md (evidence reports).
    format      smallint    not null check (format >= 1),
    created_at  timestamptz not null,
    created_by  text        not null check (length(trim(created_by)) > 0),
    -- The canonical JSON payload as text (not jsonb: jsonb would reorder keys and change the bytes).
    payload     text        not null,
    -- Lowercase hex SHA-256 of the UTF-8 bytes of payload; checked by the database on insert.
    sha256      text        not null check (sha256 ~ '^[0-9a-f]{64}$'),
    check (sha256 = encode(sha256(convert_to(payload, 'UTF8')), 'hex')),
    unique (tenant_id, id),
    foreign key (tenant_id, document_id) references dms.document (tenant_id, id)
);

create index evidence_report_document on dms.evidence_report (tenant_id, document_id, created_at);

create trigger evidence_report_immutable
    before update or delete on dms.evidence_report
    for each row execute function dms.reject_modification();

alter table dms.evidence_report enable row level security;
create policy tenant_isolation on dms.evidence_report
    using (tenant_id = dms.current_tenant());

grant select, insert on dms.evidence_report to dms_app;
