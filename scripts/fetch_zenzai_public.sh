#!/usr/bin/env bash
#
# 公開モデル zenz-v2.5（Miwa-Keita/zenz-v2.5-small, CC BY-SA 4.0）と、
# それを動かす llama.cpp（MIT）を取ってきて、APKに入れる準備をする。
# GitHub Actions（組み立て用サーバー）でだけ動かす。スマホのアプリは通信しない。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="Miwa-Keita/zenz-v2.5-small"
LLAMA_TAG="b5001"

echo "==> llama.cpp ${LLAMA_TAG}"
rm -rf "${ROOT}/third_party/zenzai-llama"
git clone -q --depth 1 --branch "${LLAMA_TAG}" https://github.com/ggml-org/llama.cpp.git "${ROOT}/third_party/zenzai-llama"

echo "==> ${REPO} のファイル一覧"
files="$(curl -fsSL "https://huggingface.co/api/models/${REPO}" | python3 -c 'import json,sys; print("\n".join(s["rfilename"] for s in json.load(sys.stdin)["siblings"]))')"
echo "${files}"
pick=""
for pat in 'Q5_K_M' 'Q6_K' 'Q8_0' 'f16' ''; do
  pick="$(echo "${files}" | grep -i '\.gguf$' | grep -i -- "${pat}" | head -1 || true)"
  [[ -n "${pick}" ]] && break
done
if [[ -z "${pick}" ]]; then echo "error: gguf が見つからない" >&2; exit 1; fi
echo "==> 使うモデル: ${pick}"

dest="${ROOT}/app/src/zenzai/assets/zenzai"
mkdir -p "${dest}"
curl -fL --retry 5 -o "${dest}/zenzai-plus-ft-f16.gguf" "https://huggingface.co/${REPO}/resolve/main/${pick}"
head -c 4 "${dest}/zenzai-plus-ft-f16.gguf" | grep -q GGUF
size="$(stat -c %s "${dest}/zenzai-plus-ft-f16.gguf")"
sha="$(sha256sum "${dest}/zenzai-plus-ft-f16.gguf" | cut -d' ' -f1)"
cat > "${dest}/model.json" <<JSON
{
  "file": "zenzai-plus-ft-f16.gguf",
  "source": "${REPO}/${pick}",
  "sha256": "${sha}",
  "size": ${size},
  "format": "zenz-v2",
  "license": "CC-BY-SA-4.0"
}
JSON
cp "${ROOT}/third_party/zenzai-llama/LICENSE" "${dest}/llama-LICENSE.txt"
cat > "${dest}/MODEL_CARD.md" <<CARD
# zenz-v2.5-small
かな漢字変換用の言語モデル。作者: Miwa-Keita（https://huggingface.co/${REPO}）
ライセンス: CC BY-SA 4.0。このアプリでは改変せずに同梱しています（ファイル: ${pick}）。
CARD
echo "    ${size} bytes, sha256 ${sha}"
