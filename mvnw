#!/bin/sh
set -e
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
MVN_CMD="mvn"
if [ -x "/d/program2/apache-maven-3.9.0/bin/mvn" ]; then
  MVN_CMD="/d/program2/apache-maven-3.9.0/bin/mvn"
fi
if [ -f "$SCRIPT_DIR/.mvn/settings.xml" ]; then
  exec "$MVN_CMD" -s "$SCRIPT_DIR/.mvn/settings.xml" "$@"
else
  exec "$MVN_CMD" "$@"
fi
