#include "ttt_stub.h"

struct llama_ttt_version { int unused; };

extern "C" {
const char * llama_ttt_last_error() { return "TTT is not available in this build"; }
int32_t llama_ttt_apply(llama_context *, const char *, float) { return 0; }
int32_t llama_ttt_learn_word(llama_context *, const char *, const char *, int32_t, int32_t * steps) {
    if (steps) *steps = 0;
    return 0; // 「覚えきれなかった」扱い。登録語は辞書の候補として使える。
}
llama_ttt_version * llama_ttt_export_version(llama_context *) { return new llama_ttt_version{0}; }
int32_t llama_ttt_import_version(llama_context *, llama_ttt_version *) { return 0; }
void llama_ttt_free_version(llama_ttt_version * v) { delete v; }
int32_t llama_ttt_save_fd(llama_context *, int, uint64_t) { return 0; }
int32_t llama_ttt_load(llama_context *, const char *, uint64_t * cursor) {
    if (cursor) *cursor = 0;
    return 0;
}
}
