package com.example.nearchat

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.nearchat.core.ChatCore
import com.example.nearchat.core.ChatMessage
import com.example.nearchat.core.previewText

class NearChatApp : Application() {
    lateinit var core: ChatCore
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Notifications.createChannels(this)
        core = ChatCore(this)
        core.onIncomingMessage = { m, title -> Notifications.showMessage(this, m, title) }
        core.start()
    }
}

val Context.core: ChatCore get() = (applicationContext as NearChatApp).core

/**
 * Saves the stack trace of an uncaught exception to a file, so the next launch can
 * show the user what went wrong (and they can copy it) instead of just closing.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val text = buildString {
                    append("NearChat ").append(BuildInfo.version(app)).append(" · Android ").append(Build.VERSION.RELEASE)
                    append(" (API ").append(Build.VERSION.SDK_INT).append(") · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    append("\nThread: ").append(t.name).append("\n\n")
                    append(Log.getStackTraceString(e))
                }
                java.io.File(app.filesDir, FILE).writeText(text)
            } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
    }

    fun read(ctx: Context): String? =
        java.io.File(ctx.filesDir, FILE).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(ctx: Context) { java.io.File(ctx.filesDir, FILE).delete() }
}

object BuildInfo {
    fun version(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }
}

object Notifications {
    const val CH_MESSAGES = "messages"
    const val CH_SERVICE = "service"
    const val EXTRA_CONV = "conversation"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "הודעות", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "חיבור ברקע", NotificationManager.IMPORTANCE_LOW))
    }

    private fun openIntent(ctx: Context, conv: String?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (conv != null) putExtra(EXTRA_CONV, conv)
        }
        return PendingIntent.getActivity(ctx, conv?.hashCode() ?: 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun showMessage(ctx: Context, m: ChatMessage, title: String) {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val text = if (title == m.senderName) m.previewText() else "${m.senderName}: ${m.previewText()}"
        val n = Notification.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(openIntent(ctx, m.conversationId))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(m.conversationId.hashCode(), n)
    }

    fun serviceNotification(ctx: Context): Notification =
        Notification.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setContentTitle("NearChat פעיל")
            .setContentText("מקבל הודעות ממכשירים קרובים")
            .setOngoing(true)
            .setContentIntent(openIntent(ctx, null))
            .build()
}

/**
 * Keeps the process (and therefore the Bluetooth / Wi-Fi sockets) alive while
 * the app is in the background, so messages keep arriving.
 */
class NearChatService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground() must run before anything else: if the service was started
        // with startForegroundService() and stops without it, Android kills the app.
        val foreground = try {
            startForeground(1, Notifications.serviceNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            true
        } catch (e: Exception) {
            Log.w("NearChatService", "cannot start foreground", e)
            false
        }
        try { core.start() } catch (e: Exception) { Log.e("NearChatService", "core start failed", e) }
        if (!foreground) {
            // Only reached for a system restart in the background (plain startService),
            // where stopping is safe. Try again the next time the app is opened.
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { core.flush() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        /** Needs at least one of these, otherwise the connectedDevice type is refused. */
        private val TRANSPORT_PERMISSIONS = listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )

        /** Call only while an activity is in the foreground. */
        fun start(ctx: Context) {
            if (TRANSPORT_PERMISSIONS.none { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) return
            try { ctx.startForegroundService(Intent(ctx, NearChatService::class.java)) } catch (e: Exception) {
                Log.w("NearChatService", "start failed", e)
            }
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, NearChatService::class.java)) }
    }
}
