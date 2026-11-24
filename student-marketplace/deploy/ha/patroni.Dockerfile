# PostgreSQL 16 + Patroni, used for both nodes of the HA pair.
#
# Patroni watches the PostgreSQL process and the etcd leader key. When the primary
# disappears it promotes the standby; when the old primary comes back it rejoins as a
# streaming replica (pg_rewind is enabled).
FROM postgres:16-bookworm

ARG PATRONI_VERSION=4.0.4

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      python3 \
      python3-pip \
      python3-psycopg2 \
      curl \
 && pip3 install --no-cache-dir --break-system-packages "patroni[etcd3]==${PATRONI_VERSION}" \
 && rm -rf /var/lib/apt/lists/* /root/.cache

COPY patroni.yml /etc/patroni.yml
COPY post-bootstrap.sh /scripts/post-bootstrap.sh
COPY backup.sh /scripts/backup.sh

# The official postgres image creates the postgres user (uid 999). Running Patroni as
# postgres also makes the named volumes inherit the right ownership on first start.
RUN chmod 0755 /scripts/post-bootstrap.sh /scripts/backup.sh \
 && mkdir -p /data/postgresql \
 && chmod 0700 /data/postgresql \
 && chown -R postgres:postgres /data /scripts /etc/patroni.yml

USER postgres

# Per-node values (name, connect addresses, passwords) come from PATRONI_* variables.
ENTRYPOINT ["patroni", "/etc/patroni.yml"]
