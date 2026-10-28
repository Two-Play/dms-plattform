-- Local development only: login user for the application, member of the dms_app role created by
-- migration V2. Runs once when the PostgreSQL volume is initialised.
do
$$
begin
    if not exists (select 1 from pg_roles where rolname = 'dms_app') then
        create role dms_app nologin nobypassrls;
    end if;
    if not exists (select 1 from pg_roles where rolname = 'dms_app_user') then
        create role dms_app_user login nobypassrls password 'dms-app-local' in role dms_app;
    end if;
end
$$;
