// Bizzle's Mini CPM v4.7 OS - JNI bridge to llama.cpp + libmtmd (MIT).
// One Session = one loaded model (+ optional vision projector). All calls on a Session must be serialized by the caller.
#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include "llama.h"
#include "common.h"
#include "chat.h"
#include "sampling.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "nlohmann/json.hpp"

#define TAG "BizzleLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using json = nlohmann::json;

static std::string g_last_error;

static void log_cb(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", text);
    else if (level == GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
    else if (level == GGML_LOG_LEVEL_INFO) __android_log_print(ANDROID_LOG_INFO, TAG, "%s", text);
}

// One unit of what is currently in the KV cache: a text token, or a whole image (n_pos positions).
struct Unit {
    bool image;
    llama_token tok;
    std::string id;
    llama_pos npos;
    bool operator==(const Unit & o) const { return image == o.image && (image ? id == o.id : tok == o.tok); }
};

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    mtmd_context * mctx = nullptr;
    common_chat_templates_ptr tmpls;
    std::vector<Unit> cache;
    std::atomic<bool> cancel{false};
    int n_ctx = 4096;
    int n_batch = 512;
    int n_threads = 4;
    bool gpu = false;
};

static bool abort_cb(void * data) { return ((Session *) data)->cancel.load(); }

static std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return "";
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

// Java's NewStringUTF wants modified UTF-8; build from UTF-16 instead so emoji survive.
static jstring to_jstring(JNIEnv * env, const std::string & s) {
    std::u16string u16;
    size_t i = 0;
    while (i < s.size()) {
        unsigned char c = s[i];
        uint32_t cp; int n;
        if (c < 0x80) { cp = c; n = 1; }
        else if ((c >> 5) == 0x6) { cp = c & 0x1F; n = 2; }
        else if ((c >> 4) == 0xE) { cp = c & 0x0F; n = 3; }
        else if ((c >> 3) == 0x1E) { cp = c & 0x07; n = 4; }
        else { i++; continue; }
        if (i + n > s.size()) break;
        for (int k = 1; k < n; k++) cp = (cp << 6) | (s[i + k] & 0x3F);
        i += n;
        if (cp >= 0x10000) { cp -= 0x10000; u16.push_back((char16_t) (0xD800 + (cp >> 10))); u16.push_back((char16_t) (0xDC00 + (cp & 0x3FF))); }
        else u16.push_back((char16_t) cp);
    }
    return env->NewString((const jchar *) u16.data(), (jsize) u16.size());
}

// Length of the longest prefix of s that is complete UTF-8.
static size_t utf8_complete_len(const std::string & s) {
    size_t n = s.size();
    for (size_t back = 1; back <= 4 && back <= n; back++) {
        unsigned char c = s[n - back];
        if ((c & 0xC0) == 0x80) continue; // continuation byte
        int need = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
        return (int) back >= need ? n : n - back;
    }
    return n;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bizzle_minicpm_engine_LlamaNative_nativeInit(JNIEnv * env, jobject, jstring libDir) {
    llama_log_set(log_cb, nullptr);
    mtmd_helper_log_set(log_cb, nullptr);
    std::string dir = jstr(env, libDir);
    ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
    LOGI("backends loaded from %s, devices=%zu", dir.c_str(), ggml_backend_dev_count());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bizzle_minicpm_engine_LlamaNative_nativeSystemInfo(JNIEnv * env, jobject) {
    std::ostringstream ss;
    ss << llama_print_system_info();
    ss << " | devices:";
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        auto * d = ggml_backend_dev_get(i);
        ss << " " << ggml_backend_dev_name(d) << "(" << ggml_backend_dev_description(d) << ")";
    }
    return to_jstring(env, ss.str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bizzle_minicpm_engine_LlamaNative_nativeHasGpu(JNIEnv *, jobject) {
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        auto t = ggml_backend_dev_type(ggml_backend_dev_get(i));
        if (t == GGML_BACKEND_DEVICE_TYPE_GPU || t == GGML_BACKEND_DEVICE_TYPE_IGPU) return JNI_TRUE;
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bizzle_minicpm_engine_LlamaNative_nativeLastError(JNIEnv * env, jobject) {
    return to_jstring(env, g_last_error);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bizzle_minicpm_engine_LlamaNative_nativeLoad(JNIEnv * env, jobject, jstring jmodel, jstring jmmproj,
        jint n_ctx, jint n_threads, jboolean use_gpu, jint image_max_tokens) {
    g_last_error.clear();
    auto * s = new Session();
    s->n_ctx = n_ctx; s->n_threads = n_threads; s->gpu = use_gpu;
    std::string model_path = jstr(env, jmodel), mmproj_path = jstr(env, jmmproj);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = use_gpu ? 999 : 0;
    mp.use_mmap = true;
    s->model = llama_model_load_from_file(model_path.c_str(), mp);
    if (!s->model) { g_last_error = "Could not load model file (corrupt, unsupported, or not enough memory)"; delete s; return 0; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = s->n_batch;
    cp.n_ubatch = s->n_batch;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf = false;
    s->ctx = llama_init_from_model(s->model, cp);
    if (!s->ctx) { g_last_error = "Could not create context (try a smaller context size)"; llama_model_free(s->model); delete s; return 0; }
    llama_set_abort_callback(s->ctx, abort_cb, s);

    try { s->tmpls = common_chat_templates_init(s->model, ""); }
    catch (const std::exception & e) { LOGE("template init failed: %s", e.what()); s->tmpls = common_chat_templates_init(s->model, "chatml"); }

    if (!mmproj_path.empty()) {
        mtmd_context_params mparams = mtmd_context_params_default();
        mparams.use_gpu = use_gpu;
        mparams.n_threads = n_threads;
        mparams.print_timings = true;
        mparams.warmup = false;
        if (image_max_tokens > 0) mparams.image_max_tokens = image_max_tokens;
        auto mem = mtmd_get_memory_usage(mmproj_path.c_str(), mparams);
        if (mem.use_non_causal && mem.image_max_tokens > s->n_batch) { mparams.image_max_tokens = s->n_batch; }
        s->mctx = mtmd_init_from_file(mmproj_path.c_str(), s->model, mparams);
        if (!s->mctx) { g_last_error = "Model loaded but the vision projector failed to load"; LOGE("%s", g_last_error.c_str()); }
    }
    LOGI("loaded %s ctx=%d threads=%d gpu=%d vision=%d", model_path.c_str(), n_ctx, n_threads, (int) use_gpu, s->mctx != nullptr);
    return (jlong) s;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bizzle_minicpm_engine_LlamaNative_nativeFree(JNIEnv *, jobject, jlong h) {
    auto * s = (Session *) h;
    if (!s) return;
    if (s->mctx) mtmd_free(s->mctx);
    s->tmpls.reset();
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bizzle_minicpm_engine_LlamaNative_nativeCancel(JNIEnv *, jobject, jlong h) {
    auto * s = (Session *) h;
    if (s) s->cancel.store(true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bizzle_minicpm_engine_LlamaNative_nativeInfo(JNIEnv * env, jobject, jlong h) {
    auto * s = (Session *) h;
    json j;
    char desc[256] = {0};
    llama_model_desc(s->model, desc, sizeof(desc));
    j["desc"] = desc;
    j["size"] = (double) llama_model_size(s->model);
    j["params"] = (double) llama_model_n_params(s->model);
    j["n_ctx_train"] = llama_model_n_ctx_train(s->model);
    j["n_ctx"] = s->n_ctx;
    j["vision"] = s->mctx != nullptr && mtmd_support_vision(s->mctx);
    j["threads"] = s->n_threads;
    j["gpu"] = s->gpu;
    return to_jstring(env, j.dump());
}

static int decode_tokens(Session * s, common_batch & batch, const llama_token * toks, size_t n, llama_pos & n_past, bool logits_last) {
    size_t i = 0;
    while (i < n) {
        if (s->cancel.load()) return -2;
        batch.clear();
        size_t end = std::min(n, i + (size_t) s->n_batch);
        for (size_t k = i; k < end; k++) batch.add(toks[k], n_past++, 0, logits_last && k == n - 1);
        int rc = llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        if (rc != 0) return rc;
        i = end;
    }
    return 0;
}

// messagesJson: [{"role":"user","content":"text with <__media__> markers"}...]
extern "C" JNIEXPORT jstring JNICALL
Java_com_bizzle_minicpm_engine_LlamaNative_nativeGenerate(JNIEnv * env, jobject, jlong h, jstring jmsgs, jobjectArray jimages,
        jfloat temp, jfloat top_p, jint top_k, jfloat repeat_penalty, jint max_tokens, jboolean thinking, jobject callback) {
    auto * s = (Session *) h;
    s->cancel.store(false);
    json result;
    jclass cbCls = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)Z");
    jmethodID onPrompt = env->GetMethodID(cbCls, "onPrompt", "(IZ)V");
    auto fail = [&](const std::string & msg) { result["error"] = msg; return to_jstring(env, result.dump()); };

    // 1. render chat template
    std::vector<common_chat_msg> msgs;
    try {
        for (auto & m : json::parse(jstr(env, jmsgs))) {
            common_chat_msg cm; cm.role = m["role"].get<std::string>(); cm.content = m["content"].get<std::string>();
            msgs.push_back(cm);
        }
    } catch (const std::exception & e) { return fail(std::string("bad messages: ") + e.what()); }

    std::string prompt;
    {
        common_chat_templates_inputs in;
        in.messages = msgs;
        in.add_generation_prompt = true;
        in.use_jinja = true;
        in.enable_thinking = thinking;
        in.chat_template_kwargs["enable_thinking"] = thinking ? "true" : "false";
        try { prompt = common_chat_templates_apply(s->tmpls.get(), in).prompt.str(); }
        catch (const std::exception & e) {
            LOGE("jinja failed (%s), using legacy template", e.what());
            in.use_jinja = false;
            try { prompt = common_chat_templates_apply(s->tmpls.get(), in).prompt.str(); }
            catch (const std::exception & e2) { return fail(std::string("chat template error: ") + e2.what()); }
        }
    }
    std::string tail = prompt;
    while (!tail.empty() && (tail.back() == '\n' || tail.back() == ' ')) tail.pop_back();
    bool starts_in_think = tail.size() >= 7 && tail.compare(tail.size() - 7, 7, "<think>") == 0;

    // 2. tokenize (text + images)
    auto t0 = std::chrono::steady_clock::now();
    std::vector<Unit> units;
    mtmd::input_chunks chunks(mtmd_input_chunks_init());
    std::vector<llama_token> plain_tokens;
    jsize n_img = jimages ? env->GetArrayLength(jimages) : 0;
    if (s->mctx) {
        mtmd::bitmaps bitmaps;
        for (jsize i = 0; i < n_img; i++) {
            std::string path = jstr(env, (jstring) env->GetObjectArrayElement(jimages, i));
            auto w = mtmd_helper_bitmap_init_from_file(s->mctx, path.c_str(), false, mtmd_helper_init_opt_default());
            if (!w.bitmap) return fail("could not read image " + path);
            bitmaps.entries.emplace_back(w.bitmap);
        }
        mtmd_input_text text{prompt.c_str(), prompt.size(), true, true};
        auto bptr = bitmaps.c_ptr();
        int32_t rc = mtmd_tokenize(s->mctx, chunks.ptr.get(), &text, bptr.data(), bptr.size());
        if (rc != 0) return fail(rc == 1 ? "number of images does not match image markers" : "image preprocessing failed");
        for (size_t i = 0; i < mtmd_input_chunks_size(chunks.ptr.get()); i++) {
            auto * ch = mtmd_input_chunks_get(chunks.ptr.get(), i);
            if (mtmd_input_chunk_get_type(ch) == MTMD_INPUT_CHUNK_TYPE_TEXT) {
                size_t n; auto * toks = mtmd_input_chunk_get_tokens_text(ch, &n);
                for (size_t k = 0; k < n; k++) units.push_back({false, toks[k], "", 1});
            } else {
                const char * id = mtmd_input_chunk_get_id(ch);
                units.push_back({true, -1, id ? id : "", mtmd_input_chunk_get_n_pos(ch)});
            }
        }
    } else {
        if (n_img > 0) return fail("this model has no vision projector loaded");
        plain_tokens = common_tokenize(s->ctx, prompt, true, true);
        for (auto t : plain_tokens) units.push_back({false, t, "", 1});
    }
    llama_pos total_pos = 0;
    for (auto & u : units) total_pos += u.npos;
    if (total_pos >= s->n_ctx - 8) return fail("prompt is too long for the context size (" + std::to_string(total_pos) + " tokens). Start a new chat or raise Context in Advanced AI Settings.");

    // 3. reuse the longest matching prefix already in the KV cache
    size_t reuse = 0;
    while (reuse < units.size() && reuse < s->cache.size() && units[reuse] == s->cache[reuse]) reuse++;
    if (reuse == units.size()) reuse--; // need at least one token evaluated for logits
    while (reuse > 0 && units[reuse - 1].image && reuse == units.size()) reuse--;
    llama_pos reuse_pos = 0;
    for (size_t i = 0; i < reuse; i++) reuse_pos += units[i].npos;
    auto * mem = llama_get_memory(s->ctx);
    if (reuse == 0 || !llama_memory_seq_rm(mem, 0, reuse_pos, -1)) {
        llama_memory_clear(mem, true);
        reuse = 0; reuse_pos = 0;
    }
    s->cache.clear();

    // 4. evaluate the rest
    common_batch batch(s->ctx);
    llama_pos n_past = 0;
    int evaluated = 0;
    if (s->mctx) {
        size_t unit_idx = 0;
        size_t n_chunks = mtmd_input_chunks_size(chunks.ptr.get());
        for (size_t i = 0; i < n_chunks; i++) {
            auto * ch = mtmd_input_chunks_get(chunks.ptr.get(), i);
            bool last = i == n_chunks - 1;
            if (mtmd_input_chunk_get_type(ch) == MTMD_INPUT_CHUNK_TYPE_TEXT) {
                size_t n; auto * toks = mtmd_input_chunk_get_tokens_text(ch, &n);
                size_t skip = 0;
                while (skip < n && unit_idx + skip < reuse) skip++;
                n_past += (llama_pos) skip;
                if (skip < n) {
                    int rc = decode_tokens(s, batch, toks + skip, n - skip, n_past, last);
                    if (rc != 0) { llama_memory_clear(mem, true); return fail(rc == -2 || rc == 2 ? "cancelled" : "decode failed"); }
                    evaluated += (int) (n - skip);
                }
                unit_idx += n;
            } else {
                llama_pos np = mtmd_input_chunk_get_n_pos(ch);
                if (unit_idx < reuse) { n_past += np; }
                else {
                    llama_pos new_past = n_past;
                    int rc = mtmd_helper_eval_chunk_single(s->mctx, s->ctx, ch, n_past, 0, s->n_batch, last, &new_past);
                    if (rc != 0) { llama_memory_clear(mem, true); return fail(s->cancel.load() ? "cancelled" : "image encode failed"); }
                    n_past = new_past;
                    evaluated += (int) mtmd_input_chunk_get_n_tokens(ch);
                }
                unit_idx += 1;
            }
        }
    } else {
        n_past = reuse_pos;
        int rc = decode_tokens(s, batch, plain_tokens.data() + reuse, plain_tokens.size() - reuse, n_past, true);
        if (rc != 0) { llama_memory_clear(mem, true); return fail(rc == -2 || rc == 2 ? "cancelled" : "decode failed"); }
        evaluated = (int) (plain_tokens.size() - reuse);
    }
    s->cache = units;
    auto t1 = std::chrono::steady_clock::now();
    env->CallVoidMethod(callback, onPrompt, (jint) total_pos, (jboolean) starts_in_think);

    // 5. sample
    common_params_sampling sp;
    sp.temp = temp; sp.top_p = top_p; sp.top_k = top_k; sp.penalty_repeat = repeat_penalty; sp.penalty_last_n = 64; sp.min_p = 0.05f;
    common_sampler * smpl = common_sampler_init(s->model, sp);
    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    std::string pending, full;
    int n_gen = 0;
    std::string finish = "stop";
    while (true) {
        if (s->cancel.load()) { finish = "cancelled"; break; }
        if (n_gen >= max_tokens) { finish = "length"; break; }
        if (n_past >= s->n_ctx - 1) { finish = "length"; break; }
        llama_token tok = common_sampler_sample(smpl, s->ctx, -1);
        common_sampler_accept(smpl, tok, true);
        if (llama_vocab_is_eog(vocab, tok)) break;
        n_gen++;
        pending += common_token_to_piece(s->ctx, tok, true);
        size_t ok = utf8_complete_len(pending);
        if (ok > 0) {
            std::string piece = pending.substr(0, ok);
            pending.erase(0, ok);
            full += piece;
            jstring jp = to_jstring(env, piece);
            jboolean go = env->CallBooleanMethod(callback, onToken, jp);
            env->DeleteLocalRef(jp);
            if (!go) { finish = "cancelled"; break; }
        }
        batch.clear();
        batch.add(tok, n_past++, 0, true);
        if (llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) { finish = s->cancel.load() ? "cancelled" : "error"; break; }
        s->cache.push_back({false, tok, "", 1});
    }
    common_sampler_free(smpl);
    auto t2 = std::chrono::steady_clock::now();
    if (finish == "error") { llama_memory_clear(mem, true); s->cache.clear(); }

    result["finish"] = finish;
    result["prompt_tokens"] = (int) total_pos;
    result["prompt_evaluated"] = evaluated;
    result["prompt_reused"] = (int) reuse_pos;
    result["gen_tokens"] = n_gen;
    result["prompt_ms"] = (long) std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    result["gen_ms"] = (long) std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count();
    result["starts_in_think"] = starts_in_think;
    return to_jstring(env, result.dump());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bizzle_minicpm_engine_LlamaNative_nativeBench(JNIEnv * env, jobject, jlong h, jint pp, jint tg) {
    auto * s = (Session *) h;
    s->cancel.store(false);
    auto * mem = llama_get_memory(s->ctx);
    llama_memory_clear(mem, true);
    s->cache.clear();
    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    llama_token t = llama_vocab_bos(vocab);
    if (t == LLAMA_TOKEN_NULL) t = 0;
    std::vector<llama_token> toks(pp, t);
    common_batch batch(s->ctx);
    llama_pos n_past = 0;
    json j;
    auto a = std::chrono::steady_clock::now();
    if (decode_tokens(s, batch, toks.data(), toks.size(), n_past, true) != 0) { j["error"] = "prompt bench failed"; return to_jstring(env, j.dump()); }
    auto b = std::chrono::steady_clock::now();
    for (int i = 0; i < tg; i++) {
        if (s->cancel.load()) break;
        batch.clear();
        batch.add(t, n_past++, 0, true);
        if (llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) { j["error"] = "gen bench failed"; break; }
    }
    auto c = std::chrono::steady_clock::now();
    llama_memory_clear(mem, true);
    double pp_s = std::chrono::duration<double>(b - a).count();
    double tg_s = std::chrono::duration<double>(c - b).count();
    j["pp"] = pp; j["tg"] = tg;
    j["pp_tps"] = pp_s > 0 ? pp / pp_s : 0;
    j["tg_tps"] = tg_s > 0 ? tg / tg_s : 0;
    return to_jstring(env, j.dump());
}
