#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
sh scripts/maven.sh -B -ntp verify dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/package
cp target/bridgetrust-2.0.0.jar target/package/
mkdir -p target/package/modules
for BT_FX in target/package/javafx-*-mac*.jar target/package/javafx-*-linux*.jar target/package/javafx-*-win*.jar; do
  [ -f "$BT_FX" ] || continue
  mv "$BT_FX" target/package/modules/
done
case "$(uname -s)" in
  Darwin) BT_ICON=packaging/bridgetrust.icns ;;
  MINGW*|MSYS*|CYGWIN*) BT_ICON=packaging/bridgetrust.ico ;;
  *) BT_ICON=src/main/resources/bridgetrust/ui/app-icon.png ;;
esac
if [ -x "$PWD/.tools/jdk/bin/jpackage" ]; then
  BT_JPACKAGE="$PWD/.tools/jdk/bin/jpackage"
elif [ -n "${JAVA_HOME:-}" ]; then
  BT_JPACKAGE="$JAVA_HOME/bin/jpackage"
else
  BT_JPACKAGE=jpackage
fi
"$BT_JPACKAGE" --type app-image --name BridgeTrust --app-version 2.0.0 \
  --vendor BridgeTrust --input target/package --dest "${BT_PACKAGE_DEST:-target/distribution}" --icon "$BT_ICON" \
  --main-jar bridgetrust-2.0.0.jar --main-class bridgetrust.Main \
  --java-options '--module-path=$APPDIR/modules' --java-options '--add-modules=javafx.controls' \
  --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.sql,java.xml,jdk.unsupported,java.scripting
echo "Application image created in ${BT_PACKAGE_DEST:-target/distribution}. Build separately on each target OS."
