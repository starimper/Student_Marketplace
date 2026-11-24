#!/bin/sh
# Runs once, on the node that initialises the PostgreSQL cluster.
# Patroni passes a libpq connection URL as $1 and sets PGPASSFILE for the superuser.
#
# The application role/database live in PostgreSQL itself, so they are copied to the
# standby by streaming replication just like every other change.
set -eu

: "${DB_NAME:?DB_NAME is required}"
: "${DB_USER:?DB_USER is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"

psql "$1" \
  --set=ON_ERROR_STOP=1 \
  --set=db_name="$DB_NAME" \
  --set=db_user="$DB_USER" \
  --set=db_password="$DB_PASSWORD" <<'SQL'
CREATE ROLE :"db_user" LOGIN PASSWORD :'db_password' CREATEDB;
CREATE DATABASE :"db_name" OWNER :"db_user";
SQL

echo "Created role '${DB_USER}' and database '${DB_NAME}'."
