#!/usr/bin/env bash
# Removes the spike database and role.
set -euo pipefail
docker exec dms-plattform-db-1 dropdb -U dms --if-exists spike_db_access
docker exec dms-plattform-db-1 psql -U dms -d dms -q -c "drop role if exists spike_db_access_app"
