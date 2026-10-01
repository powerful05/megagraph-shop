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
export SERVER_ADDRESS=127.0.0.1
export SERVER_PORT="${GUIDE_SERVER_PORT:-8081}"
exec mvn -s .mvn/settings.xml -f apps/backend/pom.xml spring-boot:run
