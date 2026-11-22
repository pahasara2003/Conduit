package com.example.clipsync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SyncDirection {
    PHONE_TO_PC,
    PC_TO_PHONE
}

data class ClipEntry(
    val text: String,
    val direction: SyncDirection,
    val transport: String,
    val timestamp: String
)

data class ClipSyncState(
    val pcHost: String = ClipSyncRepository.DEFAULT_TAILSCALE_HOST,
    val apiToken: String = "",
    val phoneIp: String = "Unknown",
    val isServiceRunning: Boolean = false,
    val isPcOnline: Boolean = false,
    val connectionLabel: String = "Tailscale",
    val activeBranch: String = "main",
    val statusMessage: String = "Ready",
    val history: List<ClipEntry> = emptyList()
)

data class SshInfo(val user: String, val port: Int, val ip: String, val active: Boolean)

data class SystemStats(
    val cpuPercent: Double,
    val ramUsedMb: Int,
    val ramTotalMb: Int,
    val window: String,
    val battery: Int?,
    val tailscaleIp: String
)

data class TerminalResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

data class AiModelInfo(
    val id: String,
    val label: String
)

enum class ReasoningEffort(val key: String, val label: String, val shortLabel: String, val stepIndex: Int) {
    LOW("low", "Low", "Low", 0),
    MEDIUM("medium", "Medium", "Med", 1),
    HIGH("high", "High", "High", 2),
    XHIGH("xhigh", "Max", "Max", 3);

    companion object {
        fun fromStep(step: Int): ReasoningEffort = entries.find { it.stepIndex == step } ?: MEDIUM
        fun fromKey(key: String): ReasoningEffort = when (key.lowercase()) {
            "low" -> LOW
            "medium", "med" -> MEDIUM
            "high" -> HIGH
            "xhigh", "max" -> XHIGH
            else -> MEDIUM
        }
    }
}

data class BaseModelGroup(
    val baseId: String,
    val displayName: String,
    val availableEfforts: List<ReasoningEffort> = ReasoningEffort.entries
)

data class AiSessionMessage(
    val role: String,
    val text: String,
    val timestamp: String
)

data class AiSessionSummary(
    val id: String,
    val title: String,
    val model: String,
    val modelLabel: String,
    val conversationId: String,
    val status: String,
    val updatedAt: Long,
    val messageCount: Int
)

data class AiSessionDetail(
    val summary: AiSessionSummary,
    val messages: List<AiSessionMessage>
)

data class AiPromptResult(
    val reply: String,
    val model: String,
    val modelLabel: String,
    val sessionId: String,
    val conversationId: String,
    val session: AiSessionSummary?
)

object ClipSyncRepository {
    const val DEFAULT_TAILSCALE_HOST = "100.110.163.70"
    const val PC_PORT = 8765
    const val PHONE_PORT = 8766
    const val DISCOVERY_PORT = 8767
    private const val PREFS_NAME = "clipsync_prefs"
    private const val KEY_PC_HOST = "pc_host"
    private const val KEY_API_TOKEN = "api_token"
    private const val KEY_AI_MODEL = "ai_model"
    const val DEFAULT_AI_MODEL = "gemini-3.8-flash-low"

    @Volatile
    private var appContext: Context? = null

    private val _state = MutableStateFlow(ClipSyncState())
    val state: StateFlow<ClipSyncState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedHost = prefs.getString(KEY_PC_HOST, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TAILSCALE_HOST
        val savedToken = prefs.getString(KEY_API_TOKEN, "") ?: ""
        val localIp = getLocalWifiIp() ?: "No Wi-Fi"
        _state.update {
            it.copy(
                pcHost = savedHost,
                apiToken = savedToken,
                phoneIp = localIp,
                connectionLabel = transportLabelForHost(savedHost)
            )
        }
    }

    fun setPcHost(context: Context, host: String) {
        val trimmed = host.trim().ifBlank { DEFAULT_TAILSCALE_HOST }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PC_HOST, trimmed)
            .apply()
        _state.update {
            it.copy(
                pcHost = trimmed,
                connectionLabel = transportLabelForHost(trimmed)
            )
        }
    }

    fun setApiToken(context: Context, token: String) {
        val trimmed = token.trim()
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_TOKEN, trimmed)
            .apply()
        _state.update { it.copy(apiToken = trimmed) }
    }

    fun setActiveBranch(branch: String) {
        val cleaned = branch.trim()
        if (cleaned.isNotEmpty()) {
            _state.update { it.copy(activeBranch = cleaned) }
        }
    }

    fun setServiceRunning(running: Boolean) {
        _state.update {
            it.copy(
                isServiceRunning = running,
                statusMessage = if (running) "Listening on port $PHONE_PORT" else "Listener stopped"
            )
        }
    }

    fun refreshPhoneIp() {
        _state.update { it.copy(phoneIp = getLocalWifiIp() ?: "No Wi-Fi") }
    }

    private fun markConnection(online: Boolean, label: String? = null) {
        _state.update {
            it.copy(
                isPcOnline = online,
                connectionLabel = label ?: it.connectionLabel
            )
        }
    }

    private fun transportLabelForHost(host: String): String = when {
        host == "127.0.0.1" -> "USB/ADB"
        host.startsWith("100.") -> "Tailscale"
        else -> "Wi-Fi"
    }

    suspend fun pingPc(): Result<String> = withContext(Dispatchers.IO) {
        var lastError: Throwable = IllegalStateException("PC unreachable")
        for ((host, label) in buildPcTargets(appContext)) {
            val res = getText("http://$host:$PC_PORT/ping", connectTimeoutMs = 1500, readTimeoutMs = 2000)
            if (res.isSuccess) {
                markConnection(true, label)
                return@withContext Result.success(label)
            }
            lastError = res.exceptionOrNull() ?: lastError
        }
        markConnection(false)
        Result.failure(lastError)
    }

    fun getSavedAiModel(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_AI_MODEL, DEFAULT_AI_MODEL)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_AI_MODEL
    }

    fun setSavedAiModel(context: Context, modelId: String) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AI_MODEL, modelId.trim())
            .apply()
    }

    suspend fun fetchAiModels(): Result<List<AiModelInfo>> = withContext(Dispatchers.IO) {
        var lastError: Throwable = IllegalStateException("PC unreachable")
        for ((host, label) in buildPcTargets(appContext)) {
            val res = getText(
                urlStr = "http://$host:$PC_PORT/api/ai/models",
                connectTimeoutMs = 2500,
                readTimeoutMs = 15_000,
                includeToken = true
            )
            if (res.isSuccess) {
                markConnection(true, label)
                return@withContext runCatching {
                    val arr = JSONObject(res.getOrThrow()).optJSONArray("models")
                    buildList {
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                add(AiModelInfo(obj.getString("id"), obj.optString("label", obj.getString("id"))))
                            }
                        }
                    }
                }
            }
            lastError = res.exceptionOrNull() ?: lastError
        }
        Result.failure(lastError)
    }

    suspend fun fetchAiSessions(): Result<List<AiSessionSummary>> = withContext(Dispatchers.IO) {
        var lastError: Throwable = IllegalStateException("PC unreachable")
        for ((host, label) in buildPcTargets(appContext)) {
            val res = getText(
                urlStr = "http://$host:$PC_PORT/api/ai/sessions",
                connectTimeoutMs = 2500,
                readTimeoutMs = 8000,
                includeToken = true
            )
            if (res.isSuccess) {
                markConnection(true, label)
                return@withContext runCatching {
                    val arr = JSONObject(res.getOrThrow()).optJSONArray("sessions")
                    buildList {
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                add(parseSessionSummary(arr.getJSONObject(i)))
                            }
                        }
                    }
                }
            }
            lastError = res.exceptionOrNull() ?: lastError
        }
        Result.failure(lastError)
    }

    suspend fun createAiSession(model: String, modelLabel: String = "", title: String = "New Session"): Result<AiSessionDetail> =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("model", model)
                if (modelLabel.isNotBlank()) put("model_label", modelLabel)
                put("title", title)
            }.toString()
            var lastError: Throwable = IllegalStateException("PC unreachable")
            for ((host, label) in buildPcTargets(appContext)) {
                val res = postJson(
                    urlStr = "http://$host:$PC_PORT/api/ai/sessions",
                    jsonBody = payload,
                    connectTimeoutMs = 2500,
                    readTimeoutMs = 10_000,
                    includeToken = true
                )
                if (res.isSuccess) {
                    markConnection(true, label)
                    return@withContext runCatching {
                        val sessObj = JSONObject(res.getOrThrow()).getJSONObject("session")
                        parseSessionDetail(sessObj)
                    }
                }
                lastError = res.exceptionOrNull() ?: lastError
            }
            Result.failure(lastError)
        }

    suspend fun closeAiSession(sessionId: String): Result<AiSessionSummary> = withContext(Dispatchers.IO) {
        var lastError: Throwable = IllegalStateException("PC unreachable")
        for ((host, label) in buildPcTargets(appContext)) {
            val res = postJson(
                urlStr = "http://$host:$PC_PORT/api/ai/sessions/$sessionId/close",
                jsonBody = "{}",
                connectTimeoutMs = 2500,
                readTimeoutMs = 8000,
                includeToken = true
            )
            if (res.isSuccess) {
                markConnection(true, label)
                return@withContext runCatching {
                    parseSessionSummary(JSONObject(res.getOrThrow()).getJSONObject("session"))
                }
            }
            lastError = res.exceptionOrNull() ?: lastError
        }
        Result.failure(lastError)
    }

    suspend fun resumeAiSession(sessionId: String): Result<AiSessionDetail> = withContext(Dispatchers.IO) {
        var lastError: Throwable = IllegalStateException("PC unreachable")
        for ((host, label) in buildPcTargets(appContext)) {
            val res = postJson(
                urlStr = "http://$host:$PC_PORT/api/ai/sessions/$sessionId/resume",
                jsonBody = "{}",
                connectTimeoutMs = 2500,
                readTimeoutMs = 8000,
                includeToken = true
            )
            if (res.isSuccess) {
                markConnection(true, label)
                return@withContext runCatching {
                    parseSessionDetail(JSONObject(res.getOrThrow()).getJSONObject("session"))
                }
            }
            lastError = res.exceptionOrNull() ?: lastError
        }
        Result.failure(lastError)
    }

    suspend fun deleteAiSession(sessionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        var lastError: Throwable = IllegalStateException("PC unreachable")
        for ((host, label) in buildPcTargets(appContext)) {
            val res = postJson(
                urlStr = "http://$host:$PC_PORT/api/ai/sessions/$sessionId/delete",
                jsonBody = "{}",
                connectTimeoutMs = 2500,
                readTimeoutMs = 8000,
                includeToken = true
            )
            if (res.isSuccess) {
                markConnection(true, label)
                return@withContext Result.success(Unit)
            }
            lastError = res.exceptionOrNull() ?: lastError
        }
        Result.failure(lastError)
    }

    suspend fun sendAiPrompt(prompt: String): Result<String> {
        return sendAiPromptInSession(prompt = prompt).map { it.reply }
    }

    suspend fun sendAiPromptInSession(
        prompt: String,
        model: String? = null,
        effort: String? = null,
        sessionId: String? = null,
        conversationId: String? = null
    ): Result<AiPromptResult> = withContext(Dispatchers.IO) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Prompt is empty"))
        }
        val payload = JSONObject().apply {
            put("prompt", trimmed)
            if (!model.isNullOrBlank()) put("model", model)
            if (!effort.isNullOrBlank()) put("effort", effort)
            if (!sessionId.isNullOrBlank()) put("session_id", sessionId)
            if (!conversationId.isNullOrBlank()) put("conversation_id", conversationId)
        }.toString()
        var lastError: Throwable = IllegalStateException("PC unreachable")
        for ((host, label) in buildPcTargets(appContext)) {
            val res = postJson(
                urlStr = "http://$host:$PC_PORT/api/ai/prompt",
                jsonBody = payload,
                connectTimeoutMs = 2500,
                readTimeoutMs = 185_000,
                includeToken = true
            )
            if (res.isSuccess) {
                markConnection(true, label)
                val body = res.getOrThrow()
                return@withContext runCatching {
                    val j = JSONObject(body)
                    if (j.optString("status") == "error") {
                        throw IllegalStateException(j.optString("error", "AI request failed"))
                    }
                    val sessObj = j.optJSONObject("session")
                    AiPromptResult(
                        reply = j.optString("reply", body),
                        model = j.optString("model", model ?: DEFAULT_AI_MODEL),
                        modelLabel = j.optString("model_label", model ?: DEFAULT_AI_MODEL),
                        sessionId = j.optString("session_id", sessionId ?: ""),
                        conversationId = j.optString("conversation_id", conversationId ?: ""),
                        session = sessObj?.let { parseSessionSummary(it) }
                    )
                }
            } else {
                val err = res.exceptionOrNull()
                lastError = err ?: lastError
                if (err is ApiHttpException && (err.code == 403 || err.code == 500)) {
                    markConnection(true, label)
                    return@withContext Result.failure(err)
                }
            }
        }
        markConnection(false)
        Result.failure(lastError)
    }

    private fun parseSessionSummary(j: JSONObject): AiSessionSummary {
        val model = j.optString("model", DEFAULT_AI_MODEL)
        return AiSessionSummary(
            id = j.getString("id"),
            title = j.optString("title", "New Session"),
            model = model,
            modelLabel = j.optString("model_label", model),
            conversationId = j.optString("conversation_id", ""),
            status = j.optString("status", "open"),
            updatedAt = j.optLong("updated_at", 0L),
            messageCount = j.optInt("message_count", j.optJSONArray("messages")?.length() ?: 0)
        )
    }

    private fun parseSessionDetail(j: JSONObject): AiSessionDetail {
        val summary = parseSessionSummary(j)
        val arr = j.optJSONArray("messages")
        val msgs = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    add(
                        AiSessionMessage(
                            role = m.optString("role", "assistant"),
                            text = m.optString("text", ""),
                            timestamp = m.optString("timestamp", "")
                        )
                    )
                }
            }
        }
        return AiSessionDetail(summary = summary, messages = msgs)
    }

    suspend fun fetchSystemStats(): Result<SystemStats> = withContext(Dispatchers.IO) {
        var lastError: Throwable = IllegalStateException("PC unreachable")
        for ((host, label) in buildPcTargets(appContext)) {
            val res = getText(
                urlStr = "http://$host:$PC_PORT/api/system/stats",
                connectTimeoutMs = 2500,
                readTimeoutMs = 8000,
                includeToken = true
            )
            if (res.isSuccess) {
                markConnection(true, label)
                return@withContext runCatching {
                    val j = JSONObject(res.getOrThrow())
                    val battery = if (j.isNull("battery")) null else j.optInt("battery")
                    SystemStats(
                        cpuPercent = j.optDouble("cpu_percent", 0.0),
                        ramUsedMb = j.optInt("ram_used_mb", 0),
                        ramTotalMb = j.optInt("ram_total_mb", 0),
                        window = j.optString("window", ""),
                        battery = battery,
                        tailscaleIp = j.optString("tailscale_ip", "")
                    )
                }
            } else {
                val err = res.exceptionOrNull()
                lastError = err ?: lastError
                if (err is ApiHttpException && err.code == 403) {
                    markConnection(true, label)
                    return@withContext Result.failure(err)
                }
            }
        }
        markConnection(false)
        Result.failure(lastError)
    }

    suspend fun runTerminalCommand(
        cmd: String,
        timeoutSec: Int = 60,
        cwd: String? = null
    ): Result<TerminalResult> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("command", cmd)
            put("timeout", timeoutSec)
            if (!cwd.isNullOrBlank()) {
                put("cwd", cwd)
            }
        }.toString()
        var lastError: Throwable = IllegalStateException("PC unreachable")
        for ((host, label) in buildPcTargets(appContext)) {
            val res = postJson(
                urlStr = "http://$host:$PC_PORT/api/terminal/run",
                jsonBody = payload,
                connectTimeoutMs = 2500,
                readTimeoutMs = (timeoutSec + 10) * 1000,
                includeToken = true
            )
            if (res.isSuccess) {
                markConnection(true, label)
                return@withContext runCatching {
                    val j = JSONObject(res.getOrThrow())
                    if (j.optString("status") == "error") {
                        throw IllegalStateException(j.optString("error", "Command failed"))
                    }
                    TerminalResult(
                        command = cmd,
                        exitCode = j.optInt("exit_code", -1),
                        stdout = j.optString("stdout", ""),
                        stderr = j.optString("stderr", "")
                    )
                }
            } else {
                val err = res.exceptionOrNull()
                lastError = err ?: lastError
                if (err is ApiHttpException && (err.code == 400 || err.code == 403)) {
                    markConnection(true, label)
                    return@withContext Result.failure(err)
                }
            }
        }
        markConnection(false)
        Result.failure(lastError)
    }

    suspend fun fetchScreenshotBitmap(): Result<Bitmap> = withContext(Dispatchers.IO) {
        var lastError: Throwable = IllegalStateException("PC unreachable")
        for ((host, label) in buildPcTargets(appContext)) {
            val res = getBytes(
                urlStr = "http://$host:$PC_PORT/api/system/screenshot",
                connectTimeoutMs = 2500,
                readTimeoutMs = 20_000,
                includeToken = true
            )
            if (res.isSuccess) {
                markConnection(true, label)
                val bytes = res.getOrThrow()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                return@withContext if (bmp != null) {
                    Result.success(bmp)
                } else {
                    Result.failure(IllegalStateException("Failed to decode screenshot image"))
                }
            } else {
                val err = res.exceptionOrNull()
                lastError = err ?: lastError
                if (err is ApiHttpException && (err.code == 403 || err.code == 500)) {
                    markConnection(true, label)
                    return@withContext Result.failure(err)
                }
            }
        }
        markConnection(false)
        Result.failure(lastError)
    }

    suspend fun discoverPc(context: Context): String? = withContext(Dispatchers.IO) {
        refreshPhoneIp()
        var foundIp: String? = null
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 1500
                val msg = "CLIPSYNC_DISCOVER".toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(
                    msg,
                    msg.size,
                    InetAddress.getByName("255.255.255.255"),
                    DISCOVERY_PORT
                )
                socket.send(packet)

                val buf = ByteArray(256)
                val response = DatagramPacket(buf, buf.size)
                socket.receive(response)
                val reply = String(response.data, 0, response.length, Charsets.UTF_8)
                if (reply.startsWith("CLIPSYNC_PC:")) {
                    foundIp = response.address.hostAddress
                }
            }
        } catch (_: Exception) {
            // Timeout or no Wi-Fi broadcast response; Tailscale/ADB fallback remains available
        }

        if (!foundIp.isNullOrBlank()) {
            setPcHost(context, foundIp!!)
            _state.update { it.copy(statusMessage = "Found PC at $foundIp") }
            registerPhoneWithPc(context)
        } else {
            _state.update { it.copy(statusMessage = "Using ${state.value.pcHost} (Tailscale / ADB ready)") }
        }
        foundIp
    }

    suspend fun registerPhoneWithPc(context: Context) = withContext(Dispatchers.IO) {
        val phoneIp = getLocalWifiIp() ?: return@withContext
        val payload = """{"ip":"$phoneIp","port":$PHONE_PORT}"""
        val targets = buildPcTargets(context)
        for ((host, _) in targets) {
            if (postText("http://$host:$PC_PORT/register", payload).isSuccess) {
                break
            }
        }
    }

    suspend fun sendToPc(context: Context, text: String): Result<String> = withContext(Dispatchers.IO) {
        if (text.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Text is empty"))
        }
        val targets = buildPcTargets(context)
        var lastError: Throwable? = null
        for ((host, label) in targets) {
            val result = postText("http://$host:$PC_PORT/clip", text)
            if (result.isSuccess) {
                markConnection(true, label)
                addHistory(text, SyncDirection.PHONE_TO_PC, label)
                _state.update { it.copy(statusMessage = "Sent to PC via $label") }
                return@withContext Result.success(label)
            } else {
                lastError = result.exceptionOrNull()
            }
        }
        val errMsg = lastError?.message ?: "PC unreachable"
        _state.update { it.copy(statusMessage = "Send failed: $errMsg") }
        Result.failure(lastError ?: IllegalStateException("PC unreachable"))
    }

    suspend fun pullFromPc(context: Context): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        val targets = buildPcTargets(context)
        var lastError: Throwable? = null
        for ((host, label) in targets) {
            val result = getText("http://$host:$PC_PORT/clip")
            if (result.isSuccess) {
                markConnection(true, label)
                val text = result.getOrThrow()
                if (text.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        setPhoneClipboard(context, text)
                    }
                    addHistory(text, SyncDirection.PC_TO_PHONE, label)
                    _state.update { it.copy(statusMessage = "Pulled from PC via $label") }
                    return@withContext Result.success(text to label)
                } else {
                    return@withContext Result.failure(IllegalStateException("PC clipboard is empty"))
                }
            } else {
                lastError = result.exceptionOrNull()
            }
        }
        val errMsg = lastError?.message ?: "PC unreachable"
        _state.update { it.copy(statusMessage = "Pull failed: $errMsg") }
        Result.failure(lastError ?: IllegalStateException("PC unreachable"))
    }

    fun onReceivedFromPc(context: Context, text: String, remoteAddress: String?) {
        val transport = when {
            remoteAddress == "127.0.0.1" || remoteAddress == "::1" -> "USB/ADB"
            remoteAddress?.startsWith("100.") == true -> "Tailscale"
            else -> "Wi-Fi"
        }
        mainHandler.post {
            setPhoneClipboard(context, text)
        }
        addHistory(text, SyncDirection.PC_TO_PHONE, transport)
        _state.update { it.copy(statusMessage = "Received from PC via $transport") }
    }

    fun setPhoneClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ClipSync", text))
    }

    fun getPhoneClipboard(context: Context): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!clipboard.hasPrimaryClip()) return null
        val item = clipboard.primaryClip?.getItemAt(0) ?: return null
        return item.coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }
    }

    private fun addHistory(text: String, direction: SyncDirection, transport: String) {
        val entry = ClipEntry(
            text = text,
            direction = direction,
            transport = transport,
            timestamp = timeFormat.format(Date())
        )
        _state.update { current ->
            current.copy(history = (listOf(entry) + current.history).take(15))
        }
    }

    private fun buildPcTargets(context: Context?): List<Pair<String, String>> {
        val saved = _state.value.pcHost.ifBlank {
            context?.applicationContext
                ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(KEY_PC_HOST, "") ?: ""
        }
        val list = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()

        fun addTarget(host: String, label: String) {
            if (host.isNotBlank() && seen.add(host)) {
                list.add(host to label)
            }
        }

        // Prefer Tailscale default or saved host first, then fallback to the other, then USB/ADB
        if (saved.isNotBlank() && saved != "127.0.0.1") {
            addTarget(saved, transportLabelForHost(saved))
        }
        addTarget(DEFAULT_TAILSCALE_HOST, "Tailscale")
        addTarget("127.0.0.1", "USB/ADB")
        return list
    }

    private fun currentApiToken(): String {
        val inMem = _state.value.apiToken
        if (inMem.isNotBlank()) return inMem
        return appContext
            ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(KEY_API_TOKEN, "") ?: ""
    }

    private fun parseHttpError(conn: HttpURLConnection, code: Int): ApiHttpException {
        val errBody = try {
            conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        } catch (_: Exception) {
            ""
        }
        val detail = try {
            JSONObject(errBody).optString("error").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            errBody.takeIf { it.isNotBlank() }
        }
        val msg = when {
            code == 403 -> detail ?: "HTTP 403: Set PC API Token (X-Token) in Settings & Tools"
            detail != null -> "HTTP $code: $detail"
            else -> "HTTP $code"
        }
        return ApiHttpException(code, msg)
    }

    private fun postText(urlStr: String, body: String): Result<Unit> {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 1500
                readTimeout = 2500
                doOutput = true
                setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
                writer.flush()
            }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("HTTP $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun postJson(
        urlStr: String,
        jsonBody: String,
        connectTimeoutMs: Int = 2500,
        readTimeoutMs: Int = 30_000,
        includeToken: Boolean = false
    ): Result<String> {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (includeToken) {
                    val tok = currentApiToken()
                    if (tok.isNotBlank()) {
                        setRequestProperty("X-Token", tok)
                    }
                }
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonBody)
                writer.flush()
            }
            val code = conn.responseCode
            if (code in 200..299) {
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                conn.disconnect()
                Result.success(text)
            } else {
                val err = parseHttpError(conn, code)
                conn.disconnect()
                Result.failure(err)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendNotificationToPc(
        context: Context,
        appName: String,
        title: String,
        text: String,
        pkg: String,
        imageBase64: String? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            val targets = buildPcTargets(context)
            var lastError: Throwable? = null
            for ((host, _) in targets) {
                val json = JSONObject().apply {
                    put("type", "notification")
                    put("package", pkg)
                    put("app_name", appName)
                    put("title", title)
                    put("text", text)
                    put("timestamp", System.currentTimeMillis())
                    if (!imageBase64.isNullOrEmpty()) {
                        put("image_base64", imageBase64)
                    }
                }
                val payload = json.toString()
                val result = postText("http://$host:$PC_PORT/notification", payload)
                if (result.isSuccess) {
                    lastError = null
                    break
                } else {
                    lastError = result.exceptionOrNull()
                }
            }
            lastError == null
        }
    }

    private fun getText(
        urlStr: String,
        connectTimeoutMs: Int = 1500,
        readTimeoutMs: Int = 2500,
        includeToken: Boolean = false
    ): Result<String> {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                if (includeToken) {
                    val tok = currentApiToken()
                    if (tok.isNotBlank()) {
                        setRequestProperty("X-Token", tok)
                    }
                }
            }
            val code = conn.responseCode
            if (code in 200..299) {
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                conn.disconnect()
                Result.success(text)
            } else {
                val err = parseHttpError(conn, code)
                conn.disconnect()
                Result.failure(err)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getBytes(
        urlStr: String,
        connectTimeoutMs: Int = 2500,
        readTimeoutMs: Int = 15_000,
        includeToken: Boolean = false
    ): Result<ByteArray> {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                if (includeToken) {
                    val tok = currentApiToken()
                    if (tok.isNotBlank()) {
                        setRequestProperty("X-Token", tok)
                    }
                }
            }
            val code = conn.responseCode
            if (code in 200..299) {
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                Result.success(bytes)
            } else {
                val err = parseHttpError(conn, code)
                conn.disconnect()
                Result.failure(err)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchSshInfo(context: Context): Result<SshInfo> = withContext(Dispatchers.IO) {
        var last: Throwable = IllegalStateException("PC unreachable")
        for ((host, _) in buildPcTargets(context)) {
            val r = getText("http://$host:$PC_PORT/ssh/info")
            if (r.isSuccess) {
                return@withContext runCatching {
                    val j = JSONObject(r.getOrThrow())
                    SshInfo(j.getString("user"), j.getInt("port"), j.getString("ip"), j.getBoolean("active"))
                }
            }
            last = r.exceptionOrNull() ?: last
        }
        Result.failure(last)
    }

    suspend fun authorizeSshKey(context: Context, pubKey: String): Result<Unit> = withContext(Dispatchers.IO) {
        var last: Throwable = IllegalStateException("PC unreachable")
        for ((host, _) in buildPcTargets(context)) {
            val r = postText("http://$host:$PC_PORT/ssh/authorize", pubKey)
            if (r.isSuccess) return@withContext r
            last = r.exceptionOrNull() ?: last
        }
        Result.failure(last)
    }

    fun getLocalWifiIp(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase(Locale.US)
                if (!name.startsWith("wlan") && !name.startsWith("eth") && !name.startsWith("ap")) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}

class ApiHttpException(val code: Int, message: String) : IllegalStateException(message)
