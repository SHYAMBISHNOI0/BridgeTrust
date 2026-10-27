#!/bin/sh
# Uses the ignored development toolchain when available; otherwise standard installations.
set -eu
cd "$(dirname "$0")/.."
if [ -x "$PWD/.tools/jdk/bin/java" ]; then
  JAVA_HOME="$PWD/.tools/jdk"
  export JAVA_HOME
fi
if [ -x "$PWD/.tools/apache-maven-3.9.9/bin/mvn" ]; then
  exec "$PWD/.tools/apache-maven-3.9.9/bin/mvn" -Dmaven.repo.local="$PWD/.tools/m2" "$@"
fi
if ! command -v mvn >/dev/null 2>&1; then
  echo "Install JDK 21+ and Maven 3.9+, then run again. See README.md." >&2
  exit 1
fi
exec mvn "$@"
