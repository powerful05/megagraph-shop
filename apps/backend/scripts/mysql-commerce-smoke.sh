#!/usr/bin/env bash
set -euo pipefail

: "${MYSQL_COMMERCE_URL:?Set MYSQL_COMMERCE_URL to an empty dedicated MySQL 8.4 schema}"
: "${MYSQL_COMMERCE_USERNAME:?Set MYSQL_COMMERCE_USERNAME}"
: "${MYSQL_COMMERCE_PASSWORD:?Set MYSQL_COMMERCE_PASSWORD}"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

exec mvn \
  -s "${repo_root}/.mvn/settings.xml" \
  -f "${repo_root}/apps/backend/pom.xml" \
  -Dtest=MySqlCommerceSmokeTest \
  test
