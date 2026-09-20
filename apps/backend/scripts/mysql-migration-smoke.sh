#!/usr/bin/env bash
set -euo pipefail

: "${MYSQL_SMOKE_URL:?Set MYSQL_SMOKE_URL to an empty dedicated MySQL 8.4 schema}"
: "${MYSQL_SMOKE_USERNAME:?Set MYSQL_SMOKE_USERNAME}"
: "${MYSQL_SMOKE_PASSWORD:?Set MYSQL_SMOKE_PASSWORD}"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

exec mvn \
  -s "${repo_root}/.mvn/settings.xml" \
  -f "${repo_root}/apps/backend/pom.xml" \
  -Dtest=MySqlMigrationSmokeTest \
  test
