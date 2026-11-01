#!/usr/bin/env bash
# Builds build/querydesk-jdbc.jar. No dependencies, so plain javac is enough.
set -euo pipefail
cd "$(dirname "$0")"

rm -rf build
mkdir -p build/classes
javac --release 17 -Xlint:all -Werror -d build/classes $(find src/main/java -name '*.java')
cp -R src/main/resources/. build/classes/
jar --create --file build/querydesk-jdbc.jar -C build/classes .
echo "built build/querydesk-jdbc.jar"
