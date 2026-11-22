package salu.Kandoraq.miauterm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.File
import salu.Kandoraq.miauterm.MainActivity
import salu.Kandoraq.Miauterm.R
import salu.Kandoraq.miauterm.session.SessionManager
import salu.Kandoraq.miauterm.session.SessionStore

class MiautermService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1337
        const val CHANNEL_ID = "miauterm_terminal_channel"

        const val ACTION_START = "salu.Kandoraq.Miauterm.action.START"
        const val ACTION_STOP = "salu.Kandoraq.Miauterm.action.STOP"
        const val ACTION_EXIT = "salu.Kandoraq.Miauterm.action.EXIT"
        const val ACTION_TOGGLE_WAKELOCK = "salu.Kandoraq.Miauterm.action.TOGGLE_WAKELOCK"
        const val ACTION_SET_WAKELOCK = "salu.Kandoraq.Miauterm.action.SET_WAKELOCK"
        const val EXTRA_WAKELOCK_ENABLED = "salu.Kandoraq.Miauterm.extra.WAKELOCK_ENABLED"
        const val ACTION_UPDATE_NOTIFICATION = "salu.Kandoraq.Miauterm.action.UPDATE_NOTIFICATION"

        const val ACTION_COPY_LINK = "salu.Kandoraq.Miauterm.action.COPY_LINK"
        const val EXTRA_LINK = "salu.Kandoraq.Miauterm.extra.LINK"

        @Volatile
        var instance: MiautermService? = null
            private set

        @Volatile
        private var _sessionManager: SessionManager? = null

        @Volatile
        var isRunning: Boolean = false
        private const val PREF_LAST_VERSION_CODE = "last_version_code"

        @Synchronized
        fun getOrCreateSessionManager(context: Context): SessionManager {
            val appVersion = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0).versionCode
                }
            } catch (e: Exception) { 0 }

            val versionPrefs = context.getSharedPreferences("miauterm_service", Context.MODE_PRIVATE)
            val lastVersionCode = versionPrefs.getInt(PREF_LAST_VERSION_CODE, 0)
            if (lastVersionCode != 0 && lastVersionCode < appVersion && _sessionManager != null) {
                Log.i("MiautermService", "App version updated from $lastVersionCode to $appVersion. Resetting sessions.")
                try {
                    _sessionManager?.destroyAll(forget = false)
                } catch (e: Exception) {
                    Log.w("MiautermService", "Could not destroy sessions after version update", e)
                }
                _sessionManager = null
            }
            if (versionPrefs.getInt(PREF_LAST_VERSION_CODE, 0) != appVersion) {
                versionPrefs.edit().putInt(PREF_LAST_VERSION_CODE, appVersion).apply()
            }

            if (_sessionManager == null) {
                _sessionManager = SessionManager(context.applicationContext).also {
                    it.store = SessionStore(File(context.filesDir, "sessions"))
                }
            }
            return _sessionManager!!
        }

        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L

        @Volatile
        private var wakeLock: PowerManager.WakeLock? = null

        val isWakeLockHeld: Boolean
            get() = wakeLock?.isHeld == true

        fun setWakeLock(context: Context, enabled: Boolean): Boolean {
            val service = instance
            if (service != null) {
                service.applyWakeLock(enabled)
                return true
            }
            if (!enabled) return true
            val intent = Intent(context, MiautermService::class.java).apply {
                action = ACTION_SET_WAKELOCK
                putExtra(EXTRA_WAKELOCK_ENABLED, enabled)
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                Log.e("MiautermService", "setWakeLock failed to start service", e)
                false
            }
        }

        fun start(context: Context) {
            try {
                val intent = Intent(context, MiautermService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        context.startForegroundService(intent)
                    } catch (e: IllegalStateException) {

                        Log.w("MiautermService", "startForegroundService blocked, using startService", e)
                        try { context.startService(intent) } catch (e2: Exception) {
                            Log.e("MiautermService", "Failed to start service", e2)
                        }
                    }
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("MiautermService", "Failed to start service", e)
            }
        }

        fun updateNotification(context: Context) {
            try {
                val intent = Intent(context, MiautermService::class.java).apply {
                    action = ACTION_UPDATE_NOTIFICATION
                }

                context.startService(intent)
            } catch (e: IllegalStateException) {
                Log.w("MiautermService", "updateNotification skipped (background)", e)
            } catch (e: Exception) {
                Log.e("MiautermService", "updateNotification failed", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, MiautermService::class.java))
            } catch (e: IllegalStateException) {
                Log.w("MiautermService", "stop skipped (background)", e)
            } catch (e: Exception) {
                Log.e("MiautermService", "stop failed", e)
            }
        }
    }

    private var localServers: LocalServers? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true

        try {
            createNotificationChannel()
        } catch (e: Exception) {
            Log.e("MiautermService", "createNotificationChannel failed", e)
        }
        // Promote to foreground immediately with a minimal notification. Android kills an app
        // that calls startForegroundService() without a prompt startForeground(), and building
        // the full notification can be slow on first run.
        try {
            startForeground(NOTIFICATION_ID, buildBootNotification())
        } catch (e: Exception) {
            Log.e("MiautermService", "initial startForeground failed", e)
            try {
                startForeground(NOTIFICATION_ID, buildBootNotification())
            } catch (retry: Exception) {
                Log.e("MiautermService", "Could not start foreground service after retry", retry)
                stopSelf()
                return
            }
        }
        try {
            refreshForegroundNotification()
        } catch (e: Exception) {
            Log.w("MiautermService", "Could not refresh foreground notification", e)
        }

        try {
            getOrCreateSessionManager(this)
        } catch (e: Exception) {
            Log.e("MiautermService", "getOrCreateSessionManager failed", e)
        }

        salu.Kandoraq.miauterm.api.ApiServer.start(this)

        localServers = LocalServers(applicationContext, salu.Kandoraq.miauterm.runtime.Environment.getMiautermRoot(this)).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        try {
            refreshForegroundNotification()
        } catch (e: Exception) {
            Log.w("MiautermService", "re-promote startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_EXIT -> {
                exitAll()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_WAKELOCK -> {
                toggleWakeLock()
                updateNotificationDisplay()
            }
            ACTION_SET_WAKELOCK -> {
                applyWakeLock(intent.getBooleanExtra(EXTRA_WAKELOCK_ENABLED, false))
                updateNotificationDisplay()
            }
            ACTION_UPDATE_NOTIFICATION -> {
                updateNotificationDisplay()
            }
            ACTION_COPY_LINK -> {
                intent.getStringExtra(EXTRA_LINK)?.let { link ->
                    val uri = runCatching { android.net.Uri.parse(link) }.getOrNull()
                    if (uri?.scheme == "http" && uri.host == "localhost" && uri.port in 1..65535) {
                        try {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("URL", link))
                            android.widget.Toast.makeText(this, this.getString(R.string.copied_url, link), android.widget.Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Log.w("MiautermService", "Copying the link failed", e)
                        }
                    }
                }
            }
            ACTION_STOP -> {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                updateNotificationDisplay()
            }
        }
        return START_STICKY
    }

    private fun toggleWakeLock() {
        applyWakeLock(!isWakeLockHeld)
    }

    private fun applyWakeLock(enabled: Boolean) {
        if (enabled) acquireWakeLock() else releaseWakeLock()
    }

    private fun acquireWakeLock() {
        try {
            val lock = wakeLock ?: run {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "miauterm:wakelock").also { wakeLock = it }
            }
            if (!lock.isHeld) {
                lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.e("MiautermService", "Failed to acquire wakelock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e("MiautermService", "Failed to release wakelock", e)
        }
    }

    fun exitAll() {
        try {
            releaseWakeLock()
            _sessionManager?.destroyAll()
            _sessionManager = null
        } catch (e: Exception) {
            Log.w("MiautermService", "Could not exit all sessions", e)
        }

        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()

        MainActivity.instance?.get()?.let { act ->
            act.runOnUiThread {
                act.finishAndRemoveTask()
            }
        }
    }

    private fun updateNotificationDisplay() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e("MiautermService", "Failed to update notification", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val lc = this
            val channel = NotificationChannel(
                CHANNEL_ID,
                lc.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = lc.getString(R.string.notif_channel_description)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildBootNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }

    private fun refreshForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), 0)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val lc = this
        val sessionCount = _sessionManager?.sessionCount() ?: 0
        val contentText = if (isWakeLockHeld) {
            lc.getString(R.string.notif_sessions_wake_lock, sessionCount)
        } else {
            lc.getString(R.string.notif_sessions, sessionCount)
        }

        val flagImmutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or flagImmutable
        )

        val exitIntent = Intent(this, MiautermService::class.java).apply {
            action = ACTION_EXIT
        }
        val exitPendingIntent = PendingIntent.getService(
            this, 1, exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or flagImmutable
        )

        val wlIntent = Intent(this, MiautermService::class.java).apply {
            action = ACTION_TOGGLE_WAKELOCK
        }
        val wlPendingIntent = PendingIntent.getService(
            this, 2, wlIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or flagImmutable
        )

        val wlActionTitle = lc.getString(
            if (isWakeLockHeld) R.string.notif_action_release_wakelock else R.string.notif_action_acquire_wakelock
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setContentTitle(lc.getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    0, lc.getString(R.string.notif_action_exit), exitPendingIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    0, wlActionTitle, wlPendingIntent
                ).build()
            )

        return builder.build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {

        if (_sessionManager == null || _sessionManager?.sessionCount() == 0) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            salu.Kandoraq.miauterm.api.ApiServer.stop(this)
            localServers?.stop()
            localServers = null
            releaseWakeLock()
        } catch (e: Exception) {
            Log.e("MiautermService", "Service cleanup failed", e)
        } finally {
            isRunning = false
            instance = null
            super.onDestroy()
        }
    }
}
