package com.anywhere.transcript.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anywhere.transcript.data.DeviceTier
import com.anywhere.transcript.data.Hexagon
import com.anywhere.transcript.data.ModelCatalog
import com.anywhere.transcript.data.ModelInfo
import com.anywhere.transcript.data.ModelStatus
import com.anywhere.transcript.ui.AppViewModel
import com.anywhere.transcript.ui.components.Format

/**
 * First-run flow: shows what the device can do, lets the user pick (and
 * download) their model, then enters the app. Skippable — a tier default is
 * used when no explicit choice is made.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(vm: AppViewModel) {
    val tier by vm.effectiveTier.collectAsStateWithLifecycle()
    val states by vm.modelStates.collectAsStateWithLifecycle()
    val backends = remember { vm.backends }
    val soc = remember { Hexagon.socModel() }
    val arch = remember(soc) { Hexagon.archForSoc(soc) }
    val npuPresent = backends.any { it.name.startsWith("HTP") || it.name.contains("Hexagon", true) }

    val options = remember(tier) { ModelCatalog.onboardingOptions(tier, arch) }
    var selectedId by remember(tier) { mutableStateOf(options.firstOrNull()?.id) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Welcome") }) },
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "hero") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Anywhere Transcript",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        "Transcribe audio completely offline. Whisper runs on this " +
                            "device — nothing ever leaves it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item(key = "device") {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Your device", style = MaterialTheme.typography.titleSmall)
                        Text(
                            buildString {
                                append(tier.label)
                                if (soc != null) {
                                    append(" · ")
                                    append(soc)
                                    if (arch != null) append(" (Hexagon $arch NPU)")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (npuPresent)
                                "Qualcomm NPU detected — the Turbo model below runs on it."
                            else
                                "No Qualcomm NPU detected — CPU (and GPU) backends will be used.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item(key = "pick-hdr") {
                Text("Pick a model", style = MaterialTheme.typography.titleMedium)
            }

            items(options.size, key = { "opt-$it" }) { i ->
                val model = options[i]
                val recommended = i == 0
                val status = states[model.id]?.status ?: ModelStatus.NOT_DOWNLOADED
                OptionCard(
                    model = model,
                    selected = selectedId == model.id,
                    recommended = recommended && arch != null,
                    tag = when {
                        model.id.startsWith("qnn-turbo") -> "Best quality · runs on NPU"
                        model.id == ModelCatalog.recommendedFor(tier).id -> "Balanced · fits your device"
                        else -> "Lightest download"
                    },
                    status = status,
                    state = states[model.id],
                    onSelect = { selectedId = model.id },
                    onDownload = { vm.download(model) },
                    onCancel = { vm.cancelDownload(model.id) },
                )
            }

            item(key = "actions") {
                val sel = selectedId?.let { ModelCatalog.byId[it] }
                val selStatus = sel?.let { states[it.id]?.status ?: ModelStatus.NOT_DOWNLOADED }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.finishOnboarding(sel?.id) },
                        enabled = sel == null || selStatus == ModelStatus.DOWNLOADED,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Text(
                            when {
                                sel == null -> "Start"
                                selStatus == ModelStatus.DOWNLOADED -> "Start transcribing"
                                else -> "Download to continue"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    TextButton(
                        onClick = { vm.finishOnboarding(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Skip — I'll pick a model later")
                    }
                }
            }

            item(key = "footer") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun OptionCard(
    model: ModelInfo,
    selected: Boolean,
    recommended: Boolean,
    tag: String,
    status: ModelStatus,
    state: com.anywhere.transcript.data.ModelDownloadState?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                RadioButton(selected = selected, onClick = onSelect)
            }
            Text(tag, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "${Format.bytes(model.sizeBytes)} · ${model.params} params · " +
                    if (model.multilingual) "Multilingual" else "English",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (status) {
                ModelStatus.DOWNLOADING -> {
                    val st = state
                    LinearProgressIndicator(
                        progress = {
                            if (st != null && st.totalBytes > 0)
                                (st.downloadedBytes.toFloat() / st.totalBytes).coerceIn(0f, 1f) else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (st != null && st.totalBytes > 0)
                                "${Format.bytes(st.downloadedBytes)} / ${Format.bytes(st.totalBytes)}"
                            else "Starting…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
                ModelStatus.DOWNLOADED -> Text(
                    "✓ Downloaded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                ModelStatus.FAILED -> Text(
                    "Download failed${state?.error?.let { ": $it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                ModelStatus.NOT_DOWNLOADED -> if (selected || recommended) {
                    TextButton(onClick = onDownload) { Text("Download now") }
                }
            }
        }
    }
}
