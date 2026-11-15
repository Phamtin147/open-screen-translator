#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "ggml-backend.h"
#include "llama.h"
#include "sampling.h"

#define LOG_TAG "HyMtJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kContextSize = 2048;
constexpr int kBatchSize = 512;
constexpr int kMinThreads = 2;
constexpr int kMaxThreads = 6;

std::mutex g_mutex;
bool g_backend_initialized = false;
std::string g_loaded_model_path;

llama_model *g_model = nullptr;
llama_context *g_context = nullptr;
common_chat_templates_ptr g_chat_templates;
std::atomic<bool> g_cancel_requested{false};

void android_llama_log(ggml_log_level level, const char *text, void * /*user_data*/) {
    if (!text) return;
    int prio = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_INFO) prio = ANDROID_LOG_INFO;
    if (prio >= ANDROID_LOG_WARN) {
        __android_log_print(prio, LOG_TAG, "%s", text);
    }
}

void free_model_locked() {
    g_chat_templates.reset();
    if (g_context) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_loaded_model_path.clear();
}

bool is_valid_utf8(const std::string &str) {
    const auto *bytes = reinterpret_cast<const unsigned char *>(str.c_str());
    while (*bytes != 0x00) {
        int num = 0;
        if ((*bytes & 0x80) == 0x00) num = 1;
        else if ((*bytes & 0xE0) == 0xC0) num = 2;
        else if ((*bytes & 0xF0) == 0xE0) num = 3;
        else if ((*bytes & 0xF8) == 0xF0) num = 4;
        else return false;

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) return false;
            bytes += 1;
        }
    }
    return true;
}

int compute_thread_count() {
    const int cpu_count = static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN));
    if (cpu_count <= 2) return 2;
    return std::max(kMinThreads, std::min(kMaxThreads, cpu_count - 1));
}

bool decode_tokens(llama_context *ctx, common_batch &batch, const llama_tokens &tokens) {
    for (int i = 0; i < static_cast<int>(tokens.size()); i += kBatchSize) {
        const int cur = std::min(static_cast<int>(tokens.size()) - i, kBatchSize);
        batch.clear();
        for (int j = 0; j < cur; ++j) {
            const bool want_logit = (i + j == static_cast<int>(tokens.size()) - 1);
            batch.add(tokens[i + j], i + j, 0, want_logit);
        }
        if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            return false;
        }
    }
    return true;
}

std::string format_hymt_prompt(const std::string &user_prompt) {
    if (g_chat_templates) {
        common_chat_templates_inputs inputs;
        common_chat_msg msg;
        msg.role = "user";
        msg.content = user_prompt;
        inputs.messages = {msg};
        inputs.add_generation_prompt = true;
        inputs.use_jinja = true;
        inputs.enable_thinking = false;
        const auto params = common_chat_templates_apply(g_chat_templates.get(), inputs);
        const std::string formatted = params.prompt.str();
        if (!formatted.empty()) {
            return formatted;
        }
    }
    return std::string("<｜hy_begin▁of▁sentence｜><｜hy_User｜>") + user_prompt + "<｜hy_Assistant｜>";
}

} // namespace

extern "C"
JNIEXPORT void JNICALL
Java_com_openscreentranslator_app_translation_LlamaBridge_nativeInit(
        JNIEnv *env,
        jobject /*thiz*/,
        jstring jnative_lib_dir
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_backend_initialized) {
        return;
    }

    llama_log_set(android_llama_log, nullptr);
    const char *lib_dir = env->GetStringUTFChars(jnative_lib_dir, nullptr);
    if (lib_dir) {
        LOGI("Loading ggml backends from: %s", lib_dir);
        ggml_backend_load_all_from_path(lib_dir);
        env->ReleaseStringUTFChars(jnative_lib_dir, lib_dir);
    }
    llama_backend_init();
    g_backend_initialized = true;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_openscreentranslator_app_translation_LlamaBridge_nativeLoadModel(
        JNIEnv *env,
        jobject /*thiz*/,
        jstring jmodel_path
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_backend_initialized) {
        LOGE("nativeLoadModel called before nativeInit");
        return JNI_FALSE;
    }

    const char *cpath = env->GetStringUTFChars(jmodel_path, nullptr);
    if (!cpath) return JNI_FALSE;
    std::string model_path(cpath);
    env->ReleaseStringUTFChars(jmodel_path, cpath);

    if (g_model && g_context && g_loaded_model_path == model_path) {
        return JNI_TRUE;
    }

    free_model_locked();

    LOGI("Loading GGUF model: %s", model_path.c_str());
    llama_model_params mparams = llama_model_default_params();
    g_model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (!g_model) {
        LOGE("Failed to load GGUF model from %s", model_path.c_str());
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = kContextSize;
    ctx_params.n_batch = kBatchSize;
    ctx_params.n_ubatch = kBatchSize;
    const int threads = compute_thread_count();
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;

    g_context = llama_init_from_model(g_model, ctx_params);
    if (!g_context) {
        LOGE("Failed to initialize llama context");
        free_model_locked();
        return JNI_FALSE;
    }

    g_chat_templates = common_chat_templates_init(g_model, "");
    g_loaded_model_path = model_path;
    LOGI("Model ready (%d threads, ctx=%d)", threads, kContextSize);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_openscreentranslator_app_translation_LlamaBridge_nativeIsModelLoaded(
        JNIEnv * /*env*/,
        jobject /*thiz*/
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_model != nullptr && g_context != nullptr) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_openscreentranslator_app_translation_LlamaBridge_nativeCancel(
        JNIEnv * /*env*/,
        jobject /*thiz*/
) {
    g_cancel_requested.store(true);
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_openscreentranslator_app_translation_LlamaBridge_nativeTranslateBytes(
        JNIEnv *env,
        jobject /*thiz*/,
        jstring jprompt,
        jint max_tokens,
        jfloat temperature
) {
    g_cancel_requested.store(false);
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model || !g_context) {
        LOGE("nativeTranslate called without loaded model");
        return nullptr;
    }

    const char *cprompt = env->GetStringUTFChars(jprompt, nullptr);
    if (!cprompt) return nullptr;
    std::string raw_prompt(cprompt);
    env->ReleaseStringUTFChars(jprompt, cprompt);

    llama_memory_clear(llama_get_memory(g_context), true);

    const std::string formatted = format_hymt_prompt(raw_prompt);
    const std::string bos_str = "<｜hy_begin▁of▁sentence｜>";
    const bool has_bos_prefix = formatted.rfind(bos_str, 0) == 0;

    llama_tokens tokens = common_tokenize(g_context, formatted, !has_bos_prefix, true);
    const int n_ctx = static_cast<int>(llama_n_ctx(g_context));
    if (tokens.empty() || static_cast<int>(tokens.size()) >= n_ctx - 32) {
        LOGW("Prompt tokens (%zu) exceed safe context (%d), trimming", tokens.size(), n_ctx);
        if (static_cast<int>(tokens.size()) >= n_ctx - 32) {
            tokens.resize(n_ctx - 64);
        }
    }

    common_batch batch(g_context);
    if (!decode_tokens(g_context, batch, tokens)) {
        LOGE("Prompt decode failed");
        return nullptr;
    }

    common_params_sampling sparams;
    sparams.temp = temperature;
    sparams.top_k = 20;
    sparams.top_p = 0.6f;
    sparams.min_p = 0.0f;
    sparams.penalty_repeat = 1.05f;
    common_sampler *sampler = common_sampler_init(g_model, sparams);
    if (!sampler) {
        LOGE("Failed to init sampler");
        return nullptr;
    }

    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    std::ostringstream output;
    std::string pending_utf8;

    int cur_pos = static_cast<int>(tokens.size());
    const int max_gen = std::min(static_cast<int>(max_tokens), std::max(32, n_ctx - cur_pos - 4));

    for (int step = 0; step < max_gen; ++step) {
        if (g_cancel_requested.load()) {
            break;
        }

        const llama_token tok = common_sampler_sample(sampler, g_context, -1);
        common_sampler_accept(sampler, tok, true);

        if (llama_vocab_is_eog(vocab, tok)) {
            break;
        }

        const std::string piece = common_token_to_piece(g_context, tok, false);
        pending_utf8 += piece;
        if (is_valid_utf8(pending_utf8)) {
            output << pending_utf8;
            pending_utf8.clear();
        }

        batch.clear();
        batch.add(tok, cur_pos, 0, true);
        cur_pos += 1;
        if (llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            LOGE("Token generation decode failed at step %d", step);
            break;
        }
    }

    common_sampler_free(sampler);
    const std::string result = output.str();
    const jsize len = static_cast<jsize>(result.size());
    jbyteArray arr = env->NewByteArray(len);
    if (arr != nullptr && len > 0) {
        env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte *>(result.data()));
    }
    return arr;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_openscreentranslator_app_translation_LlamaBridge_nativeUnloadModel(
        JNIEnv * /*env*/,
        jobject /*thiz*/
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_model_locked();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_openscreentranslator_app_translation_LlamaBridge_nativeShutdown(
        JNIEnv * /*env*/,
        jobject /*thiz*/
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_model_locked();
    if (g_backend_initialized) {
        llama_backend_free();
        g_backend_initialized = false;
    }
}
