# Anywhere Transcript

**Offline speech-to-text for Android.** Share any audio from any app — or record
live — and Whisper transcribes it entirely on your device. No cloud, no
accounts, no storage permissions.

Built with **Kotlin + Jetpack Compose (Material 3 Expressive)** and
**whisper.cpp** (v1.9.3) compiled natively via the NDK. On Snapdragon flagships
it runs Whisper Turbo on the **Hexagon NPU** through Qualcomm's QNN runtime at
~7× realtime.

---

## Why another transcription app?

Everything else either uploads your voice to a server or runs a toy model.
This app runs the *real* Whisper — up to Large-V3-Turbo — with the same
privacy as a notes app: audio never leaves the device, and the entire pipeline
(engine, models, UI) works in airplane mode.

| | |
|---|---|
| 🎙️ **Record tab** | Live transcription while you speak — text streams in every few seconds; the finished take is re-transcribed with full context and saved to History |
| 📤 **Share target** | Hit *Share* on any audio/video and choose *Anywhere Transcript* |
| 📁 **File picker** | Pick any audio file from the Transcribe tab |
| 🔒 **100% offline** | Engine, models, UI — airplane mode is a feature |
| ⚡ **NPU fast** | Whisper Turbo fp16 on the Hexagon NPU (QNN): ~7× realtime on Snapdragon 8 Elite |

---

## Engines

The engine follows the model, with a manual override in
*Settings → Compute backend*. The app always reports honestly which engine it
can actually use:

| Engine | Status | Notes |
|---|---|---|
| **QNN (Hexagon NPU)** | ✅ Works well | Whisper Turbo fp16 as precompiled context binaries (~2 GB/arch, Snapdragon 8 Gen 1 → 8 Elite Gen 5); ~7× realtime on 8 Elite |
| **CPU** | ✅ Works everywhere | Including non-Qualcomm SoCs; slower for large models |
| **OpenCL / Vulkan GPU** | ⚠️ Opt-in, iffy | Some Adreno drivers abort uncatchably, so OpenCL stays off unless explicitly picked |

### NPU support: QNN precompiled packages only

**There is no direct/raw NPU support, and there can't be one.** A raw
whisper.cpp model (`.bin` ggml file) will never run on the Hexagon NPU:

- Qualcomm's HTP executes **precompiled, SoC-specific graphs only** — the model
  must be compiled into QAIRT context binaries (a `ctx.onnx` +
  `qairt_context.bin` pair) for *your exact Hexagon version* first.
  A float `.bin`/`.onnx` can never load on the NPU, no matter the runtime.
- App-UID DSP sessions for raw dispatch are **OEM-gated** and fail on retail
  devices.
- Same wall on MediaTek: the NeuroPilot runtime API is vendor-partition only
  for third-party apps.

The NPU is therefore reached exclusively via **QNN precompiled packages**
(Whisper Turbo fp16 compiled by Qualcomm AI Hub per SoC). Direct Hexagon
dispatch ("Engine 1") was removed in v0.2.3; the NPU story is QNN or nothing.

> Want to import your own QNN package? Models → *Import file* now accepts
> AI Hub `.zip` packages too — it sniffs the target arch from the zip, rejects
> wrong-chip builds, and extracts the payload into place. The regular
> `.bin` import path still works exactly as before.

---

## Using the NPU (user guide)

Works on Snapdragon **8 Gen 1 through 8 Elite Gen 5**. First launch shows a
picker that already highlights the best option for your chip; afterwards
everything lives in *Models* and *Settings → Compute backend*.

1. **Models tab** — find the entry marked *"✓ Your chip"*:
   - **Whisper Turbo · NPU (v79)** etc. — Large-V3-Turbo fp16 as QNN context
     binaries (~2 GB). Best quality and speed; runs entirely on the Hexagon
     NPU (~630 ms per 30 s window encoder + ~18 ms/token decoder ≈ **7×
     realtime** on a Snapdragon 8 Elite).
   - any **q8_0** ggml model (e.g. Large-v3-Turbo q8_0, 834 MB) — runs on CPU
     anywhere.
2. **Settings → Compute backend**: pick **QNN** for the Turbo package (the app
   also routes automatically: a qnn-* model always runs on the QNN engine).
   *Auto* uses GPU when available, else CPU (the NPU is reached via QNN); if
   the chosen backend fails at init, the job **retries on CPU automatically**.
3. Share any audio file from any app, pick one in the Transcribe tab, or hit
   **Record** and speak.

The first transcription opens the QNN sessions (~10–20 s); after that each
run is instant to start.

## Recording with live transcription

The **Record** tab (4th slot in the bottom bar) captures from the microphone
and transcribes *while you speak*:

- Audio is resampled to 16 kHz on the fly; every ~5 s of speech is decoded as
  its own chunk and appended to the live transcript — on the NPU this tracks
  the speaker within a few seconds.
- **When you stop recording**, the complete take is transcribed again from
  the start with full context (chunk-boundary words heal themselves), saved
  to History, and shown on the Transcribe tab like any file result.
- Live decoding uses the same engine routing as file transcription: QNN when
  the Turbo package is selected/present, else whisper.cpp on CPU. If live
  decoding hits a snag, capture keeps running and the end-of-recording pass
  still produces the authoritative transcript.

---

## Models

- **QNN NPU packages** — Whisper Turbo fp16 context binaries per Hexagon
  arch, arch-locked (see table below). Downloadable in-app from Qualcomm AI
  Hub, or imported from a `.zip` (Models → *Import file*).
- **ggml models** — downloadable on demand from Hugging Face
  ([ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp)) with
  progress, HTTP resume, delete, and free manual selection (English-only and
  multilingual variants). q8_0 quants are near-lossless. Custom URLs and
  local `.bin` imports supported.

Three device tiers (auto-detected from RAM, overridable in Settings) steer
the default model:

| Tier | Recommended model | Size |
|---|---|---|
| Low-end · 4 GB | base-q8_0 | ~78 MB |
| Mid-range · 8 GB | small-q8_0 | ~256 MB |
| Flagship · 16 GB | large-v3-turbo-q8_0 | ~834 MB |

### Which QNN package fits my phone?

Context binaries are arch-locked: a build for one Hexagon version loads only
on that DSP. The app reads `Build.SOC_MODEL` and matches it (substring —
Galaxy variants carry suffixes like `SM8750-AC`):

| Hexagon | SoC model | Chips |
|---|---|---|
| v69 | SM8450 / SM8475 | Snapdragon 8 Gen 1 / 8+ Gen 1 |
| v73 | SM8550 / SM8635 / SM7675 / QCS8550 | 8 Gen 2 / 8s Gen 3 / 7+ Gen 3 |
| v75 | SM8650 | Snapdragon 8 Gen 3 |
| v79 | SM8750 | Snapdragon 8 Elite (incl. -AC Galaxy variants) |
| v81 | SM8850 | Snapdragon 8 Elite Gen 5 |

---

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
./gradlew testDebugUnitTest      # unit tests (resampler, wav, tiers, catalog, qnn import)
```

Debug builds contain `arm64-v8a` + `x86_64` (emulator testing); release builds
are `arm64-v8a` only by default.

---

## Architecture notes

- `whisper-jni/` — CMake + JNI wrapper (`whisper_jni.cpp`). Backend selection
  maps a backend name (e.g. `OpenCL`) to whisper's `use_gpu`/`gpu_device`
  params; a GPU failure at init falls back to CPU.
- `whisper-jni/opencl_shim/` — generated dlopen shim. Android apps must not
  DT_NEEDED-link vendor `libOpenCL.so` (outside the app linker namespace), so
  the shim resolves every OpenCL entry point with `dlsym` and forwards via
  tail-jump trampolines. If no OpenCL runtime exists, `clGetPlatformIDs`
  returns `CL_PLATFORM_NOT_FOUND_KHR` and ggml registers no GPU device (clean
  CPU fallback). Regenerate with `python3 cmake/gen_opencl_shim.py`.
- `audio/` — MediaExtractor/MediaCodec decode → mono → streaming linear
  resample to 16 kHz (`RateConverter`), windowed output so memory stays
  bounded on 4 GB devices (60/120/300 s windows per tier). Plain WAVs use a
  fast RIFF path. `WavWriter` streams recordings to disk with header patching.
- `transcription/` — `TranscriptionCoordinator` (file pipeline),
  `LiveRecordingSession` (mic capture + chunked live decode), and the
  process-wide `TranscriptionBus` state.
- `data/` — model catalog + OkHttp downloader (resume via Range), DataStore
  settings, QNN zip import, Room history.
- `service/` — foreground services (`mediaProcessing` on API 35+,
  `dataSync` before) with progress + cancel.

## QNN runtime details

Whisper Large-V3-Turbo fp16 as **Qualcomm AI Hub context binaries**, executed
through ONNX Runtime's QNN Execution Provider (`onnxruntime-android-qnn` +
`qnn-runtime` Maven artifacts; native core vendored from
thedevguy/whisper-htp-android, MIT). Packages are downloadable in-app and
extracted to `files/qnn/<arch>/`. Design notes:
[docs/QNN_V2_PLAN.md](docs/QNN_V2_PLAN.md).

> Why precompiled? Per Qualcomm's
> [AI Developer Workflow](https://docs.qualcomm.com/doc/80-70029-15B/topic/run-prebuilt-models-and-apps.html)
> ([ONNX on NPU via ORT](https://docs.qualcomm.com/doc/80-70029-15B/topic/run-an-onnx-model-using-ort.html)),
> the NPU cannot run a raw float ONNX the way the CPU does — the model must be
> converted/quantized into a SoC-specific artifact first (a QAIRT `ctx.onnx` /
> `qairt_context.bin`, or a QDQ-quantized ONNX). That is exactly what the
> per-arch zips are: AI Hub compiles Whisper once per Hexagon version in the
> cloud, and the app downloads the binary matching the phone's chip.

Official listings:

- https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo (fp16 — what the app uses)
- https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo-Quantized (w8a16 —
  needs a different decode path, not yet supported)

Both pages are only manifests: their `release_assets.json` points into
Qualcomm's AI Hub S3 bucket (`qaihub-public-assets.s3.us-west-2.amazonaws.com`),
same release (v0.61.0), same bytes the app downloads. The per-chipset zip is
`whisper_large_v3_turbo-precompiled_qnn_onnx-float-<chip>.zip` (underscores —
dashed names 403) and contains exactly `encoder.onnx`,
`encoder_qairt_context.bin`, `decoder.onnx`, `decoder_qairt_context.bin` +
metadata.

### Vocab / special tokens (the trap that costs a day)

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

### Debugging

logcat tags: `QnnWhisper` (engine), `HtpWhisper` (native decode/encode timings),
`WhisperMel` (features), `Transcription` (pipeline), `LiveRecord` (mic +
live chunks). A healthy run logs `encoder: ~630 ms on HTP` then
`decoder: N steps … tokens … avg_p=…`.

In the app: *Settings → Compute backend* offers **QNN**, **GPU (opt-in)** and
**CPU**, with *Auto* as the default. Every engine degrades gracefully to CPU.

Local patches carried on top of the vendored `whisper-src` (v1.9.3):

- `ggml/src/ggml-hexagon/htp-drv.cpp` — dlopens `libcdsprpc.so` from absolute
  vendor paths as well as by plain name.

## License notes

whisper.cpp is MIT; the ggml model files on `ggerganov/whisper.cpp` inherit
the Apache-2.0/MIT licensing of the original Whisper weights; OpenCL-Headers
and the vendored Khronos code are Apache-2.0.
