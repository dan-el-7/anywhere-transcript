#!/usr/bin/env bash
# End-to-end device test for Anywhere Transcript (QNN + whisper.cpp engines).
# Prereqs: adb device connected w/ USB debugging; model zip extracted at
# .tmp/turbo-model/... (see docs/QNN_V2_PLAN.md); test wav at .tmp/test-speech.wav.
set -uo pipefail
cd "$(dirname "$0")/.."

ADB=${ADB:-/d/IDEs/SDK/platform-tools/adb.exe}
PKG=com.anywhere.transcript
QNN_DIR=/sdcard/Android/data/$PKG/files/qnn

"$ADB" devices | grep -qw device || { echo "NO DEVICE"; exit 1; }

echo "== install"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk || exit 1

echo "== push QNN model files (skip if present)"
"$ADB" shell mkdir -p $QNN_DIR
SRC=.tmp/turbo-model/whisper_large_v3_turbo-precompiled_qnn_onnx-float-qualcomm_snapdragon_8_elite_for_galaxy
for f in encoder.onnx encoder_qairt_context.bin decoder.onnx decoder_qairt_context.bin; do
  [ -f "$SRC/$f" ] || { echo "missing $SRC/$f — extract the model zip first"; exit 1; }
done
[ -f .tmp/test-speech.wav ] || { echo "missing .tmp/test-speech.wav"; exit 1; }

"$ADB" logcat -c

echo "== QNN run (Turbo fp16 on HTP)"
"$ADB" shell am start-foreground-service -n $PKG/.service.TranscriptionService \
  -a com.anywhere.transcript.action.START \
  -d "file:///sdcard/Android/data/$PKG/files/test-speech.wav" \
  --es extra_name test-qnn --es extra_backend qnn
sleep 60
PID=$("$ADB" shell pidof $PKG | tr -d '\r')
"$ADB" logcat -d --pid=$PID 2>/dev/null | grep -aE "QnnWhisper|Transcription|HtpWhisper|window done|done: segments" | head -30

echo "== whisper.cpp run (auto backend: NPU->GPU->CPU)"
"$ADB" shell am start-foreground-service -n $PKG/.service.TranscriptionService \
  -a com.anywhere.transcript.action.START \
  -d "file:///sdcard/Android/data/$PKG/files/test-speech.wav" \
  --es extra_name test-v1
sleep 90
PID=$("$ADB" shell pidof $PKG | tr -d '\r')
"$ADB" logcat -d --pid=$PID 2>/dev/null | grep -aE "Transcription|whisper-jni|ggml-hex" | tail -30
