package com.example.clipsync

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream

class ClipSyncNotificationListenerService : NotificationListenerService() {

    private var lastNotificationKey: String? = null
    private var lastNotificationTime: Long = 0
    private val deduplicationWindowMs = 2000L

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: return

        if (title.isBlank() && text.isBlank()) return

        val lowerTitle = title.lowercase()
        val lowerText = text.lowercase()

        // Filter out transient sync background notices
        val ignoredPhrases = listOf(
            "checking for new messages",
            "checking for messages",
            "whatsapp web is currently active",
            "backup in progress"
        )
        if (ignoredPhrases.any { lowerTitle.contains(it) || lowerText.contains(it) }) return

        val currentKey = "${sbn.packageName}:$title:$text"
        val currentTime = SystemClock.elapsedRealtime()
        if (currentKey == lastNotificationKey && currentTime - lastNotificationTime < deduplicationWindowMs) {
            return
        }
        lastNotificationKey = currentKey
        lastNotificationTime = currentTime

        val context = this.applicationContext
        val pkg = sbn.packageName

        val appName = try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }

        val imageBase64 = extractImageThumbnail(context, sbn.notification)
        Log.d("ClipSyncNotification", "Extracted imageBase64: ${imageBase64 != null} (len: ${imageBase64?.length ?: 0}) for $pkg ($title)")

        runBlocking(Dispatchers.IO) {
            ClipSyncRepository.sendNotificationToPc(context, appName, title, text, pkg, imageBase64)
        }
    }

    private fun iconToBitmap(context: Context, icon: Icon): Bitmap? {
        return try {
            val drawable = icon.loadDrawable(context) ?: return null
            if (drawable is BitmapDrawable) {
                return drawable.bitmap
            }
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp
        } catch (e: Exception) {
            Log.e("ClipSyncNotification", "iconToBitmap error", e)
            null
        }
    }

    private fun extractImageThumbnail(context: Context, notification: Notification): String? {
        var bitmap: Bitmap? = null
        val extras = notification.extras ?: return null

        // 1. Check EXTRA_PICTURE (BigPictureStyle)
        val picture = extras.get(Notification.EXTRA_PICTURE)
        if (picture is Bitmap) {
            bitmap = picture
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && picture is Icon) {
            bitmap = iconToBitmap(context, picture)
        }

        // 2. Check EXTRA_PICTURE_ICON (API 31+)
        if (bitmap == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val pictureIcon = extras.get(Notification.EXTRA_PICTURE_ICON)
            if (pictureIcon is Icon) {
                bitmap = iconToBitmap(context, pictureIcon)
            }
        }

        // 3. Check EXTRA_LARGE_ICON_BIG
        if (bitmap == null) {
            val largeIconBig = extras.get(Notification.EXTRA_LARGE_ICON_BIG)
            if (largeIconBig is Bitmap) {
                bitmap = largeIconBig
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && largeIconBig is Icon) {
                bitmap = iconToBitmap(context, largeIconBig)
            }
        }

        // 4. Check EXTRA_LARGE_ICON (Common in WhatsApp photo messages)
        if (bitmap == null) {
            val largeIcon = extras.get(Notification.EXTRA_LARGE_ICON)
            if (largeIcon is Bitmap) {
                bitmap = largeIcon
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && largeIcon is Icon) {
                bitmap = iconToBitmap(context, largeIcon)
            }
        }

        // 5. Check MessagingStyle message data URI
        if (bitmap == null) {
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (messages != null && messages.isNotEmpty()) {
                for (i in messages.indices.reversed()) {
                    val bundle = messages[i] as? Bundle ?: continue
                    val dataUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        bundle.getParcelable("data_uri", Uri::class.java) ?: bundle.getParcelable("uri", Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        bundle.getParcelable<Uri>("data_uri") ?: bundle.getParcelable<Uri>("uri")
                    }
                    if (dataUri != null) {
                        try {
                            context.contentResolver.openInputStream(dataUri)?.use { input ->
                                bitmap = BitmapFactory.decodeStream(input)
                            }
                            if (bitmap != null) break
                        } catch (e: Exception) {
                            Log.e("ClipSyncNotification", "dataUri decode error", e)
                        }
                    }
                }
            }
        }

        bitmap ?: return null

        val circular = getCircularBitmap(bitmap)
        val maxDimension = 256
        val width = circular.width
        val height = circular.height
        val scaledBitmap = if (width > maxDimension || height > maxDimension) {
            Bitmap.createScaledBitmap(circular, maxDimension, maxDimension, true)
        } else {
            circular
        }

        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, size, size)
        val rectF = RectF(rect)

        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawOval(rectF, paint)

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val left = (bitmap.width - size) / 2
        val top = (bitmap.height - size) / 2
        val srcRect = Rect(left, top, left + size, top + size)
        canvas.drawBitmap(bitmap, srcRect, rect, paint)

        return output
    }
}
