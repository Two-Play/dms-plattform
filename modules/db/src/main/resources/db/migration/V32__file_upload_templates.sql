-- Upload templates of document classes (DMS-127, ADR 0049).
--
-- A file document is always created from a released template version (ADR 0042): the template supplies the class
-- version, its metadata form and its number range; the file document's content is its uploaded file. To upload a
-- finished file as a document of a class without a template chosen first, the platform keeps one template per
-- class, created on demand: its versions have empty structured content and no Word file, are released when they
-- are created (by the platform, not by a second person: there is no content to check) and bind the latest
-- version of the class. They are not listed in the template catalog nor in the designers' list.
--
-- file_upload_class_id marks such a template with its class; at most one per class and tenant. dms.template stays
-- insert-only (template_guard).

alter table dms.template
    add column file_upload_class_id uuid;

alter table dms.template
    add constraint template_file_upload_class
        foreign key (tenant_id, file_upload_class_id) references dms.document_class (tenant_id, id);

create unique index template_file_upload_class_once on dms.template (tenant_id, file_upload_class_id)
    where file_upload_class_id is not null;
