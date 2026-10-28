-- Index for the impact analysis before a template release (US-01.2, DMS-50, DMS-98).
--
-- DocumentService.templateUsage counts the document versions on the earlier versions of a template and lists the
-- open ones (where template_id = ? and template_version < ?). Without an index each analysis scans all document
-- versions of the tenant; the index makes it a range scan over the versions of that template.

create index document_version_template on dms.document_version (template_id, template_version);
