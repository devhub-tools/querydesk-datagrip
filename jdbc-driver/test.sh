#!/usr/bin/env bash
# Builds the driver and runs DriverTest against a local stand-in for the statements API.
set -euo pipefail
cd "$(dirname "$0")"

./build.sh
mkdir -p build/test-classes
javac --release 17 -Xlint:all -Werror -cp build/classes -d build/test-classes $(find src/test/java -name '*.java')
java -cp build/querydesk-jdbc.jar:build/test-classes com.querydesk.jdbc.DriverTest
