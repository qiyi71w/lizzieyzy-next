#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

DATE_TAG="${1:-$(date +%F)}"
APP_VERSION="${2:-2.5.3}"
JAR_PATH="${3:-target/lizzie-yzy2.5.3-shaded.jar}"

JAVA_HOME_DEFAULT="$ROOT_DIR/.tools/jdk-21/jdk-21.0.10.jdk/Contents/Home"
if [[ -d "$JAVA_HOME_DEFAULT" ]]; then
  export JAVA_HOME="$JAVA_HOME_DEFAULT"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if ! command -v jpackage >/dev/null 2>&1; then
  echo "jpackage not found. Please use JDK 14+ with jpackage."
  exit 1
fi

if [[ ! -f "$JAR_PATH" ]]; then
  echo "Jar not found: $JAR_PATH"
  echo "Build first: mvn -DskipTests package"
  exit 1
fi

ARCH="$(uname -m)"
if [[ "$ARCH" == "arm64" ]]; then
  ARCH_TAG="mac-arm64"
else
  ARCH_TAG="mac-amd64"
fi

DIST_DIR="$ROOT_DIR/dist/macos"
INPUT_DIR="$DIST_DIR/input"
APP_IMAGE_DIR="$DIST_DIR/app-image"
DMG_DIR="$DIST_DIR/dmg"
rm -rf "$INPUT_DIR" "$APP_IMAGE_DIR" "$DMG_DIR"
mkdir -p "$INPUT_DIR" "$APP_IMAGE_DIR" "$DMG_DIR"

cp "$JAR_PATH" "$INPUT_DIR/"
cp README.md README_EN.md README_KO.md LICENSE.txt "$INPUT_DIR/"
cp readme_cn.pdf readme_en.pdf "$INPUT_DIR/"

APP_NAME="LizzieYzy Next-FoxUID"
MAIN_JAR="$(basename "$JAR_PATH")"
IDENTIFIER="com.wimi321.lizzieyzy.nextfoxuid"

jpackage \
  --type app-image \
  --name "$APP_NAME" \
  --input "$INPUT_DIR" \
  --main-jar "$MAIN_JAR" \
  --main-class featurecat.lizzie.Lizzie \
  --dest "$APP_IMAGE_DIR" \
  --app-version "$APP_VERSION" \
  --vendor "wimi321" \
  --description "LizzieYzy maintained fork with Fox UID sync fix" \
  --java-options "-Xmx4096m"

jpackage \
  --type dmg \
  --name "$APP_NAME" \
  --input "$INPUT_DIR" \
  --main-jar "$MAIN_JAR" \
  --main-class featurecat.lizzie.Lizzie \
  --dest "$DMG_DIR" \
  --app-version "$APP_VERSION" \
  --vendor "wimi321" \
  --description "LizzieYzy maintained fork with Fox UID sync fix" \
  --mac-package-identifier "$IDENTIFIER" \
  --java-options "-Xmx4096m"

APP_BUNDLE="$APP_IMAGE_DIR/$APP_NAME.app"
APP_ZIP="$ROOT_DIR/dist/release/${DATE_TAG}-${ARCH_TAG}.app.zip"
DMG_FILE="$(ls "$DMG_DIR"/*.dmg | head -n 1)"
FINAL_DMG="$ROOT_DIR/dist/release/${DATE_TAG}-${ARCH_TAG}.dmg"

mkdir -p "$ROOT_DIR/dist/release"
cp "$DMG_FILE" "$FINAL_DMG"
(
  cd "$APP_IMAGE_DIR"
  ditto -c -k --sequesterRsrc --keepParent "$APP_NAME.app" "$APP_ZIP"
)

cat >"$ROOT_DIR/dist/release/${DATE_TAG}-${ARCH_TAG}-install.txt" <<EOF
Package type: unsigned macOS app + dmg
Build architecture: $ARCH
Generated on: $DATE_TAG

Install:
1. Open the dmg and drag app to Applications.
2. First run may be blocked by Gatekeeper, use:
   System Settings -> Privacy & Security -> Open Anyway

Notes:
- This package is unsigned/not notarized.
- For Intel/Apple Silicon dual-native support, build once on each architecture.
EOF

echo "Artifacts:"
ls -lh "$ROOT_DIR/dist/release/${DATE_TAG}-${ARCH_TAG}"*
