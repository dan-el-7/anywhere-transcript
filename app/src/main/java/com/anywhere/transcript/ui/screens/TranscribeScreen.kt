package com.anywhere.transcript.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anywhere.transcript.transcription.JobPhase
import com.anywhere.transcript.transcription.TranscriptionUiState
import com.anywhere.transcript.ui.AppViewModel
import com.anywhere.transcript.ui.components.Format
import com.anywhere.transcript.ui.components.displayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscribeScreen(
    vm: AppViewModel,
    onGoToModels: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = displayName(ctx, uri) ?: "audio"
            vm.requestTranscribe(uri, name)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Transcribe") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(com.anywhere.transcript.R.drawable.ic_settings), contentDescription = "Settings")
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (ui.phase) {
                JobPhase.IDLE -> IdleContent(vm, onGoToModels) { picker.launch(arrayOf("audio/*", "video/*", "application/ogg", "application/x-ogg")) }
                JobPhase.PREPARING, JobPhase.DECODING, JobPhase.TRANSCRIBING -> ProgressContent(vm, ui)
                JobPhase.DONE -> ResultContent(vm)
                JobPhase.ERROR -> ErrorContent(ui, onGoToModels) { vm.dismissResult() }
                JobPhase.CANCELLED -> CancelledContent(vm)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun IdleContent(vm: AppViewModel, onGoToModels: () -> Unit, onPick: () -> Unit) {
    val ctx = LocalContext.current
    val tier by vm.effectiveTier.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val selected = remember(settings.modelId, tier) { vm.selectedModel() }
    val backends = remember { vm.backends }

    val gpu = backends.firstOrNull { it.isGpu && !it.name.startsWith("HTP") && !it.name.contains("Hexagon", true) }
    // QNN availability = known Hexagon arch (SoC check), NOT whisper.cpp backend
    // detection: listBackends HTP reporting died with the deleted skels, and was
    // OEM-gated anyway. The QNN engine brings its own runtime.
    val hexArch = remember { com.anywhere.transcript.data.Hexagon.deviceArch() }
    val qnnReady = com.anywhere.transcript.engine.QnnWhisperEngine.modelsReady(
        ctx,
        com.anywhere.transcript.data.Hexagon.deviceArch() ?: "v79",
    )

    val backendLabel = when {
        selected == null -> "No model yet — download one in the Models tab"
        // QNN packages only run on the QNN engine, whatever the preference says
        selected.id.startsWith("qnn-turbo") -> when {
            hexArch == null -> "QNN unavailable on this device → pick a ggml model"
            qnnReady -> "QNN · Turbo fp16 (Hexagon NPU)"
            else -> "QNN package not downloaded yet"
        }
        settings.backendPref == "qnn" -> when {
            hexArch == null -> "QNN unavailable on this device → CPU"
            qnnReady -> "QNN · Turbo fp16 (Hexagon NPU)"
            else -> "QNN needs the NPU Turbo model (Models tab)"
        }
        settings.backendPref == "gpu" ->
            if (gpu != null) "GPU (${gpu.name})" else "No GPU driver → CPU"
        settings.backendPref == "cpu" -> "CPU (forced)"
        else -> when {
            // Direct HTP dispatch is not advertised: per Qualcomm the HTP runs
            // quantized/precompiled graphs only and app DSP sessions are
            // OEM-gated, so Auto promises CPU or GPU; the NPU is used via QNN.
            gpu != null -> "Auto · ${gpu.name} GPU"
            else -> "Auto · CPU"
        }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ready to transcribe", style = MaterialTheme.typography.titleLarge)
            SetupRow("Device tier", tier.label)
            SetupRow("Model", selected?.let { "${it.label} · ${Format.bytes(it.sizeBytes)}" } ?: "None — pick one in Models")
            SetupRow("Engine", backendLabel)
            if (selected != null && !backendLabel.contains("NPU") && !backendLabel.contains("GPU") && selected.sizeBytes > 400L * 1024 * 1024) {
                Text(
                    "Running a large model on CPU is slow. For everyday clips, small-q8_0 (Models tab) is several times faster with slightly lower accuracy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            TextButton(onClick = onGoToModels) { Text("Change model") }
        }
    }

    Button(
        onClick = onPick,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Text("Choose an audio file", style = MaterialTheme.typography.titleMedium)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tip", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "Share audio from any app — voice memos, messengers, recordings — and pick “Anywhere Transcript”. " +
                    "Everything runs offline on this device.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    Text(
        remember { vm.engineInfo },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SetupRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ProgressContent(vm: AppViewModel, ui: com.anywhere.transcript.transcription.TranscriptionUiState) {
    val phaseLabel = when (ui.phase) {
        JobPhase.PREPARING -> "Loading model…"
        JobPhase.DECODING -> "Decoding audio…"
        else -> "Transcribing…"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(ui.fileName, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (ui.progress >= 0f) {
                    LinearProgressIndicator(
                        progress = { ui.progress },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    Text("${(ui.progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Text(phaseLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (ui.partialText.isNotBlank()) {
        SelectionContainer {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    ui.partialText,
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }

    OutlinedButton(
        onClick = { vm.cancel() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Cancel")
    }
}

@Composable
private fun ResultContent(vm: AppViewModel) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val result = ui.result ?: return
    var showTimestamps by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    val displayText = remember(result, showTimestamps) {
        if (!showTimestamps || result.segments.isEmpty()) {
            result.text
        } else {
            result.segments.joinToString("\n") { seg ->
                "[${Format.duration(seg.startMs)}] ${seg.text}"
            }
        }
    }

    SelectionContainer {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                displayText,
                modifier = Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Timestamps", style = MaterialTheme.typography.bodyMedium)
        Switch(checked = showTimestamps, onCheckedChange = { showTimestamps = it })
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = {
                vm.copyToClipboard(displayText)
                copied = true
            },
            modifier = Modifier.weight(1f),
        ) {
            Text(if (copied) "Copied ✓" else "Copy")
        }
        OutlinedButton(
            onClick = { vm.shareText(displayText) },
            modifier = Modifier.weight(1f),
        ) {
            Text("Share")
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ResultMeta("File", result.fileName)
            ResultMeta("Model", "${result.modelLabel} · ${result.backend}")
            ResultMeta("Audio", Format.duration(result.audioDurationMs))
            ResultMeta("Processing", Format.duration(result.processingMs))
        }
    }

    TextButton(onClick = { vm.dismissResult() }, modifier = Modifier.fillMaxWidth()) {
        Text("Transcribe another file")
    }
}

@Composable
private fun ResultMeta(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 24.dp),
        )
    }
}

@Composable
private fun ErrorContent(
    ui: com.anywhere.transcript.transcription.TranscriptionUiState,
    onGoToModels: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Transcription failed", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(ui.error ?: "Unknown error", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            if (ui.fileName.isNotEmpty()) {
                Text(ui.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
    if (ui.modelMissing) {
        Button(onClick = onGoToModels, modifier = Modifier.fillMaxWidth()) {
            Text("Open Models")
        }
    }
    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
        Text("Dismiss")
    }
}

@Composable
private fun CancelledContent(vm: AppViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cancelled", style = MaterialTheme.typography.titleMedium)
            Text("The transcription was cancelled.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Button(onClick = { vm.dismissResult() }, modifier = Modifier.fillMaxWidth()) {
        Text("OK")
    }
}
