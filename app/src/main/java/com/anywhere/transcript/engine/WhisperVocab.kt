package com.anywhere.transcript.engine

import android.util.Base64
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Whisper detokenizer. The vocab file is a JSON array indexed by token id,
 * each entry base64-encoded UTF-8 bytes ("" for special/timestamp tokens).
 * (Vendored from theedevguy/whisper-htp-android, MIT.)
 */
class WhisperVocab(vocabFile: File) {
    private val tokens: Array<ByteArray>

    /** id -> decoded token string, for special-token lookups (<|en|> etc.). */
    private val strings: Array<String>

    init {
        val arr = JSONArray(vocabFile.readText())
        tokens = Array(arr.length()) { Base64.decode(arr.getString(it), Base64.DEFAULT) }
        strings = Array(tokens.size) { String(tokens[it], Charsets.UTF_8) }
    }

    val size: Int get() = tokens.size

    fun decode(ids: IntArray): String {
        val bytes = ByteArrayOutputStream()
        for (id in ids) if (id in tokens.indices) bytes.write(tokens[id])
        return bytes.toString(Charsets.UTF_8.name())
    }

    /** First token id whose text equals [text] (e.g. "<|en|>"), or -1. */
    fun idOf(text: String): Int = strings.indexOfFirst { it == text }
}
