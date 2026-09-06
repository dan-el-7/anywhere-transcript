#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"
#include "ggml.h"
#include "ggml-backend.h"

#define TAG "whisper-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM *g_jvm = nullptr;

// Route whisper/ggml (and backend: OpenCL/Hexagon) logs into logcat so
// backend registration and DSP session issues are visible on device.
static void jni_ggml_log(enum ggml_log_level level, const char *text, void * /*user_data*/) {
    int prio = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    __android_log_print(prio, "whisper-cpp", "%s", text ? text : "");
}

jint JNI_OnLoad(JavaVM *vm, void *) {
    g_jvm = vm;
    ggml_log_set(jni_ggml_log, nullptr);
    whisper_log_set(jni_ggml_log, nullptr);
    return JNI_VERSION_1_6;
}

namespace {

// Attach the calling (possibly backend worker) thread to the JVM.
struct ScopedAttach {
    JNIEnv *env = nullptr;
    bool detach = false;

    ScopedAttach() {
        if (g_jvm == nullptr) return;
        if (g_jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) return;
        if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) detach = true;
    }
    ~ScopedAttach() {
        if (detach && g_jvm) g_jvm->DetachCurrentThread();
    }
};

struct WhisperHandle {
    whisper_context *ctx = nullptr;
    int threads = 4;
};

struct CallbackCtx {
    jobject obj = nullptr;   // global ref, released by transcribe()
    jmethodID onProgress;    // (I)V
    jmethodID onSegment;     // (JJLjava/lang/String;)Z
    jmethodID shouldContinue;// ()Z
};

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_anywhere_transcript_engine_WhisperEngine_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

// The FastRPC DSP loader searches ADSP_LIBRARY_PATH for the skel library
// (libggml-htp-vXX.so). Apps must point it at their own extracted lib dir —
// the DSP-side default paths (/vendor/dsp/cdsp etc.) are not app-writable.
JNIEXPORT void JNICALL
Java_com_anywhere_transcript_engine_WhisperEngine_setDspLibraryPath(JNIEnv *env, jobject, jstring dir) {
    const char *d = env->GetStringUTFChars(dir, nullptr);
    if (!d) return;
    std::string paths = std::string(d)
        + ";/vendor/dsp/cdsp;/system/lib/rfsa/adsp;/vendor/lib/rfsa/adsp;/system/lib64";
    setenv("ADSP_LIBRARY_PATH", paths.c_str(), 1);
    LOGI("ADSP_LIBRARY_PATH=%s", paths.c_str());
    env->ReleaseStringUTFChars(dir, d);
}

// Each entry: "name;description;kind" where kind is cpu|gpu|accel
JNIEXPORT jobjectArray JNICALL
Java_com_anywhere_transcript_engine_WhisperEngine_listBackends(JNIEnv *env, jobject) {
    const size_t n = ggml_backend_dev_count();
    std::vector<std::string> items;
    items.reserve(n);
    for (size_t i = 0; i < n; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        // Backends whose session init failed (e.g. Hexagon on OEM builds that
        // deny DSP access) can yield null devices — skip them, never abort.
        if (dev == nullptr) continue;
        const char *name = ggml_backend_dev_name(dev);
        const char *desc = ggml_backend_dev_description(dev);
        const char *kind = "cpu";
        switch (ggml_backend_dev_type(dev)) {
            case GGML_BACKEND_DEVICE_TYPE_GPU:   kind = "gpu";   break;
            case GGML_BACKEND_DEVICE_TYPE_ACCEL: kind = "accel"; break;
            default:                             kind = "cpu";   break;
        }
        std::string s = name ? name : "?";
        s += ";";
        s += desc ? desc : "";
        s += ";";
        s += kind;
        items.push_back(std::move(s));
    }
    jclass strClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(items.size()), strClass, nullptr);
    for (size_t i = 0; i < items.size(); ++i) {
        jstring js = env->NewStringUTF(items[i].c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), js);
        env->DeleteLocalRef(js);
    }
    return arr;
}

JNIEXPORT jlong JNICALL
Java_com_anywhere_transcript_engine_WhisperEngine_createContext(
        JNIEnv *env, jobject, jstring modelPath, jstring backendName, jint threads) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    const char *backend = env->GetStringUTFChars(backendName, nullptr);
    if (!path || !backend) {
        if (path) env->ReleaseStringUTFChars(modelPath, path);
        if (backend) env->ReleaseStringUTFChars(backendName, backend);
        return 0;
    }

    whisper_context_params cparams = whisper_context_default_params();
    if (strcmp(backend, "CPU") != 0) {
        // whisper's gpu_device is an index among GPU-type devices
        const size_t n = ggml_backend_dev_count();
        int gpuIdx = -1;
        int gpuCount = 0;
        for (size_t i = 0; i < n; ++i) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            if (dev == nullptr) continue;
            if (ggml_backend_dev_type(dev) != GGML_BACKEND_DEVICE_TYPE_GPU) continue;
            if (strcmp(ggml_backend_dev_name(dev), backend) == 0) {
                gpuIdx = gpuCount;
                break;
            }
            ++gpuCount;
        }
        if (gpuIdx >= 0) {
            cparams.use_gpu = true;
            cparams.gpu_device = gpuIdx;
        } else {
            LOGW("backend '%s' not found among GPU devices, falling back to CPU", backend);
            cparams.use_gpu = false;
        }
    } else {
        cparams.use_gpu = false;
    }

    LOGI("creating context: model=%s backend=%s threads=%d use_gpu=%d",
         path, backend, threads, static_cast<int>(cparams.use_gpu));

    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);

    env->ReleaseStringUTFChars(modelPath, path);
    env->ReleaseStringUTFChars(backendName, backend);

    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params failed");
        return 0;
    }
    auto *h = new WhisperHandle{ctx, threads > 0 ? threads : 4};
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_anywhere_transcript_engine_WhisperEngine_destroyContext(JNIEnv *, jobject, jlong handle) {
    auto *h = reinterpret_cast<WhisperHandle *>(handle);
    if (h == nullptr) return;
    if (h->ctx) whisper_free(h->ctx);
    delete h;
}

JNIEXPORT jboolean JNICALL
Java_com_anywhere_transcript_engine_WhisperEngine_isMultilingual(JNIEnv *, jobject, jlong handle) {
    auto *h = reinterpret_cast<WhisperHandle *>(handle);
    if (!h || !h->ctx) return JNI_FALSE;
    return whisper_is_multilingual(h->ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_anywhere_transcript_engine_WhisperEngine_transcribe(
        JNIEnv *env, jobject, jlong handle, jfloatArray pcm, jstring language,
        jboolean translate, jobject callback) {

    auto *h = reinterpret_cast<WhisperHandle *>(handle);
    if (!h || !h->ctx || !pcm || !callback) return JNI_FALSE;

    CallbackCtx cb{};
    cb.obj = env->NewGlobalRef(callback);
    jclass cls = env->GetObjectClass(cb.obj);
    cb.onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
    cb.onSegment = env->GetMethodID(cls, "onSegment", "(JJLjava/lang/String;)Z");
    cb.shouldContinue = env->GetMethodID(cls, "shouldContinue", "()Z");
    env->DeleteLocalRef(cls);
    if (!cb.onProgress || !cb.onSegment || !cb.shouldContinue) {
        env->DeleteGlobalRef(cb.obj);
        LOGE("TranscriptionCallback methods not found");
        return JNI_FALSE;
    }

    const jsize n = env->GetArrayLength(pcm);
    if (n <= 0) {
        env->DeleteGlobalRef(cb.obj);
        return JNI_FALSE;
    }
    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());

    const char *lang = env->GetStringUTFChars(language, nullptr);
    const bool autoLang = (lang == nullptr) || strcmp(lang, "auto") == 0;

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = h->threads;
    p.translate = translate == JNI_TRUE;
    p.language = autoLang ? nullptr : lang;
    p.print_special = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;

    p.progress_callback = [](whisper_context *, whisper_state *, int progress, void *ud) {
        auto *c = static_cast<CallbackCtx *>(ud);
        if (g_jvm == nullptr || !c) return;
        ScopedAttach a;
        if (a.env) {
            a.env->CallVoidMethod(c->obj, c->onProgress, static_cast<jint>(progress));
            if (a.env->ExceptionCheck()) a.env->ExceptionClear();
        }
    };
    p.progress_callback_user_data = &cb;

    p.new_segment_callback = [](whisper_context *ctx, whisper_state *, int n_new, void *ud) {
        auto *c = static_cast<CallbackCtx *>(ud);
        if (g_jvm == nullptr || !c || n_new <= 0) return;
        ScopedAttach a;
        if (!a.env) return;
        const int total = whisper_full_n_segments(ctx);
        for (int i = total - n_new; i < total; ++i) {
            // t0/t1 are in centiseconds
            const int64_t t0 = whisper_full_get_segment_t0(ctx, i);
            const int64_t t1 = whisper_full_get_segment_t1(ctx, i);
            const char *text = whisper_full_get_segment_text(ctx, i);
            jstring jtext = a.env->NewStringUTF(text ? text : "");
            a.env->CallBooleanMethod(c->obj, c->onSegment,
                                     static_cast<jlong>(t0) * 10, static_cast<jlong>(t1) * 10, jtext);
            if (a.env->ExceptionCheck()) {
                a.env->ExceptionClear();
                if (jtext) a.env->DeleteLocalRef(jtext);
                break;
            }
            if (jtext) a.env->DeleteLocalRef(jtext);
        }
    };
    p.new_segment_callback_user_data = &cb;

    // polled between operations; return true to abort
    p.abort_callback = [](void *ud) -> bool {
        auto *c = static_cast<CallbackCtx *>(ud);
        if (g_jvm == nullptr || !c) return false;
        ScopedAttach a;
        if (!a.env) return false;
        jboolean cont = a.env->CallBooleanMethod(c->obj, c->shouldContinue);
        if (a.env->ExceptionCheck()) {
            a.env->ExceptionClear();
            cont = JNI_TRUE;
        }
        return cont != JNI_TRUE;
    };
    p.abort_callback_user_data = &cb;

    const int rc = whisper_full(h->ctx, p, samples.data(), n);

    env->ReleaseStringUTFChars(language, lang);
    env->DeleteGlobalRef(cb.obj);

    LOGI("whisper_full rc=%d native_segments=%d", rc, whisper_full_n_segments(h->ctx));
    if (rc != 0) LOGW("whisper_full returned %d (aborted or failed)", rc);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
