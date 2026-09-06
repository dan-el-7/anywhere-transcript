package com.anywhere.transcript.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class QnnImportTest {

    @Test
    fun `arch sniffing finds vXX tokens in file names`() {
        assertEquals("v79", QnnImport.archFromName("whisper_large_v3_turbo-precompiled_qnn_onnx-float-v79.zip"))
        assertEquals("v73", QnnImport.archFromName("some/path/v73/package.zip"))
        assertEquals("v81", QnnImport.archFromName("v81.zip"))
        // no token → null (AI Hub soc-key names carry no vXX)
        assertEquals(null, QnnImport.archFromName("whisper_large_v3_turbo-precompiled_qnn_onnx-float-qualcomm_snapdragon_8_elite_for_galaxy.zip"))
    }

    @Test
    fun `soc keys map to archs`() {
        assertEquals("v79", QnnImport.archFromSocKey("qualcomm_snapdragon_8_elite_for_galaxy"))
        assertEquals("v81", QnnImport.archFromSocKey("qualcomm_snapdragon_8_elite_gen5_for_galaxy"))
        assertEquals("v75", QnnImport.archFromSocKey("qualcomm_snapdragon_8gen3"))
        assertEquals("v73", QnnImport.archFromSocKey("qualcomm_qcs8550_proxy"))
        assertEquals("v69", QnnImport.archFromSocKey("qualcomm_snapdragon_8gen1"))
        assertEquals(null, QnnImport.archFromSocKey("qualcomm_something_else"))
    }

    @Test
    fun `gen5 soc key wins over plain 8 elite`() {
        // "8_elite_gen5" contains "8_elite" — the ordered list must pick v81
        assertEquals("v81", QnnImport.archFromSocKey("8_elite_gen5_for_galaxy"))
    }

    @Test
    fun `sniffArch reads entry names without extracting`() {
        val zip = zipOf("artifacts/v69/encoder.onnx" to ByteArray(8))
        assertEquals("v69", QnnImport.sniffArch(ByteArrayInputStream(zip)))
    }

    @Test
    fun `extract copies only the four payload files`() {
        val zip = zipOf(
            "artifacts/encoder.onnx" to "enc".toByteArray(),
            "artifacts/encoder_qairt_context.bin" to "ec".toByteArray(),
            "artifacts/decoder.onnx" to "dec".toByteArray(),
            "artifacts/decoder_qairt_context.bin" to "dc".toByteArray(),
            "artifacts/model_card.html" to "junk".toByteArray(),
            "README.txt" to "junk".toByteArray(),
        )
        val dir = File.createTempFile("qnnimp", "").let { it.delete(); it.mkdirs(); it }
        try {
            val written = QnnImport.extract(ByteArrayInputStream(zip), dir)!!
            assertEquals(QnnImport.REQUIRED, written)
            assertEquals("enc", File(dir, "encoder.onnx").readText())
            assertEquals("dc", File(dir, "decoder_qairt_context.bin").readText())
            assertTrue(File(dir, "model_card.html").exists().not())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
