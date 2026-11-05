create table dms.mixed_test (id integer primary key);

create index concurrently tenant_name_test on dms.tenant (name);
