#!/bin/sh
set -eu
cd "$(dirname "$0")"
BT_DATA_DIR="${BT_DATA_DIR:-$PWD/data}"
export BT_DATA_DIR
exec sh scripts/maven.sh compile javafx:run "$@"
