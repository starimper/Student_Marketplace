#!/bin/sh
# Daily logical backup of the current Patroni primary.
#
# Why not dump from a fixed host: after a failover pg1 is no longer the primary (and may
# be down entirely). This script asks Patroni on each node which one is the leader
# (GET /primary returns 200 only on the primary) and dumps from that node, so the backup
# keeps running across failovers with no manual change.
#
# Restore: gunzip -c marketplace-YYYYmmdd-HHMMSS.sql.gz | psql -U marketplace -d marketplace
set -eu

: "${DB_NAME:?DB_NAME is required}"
: "${DB_USER:?DB_USER is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"

BACKUP_DIR="${BACKUP_DIR:-/backups}"
BACKUP_KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
PATRONI_HOSTS="${PATRONI_HOSTS:-pg1 pg2}"
BACKUP_INTERVAL_SECONDS="${BACKUP_INTERVAL_SECONDS:-86400}"

export PGPASSWORD="$DB_PASSWORD"

find_primary() {
    for host in $PATRONI_HOSTS; do
        if curl -fsS --max-time 3 "http://${host}:8008/primary" >/dev/null 2>&1; then
            echo "$host"
            return 0
        fi
    done
    return 1
}

backup_once() {
    primary=$(find_primary) || {
        echo "backup: no Patroni primary available, will retry in ${BACKUP_INTERVAL_SECONDS}s"
        return 1
    }

    stamp=$(date +%Y%m%d-%H%M%S)
    file="${BACKUP_DIR}/${DB_NAME}-${stamp}.sql.gz"
    raw="${file%.gz}.tmp"

    # Dump to a plain file first: piping pg_dump into gzip would hide a pg_dump failure
    # behind gzip's exit status and leave a truncated "successful" backup.
    if pg_dump --host="$primary" --username="$DB_USER" --dbname="$DB_NAME" \
               --no-owner --clean --if-exists > "$raw"; then
        gzip -c "$raw" > "$file" && rm -f "$raw"
        echo "backup: wrote $file from $primary"
    else
        rm -f "$raw" "$file"
        echo "backup: pg_dump failed on $primary"
        return 1
    fi

    find "$BACKUP_DIR" -name "${DB_NAME}-*.sql.gz" -mtime +"$BACKUP_KEEP_DAYS" -delete
}

mkdir -p "$BACKUP_DIR"
echo "backup: dumping ${DB_NAME} every ${BACKUP_INTERVAL_SECONDS}s, keeping ${BACKUP_KEEP_DAYS} days"

while true; do
    if backup_once; then
        sleep "$BACKUP_INTERVAL_SECONDS"
    else
        # Database not ready yet or a transient failure: retry soon, do not skip a whole day.
        sleep "${BACKUP_RETRY_SECONDS:-60}"
    fi
done
