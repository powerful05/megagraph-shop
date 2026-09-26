#!/usr/bin/env bash
set -euo pipefail

: "${MYSQL_UPGRADE_URL:?Set MYSQL_UPGRADE_URL to an empty dedicated MySQL 8.4 schema}"
: "${MYSQL_UPGRADE_USERNAME:?Set MYSQL_UPGRADE_USERNAME}"
: "${MYSQL_UPGRADE_PASSWORD:?Set MYSQL_UPGRADE_PASSWORD}"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

exec mvn \
  -s "${repo_root}/.mvn/settings.xml" \
  -f "${repo_root}/apps/backend/pom.xml" \
  -Dtest=MySqlUpgradeSmokeTest \
  test
