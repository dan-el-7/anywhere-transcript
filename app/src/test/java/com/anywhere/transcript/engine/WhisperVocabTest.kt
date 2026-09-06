package com.anywhere.transcript.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class WhisperVocabTest {

    private fun writeVocab(entries: List<String>): File {
        val f = File.createTempFile("vocab", ".json")
        val arr = entries.joinToString(",") { '"' + Base64.getEncoder().encodeToString(it.toByteArray()) + '"' }
        f.writeText("[$arr]")
        return f
    }

    @Test
    fun `decodes base64 entries back to text`() {
        val f = writeVocab(listOf("", " hello", " world"))
        val vocab = WhisperVocab(f)
        assertEquals("hello world", vocab.decode(intArrayOf(1, 2)).trim())
    }

    @Test
    fun `finds special token ids`() {
        val f = writeVocab(listOf("<|startoftranscript|>", "<|en|>", "<|transcribe|>", "<|notimestamps|>"))
        val vocab = WhisperVocab(f)
        assertEquals(0, vocab.idOf("<|startoftranscript|>"))
        assertEquals(1, vocab.idOf("<|en|>"))
        assertEquals(2, vocab.idOf("<|transcribe|>"))
        assertEquals(3, vocab.idOf("<|notimestamps|>"))
        assertEquals(-1, vocab.idOf("<|missing|>"))
    }

    @Test
    fun `out of range ids are ignored`() {
        val f = writeVocab(listOf(" a", " b"))
        val vocab = WhisperVocab(f)
        // real whisper vocab entries carry leading spaces (BPE convention)
        assertEquals(" a b", vocab.decode(intArrayOf(0, 5, 1, -3)))
    }
}
