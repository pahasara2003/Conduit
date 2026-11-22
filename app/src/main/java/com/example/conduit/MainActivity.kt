package com.example.conduit

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.conduit.ui.IdeWorkspaceScreen
import com.example.conduit.ui.SettingsToolsDrawer
import com.example.conduit.ui.theme.ConduitTheme
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        ConduitService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConduitRepository.init(this)

        if (forwardIfQuickIntent(intent)) {
            return
        }

        ensureServiceAndPermissions()

        setContent {
            val mode by ConduitRepository.themeMode.collectAsState()
            val dark = when (mode) {
                "Light" -> false
                "System" -> androidx.compose.foundation.isSystemInDarkTheme()
                else -> true
            }
            // status/nav bar icons must follow the theme
            androidx.compose.runtime.SideEffect {
                val t = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
                )
            }
            ConduitTheme(darkTheme = dark) {
                ConduitIdeRoot(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardIfQuickIntent(intent)
    }

    private fun forwardIfQuickIntent(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        if (action == Intent.ACTION_PROCESS_TEXT ||
            action == Intent.ACTION_SEND ||
            action == ACTION_SEND_CLIPBOARD ||
            action == ACTION_PULL_CLIPBOARD
        ) {
            val forward = Intent(intent).apply {
                setClass(this@MainActivity, QuickActionActivity::class.java)
            }
            startActivity(forward)
            finish()
            return true
        }
        return false
    }

    private fun ensureServiceAndPermissions() {
        ConduitService.start(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        const val ACTION_SEND_CLIPBOARD = "com.example.conduit.ACTION_SEND_CLIPBOARD"
        const val ACTION_PULL_CLIPBOARD = "com.example.conduit.ACTION_PULL_CLIPBOARD"
    }
}

@Composable
fun ConduitIdeRoot(viewModel: MainViewModel) {
    val context = LocalContext.current
    val syncState by viewModel.uiState.collectAsState()
    val workspaceState by viewModel.workspaceState.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var promptInput by remember { mutableStateOf("") }
    var isVoiceActive by remember { mutableStateOf(false) }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isVoiceActive = false
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrEmpty()) {
                promptInput = if (promptInput.isBlank()) spoken else "$promptInput $spoken"
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SettingsToolsDrawer(
                state = syncState,
                sessions = workspaceState.sessions,
                activeSessionId = workspaceState.activeSession?.id,
                onClose = { scope.launch { drawerState.close() } },
                onNewSession = { viewModel.startNewSession(workspaceState.selectedModel) },
                onResumeSession = { viewModel.resumeSession(it) },
                onDeleteSession = { viewModel.deleteSession(it) },
                onSendClipboard = { viewModel.sendCurrentClipboard() },
                onPullClipboard = { viewModel.pullFromPc() },
                onToggleService = { viewModel.toggleService(it) },
                onUpdatePcHost = { viewModel.updatePcHost(it) },
                onDiscoverPc = { viewModel.discoverPc() },
                themeMode = ConduitRepository.themeMode.collectAsState().value,
                onThemeMode = { ConduitRepository.setThemeMode(it) },
                onCopyHistory = { viewModel.copyToPhone(it) },
                onResendHistory = { viewModel.sendText(it) }
            )
        }
    ) {
        IdeWorkspaceScreen(
            syncState = syncState,
            workspaceState = workspaceState,
            promptInput = promptInput,
            isVoiceActive = isVoiceActive,
            onPromptChange = { promptInput = it },
            onSendPrompt = {
                val toSend = promptInput.trim()
                if (toSend.isNotEmpty()) {
                    promptInput = ""
                    viewModel.sendAiPrompt(toSend)
                }
            },
            onVoiceToggle = {
                if (isVoiceActive) {
                    isVoiceActive = false
                } else {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your prompt")
                    }
                    try {
                        isVoiceActive = true
                        voiceLauncher.launch(intent)
                    } catch (_: Exception) {
                        isVoiceActive = false
                        Toast.makeText(
                            context,
                            "Voice recognition not available on this device",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onOpenDrawer = {
                viewModel.refreshSessions()
                scope.launch { drawerState.open() }
            },
            onRefreshConnection = { viewModel.refreshConnectionAndBranch() },
            onRefreshModels = { viewModel.refreshModels() },
            onSelectBaseModel = { viewModel.selectBaseModel(it) },
            onSelectEffort = { viewModel.selectEffort(it) },
            onCopyText = { viewModel.copyToPhone(it) },
            onDownloadFiles = { viewModel.downloadFiles(it) }
        )
    }
}
