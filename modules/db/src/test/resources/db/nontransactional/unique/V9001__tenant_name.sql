drop index concurrently if exists dms.tenant_name_test;

create unique index concurrently tenant_name_test on dms.tenant (name);
