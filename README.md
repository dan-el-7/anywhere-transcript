# Anywhere Transcript

Offline speech-to-text for Android. Share any audio file from any app (or pick one
manually) → Whisper runs fully on-device → copy or share the transcript. No cloud,
no accounts, no storage permissions.

Built with **Kotlin + Jetpack Compose Material 3 Expressive** and
**whisper.cpp** (v1.9.3) compiled natively via the NDK.

> **Status: NPU support is WIP.** The Hexagon NPU backend ships in the APK but is
> disabled-by-default on devices whose OEM blocks third-party DSP sessions
> (see [NPU support (WIP)](#npu-support-wip)). CPU works everywhere; OpenCL works
> where the OEM exposes the Adreno driver. The app always reports honestly which
> engine it can actually use.

## Features

- **Share-target**: hit Share on any audio/video file and choose *Anywhere Transcript*,
  or pick a file from the Transcribe tab.
- **100% on-device inference** with automatic backend negotiation:
  - **CPU** — works everywhere (including non-Qualcomm SoCs)
  - **OpenCL GPU** — Adreno-tuned ggml kernels, loaded via `dlopen("libOpenCL.so")`
  - **Vulkan** — built when available; covers Mali/other GPUs
  - **Hexagon NPU (experimental)** — whisper.cpp's upstream `ggml-hexagon` backend;
    see [NPU build](#qualcomm-npu-experimental) below. Disabled by default.
- **Three device tiers** (auto-detected from RAM, overridable in Settings):
  | Tier | Recommended model | Size |
  |---|---|---|
  | Low-end · 4 GB | base-q8_0 | ~78 MB |
  | Mid-range · 8 GB | small-q8_0 | ~256 MB |
  | Flagship · 16 GB | large-v3-turbo-q8_0 | ~834 MB |
- **Models** are downloaded on demand from Hugging Face
  ([ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp)) with progress,
  HTTP resume, delete, and free manual selection (English-only and multilingual variants).
  q8_0 quants are near-lossless *and* the quant family the NPU path supports.
- **History** with copy/share/delete, timestamps toggle, streaming partial results
  while transcribing, and a cancel button.
- Long jobs run in a **foreground service** (notification with progress) so they
  survive app switches.

## Building

Requirements: JDK 17+ (Android Studio's JBR works), Android SDK platform 36,
NDK 30, CMake 3.22.1, and Python 3 on the PATH (used once at configure time to
embed the OpenCL kernels).

```bash
# 1. fetch whisper.cpp sources (pinned)
git clone --depth 1 --branch v1.9.3 https://github.com/ggml-org/whisper.cpp whisper-src

# 2. point local.properties at your SDK (already gitignored)
#    sdk.dir=D\:\\path\\to\\Android\\Sdk

# 3. build
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests (resampler, wav parser, tiers, catalog)
```

Debug builds contain `arm64-v8a` + `x86_64` (emulator testing); release builds are
`arm64-v8a` only by default.

## Architecture notes

- `whisper-jni/` — CMake + JNI wrapper (`whisper_jni.cpp`). Backend selection maps a
  backend name (e.g. `OpenCL`) to whisper's `use_gpu`/`gpu_device` params; a GPU
  failure at init falls back to CPU.
- `whisper-jni/opencl_shim/` — generated dlopen shim. Android apps must not
  DT_NEEDED-link vendor `libOpenCL.so` (outside the app linker namespace), so the
  shim resolves every OpenCL entry point with `dlsym` and forwards via tail-jump
  trampolines. If no OpenCL runtime exists, `clGetPlatformIDs` returns
  `CL_PLATFORM_NOT_FOUND_KHR` and ggml registers no GPU device (clean CPU fallback).
  Regenerate with `python3 cmake/gen_opencl_shim.py`.
- `audio/` — MediaExtractor/MediaCodec decode → mono → streaming linear resample to
  16 kHz (`RateConverter`), windowed output so memory stays bounded on 4 GB devices
  (60/120/300 s windows per tier). Plain WAVs use a fast RIFF path.
- `data/` — model catalog + OkHttp downloader (resume via Range), DataStore settings,
  Room history. `ggml-hexagon`-compatible quants (q8_0) are the defaults, so enabling
  the NPU needs no extra downloads.
- `service/` — foreground service (`mediaProcessing` on API 35+, `dataSync` before)
  with progress + cancel; state flows through the process-wide `TranscriptionBus`.

## NPU support (WIP)

The arm64 build ships with the Hexagon NPU backend compiled in
(`app/src/main/jniLibs/arm64-v8a/libwhisperjni.so`, plus per-SoC DSP kernel
drivers `libggml-htp-v73/v75/v79/v81.so` covering Snapdragon 8 Gen 2 → 8 Elite
Gen 5). Built with the Hexagon SDK 6.6.0.0 (available without a Qualcomm login
from the public mirror `github.com/snapdragon-toolchain/hexagon-sdk`); the
Gradle build copies them verbatim for arm64 and compiles the non-NPU build for
other ABIs. See `hexagon-npu-libs/HEXAGON_INTEGRATION.md` for checksums, caveats
and the full rebuild recipe (WSL2/Linux).

**What works / what's WIP:**

- ✅ Detection, graceful fallback: if the DSP session can't open, the device is
  skipped and transcription continues on CPU/GPU — no crashes either way.
- ✅ Works end-to-end on devices whose vendor policy permits unsigned PD
  FastRPC sessions from third-party apps.
- ⚠️ **WIP** — some OEM builds (e.g. ColorOS/Realme) reject unsigned PD sessions
  (`failed to enable unsigned PD`) and block the vendor driver from app
  namespaces entirely, so the NPU stays unavailable there. Making session
  failures fully non-fatal across OEM variants is the remaining work; a QNN
  SDK-based engine (converted context-binary models, like Qualcomm's
  AuraTranslator) is the alternative long-term route.

In the app: *Settings → Compute backend* offers **NPU — Hexagon (experimental)**;
*Auto* prefers NPU when a Hexagon device is present and the selected model is a
q8_0 quant (the recommended models are), then GPU, then CPU. NPU runs
q8_0/f32-quant models only — incompatible models are flagged in the Models tab.

Local patches carried on top of the vendored `whisper-src` (v1.9.3):
- `ggml/src/ggml-hexagon/htp-drv.cpp` — dlopens `libcdsprpc.so` from absolute
  vendor paths. Several OEM Android builds (ColorOS/Realme, etc.) expose vendor
  public libraries only via absolute paths, so the plain-name lookup fails and
  the NPU silently disappears.
- OpenCL: some OEM builds block `dlopen("/vendor/lib64/libOpenCL.so")` outright
  (its Adreno dependency is not a public library) — the shim logs every
  candidate failure under the `opencl-shim` logcat tag for diagnosis.

## License notes

whisper.cpp is MIT; the ggml model files on `ggerganov/whisper.cpp` inherit the
Apache-2.0/MIT licensing of the original Whisper weights; OpenCL-Headers and the
vendored Khronos code are Apache-2.0.
