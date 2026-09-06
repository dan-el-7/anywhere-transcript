package com.anywhere.transcript.ui.screens

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anywhere.transcript.R
import com.anywhere.transcript.transcription.JobPhase
import com.anywhere.transcript.ui.AppViewModel
import com.anywhere.transcript.ui.components.Format

/**
 * Record tab: live on-device transcription while speaking. Text streams in
 * every few seconds (QNN keeps up ~5× realtime); when the take stops, the
 * full recording is re-transcribed with full context and lands in History
 * like any shared file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val rec by vm.recordState.collectAsStateWithLifecycle()
    val ui by vm.uiState.collectAsStateWithLifecycle()

    fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.startRecording()
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Record") }) },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // status / error card
            when {
                rec.error != null -> ErrorCard(rec.error!!, rec.modelMissing, onGoToModels = { vm.goToModels.value = true })
                rec.recording -> LiveCard(rec)
                rec.preloading -> PreloadCard()
                else -> IdleCard(vm)
            }

            // main control
            if (rec.recording) {
                Button(
                    onClick = { vm.stopRecording() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Stop & transcribe", style = MaterialTheme.typography.titleMedium)
                }
            } else if (rec.preloading) {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Text("Preparing model…", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Button(
                    onClick = {
                        if (hasMic()) vm.startRecording() else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Text("Start recording", style = MaterialTheme.typography.titleMedium)
                }
            }

            // live text (during recording) / final result note (after)
            if (rec.recording && rec.liveText.isNotBlank()) {
                SelectionContainer {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Live transcript", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Text(rec.liveText, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }

            // after stop: the final pass runs in-process; show its state here
            if (!rec.recording && rec.elapsedMs > 0 && rec.error == null && ui.phase != JobPhase.IDLE) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Finishing the take — transcribing the full recording with complete context…",
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (!rec.recording && rec.elapsedMs > 0 && rec.error == null && ui.phase == JobPhase.DONE && ui.result != null) {
                SelectionContainer {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Full transcript", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Text(ui.result!!.text, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${ui.result!!.modelLabel} · ${ui.result!!.backend} · saved to History",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                OutlinedButton(onClick = { vm.dismissResult() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Done")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun IdleCard(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val hexArch = androidx.compose.runtime.remember { com.anywhere.transcript.data.Hexagon.deviceArch() }
    val qnnReady = hexArch != null && com.anywhere.transcript.engine.QnnWhisperEngine.modelsReady(
        LocalContext.current, hexArch,
    )
    val engineLabel = when {
        settings.backendPref == "qnn" || settings.modelId?.startsWith("qnn-turbo") == true ->
            if (qnnReady) "QNN · Turbo fp16 (Hexagon NPU)" else "QNN package not downloaded yet"
        else -> null // whisper.cpp path: same routing as the Transcribe tab
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Live transcription", style = MaterialTheme.typography.titleLarge)
            Text(
                "Speak and watch text appear on-device while you talk. " +
                    "When you stop, the whole take is transcribed again with full context " +
                    "and saved to History.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (engineLabel != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Engine",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(engineLabel, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun PreloadCard() {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Preparing model…", style = MaterialTheme.typography.titleLarge)
            Text(
                "Loading the engine into memory before capture starts, so live text " +
                    "keeps up with your speech from the first word.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LiveCard(rec: com.anywhere.transcript.transcription.RecordUiState) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    painterResource(R.drawable.ic_wave),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Text("Recording…", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
            }
            Text(
                Format.duration(rec.elapsedMs),
                style = MaterialTheme.typography.displaySmall,
            )
            if (rec.liveText.isBlank()) {
                Text(
                    "Listening — live text appears every few seconds…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, modelMissing: Boolean, onGoToModels: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Recording failed", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            if (modelMissing) {
                Button(onClick = onGoToModels) { Text("Open Models") }
            }
        }
    }
}
