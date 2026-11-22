#!/usr/bin/env sh
set -eu
if [ "$#" -lt 1 ]; then
  echo "Verwendung: generate-umlet.sh <Java-Quellordner> [Diagrammname]"
  exit 1
fi
java -jar "$(dirname "$0")/umlet-java-generator.jar" "$@"
