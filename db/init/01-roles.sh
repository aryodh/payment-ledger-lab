#!/bin/sh
# Runs as the Postgres superuser the first time a data directory is initialized.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
	-v migrator_password="$LEDGER_MIGRATOR_DB_PASSWORD" \
	-v app_password="$LEDGER_APP_DB_PASSWORD" <<'SQL'
CREATE ROLE ledger_migrator LOGIN PASSWORD :'migrator_password';
CREATE ROLE ledger_app LOGIN PASSWORD :'app_password';

REVOKE ALL ON DATABASE ledger FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE ledger TO ledger_migrator;
GRANT CONNECT ON DATABASE ledger TO ledger_app;
SQL
