#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
mkdir -p build/classes
javac -encoding UTF-8 -d build/classes src/org/umletgen/UmletJavaGenerator.java
jar --create --file umlet-java-generator.jar \
  --main-class org.umletgen.UmletJavaGenerator -C build/classes .
echo "Erstellt: umlet-java-generator.jar"
