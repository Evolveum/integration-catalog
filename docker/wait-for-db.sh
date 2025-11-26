#!/bin/sh
# wait-for-db.sh
# Waits until the Postgres server is ready to accept connections

set -e

host="$1"
shift
cmd="$@"

until PGPASSWORD=$POSTGRES_PASSWORD psql -h "$host" -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c '\q'; do
  echo "Waiting for Postgres at $host..."
  sleep 2
done

exec $cmd
