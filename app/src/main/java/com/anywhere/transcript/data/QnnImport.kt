package com.anywhere.transcript.data

import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Imports a user-supplied QNN context-binary package (the AI Hub
 * `whisper_large_v3_turbo-precompiled_qnn_onnx-float-<chip>.zip` layout)
 * into the app's per-arch qnn directory.
 *
 * Entry names may carry an arbitrary prefix (AI Hub nests them), so files
 * are matched by basename. The target arch is sniffed from the zip/entry
 * names (a `vXX` token, or the AI Hub SoC key), falling back to this
 * device's Hexagon arch.
 */
object QnnImport {

    /** The four files a complete QNN package must provide. */
    val REQUIRED = setOf(
        "encoder.onnx",
        "encoder_qairt_context.bin",
        "decoder.onnx",
        "decoder_qairt_context.bin",
    )

    /** Hexagon versions AI Hub compiles Whisper Turbo for. */
    private val ARCH_REGEX = Regex("v(69|73|75|79|81)\\b")

    /** AI Hub SoC keys inside package filenames → Hexagon arch.
     *  Order matters: "8_elite_gen5" contains "8_elite". */
    private val SOC_KEYS = listOf(
        "8_elite_gen5" to "v81",
        "8_elite" to "v79",
        "8gen3" to "v75",
        "qcs8550" to "v73",
        "8gen1" to "v69",
    )

    /** Sniffs a Hexagon arch from a vXX token in a file/entry path, or null. */
    fun archFromName(name: String): String? {
        val m = ARCH_REGEX.find(name) ?: return null
        return "v" + m.groupValues[1]
    }

    /** Maps an AI Hub SoC key substring (e.g. "qualcomm_snapdragon_8gen1") to an arch. */
    fun archFromSocKey(name: String): String? {
        val lower = name.lowercase()
        return SOC_KEYS.firstOrNull { (k, _) -> k in lower }?.second
    }

    /** Scans zip entry names for an arch token. Cheap: no extraction. */
    fun sniffArch(stream: InputStream): String? {
        ZipInputStream(stream.buffered()).use { zis ->
            while (true) {
                val e: ZipEntry = zis.nextEntry ?: break
                archFromName(e.name)?.let { return it }
                zis.closeEntry()
            }
        }
        return null
    }

    /**
     * Copies a zip's QNN payload into [outDir] (created). Returns the set of
     * basenames actually written, or null if the stream can't be read.
     */
    fun extract(stream: InputStream, outDir: File): Set<String>? {
        val written = mutableSetOf<String>()
        return try {
            outDir.mkdirs()
            ZipInputStream(stream.buffered()).use { zis ->
                while (true) {
                    val e: ZipEntry = zis.nextEntry ?: break
                    val base = e.name.substringAfterLast('/')
                    if (base in REQUIRED) {
                        File(outDir, base).outputStream().use { zis.copyTo(it) }
                        written.add(base)
                    }
                    zis.closeEntry()
                }
            }
            written
        } catch (t: Throwable) {
            // caller verifies completeness and prunes incomplete dirs
            null
        }
    }
}
