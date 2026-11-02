#!/usr/bin/env bash
# Trae el motor de voz rápida (sherpa-onnx): reconocimiento de voz en streaming y voz neural,
# todo dentro del teléfono. Solo las librerías nativas para arm64 (el S24); el código Kotlin que
# las llama está copiado en app/src/main/java/com/k2fsa/sherpa/onnx (misma versión).
# Los modelos de voz (unos 150 MB) no van en el APK: los descarga la app la primera vez.
set -euo pipefail

cd "$(dirname "$0")/.."
VERSION=1.13.8
DEST=app/src/main/jniLibs/arm64-v8a
if [ -f "$DEST/libsherpa-onnx-jni.so" ] && [ -f "build/.sherpa-$VERSION" ]; then
  echo "Motor de voz ya está."; exit 0
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
curl -fsSL --retry 4 -o "$TMP/sherpa.tar.bz2" \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/sherpa-onnx-v$VERSION-android.tar.bz2"
echo "Huella del motor de voz: $(sha256sum "$TMP/sherpa.tar.bz2" | cut -d' ' -f1)"
tar xjf "$TMP/sherpa.tar.bz2" -C "$TMP"

mkdir -p "$DEST"
for lib in libsherpa-onnx-jni.so libonnxruntime.so; do
  found=$(find "$TMP" -path "*arm64-v8a*" -name "$lib" | head -n1)
  [ -n "$found" ] || { echo "No encuentro $lib en el paquete de sherpa-onnx" >&2; exit 1; }
  cp "$found" "$DEST/$lib"
done
mkdir -p build && touch "build/.sherpa-$VERSION"
echo "Motor de voz listo (sherpa-onnx $VERSION)."
