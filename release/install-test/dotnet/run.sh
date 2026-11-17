#!/usr/bin/env bash
# Puts the built .nupkg into a local feed, builds a net8.0 app and a netstandard2.0 library against
# it in a clean folder with its own package cache, and runs the app against the mock.
#
#   release/install-test/dotnet/run.sh <folder with MediSync.X.Y.Z.nupkg> <X.Y.Z>
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PACKAGES="$(cd "$1" && pwd)"
VERSION="$2"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp -r "$HERE/app" "$HERE/lib" "$HERE/nuget.config" "$WORK/"
mkdir "$WORK/feed"
cp "$PACKAGES/MediSync.$VERSION.nupkg" "$WORK/feed/"
cd "$WORK"
# Its own package cache: no stale MediSync from an earlier build.
export NUGET_PACKAGES="$WORK/packages"
dotnet build app -c Release -p:MediSyncVersion="$VERSION" -nologo -v q
"$HERE/../with-mock.sh" sh -c 'dotnet app/bin/Release/net8.0/app.dll "$MOCK_URL" "$1"' _ "$VERSION"
