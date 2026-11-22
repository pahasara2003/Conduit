package com.example.conduit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class ConduitService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var running = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ConduitRepository.init(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification("Listening for PC clipboard")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!running) {
            running = true
            ConduitRepository.setServiceRunning(true)
            startServer()
            scope.launch {
                ConduitRepository.discoverPc(applicationContext)
                ConduitRepository.registerPhoneWithPc(applicationContext)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        ConduitRepository.setServiceRunning(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun startServer() {
        scope.launch {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(ConduitRepository.PHONE_PORT))
                }
                serverSocket = server
                while (running && !server.isClosed) {
                    val client = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    launch { handleClient(client) }
                }
            } catch (_: Exception) {
                ConduitRepository.setServiceRunning(false)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 3000
            val input = BufferedInputStream(sock.getInputStream())
            val output = sock.getOutputStream()

            val headerBytes = ByteArrayOutputStream()
            var prev = -1
            while (true) {
                val b = input.read()
                if (b == -1) break
                headerBytes.write(b)
                if (b == '\n'.code && prev == '\r'.code && headerBytes.size() >= 4) {
                    val arr = headerBytes.toByteArray()
                    val n = arr.size
                    if (arr[n - 4] == '\r'.code.toByte() &&
                        arr[n - 3] == '\n'.code.toByte() &&
                        arr[n - 2] == '\r'.code.toByte() &&
                        arr[n - 1] == '\n'.code.toByte()
                    ) {
                        break
                    }
                }
                prev = b
                if (headerBytes.size() > 16384) return
            }

            val headersText = headerBytes.toString(Charsets.UTF_8.name())
            val lines = headersText.split("\r\n")
            val requestLine = lines.firstOrNull() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1]

            var contentLength = 0
            var givenToken = ""
            for (line in lines.drop(1)) {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim().lowercase()
                    val value = line.substring(idx + 1).trim()
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                    if (key == "x-token") {
                        givenToken = value
                    }
                }
            }

            if (method == "GET" && path == "/ping") {
                writeResponse(output, 200, "conduit-phone")
                return
            }

            // Everything except /ping needs the shared secret; no secret built in means nothing is accepted
            val expected = BuildConfig.PC_TOKEN
            if (expected.isEmpty() || !java.security.MessageDigest.isEqual(
                    givenToken.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
            ) {
                writeResponse(output, 403, "Forbidden")
                return
            }

            if (method == "POST" && path == "/clip") {
                val bodyBytes = ByteArray(contentLength.coerceIn(0, 1024 * 1024))
                var readTotal = 0
                while (readTotal < bodyBytes.size) {
                    val n = input.read(bodyBytes, readTotal, bodyBytes.size - readTotal)
                    if (n <= 0) break
                    readTotal += n
                }
                val text = String(bodyBytes, 0, readTotal, Charsets.UTF_8)
                val remoteIp = sock.inetAddress?.hostAddress
                if (text.isNotEmpty()) {
                    ConduitRepository.onReceivedFromPc(applicationContext, text, remoteIp)
                    mainHandler.post {
                        Toast.makeText(
                            applicationContext,
                            "Clipboard received from PC ✓",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                writeResponse(output, 200, "OK")
                return
            }

            writeResponse(output, 404, "Not Found")
        }
    }

    private fun writeResponse(output: java.io.OutputStream, status: Int, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val statusText = when (status) {
            200 -> "OK"
            404 -> "Not Found"
            else -> "Error"
        }
        val response = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        output.write(response)
        output.write(bodyBytes)
        output.flush()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Conduit Background Listener",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Conduit ready to receive clipboard from your PC"
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pullIntent = Intent(this, QuickActionActivity::class.java).apply {
            action = MainActivity.ACTION_PULL_CLIPBOARD
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pullPending = PendingIntent.getActivity(
            this,
            1,
            pullIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, ConduitService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_clip_sync)
            .setContentTitle("Conduit Active")
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(0, "Pull PC Clip", pullPending)
            .addAction(0, "Stop", stopPending)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "conduit_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.example.conduit.STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, ConduitService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConduitService::class.java))
        }
    }
}
