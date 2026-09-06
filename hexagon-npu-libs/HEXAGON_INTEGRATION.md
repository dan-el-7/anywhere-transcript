# Hexagon NPU libraries — drop-in handoff

Prebuilt native libraries that add the experimental Qualcomm Hexagon NPU backend
(`GGML_HEXAGON`) to this project, built from a snapshot of **this repo's own sources**
(`whisper-jni/` + `whisper-src/` @ v1.9.3, commit 371b5a7).

The app's workspace was untouched — everything here lives in `hexagon-npu-libs/`.

## Files (arm64-v8a)

| File | Size | Purpose |
|---|---|---|
| `libwhisperjni.so` | 33.1 MB | Full JNI lib: whisper + ggml + CPU + OpenCL + **Hexagon**, statically linked |
| `libggml-htp-v73.so` | 645 KB | DSP kernel driver — Snapdragon 8 Gen 2 |
| `libggml-htp-v75.so` | 714 KB | DSP kernel driver — Snapdragon 8 Gen 3 |
| `libggml-htp-v79.so` | 730 KB | DSP kernel driver — Snapdragon 8 Elite |
| `libggml-htp-v81.so` | 730 KB | DSP kernel driver — Snapdragon 8 Elite Gen 5 |

Build: NDK r28b, `ANDROID_PLATFORM=android-26`, Release, `GGML_OPENMP=OFF`.
Runtime deps of `libwhisperjni.so` are only system libs: `liblog`, `libdl`, `libm`, `libc`.

sha256:
```
f2316e28fe39fce0c44f4b2e8d1ddca2890460bf019dd338e5edaeda74cef6d7  libwhisperjni.so
379e8a7e6c75578a18e2ec09469e8fa9a721ff5239f61ac1e2f2d648bf1bb4d9  libggml-htp-v73.so
04a3e2b79fb3b26326a74c8715a4f9b4c8c41a8266ac611a4ae46ca6b45d9e67  libggml-htp-v75.so
30331856e38ceb331b29c82718e96063413a102636df2bb4ac051fa7a284d1fb  libggml-htp-v79.so
535b26da89163ed2b87cf0ecbd6ebcda555222dbe74784f91aa79435689494b8  libggml-htp-v81.so
```

## Required code fixes (found while building your snapshot)

1. **`whisper-jni/whisper_jni.cpp` line ~160 — compile error.**
   `whisper_model_is_multilingual()` does not exist in whisper.cpp v1.9.3.
   Rename the call to `whisper_is_multilingual()`. (This breaks the normal Gradle
   build too, not just the NPU build.)

2. **Add `-DGGML_OPENMP=OFF` to the cmake `arguments` in `app/build.gradle.kts`.**
   Without it the link pulls in `libomp.so`, which is not a public Android system
   library. Upstream's Android presets disable OpenMP for the same reason
   (ggml falls back to its std::thread pool).

## Drop-in integration

1. Copy the five `.so` files into `app/src/main/jniLibs/arm64-v8a/`.
2. In `app/build.gradle.kts` (defaultConfig) **remove** `ndk { abiFilters += "arm64-v8a" }`
   so Gradle's externalNativeBuild stops producing its own arm64 `libwhisperjni.so`
   (it would collide with the prebuilt one). Keep the debug-only `x86_64` filter as is —
   emulator builds keep working, and arm64 APKs package the prebuilt libs.
3. No Kotlin changes are required for the lib to load. On a supported Snapdragon,
   `WhisperEngine.backends()` will report a GPU-kind device named `Hexagon…`.

## Making the UI use it

`TranscriptionCoordinator.pickBackend()` currently maps `"gpu"`/`"auto"` to
`gpus.firstOrNull()`, so on an Adreno device the OpenCL GPU may win. To expose the
NPU explicitly:

- Add a settings radio row, e.g. **"NPU — Hexagon (experimental)"**, persisting
  `backendPref = "npu"`.
- In `pickBackend()` add: `"npu" -> gpus.firstOrNull { it.name.startsWith("Hexagon") } ?: cpuDevice()`.

## Runtime expectations & caveats

- First NPU use logs lines like `ggml-hex: Hexagon Arch version v79` in logcat.
- Loading the DSP can fail on devices that disallow unsigned Programmable Driver
  sessions (vendor/SELinux policy). whisper's scheduler then falls back — ops run on
  CPU/GPU instead. Treat NPU as best-effort; always keep a CPU retry path.
- Start with `large-v3-turbo-q8_0`; q8_0 is the quant this path is designed around.

## Rebuild recipe (WSL2, Ubuntu, 16 threads)

```bash
# one-time: Hexagon SDK 6.6.0.0 at ~/hexagon/6.6.0.0 (from the public Qualcomm
# GitHub mirror), Linux NDK r28b at ~/android-ndk-r28b, sources at ~/whisper-build
cmake -S ~/whisper-build/whisper-jni -B ~/whisper-build/build-hex -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$HOME/android-ndk-r28b/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release \
  -DPREBUILT_LIB_DIR=android_aarch64 \
  -DGGML_OPENCL=ON -DGGML_NATIVE=OFF -DGGML_BACKEND_DL=OFF -DGGML_OPENMP=OFF \
  -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=OFF -DWHISPER_BUILD_SERVER=OFF \
  -DWHISPER_CURL=OFF \
  -DWH_ENABLE_HEXAGON=ON \
  -DHEXAGON_SDK_ROOT=$HOME/hexagon/6.6.0.0 \
  -DHEXAGON_TOOLS_ROOT=$HOME/hexagon/6.6.0.0/tools/HEXAGON_Tools/19.0.07

ninja -C ~/whisper-build/build-hex
# the DSP kernels are separate ExternalProject targets, not built by default:
ninja -C ~/whisper-build/build-hex htp-v73 htp-v75 htp-v79 htp-v81
```

Products: `build-hex/libwhisperjni.so` and
`build-hex/whisper-build/ggml/src/ggml-hexagon/libggml-htp-v{73,75,79,81}.so`.

Notes: `PREBUILT_LIB_DIR=android_aarch64` is required at configure time (the SDK's
`hexagon_fun.cmake` does unquoted `string(FIND ${PREBUILT_LIB_DIR} ...)` before the
per-arch skel builds set it). The Hexagon SDK was obtained without a Qualcomm login:
`github.com/snapdragon-toolchain/hexagon-sdk` release `v6.6.0.0`, asset
`hexagon-sdk-v6.6.0.0-amd64-lnx.tar.xz` (694 MB).
