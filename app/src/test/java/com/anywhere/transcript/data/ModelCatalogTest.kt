package com.anywhere.transcript.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun `ids are unique and files are ggml bins or qnn packages`() {
        val ids = ModelCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ModelCatalog.all.forEach { m ->
            if (ModelCatalog.isQnnPackage(m.id)) {
                assertTrue(m.file.endsWith(".zip"))
                assertTrue(m.url.startsWith("https://qaihub-public-assets.s3.us-west-2.amazonaws.com/"))
                assertTrue(m.url.endsWith(".zip"))
            } else {
                assertTrue(m.file.startsWith("ggml-") && m.file.endsWith(".bin"))
                assertTrue(m.url.startsWith("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"))
                assertTrue(m.url.endsWith(m.file))
            }
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

    @Test
    fun `qnn packages use the correct AI Hub S3 key format`() {
        ModelCatalog.qnnModels.forEach { m ->
            assertTrue(m.qnnArch != null)
            assertTrue(m.socLabel != null)
            // keys are underscore-separated after "-float-"; dashes there = 403 from S3
            val key = m.url.substringAfter("-float-")
            assertFalse("S3 key must not contain dashes: ${m.url}", key.contains('-'))
            assertTrue(m.url.startsWith("https://qaihub-public-assets.s3.us-west-2.amazonaws.com/"))
        }
    }

    @Test
    fun `soc models map to hexagon archs`() {
        assertEquals("v69", Hexagon.archForSoc("SM8450"))
        assertEquals("v69", Hexagon.archForSoc("SM8475"))
        assertEquals("v73", Hexagon.archForSoc("SM8550"))
        assertEquals("v73", Hexagon.archForSoc("SM8635"))
        assertEquals("v75", Hexagon.archForSoc("SM8650"))
        assertEquals("v79", Hexagon.archForSoc("SM8750"))
        assertEquals("v81", Hexagon.archForSoc("SM8850"))
        assertEquals(null, Hexagon.archForSoc("SM9999"))
        assertEquals(null, Hexagon.archForSoc(null))
    }

    @Test
    fun `suffixed galaxy soc variants map to the base arch`() {
        assertEquals("v79", Hexagon.archForSoc("SM8750-AC"))
        assertEquals("v79", Hexagon.archForSoc("SM8750-3-NZAE"))
        assertEquals("v81", Hexagon.archForSoc("SM8850-AC"))
        assertEquals("v73", Hexagon.archForSoc("QCS8550"))
    }

    @Test
    fun `onboarding options lead with the NPU package on Hexagon devices`() {
        val withNpu = ModelCatalog.onboardingOptions(DeviceTier.FLAGSHIP, "v79")
        assertEquals("qnn-turbo-v79", withNpu.first().id)
        assertTrue(withNpu.size >= 2)
        // no duplication between tiers and options
        assertEquals(withNpu.size, withNpu.map { it.id }.toSet().size)

        val noNpu = ModelCatalog.onboardingOptions(DeviceTier.MID, null)
        assertEquals(ModelCatalog.recommendedFor(DeviceTier.MID).id, noNpu.first().id)
    }
}
