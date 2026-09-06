package com.anywhere.transcript.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anywhere.transcript.R
import com.anywhere.transcript.data.DeviceTier
import com.anywhere.transcript.data.ModelCatalog
import com.anywhere.transcript.ui.AppViewModel

private val WHISPER_LANGUAGES = listOf(
    "auto" to "Auto-detect",
    "en" to "English", "de" to "German", "fr" to "French", "es" to "Spanish", "it" to "Italian",
    "pt" to "Portuguese", "nl" to "Dutch", "ru" to "Russian", "uk" to "Ukrainian", "pl" to "Polish",
    "tr" to "Turkish", "ar" to "Arabic", "zh" to "Chinese", "ja" to "Japanese", "ko" to "Korean",
    "hi" to "Hindi", "id" to "Indonesian", "vi" to "Vietnamese", "th" to "Thai", "fa" to "Persian",
    "he" to "Hebrew", "el" to "Greek", "sv" to "Swedish", "da" to "Danish", "nb" to "Norwegian",
    "fi" to "Finnish", "cs" to "Czech", "hu" to "Hungarian", "ro" to "Romanian", "bg" to "Bulgarian",
    "hr" to "Croatian", "sk" to "Slovak", "ca" to "Catalan",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var showBackendWarning by remember { mutableStateOf<String?>(null) }
    var npuModelWarning by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard("Device tier") {
                RadioRow("Auto (detected from RAM)", settings.tierOverride == "auto") { vm.setTierOverride("auto") }
                RadioRow("Low-end · 4 GB", settings.tierOverride == "LOW") { vm.setTierOverride("LOW") }
                RadioRow("Mid-range · 8 GB", settings.tierOverride == "MID") { vm.setTierOverride("MID") }
                RadioRow("Flagship · 16 GB", settings.tierOverride == "FLAGSHIP") { vm.setTierOverride("FLAGSHIP") }
                HelperText("Controls which models are recommended and how much audio is processed per window.")
            }

            SectionCard("Compute backend") {
                val backends = remember { vm.backends }
                val npuPresent = backends.any { it.name.startsWith("HTP") || it.name.contains("Hexagon", true) }
                val selectedModel = remember(settings.modelId) { vm.selectedModel() }

                RadioRow("Auto — NPU/GPU when available, else CPU", settings.backendPref == "auto") { vm.setBackendPref("auto") }
                RadioRow("QNN — Whisper Turbo fp16 on NPU (needs model files)", settings.backendPref == "qnn") {
                    if (npuPresent) vm.setBackendPref("qnn") else showBackendWarning = "qnn"
                }
                RadioRow("NPU — direct Hexagon (rarely works from apps, prefer QNN)", settings.backendPref == "npu") {
                    when {
                        !npuPresent -> showBackendWarning = "npu"
                        selectedModel == null ->
                            npuModelWarning = "no model selected"
                        !selectedModel.id.endsWith("-q8_0") && !selectedModel.id.startsWith("qnn-turbo") ->
                            npuModelWarning = selectedModel.label
                        else -> vm.setBackendPref("npu")
                    }
                }
                RadioRow("GPU — OpenCL (Adreno) / Vulkan", settings.backendPref == "gpu") { vm.setBackendPref("gpu") }
                RadioRow("CPU", settings.backendPref == "cpu") { vm.setBackendPref("cpu") }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Hide incompatible models", style = MaterialTheme.typography.bodyLarge)
                        HelperText("Hides NPU packages built for other chips (on by default).")
                    }
                    Switch(
                        checked = settings.hideIncompatibleModels,
                        onCheckedChange = { vm.setHideIncompatibleModels(it) },
                    )
                }
                HelperText(
                    "QNN uses Qualcomm AI Hub context binaries (~2.2GB, files/qnn). NPU requires a " +
                        "Hexagon-capable Snapdragon and a q8_0 model; both fall back automatically if unavailable.",
                )
            }

            SectionCard("Language") {
                var expanded by remember { mutableStateOf(false) }
                val current = WHISPER_LANGUAGES.firstOrNull { it.first == settings.language }
                    ?: WHISPER_LANGUAGES.first()
                Box {
                    TextButton(onClick = { expanded = true }) { Text(current.second) }
                    androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        WHISPER_LANGUAGES.forEach { (code, name) ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    vm.setLanguage(code)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Translate to English", style = MaterialTheme.typography.bodyLarge)
                        HelperText("Requires a multilingual model.")
                    }
                    Switch(checked = settings.translateToEnglish, onCheckedChange = { vm.setTranslate(it) })
                }
            }

            SectionCard("Appearance") {
                RadioRow("Follow system", settings.themeMode == "system") { vm.setThemeMode("system") }
                RadioRow("Light", settings.themeMode == "light") { vm.setThemeMode("light") }
                RadioRow("Dark", settings.themeMode == "dark") { vm.setThemeMode("dark") }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Dynamic color", style = MaterialTheme.typography.bodyLarge)
                            HelperText("Use colors from your wallpaper (Material You).")
                        }
                        Switch(checked = settings.dynamicColor, onCheckedChange = { vm.setDynamicColor(it) })
                    }
                }
            }
        }
    }

    // backend guards: don't let the user pick an engine the device can't run,
    // and warn when the current model can't run on the chosen engine
    if (showBackendWarning != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showBackendWarning = null },
            title = { Text("No Qualcomm NPU") },
            text = {
                Text(
                    "This device doesn't expose a Hexagon NPU, so the " +
                        (if (showBackendWarning == "qnn") "QNN" else "NPU") +
                        " backend can't run here. Transcription would fall back to CPU anyway — " +
                        "keep Auto or pick CPU/GPU.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showBackendWarning = null }) { Text("OK") }
            },
        )
    }
    if (npuModelWarning != null) {
        val recommended = ModelCatalog.recommendedFor(vm.effectiveTier.value)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { npuModelWarning = null },
            title = { Text("Model can't use the NPU") },
            text = {
                Text(
                    "“$npuModelWarning” isn't a q8_0 model, and the NPU only runs q8_0 — it would " +
                        "silently fall back to CPU. Switch the model instead?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.selectModel(recommended.id)
                    vm.setBackendPref("npu")
                    npuModelWarning = null
                }) { Text("Use ${recommended.label}") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.setBackendPref("npu")
                    npuModelWarning = null
                }) { Text("Keep model (CPU fallback)") }
            },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun HelperText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
