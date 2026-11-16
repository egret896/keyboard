#pragma once
// ふつうの llama.cpp には、学習（TTT）の関数がない。
// KKKeyboard の公開モデル版では学習を使わないので、何もしない関数で置き換える。
// 変換（推論）はそのまま動く。
#include <cstdint>
struct llama_context;
struct llama_ttt_version;
extern "C" {
const char * llama_ttt_last_error();
int32_t llama_ttt_apply(llama_context *, const char *, float);
int32_t llama_ttt_learn_word(llama_context *, const char *, const char *, int32_t, int32_t *);
llama_ttt_version * llama_ttt_export_version(llama_context *);
int32_t llama_ttt_import_version(llama_context *, llama_ttt_version *);
void llama_ttt_free_version(llama_ttt_version *);
int32_t llama_ttt_save_fd(llama_context *, int, uint64_t);
int32_t llama_ttt_load(llama_context *, const char *, uint64_t *);
}
