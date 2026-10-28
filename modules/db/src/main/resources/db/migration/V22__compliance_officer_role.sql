-- Compliance cockpit (US-15.1, DMS-59, ADR 0036): the business role "Compliance-Beauftragter" (business concept 2.2)
-- can be assigned. Compliance officers see overdue reviews and acknowledgement rates of the documents in their
-- scope and, for escalation with a stated purpose, who has not acknowledged a version yet.
--
-- The cockpit computes its figures on request from the existing tables (document versions, review dates,
-- acknowledgements, role assignments); it adds no table.

alter table dms.role_assignment drop constraint role_assignment_role_check;
alter table dms.role_assignment
    add constraint role_assignment_role_check check (role in ('Reader', 'Author', 'Owner', 'Reviewer', 'Approver',
                                                              'TemplateDesigner', 'RecordsManager', 'Auditor',
                                                              'TenantAdmin', 'ComplianceOfficer'));
