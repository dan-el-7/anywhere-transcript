# AGENTS.md — Anywhere Transcript

Offline Android speech-to-text (Whisper on-device). Kotlin + Compose Material 3
Expressive, single Activity, tabs: Transcribe / Models / History.
Repo: `dan-el-7/anywhere-transcript`. Language of the user: short replies,
no filler, no process narration.

## Build / test / install (Windows host, Git-Bash shell)

```bash
JAVA_HOME="D:/IDEs/Android Studio/jbr" ./gradlew assembleDebug testDebugUnitTest
ADB="D:/IDEs/SDK/platform-tools/adb.exe"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
```

- JDK = Android Studio JBR, NOT system java. Tests: unit tests under
  `app/src/test` (tiers, catalog, vocab, resampler). All must stay green.
- `gh` CLI is available for releases (see Release section).

## On-device verification (preferred — user keeps a phone attached)

```bash
"$ADB" shell am force-stop com.anywhere.transcript
"$ADB" shell am start -n com.anywhere.transcript/.MainActivity
# tap bottom tabs by coordinate, then dump UI and grep text:
"$ADB" shell input tap 631 2590   # Models tab (1080x2712 screen)
"$ADB" shell uiautomator dump /sdcard/ui.xml
"$ADB" shell "cat /sdcard/ui.xml" > .tmp/ui.xml
# parse text="..." attributes with python (regex on <node> tags)
```

- Bottom-tab x-centers ≈ 147 (Transcribe) / 470 (Record) / 793 (Models) /
  1116 (History) on the 1080x2712 screen.
- Compose nodes often lack clickable=true — tap TextView centers, it works.
- vision_analyze is BROKEN here (401s) — verify via uiautomator text dumps,
  never screenshots.
- No device attached (`adb devices` empty) → say so, skip on-device checks.

## Storage layout on device (TWO roots — do not confuse)

- ggml models: **internal** `filesDir/models/` (`ModelRepository.dir`).
- QNN context binaries: **external** `getExternalFilesDir()/qnn/<arch>/`
  (`QnnWhisperEngine`), 4 files: encoder/decoder `.onnx` + `*_qairt_context.bin`.
- `diskUsageBytes()` must sum BOTH. Past bug: counted one root only.
- Never `mkdir` under `/sdcard/...` via adb shell — the dir ends up
  shell-owned and invisible to the app. Let the app create dirs itself
  (in-app migration in `QnnWhisperEngine.modelsReady`), or live with it.
  `run-as` cannot write FUSE paths either.

## Backend truth (verified against Qualcomm docs — do not regress)

- **QNN works.** Whisper Turbo fp16 as AI Hub precompiled context binaries,
  arch-locked per Hexagon (v69/v73/v75/v79/v81, matched via `Build.SOC_MODEL`
  substring — Galaxy `-AC` suffixes included). ~7× realtime on 8 Elite.
- **Direct/raw NPU was REMOVED (v0.2.3) — do not re-add.** Per Qualcomm, the HTP
  executes quantized/precompiled graphs only (never raw float), and app-UID DSP
  sessions are OEM-gated. Same wall on MediaTek (NeuroPilot runtime API is
  vendor-partition only for third-party apps). The NPU is used exclusively via
  the QNN engine. The per-SoC skels (`libggml-htp-v*.so`) were deleted from
  `jniLibs`; `ADSP_LIBRARY_PATH` setup was removed from `TranscriberApp`.
  Settings offers Auto / QNN / GPU / CPU only. Stored legacy `"npu"` prefs fall
  through to auto behavior (gpu → cpu) in `pickBackend`.
- **`backendPref == "qnn"` routes to the QNN engine whenever the chip's package
  is present** (coordinator + LiveRecordingSession). It must NEVER fall through
  to whisper.cpp GPU: the GPUOpenCL device sits in ggml's registry even with the
  OpenCL shim disabled, and touching it aborts natively (uncatchable
  `ggml_abort` in `ggml_backend_dev_by_type` — proven by tombstone). The auto
  path likewise only counts GPU as available when the `engine_flags`
  `opencl_enabled` side-channel is set (user opted in via Settings → GPU).
- Engine routing is MODEL-based (`TranscriptionCoordinator`: `qnn-turbo-*`
  → QNN engine under every pref). CPU always works. OpenCL opt-in only
  (some Adreno drivers abort uncatchably). whisper.cpp `listBackends`
  may list HTP — that is detection, not usability.
- **Record tab (live transcription)**: `LiveRecordingSession` preloads the
  engine BEFORE capture (QNN session setup / ggml context = 10–20 s; without
  preload the first live chunk lags far behind the mic). 48 kHz capture →
  `RateConverter` → 16 kHz; every 5 s chunk decodes live (QNN or CPU); the
  whole take is also written to a 16 kHz WAV (`WavWriter`, patched-on-close
  header). Stop → the finished take is re-transcribed IN-PROCESS via
  `TranscriberApp.coordinator` — NOT via `requestTranscribe`/FGS: racing a
  DONE-state service teardown with `startForegroundService` crashes with
  `ForegroundServiceDidNotStartInTimeException`. The bus drives the Record
  screen's final-transcript card; History entry lands normally.
- Special-token ids are hardcoded constants, NOT vocab lookups (AI Hub vocab
  stores specials as empty entries): SOT=50258, TRANSCRIBE=50360 (turbo),
  NOTIMESTAMPS=50364, langs from 50259. Wrong ids → fast but EMPTY output.

## Settings / DataStore

- `SettingsRepository` (DataStore prefs) + `AppSettings` data class.
  New boolean pref = 3 touches: data-class field (with default), Key,
  read mapping + setter. Expose via `AppViewModel` setter, UI in
  `SettingsScreen`. DataStore **persists across `install -r`** — testing a
  "default" requires fresh install or clearing app data (which wipes models).
- Current prefs: tierOverride, backendPref (auto/qnn/npu/gpu/cpu), modelId,
  language, translateToEnglish, dynamicColor, themeMode,
  onboardingDone, hideIncompatibleModels (default true).
- Model selection (`ModelCatalog.selectModel`, mirrored by
  `ModelRepository.selectedOrDefault` — nullable): manual pick if downloaded →
  tier recommendation if downloaded → ANY downloaded ggml model. Null = no
  ggml model at all → coordinator/viewmodel must surface an ERROR
  (modelMissing=true), never silently return (that stranded the FGS in IDLE).
  QNN packages are never a whisper.cpp fallback.

## Transcription pipeline invariants

- `AudioDecoder` accumulator (`out16k`) grows amortized-doubling with a
  separate `outLen` logical length — do NOT revert to per-chunk copyOf
  (that was O(n²) memcpy; decode of long files crawled).
- Backend init failure (createContext == 0) retries on CPU once before
  erroring; the bus's `backend` field is updated so the UI shows what ran.
- Auto backend pref never dispatches raw HTP (GPU or CPU only); there is no
  direct-NPU option anymore (removed v0.2.3, skels deleted).

## Models screen rules

- `backendPref == "qnn"` → QNN packages ONLY (chip-matched recommendation +
  "NPU packages (QNN)" section, ggml tiers hidden).
- `hideIncompatibleModels` (on by default) → hide QNN archs ≠ device arch;
  needs known arch (`Hexagon.deviceArch()`), else show all.
- `ModelCatalog.qnnModels` = the 5 AI Hub zips (S3 URLs, `*_precompiled_qnn_onnx-float-<chip>.zip`,
  underscores — dashed names 403). Sizes are display-only approximations.
- **Import** accepts ggml `.bin` (unchanged) AND AI Hub QNN `.zip` packages:
  `QnnImport` sniffs the arch (vXX token in entry paths, else SoC key in the
  filename), rejects wrong-chip builds, extracts the 4 payload files into
  `files/qnn/<arch>`, then `rescan()` marks the catalog entry downloaded.
- LazyColumn spacing is uniform 8dp — do NOT add per-item top paddings
  (that stacking caused the oversized gaps before).

## Release

```bash
./gradlew assembleDebug  # or assembleRelease
gh release create vX.Y.Z app/build/outputs/apk/debug/app-debug.apk --title ... --notes ...
```

Past releases: v0.2.0 (NPU working), v0.2.1 (onboarding guards, icon).
DeviceTier thresholds use marketing-GB normalization (`DeviceTier.detect`,
×10/9 integer math) — totalMem under-reports by 5–10%.

## Gotchas

- `whisper-jni/`: arm64 uses PREBUILT `app/src/main/jniLibs/arm64-v8a/*.so`
  (Hexagon backend inside). Touching native code needs the Hexagon SDK
  rebuild recipe in `hexagon-npu-libs/HEXAGON_INTEGRATION.md`.
- Manifest needs `<uses-native-library libcdsprpc.so>` + `ADSP_LIBRARY_PATH`
  set from JNI + `useLegacyPackaging` for the DSP loader.
- `.tmp/` holds disposable artifacts (dumps, screenshots, zips) — safe to clear.
- Logcat tags: `QnnWhisper`, `HtpWhisper`, `WhisperMel`, `Transcription`.
