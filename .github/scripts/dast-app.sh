#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Charlie Genova and contributors
#
# Starts or stops the image under DAST (WP-28 part c) the way deploy/ runs it, on the host network beside the compose
# services (decision Q1: the built image with the prod security settings):
#   1. the migration Job: profiles prod,migrate as the schema owner, which applies the migrations and the grants and
#      exits (deploy/jobs/migrate);
#   2. the web Deployment: profile prod as the runtime user TRAILKEEP_APP, which owns nothing and has only its grants;
#      read-only root filesystem, tmpfs where the Deployment mounts emptyDirs, the clamd CA where the ConfigMap points,
#      and the secrets read by configtree from files (deploy/base/deployment.yaml).
# postgresql is the vendor profile an overlay adds. The local profile is never active: its two seed beans are its only
# difference from prod beyond connection wiring, and DastSessionIT seeds instead. Every value is a dev-only one from
# compose.yaml and the dev realm, except two generated for this run and never printed: the audit HMAC key and the
# password that lets the runtime user log in to this throwaway database (compose creates it without a login).
# Usage: dast-app.sh start <image> | dast-app.sh stop
set -euo pipefail

name=trailkeep-dast
work="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/trailkeep-dast"

# Non-secret wiring, as the ConfigMap would carry it (deploy/base/configmap.yaml). Mail is off: the prod profile
# requires STARTTLS, which Mailpit does not offer; mail stays queued in NOTIFICATION_OUTBOX.
wiring() { # <database user>
  cat <<JSON
{
  "spring": {
    "datasource": {"url": "jdbc:postgresql://localhost:5432/trailkeep", "username": "$1"},
    "mail": {"host": "localhost", "port": 1025},
    "ssl": {"bundle": {"pem": {"clamd": {"truststore": {"certificate": "file:/etc/trailkeep/clamav-ca/ca.crt"}}}}}
  },
  "app": {
    "base-url": "http://localhost:8080",
    "mail": {"enabled": false, "from": "Trailkeep (DAST) <trailkeep@example.invalid>"},
    "proxy": {"pattern": "10\\\\.128\\\\.\\\\d+\\\\.\\\\d+"},
    "db": {"schema": "public", "app-user": "trailkeep_app", "purge-user": "trailkeep_purge",
           "import-user": "trailkeep_import"},
    "attachments": {"clamd": {"host": "localhost"}},
    "security": {
      "oidc": {"issuer-uri": "http://localhost:8180/realms/trailkeep-dev", "client-id": "trailkeep"},
      "group-roles": {"trailkeep-compliance-admins": "COMPLIANCE_ADMIN", "trailkeep-system-admins": "SYSTEM_ADMIN"}
    }
  }
}
JSON
}

run() { # <secrets dir> <database user> <profiles> <docker run options...>
  local secrets="$1" user="$2" profiles="$3"
  shift 3
  docker run --network host --read-only \
    --tmpfs /tmp:mode=1777 \
    --volume "${secrets}:/etc/trailkeep/secrets:ro" \
    --volume "${PWD}/.local/clamd-tls/ca.crt:/etc/trailkeep/clamav-ca/ca.crt:ro" \
    --env SPRING_PROFILES_ACTIVE="${profiles}" \
    --env SPRING_CONFIG_IMPORT=configtree:/etc/trailkeep/secrets/ \
    --env SPRING_APPLICATION_JSON="$(wiring "${user}")" \
    "$@"
}

case "${1:-}" in
  start)
    image="$2"
    rm -rf "${work}" && mkdir -p "${work}/owner" "${work}/web"
    printf '%s' 'trailkeep' > "${work}/owner/spring.datasource.password" # compose.yaml's dev-only owner
    openssl rand -hex 24 | tr -d '\n' > "${work}/web/spring.datasource.password"
    openssl rand -base64 32 | tr -d '\n' > "${work}/web/app.audit.hmac-key"
    printf '%s' 'dev-only-client-secret' > "${work}/web/app.security.oidc.client-secret" # the dev realm's
    chmod 0444 "${work}"/owner/* "${work}"/web/*

    # The web pod discovers the provider at start-up; the dev realm is imported a while after Keycloak listens.
    for _ in $(seq 1 90); do
      curl -fsS http://localhost:8180/realms/trailkeep-dev/.well-known/openid-configuration > /dev/null 2>&1 && break
      sleep 2
    done
    docker compose exec -T postgres psql -q -v ON_ERROR_STOP=1 -U trailkeep -d trailkeep \
      -v password="$(cat "${work}/web/spring.datasource.password")" <<'SQL'
ALTER ROLE trailkeep_app LOGIN PASSWORD :'password';
SQL

    echo "Migrating as the schema owner (profiles prod,migrate)."
    run "${work}/owner" trailkeep prod,postgresql,migrate --rm --name "${name}-migrate" "${image}"

    run "${work}/web" trailkeep_app prod,postgresql --detach --name "${name}" \
      --tmpfs /var/lib/trailkeep/uploads:mode=1777 \
      --tmpfs /var/lib/trailkeep/tomcat:mode=1777 \
      "${image}" > /dev/null
    for _ in $(seq 1 90); do
      if curl -fsS http://localhost:8081/actuator/health/readiness > /dev/null 2>&1; then
        echo "The image is ready, as the runtime user."
        exit 0
      fi
      if [ "$(docker inspect --format '{{.State.Running}}' "${name}")" != "true" ]; then
        docker logs "${name}" | tail -n 100
        echo "::error::The image exited during start-up."
        exit 1
      fi
      sleep 2
    done
    docker logs "${name}" | tail -n 100
    echo "::error::The image was not ready after 180 s."
    exit 1
    ;;
  stop)
    mkdir -p target/zap
    docker logs "${name}" > target/zap/app.log 2>&1 || true
    docker rm --force "${name}" > /dev/null 2>&1 || true
    rm -rf "${work}"
    ;;
  *)
    echo "usage: $0 start <image> | stop" >&2
    exit 2
    ;;
esac
