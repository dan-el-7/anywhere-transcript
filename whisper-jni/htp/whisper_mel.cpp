// Log-mel spectrogram for the HTP Whisper encoder, ported 1:1 from HF
// WhisperFeatureExtractor._np_extract_fbank_features (the exact pipeline that
// produced the validated enc_input.raw): hann(400)/hop 160/center reflect,
// power spectrum, slaney mel (filters precomputed on the host, 80x201 f32
// binary), log10 with 1e-10 floor, 3001 frames computed and the last dropped,
// clamp to global max-8, (x+4)/4, then quantize to the encoder's uint16
// contract (scale 4.677e-05, zp 32072).

#include <android/log.h>
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include <thread>
#include <vector>

#define TAG "WhisperMel"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int SAMPLES_30S = 480000;
constexpr int N_FFT = 400;
constexpr int N_BINS = N_FFT / 2 + 1;  // 201
constexpr int HOP = 160;
constexpr int N_FRAMES = 3000;
// small-quantized encoder input contract; fp16 mode bypasses quantization
constexpr double QUANT_SCALE = 4.677007018472068e-05;
constexpr int QUANT_ZP = 32072;

std::vector<float> g_filters;  // [nMel][201]
int g_nmel = 0;
float g_hann[N_FFT];

// Recursive Cooley-Tukey for even N with a naive DFT base for odd N — same
// approach whisper.cpp uses; exact enough at N=400 (base case N=25).
void fft(const float* in, int n, int stride, float* out /* interleaved re,im */) {
    if (n == 1) {
        out[0] = in[0];
        out[1] = 0.0f;
        return;
    }
    if (n % 2 == 1) {  // naive DFT
        for (int k = 0; k < n; k++) {
            double re = 0, im = 0;
            for (int t = 0; t < n; t++) {
                double a = -2.0 * M_PI * k * t / n;
                re += in[t * stride] * cos(a);
                im += in[t * stride] * sin(a);
            }
            out[2 * k] = (float)re;
            out[2 * k + 1] = (float)im;
        }
        return;
    }
    int half = n / 2;
    std::vector<float> even(2 * half), odd(2 * half);
    fft(in, half, stride * 2, even.data());
    fft(in + stride, half, stride * 2, odd.data());
    for (int k = 0; k < half; k++) {
        double a = -2.0 * M_PI * k / n;
        double wr = cos(a), wi = sin(a);
        double or_ = odd[2 * k], oi = odd[2 * k + 1];
        double tr = wr * or_ - wi * oi;
        double ti = wr * oi + wi * or_;
        out[2 * k] = even[2 * k] + (float)tr;
        out[2 * k + 1] = even[2 * k + 1] + (float)ti;
        out[2 * (k + half)] = even[2 * k] - (float)tr;
        out[2 * (k + half) + 1] = even[2 * k + 1] - (float)ti;
    }
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_anywhere_transcript_engine_HtpWhisper_nativeMelInit(JNIEnv* env, jobject,
                                                                 jstring jFiltersPath, jint nMel) {
    const char* c = env->GetStringUTFChars(jFiltersPath, nullptr);
    FILE* f = fopen(c, "rb");
    env->ReleaseStringUTFChars(jFiltersPath, c);
    if (!f) {
        LOGE("cannot open mel filters file");
        return JNI_FALSE;
    }
    g_nmel = nMel;
    g_filters.resize((size_t)g_nmel * N_BINS);
    size_t got = fread(g_filters.data(), 4, g_filters.size(), f);
    fclose(f);
    if (got != g_filters.size()) {
        LOGE("mel filters short read: %zu", got);
        g_filters.clear();
        return JNI_FALSE;
    }
    for (int i = 0; i < N_FFT; i++)
        g_hann[i] = 0.5f * (1.0f - cosf(2.0f * (float)M_PI * i / N_FFT));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_anywhere_transcript_engine_HtpWhisper_nativeMel(JNIEnv* env, jobject,
                                                             jfloatArray jPcm, jboolean fp16Out) {
    if (g_filters.empty()) {
        LOGE("nativeMelInit not called");
        return nullptr;
    }
    jsize n = env->GetArrayLength(jPcm);
    if (n > SAMPLES_30S) n = SAMPLES_30S;

    // Zero-pad to 30s, then reflect-pad n_fft/2 for center=True.
    const int PAD = N_FFT / 2;
    std::vector<float> x(SAMPLES_30S + 2 * PAD, 0.0f);
    env->GetFloatArrayRegion(jPcm, 0, n, x.data() + PAD);
    for (int i = 0; i < PAD; i++) {
        x[PAD - 1 - i] = x[PAD + 1 + i];                                  // left reflect
        x[PAD + SAMPLES_30S + i] = x[PAD + SAMPLES_30S - 2 - i];          // right reflect
    }

    // Plain local: thread_local would NOT be captured by the pool lambdas
    // below (thread-storage variables are re-resolved per executing thread,
    // which crashed with each pool thread seeing an empty vector).
    std::vector<float> mel((size_t)g_nmel * N_FRAMES);

    // Frames are independent — split across the big cores (4 threads measured
    // best for ggml on this big.LITTLE part; same reasoning applies here).
    const int nThreads = 4;
    std::vector<std::thread> pool;
    for (int ti = 0; ti < nThreads; ti++) {
        pool.emplace_back([&, ti] {
            std::vector<float> frame(N_FFT), spec(2 * N_FFT), power(N_BINS);
            for (int t = ti; t < N_FRAMES; t += nThreads) {
                const float* src = x.data() + t * HOP;
                for (int i = 0; i < N_FFT; i++) frame[i] = src[i] * g_hann[i];
                fft(frame.data(), N_FFT, 1, spec.data());
                for (int k = 0; k < N_BINS; k++)
                    power[k] = spec[2 * k] * spec[2 * k] + spec[2 * k + 1] * spec[2 * k + 1];
                for (int m = 0; m < g_nmel; m++) {
                    const float* filt = g_filters.data() + m * N_BINS;
                    double acc = 0;
                    for (int k = 0; k < N_BINS; k++) acc += filt[k] * power[k];
                    if (acc < 1e-10) acc = 1e-10;
                    mel[m * N_FRAMES + t] = (float)log10(acc);
                }
            }
        });
    }
    for (auto& th : pool) th.join();

    float mmax = mel[0];
    for (float v : mel)
        if (v > mmax) mmax = v;
    float lo = mmax - 8.0f;

    jshortArray out = env->NewShortArray(g_nmel * N_FRAMES);
    std::vector<int16_t> q((size_t)g_nmel * N_FRAMES);
    for (size_t i = 0; i < mel.size(); i++) {
        float v = mel[i] < lo ? lo : mel[i];
        v = (v + 4.0f) / 4.0f;
        if (fp16Out) {
            __fp16 hv = (__fp16)v;
            memcpy(&q[i], &hv, 2);
        } else {
            long qi = lround(v / QUANT_SCALE) + QUANT_ZP;
            if (qi < 0) qi = 0;
            if (qi > 65535) qi = 65535;
            q[i] = (int16_t)(uint16_t)qi;
        }
    }
    env->SetShortArrayRegion(out, 0, q.size(), q.data());
    return out;
}
