// Whisper-on-HTP via ONNX Runtime's C API (the Java API lacks UINT16/FLOAT16
// tensors). Supports both Qualcomm precompiled variants:
//   variant 0: Whisper-Small-Quantized  w8a16 — 12 layers, 12 KV heads, 80-mel,
//              uint16 mel/mask/logits (affine-quantized), uint8 KV
//   variant 1: Whisper-Large-V3-Turbo   float — 4 layers, 20 KV heads, 128-mel,
//              float16 everywhere
// Fixed-shape KV-cache decode: 200-token window, right-aligned, one token per
// Run; self-KV outputs feed back verbatim (identical in/out quantization).
// Greedy = argmax over raw logits (quantization is monotonic; fp16 compares
// exactly). libonnxruntime.so is dlopen'd — no link-time dependency.

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <string.h>
#include <time.h>

#include <string>
#include <vector>

#include "onnxruntime_c_api.h"

#define TAG "HtpWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM* g_jvm = nullptr;

jint JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

// Attach the calling thread to the JVM for callback invocations.
struct ScopedAttach {
    JNIEnv* env = nullptr;
    bool detach = false;
    ScopedAttach() {
        if (g_jvm == nullptr) return;
        if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return;
        if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) detach = true;
    }
    ~ScopedAttach() {
        if (detach && g_jvm) g_jvm->DetachCurrentThread();
    }
};

namespace {

constexpr int WINDOW = 200;  // decoder attention window (199 cache + current)
constexpr int CACHE = 199;
constexpr int HEAD_DIM = 64;
constexpr int MAX_LAYERS = 12;
constexpr int EOT = 50257;  // same in v2 and v3 vocabs; also the argmax cutoff

struct ModelCfg {
    int layers;
    int kvHeads;
    int melBins;
    bool fp16;  // false = w8a16 (uint16 activations / uint8 KV)
    int vocab;
    int noSpeechId;  // <|nospeech|>: 50362 in the v2 vocab, 50363 in v3
    int langCount;   // language tokens start at 50259: 99 in v2, 100 in v3
};
constexpr ModelCfg CFG_SMALL = {12, 12, 80, false, 51865, 50362, 99};
constexpr ModelCfg CFG_TURBO = {4, 20, 128, true, 51866, 50363, 100};
constexpr int LANG_FIRST = 50259;
constexpr int LANG_PT = 50267;
constexpr int LANG_EN = 50259;

// small-quantized affine params (metadata.json); fp16 variant needs none
constexpr float LOGITS_SCALE = 0.0012925398768857121f;
constexpr uint16_t MASK_ATTEND_U16 = 65535;  // dequantizes to 0.0
constexpr uint16_t MASK_OFF_U16 = 0;         // dequantizes to -100.0
constexpr uint8_t KV_ZP_U8 = 128;

// Streaming + cancellation hooks into the decode loop. `listener` (optional)
// receives partial generated-id arrays every few steps; returning false from
// shouldContinue stops the decode, mirroring the whisper.cpp engine's contract.
struct JavaCallbacks {
    jobject listener = nullptr;          // global ref; HtpWhisper.Listener
    jmethodID onPartial = nullptr;       // ([I)Z
    jmethodID shouldContinue = nullptr;  // ()Z
};

struct Handle {
    void* ortLib = nullptr;
    const OrtApi* api = nullptr;
    OrtEnv* env = nullptr;
    OrtSession* enc = nullptr;
    OrtSession* dec = nullptr;
    OrtMemoryInfo* mem = nullptr;
    ModelCfg cfg = CFG_SMALL;
    OrtValue* cross[2 * MAX_LAYERS] = {};  // encoder outputs, constant per segment
    std::vector<uint8_t> selfKV[2 * MAX_LAYERS];
    int32_t inputId = 0;
    int32_t positionId = 0;
    uint16_t mask[WINDOW] = {};  // raw bits (uint16 or fp16 per variant)
    JavaCallbacks cb{};

    size_t kvElem() const { return cfg.fp16 ? 2 : 1; }
    size_t kvBytes() const { return (size_t)cfg.kvHeads * CACHE * HEAD_DIM * kvElem(); }
    ONNXTensorElementDataType kvType() const {
        return cfg.fp16 ? ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16 : ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT8;
    }
    ONNXTensorElementDataType actType() const {  // mel, mask, logits
        return cfg.fp16 ? ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16 : ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT16;
    }
};

uint16_t fp16Bits(float f) {
    __fp16 h = (__fp16)f;
    uint16_t b;
    memcpy(&b, &h, 2);
    return b;
}

float fromFp16(uint16_t b) {
    __fp16 h;
    memcpy(&h, &b, 2);
    return (float)h;
}

bool check(const OrtApi* api, OrtStatus* st, const char* what) {
    if (!st) return true;
    LOGE("%s failed: %s", what, api->GetErrorMessage(st));
    api->ReleaseStatus(st);
    return false;
}

OrtSession* createQnnSession(Handle* h, const char* modelPath, const std::string& libDir) {
    const OrtApi* api = h->api;
    OrtSessionOptions* opts = nullptr;
    if (!check(api, api->CreateSessionOptions(&opts), "CreateSessionOptions")) return nullptr;
    api->SetSessionGraphOptimizationLevel(opts, ORT_DISABLE_ALL);
    std::string backend = libDir + "/libQnnHtp.so";
    // high_performance (NOT burst): burst drove the passively-cooled puck to
    // thermal-emergency (skin 45°C+) after ~2.5h of continuous captioning on
    // 2026-07-08. The default profile is 3-4x slower; high_performance keeps
    // most of burst's speed at sustainable clocks.
    const char* keys[] = {"backend_path", "htp_performance_mode"};
    const char* vals[] = {backend.c_str(), "high_performance"};
    if (!check(api, api->SessionOptionsAppendExecutionProvider(opts, "QNN", keys, vals, 2),
               "AppendExecutionProvider(QNN)")) {
        api->ReleaseSessionOptions(opts);
        return nullptr;
    }
    OrtSession* session = nullptr;
    bool ok = check(api, api->CreateSession(h->env, modelPath, opts, &session), modelPath);
    api->ReleaseSessionOptions(opts);
    return ok ? session : nullptr;
}

void resetSelfKV(Handle* h) {
    for (int i = 0; i < 2 * h->cfg.layers; i++) {
        h->selfKV[i].assign(h->kvBytes(), h->cfg.fp16 ? 0 : KV_ZP_U8);
    }
}

void destroy(JNIEnv* env, Handle* h) {
    if (!h) return;
    if (h->cb.listener && env) {
        env->DeleteGlobalRef(h->cb.listener);
        h->cb.listener = nullptr;
    }
    if (h->api) {
        for (auto*& v : h->cross)
            if (v) h->api->ReleaseValue(v);
        if (h->mem) h->api->ReleaseMemoryInfo(h->mem);
        if (h->enc) h->api->ReleaseSession(h->enc);
        if (h->dec) h->api->ReleaseSession(h->dec);
        if (h->env) h->api->ReleaseEnv(h->env);
    }
    if (h->ortLib) dlclose(h->ortLib);
    delete h;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_anywhere_transcript_engine_HtpWhisper_nativeInit(
    JNIEnv* env, jobject, jstring jLibDir, jstring jEncPath, jstring jDecPath, jint variant,
    jobject listener) {
    const char* c;
    c = env->GetStringUTFChars(jLibDir, nullptr);  std::string libDir = c;  env->ReleaseStringUTFChars(jLibDir, c);
    c = env->GetStringUTFChars(jEncPath, nullptr); std::string encPath = c; env->ReleaseStringUTFChars(jEncPath, c);
    c = env->GetStringUTFChars(jDecPath, nullptr); std::string decPath = c; env->ReleaseStringUTFChars(jDecPath, c);

    auto* h = new Handle();
    h->cfg = (variant == 1) ? CFG_TURBO : CFG_SMALL;
    if (listener) {
        h->cb.listener = env->NewGlobalRef(listener);
        jclass cls = env->GetObjectClass(h->cb.listener);
        h->cb.onPartial = env->GetMethodID(cls, "onPartial", "([I)Z");
        h->cb.shouldContinue = env->GetMethodID(cls, "shouldContinue", "()Z");
        env->DeleteLocalRef(cls);
    }
    std::string ortPath = libDir + "/libonnxruntime.so";
    h->ortLib = dlopen(ortPath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!h->ortLib) {
        LOGE("dlopen(%s): %s", ortPath.c_str(), dlerror());
        destroy(env, h);
        return 0;
    }
    auto getApiBase = reinterpret_cast<const OrtApiBase* (*)()>(dlsym(h->ortLib, "OrtGetApiBase"));
    if (!getApiBase) {
        LOGE("dlsym OrtGetApiBase: %s", dlerror());
        destroy(env, h);
        return 0;
    }
    h->api = getApiBase()->GetApi(ORT_API_VERSION);
    if (!h->api) {
        LOGE("GetApi(%d) returned null (runtime older than header?)", ORT_API_VERSION);
        destroy(env, h);
        return 0;
    }
    const OrtApi* api = h->api;
    if (!check(api, api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "HtpWhisper", &h->env), "CreateEnv") ||
        !check(api, api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &h->mem),
               "CreateCpuMemoryInfo")) {
        destroy(env, h);
        return 0;
    }

    h->enc = createQnnSession(h, encPath.c_str(), libDir);
    if (!h->enc) { destroy(env, h); return 0; }
    h->dec = createQnnSession(h, decPath.c_str(), libDir);
    if (!h->dec) { destroy(env, h); return 0; }
    LOGI("sessions created (variant=%d: %d layers, %d kv-heads, %d mel bins, %s)", variant,
         h->cfg.layers, h->cfg.kvHeads, h->cfg.melBins, h->cfg.fp16 ? "fp16" : "w8a16");

    resetSelfKV(h);
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_anywhere_transcript_engine_HtpWhisper_nativeTranscribe(
    JNIEnv* env, jobject, jlong jh, jshortArray jMel, jintArray jPrompt, jfloatArray jMetrics) {
    auto* h = reinterpret_cast<Handle*>(jh);
    if (!h) return nullptr;
    const OrtApi* api = h->api;
    const ModelCfg& cfg = h->cfg;
    const int L = cfg.layers;
    const int VOCAB_ARGMAX = EOT;  // inclusive upper bound of sampled ids

    // ------------------------------------------------------------- encoder
    jsize melLen = env->GetArrayLength(jMel);
    if (melLen != cfg.melBins * 3000) {
        LOGE("mel length %d != %d", melLen, cfg.melBins * 3000);
        return nullptr;
    }
    std::vector<uint16_t> mel(melLen);
    env->GetShortArrayRegion(jMel, 0, melLen, reinterpret_cast<jshort*>(mel.data()));

    for (auto*& v : h->cross) {
        if (v) api->ReleaseValue(v);
        v = nullptr;
    }

    int64_t melShape[] = {1, cfg.melBins, 3000};
    OrtValue* melVal = nullptr;
    if (!check(api,
               api->CreateTensorWithDataAsOrtValue(h->mem, mel.data(), mel.size() * 2, melShape, 3,
                                                   h->actType(), &melVal),
               "create mel tensor"))
        return nullptr;

    std::vector<std::string> encOutStore;
    for (int i = 0; i < L; i++) encOutStore.push_back("k_cache_cross_" + std::to_string(i));
    for (int i = 0; i < L; i++) encOutStore.push_back("v_cache_cross_" + std::to_string(i));
    std::vector<const char*> encOut;
    for (auto& s : encOutStore) encOut.push_back(s.c_str());
    const char* encIn[] = {"input_features"};

    struct timespec ts0, ts1;
    clock_gettime(CLOCK_MONOTONIC, &ts0);
    OrtStatus* st = api->Run(h->enc, nullptr, encIn, (const OrtValue* const*)&melVal, 1,
                             encOut.data(), encOut.size(), h->cross);
    clock_gettime(CLOCK_MONOTONIC, &ts1);
    api->ReleaseValue(melVal);
    if (!check(api, st, "encoder Run")) return nullptr;
    double encMs = (ts1.tv_sec - ts0.tv_sec) * 1e3 + (ts1.tv_nsec - ts0.tv_nsec) / 1e6;
    LOGI("encoder: %.1f ms on HTP", encMs);

    // ------------------------------------------------------------- decoder
    jsize promptLen = env->GetArrayLength(jPrompt);
    std::vector<int32_t> prompt(promptLen);
    env->GetIntArrayRegion(jPrompt, 0, promptLen, prompt.data());

    resetSelfKV(h);
    const uint16_t maskOff = cfg.fp16 ? fp16Bits(-100.0f) : MASK_OFF_U16;
    const uint16_t maskAttend = cfg.fp16 ? fp16Bits(0.0f) : MASK_ATTEND_U16;
    for (auto& m : h->mask) m = maskOff;

    std::vector<std::string> inNameStore;
    inNameStore.push_back("input_ids");
    inNameStore.push_back("attention_mask");
    for (int i = 0; i < L; i++) {
        inNameStore.push_back("k_cache_self_" + std::to_string(i) + "_in");
        inNameStore.push_back("v_cache_self_" + std::to_string(i) + "_in");
    }
    for (int i = 0; i < L; i++) {
        inNameStore.push_back("k_cache_cross_" + std::to_string(i));
        inNameStore.push_back("v_cache_cross_" + std::to_string(i));
    }
    inNameStore.push_back("position_ids");
    std::vector<const char*> inNames;
    for (auto& s : inNameStore) inNames.push_back(s.c_str());

    int64_t idShape[] = {1, 1};
    int64_t maskShape[] = {1, 1, 1, WINDOW};
    int64_t kShape[] = {cfg.kvHeads, 1, HEAD_DIM, CACHE};
    int64_t vShape[] = {cfg.kvHeads, 1, CACHE, HEAD_DIM};
    int64_t posShape[] = {1};

    std::vector<OrtValue*> inVals;
    bool ok = true;
    OrtValue* val = nullptr;
    ok = ok && check(api, api->CreateTensorWithDataAsOrtValue(h->mem, &h->inputId, 4, idShape, 2,
                                                              ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32, &val),
                     "input_ids tensor");
    inVals.push_back(val);
    ok = ok && check(api, api->CreateTensorWithDataAsOrtValue(h->mem, h->mask, WINDOW * 2, maskShape, 4,
                                                              h->actType(), &val),
                     "mask tensor");
    inVals.push_back(val);
    for (int i = 0; i < L && ok; i++) {
        ok = ok && check(api,
                         api->CreateTensorWithDataAsOrtValue(h->mem, h->selfKV[2 * i].data(),
                                                             h->kvBytes(), kShape, 4, h->kvType(), &val),
                         "self k tensor");
        inVals.push_back(val);
        ok = ok && check(api,
                         api->CreateTensorWithDataAsOrtValue(h->mem, h->selfKV[2 * i + 1].data(),
                                                             h->kvBytes(), vShape, 4, h->kvType(), &val),
                         "self v tensor");
        inVals.push_back(val);
    }
    if (ok) {
        for (int i = 0; i < L; i++) {
            inVals.push_back(h->cross[i]);      // k_cache_cross_i
            inVals.push_back(h->cross[L + i]);  // v_cache_cross_i
        }
        ok = check(api, api->CreateTensorWithDataAsOrtValue(h->mem, &h->positionId, 4, posShape, 1,
                                                            ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32, &val),
                   "position_ids tensor");
        inVals.push_back(val);
    }

    std::vector<std::string> outNameStore;
    outNameStore.push_back("logits");
    for (int i = 0; i < L; i++) {
        outNameStore.push_back("k_cache_self_" + std::to_string(i) + "_out");
        outNameStore.push_back("v_cache_self_" + std::to_string(i) + "_out");
    }
    std::vector<const char*> outNamesC;
    for (auto& s : outNameStore) outNamesC.push_back(s.c_str());

    std::vector<int32_t> generated;
    double decTotalMs = 0;
    int steps = 0;
    double probSum = 0;
    int probCount = 0;
    float noSpeechProb = 0.0f;
    float ptProb = 0.0f;  // P(<|pt|>) among the language tokens at the SOT step
    float enProb = 0.0f;  // P(<|en|>) — silence hallucinations read as English

    if (ok) {
        clock_gettime(CLOCK_MONOTONIC, &ts0);
        for (int step = 0; step < WINDOW - 1; step++) {
            h->inputId = (step < promptLen) ? prompt[step] : generated.back();
            h->positionId = step;
            // Right-aligned attention window: current token at slot 199.
            h->mask[WINDOW - 1 - step] = maskAttend;

            std::vector<OrtValue*> outs(1 + 2 * L, nullptr);
            st = api->Run(h->dec, nullptr, inNames.data(), (const OrtValue* const*)inVals.data(),
                          inVals.size(), outNamesC.data(), outNamesC.size(), outs.data());
            if (!check(api, st, "decoder Run")) { ok = false; break; }
            steps++;

            for (int i = 0; i < 2 * L; i++) {
                void* data = nullptr;
                api->GetTensorMutableData(outs[1 + i], &data);
                memcpy(h->selfKV[i].data(), data, h->kvBytes());
            }

            // Whisper's no-speech signal lives in the logits at the
            // start-of-transcript position (step 0 feeds <|startoftranscript|>).
            if (step == 0) {
                void* ldata = nullptr;
                api->GetTensorMutableData(outs[0], &ldata);
                auto* logits = static_cast<uint16_t*>(ldata);
                double denom = 0, lns = 0, langDenom = 0, langPt = 0, langEn = 0;
                if (cfg.fp16) {
                    float mx = fromFp16(logits[0]);
                    for (int i = 1; i < cfg.vocab; i++) mx = fmaxf(mx, fromFp16(logits[i]));
                    for (int i = 0; i < cfg.vocab; i++) denom += exp((double)(fromFp16(logits[i]) - mx));
                    lns = exp((double)(fromFp16(logits[cfg.noSpeechId]) - mx));
                    for (int i = LANG_FIRST; i < LANG_FIRST + cfg.langCount; i++)
                        langDenom += exp((double)(fromFp16(logits[i]) - mx));
                    langPt = exp((double)(fromFp16(logits[LANG_PT]) - mx));
                    langEn = exp((double)(fromFp16(logits[LANG_EN]) - mx));
                } else {
                    uint16_t mx = logits[0];
                    for (int i = 1; i < cfg.vocab; i++) mx = logits[i] > mx ? logits[i] : mx;
                    for (int i = 0; i < cfg.vocab; i++) denom += exp(((int)logits[i] - (int)mx) * LOGITS_SCALE);
                    lns = exp(((int)logits[cfg.noSpeechId] - (int)mx) * LOGITS_SCALE);
                    for (int i = LANG_FIRST; i < LANG_FIRST + cfg.langCount; i++)
                        langDenom += exp(((int)logits[i] - (int)mx) * LOGITS_SCALE);
                    langPt = exp(((int)logits[LANG_PT] - (int)mx) * LOGITS_SCALE);
                    langEn = exp(((int)logits[LANG_EN] - (int)mx) * LOGITS_SCALE);
                }
                noSpeechProb = (float)(lns / denom);
                ptProb = langDenom > 0 ? (float)(langPt / langDenom) : 0.0f;
                enProb = langDenom > 0 ? (float)(langEn / langDenom) : 0.0f;
            }
            if (h->cb.shouldContinue) {
                ScopedAttach sa;
                if (!sa.env) { ok = false; break; }
                jboolean cont = sa.env->CallBooleanMethod(h->cb.listener, h->cb.shouldContinue);
                if (sa.env->ExceptionCheck()) { sa.env->ExceptionClear(); cont = JNI_TRUE; }
                if (cont != JNI_TRUE) { ok = false; break; }
            }
            if (h->cb.onPartial && (step % 8 == 7 || step == promptLen)) {
                ScopedAttach sa;
                if (sa.env) {
                    jintArray arr = sa.env->NewIntArray((jsize)generated.size());
                    if (arr) {
                        if (!generated.empty())
                            sa.env->SetIntArrayRegion(arr, 0, (jsize)generated.size(), generated.data());
                        sa.env->CallBooleanMethod(h->cb.listener, h->cb.onPartial, arr);
                        if (sa.env->ExceptionCheck()) sa.env->ExceptionClear();
                        sa.env->DeleteLocalRef(arr);
                    }
                }
            }
            if (step >= promptLen - 1) {
                void* ldata = nullptr;
                api->GetTensorMutableData(outs[0], &ldata);
                int best = 0;
                if (cfg.fp16) {
                    auto* logits = static_cast<uint16_t*>(ldata);
                    float bestV = fromFp16(logits[0]);
                    for (int i = 1; i <= VOCAB_ARGMAX; i++) {
                        float v = fromFp16(logits[i]);
                        if (v > bestV) { bestV = v; best = i; }
                    }
                    double denom = 0;
                    for (int i = 0; i <= VOCAB_ARGMAX; i++)
                        denom += exp((double)(fromFp16(logits[i]) - bestV));
                    probSum += 1.0 / denom;
                } else {
                    auto* logits = static_cast<uint16_t*>(ldata);
                    for (int i = 1; i <= VOCAB_ARGMAX; i++)
                        if (logits[i] > logits[best]) best = i;
                    double denom = 0;
                    for (int i = 0; i <= VOCAB_ARGMAX; i++)
                        denom += exp(((int)logits[i] - (int)logits[best]) * LOGITS_SCALE);
                    probSum += 1.0 / denom;
                }
                probCount++;
                for (auto* o : outs) api->ReleaseValue(o);
                if (best == EOT) break;
                generated.push_back(best);
            } else {
                for (auto* o : outs) api->ReleaseValue(o);
            }
        }
        clock_gettime(CLOCK_MONOTONIC, &ts1);
        decTotalMs = (ts1.tv_sec - ts0.tv_sec) * 1e3 + (ts1.tv_nsec - ts0.tv_nsec) / 1e6;
    }

    for (size_t i = 0; i < inVals.size(); i++) {
        bool isCross = i >= (size_t)(2 + 2 * L) && i < (size_t)(2 + 4 * L);
        if (!isCross && inVals[i]) api->ReleaseValue(inVals[i]);
    }
    if (!ok) return nullptr;

    float avgProb = probCount ? (float)(probSum / probCount) : 0.0f;
    LOGI("decoder: %d steps in %.1f ms (%.1f ms/token), %zu tokens, avg_p=%.3f no_speech=%.3f p_pt=%.3f p_en=%.3f",
         steps, decTotalMs, steps ? decTotalMs / steps : 0.0, generated.size(), avgProb,
         noSpeechProb, ptProb, enProb);
    if (jMetrics && env->GetArrayLength(jMetrics) >= 1) {
        env->SetFloatArrayRegion(jMetrics, 0, 1, &avgProb);
    }
    if (jMetrics && env->GetArrayLength(jMetrics) >= 2) {
        env->SetFloatArrayRegion(jMetrics, 1, 1, &noSpeechProb);
    }
    if (jMetrics && env->GetArrayLength(jMetrics) >= 3) {
        env->SetFloatArrayRegion(jMetrics, 2, 1, &ptProb);
    }
    if (jMetrics && env->GetArrayLength(jMetrics) >= 4) {
        env->SetFloatArrayRegion(jMetrics, 3, 1, &enProb);
    }

    jintArray result = env->NewIntArray(generated.size());
    env->SetIntArrayRegion(result, 0, generated.size(), generated.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_anywhere_transcript_engine_HtpWhisper_nativeFree(JNIEnv* env, jobject, jlong jh) {
    destroy(env, reinterpret_cast<Handle*>(jh));
}
