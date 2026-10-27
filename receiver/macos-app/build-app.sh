#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RECEIVER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_DIR="$(cd "$RECEIVER_DIR/.." && pwd)"
OUTPUT="${1:-$RECEIVER_DIR/build/OpenBot LAN Audio.app}"
BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/openbot-lanaudio-app.XXXXXX")"
trap 'rm -rf "$BUILD_DIR"' EXIT

APP_DIR="$BUILD_DIR/OpenBot LAN Audio.app"
CONTENTS="$APP_DIR/Contents"
mkdir -p "$CONTENTS/MacOS" "$CONTENTS/Resources"

FRAMEWORKS=(
  -framework AVFoundation -framework AudioToolbox -framework Security
  -framework CoreServices -framework CoreAudio -framework CoreImage -framework AppKit
)
SOURCE="$RECEIVER_DIR/Receiver.swift"
APP_SOURCE="$SCRIPT_DIR/OpenBotLANAudioApp.swift"

xcrun --sdk macosx swiftc -O -parse-as-library -target arm64-apple-macosx12.0 \
  "${FRAMEWORKS[@]}" "$SOURCE" "$APP_SOURCE" -o "$BUILD_DIR/OpenBotLANAudio-arm64"
xcrun --sdk macosx swiftc -O -parse-as-library -target x86_64-apple-macosx12.0 \
  "${FRAMEWORKS[@]}" "$SOURCE" "$APP_SOURCE" -o "$BUILD_DIR/OpenBotLANAudio-x86_64"
xcrun lipo -create "$BUILD_DIR/OpenBotLANAudio-arm64" "$BUILD_DIR/OpenBotLANAudio-x86_64" \
  -output "$CONTENTS/MacOS/OpenBotLANAudio"
chmod 755 "$CONTENTS/MacOS/OpenBotLANAudio"

cat > "$CONTENTS/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleExecutable</key><string>OpenBotLANAudio</string>
  <key>CFBundleIdentifier</key><string>com.openbot.lanaudio.receiverapp</string>
  <key>CFBundleName</key><string>OpenBot LAN Audio</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>12.0</string>
  <key>NSPrincipalClass</key><string>NSApplication</string>
  <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
PLIST

mkdir -p "$(dirname "$OUTPUT")"
if [[ -e "$OUTPUT" ]]; then
  echo "Refusing to overwrite existing app: $OUTPUT" >&2
  exit 2
fi
ditto "$APP_DIR" "$OUTPUT"
codesign --force --deep --sign - "$OUTPUT"
echo "Built: $OUTPUT"
file "$OUTPUT/Contents/MacOS/OpenBotLANAudio"
codesign --verify --deep --strict "$OUTPUT"
