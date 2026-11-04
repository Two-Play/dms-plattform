-- Retention rules of document classes and documents (DMS-133, addendum to ADR 0054).
--
-- 1. dms.class_retention_rule: the retention rule (dms.retention_rule, V33) a document class assigns to its
--    documents; inherited by subclasses without an own assignment. Records managers maintain it (audited by the
--    application); removing the assignment of a class deletes its row, so its subclasses inherit again. Like the
--    acknowledgement settings (V29) it is a setting next to the class, not part of the insert-only class
--    versions: changing it neither creates a profile version nor needs the class administrator.
-- 2. dms.document_retention_rule: the rule a records manager set for one document instead of its class's rule;
--    it applies to the open draft and every later version until it is removed (audited by the application).
--
-- The platform sets the core field retentionRule of drafts and new versions from them (the document's rule first,
-- then the class's); the frozen metadata snapshot of approved versions keeps the rule that applied on approval.

create table dms.class_retention_rule (
    tenant_id  uuid        not null references dms.tenant (id),
    class_id   uuid        not null,
    rule_key   text        not null,
    updated_at timestamptz not null default now(),
    updated_by text        not null check (length(trim(updated_by)) > 0),
    primary key (tenant_id, class_id),
    foreign key (tenant_id, class_id) references dms.document_class (tenant_id, id),
    -- Rules are never deleted (V33), so an assignment always names an existing rule.
    foreign key (tenant_id, rule_key) references dms.retention_rule (tenant_id, key)
);

alter table dms.class_retention_rule enable row level security;
create policy tenant_isolation on dms.class_retention_rule
    using (tenant_id = dms.current_tenant());

grant select, insert, update, delete on dms.class_retention_rule to dms_app;

create table dms.document_retention_rule (
    tenant_id   uuid        not null references dms.tenant (id),
    document_id uuid        not null,
    rule_key    text        not null,
    updated_at  timestamptz not null default now(),
    updated_by  text        not null check (length(trim(updated_by)) > 0),
    primary key (tenant_id, document_id),
    foreign key (tenant_id, document_id) references dms.document (tenant_id, id),
    foreign key (tenant_id, rule_key) references dms.retention_rule (tenant_id, key)
);

alter table dms.document_retention_rule enable row level security;
create policy tenant_isolation on dms.document_retention_rule
    using (tenant_id = dms.current_tenant());

grant select, insert, update, delete on dms.document_retention_rule to dms_app;
