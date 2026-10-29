#!/usr/bin/env bash
# Trae el motor que crea imágenes dentro del teléfono (Stable Diffusion en la NPU del Snapdragon).
# Son binarios ya compilados de Local Dream (github.com/xororz/local-dream, licencia CC BY-NC 4.0,
# uso personal) y las librerías de Qualcomm QNN para el Snapdragon 8 Gen 3 (Hexagon V75).
# Se toman de una versión fija de Off Grid (github.com/alichherawalla/off-grid-mobile), que los
# empaqueta igual, y se comprueba su huella para que nunca cambien sin querer.
set -euo pipefail

cd "$(dirname "$0")/.."
COMMIT=d3feaaecdbc969733bb0092a45d92368d261d34c
BASE="https://raw.githubusercontent.com/alichherawalla/off-grid-mobile/$COMMIT/android/app/src/main"

fetch() { # origen destino sha256
  if [ -f "$2" ] && echo "$3  $2" | sha256sum -c --status; then return; fi
  mkdir -p "$(dirname "$2")"
  curl -fsSL --retry 4 -o "$2.tmp" "$BASE/$1"
  echo "$3  $2.tmp" | sha256sum -c --status || { echo "Huella incorrecta: $1" >&2; rm -f "$2.tmp"; exit 1; }
  mv "$2.tmp" "$2"
}

# El ejecutable va con las librerías nativas: es la única carpeta desde la que Android deja ejecutar.
fetch jniLibs/arm64-v8a/libstable_diffusion_core.so app/src/main/jniLibs/arm64-v8a/libstable_diffusion_core.so 794127b7b6ef26273abbbe9d366ac4d46ef534d5be699a968e7b52f0c1d8879f
fetch assets/qnnlibs/libQnnHtp.so        app/src/main/assets/qnn/libQnnHtp.so        5020af677e221f1673271ac7be32efbf861b152ec71c021bd13827ec94fd10e1
fetch assets/qnnlibs/libQnnSystem.so     app/src/main/assets/qnn/libQnnSystem.so     e31c8e0b6e2d38be46b21fc3ce9c26d8532e8d0623dc7407f4c90248b040d795
fetch assets/qnnlibs/libQnnHtpV75.so     app/src/main/assets/qnn/libQnnHtpV75.so     3714a210ce1848451e5e75eae3d6cbd7fe5653771acbe56ba8bdcd26194f0f53
fetch assets/qnnlibs/libQnnHtpV75Skel.so app/src/main/assets/qnn/libQnnHtpV75Skel.so 95b15f609b7782f67b548db4203e7a1565ee58045deacde3e0a44720818b9a89
fetch assets/qnnlibs/libQnnHtpV75Stub.so app/src/main/assets/qnn/libQnnHtpV75Stub.so f86ed5decd47710491f42affd651a62b12cfc0b2a4665e5931b959a8658f1f2c
echo "Motor de imágenes listo."
