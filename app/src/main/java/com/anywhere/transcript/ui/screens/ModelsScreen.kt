package com.anywhere.transcript.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anywhere.transcript.R
import com.anywhere.transcript.data.DeviceTier
import com.anywhere.transcript.data.ModelCatalog
import com.anywhere.transcript.data.ModelInfo
import com.anywhere.transcript.data.ModelStatus
import com.anywhere.transcript.ui.AppViewModel
import com.anywhere.transcript.ui.components.Format

private fun backendLabel(name: String, kind: String): String = when {
    name.startsWith("Hexagon") -> "Hexagon NPU"
    kind == "accel" -> "$name NPU"
    kind == "gpu" -> "$name GPU"
    else -> name
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val tier by vm.effectiveTier.collectAsStateWithLifecycle()
    val states by vm.modelStates.collectAsStateWithLifecycle()
    val customModels by vm.customModels.collectAsStateWithLifecycle()
    val customError by vm.customModelError.collectAsStateWithLifecycle()
    val backends = remember { vm.backends }
    val usage = remember { vm.diskUsageBytes() }

    var urlInput by rememberSaveable { mutableStateOf("") }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importModel(uri)
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Models") }) },
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- device info --------------------------------------------------------
            item(key = "device") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("This device", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = {}, label = { Text(tier.label) })
                            backends.forEach { b ->
                                AssistChip(onClick = {}, label = { Text(backendLabel(b.name, b.kind)) })
                            }
                        }
                        Text(
                            "Models use ${Format.bytes(usage)} of storage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            remember { vm.engineInfo },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                        )
                    }
                }
            }

            // ---- recommended for this device ----------------------------------------
            val recommended = ModelCatalog.recommendedFor(tier)
            item(key = "recommended") {
                SectionHeader("For your device", tag = null)
                RecommendedCard(
                    model = recommended,
                    state = states[recommended.id],
                    selected = (settings.modelId ?: ModelCatalog.recommended[tier]) == recommended.id,
                    onDownload = { vm.download(recommended) },
                    onCancelDownload = { vm.cancelDownload(recommended.id) },
                    onDelete = { vm.deleteModel(recommended.id) },
                    onSelect = { vm.selectModel(recommended.id) },
                )
            }

            // ---- all models, grouped by tier ----------------------------------------
            item(key = "hdr-all") {
                SectionHeader("All models", tag = null)
            }
            val npuMode = settings.backendPref == "npu"
            DeviceTier.entries.forEach { group ->
                val models = ModelCatalog.forTier(group).let { list ->
                    if (npuMode) list.sortedWith(compareBy({ !it.id.endsWith("-q8_0") }, { it.sizeBytes }))
                    else list.sortedBy { it.sizeBytes }
                }
                item(key = "sub-$group") {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            group.label.substringBefore("·").trim(),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (group == tier) {
                            AssistChip(onClick = {}, label = { Text("your tier") })
                        }
                    }
                }
                items(models, key = { it.id }) { model ->
                    ModelRow(
                        model = model,
                        state = states[model.id],
                        selected = (settings.modelId ?: ModelCatalog.recommended[tier]) == model.id,
                        npuSelected = npuMode,
                        onDownload = { vm.download(model) },
                        onCancelDownload = { vm.cancelDownload(model.id) },
                        onDelete = { vm.deleteModel(model.id) },
                        onSelect = { vm.selectModel(model.id) },
                    )
                }
            }

            // ---- custom models --------------------------------------------------------
            item(key = "hdr-custom") {
                SectionHeader("Custom models", tag = null)
                Text(
                    "Add a ggml Whisper model from a URL, or import a .bin file from this device. NPU runs q8_0/f32 models only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(customModels, key = { it.id }) { model ->
                ModelRow(
                    model = model,
                    state = states[model.id],
                    selected = settings.modelId == model.id,
                    onDownload = { vm.download(model) },
                    onCancelDownload = { vm.cancelDownload(model.id) },
                    onDelete = { vm.removeCustomModel(model.id) },
                    onSelect = { vm.selectModel(model.id) },
                )
            }
            if (customModels.isEmpty()) {
                item(key = "custom-empty") {
                    Text(
                        "No custom models yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "custom-add") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("https://huggingface.co/…/ggml-model.bin") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                vm.addCustomModel(urlInput)
                                urlInput = ""
                            },
                            enabled = urlInput.isNotBlank(),
                        ) {
                            Text("Add by URL")
                        }
                        Button(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                            Text("Import .bin file")
                        }
                    }
                    if (customError != null) {
                        Text(
                            customError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { vm.clearCustomModelError() }) { Text("Dismiss") }
                    }
                }
            }

            item(key = "auto") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.modelId == null, onClick = { vm.selectModel(null) })
                    Spacer(Modifier.width(8.dp))
                    Text("Automatic — use the recommended model for this device")
                }
            }

            item(key = "footer") { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(title: String, tag: String?) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        HorizontalDivider(
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun RecommendedCard(
    model: ModelInfo,
    state: com.anywhere.transcript.data.ModelDownloadState?,
    selected: Boolean,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    val status = state?.status ?: ModelStatus.NOT_DOWNLOADED
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
        onClick = { if (status == ModelStatus.DOWNLOADED) onSelect() },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(model.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${Format.bytes(model.sizeBytes)} · ${model.params} params · " +
                            if (model.multilingual) "Multilingual" else "English",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DownloadAction(status, state, onDownload, onCancelDownload)
            }
            if (status == ModelStatus.DOWNLOADING && state != null && state.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${Format.bytes(state.downloadedBytes)} / ${Format.bytes(state.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status == ModelStatus.DOWNLOADED) {
                    AssistChip(onClick = onSelect, label = { Text(if (selected) "Selected" else "Use this") })
                } else {
                    AssistChip(onClick = onDownload, label = { Text("Download") })
                }
                if (status == ModelStatus.DOWNLOADED) {
                    IconButton(onClick = onDelete) {
                        Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete model")
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: ModelInfo,
    state: com.anywhere.transcript.data.ModelDownloadState?,
    selected: Boolean,
    npuSelected: Boolean = false,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    val status = state?.status ?: ModelStatus.NOT_DOWNLOADED
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
        onClick = { if (status == ModelStatus.DOWNLOADED) onSelect() },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(model.label, style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (model.sizeBytes > 0) Format.bytes(model.sizeBytes) else "—",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(model.params + " params", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (model.multilingual) "Multilingual" else "English",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                DownloadAction(status, state, onDownload, onCancelDownload, onDelete)
            }
            if (status == ModelStatus.DOWNLOADING && state != null && state.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${Format.bytes(state.downloadedBytes)} / ${Format.bytes(state.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status == ModelStatus.FAILED && state?.error != null) {
                Text(
                    "Download failed: ${state.error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (model.note != null && status != ModelStatus.DOWNLOADING) {
                AssistChip(onClick = {}, label = { Text(model.note) })
            }
            if (npuSelected && !model.id.endsWith("-q8_0")) {
                Text(
                    "Not NPU-compatible — the NPU only runs q8_0/f32 models; this one would fall back to CPU.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun DownloadAction(
    status: ModelStatus,
    state: com.anywhere.transcript.data.ModelDownloadState?,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit = {},
) {
    when (status) {
        ModelStatus.DOWNLOADED -> Icon(
            painter = painterResource(R.drawable.ic_check),
            contentDescription = "Downloaded",
            tint = MaterialTheme.colorScheme.primary,
        )
        ModelStatus.DOWNLOADING -> IconButton(onClick = onCancelDownload) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = "Cancel download")
        }
        ModelStatus.FAILED -> IconButton(onClick = onDownload) {
            Icon(painterResource(R.drawable.ic_download), contentDescription = "Retry download")
        }
        ModelStatus.NOT_DOWNLOADED -> FilledTonalIconButton(onClick = onDownload) {
            Icon(painterResource(R.drawable.ic_download), contentDescription = "Download")
        }
    }
}
