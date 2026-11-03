#!/usr/bin/env bash
# Trae el "sentido" de las palabras para la memoria de Ultra: un modelo de embeddings estáticos
# multilingüe (sentence-transformers/static-similarity-mrl-multilingual-v1, Apache-2.0, sin registro).
# Sabe que "coche" y "vehículo" o "perro" y "can" se parecen, en español y en 50 idiomas más.
# No usa librerías nativas: es una tabla de vectores por trozo de palabra. Se recorta a 256
# dimensiones (el modelo está entrenado para eso, "Matryoshka") y se guarda en int8: unos 28 MB
# dentro del APK. Así funciona sin internet, al instante y también en el emulador.
set -euo pipefail

cd "$(dirname "$0")/.."
REPO=sentence-transformers/static-similarity-mrl-multilingual-v1
DEST=app/src/main/assets/sentido
VERSION=1
if [ -f "$DEST/vectores.bin" ] && [ -f "$DEST/vocab.txt" ] && [ -f "build/.sentido-$VERSION" ]; then
  echo "Modelo de sentido ya está."; exit 0
fi

CACHE=${SENTIDO_CACHE:-build/sentido-descarga}
mkdir -p "$CACHE" "$DEST"
fetch() { # fetch <nombre> : prueba en la raíz y en 0_StaticEmbedding
  [ -s "$CACHE/$1" ] && return 0
  for p in "0_StaticEmbedding/$1" "$1"; do
    if curl -fsSL --retry 4 -o "$CACHE/$1" "https://huggingface.co/$REPO/resolve/main/$p"; then return 0; fi
  done
  echo "No pude descargar $1 de $REPO" >&2; return 1
}
fetch tokenizer.json
fetch model.safetensors
echo "Huella del modelo de sentido: $(sha256sum "$CACHE/model.safetensors" | cut -d' ' -f1)"

python3 -m pip install --quiet numpy >/dev/null 2>&1 || true
python3 - "$CACHE" "$DEST" <<'PY'
import json, struct, sys
import numpy as np
cache, dest = sys.argv[1], sys.argv[2]
DIMS = 256

with open(f"{cache}/model.safetensors", "rb") as f:
    n = struct.unpack("<Q", f.read(8))[0]
    header = json.loads(f.read(n))
    tensors = {k: v for k, v in header.items() if k != "__metadata__"}
    print("Tensores:", {k: (v["dtype"], v["shape"]) for k, v in tensors.items()})
    name, info = max(tensors.items(), key=lambda kv: kv[1]["shape"][0] * (kv[1]["shape"][1] if len(kv[1]["shape"]) > 1 else 1))
    dtype = {"F32": np.float32, "F16": np.float16, "BF16": None}[info["dtype"]]
    start, end = info["data_offsets"]
    f.seek(8 + n + start)
    raw = f.read(end - start)
if dtype is None:
    w = (np.frombuffer(raw, dtype=np.uint16).astype(np.uint32) << 16).view(np.float32)
else:
    w = np.frombuffer(raw, dtype=dtype).astype(np.float32)
rows, cols = info["shape"]
w = w.reshape(rows, cols)[:, :DIMS]
scale = np.abs(w).max(axis=1) / 127.0
scale[scale == 0] = 1.0
q = np.clip(np.round(w / scale[:, None]), -127, 127).astype(np.int8)

tok = json.load(open(f"{cache}/tokenizer.json", encoding="utf-8"))
model = tok["model"]
vocab = model["vocab"]
print("Tokenizador:", model.get("type"), "vocabulario", len(vocab), "filas", rows)
assert model.get("type") == "WordPiece", model.get("type")
assert len(vocab) <= rows, (len(vocab), rows)
words = [""] * rows
for t, i in vocab.items():
    words[i] = t
norm = tok.get("normalizer") or {}
lower = bool(norm.get("lowercase", True))
strip = norm.get("strip_accents")
strip = lower if strip is None else bool(strip)

with open(f"{dest}/vectores.bin", "wb") as out:
    out.write(b"SNT1")
    out.write(struct.pack("<ii", rows, DIMS))
    out.write(scale.astype("<f4").tobytes())
    out.write(q.tobytes())
with open(f"{dest}/vocab.txt", "w", encoding="utf-8") as out:
    for t in words:
        out.write(t.replace("\n", " ") + "\n")
json.dump({
    "lowercase": lower, "strip_accents": strip,
    "prefix": model.get("continuing_subword_prefix", "##"),
    "unk": model.get("unk_token", "[UNK]"),
    "max_chars": model.get("max_input_chars_per_word", 100),
    "fuente": "sentence-transformers/static-similarity-mrl-multilingual-v1 (Apache-2.0)",
}, open(f"{dest}/sentido.json", "w"), ensure_ascii=False)
print(f"Sentido listo: {rows} trozos x {DIMS} dimensiones")
PY
mkdir -p build && touch "build/.sentido-$VERSION"
