package com.anywhere.transcript.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    /** "auto" or a DeviceTier name. */
    val tierOverride: String = "auto",
    /** "auto" | "gpu" | "cpu". */
    val backendPref: String = "auto",
    /** Manually selected model, null = tier recommendation. */
    val modelId: String? = null,
    /** "auto" or an ISO language code known to Whisper. */
    val language: String = "auto",
    val translateToEnglish: Boolean = false,
    val dynamicColor: Boolean = true,
    /** "system" | "light" | "dark". */
    val themeMode: String = "system",
    /** First-run model picker already completed (or skipped). */
    val onboardingDone: Boolean = false,
    /** Hide QNN packages built for other Hexagon archs (can't run here). */
    val hideIncompatibleModels: Boolean = true,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val TIER = stringPreferencesKey("tier_override")
        val BACKEND = stringPreferencesKey("backend_pref")
        val MODEL = stringPreferencesKey("model_id")
        val LANGUAGE = stringPreferencesKey("language")
        val TRANSLATE = booleanPreferencesKey("translate")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val THEME = stringPreferencesKey("theme_mode")
        val ONBOARDING = booleanPreferencesKey("onboarding_done")
        val HIDE_INCOMPATIBLE = booleanPreferencesKey("hide_incompatible_models")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            tierOverride = p[Keys.TIER] ?: "auto",
            backendPref = p[Keys.BACKEND] ?: "auto",
            modelId = p[Keys.MODEL],
            language = p[Keys.LANGUAGE] ?: "auto",
            translateToEnglish = p[Keys.TRANSLATE] ?: false,
            dynamicColor = p[Keys.DYNAMIC] ?: true,
            themeMode = p[Keys.THEME] ?: "system",
            onboardingDone = p[Keys.ONBOARDING] ?: false,
            hideIncompatibleModels = p[Keys.HIDE_INCOMPATIBLE] ?: true,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setTierOverride(v: String) = context.dataStore.edit { it[Keys.TIER] = v }
    suspend fun setBackendPref(v: String) {
        context.dataStore.edit { it[Keys.BACKEND] = v }
        // synchronous side-channel: the OpenCL opt-in flag must be readable
        // before DataStore's first read (JNI needs it at process start)
        context.getSharedPreferences("engine_flags", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("opencl_enabled", v == "gpu")
            .apply()
    }
    suspend fun setModelId(v: String?) = context.dataStore.edit {
        if (v == null) it.remove(Keys.MODEL) else it[Keys.MODEL] = v
    }
    suspend fun setLanguage(v: String) = context.dataStore.edit { it[Keys.LANGUAGE] = v }
    suspend fun setTranslate(v: Boolean) = context.dataStore.edit { it[Keys.TRANSLATE] = v }
    suspend fun setDynamicColor(v: Boolean) = context.dataStore.edit { it[Keys.DYNAMIC] = v }
    suspend fun setThemeMode(v: String) = context.dataStore.edit { it[Keys.THEME] = v }
    suspend fun setOnboardingDone(v: Boolean) = context.dataStore.edit { it[Keys.ONBOARDING] = v }
    suspend fun setHideIncompatibleModels(v: Boolean) = context.dataStore.edit { it[Keys.HIDE_INCOMPATIBLE] = v }
}
