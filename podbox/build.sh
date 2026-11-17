#!/bin/bash
# PodBox build: runs inside WSL (archlinux).
# Usage (from Windows Git-Bash): MSYS_NO_PATHCONV=1 wsl -d archlinux -e bash "/mnt/f/!Code2/PodBox/podbox/build.sh" [zip]
set -e
export ANDROID_SDK_PATH=~/android/sdk
export ANDROID_NDK_PATH=~/android/android-ndk-r16b
SRC="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$SRC/podbox.apk"
BUILD=~/podbox/build
BT=$ANDROID_SDK_PATH/build-tools/30.0.3
KS=~/podbox/podbox.keystore

mkdir -p "$BUILD" && cd "$BUILD"
if [ ! -f Makefile ]; then
    "$SRC/tools/configure" --target=201 --lcdwidth=480 --lcdheight=640 --type=N
fi

make -j"$(nproc)" > make.log 2>&1 || { grep -E "error|Error" make.log | head -30; exit 1; }
if [ "$1" = "zip" ] || [ ! -f rockbox.zip ]; then
    make zip > zip.log 2>&1 || { tail -20 zip.log; exit 1; }
fi
make apk > apk.log 2>&1 || { tail -30 apk.log; exit 1; }

# Android 12 rejects the MD5/SHA1 v1 signature from android.make -> re-sign
[ -f "$KS" ] || keytool -genkeypair -alias podbox -keystore "$KS" -storepass android \
    -keypass android -validity 10000 -keyalg RSA -keysize 2048 -dname "CN=PodBox"
"$BT/zipalign" -f 4 bin/_rockbox.apk podbox-aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out podbox.apk podbox-aligned.apk
cp podbox.apk "$OUT"
echo "APK: $OUT"
