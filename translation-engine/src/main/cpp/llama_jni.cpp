#include <jni.h>
#include <llama.h>
#include <ggml-cpu.h>
#include "llama_cpu_policy.h"
#include "translation_chat_template.h"
#include <dlfcn.h>
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <memory>
#include <mutex>
#include <sched.h>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
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
    FILE *model_file = nullptr; // Own the duplicated SAF descriptor until the model is freed.
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    std::unique_ptr<ehnz::TranslationChatTemplate> chat_template;
    bool supports_system = false;
    ggml_threadpool_t threadpool = nullptr;
    std::vector<ggml_threadpool_t> retired_pools;
    decltype(&ggml_threadpool_new) new_threadpool = nullptr;
    decltype(&ggml_threadpool_free) free_threadpool = nullptr;
    cpu_set_t affinity{};
    int max_threads = 1;
    int active_threads = 0;
    int allowed_cpus = 0;
    std::vector<llama_token> prompt;
    std::vector<char> output_piece = std::vector<char>(256);
    std::vector<char> output_chunk;
    CacheHandle cache;
    std::string model_key;
    std::vector<llama_token> prefix, live_prefix;
    bool cache_enabled = true;
    int cached_tokens = 0;
    size_t consumed = 0;
    int generated = 0;
    int max_output = MAX_OUTPUT;
    bool finished = true;
    bool pending_limit = false;
    ~Session() {
        if (sampler) llama_sampler_free(sampler);
        if (ctx) {
            llama_detach_threadpool(ctx);
            llama_free(ctx);
        }
        if (threadpool) free_threadpool(threadpool);
        for (auto pool : retired_pools) free_threadpool(pool);
        if (model) llama_model_free(model);
        if (model_file) std::fclose(model_file);
    }
};
std::once_flag initialized;
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

std::string tokenText(const llama_vocab *vocab, llama_token token) {
    if (token == LLAMA_TOKEN_NULL) return "";
    const char *text = llama_vocab_get_text(vocab, token);
    return text ? text : "";
}

std::vector<llama_token> chatTokens(Session &s, const std::vector<ehnz::ChatMessage> &messages) {
    auto text = s.chat_template->render(messages);
    const auto *vocab = llama_model_get_vocab(s.model);
    // The tokenizer adds configured BOS/EOS. Avoid doubling tokens already emitted by Jinja.
    const auto bos = tokenText(vocab, llama_vocab_bos(vocab));
    const auto eos = tokenText(vocab, llama_vocab_eos(vocab));
    if (llama_vocab_get_add_bos(vocab) && !bos.empty() && text.compare(0, bos.size(), bos) == 0)
        text.erase(0, bos.size());
    if (llama_vocab_get_add_eos(vocab) && !eos.empty() && text.size() >= eos.size() &&
        text.compare(text.size() - eos.size(), eos.size(), eos) == 0)
        text.resize(text.size() - eos.size());
    if (text.empty() || text.size() > 256 * 1024)
        throw std::runtime_error("Invalid formatted translation prompt");
    return tokenize(vocab, std::vector<char>(text.begin(), text.end()), text.size());
}

void configureSampler(Session &s, int profile) {
    if (profile < 0 || profile > 2) throw std::runtime_error("Unknown translation sampling profile");
    auto *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!sampler) throw std::runtime_error("Cannot allocate translation sampler");
    // Penalties -> top-k -> top-p -> temperature -> seeded distribution; no min-p filter.
    if (profile != 0) {
        llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
            llama_vocab_n_tokens(llama_model_get_vocab(s.model)), 64, 1.05f, 0.0f, 0.0f));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(20));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.6f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(profile == 1 ? 0.7f : 0.15f));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    }
    if (s.sampler) llama_sampler_free(s.sampler);
    s.sampler = sampler;
}
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
Java_com_hippo_ehviewer_translation_engine_NativePrefixCache_createCache(JNIEnv *env, jobject) {
    try { return reinterpret_cast<jlong>(new CacheHandle(std::make_shared<PrefixCache>())); }
    catch (...) { fail(env, "Cannot allocate prefix cache"); }
    return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_translation_engine_NativePrefixCache_retainCache(JNIEnv *env, jobject, jlong handle) {
    try { return reinterpret_cast<jlong>(new CacheHandle(prefixCache(handle))); }
    catch (...) { fail(env, "Cannot retain prefix cache"); }
    return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_translation_engine_NativePrefixCache_sizeBytes(JNIEnv *env, jobject, jlong handle) {
    try {
        auto &cache = prefixCache(handle);
        std::lock_guard<std::mutex> guard(cache->mutex);
        return cache->bytes();
    } catch (const std::exception &e) { fail(env, e.what()); }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_translation_engine_NativePrefixCache_clearCache(JNIEnv *env, jobject, jlong handle) {
    try {
        auto &cache = prefixCache(handle);
        std::lock_guard<std::mutex> guard(cache->mutex);
        cache->entries.clear();
    } catch (const std::exception &e) { fail(env, e.what()); }
}

extern "C" JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_translation_engine_NativePrefixCache_destroyCache(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<CacheHandle *>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_translation_engine_NativePrefixCache_createModel(JNIEnv *env, jobject, jlong cacheHandle,
        jbyteArray path, jint threads, jint fileDescriptor) {
    try {
        std::call_once(initialized, initializeBackend);
        auto s = std::make_unique<Session>();
        s->cache = prefixCache(cacheHandle);
        const auto model_path = utf8(env, path);
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        struct stat info{};
        if (fileDescriptor >= 0) {
            const int owned_fd = dup(fileDescriptor);
            if (owned_fd < 0) throw std::runtime_error("Cannot retain external GGUF file");
            s->model_file = fdopen(owned_fd, "rb");
            if (!s->model_file) {
                close(owned_fd);
                throw std::runtime_error("Cannot open external GGUF file");
            }
            if (fstat(owned_fd, &info) || !S_ISREG(info.st_mode) || fseeko(s->model_file, 0, SEEK_SET))
                throw std::runtime_error("External GGUF must support random access; copy it into the app instead");
            s->model = llama_model_load_from_file_ptr(s->model_file, mp);
        } else {
            if (stat(model_path.c_str(), &info)) throw std::runtime_error("Cannot identify GGUF file");
            s->model = llama_model_load_from_file(model_path.c_str(), mp);
        }
        if (!s->model) throw std::runtime_error("Cannot load GGUF model (unsupported, damaged or insufficient memory)");
        if (llama_model_has_encoder(s->model)) throw std::runtime_error("Use a decoder-only GGUF instruction model");
        const char *tmpl = llama_model_chat_template(s->model, nullptr);
        if (!tmpl || !*tmpl) throw std::runtime_error("GGUF chat template is missing");
        const auto *vocab = llama_model_get_vocab(s->model);
        s->chat_template = std::make_unique<ehnz::TranslationChatTemplate>(tmpl,
            tokenText(vocab, llama_vocab_bos(vocab)), tokenText(vocab, llama_vocab_eos(vocab)),
            [name = std::string(tmpl)](const std::vector<ehnz::ChatMessage> &input) {
                std::vector<llama_chat_message> chat;
                for (const auto &message : input) chat.push_back({message.role.c_str(), message.content.c_str()});
                int length = llama_chat_apply_template(name.c_str(), chat.data(), chat.size(), true, nullptr, 0);
                if (length <= 0) throw std::runtime_error("Unsupported GGUF chat template name");
                std::vector<char> text(length + 1);
                length = llama_chat_apply_template(name.c_str(), chat.data(), chat.size(), true, text.data(), text.size());
                if (length <= 0 || length >= static_cast<int>(text.size()))
                    throw std::runtime_error("GGUF chat formatting failed");
                return std::string(text.data(), length);
            });
        s->supports_system = s->chat_template->supportsSystem();
        auto cp = llama_context_default_params();
        cp.n_ctx = std::min(2048, llama_model_n_ctx_train(s->model));
        if (cp.n_ctx <= MAX_OUTPUT) throw std::runtime_error("GGUF context is too small");
        cp.n_batch = 128;
        cp.n_ubatch = 128;
        cp.n_threads = cp.n_threads_batch = std::clamp(static_cast<int>(threads), 1, 4);
        s->max_threads = cp.n_threads;
        s->ctx = llama_init_from_model(s->model, cp);
        if (!s->ctx) throw std::runtime_error("Cannot allocate translation context");
        s->model_key = model_path + ":" + std::to_string(info.st_dev) + ":" + std::to_string(info.st_ino) +
            ":" + std::to_string(info.st_size) + ":" + std::to_string(info.st_mtim.tv_sec) +
            ":" + std::to_string(info.st_mtim.tv_nsec) + ":" + std::to_string(info.st_ctim.tv_sec) +
            ":" + std::to_string(info.st_ctim.tv_nsec) + ":" + std::to_string(llama_n_ctx(s->ctx)) +
            ":" + std::to_string(cp.n_threads);
        auto cpu = ggml_backend_dev_backend_reg(ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU));
        s->new_threadpool = reinterpret_cast<decltype(&ggml_threadpool_new)>(
            ggml_backend_reg_get_proc_address(cpu, "ggml_threadpool_new"));
        s->free_threadpool = reinterpret_cast<decltype(&ggml_threadpool_free)>(
            ggml_backend_reg_get_proc_address(cpu, "ggml_threadpool_free"));
        if (!s->new_threadpool || !s->free_threadpool) throw std::runtime_error("CPU thread pool is unavailable");
        updateCpuPool(*s);
        configureSampler(*s, 0);
        return reinterpret_cast<jlong>(s.release());
    } catch (const std::exception &e) { fail(env, e.what()); }
    catch (...) { fail(env, "Native model initialization failed"); }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_translation_engine_NativeLlm_beginMessages(JNIEnv *env, jobject, jlong handle,
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
        std::vector<ehnz::ChatMessage> messages(n);
        size_t total = 0;
        for (int i = 0; i < n; ++i) {
            auto role = static_cast<jbyteArray>(env->GetObjectArrayElement(roleBytes, i));
            auto content = static_cast<jbyteArray>(env->GetObjectArrayElement(contentBytes, i));
            messages[i] = {utf8(env, role), utf8(env, content)};
            env->DeleteLocalRef(role);
            env->DeleteLocalRef(content);
            if (messages[i].role != "system" && messages[i].role != "user" && messages[i].role != "assistant")
                throw std::runtime_error("Unsupported message role");
            if (messages[i].role == "system" && !s.supports_system) return -2;
            total += messages[i].content.size();
        }
        if (total > 65536) return -1;
        auto prompt = chatTokens(s, messages);
        const int count = prompt.size();
        s.max_output = std::clamp(static_cast<int>(maxOutput), 1, static_cast<int>(llama_n_ctx(s.ctx)) / 2);
        if (count + s.max_output > static_cast<int>(llama_n_ctx(s.ctx))) return -1;
        s.cache_enabled = cacheEnabled;
        s.prefix.clear();
        if (s.cache_enabled && messages.back().role == "user") {
            const std::string fixed = lastUserPrefix ? utf8(env, lastUserPrefix) : "";
            if (messages.back().content.compare(0, fixed.size(), fixed) != 0)
                throw std::runtime_error("Invalid fixed user prefix");
            messages.back().content = fixed;
            try {
                auto fixed_tokens = chatTokens(s, messages);
                size_t common = 0;
                while (common < fixed_tokens.size() && common + 1 < prompt.size() &&
                       fixed_tokens[common] == prompt[common]) ++common;
                if (common <= MAX_PREFIX_TOKENS)
                    s.prefix.assign(prompt.begin(), prompt.begin() + common);
            } catch (const std::exception &) {
                // Some templates reject an empty user message; full requests remain usable without a snapshot.
            }
        }
        s.consumed = restorePrefix(s);
        s.cached_tokens = s.consumed;
        s.prompt = std::move(prompt);
        llama_sampler_reset(s.sampler);
        s.generated = 0;
        s.pending_limit = false;
        s.finished = false;
        return count;
    } catch (const std::exception &e) { fail(env, e.what()); }
    catch (...) { fail(env, "Native prompt preparation failed"); }
    return -1;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_translation_engine_NativeLlm_cachedPromptTokens(JNIEnv *env, jobject, jlong handle) {
    try { return session(handle).cached_tokens; }
    catch (const std::exception &e) { fail(env, e.what()); }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_translation_engine_NativeLlm_completionTokens(JNIEnv *env, jobject, jlong handle) {
    try { return session(handle).generated; }
    catch (const std::exception &e) { fail(env, e.what()); }
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hippo_ehviewer_translation_engine_NativeLlm_systemInfo(JNIEnv *env, jobject, jlong handle) {
    try {
        session(handle);
        return env->NewStringUTF(llama_print_system_info());
    } catch (const std::exception &e) { fail(env, e.what()); }
    return nullptr;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_hippo_ehviewer_translation_engine_NativeLlm_cpuState(JNIEnv *env, jobject, jlong handle) {
    try {
        auto &s = session(handle);
        const jint state[] = {s.active_threads, s.allowed_cpus};
        auto result = env->NewIntArray(2);
        if (result) env->SetIntArrayRegion(result, 0, 2, state);
        return result;
    } catch (const std::exception &e) { fail(env, e.what()); }
    return nullptr;
}

namespace {
jbyteArray generateChunk(JNIEnv *env, jlong handle, int maxTokens, int maxMillis) {
    try {
        auto &s = session(handle);
        if (s.pending_limit) {
            s.pending_limit = false;
            env->ThrowNew(env->FindClass("com/hippo/ehviewer/translation/engine/TranslationOutputLimitException"),
                          "Translation was truncated");
            return nullptr;
        }
        if (s.finished) return nullptr;
        updateCpuPool(s);
        if (s.consumed < s.prompt.size()) {
            const size_t boundary = s.consumed < s.prefix.size() ? s.prefix.size() : s.prompt.size();
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
        const auto *vocab = llama_model_get_vocab(s.model);
        auto &chunk = s.output_chunk;
        chunk.clear();
        const auto began = std::chrono::steady_clock::now();
        for (int step = 0; step < std::clamp(maxTokens, 1, 4); ++step) {
            auto token = llama_sampler_sample(s.sampler, s.ctx, -1);
            if (llama_vocab_is_eog(vocab, token)) { s.finished = true; break; }
            if (s.generated >= s.max_output) {
                s.finished = true;
                // Deliver this chunk before surfacing the limit, so completed translations survive.
                s.pending_limit = true;
                break;
            }
            auto &piece = s.output_piece;
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
            chunk.insert(chunk.end(), piece.begin(), piece.begin() + length);
            if (std::chrono::steady_clock::now() - began >=
                std::chrono::milliseconds(std::clamp(maxMillis, 1, 32))) break;
        }
        if (chunk.empty() && s.pending_limit) return generateChunk(env, handle, maxTokens, maxMillis);
        if (chunk.empty() && s.finished) return nullptr;
        auto result = env->NewByteArray(chunk.size());
        if (result && !chunk.empty()) env->SetByteArrayRegion(result, 0, chunk.size(),
            reinterpret_cast<const jbyte *>(chunk.data()));
        return result;
    } catch (const std::exception &e) { fail(env, e.what()); }
    catch (...) { fail(env, "Native translation failed"); }
    return nullptr;
}
} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_translation_engine_NativeLlm_next(JNIEnv *env, jobject, jlong handle) {
    return generateChunk(env, handle, 1, 1);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_hippo_ehviewer_translation_engine_NativeLlm_nextChunk(JNIEnv *env, jobject, jlong handle,
        jint maxTokens, jint maxMillis, jintArray state) {
    if (!state || env->GetArrayLength(state) != 1) {
        fail(env, "Invalid generation chunk state");
        return nullptr;
    }
    auto result = generateChunk(env, handle, maxTokens, maxMillis);
    if (!env->ExceptionCheck()) {
        const jint count = session(handle).generated;
        env->SetIntArrayRegion(state, 0, 1, &count);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_translation_engine_NativeLlm_countTokens(JNIEnv *env, jobject, jlong handle, jbyteArray text) {
    try {
        auto &s = session(handle);
        const auto content = utf8(env, text);
        const int count = llama_tokenize(llama_model_get_vocab(s.model), content.data(), content.size(),
                                        nullptr, 0, false, false);
        return count < 0 ? -count : count;
    } catch (const std::exception &e) { fail(env, e.what()); }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_translation_engine_NativeLlm_configureSampling(JNIEnv *env, jobject, jlong handle, jint profile) {
    try { configureSampler(session(handle), profile); }
    catch (const std::exception &e) { fail(env, e.what()); }
}

extern "C" JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_translation_engine_NativeLlm_destroy(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Session *>(handle);
}
