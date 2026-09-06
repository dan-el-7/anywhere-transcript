package com.anywhere.transcript

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anywhere.transcript.transcription.TranscriptionBus
import com.anywhere.transcript.ui.AppViewModel
import com.anywhere.transcript.ui.components.displayName
import com.anywhere.transcript.ui.screens.HistoryScreen
import com.anywhere.transcript.ui.screens.ModelsScreen
import com.anywhere.transcript.ui.screens.SettingsScreen
import com.anywhere.transcript.ui.screens.TranscribeScreen
import com.anywhere.transcript.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    data class PendingShare(val uri: Uri, val name: String, val backendOverride: String? = null)

    private val pendingShare = MutableStateFlow<PendingShare?>(null)
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            AppTheme(settings) {
                AppRoot(viewModel, pendingShare)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        android.util.Log.i("ShareRoute", "handleIntent: action=${intent?.action}")
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    .orEmpty()
                    .filterNotNull()
            else -> emptyList()
        }
        android.util.Log.i("ShareRoute", "handleIntent: ${uris.size} stream uri(s)")
        val source = uris.firstOrNull() ?: return
        val backendOverride = intent?.getStringExtra("debug_backend")

        // Copy immediately: share permissions on the source Uri can be transient.
        lifecycleScope.launch(Dispatchers.IO) {
            val name = displayName(this@MainActivity, source) ?: "audio_${System.currentTimeMillis()}"
            val safeName = name.replace(Regex("[^A-Za-z0-9._ ()-]"), "_")
            val dir = File(cacheDir, "inbox").apply { mkdirs() }
            val dst = File(dir, "${System.currentTimeMillis()}_$safeName")
            val copied = runCatching {
                contentResolver.openInputStream(source)?.use { input ->
                    dst.outputStream().use { input.copyTo(it) }
                }
                dst.length() > 0
            }.getOrDefault(false)

            if (copied) {
                pendingShare.value = PendingShare(Uri.fromFile(dst), name, backendOverride)
            } else {
                dst.delete()
                TranscriptionBus.update {
                    it.copy(
                        phase = com.anywhere.transcript.transcription.JobPhase.ERROR,
                        fileName = name,
                        error = "Could not read the shared audio file.",
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRoot(vm: AppViewModel, pendingShare: MutableStateFlow<MainActivity.PendingShare?>) {
    val onboarding by vm.needsOnboarding.collectAsStateWithLifecycle()
    if (onboarding) {
        com.anywhere.transcript.ui.screens.OnboardingScreen(vm)
        return
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val pending by pendingShare.collectAsStateWithLifecycle()

    // System back gesture: leave Settings first, then collapse to the home tab.
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = !showSettings && tab != 0) { tab = 0 }

    LaunchedEffect(pending) {
        pending?.let { p ->
            tab = 0
            vm.requestTranscribe(p.uri, p.name, p.backendOverride)
            pendingShare.value = null
        }
    }

    Scaffold(
        bottomBar = {
            if (!showSettings) {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(painterResource(R.drawable.ic_wave), contentDescription = null) },
                        label = { Text("Transcribe") },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(painterResource(R.drawable.ic_mic), contentDescription = null) },
                        label = { Text("Record") },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(painterResource(R.drawable.ic_layers), contentDescription = null) },
                        label = { Text("Models") },
                    )
                    NavigationBarItem(
                        selected = tab == 3,
                        onClick = { tab = 3 },
                        icon = { Icon(painterResource(R.drawable.ic_history), contentDescription = null) },
                        label = { Text("History") },
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(bottom = pad.calculateBottomPadding())) {
            // one-shot navigation from Record error card → Models tab
            val goToModels by vm.goToModels.collectAsStateWithLifecycle()
            LaunchedEffect(goToModels) {
                if (goToModels) {
                    tab = 2
                    vm.goToModels.value = false
                }
            }
            when {
                showSettings -> SettingsScreen(vm, onBack = { showSettings = false })
                tab == 0 -> TranscribeScreen(
                    vm = vm,
                    onGoToModels = { tab = 2 },
                    onOpenSettings = { showSettings = true },
                )
                tab == 1 -> com.anywhere.transcript.ui.screens.RecordScreen(vm)
                tab == 2 -> ModelsScreen(vm)
                else -> HistoryScreen(vm)
            }
        }
    }
}
