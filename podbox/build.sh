#!/bin/bash
# PodBox build: runs inside WSL (archlinux).
# Usage (from Windows Git-Bash): MSYS_NO_PATHCONV=1 wsl -d archlinux -e bash "/mnt/f/!Code2/PodBox/podbox/build.sh" [zip]
# Native part: tools/configure + make. APK: Gradle (android/build.gradle).
set -e
export ANDROID_SDK_PATH=~/android/sdk
export ANDROID_HOME=$ANDROID_SDK_PATH
# NDK r27 (clang), 64 bit (arm64-v8a) by default.
#   32 bit:          ANDROID_ABI=armeabi PODBOX_BUILD=~/podbox/build-clang
#   old GCC 4.9:     ANDROID_ABI=armeabi ANDROID_NDK_PATH=~/android/android-ndk-r16b PODBOX_BUILD=~/podbox/build
#   old APK build:   PODBOX_APK=make (android.make + apksigner instead of Gradle)
export ANDROID_NDK_PATH=${ANDROID_NDK_PATH:-~/android/sdk/ndk/27.2.12479018}
export ANDROID_ABI=${ANDROID_ABI:-arm64-v8a}
SRC="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$SRC/podbox.apk"
BUILD=${PODBOX_BUILD:-~/podbox/build-arm64}
BT=$ANDROID_SDK_PATH/build-tools/30.0.3
KS=~/podbox/podbox.keystore

mkdir -p "$BUILD" && cd "$BUILD"
# package id, see tools/configure (paths) and android/build.gradle
export PODBOX_APP_ID=${PODBOX_APP_ID:-com.blueshark.player}
if [ ! -f Makefile ] || ! grep -q "/data/data/$PODBOX_APP_ID/" autoconf.h 2>/dev/null; then
    "$SRC/tools/configure" --target=201 --lcdwidth=480 --lcdheight=640 --type=N
fi

make -j"$(nproc)" > make.log 2>&1 || { grep -E "error|Error" make.log | head -30; exit 1; }
if [ "$1" = "zip" ] || [ ! -f rockbox.zip ]; then
    make zip > zip.log 2>&1 || { tail -20 zip.log; exit 1; }
fi

[ -f "$KS" ] || keytool -genkeypair -alias podbox -keystore "$KS" -storepass android \
    -keypass android -validity 10000 -keyalg RSA -keysize 2048 -dname "CN=PodBox"

if [ "$PODBOX_APK" = "make" ]; then
    make apk > apk.log 2>&1 || { tail -30 apk.log; exit 1; }
    # Android 12 rejects the MD5/SHA1 v1 signature from android.make -> re-sign
    "$BT/zipalign" -f 4 bin/_rockbox.apk podbox-aligned.apk
    "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out podbox.apk podbox-aligned.apk
    APK=podbox.apk
else
    # copy librockbox.so, codecs and libmisc.so (= rockbox.zip) to libs/<abi>
    make libs > libs.log 2>&1 || { tail -30 libs.log; exit 1; }
    VERSION=$(git -C "$SRC" -c safe.directory='*' rev-parse --short HEAD 2>/dev/null || echo 1.0)
    "$SRC/android/gradlew" -p "$SRC/android" -q --project-cache-dir "$BUILD/gradle-cache" \
        -Ppodbox.build="$BUILD" -Ppodbox.keystore="$KS" -Ppodbox.version="$VERSION" -Ppodbox.appid="$PODBOX_APP_ID" \
        assembleDebug > gradle.log 2>&1 || { tail -40 gradle.log; exit 1; }
    APK=gradle/outputs/apk/debug/rockbox-debug.apk
fi
cp "$APK" "$OUT"
echo "APK: $OUT"
