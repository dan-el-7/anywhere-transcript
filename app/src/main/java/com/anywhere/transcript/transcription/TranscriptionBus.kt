package com.anywhere.transcript.transcription

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class JobPhase { IDLE, PREPARING, DECODING, TRANSCRIBING, DONE, ERROR, CANCELLED }

data class TranscriptSegment(val startMs: Long, val endMs: Long, val text: String)

data class TranscriptResult(
    val text: String,
    val segments: List<TranscriptSegment>,
    val fileName: String,
    val modelId: String,
    val modelLabel: String,
    val backend: String,
    val language: String,
    val audioDurationMs: Long,
    val processingMs: Long,
)

data class TranscriptionUiState(
    val phase: JobPhase = JobPhase.IDLE,
    val fileName: String = "",
    val modelId: String = "",
    val modelLabel: String = "",
    val backend: String = "",
    val progress: Float = 0f,
    val partialText: String = "",
    val result: TranscriptResult? = null,
    val error: String? = null,
    /** Set when the error is "no model downloaded yet" so the UI can offer a shortcut. */
    val modelMissing: Boolean = false,
)

/** Process-wide transcription state shared between the foreground service and the UI. */
object TranscriptionBus {
    private val _state = MutableStateFlow(TranscriptionUiState())
    val state: StateFlow<TranscriptionUiState> = _state.asStateFlow()

    @Volatile
    var cancelRequested = false
        private set

    fun update(transform: (TranscriptionUiState) -> TranscriptionUiState) {
        _state.update(transform)
    }

    fun requestCancel() {
        cancelRequested = true
    }

    fun reset() {
        cancelRequested = false
        _state.value = TranscriptionUiState()
    }
}
