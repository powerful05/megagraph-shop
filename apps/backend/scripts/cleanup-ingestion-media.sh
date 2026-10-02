#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
jar=target/megagraph-shop-backend-0.1.0-SNAPSHOT.jar
if [ ! -f "$jar" ]; then
  printf '%s\n' 'Build the backend package first with the repository Maven settings.' >&2
  exit 1
fi
# Dedicated main reads protected MEDIA_CLEANUP_DB_URL/USERNAME/PASSWORD and
# MEDIA_CLEANUP_UPLOAD_DIR from environment, with no Spring Boot startup.
# Use a maintenance window; files <24h and all DB references are preserved.
exec java -Dloader.main=com.megagraph.shop.ingestion.MediaCleanup -cp "$jar" \
  org.springframework.boot.loader.launch.PropertiesLauncher
