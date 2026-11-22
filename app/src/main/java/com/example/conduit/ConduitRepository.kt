package com.example.conduit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

data class ConduitState(
    val pcHost: String = ConduitRepository.DEFAULT_TAILSCALE_HOST,
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

data class AiFile(val path: String, val name: String, val size: Long)

data class AiSessionMessage(
    val role: String,
    val text: String,
    val timestamp: String,
    val files: List<AiFile> = emptyList()
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
    val session: AiSessionSummary?,
    val files: List<AiFile> = emptyList()
)

data class RouteStatus(val label: String, val host: String, val reachable: Boolean, val latencyMs: Long)

object ConduitRepository {
    const val DEFAULT_TAILSCALE_HOST = "100.110.163.70"
    const val PC_PORT = 8765
    const val PHONE_PORT = 8766
    const val DISCOVERY_PORT = 8767
    private const val PREFS_NAME = "conduit_prefs"
    private const val KEY_PC_HOST = "pc_host"
    private const val KEY_ROUTE_MODE = "route_mode"
    private const val KEY_AI_MODEL = "ai_model"
    const val DEFAULT_AI_MODEL = "gemini-3.8-flash-low"

    @Volatile
    private var appContext: Context? = null

    private val _state = MutableStateFlow(ConduitState())
    val state: StateFlow<ConduitState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedHost = prefs.getString(KEY_PC_HOST, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TAILSCALE_HOST
        val localIp = getLocalWifiIp() ?: "No Wi-Fi"
        _state.update {
            it.copy(
                pcHost = savedHost,
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

    /** Pings every route in the order requests try them; the first reachable one is what gets used. */
    suspend fun probeRoutes(): List<RouteStatus> = withContext(Dispatchers.IO) {
        kotlinx.coroutines.coroutineScope {
            allPcTargets(appContext).map { (host, label) ->
                async {
                    val t0 = System.currentTimeMillis()
                    val ok = getText("http://$host:$PC_PORT/ping", connectTimeoutMs = 2000, readTimeoutMs = 2000).isSuccess
                    RouteStatus(label, host, ok, System.currentTimeMillis() - t0)
                }
            }.awaitAll()
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

    suspend fun streamAiPromptInSession(
        prompt: String,
        model: String? = null,
        effort: String? = null,
        sessionId: String? = null,
        conversationId: String? = null,
        onStatus: (String) -> Unit
    ): Result<AiPromptResult> = withContext(Dispatchers.IO) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Prompt is empty"))
        }
        val payload = JSONObject().apply {
            put("prompt", trimmed)
            put("stream", true)
            if (!model.isNullOrBlank()) put("model", model)
            if (!effort.isNullOrBlank()) put("effort", effort)
            if (!sessionId.isNullOrBlank()) put("session_id", sessionId)
            if (!conversationId.isNullOrBlank()) put("conversation_id", conversationId)
        }.toString()

        var lastError: Throwable = IllegalStateException("PC unreachable")
        for ((host, label) in buildPcTargets(appContext)) {
            val url = URL("http://$host:$PC_PORT/api/sessions/prompt")
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 3000
                    readTimeout = 185_000
                    doInput = true
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "text/event-stream")
                    val token = currentApiToken()
                    if (token.isNotEmpty()) {
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                }

                conn.outputStream.use { os ->
                    OutputStreamWriter(os, "UTF-8").use { it.write(payload) }
                }

                val code = conn.responseCode
                if (code in 200..299) {
                    markConnection(true, label)
                    var lastResult: AiPromptResult? = null
                    conn.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        for (rawLine in lines) {
                            val line = rawLine.trim()
                            if (line.startsWith("data:")) {
                                val dataJson = line.removePrefix("data:").trim()
                                if (dataJson.isNotEmpty()) {
                                    runCatching {
                                        val j = JSONObject(dataJson)
                                        val type = j.optString("type")
                                        if (type == "status") {
                                            val statusMsg = j.optString("status")
                                            if (statusMsg.isNotEmpty()) {
                                                withContext(Dispatchers.Main) {
                                                    onStatus(statusMsg)
                                                }
                                            }
                                        } else if (j.optBoolean("done", false)) {
                                            if (j.optString("status") == "error") {
                                                throw IllegalStateException(j.optString("error", "AI failed"))
                                            }
                                            val sessObj = j.optJSONObject("session")
                                            lastResult = AiPromptResult(
                                                reply = j.optString("reply", ""),
                                                model = j.optString("model", model ?: DEFAULT_AI_MODEL),
                                                modelLabel = j.optString("model_label", model ?: DEFAULT_AI_MODEL),
                                                sessionId = j.optString("session_id", sessionId ?: ""),
                                                conversationId = j.optString("conversation_id", conversationId ?: ""),
                                                session = sessObj?.let { parseSessionSummary(it) },
                                                files = parseFiles(j.optJSONArray("files"))
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (lastResult != null) {
                        return@withContext Result.success(lastResult!!)
                    }
                }
            } catch (e: Exception) {
                lastError = e
            } finally {
                conn?.disconnect()
            }
        }
        // Fallback to non-streaming if SSE fails
        sendAiPromptInSession(prompt, model, effort, sessionId, conversationId)
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
                        session = sessObj?.let { parseSessionSummary(it) },
                        files = parseFiles(j.optJSONArray("files"))
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

    private fun parseFiles(arr: org.json.JSONArray?): List<AiFile> = buildList {
        if (arr == null) return@buildList
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            add(AiFile(f.getString("path"), f.optString("name", "file"), f.optLong("size", 0)))
        }
    }

    /** Downloads one file (or a zip of several) from the PC into the phone's Downloads folder. */
    suspend fun downloadFiles(files: List<AiFile>): Result<Pair<android.net.Uri, String>> = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext Result.failure(IllegalStateException("not initialised"))
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return@withContext Result.failure(IllegalStateException("Saving files needs Android 10+"))
        }
        val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
        val (query, name, mime) = if (files.size == 1) {
            Triple("download?path=${enc(files[0].path)}", files[0].name, mimeFor(files[0].name))
        } else {
            Triple("zip?paths=${enc(files.joinToString("\n") { it.path })}", "conduit-files.zip", "application/zip")
        }
        var lastError: Throwable = IllegalStateException("PC unreachable")
        for ((host, label) in buildPcTargets(ctx)) {
            val res = getBytes("http://$host:$PC_PORT/api/files/$query", readTimeoutMs = 120_000, includeToken = true)
            if (res.isFailure) { lastError = res.exceptionOrNull() ?: lastError; continue }
            markConnection(true, label)
            return@withContext runCatching {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                }
                val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not create file in Downloads")
                ctx.contentResolver.openOutputStream(uri)!!.use { it.write(res.getOrThrow()) }
                uri to mime
            }
        }
        Result.failure(lastError)
    }

    private fun mimeFor(name: String): String =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

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
                            timestamp = m.optString("timestamp", ""),
                            files = parseFiles(m.optJSONArray("files"))
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
        // Discovery is a Wi-Fi broadcast and rewrites the saved host: never do that in a manual Tailscale/USB mode
        if (getRouteMode() == "Tailscale" || getRouteMode() == "USB/ADB") {
            _state.update { it.copy(statusMessage = "Manual ${getRouteMode()} mode: discovery skipped") }
            return@withContext null
        }
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
        // The PC pushes clipboard back to this address, so it must belong to the chosen network
        val phoneIp = when (getRouteMode()) {
            "USB/ADB" -> return@withContext // PC reaches the phone through adb forward
            "Tailscale" -> getLocalTailscaleIp()
            else -> getLocalWifiIp()
        } ?: return@withContext
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
        clipboard.setPrimaryClip(ClipData.newPlainText("Conduit", text))
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

    /** "Auto" tries every route in order; otherwise only the chosen transport label is used. */
    val routeModes = listOf("Auto", "Wi-Fi", "Tailscale", "USB/ADB")

    fun getRouteMode(): String =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.getString(KEY_ROUTE_MODE, "Auto") ?: "Auto"

    fun setRouteMode(mode: String) {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.putString(KEY_ROUTE_MODE, mode)?.apply()
    }

    private fun buildPcTargets(context: Context?): List<Pair<String, String>> {
        val mode = getRouteMode()
        val all = allPcTargets(context)
        return if (mode == "Auto") all else all.filter { it.second == mode }
    }

    private fun allPcTargets(context: Context?): List<Pair<String, String>> {
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

    private fun currentApiToken(): String = BuildConfig.PC_TOKEN

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
                currentApiToken().takeIf { it.isNotBlank() }?.let { setRequestProperty("X-Token", it) }
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
        includeToken: Boolean = true
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
        includeToken: Boolean = true
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
        includeToken: Boolean = true
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

    /** This phone's Tailscale address (CGNAT range 100.64.0.0/10), if the VPN is up. */
    fun getLocalTailscaleIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .firstOrNull { ip -> ip.split(".").let { it.size == 4 && it[0] == "100" && it[1].toIntOrNull() in 64..127 } }
    } catch (_: Exception) { null }

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
