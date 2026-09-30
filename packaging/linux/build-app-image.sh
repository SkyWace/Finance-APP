#!/usr/bin/env bash
# Construit une image applicative Linux (ou un .deb) avec jpackage : meme
# configuration que l'installateur Windows, pour la verifier sans Windows.
#   packaging/linux/build-app-image.sh            # app-image
#   packaging/linux/build-app-image.sh deb        # paquet .deb (fakeroot + dpkg requis)
set -euo pipefail
TYPE="${1:-app-image}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TARGET="$ROOT/financeapp-desktop/target"
NAME="$(grep '^app.name=' "$ROOT/financeapp-desktop/src/main/resources/application.properties" | cut -d= -f2 | tr -d '[:space:]')"
VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$ROOT/pom.xml" | head -1 | sed 's/-SNAPSHOT//')"
echo "==> $NAME $VERSION ($TYPE)"

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  (cd "$ROOT" && mvn -B -q package -DskipTests)
fi

rm -rf "$TARGET/jpackage-input" "$TARGET/jpackage-runtime"
mkdir -p "$TARGET/jpackage-input" "$TARGET/installer"
cp "$TARGET/financeapp-desktop.jar" "$TARGET/jpackage-input/"
cp -r "$TARGET/lib" "$TARGET/jpackage-input/"

jlink --add-modules "$(tr -d '[:space:]' < "$ROOT/packaging/jlink-modules.txt")" \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
  --output "$TARGET/jpackage-runtime"

rm -rf "$TARGET/installer/$NAME"
jpackage --type "$TYPE" --name "$NAME" --app-version "$VERSION" --vendor "$NAME" \
  --description "Gestion financiere personnelle locale" \
  --input "$TARGET/jpackage-input" --main-jar financeapp-desktop.jar \
  --main-class com.financeapp.desktop.Launcher \
  --runtime-image "$TARGET/jpackage-runtime" \
  --icon "$ROOT/packaging/linux/financeapp.png" \
  --java-options "-Dfile.encoding=UTF-8" --java-options "-Xmx1g" \
  --dest "$TARGET/installer"
ls -la "$TARGET/installer"
