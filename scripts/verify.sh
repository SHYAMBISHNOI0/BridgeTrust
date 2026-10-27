#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
sh scripts/maven.sh -B -ntp verify
sh scripts/maven.sh -B -ntp javafx:run -Djavafx.args=--benchmark
if [ "${1:-}" = "--ui" ]; then
  BT_DATA_DIR="$PWD/target/ui-audit" sh scripts/maven.sh -B -ntp javafx:run -Djavafx.args=--smoke
  test -f target/ui-smoke.txt
  cat target/ui-smoke.txt
fi
