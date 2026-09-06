package com.anywhere.transcript.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun `ids are unique and files are ggml bins`() {
        val ids = ModelCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ModelCatalog.all.forEach { m ->
            assertTrue(m.file.startsWith("ggml-") && m.file.endsWith(".bin"))
            assertTrue(m.url.startsWith("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"))
            assertTrue(m.url.endsWith(m.file))
            assertTrue(m.sizeBytes > 0)
        }
    }

    @Test
    fun `every tier has a recommendation that belongs to it`() {
        DeviceTier.entries.forEach { tier ->
            val recId = ModelCatalog.recommended.getValue(tier)
            val rec = ModelCatalog.byId[recId]
            assertTrue("recommended $recId must exist", rec != null)
            assertEquals(tier, rec?.tier)
        }
    }

    @Test
    fun `english-only models are not multilingual`() {
        ModelCatalog.all.forEach { m ->
            if (m.id.endsWith(".en-q8_0") || m.id.endsWith(".en-q5_1") || m.id.endsWith(".en-q5_0")) {
                assertFalse(m.multilingual)
            }
        }
        // q8_0 quant is the NPU-compatible family: the per-tier recommendations use it
        DeviceTier.entries.forEach { tier ->
            assertTrue(ModelCatalog.recommendedFor(tier).id.endsWith("-q8_0"))
        }
    }

    @Test
    fun `catalog models derive HF urls`() {
        val base = ModelCatalog.byId.getValue("base-q8_0")
        assertEquals("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q8_0.bin", base.url)
    }

    @Test
    fun `custom models keep explicit urls`() {
        val custom = ModelInfo(
            id = "custom-x",
            file = "ggml-my-model.bin",
            sizeBytes = 1,
            tier = DeviceTier.FLAGSHIP,
            multilingual = true,
            params = "custom",
            explicitUrl = "https://example.com/models/ggml-my-model.bin",
        )
        assertEquals("https://example.com/models/ggml-my-model.bin", custom.url)
        // default derivation still applies when no explicit url is set
        val derived = custom.copy(explicitUrl = null)
        assertEquals("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-my-model.bin", derived.url)
    }

    @Test
    fun `tier grouping is complete and sane`() {
        ModelCatalog.forTier(DeviceTier.LOW).forEach { assertTrue(it.sizeBytes < 200L * 1024 * 1024) }
        ModelCatalog.forTier(DeviceTier.MID).forEach { assertTrue(it.sizeBytes < 600L * 1024 * 1024) }
        ModelCatalog.forTier(DeviceTier.FLAGSHIP).isNotEmpty()
    }
}
