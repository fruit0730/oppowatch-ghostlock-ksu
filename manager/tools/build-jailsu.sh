#!/usr/bin/env bash
# Build the armv7 root-shell bridge used as app/src/main/jniLibs/armeabi-v7a/libksud.so
set -e
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

NDK=${KSUW_NDK:-/g/ndk/android-ndk-r29}
TC="$NDK/toolchains/llvm/prebuilt/windows-x86_64"
CC="$TC/bin/armv7a-linux-androideabi30-clang"
SYSROOT_WIN=${KSUW_SYSROOT:-'G:\ndk\android-ndk-r29\toolchains\llvm\prebuilt\windows-x86_64\sysroot'}
OUT="$(dirname "$0")/../app/src/main/jniLibs/armeabi-v7a"

mkdir -p "$OUT"
"$CC" --target=armv7a-linux-androideabi30 "--sysroot=$SYSROOT_WIN" \
  -O2 -Wall -Wextra -fPIE -pie \
  "$(dirname "$0")/jailsu.c" -o "$OUT/libksud.so"
"$TC/bin/llvm-strip" "$OUT/libksud.so" 2>/dev/null || true
ls -l "$OUT/libksud.so"
file "$OUT/libksud.so" 2>/dev/null || true
