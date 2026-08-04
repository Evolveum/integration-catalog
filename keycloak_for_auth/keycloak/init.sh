#!/bin/bash

echo "Starting Keycloak..."
/opt/keycloak/bin/kc.sh "$@" &
PID=$!

sleep 10
until /opt/keycloak/bin/kcadm.sh config credentials \
    --server http://localhost:${KC_HTTP_PORT:-8080} \
    --realm master \
    --user "$KC_BOOTSTRAP_ADMIN_USERNAME" \
    --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null 2>&1
do
    echo "Waiting for Keycloak..."
    sleep 2
done

echo "Keycloak started."

wait $PID
