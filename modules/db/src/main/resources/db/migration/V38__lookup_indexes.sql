-- DMS-60: indexes the load test found missing (scripts/loadtest/README.md, ADR 0056).
--
-- Reading a document reads its versions and its workflow cases by document id. Neither table had an index serving
-- `where document_id = ?`: the indexes of dms.document_version on document_id are all partial, so every document
-- page, every row of the document list and every entry of the library scanned the whole table.

-- With updated_at: the document list orders by the latest change of a document's versions.
create index document_version_document on dms.document_version (document_id, updated_at);

create index workflow_case_document on dms.workflow_case (document_id);

-- Read per document by the compliance cockpit, the evidence report and the destruction check.
create index document_acknowledgement_document on dms.document_acknowledgement (document_id);

create index document_review_document on dms.document_review (document_id);

-- What a reader still has to acknowledge: the few valid versions that ask for it, the latest publication first.
create index document_version_acknowledgement_due on dms.document_version (tenant_id, published_at desc)
    where requires_acknowledgement and status in ('Published', 'ReviewDue');

-- Workers claim the jobs of their kinds: without the kind in an index, the regular worker would walk past every queued
-- bulk delivery of a publication.
create index job_due_kind on dms.job (kind, run_at, id) where status in ('queued', 'running');

-- The latest status change of the versions that are or were valid: tells the library whether its copy of the valid
-- versions is current (ADR 0056).
create index document_version_validity_stamp on dms.document_version (tenant_id, status_changed_at)
    where status in ('Published', 'ReviewDue', 'Superseded', 'Withdrawn', 'Archived', 'Destroyed');
