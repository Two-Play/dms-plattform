-- Archive rendition before approval (DMS-44, ADR 0025).
--
-- The last approving vote no longer approves the version directly: it requests the PDF/A rendition (job queue,
-- ADR 0010) and the case waits in status 'Rendering' until the rendering job has stored and attached the
-- rendition and completed the approval. The version stays 'InApproval' meanwhile. No new table: the job is a
-- row in dms.job, the rendition reference lives in dms.document_version (ADR 0021), renderer and validation
-- details in the audit trail.

alter table dms.workflow_case drop constraint workflow_case_status_check;
alter table dms.workflow_case
    add constraint workflow_case_status_check check (status in ('Running', 'Returned', 'Rendering', 'Approved'));
