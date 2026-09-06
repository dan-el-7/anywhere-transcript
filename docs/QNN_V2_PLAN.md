# V2 Engine Plan — QNN/Hexagon NPU for Whisper Large-V3-Turbo

Status: **planned, not started**. This document is the implementation plan for a
second inference engine that runs Whisper Large-V3-Turbo on the Hexagon NPU via
the QNN runtime — the same architecture LocalDream uses for Stable Diffusion and
Qualcomm's AuraTranslator uses for live Whisper translation.

## Why a second engine

The v1 engine (whisper.cpp + `ggml-hexagon`) is blocked on OEMs like ColorOS:
its raw FastRPC session open (`unsigned PD`) is rejected and the backend
hard-aborts on session failure. The QNN runtime handles the same DSP through a
production-grade session manager with graceful error codes, and — critically —
**the whole Whisper model is pre-converted into a QNN context binary**, so the
app never opens raw FastRPC sessions itself; `libQnnHtp.so` does that internally
with whatever the device permits.

## Proven prior art (do not reinvent)

| Asset | What it gives us |
|---|---|
| [whisper-htp-android](https://github.com/thedevguy/whisper-htp-android) | Reference Android app: Whisper Large-V3-Turbo (fp16) or Small (w8a16) on HTP via AI Hub context binaries + ORT QNN EP. "No SDK downloads needed just to run." Copy its loading/deployment pattern. |
| [AI Hub — Whisper-Large-V3-Turbo-Quantized](https://aihub.qualcomm.com/compute/models/whisper_large_v3_turbo_quantized) | Officially compiled context binaries (MHA → single-head attention for edge). Downloadable per target SoC (v73/v75/v79/v81). |
| [FluidInference/whisper-large-v3-turbo-qnn (HF)](https://huggingface.co/FluidInference/whisper-large-v3-turbo-qnn) | Pre-packaged ONNX/QNN model, validated against `onnxruntime-qnn==1.22.0`. Candidate for direct HF-hosted download (fits our existing downloader!). |
| [onnxruntime-android-qnn (Maven Central)](https://central.sonatype.com/artifact/com.microsoft.onnxruntime/onnxruntime-android-qnn) | ORT Android AAR **with QNN EP included** — removes the custom-ORT-build risk entirely. |
| [AuraTranslator](https://www.qualcomm.com/developer/project/aura-translator) | Qualcomm's proof this architecture ships: Whisper-Large-V3-Turbo on Hexagon via precompiled context binaries + ORT QNN EP. |

## Architecture

```
audio → 16kHz mono PCM (existing AudioDecoder, unchanged)
      → log-mel front-end (CPU, ~ms; whisper.cpp mel code ported or Kotlin DSP)
      → ORT session (QNN EP, context binary)   ← NPU: encoder (+decoder w/ SHA)
      → autoregressive decode loop (ORT; KV cache; GPU/CPU partition where QNN
        EP falls back) → token stream → whisper.cpp tokenizer vocabulary
      → segments/timestamps → existing Bus/UI/History (unchanged)
```

- The **whisper.cpp v1 engine stays** as the default + fallback: QNN is a second
  `Engine` implementation behind the same `TranscriptionCoordinator` flow.
- Tokenizer: reuse whisper.cpp's tokenizer via a small JNI accessor (no new dep),
  or `tokenizers-cpp` if that proves easier.
- Timestamps: standard Whisper timestamp-token decoding in the loop
  (`<|0.00→|>`-style tokens); keep the timestamps toggle UX.

## Model strategy ("turboquant, best model available")

1. **Primary**: `whisper_large_v3_turbo_quantized` context binary from AI Hub
   (w8a16-class quant, SHA-optimized). Best accuracy-per-watt available on HTP.
2. **Per-arch builds**: context binaries are SoC-generation-locked — build/download
   v73 (8 Gen 2), v75 (8 Gen 3), v79 (8 Elite), v81 (8 Elite Gen 5). The app
   detects `ggml-hex` arch (already logged) and picks the matching binary.
3. **Distribution**: HF-hosted (matches existing downloader + custom-URL support),
   with sizes expected ~0.8–1.6 GB for turbo (vs 834 MB ggml q8_0). Consider a
   zstd-compressed asset like LocalDream (`zstd.h` is in their build for this).
4. **Fallback ladder**: QNN turbo → whisper.cpp turbo (CPU) → tier model. Never
   block transcription on QNN availability.

## Phases & estimates

| Phase | Work | Estimate | Gate |
|---|---|---|---|
| 0 · Spike | Minimal harness (or run whisper-htp-android as-is) on the 8 Elite: ORT-QNN session open + tiny context binary. Answers THE question: does QNN's session manager pass where raw FastRPC was denied? | 0.5 day | Session opens; if not, v2 is parked (engine would still help other devices) |
| 1 · Model | Obtain turbo-quantized context binary for v79 (AI Hub account or FluidInference HF pull); validate ORT-QNN load from `filesDir` | 0.5–1 day | Inference matches expected text on a test clip |
| 2 · Engine | `QnnWhisperEngine`: mel front-end, decode loop, tokenizer bridge, streaming segments + cancel | 1–2 days | Parity with v1 UX |
| 3 · App | Settings backend "QNN (NPU)", catalog entries + per-arch download, storage management, CPU fallback ladder | 1 day | Release-quality |
| 4 · Harden | Memory tuning (HTP vmem limits), long-file windowing, multilingual check, perf capture (target: ≥5–10× realtime on 8 Elite) | 1 day | Test matrix green |

**Total: ~4–5 working days**, gated by Phase 0 (half a day to a definitive
go/no-go on this Realme).

## Risks / open questions

1. **ColorOS DSP policy (the big one)** — raw FastRPC was denied; QNN's session
   manager may still be denied the same way. Phase 0 answers it. If denied:
   v2 still ships for permissive devices, and this phone stays CPU (documented).
2. **Turbo decoder placement** — AI Hub's quantized turbo runs the full model,
   but autoregressive decode loop cost on HTP vs CPU must be measured; may be
   faster to keep decode on CPU and only the encoder on NPU (encoder ≈ 90% of
   compute for turbo).
3. **Context-binary ↔ SoC lock** — need one binary per Hexagon arch; storage
   budget per arch (~1 GB). Mitigate with on-demand download (already built).
4. **Timestamp quality** with SHA (single-head attention) variants — verify
   segment timestamps remain usable; timestamps toggle already exists.
5. **Memory** — HTP vmem cap (~4 GB shared with system); turbo fp16 may exceed —
   the *quantized* variant is the safe target (hence "turboquant").
6. **Licensing** — Whisper weights MIT; AI Hub model terms permit app use;
   QNN SDK runtime redistribution is permitted (LocalDream ships it on Play).

## When picked up

Start at Phase 0 with `whisper-htp-android` on the connected 8 Elite. The
existing adb-driven test rig (SAPI speech wav + share-intent + logcat capture)
reuses as-is for validation.
