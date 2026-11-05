drop index concurrently if exists dms.tenant_name_test;

create index concurrently tenant_name_test on dms.tenant (name);
