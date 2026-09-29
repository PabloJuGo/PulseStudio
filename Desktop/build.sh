#!/usr/bin/env bash
# Equivalente de build.bat para macOS/Linux (útil para desarrollo y CI). En Windows usa build.bat.
set -euo pipefail
cd "$(dirname "$0")"
APP_NAME=PulseStudio APP_VERSION=1.0.0 MAIN_CLASS=com.pulsestudio.desktop.PulseStudioApp
MODULES=java.base,java.desktop,java.logging,java.net.http,java.xml,jdk.httpserver,jdk.crypto.ec,jdk.localedata
rm -rf build "dist/$APP_NAME"; mkdir -p build/classes build/input
javac --release 17 -encoding UTF-8 -d build/classes $(find src/main/java -name '*.java')
cp -R src/main/resources/. build/classes/
jar --create --file build/input/pulse-studio.jar --main-class "$MAIN_CLASS" -C build/classes .
if [[ "${1:-}" == "jar" ]]; then echo "OK: build/input/pulse-studio.jar"; exit 0; fi
ICON=packaging/PulseStudio.png; [[ "$(uname)" == Darwin ]] && ICON=packaging/PulseStudio.icns
ICON_ARGS=(); [[ -f "$ICON" ]] && ICON_ARGS=(--icon "$ICON")
jpackage --type app-image --name "$APP_NAME" --app-version "$APP_VERSION" --vendor JMOrdenadores \
  --input build/input --main-jar pulse-studio.jar --main-class "$MAIN_CLASS" "${ICON_ARGS[@]}" \
  --add-modules "$MODULES" --java-options "-Djava.net.useSystemProxies=true -Dfile.encoding=UTF-8 -Xmx1g" --dest dist
echo "OK: dist/$APP_NAME"
