package com.example.clipsync

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class IdeWorkspaceState(
    val items: List<WorkspaceItem> = emptyList(),
    val isWorking: Boolean = false,
    val workingStatus: String = "",
    val models: List<AiModelInfo> = DEFAULT_MODELS,
    val baseModels: List<BaseModelGroup> = DEFAULT_BASE_MODELS,
    val selectedBaseModel: BaseModelGroup = DEFAULT_BASE_MODELS[1], // Gemini 3.8 Flash
    val selectedEffort: ReasoningEffort = ReasoningEffort.LOW,
    val selectedModel: AiModelInfo = AiModelInfo("gemini-3.8-flash-low", "Gemini 3.8 Flash (Low)"),
    val sessions: List<AiSessionSummary> = emptyList(),
    val activeSession: AiSessionSummary? = null
) {
    companion object {
        val DEFAULT_BASE_MODELS = listOf(
            BaseModelGroup("gemini-4-neon", "Gemini 4 Neon", listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH)),
            BaseModelGroup("gemini-3.8-flash", "Gemini 3.8 Flash", listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)),
            BaseModelGroup("gemini-4-argon", "Gemini 4 Argon", listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)),
            BaseModelGroup("claude-sonnet-5-5", "Claude Sonnet 5.5", listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)),
            BaseModelGroup("claude-opus-5-5", "Claude Opus 5.5", listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)),
            BaseModelGroup("gpt-oss-120b", "GPT-OSS 120B", listOf(ReasoningEffort.MEDIUM))
        )

        val DEFAULT_MODELS = listOf(
            AiModelInfo("gemini-4-neon-low", "Gemini 4 Neon (Low)"),
            AiModelInfo("gemini-4-neon-medium", "Gemini 4 Neon (Medium)"),
            AiModelInfo("gemini-4-neon-high", "Gemini 4 Neon (High)"),
            AiModelInfo("gemini-4-neon-xhigh", "Gemini 4 Neon (XHigh)"),
            AiModelInfo("gemini-3.8-flash-low", "Gemini 3.8 Flash (Low)"),
            AiModelInfo("gemini-3.8-flash-medium", "Gemini 3.8 Flash (Medium)"),
            AiModelInfo("gemini-3.8-flash-high", "Gemini 3.8 Flash (High)"),
            AiModelInfo("gemini-4-argon-low", "Gemini 4 Argon (Low)"),
            AiModelInfo("gemini-4-argon-medium", "Gemini 4 Argon (Medium)"),
            AiModelInfo("gemini-4-argon-high", "Gemini 4 Argon (High)"),
            AiModelInfo("claude-sonnet-5-5-low", "Claude Sonnet 5.5 (Low)"),
            AiModelInfo("claude-sonnet-5-5-medium", "Claude Sonnet 5.5 (Medium)"),
            AiModelInfo("claude-sonnet-5-5-high", "Claude Sonnet 5.5 (High)"),
            AiModelInfo("claude-opus-5-5-low", "Claude Opus 5.5 (Low)"),
            AiModelInfo("claude-opus-5-5-medium", "Claude Opus 5.5 (Medium)"),
            AiModelInfo("claude-opus-5-5-high", "Claude Opus 5.5 (High)"),
            AiModelInfo("gpt-oss-120b-medium", "GPT-OSS 120B (Medium)")
        )

        private val EFFORT_SUFFIXES = listOf("-xhigh", "-high", "-medium", "-low", "-max")

        fun splitModelId(modelId: String): Pair<String, ReasoningEffort> {
            for (suffix in EFFORT_SUFFIXES) {
                if (modelId.endsWith(suffix, ignoreCase = true)) {
                    val base = modelId.dropLast(suffix.length)
                    val effort = ReasoningEffort.fromKey(suffix.removePrefix("-"))
                    return base to effort
                }
            }
            return modelId to ReasoningEffort.MEDIUM
        }

        fun composeModel(
            base: BaseModelGroup,
            effort: ReasoningEffort,
            rawModels: List<AiModelInfo>
        ): AiModelInfo {
            val exactId = "${base.baseId}-${effort.key}"
            rawModels.find { it.id == exactId }?.let { return it }

            // Gracefully map to closest supported effort for this base model if exact ID isn't listed
            val fallbackEffort = when {
                effort in base.availableEfforts -> effort
                effort == ReasoningEffort.XHIGH && ReasoningEffort.HIGH in base.availableEfforts -> ReasoningEffort.HIGH
                ReasoningEffort.MEDIUM in base.availableEfforts -> ReasoningEffort.MEDIUM
                else -> base.availableEfforts.firstOrNull() ?: effort
            }
            val fallbackId = "${base.baseId}-${fallbackEffort.key}"
            rawModels.find { it.id == fallbackId }?.let { return it }

            return AiModelInfo(
                id = fallbackId,
                label = "${base.displayName} (${fallbackEffort.label})"
            )
        }

        fun buildBaseModels(rawModels: List<AiModelInfo>): List<BaseModelGroup> {
            val grouped = linkedMapOf<String, Pair<String, MutableSet<ReasoningEffort>>>()
            // Seed with the core ordered base models first
            DEFAULT_BASE_MODELS.forEach { core ->
                grouped[core.baseId] = core.displayName to core.availableEfforts.toMutableSet()
            }
            rawModels.forEach { m ->
                val (baseId, effort) = splitModelId(m.id)
                val cleanLabel = m.label
                    .replace(Regex("""\s*\((?:Low|Medium|High|XHigh|Max)\)\s*$""", RegexOption.IGNORE_CASE), "")
                    .trim()
                val existing = grouped[baseId]
                if (existing != null) {
                    existing.second.add(effort)
                } else if (!baseId.contains("orchestrator", ignoreCase = true)) {
                    grouped[baseId] = cleanLabel.ifBlank { baseId } to mutableSetOf(effort)
                }
            }
            return grouped.map { (id, pair) ->
                BaseModelGroup(
                    baseId = id,
                    displayName = pair.first,
                    availableEfforts = pair.second.sortedBy { it.stepIndex }
                )
            }
        }
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val uiState: StateFlow<ClipSyncState> = ClipSyncRepository.state

    private val _workspaceState = MutableStateFlow(IdeWorkspaceState())
    val workspaceState: StateFlow<IdeWorkspaceState> = _workspaceState.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val projectDir = "~/AndroidProjects/ClipSync"

    init {
        ClipSyncRepository.init(application)
        val savedModelId = ClipSyncRepository.getSavedAiModel(application)
        val (baseId, effort) = IdeWorkspaceState.splitModelId(savedModelId)
        val baseGroup = IdeWorkspaceState.DEFAULT_BASE_MODELS.find { it.baseId == baseId }
            ?: BaseModelGroup(baseId, baseId)
        val composed = IdeWorkspaceState.composeModel(baseGroup, effort, IdeWorkspaceState.DEFAULT_MODELS)
        _workspaceState.update {
            it.copy(
                selectedBaseModel = baseGroup,
                selectedEffort = effort,
                selectedModel = composed
            )
        }
        refreshConnectionAndBranch()
    }

    private fun now(): String = timeFormat.format(Date())
    private fun nextId(): String = UUID.randomUUID().toString()

    fun refreshConnectionAndBranch() {
        viewModelScope.launch {
            val ping = ClipSyncRepository.pingPc()
            if (ping.isSuccess) {
                launch { refreshModels() }
                launch { refreshSessions() }
                val branchRes = ClipSyncRepository.runTerminalCommand(
                    cmd = "git -C $projectDir rev-parse --abbrev-ref HEAD",
                    timeoutSec = 8
                )
                branchRes.getOrNull()?.let { result ->
                    if (result.exitCode == 0 && result.stdout.isNotBlank()) {
                        ClipSyncRepository.setActiveBranch(result.stdout.trim())
                    }
                }
            }
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            val res = ClipSyncRepository.fetchAiModels()
            res.getOrNull()?.takeIf { it.isNotEmpty() }?.let { list ->
                _workspaceState.update { cur ->
                    val baseGroups = IdeWorkspaceState.buildBaseModels(list)
                    val matchedBase = baseGroups.find { it.baseId == cur.selectedBaseModel.baseId }
                        ?: baseGroups.first()
                    val composed = IdeWorkspaceState.composeModel(matchedBase, cur.selectedEffort, list)
                    cur.copy(
                        models = list,
                        baseModels = baseGroups,
                        selectedBaseModel = matchedBase,
                        selectedModel = composed
                    )
                }
            }
        }
    }

    fun refreshSessions() {
        viewModelScope.launch {
            val res = ClipSyncRepository.fetchAiSessions()
            res.getOrNull()?.let { list ->
                _workspaceState.update { cur ->
                    val updatedActive = cur.activeSession?.let { act ->
                        list.find { it.id == act.id } ?: act
                    }
                    cur.copy(sessions = list, activeSession = updatedActive)
                }
            }
        }
    }

    fun selectBaseModel(baseModel: BaseModelGroup) {
        val cur = _workspaceState.value
        val composed = IdeWorkspaceState.composeModel(baseModel, cur.selectedEffort, cur.models)
        ClipSyncRepository.setSavedAiModel(getApplication(), composed.id)
        _workspaceState.update {
            it.copy(
                selectedBaseModel = baseModel,
                selectedModel = composed
            )
        }
    }

    fun selectEffort(effort: ReasoningEffort) {
        val cur = _workspaceState.value
        val composed = IdeWorkspaceState.composeModel(cur.selectedBaseModel, effort, cur.models)
        ClipSyncRepository.setSavedAiModel(getApplication(), composed.id)
        _workspaceState.update {
            it.copy(
                selectedEffort = effort,
                selectedModel = composed
            )
        }
    }

    fun selectModel(model: AiModelInfo) {
        val (baseId, effort) = IdeWorkspaceState.splitModelId(model.id)
        val cur = _workspaceState.value
        val baseGroup = cur.baseModels.find { it.baseId == baseId }
            ?: BaseModelGroup(baseId, model.label.substringBefore(" ("))
        ClipSyncRepository.setSavedAiModel(getApplication(), model.id)
        _workspaceState.update {
            it.copy(
                selectedBaseModel = baseGroup,
                selectedEffort = effort,
                selectedModel = model
            )
        }
    }

    fun startNewSession(model: AiModelInfo = _workspaceState.value.selectedModel) {
        if (_workspaceState.value.isWorking) return
        selectModel(model)
        viewModelScope.launch {
            val res = ClipSyncRepository.createAiSession(model.id, model.label)
            res.fold(
                onSuccess = { detail ->
                    _workspaceState.update { cur ->
                        cur.copy(
                            activeSession = detail.summary,
                            items = emptyList(),
                            sessions = listOf(detail.summary) + cur.sessions.filterNot { it.id == detail.summary.id }
                        )
                    }
                    Toast.makeText(getApplication(), "Started session (${model.label})", Toast.LENGTH_SHORT).show()
                },
                onFailure = { err ->
                    _workspaceState.update { it.copy(activeSession = null, items = emptyList()) }
                    Toast.makeText(getApplication(), "Offline session ready (${err.message})", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    fun closeActiveSession() {
        val active = _workspaceState.value.activeSession
        if (active == null) {
            clearWorkspace()
            return
        }
        viewModelScope.launch {
            val res = ClipSyncRepository.closeAiSession(active.id)
            res.fold(
                onSuccess = { closedSummary ->
                    _workspaceState.update { cur ->
                        cur.copy(
                            activeSession = null,
                            items = emptyList(),
                            sessions = cur.sessions.map { if (it.id == closedSummary.id) closedSummary else it }
                        )
                    }
                    Toast.makeText(getApplication(), "Session closed (saved to resume later)", Toast.LENGTH_SHORT).show()
                },
                onFailure = { err ->
                    Toast.makeText(getApplication(), "Close failed: ${err.message}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    fun closeSessionById(sessionId: String) {
        viewModelScope.launch {
            val res = ClipSyncRepository.closeAiSession(sessionId)
            res.getOrNull()?.let { closedSummary ->
                _workspaceState.update { cur ->
                    val clearActive = cur.activeSession?.id == sessionId
                    cur.copy(
                        activeSession = if (clearActive) null else cur.activeSession,
                        items = if (clearActive) emptyList() else cur.items,
                        sessions = cur.sessions.map { if (it.id == closedSummary.id) closedSummary else it }
                    )
                }
            }
        }
    }

    fun resumeSession(sessionId: String) {
        if (_workspaceState.value.isWorking) return
        viewModelScope.launch {
            setWorking(true, "Resuming session...")
            val res = ClipSyncRepository.resumeAiSession(sessionId)
            res.fold(
                onSuccess = { detail ->
                    val restoredItems = detail.messages.map { msg ->
                        if (msg.role == "user") {
                            WorkspaceItem.UserPrompt(
                                id = nextId(),
                                timestamp = msg.timestamp.ifBlank { now() },
                                text = msg.text
                            )
                        } else {
                            WorkspaceItem.AssistantResponse(
                                id = nextId(),
                                timestamp = msg.timestamp.ifBlank { now() },
                                text = msg.text
                            )
                        }
                    }
                    val (baseId, effort) = IdeWorkspaceState.splitModelId(detail.summary.model)
                    val curModels = _workspaceState.value.models
                    val baseGroup = _workspaceState.value.baseModels.find { it.baseId == baseId }
                        ?: BaseModelGroup(baseId, detail.summary.modelLabel.substringBefore(" ("))
                    val composed = IdeWorkspaceState.composeModel(baseGroup, effort, curModels)
                    ClipSyncRepository.setSavedAiModel(getApplication(), composed.id)
                    _workspaceState.update { cur ->
                        cur.copy(
                            activeSession = detail.summary,
                            selectedBaseModel = baseGroup,
                            selectedEffort = effort,
                            selectedModel = composed,
                            items = restoredItems,
                            sessions = listOf(detail.summary) + cur.sessions.filterNot { it.id == detail.summary.id }
                        )
                    }
                    Toast.makeText(getApplication(), "Resumed: ${detail.summary.title}", Toast.LENGTH_SHORT).show()
                },
                onFailure = { err ->
                    Toast.makeText(getApplication(), "Resume failed: ${err.message}", Toast.LENGTH_SHORT).show()
                }
            )
            setWorking(false)
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            val res = ClipSyncRepository.deleteAiSession(sessionId)
            res.fold(
                onSuccess = {
                    _workspaceState.update { cur ->
                        val wasActive = cur.activeSession?.id == sessionId
                        cur.copy(
                            activeSession = if (wasActive) null else cur.activeSession,
                            items = if (wasActive) emptyList() else cur.items,
                            sessions = cur.sessions.filterNot { it.id == sessionId }
                        )
                    }
                },
                onFailure = { err ->
                    Toast.makeText(getApplication(), "Delete failed: ${err.message}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    fun sendAiPrompt(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty() || _workspaceState.value.isWorking) return

        val cur = _workspaceState.value
        val currentModel = cur.selectedModel
        val currentEffort = cur.selectedEffort
        val currentSession = cur.activeSession

        appendItem(
            WorkspaceItem.UserPrompt(
                id = nextId(),
                timestamp = now(),
                text = trimmed
            )
        )

        viewModelScope.launch {
            setWorking(true, "${cur.selectedBaseModel.displayName} is thinking...")
            val result = ClipSyncRepository.sendAiPromptInSession(
                prompt = trimmed,
                model = currentModel.id,
                effort = currentEffort.key,
                sessionId = currentSession?.id,
                conversationId = currentSession?.conversationId?.takeIf { it.isNotBlank() }
            )
            result.fold(
                onSuccess = { res ->
                    appendItem(
                        WorkspaceItem.AssistantResponse(
                            id = nextId(),
                            timestamp = now(),
                            text = res.reply.ifBlank { "(Empty response from model)" }
                        )
                    )
                    res.session?.let { updatedSess ->
                        _workspaceState.update { state ->
                            state.copy(
                                activeSession = updatedSess,
                                sessions = listOf(updatedSess) + state.sessions.filterNot { it.id == updatedSess.id }
                            )
                        }
                    }
                },
                onFailure = { err ->
                    appendItem(
                        WorkspaceItem.ErrorNotice(
                            id = nextId(),
                            timestamp = now(),
                            title = "AI Prompt Failed",
                            detail = err.message ?: "Unable to reach PC agent"
                        )
                    )
                }
            )
            setWorking(false)
        }
    }

    fun runBuildQuickAction() {
        runToolCommand(
            label = "./gradlew assembleDebug",
            command = "cd $projectDir && ./gradlew assembleDebug",
            timeoutSec = 120
        )
    }

    fun runGitDiffQuickAction() {
        runToolCommand(
            label = "git status & diff",
            command = "cd $projectDir && git status -sb && echo '---' && git diff --stat",
            timeoutSec = 20
        )
    }

    fun runToolCommand(label: String, command: String, timeoutSec: Int = 60) {
        if (_workspaceState.value.isWorking) return
        val cardId = nextId()
        appendItem(
            WorkspaceItem.ToolExecution(
                id = cardId,
                timestamp = now(),
                label = label,
                command = command,
                isRunning = true
            )
        )

        viewModelScope.launch {
            setWorking(true, "Running $label...")
            val result = ClipSyncRepository.runTerminalCommand(command, timeoutSec = timeoutSec)
            result.fold(
                onSuccess = { term ->
                    replaceItem(cardId) {
                        WorkspaceItem.ToolExecution(
                            id = cardId,
                            timestamp = now(),
                            label = label,
                            command = command,
                            exitCode = term.exitCode,
                            stdout = term.stdout.trimEnd(),
                            stderr = term.stderr.trimEnd(),
                            isRunning = false
                        )
                    }
                },
                onFailure = { err ->
                    replaceItem(cardId) {
                        WorkspaceItem.ToolExecution(
                            id = cardId,
                            timestamp = now(),
                            label = label,
                            command = command,
                            exitCode = -1,
                            stdout = "",
                            stderr = err.message ?: "Command failed",
                            isRunning = false
                        )
                    }
                }
            )
            setWorking(false)
        }
    }

    fun captureScreenQuickAction() {
        if (_workspaceState.value.isWorking) return
        viewModelScope.launch {
            setWorking(true, "Capturing PC screen...")
            val result = ClipSyncRepository.fetchScreenshotBitmap()
            result.fold(
                onSuccess = { bitmap ->
                    appendItem(
                        WorkspaceItem.ScreenshotItem(
                            id = nextId(),
                            timestamp = now(),
                            bitmap = bitmap
                        )
                    )
                },
                onFailure = { err ->
                    appendItem(
                        WorkspaceItem.ErrorNotice(
                            id = nextId(),
                            timestamp = now(),
                            title = "Screen Capture Failed",
                            detail = err.message ?: "Unable to capture PC screenshot"
                        )
                    )
                }
            )
            setWorking(false)
        }
    }

    fun fetchSystemStatsQuickAction() {
        if (_workspaceState.value.isWorking) return
        viewModelScope.launch {
            setWorking(true, "Reading PC system telemetry...")
            val result = ClipSyncRepository.fetchSystemStats()
            result.fold(
                onSuccess = { stats ->
                    appendItem(
                        WorkspaceItem.SystemStatsItem(
                            id = nextId(),
                            timestamp = now(),
                            stats = stats
                        )
                    )
                },
                onFailure = { err ->
                    appendItem(
                        WorkspaceItem.ErrorNotice(
                            id = nextId(),
                            timestamp = now(),
                            title = "System Stats Unavailable",
                            detail = err.message ?: "Unable to fetch PC stats"
                        )
                    )
                }
            )
            setWorking(false)
        }
    }

    fun clearWorkspace() {
        _workspaceState.update { it.copy(items = emptyList()) }
    }

    private fun appendItem(item: WorkspaceItem) {
        _workspaceState.update { it.copy(items = it.items + item) }
    }

    private fun replaceItem(id: String, transform: (WorkspaceItem) -> WorkspaceItem) {
        _workspaceState.update { current ->
            current.copy(items = current.items.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun setWorking(working: Boolean, status: String = "") {
        _workspaceState.update { it.copy(isWorking = working, workingStatus = status) }
    }

    // Secondary features: Clipboard Sync & Connection settings

    fun updatePcHost(host: String) {
        ClipSyncRepository.setPcHost(getApplication(), host)
        refreshConnectionAndBranch()
    }

    fun updateApiToken(token: String) {
        ClipSyncRepository.setApiToken(getApplication(), token)
    }

    fun discoverPc() {
        viewModelScope.launch {
            val found = ClipSyncRepository.discoverPc(getApplication())
            val msg = if (found != null) "Found PC at $found" else "Using Tailscale / ADB fallback"
            Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
            refreshConnectionAndBranch()
        }
    }

    fun sendCurrentClipboard() {
        val ctx = getApplication<Application>()
        val text = ClipSyncRepository.getPhoneClipboard(ctx)
        if (text.isNullOrEmpty()) {
            Toast.makeText(ctx, "Phone clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        sendText(text)
    }

    fun sendText(text: String) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val result = ClipSyncRepository.sendToPc(ctx, text)
            val msg = result.fold(
                onSuccess = { transport -> "Sent to PC ($transport) ✓" },
                onFailure = { err -> "Failed: ${err.message}" }
            )
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun pullFromPc() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val result = ClipSyncRepository.pullFromPc(ctx)
            val msg = result.fold(
                onSuccess = { (_, transport) -> "Copied PC clipboard ($transport) ✓" },
                onFailure = { err -> "Pull failed: ${err.message}" }
            )
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun copyToPhone(text: String) {
        val ctx = getApplication<Application>()
        ClipSyncRepository.setPhoneClipboard(ctx, text)
        Toast.makeText(ctx, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun toggleService(enable: Boolean) {
        val ctx = getApplication<Application>()
        if (enable) {
            ClipSyncService.start(ctx)
        } else {
            ClipSyncService.stop(ctx)
        }
    }
}
