# Anywhere Transcript

Offline speech-to-text for Android. Share any audio file from any app (or pick one
manually) → Whisper runs fully on-device → copy or share the transcript. No cloud,
no accounts, no storage permissions.

Built with **Kotlin + Jetpack Compose Material 3 Expressive** and
**whisper.cpp** (v1.9.3) compiled natively via the NDK.

> **Status: QNN on the Hexagon NPU works well** (the recommended path on
> Snapdragon 8 Gen 1 → 8 Elite Gen 5), and CPU works everywhere. NPU-ggml and
> OpenCL/Vulkan GPU are experimental/iffy — see [NPU support](#npu-support-two-engines).
> The app always reports honestly which engine it can actually use.

## Features

- **Share-target**: hit Share on any audio/video file and choose *Anywhere Transcript*,
  or pick a file from the Transcribe tab.
- **100% on-device inference** — the engine follows the model, with a manual
  override in *Settings → Compute backend*. Honest maturity levels:
  | Engine | Status | Notes |
  |---|---|---|
  | **QNN (Hexagon NPU)** | ✅ Works well | Whisper Turbo fp16 as precompiled context binaries (~2 GB/arch, 8 Gen 1 → 8 Elite Gen 5); ~7× realtime on-device |
  | **CPU** | ✅ Works everywhere | Including non-Qualcomm SoCs; slower for large models |
  | **NPU-ggml (direct Hexagon)** | ❌ Doesn't work directly | The HTP runs quantized/precompiled graphs only — never a raw model ([QNN docs](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html)) — and app DSP sessions are OEM-gated (detection still reports HTP where apps are blocked). Kept as an explicit option; prefer QNN |
  | **OpenCL / Vulkan GPU** | ⚠️ Opt-in, iffy | Some Adreno drivers abort uncatchably, so OpenCL stays off unless picked |
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

## NPU support (two engines)

### Using the NPU on a Qualcomm phone (user guide)

**Maturity: the QNN engine is the one that works well** — it's what the app
steers you to. CPU always works. The other two paths are usable but iffy:
NPU-ggml (engine 1) is experimental and driver-dependent, and OpenCL GPU is
opt-in for the same reason.

Both NPU engines work on Snapdragon **8 Gen 1 through 8 Elite Gen 5**. First
launch shows a picker that already highlights the best option for your chip;
afterwards everything lives in *Models* and *Settings → Compute backend*.

1. **Models tab** — find the entry marked *"✓ Your chip"*:
   - **Whisper Turbo · NPU (v79)** etc. — Large-V3-Turbo fp16 as QNN context
     binaries (~2 GB). Best quality and speed; runs entirely on the Hexagon NPU
     (~630 ms per 30 s window encoder + ~18 ms/token decoder ≈ **7× realtime**
     on a Snapdragon 8 Elite).
   - any **q8_0** ggml model (e.g. Large-v3-Turbo q8_0, 834 MB) — runs on the
     NPU through whisper.cpp's Hexagon backend, or on CPU anywhere.
2. **Settings → Compute backend**: pick **QNN** for the Turbo package (the app
   also routes automatically: a qnn-* model always runs on the QNN engine), or
   **NPU** for q8_0 models on the ggml engine. *Auto* never dispatches raw HTP —
   it uses GPU when available, else CPU (the NPU is reached via QNN); if the
   chosen backend fails at init, the job **retries on CPU automatically**.
3. Share any audio file from any app, or pick one in the Transcribe tab.

The first transcription opens the QNN sessions (~10–20 s); after that each run
is instant to start. Everything is offline.

**Engine 1 — Hexagon ggml (experimental, iffy):** whisper.cpp with the
`ggml-hexagon` backend runs regular GGML models (q8_0)
directly on the NPU. The arm64 build ships `libwhisperjni.so` with the backend
compiled in, plus per-SoC DSP kernel drivers (`libggml-htp-v73/v75/v79/v81.so`
— Snapdragon 8 Gen 2 → 8 Elite Gen 5), built with the Hexagon SDK 6.6.0.0 from
the public mirror (`github.com/snapdragon-toolchain/hexagon-sdk`). Verified on
a Snapdragon 8 Elite: `HTP0 new session`, 6 HVX + HMX, 3.35 GB vmem.

Three non-obvious fixes were required to make it work — all in the app:
1. `<uses-native-library android:name="libcdsprpc.so" android:required="false"/>`
   in the manifest. With targetSdk 31+, Android hides public vendor libraries
   from the app's linker namespace unless declared; without this, every dlopen
   of the vendor driver fails with a misleading "not accessible" error.
2. `ADSP_LIBRARY_PATH` must point at the app's extracted native lib dir
   (`setenv` from JNI) so the DSP loader can find the skel library — libs inside
   the APK are invisible to it, hence `useLegacyPackaging`.
3. The backend device is named **HTP** — backend detection must not filter on
   "Hexagon".

### Engine 2 — QNN runtime (works well)

Whisper Large-V3-Turbo fp16 as **Qualcomm AI Hub context binaries**, executed
through ONNX Runtime's QNN Execution Provider (`onnxruntime-android-qnn` +
`qnn-runtime` Maven artifacts; native core vendored from
thedevguy/whisper-htp-android, MIT). Packages are downloadable in-app and
extracted to `files/qnn/<arch>/`. Design notes:
[docs/QNN_V2_PLAN.md](docs/QNN_V2_PLAN.md).

> Why precompiled? Per Qualcomm's [AI Developer Workflow](https://docs.qualcomm.com/doc/80-70029-15B/topic/run-prebuilt-models-and-apps.html)
> ([ONNX on NPU via ORT](https://docs.qualcomm.com/doc/80-70029-15B/topic/run-an-onnx-model-using-ort.html)),
> the NPU cannot run a raw float ONNX the way the CPU does — the model must be
> converted/quantized into a SoC-specific artifact first (a QAIRT `ctx.onnx` /
> `qairt_context.bin`, or a QDQ-quantized ONNX). That is exactly what the
> per-arch zips below are: AI Hub compiles Whisper once per Hexagon version in
> the cloud, and the app downloads the binary matching the phone's chip. A
> float `.bin`/`.onnx` can never load on the NPU, no matter the runtime.

#### Where the model files actually live

Official listings:
- https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo (fp16 — what the app uses)
- https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo-Quantized (w8a16 —
  needs a different decode path, not yet supported)

Both pages are only manifests: their `release_assets.json` points into
Qualcomm's AI Hub S3 bucket (`qaihub-public-assets.s3.us-west-2.amazonaws.com`),
same release (v0.61.0), same bytes the app downloads. The per-chipset zip is
`whisper_large_v3_turbo-precompiled_qnn_onnx-float-<chip>.zip` (underscores —
dashed names 403) and contains exactly `encoder.onnx`, `encoder_qairt_context.bin`,
`decoder.onnx`, `decoder_qairt_context.bin` + metadata.

#### Context binaries are arch-locked

A binary compiled for one Hexagon version loads only on that DSP. The app reads
`Build.SOC_MODEL` (API 31+) and matches it (substring — Galaxy variants carry
suffixes like `SM8750-AC`):

| Hexagon | SoC model | Chips |
|---|---|---|
| v69 | SM8450 / SM8475 | Snapdragon 8 Gen 1 / 8+ Gen 1 |
| v73 | SM8550 / SM8635 / SM7675 / QCS8550 | 8 Gen 2 / 8s Gen 3 / 7+ Gen 3 |
| v75 | SM8650 | Snapdragon 8 Gen 3 |
| v79 | SM8750 | Snapdragon 8 Elite (incl. -AC Galaxy variants) |
| v81 | SM8850 | Snapdragon 8 Elite Gen 5 |

#### Vocab / special tokens (the trap that costs a day)

The vocab JSON (`whisper_vocab_v3.json`, 51866 entries) stores tokens as a
base64 JSON array, but **all special tokens are empty entries** — you cannot
look up `<|startoftranscript|>` by string. The ids are fixed constants for the
AI Hub exports, and they are *not* the standard ones:

- `EOT = 50257` (argmax cutoff too — see `htp_whisper.cpp`)
- prompt = `[SOT=50258, lang, TRANSCRIBE, NOTIMESTAMPS]`
  - v3 (turbo): `TRANSCRIBE = 50360`, `NOTIMESTAMPS = 50364`
  - v2 (small): `TRANSCRIBE = 50359`, `NOTIMESTAMPS = 50363`
- languages start at `50259` (`<|en|>` = 50259, `<|pt|>` = 50267) in whisper's
  standard language order.

Wrong ids here don't crash — every decode step yields nothing (or EOT
immediately) and transcriptions come back **empty** while looking fast.

#### Debugging

logcat tags: `QnnWhisper` (engine), `HtpWhisper` (native decode/encode timings),
`WhisperMel` (features), `Transcription` (pipeline). A healthy run logs
`encoder: ~630 ms on HTP` then `decoder: N steps … tokens … avg_p=…`.

In the app: *Settings → Compute backend* offers **QNN — Whisper Turbo fp16 on
NPU**, **NPU — Hexagon ggml (experimental)**, **GPU — OpenCL (opt-in)** and
**CPU**. OpenCL is off unless explicitly selected: some OEM Adreno drivers abort
inside ggml's CL_CHECK paths (uncatchable from Java), so the shim keeps it
disabled until the user opts in. Every engine degrades gracefully to CPU.

Local patches carried on top of the vendored `whisper-src` (v1.9.3):
- `ggml/src/ggml-hexagon/htp-drv.cpp` — dlopens `libcdsprpc.so` from absolute
  vendor paths as well as by plain name.

## License notes

whisper.cpp is MIT; the ggml model files on `ggerganov/whisper.cpp` inherit the
Apache-2.0/MIT licensing of the original Whisper weights; OpenCL-Headers and the
vendored Khronos code are Apache-2.0.
