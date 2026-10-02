#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$repo_root"
guide_config="${APP_GUIDE_CONFIG:-$repo_root/.env.local}"
if [[ ! -f "$guide_config" ]]; then
  echo "Missing local guide configuration: $guide_config" >&2
  exit 1
fi
set -a
source "$guide_config"
set +a
: "${GUIDE_MODEL_API_KEY:?Set GUIDE_MODEL_API_KEY in the local configuration}"
: "${GUIDE_MODEL_NAME:?Set GUIDE_MODEL_NAME in the local configuration}"
export SPRING_PROFILES_ACTIVE=local,guide
if [[ "${GUIDE_TEST_SUPPLY:-false}" == "true" ]]; then
  export SPRING_PROFILES_ACTIVE=local,guide,supply-demo
  export APP_TEST_SUPPLY_ASSET_DIR="$repo_root/apps/shopper-web/public/demo-products"
fi
if [[ "${GUIDE_EXPANDED_SUPPLY:-false}" == "true" ]]; then
  if [[ "${GUIDE_TEST_SUPPLY:-false}" != "true" ]]; then
    echo "Expanded fixtures require GUIDE_TEST_SUPPLY=true" >&2
    exit 1
  fi
  export SPRING_PROFILES_ACTIVE=local,guide,supply-demo,supply-expanded
  export SPRING_DATASOURCE_URL='jdbc:h2:file:./var/db/guide-expanded;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;AUTO_SERVER=TRUE'
  export APP_MEDIA_UPLOAD_DIR=./var/guide-expanded/uploads
  export SERVER_SERVLET_SESSION_COOKIE_NAME=GUIDE_EXPANDED_SESSION
fi
export SERVER_ADDRESS=127.0.0.1
if [[ "${GUIDE_EXPANDED_SUPPLY:-false}" == "true" ]]; then
  export SERVER_PORT="${GUIDE_SERVER_PORT:-8082}"
else
  export SERVER_PORT="${GUIDE_SERVER_PORT:-8081}"
fi
exec mvn -s .mvn/settings.xml -f apps/backend/pom.xml spring-boot:run
