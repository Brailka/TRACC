#!/usr/bin/env bash
# Скачивает нативные компоненты: ядро Xray (libv2ray.aar) и движок AmneziaWG (libwg-go.so из релиза amneziawg-android).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
XRAY_LIB_VERSION="${XRAY_LIB_VERSION:-v26.10.21}"
AWG_VERSION="${AWG_VERSION:-v3.1.20260814}"

echo "Ядро Xray ($XRAY_LIB_VERSION)"
mkdir -p "$ROOT/app/libs"
gh release download "$XRAY_LIB_VERSION" -R 2dust/AndroidLibXrayLite -p 'libv2ray.aar' -D "$ROOT/app/libs" --clobber

echo "Геофайлы для APK (урезанные)"
WORK="$(mktemp -d)"
unzip -q -o "$ROOT/app/libs/libv2ray.aar" 'assets/*' -d "$WORK"
mkdir -p "$ROOT/app/src/main/assets"
python3 "$ROOT/scripts/trim-geo.py" "$WORK/assets" "$ROOT/app/src/main/assets"

echo "AmneziaWG ($AWG_VERSION)"
gh release download "$AWG_VERSION" -R amnezia-vpn/amneziawg-android -p '*.apk' -D "$WORK" --clobber
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  unzip -q -o -j "$WORK"/*.apk "lib/$abi/libwg-go.so" -d "$ROOT/app/src/main/jniLibs/$abi"
done
echo "Проверка контрольных сумм (scripts/natives.sha256)"
(cd "$ROOT/app/libs" && grep 'libv2ray.aar' "$ROOT/scripts/natives.sha256" | sha256sum -c -)
(cd "$ROOT/app/src/main/jniLibs" && grep 'libwg-go.so' "$ROOT/scripts/natives.sha256" | sha256sum -c -)
echo "Готово"
