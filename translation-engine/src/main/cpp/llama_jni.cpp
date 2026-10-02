#include <jni.h>
#include <llama.h>
#include <ggml-cpu.h>
#include "llama_cpu_policy.h"
#include <dlfcn.h>
#include <android/log.h>
#include <algorithm>
#include <memory>
#include <mutex>
#include <sched.h>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <vector>

namespace {
constexpr int MAX_OUTPUT = 512;
constexpr size_t MAX_PREFIX_BYTES = 32 * 1024 * 1024;
constexpr size_t MAX_PREFIX_TOKENS = 1024;
struct PrefixEntry {
    std::string model_key;
    std::vector<llama_token> tokens;
    std::vector<uint8_t> state;
};
struct PrefixCache {
    std::mutex mutex;
    std::vector<PrefixEntry> entries; // Least recently used first; at most two entries.
    size_t bytes() const {
        size_t total = 0;
        for (const auto &entry : entries) total += entry.state.size();
        return total;
    }
};
using CacheHandle = std::shared_ptr<PrefixCache>;
struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    ggml_threadpool_t threadpool = nullptr;
    std::vector<ggml_threadpool_t> retired_pools;
    decltype(&ggml_threadpool_new) new_threadpool = nullptr;
    decltype(&ggml_threadpool_free) free_threadpool = nullptr;
    cpu_set_t affinity{};
    int max_threads = 1;
    int active_threads = 0;
    int allowed_cpus = 0;
    std::vector<llama_token> prompt;
    CacheHandle cache;
    std::string model_key;
    std::vector<llama_token> prefix, live_prefix;
    bool cache_enabled = true;
    int cached_tokens = 0;
    size_t consumed = 0;
    int generated = 0;
    int max_output = MAX_OUTPUT;
    bool finished = true;
    ~Session() {
        if (sampler) llama_sampler_free(sampler);
        if (ctx) {
            llama_detach_threadpool(ctx);
            llama_free(ctx);
        }
        if (threadpool) free_threadpool(threadpool);
        for (auto pool : retired_pools) free_threadpool(pool);
        if (model) llama_model_free(model);
    }
};
std::once_flag initialized;
// Called at decode boundaries. The CPU backend keeps its own reference until
// the next graph switches pools (and pauses the previous one), even after
// llama_detach_threadpool. Retire old pools only after that decode succeeds.
// Recreate on affinity changes so new workers inherit the current CPU set.
void updateCpuPool(Session &s) {
    cpu_set_t affinity{};
    const bool known = sched_getaffinity(0, sizeof(affinity), &affinity) == 0;
    const int allowed = known ? CPU_COUNT(&affinity) : 0;
    const int threads = ehnz::inferenceThreads(s.max_threads, allowed);
    if (s.threadpool && threads == s.active_threads && CPU_EQUAL(&s.affinity, &affinity)) return;
    auto tp = ggml_threadpool_params_default(threads);
    tp.poll = 0;
    auto replacement = s.new_threadpool(&tp);
    if (!replacement) throw std::runtime_error("Cannot allocate translation threads");
    if (s.threadpool) {
        try { s.retired_pools.push_back(s.threadpool); }
        catch (...) { s.free_threadpool(replacement); throw; }
    }
    s.threadpool = replacement;
    s.affinity = affinity;
    s.active_threads = threads;
    s.allowed_cpus = allowed;
    llama_set_n_threads(s.ctx, threads, threads);
    llama_attach_threadpool(s.ctx, s.threadpool, s.threadpool);
    __android_log_print(ANDROID_LOG_INFO, "NativeLlm", "CPU pool: threads=%d allowed=%d", threads, allowed);
}
void releaseRetiredPools(Session &s) {
    for (auto pool : s.retired_pools) s.free_threadpool(pool);
    s.retired_pools.clear();
}
void fail(JNIEnv *env, const char *message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
std::string utf8(JNIEnv *env, jbyteArray bytes) {
    if (!bytes) throw std::runtime_error("Missing UTF-8 input");
    std::string result(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, result.size(), reinterpret_cast<jbyte *>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Cannot read UTF-8 input");
    if (result.find('\0') != std::string::npos) throw std::runtime_error("Embedded NUL in input");
    return result;
}
Session &session(jlong handle) {
    if (!handle) throw std::runtime_error("Native translator is closed");
    return *reinterpret_cast<Session *>(handle);
}
CacheHandle &prefixCache(jlong handle) {
    if (!handle) throw std::runtime_error("Native prefix cache is closed");
    return *reinterpret_cast<CacheHandle *>(handle);
}
std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::vector<char> &text, int length) {
    const int count = -llama_tokenize(vocab, text.data(), length, nullptr, 0, true, true);
    if (count <= 0) throw std::runtime_error("Tokenization failed");
    std::vector<llama_token> tokens(count);
    if (llama_tokenize(vocab, text.data(), length, tokens.data(), count, true, true) != count)
        throw std::runtime_error("Tokenization failed");
    return tokens;
}
// Snapshot only at the fixed-prefix boundary, before decoding any OCR or output tokens.
// A failed/oversized cache is an optimization miss, never a translation failure.
void savePrefix(Session &s) noexcept {
    try {
        s.live_prefix = s.prefix;
        std::lock_guard<std::mutex> guard(s.cache->mutex);
        for (const auto &entry : s.cache->entries)
            if (entry.model_key == s.model_key && entry.tokens == s.prefix) return;
        const size_t size = llama_state_seq_get_size(s.ctx, 0);
        if (!size || size > MAX_PREFIX_BYTES) return;
        while (!s.cache->entries.empty() &&
               (s.cache->entries.size() >= 2 || s.cache->bytes() + size > MAX_PREFIX_BYTES))
            s.cache->entries.erase(s.cache->entries.begin());
        PrefixEntry entry{s.model_key, s.prefix, std::vector<uint8_t>(size)};
        if (llama_state_seq_get_data(s.ctx, entry.state.data(), size, 0) == size)
            s.cache->entries.push_back(std::move(entry));
    } catch (...) {
        __android_log_print(ANDROID_LOG_WARN, "NativeLlm", "Fixed-prefix snapshot skipped");
    }
}
size_t restorePrefix(Session &s) {
    auto mem = llama_get_memory(s.ctx);
    // Same live context: keep its fixed prefix and remove previous source/output.
    if (!s.prefix.empty() && s.prefix == s.live_prefix &&
        llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(s.prefix.size()), -1)) {
        std::lock_guard<std::mutex> guard(s.cache->mutex);
        auto &entries = s.cache->entries;
        for (size_t i = 0; i < entries.size(); ++i) {
            if (entries[i].model_key == s.model_key && entries[i].tokens == s.prefix) {
                std::rotate(entries.begin() + i, entries.begin() + i + 1, entries.end());
                break;
            }
        }
        return s.prefix.size();
    }
    s.live_prefix.clear();
    llama_memory_clear(mem, true);
    if (s.prefix.empty()) return 0;
    std::lock_guard<std::mutex> guard(s.cache->mutex);
    auto &entries = s.cache->entries;
    for (size_t i = 0; i < entries.size(); ++i) {
        auto &entry = entries[i];
        if (entry.model_key != s.model_key || entry.tokens != s.prefix) continue;
        if (llama_state_seq_set_data(s.ctx, entry.state.data(), entry.state.size(), 0) == entry.state.size()) {
            s.live_prefix = s.prefix;
            // Moving the entry updates LRU without copying its KV buffer.
            std::rotate(entries.begin() + i, entries.begin() + i + 1, entries.end());
            return s.prefix.size();
        }
        entries.erase(entries.begin() + i);
        llama_memory_clear(mem, true);
        break;
    }
    return 0;
}
void initializeBackend() {
    Dl_info library{};
    if (!dladdr(reinterpret_cast<void *>(&initializeBackend), &library) || !library.dli_fname)
        throw std::runtime_error("Cannot locate native translation libraries");
    const std::string path(library.dli_fname);
    const auto directory = path.substr(0, path.find_last_of('/'));
    ggml_backend_load_all_from_path(directory.c_str());
    if (!ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU))
        throw std::runtime_error("No compatible translation CPU backend");
    llama_backend_init();
    __android_log_print(ANDROID_LOG_INFO, "NativeLlm", "%s", llama_print_system_info());
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_li_joye_yakuyomi_engine_NativePrefixCache_createCache(JNIEnv *env, jobject) {
    try { return reinterpret_cast<jlong>(new CacheHandle(std::make_shared<PrefixCache>())); }
    catch (...) { fail(env, "Cannot allocate prefix cache"); }
    return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_li_joye_yakuyomi_engine_NativePrefixCache_retainCache(JNIEnv *env, jobject, jlong handle) {
    try { return reinterpret_cast<jlong>(new CacheHandle(prefixCache(handle))); }
    catch (...) { fail(env, "Cannot retain prefix cache"); }
    return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_li_joye_yakuyomi_engine_NativePrefixCache_sizeBytes(JNIEnv *env, jobject, jlong handle) {
    try {
        auto &cache = prefixCache(handle);
        std::lock_guard<std::mutex> guard(cache->mutex);
        return cache->bytes();
    } catch (const std::exception &e) { fail(env, e.what()); }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_li_joye_yakuyomi_engine_NativePrefixCache_clearCache(JNIEnv *env, jobject, jlong handle) {
    try {
        auto &cache = prefixCache(handle);
        std::lock_guard<std::mutex> guard(cache->mutex);
        cache->entries.clear();
    } catch (const std::exception &e) { fail(env, e.what()); }
}

extern "C" JNIEXPORT void JNICALL
Java_li_joye_yakuyomi_engine_NativePrefixCache_destroyCache(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<CacheHandle *>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_li_joye_yakuyomi_engine_NativePrefixCache_createModel(JNIEnv *env, jobject, jlong cacheHandle,
        jbyteArray path, jint threads) {
    try {
        std::call_once(initialized, initializeBackend);
        auto s = std::make_unique<Session>();
        s->cache = prefixCache(cacheHandle);
        const auto model_path = utf8(env, path);
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        s->model = llama_model_load_from_file(model_path.c_str(), mp);
        if (!s->model) throw std::runtime_error("Cannot load GGUF model (unsupported, damaged or insufficient memory)");
        if (llama_model_has_encoder(s->model)) throw std::runtime_error("Use a decoder-only GGUF instruction model");
        const char *tmpl = llama_model_chat_template(s->model, nullptr);
        llama_chat_message probe{"user", "Translate into English: test"};
        if (!tmpl || llama_chat_apply_template(tmpl, &probe, 1, true, nullptr, 0) <= 0)
            throw std::runtime_error("GGUF chat template is missing or unsupported");
        auto cp = llama_context_default_params();
        cp.n_ctx = std::min(2048, llama_model_n_ctx_train(s->model));
        if (cp.n_ctx <= MAX_OUTPUT) throw std::runtime_error("GGUF context is too small");
        cp.n_batch = 128;
        cp.n_ubatch = 128;
        cp.n_threads = cp.n_threads_batch = std::clamp(static_cast<int>(threads), 1, 4);
        s->max_threads = cp.n_threads;
        s->ctx = llama_init_from_model(s->model, cp);
        if (!s->ctx) throw std::runtime_error("Cannot allocate translation context");
        struct stat info{};
        if (stat(model_path.c_str(), &info)) throw std::runtime_error("Cannot identify GGUF file");
        // Snapshots never leave this process/binary. Bind them to file identity and
        // context configuration; exact templated prefix tokens are checked on every hit.
        s->model_key = model_path + ":" + std::to_string(info.st_dev) + ":" + std::to_string(info.st_ino) +
            ":" + std::to_string(info.st_size) + ":" + std::to_string(info.st_mtim.tv_sec) +
            ":" + std::to_string(info.st_mtim.tv_nsec) + ":" + std::to_string(info.st_ctim.tv_sec) +
            ":" + std::to_string(info.st_ctim.tv_nsec) + ":" + std::to_string(llama_n_ctx(s->ctx)) +
            ":" + std::to_string(cp.n_threads);
        // Reuse the selected backend's pool, adapting it to actual CPU availability.
        auto cpu = ggml_backend_dev_backend_reg(ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU));
        s->new_threadpool = reinterpret_cast<decltype(&ggml_threadpool_new)>(
            ggml_backend_reg_get_proc_address(cpu, "ggml_threadpool_new"));
        s->free_threadpool = reinterpret_cast<decltype(&ggml_threadpool_free)>(
            ggml_backend_reg_get_proc_address(cpu, "ggml_threadpool_free"));
        if (!s->new_threadpool || !s->free_threadpool) throw std::runtime_error("CPU thread pool is unavailable");
        updateCpuPool(*s);
        s->sampler = llama_sampler_init_greedy();
        if (!s->sampler) throw std::runtime_error("Cannot allocate translation sampler");
        return reinterpret_cast<jlong>(s.release());
    } catch (const std::exception &e) { fail(env, e.what()); }
    catch (...) { fail(env, "Native model initialization failed"); }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_li_joye_yakuyomi_engine_NativeLlm_beginMessages(JNIEnv *env, jobject, jlong handle,
        jobjectArray roleBytes, jobjectArray contentBytes, jint maxOutput,
        jbyteArray lastUserPrefix, jboolean cacheEnabled) {
    try {
        auto &s = session(handle);
        s.finished = true;
        s.cached_tokens = 0;
        if (!roleBytes || !contentBytes || env->GetArrayLength(roleBytes) != env->GetArrayLength(contentBytes))
            throw std::runtime_error("Invalid chat messages");
        const int n = env->GetArrayLength(roleBytes);
        if (n < 1 || n > 128) throw std::runtime_error("Invalid chat message count");
        std::vector<std::string> roles(n), contents(n);
        std::vector<llama_chat_message> messages(n);
        size_t total = 0;
        for (int i = 0; i < n; ++i) {
            auto role = static_cast<jbyteArray>(env->GetObjectArrayElement(roleBytes, i));
            auto content = static_cast<jbyteArray>(env->GetObjectArrayElement(contentBytes, i));
            roles[i] = utf8(env, role);
            contents[i] = utf8(env, content);
            env->DeleteLocalRef(role);
            env->DeleteLocalRef(content);
            if (roles[i] != "system" && roles[i] != "user" && roles[i] != "assistant")
                throw std::runtime_error("Unsupported message role");
            total += contents[i].size();
            messages[i] = {roles[i].c_str(), contents[i].c_str()};
        }
        if (total > 65536) return -1;
        const char *tmpl = llama_model_chat_template(s.model, nullptr);
        int length = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, nullptr, 0);
        if (length <= 0) return -2; // Valid user-only models may not support system/few-shot roles.
        std::vector<char> formatted(length + 1);
        length = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, formatted.data(), formatted.size());
        if (length <= 0 || length >= static_cast<int>(formatted.size())) throw std::runtime_error("Chat formatting failed");
        const auto *vocab = llama_model_get_vocab(s.model);
        auto prompt = tokenize(vocab, formatted, length);
        const int count = prompt.size();
        // Preserve the existing context-memory cap. Long pages split only when
        // their actual template/token count cannot fit, never by a fixed region count.
        s.max_output = std::clamp(static_cast<int>(maxOutput), 1, static_cast<int>(llama_n_ctx(s.ctx)) / 2);
        if (count + s.max_output > static_cast<int>(llama_n_ctx(s.ctx))) return -1;
        s.cache_enabled = cacheEnabled;
        s.prefix.clear();
        if (s.cache_enabled && roles.back() == "user") {
            const std::string fixed = lastUserPrefix ? utf8(env, lastUserPrefix) : "";
            if (contents.back().compare(0, fixed.size(), fixed) != 0)
                throw std::runtime_error("Invalid fixed user prefix");
            contents.back() = fixed;
            messages.back().content = contents.back().c_str();
            int fixed_length = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, nullptr, 0);
            if (fixed_length > 0) {
                std::vector<char> fixed_text(fixed_length + 1);
                fixed_length = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true,
                                                        fixed_text.data(), fixed_text.size());
                if (fixed_length > 0 && fixed_length < static_cast<int>(fixed_text.size())) {
                    auto fixed_tokens = tokenize(vocab, fixed_text, fixed_length);
                    size_t common = 0;
                    // Always decode a suffix to refresh logits, including identical requests.
                    while (common < fixed_tokens.size() && common + 1 < prompt.size() &&
                           fixed_tokens[common] == prompt[common]) ++common;
                    if (common <= MAX_PREFIX_TOKENS)
                        s.prefix.assign(prompt.begin(), prompt.begin() + common);
                }
            }
        }
        s.consumed = restorePrefix(s);
        s.cached_tokens = s.consumed;
        s.prompt = std::move(prompt);
        llama_sampler_reset(s.sampler);
        s.generated = 0;
        s.finished = false;
        return count;
    } catch (const std::exception &e) { fail(env, e.what()); }
    catch (...) { fail(env, "Native prompt preparation failed"); }
    return -1;
}

extern "C" JNIEXPORT jint JNICALL
Java_li_joye_yakuyomi_engine_NativeLlm_cachedPromptTokens(JNIEnv *env, jobject, jlong handle) {
    try { return session(handle).cached_tokens; }
    catch (const std::exception &e) { fail(env, e.what()); }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_li_joye_yakuyomi_engine_NativeLlm_completionTokens(JNIEnv *env, jobject, jlong handle) {
    try { return session(handle).generated; }
    catch (const std::exception &e) { fail(env, e.what()); }
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_li_joye_yakuyomi_engine_NativeLlm_systemInfo(JNIEnv *env, jobject, jlong handle) {
    try {
        session(handle);
        return env->NewStringUTF(llama_print_system_info());
    } catch (const std::exception &e) { fail(env, e.what()); }
    return nullptr;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_li_joye_yakuyomi_engine_NativeLlm_cpuState(JNIEnv *env, jobject, jlong handle) {
    try {
        auto &s = session(handle);
        const jint state[] = {s.active_threads, s.allowed_cpus};
        auto result = env->NewIntArray(2);
        if (result) env->SetIntArrayRegion(result, 0, 2, state);
        return result;
    } catch (const std::exception &e) { fail(env, e.what()); }
    return nullptr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_li_joye_yakuyomi_engine_NativeLlm_next(JNIEnv *env, jobject, jlong handle) {
    try {
        auto &s = session(handle);
        if (s.finished) return nullptr;
        updateCpuPool(s);
        if (s.consumed < s.prompt.size()) {
            const size_t boundary = s.consumed < s.prefix.size() ? s.prefix.size() : s.prompt.size();
            // Keep cancellation and CPU-policy changes responsive on restricted cores.
            const int count = std::min<size_t>(s.active_threads == 1 ? 32 : 128, boundary - s.consumed);
            if (llama_decode(s.ctx, llama_batch_get_one(s.prompt.data() + s.consumed, count)) != 0) {
                s.live_prefix.clear();
                s.finished = true;
                throw std::runtime_error("Native prompt decoding failed");
            }
            releaseRetiredPools(s);
            s.consumed += count;
            if (s.cache_enabled && !s.prefix.empty() && s.consumed == s.prefix.size()) savePrefix(s);
            return env->NewByteArray(0);
        }
        auto token = llama_sampler_sample(s.sampler, s.ctx, -1);
        const auto *vocab = llama_model_get_vocab(s.model);
        if (llama_vocab_is_eog(vocab, token)) { s.finished = true; return nullptr; }
        if (s.generated >= s.max_output) {
            s.finished = true;
            env->ThrowNew(env->FindClass("li/joye/yakuyomi/engine/TranslationOutputLimitException"),
                          "Translation was truncated");
            return nullptr;
        }
        std::vector<char> piece(256);
        int length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        if (length < 0) {
            piece.resize(-length);
            length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        }
        if (length < 0) throw std::runtime_error("Cannot decode output token");
        if (llama_decode(s.ctx, llama_batch_get_one(&token, 1)) != 0) {
            s.live_prefix.clear();
            s.finished = true;
            throw std::runtime_error("Native token decoding failed");
        }
        releaseRetiredPools(s);
        ++s.generated;
        auto result = env->NewByteArray(length);
        if (result) env->SetByteArrayRegion(result, 0, length, reinterpret_cast<const jbyte *>(piece.data()));
        return result;
    } catch (const std::exception &e) { fail(env, e.what()); }
    catch (...) { fail(env, "Native translation failed"); }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_li_joye_yakuyomi_engine_NativeLlm_destroy(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Session *>(handle);
}
