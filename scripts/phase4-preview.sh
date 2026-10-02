#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
mode="${1:-backend}"
mkdir -p apps/backend/var/phase4-evidence
case "$mode" in
  backend)
    profiles=local
    if [[ -n "${DB_URL:-}" ]]; then
      : "${DB_USERNAME:?DB_USERNAME is required}"
      : "${DB_PASSWORD:?DB_PASSWORD is required}"
      profiles=local,mysql
      export SPRING_DATASOURCE_URL="$DB_URL" SPRING_DATASOURCE_USERNAME="$DB_USERNAME" SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD"
    else
      export SPRING_DATASOURCE_URL='jdbc:h2:file:./var/db/phase4;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;AUTO_SERVER=TRUE'
    fi
    if [[ "${PHASE4_TEST_SUPPLY:-true}" == true ]]; then
      profiles="$profiles,supply-demo,supply-expanded"
      export APP_TEST_SUPPLY_ASSET_DIR="$repo_root/apps/shopper-web/public/demo-products"
    fi
    if [[ -n "${APP_GUIDE_CONFIG:-}" ]]; then
      test -f "$APP_GUIDE_CONFIG"
      set -a
      source "$APP_GUIDE_CONFIG"
      set +a
      : "${GUIDE_MODEL_API_KEY:?Guide config needs an API key}"
      : "${GUIDE_MODEL_NAME:?Guide config needs a model}"
      profiles="$profiles,guide"
    fi
    export SPRING_PROFILES_ACTIVE="$profiles" APP_DEV_SEED_ENABLED=true
    export SERVER_ADDRESS=127.0.0.1 SERVER_PORT="${PHASE4_BACKEND_PORT:-8084}"
    export SERVER_SERVLET_SESSION_COOKIE_NAME=PHASE4_SESSION APP_SESSION_COOKIE_SECURE=false
    export APP_MEDIA_UPLOAD_DIR="$repo_root/apps/backend/var/phase4-uploads" TZ=UTC
    exec mvn -s .mvn/settings.xml -f apps/backend/pom.xml spring-boot:run
    ;;
  merchant)
    export MERCHANT_API_UPSTREAM="${MERCHANT_API_UPSTREAM:-http://127.0.0.1:8084}"
    exec pnpm --filter @megagraph/merchant-web exec vite --host 127.0.0.1 --port "${PHASE4_MERCHANT_PORT:-5176}" --strictPort
    ;;
  shopper)
    export NUXT_API_UPSTREAM="${NUXT_API_UPSTREAM:-http://127.0.0.1:8084}"
    exec pnpm --filter @megagraph/shopper-web exec nuxt dev --host 127.0.0.1 --port "${PHASE4_SHOPPER_PORT:-3004}"
    ;;
  *) echo 'Usage: scripts/phase4-preview.sh backend|merchant|shopper' >&2; exit 2 ;;
esac
