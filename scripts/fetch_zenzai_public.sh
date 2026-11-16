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
dest="${ROOT}/app/src/zenzai/assets/zenzai"
mkdir -p "${dest}"
if [[ -n "${pick}" ]]; then
  echo "==> 使うモデル: ${pick}"
  curl -fL --retry 5 -o "${dest}/zenzai-plus-ft-f16.gguf" "https://huggingface.co/${REPO}/resolve/main/${pick}"
else
  # gguf が公開されていないので、公開されている元の重み（safetensors）を
  # llama.cpp 付属の変換ツールで gguf にする（中身の学習結果はそのまま、形式だけ変える）
  echo "==> gguf が無いので safetensors から変換します"
  hf="${ROOT}/third_party/zenzai-hf"
  rm -rf "${hf}"; mkdir -p "${hf}"
  for f in ${files}; do
    case "${f}" in
      *.safetensors|*.json|*.txt|*.model) curl -fsSL --retry 5 -o "${hf}/${f}" "https://huggingface.co/${REPO}/resolve/main/${f}";;
    esac
  done
  ls -la "${hf}"
  python3 -m pip install -q --index-url https://download.pytorch.org/whl/cpu torch
  python3 -m pip install -q numpy sentencepiece safetensors "transformers>=4.45,<5" protobuf
  python3 -m pip install -q "${ROOT}/third_party/zenzai-llama/gguf-py"
  conv="${ROOT}/third_party/zenzai-llama/convert_hf_to_gguf.py"
  # zenz の分かち方（トークナイザ）は変換ツールの一覧に無いので、GPT-2 と同じ扱いにする
  python3 - "${conv}" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
old = 'raise NotImplementedError("BPE pre-tokenizer was not recognized - update get_vocab_base_pre()")'
assert old in s, "convert script layout changed"
s = s.replace(old, 'res = "gpt-2"')
open(p, "w", encoding="utf-8").write(s)
PY
  python3 "${conv}" "${hf}" --outtype q8_0 --outfile "${dest}/zenzai-plus-ft-f16.gguf"
  # 元の設定では「文の終わり」の印が別の番号になっているので、正しい </s> に直す
  eos="$(python3 -c 'import json,sys; v=json.load(open(sys.argv[1])); print(v["</s>"])' "${hf}/vocab.json")"
  PYTHONPATH="${ROOT}/third_party/zenzai-llama/gguf-py" python3 \
    "${ROOT}/third_party/zenzai-llama/gguf-py/gguf/scripts/gguf_set_metadata.py" --force \
    "${dest}/zenzai-plus-ft-f16.gguf" tokenizer.ggml.eos_token_id "${eos}"
  pick="model.safetensors（q8_0 に変換）"
fi
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
ライセンス: CC BY-SA 4.0。このアプリでは重みを gguf 形式（8bit量子化）に変換して同梱しています（元: ${pick}）。
CARD
echo "    ${size} bytes, sha256 ${sha}"
