#!/usr/bin/env bash
# Собирает libtraccnet.so для всех ABI в app/src/main/jniLibs (нужны cargo-ndk и ANDROID_NDK_HOME).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/rust/traccnet"
cargo ndk --platform 26 \
  -t arm64-v8a -t armeabi-v7a -t x86_64 -t x86 \
  -o "$ROOT/app/src/main/jniLibs" \
  build --release
find "$ROOT/app/src/main/jniLibs" -name 'libtraccnet.so' -exec ls -la {} \;
