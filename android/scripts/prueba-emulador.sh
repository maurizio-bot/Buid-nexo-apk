#!/usr/bin/env bash
# Prueba Ultra Yo en un emulador Android, sin el teléfono de Maurizio (lo lanza GitHub Actions).
# Instala la app y las pruebas, da los permisos, ejecuta EmuladorTest (abre la app, recorre los
# ajustes, lanza órdenes directas sin el modelo grande) y después 400 toques al azar (monkey).
# Deja capturas, respuestas y el logcat en build/prueba-emulador para subirlos como artefacto.
#
# Lo que el emulador NO comprueba: GPU/NPU del S24 (modelo, imágenes), micrófono real, Bluetooth,
# ni el oído/voz rápidos (sus librerías solo existen para el procesador arm64 del teléfono).
set -uo pipefail

cd "$(dirname "$0")/.."
PKG=com.ultrayo.app
OUT=build/prueba-emulador
mkdir -p "$OUT"
fail=0

adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent 82
adb shell wm dismiss-keyguard

adb install -r -g app/build/outputs/apk/debug/app-debug.apk || exit 1
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1
for p in RECORD_AUDIO READ_CONTACTS CALL_PHONE SEND_SMS READ_CALENDAR READ_SMS READ_CALL_LOG POST_NOTIFICATIONS \
         READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_AUDIO; do
  adb shell pm grant $PKG android.permission.$p 2>/dev/null || true
done
adb logcat -c

echo "== Pruebas de la app en el emulador =="
adb shell am instrument -w -r -e class $PKG.EmuladorTest $PKG.test/androidx.test.runner.AndroidJUnitRunner | tee "$OUT/pruebas.txt"
if ! grep -q "^OK (" "$OUT/pruebas.txt"; then
  echo "::error::Fallaron pruebas en el emulador (mira pruebas.txt y logcat.txt en el artefacto)"
  fail=1
fi

echo "== 400 toques al azar dentro de la app =="
adb shell monkey -p $PKG --pct-syskeys 0 --pct-appswitch 0 --throttle 150 -s 42 -v 400 > "$OUT/monkey.txt" 2>&1
if grep -q "// CRASH: $PKG\|ANR in $PKG" "$OUT/monkey.txt"; then
  echo "::error::La app se cerró con toques al azar (mira monkey.txt)"
  fail=1
fi

adb logcat -d > "$OUT/logcat.txt"
if grep -A2 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "Process: $PKG"; then
  echo "::error::Hubo un cierre de la app (FATAL EXCEPTION en logcat.txt)"
  grep -A 30 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -80
  fail=1
fi
adb pull /sdcard/Android/data/$PKG/files/prueba "$OUT/" >/dev/null 2>&1 || true
adb shell run-as $PKG cat files/registro-errores.txt > "$OUT/registro-errores.txt" 2>/dev/null || true
echo "Resultados en $OUT"
exit $fail
