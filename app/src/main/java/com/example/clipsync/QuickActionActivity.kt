package com.example.clipsync

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class QuickActionActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingClipboardSend = false
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disableTransition()
        ClipSyncRepository.init(this)

        when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                if (!text.isNullOrEmpty()) {
                    sendAndFinish(text)
                } else {
                    finishSilently()
                }
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrEmpty()) {
                    sendAndFinish(text)
                } else {
                    finishSilently()
                }
            }
            MainActivity.ACTION_SEND_CLIPBOARD -> {
                pendingClipboardSend = true
            }
            MainActivity.ACTION_PULL_CLIPBOARD -> {
                pullAndFinish()
            }
            else -> finishSilently()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pendingClipboardSend && !handled) {
            pendingClipboardSend = false
            handled = true
            val text = ClipSyncRepository.getPhoneClipboard(this)
            if (text.isNullOrEmpty()) {
                Toast.makeText(applicationContext, "Phone clipboard is empty", Toast.LENGTH_SHORT).show()
                finishSilently()
                return
            }
            sendAndFinish(text)
        }
    }

    private fun sendAndFinish(text: String) {
        handled = true
        scope.launch {
            val result = ClipSyncRepository.sendToPc(applicationContext, text)
            val msg = result.fold(
                onSuccess = { transport -> "Sent to PC ($transport) ✓" },
                onFailure = { err -> "Send failed: ${err.message}" }
            )
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            finishSilently()
        }
    }

    private fun pullAndFinish() {
        handled = true
        scope.launch {
            val result = ClipSyncRepository.pullFromPc(applicationContext)
            val msg = result.fold(
                onSuccess = { (_, transport) -> "Copied PC clipboard ($transport) ✓" },
                onFailure = { err -> "Pull failed: ${err.message}" }
            )
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            finishSilently()
        }
    }

    private fun finishSilently() {
        finish()
        disableTransition()
    }

    @Suppress("DEPRECATION")
    private fun disableTransition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }
}
