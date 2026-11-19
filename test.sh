#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT
if [[ -n ${ECJ_JAR:-} ]]; then
    compiler=(java -jar "$ECJ_JAR" -proc:none)
else
    compiler=(javac)
fi
"${compiler[@]}" -d "$build" -source 8 -target 8 src/dev/rollingz/oilusd/Series.java tests/SeriesTest.java
java -cp "$build" dev.rollingz.oilusd.SeriesTest
