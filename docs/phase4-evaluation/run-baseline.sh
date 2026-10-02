#!/bin/sh
set -eu
# Run from repository root; output/classes/dependency paths stay in ignored runtime data.
snapshot=${1:-docs/phase4-evaluation/catalog-h2-snapshot-v1.json}
out=${2:-apps/backend/var/phase4-evaluation/baseline.json}
fresh_or_base=${3:-}
repo=$(pwd)
case "$repo" in *MegaGraph*) ;; *) echo 'Run from MegaGraph Shop repository root.' >&2; exit 2 ;; esac
runtime="$repo/apps/backend/var/phase4-evaluation"
mkdir -p "$runtime/classes"
mvn -q -s .mvn/settings.xml -f apps/backend/pom.xml -DskipTests compile dependency:build-classpath -Dmdep.outputFile="$runtime/classpath.txt"
classpath="$repo/apps/backend/target/classes:$(cat "$runtime/classpath.txt")"
javac -encoding UTF-8 -cp "$classpath" -d "$runtime/classes" docs/phase4-evaluation/CatalogBaseline.java
if [ -n "$fresh_or_base" ]; then
  java -cp "$runtime/classes:$classpath" CatalogBaseline "$snapshot" docs/phase4-evaluation/queries-v1.json "$out" "$fresh_or_base"
else
  java -cp "$runtime/classes:$classpath" CatalogBaseline "$snapshot" docs/phase4-evaluation/queries-v1.json "$out"
fi
