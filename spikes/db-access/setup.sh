#!/usr/bin/env bash
# (Re)creates the spike database and the non-superuser app role.
set -euo pipefail
C=dms-plattform-db-1
cd "$(dirname "$0")"
docker exec "$C" dropdb -U dms --if-exists spike_db_access
docker exec "$C" psql -U dms -d dms -v ON_ERROR_STOP=1 -q -c "
  do \$\$ begin
    if not exists (select from pg_roles where rolname = 'spike_db_access_app') then
      create role spike_db_access_app login password 'spike' nosuperuser nobypassrls;
    end if;
  end \$\$;"
docker exec "$C" createdb -U dms spike_db_access
docker exec -i "$C" psql -U dms -d spike_db_access -v ON_ERROR_STOP=1 -q < schema.sql
echo "spike_db_access ready"
