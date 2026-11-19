#!/bin/bash
# PodBox build: runs inside WSL (archlinux) or on Linux (CI).
# Usage (from Windows Git-Bash): MSYS_NO_PATHCONV=1 wsl -d archlinux -e bash "/mnt/c/!Code2/PodBox/scripts/build.sh" [zip]
# Native part: tools/configure + make (NDK r27, arm64-v8a). APK: Gradle (android/build.gradle).
set -e
export ANDROID_SDK_PATH=${ANDROID_SDK_PATH:-~/android/sdk}
export ANDROID_HOME=$ANDROID_SDK_PATH
export ANDROID_NDK_PATH=${ANDROID_NDK_PATH:-~/android/sdk/ndk/27.2.12479018}
export ANDROID_ABI=arm64-v8a
SRC="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$SRC/podbox.apk"
BUILD=${PODBOX_BUILD:-~/podbox/build-arm64}
KS=${PODBOX_KEYSTORE:-~/podbox/podbox.keystore}

mkdir -p "$BUILD" && cd "$BUILD"
# package id, see tools/configure (paths) and android/build.gradle
export PODBOX_APP_ID=${PODBOX_APP_ID:-com.blueshark.player}
# reconfigure when tools/configure changed something the build depends on
# (package id, memory size, libandroid for the display)
if [ ! -f Makefile ] || ! grep -q "/data/data/$PODBOX_APP_ID/" autoconf.h 2>/dev/null \
        || ! grep -q "^export MEMORYSIZE=32$" Makefile \
        || ! grep -q -- "-landroid" Makefile; then
    "$SRC/tools/configure" --target=201 --lcdwidth=480 --lcdheight=640 --type=N
fi

make -j"$(nproc)" > make.log 2>&1 || { grep -E "error|Error" make.log | head -30; exit 1; }
if [ "$1" = "zip" ] || [ ! -f rockbox.zip ]; then
    PODBOX_BUILD="$BUILD" bash "$SRC/theme/mkfonts.sh"
    make zip > zip.log 2>&1 || { tail -20 zip.log; exit 1; }
fi

[ -f "$KS" ] || keytool -genkeypair -alias podbox -keystore "$KS" -storepass android \
    -keypass android -validity 10000 -keyalg RSA -keysize 2048 -dname "CN=PodBox"

# copy librockbox.so, codecs and libmisc.so (= rockbox.zip) to libs/<abi>
make libs > libs.log 2>&1 || { tail -30 libs.log; exit 1; }
# version name: release tag (CI) or "git describe" (e.g. 1.0.0-3-gabc1234), without leading v;
# build.gradle derives versionCode from the leading x.y.z
VERSION=${PODBOX_VERSION:-$(git -C "$SRC" -c safe.directory='*' describe --tags --always 2>/dev/null || echo 1.0)}
VERSION=${VERSION#v}
"$SRC/android/gradlew" -p "$SRC/android" -q --project-cache-dir "$BUILD/gradle-cache" \
    -Ppodbox.build="$BUILD" -Ppodbox.keystore="$KS" -Ppodbox.version="$VERSION" -Ppodbox.appid="$PODBOX_APP_ID" \
    assembleDebug > gradle.log 2>&1 || { tail -40 gradle.log; exit 1; }
APK=gradle/outputs/apk/debug/rockbox-debug.apk
cp "$APK" "$OUT"
echo "APK: $OUT"
