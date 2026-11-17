#!/usr/bin/env bash
# Installs the built jar, sources, javadoc and POM into an empty local Maven repository, compiles a
# clean project against it with the JDK on the PATH (or JAVA_HOME), and runs it against the mock.
#
#   release/install-test/java/run.sh <folder with medisync-X.Y.Z.jar and pom.xml> <X.Y.Z>
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
FILES="$(cd "$1" && pwd)"
VERSION="$2"
MVNW="$HERE/../../../java/mvnw"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
# Only the built files and their dependencies from Maven Central: no stale copy of the SDK.
REPO="$WORK/m2"
"$MVNW" -B -q -ntp -Dmaven.repo.local="$REPO" install:install-file \
  -Dfile="$FILES/medisync-$VERSION.jar" -DpomFile="$FILES/pom.xml" \
  -Dsources="$FILES/medisync-$VERSION-sources.jar" -Djavadoc="$FILES/medisync-$VERSION-javadoc.jar"
cp -r "$HERE/pom.xml" "$HERE/src" "$WORK/"
cd "$WORK"
"$MVNW" -B -q -ntp -Dmaven.repo.local="$REPO" -Dmedisync.version="$VERSION" compile
CP="$("$MVNW" -B -q -ntp -Dmaven.repo.local="$REPO" -Dmedisync.version="$VERSION" dependency:build-classpath -Dmdep.outputFile=/dev/stdout)"
"$HERE/../with-mock.sh" sh -c 'java -cp "target/classes:$1" demo.Main "$MOCK_URL" "$2"' _ "$CP" "$VERSION"
