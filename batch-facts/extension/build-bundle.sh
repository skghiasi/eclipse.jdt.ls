#!/usr/bin/env bash
# Builds the batch file facts extension as an OSGi bundle jar, against the plugin jars of an unpacked jdtls.
#   batch-facts/extension/build-bundle.sh <jdtls dir> <out jar>
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
jdtls=$1; out=$(realpath -m "$2")
javac=${JAVA_HOME:+$JAVA_HOME/bin/}javac; jar=${JAVA_HOME:+$JAVA_HOME/bin/}jar
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
"$javac" --release 21 -nowarn -cp "$(ls "$jdtls"/plugins/*.jar | tr '\n' ':')" -d "$work/classes" $(find "$here/bundle/src" -name '*.java')
cp "$here/bundle/plugin.xml" "$work/classes/"
"$jar" --create --file "$out" --manifest "$here/bundle/META-INF/MANIFEST.MF" --date 2026-09-03T00:00:00Z -C "$work/classes" .
echo "wrote $out"
