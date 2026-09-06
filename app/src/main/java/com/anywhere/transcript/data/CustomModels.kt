package com.anywhere.transcript.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val Context.customModelsDataStore by preferencesDataStore(name = "custom_models")

data class CustomModel(
    val id: String,
    val name: String,
    /** Download URL, or empty for files imported from device storage. */
    val url: String,
    val file: String,
) {
    fun toModelInfo(): ModelInfo = ModelInfo(
        id = id,
        file = file,
        sizeBytes = 0,
        tier = DeviceTier.FLAGSHIP,
        multilingual = true,
        params = "custom",
        note = if (url.isEmpty()) "Imported" else "Custom",
        explicitUrl = url.ifEmpty { null },
    )
}

/** User-added models: by Hugging Face (or any https) URL, or imported from device storage. */
class CustomModelsRepository(private val context: Context, scope: CoroutineScope) {

    private val KEY = stringPreferencesKey("entries")

    private val _entries = MutableStateFlow<List<CustomModel>>(emptyList())
    val entries: StateFlow<List<CustomModel>> = _entries.asStateFlow()

    init {
        scope.launch { _entries.value = load() }
    }

    fun find(id: String): CustomModel? = _entries.value.firstOrNull { it.id == id }

    fun fileFor(id: String): String? = find(id)?.file

    /** Adds a model by URL. Returns null (and changes nothing) for invalid input or duplicates. */
    suspend fun addUrl(url: String): CustomModel? {
        val clean = url.trim()
        if (!isValidModelUrl(clean)) return null
        val file = fileNameFor(clean) ?: return null
        val current = _entries.value
        if (current.any { it.url == clean || it.file == file }) return null
        val entry = CustomModel(
            id = "custom-" + Integer.toHexString(clean.hashCode()),
            name = displayNameFor(clean),
            url = clean,
            file = file,
        )
        save(current + entry)
        return entry
    }

    /** Registers a file copied into the models directory as a custom model. */
    suspend fun addImported(fileName: String): CustomModel {
        val entry = CustomModel(
            id = "custom-" + Integer.toHexString(fileName.hashCode()),
            name = displayNameFor(fileName),
            url = "",
            file = fileName,
        )
        if (_entries.value.none { it.id == entry.id }) {
            save(_entries.value + entry)
        }
        return entry
    }

    suspend fun remove(id: String) {
        save(_entries.value.filterNot { it.id == id })
    }

    fun isValidModelUrl(url: String): Boolean =
        url.startsWith("https://") &&
            (url.endsWith(".bin") || url.contains("/resolve/") || url.contains("/blob/"))

    fun fileNameFor(url: String): String? {
        val last = url.substringBefore('?').substringAfterLast('/')
        if (last.isBlank()) return null
        return if (last.endsWith(".bin")) last else "$last.bin"
    }

    private fun displayNameFor(fileOrUrl: String): String {
        val base = fileOrUrl.substringBefore('?').substringAfterLast('/')
            .removeSuffix(".bin").removePrefix("ggml-")
        return base.replaceFirstChar { it.uppercase() }.ifBlank { "Custom model" }
    }

    private suspend fun load(): List<CustomModel> {
        val prefs = context.customModelsDataStore.data.first()
        return parse(prefs[KEY] ?: "[]")
    }

    private suspend fun save(list: List<CustomModel>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("name", e.name)
                    .put("url", e.url)
                    .put("file", e.file),
            )
        }
        context.customModelsDataStore.edit { it[KEY] = arr.toString() }
        _entries.value = list
    }

    private fun parse(json: String): List<CustomModel> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CustomModel(
                id = o.getString("id"),
                name = o.getString("name"),
                url = o.optString("url", ""),
                file = o.getString("file"),
            )
        }
    } catch (t: Throwable) {
        emptyList()
    }
}
