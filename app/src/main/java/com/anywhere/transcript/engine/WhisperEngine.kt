package com.anywhere.transcript.engine

import android.util.Log

/** Callbacks invoked from native code during transcription. */
interface TranscriptionCallback {
    /** Decode/decode-loop progress, 0..100, for the current transcribe() call. */
    fun onProgress(percent: Int)

    /** Newly finalized segment (ms). Returning value is currently ignored by native code. */
    fun onSegment(startMs: Long, endMs: Long, text: String): Boolean

    /** Polled between internal operations; return false to abort transcription. */
    fun shouldContinue(): Boolean
}

data class BackendDevice(val name: String, val description: String, val kind: String) {
    val isGpu: Boolean get() = kind == "gpu"
}

/**
 * Thin JNI wrapper around whisper.cpp (libwhisperjni.so).
 * Handles are opaque pointers; create/destroy from the same coroutine is safe,
 * but a context must not be used from multiple threads concurrently.
 */
object WhisperEngine {

    private const val TAG = "WhisperEngine"

    init {
        System.loadLibrary("whisperjni")
    }

    /** whisper_print_system_info(): compiled backends and CPU features. */
    external fun systemInfo(): String

    /** Compiled backend devices, each "name;description;cpu|gpu|accel". */
    external fun listBackends(): Array<String>

    /** Returns an opaque handle, or 0 on failure. backendName "CPU" forces CPU. */
    external fun createContext(modelPath: String, backendName: String, threads: Int): Long

    external fun destroyContext(handle: Long)

    external fun isMultilingual(handle: Long): Boolean

    /** Runs full transcription; returns false on failure or abort. */
    external fun transcribe(
        handle: Long,
        pcm: FloatArray,
        language: String,
        translate: Boolean,
        callback: TranscriptionCallback,
    ): Boolean

    fun backends(): List<BackendDevice> = try {
        listBackends().mapNotNull { s ->
            val parts = s.split(";")
            if (parts.size == 3) BackendDevice(parts[0], parts[1], parts[2]) else null
        }
    } catch (t: Throwable) {
        Log.e(TAG, "listBackends failed", t)
        emptyList()
    }

    fun gpuBackends(): List<BackendDevice> = backends().filter { it.isGpu }

    /** Default thread count: all cores (whisper.cpp scales well on big.LITTLE), clamped. */
    fun defaultThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(4, 8)
}
