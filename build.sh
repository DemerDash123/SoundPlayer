#!/usr/bin/env bash
# Gradle-free build of SoundPlayer using only aapt, javac, dx (dalvik-exchange), zipalign and apksigner.
# Usage: ANDROID_JAR=/path/to/android-34.jar ./build.sh [--release]
# On Debian/Ubuntu: apt install aapt dalvik-exchange zipalign apksigner openjdk-17-jdk
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR="${ANDROID_JAR:-${ANDROID_HOME:-$HOME/Android/Sdk}/platforms/android-34/android.jar}"
[ -f "$ANDROID_JAR" ] || { echo "android.jar not found: set ANDROID_JAR"; exit 1; }
DX="${DX:-$(command -v dalvik-exchange || command -v dx || true)}"
[ -n "$DX" ] || { echo "dx/dalvik-exchange not found"; exit 1; }

APP=app/src/main
OUT=build-local
PKG=com.demerdash.soundplayer
VERSION_NAME=$(grep -m1 "versionName" app/build.gradle | sed -E "s/.*'(.*)'.*/\1/")
VERSION_CODE=$(grep -m1 "versionCode" app/build.gradle | sed -E "s/[^0-9]*([0-9]+).*/\1/")
MIN_SDK=$(grep -m1 "minSdk " app/build.gradle | sed -E "s/[^0-9]*([0-9]+).*/\1/")
TARGET_SDK=$(grep -m1 "targetSdk " app/build.gradle | sed -E "s/[^0-9]*([0-9]+).*/\1/")

quiet() { grep -v -E "^Picked up (JAVA_TOOL_OPTIONS|_JAVA_OPTIONS)" || true; }

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes"

# The Gradle manifest has no package attribute (AGP uses namespace); add it for aapt.
sed -e "0,/<manifest /s//<manifest package=\"$PKG\" android:versionCode=\"$VERSION_CODE\" android:versionName=\"$VERSION_NAME\" /" \
    "$APP/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"

echo "[1/5] aapt: compiling resources (v$VERSION_NAME, minSdk $MIN_SDK, targetSdk $TARGET_SDK)"
aapt package -f -m --no-version-vectors \
    -J "$OUT/gen" -M "$OUT/AndroidManifest.xml" -S "$APP/res" -I "$ANDROID_JAR" \
    --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    -F "$OUT/resources.apk"

echo "[2/5] javac"
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
    -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | quiet

echo "[3/5] dx"
"$DX" --dex --min-sdk-version="$MIN_SDK" --output="$OUT/classes.dex" "$OUT/classes" 2>&1 | quiet

echo "[4/5] package + zipalign"
cp "$OUT/resources.apk" "$OUT/unsigned.apk"
(cd "$OUT" && aapt add -k unsigned.apk classes.dex > /dev/null)
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[5/5] sign"
KS="${KEYSTORE:-$HOME/.soundplayer/release.jks}"
if [ ! -f "$KS" ]; then
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -storepass "${KS_PASS:-soundplayer}" -keypass "${KS_PASS:-soundplayer}" \
        -alias soundplayer -keyalg RSA -keysize 3072 -validity 12000 \
        -dname "CN=SoundPlayer, O=DemerDash123, C=OM" 2>&1 | quiet
fi
apksigner sign --ks "$KS" --ks-pass "pass:${KS_PASS:-soundplayer}" --ks-key-alias soundplayer \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUT/SoundPlayer-v$VERSION_NAME.apk" "$OUT/aligned.apk" 2>&1 | quiet
apksigner verify "$OUT/SoundPlayer-v$VERSION_NAME.apk" 2>&1 | quiet
echo "OK -> $OUT/SoundPlayer-v$VERSION_NAME.apk ($(du -h "$OUT/SoundPlayer-v$VERSION_NAME.apk" | cut -f1))"
