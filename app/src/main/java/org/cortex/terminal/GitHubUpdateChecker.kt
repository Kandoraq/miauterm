package org.cortex.terminal

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.SuppressLint
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object GitHubUpdateChecker {
    private const val API_URL = "https://api.github.com/repos/Kandoraq/miauterm/releases/latest"
    private const val RELEASES_URL = "https://github.com/Kandoraq/miauterm/releases"
    private const val CHANNEL_ID = "miauterm_updates"
    private const val NOTIFICATION_ID = 3101

    internal data class Release(val version: String, val url: String)

    internal sealed interface Result {
        data class Available(val release: Release) : Result
        data object Current : Result
        data class Failed(val message: String) : Result
    }

    fun check(): Result {
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Miauterm/${BuildConfig.VERSION_NAME}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                return Result.Failed("GitHub returned HTTP ${connection.responseCode}")
            }
            val release = connection.inputStream.bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                val tag = json.optString("tag_name").removePrefix("v")
                val url = json.optString("html_url")
                if (tag.isBlank() || !url.startsWith(RELEASES_URL + "/tag/")) {
                    return Result.Failed("GitHub release information was invalid")
                }
                Release(tag, url)
            }
            if (compareVersions(release.version, BuildConfig.VERSION_NAME) > 0) Result.Available(release)
            else Result.Current
        } catch (error: Exception) {
            Result.Failed(error.message?.takeIf(String::isNotBlank) ?: "Could not reach GitHub")
        } finally {
            connection.disconnect()
        }
    }

    @SuppressLint("MissingPermission")
    fun postNotification(context: Context, release: Release): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val platformManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            platformManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.github_updates_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.github_updates_channel_description)
                    setShowBadge(false)
                }
            )
            if (platformManager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        if (!manager.areNotificationsEnabled()) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.update_available_title))
            .setContentText(context.getString(R.string.update_notification_text, release.version))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.update_notification_text, release.version)))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        return runCatching {
            manager.notify(NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    private fun compareVersions(left: String, right: String): Int {
        fun core(version: String): List<Long> = version.substringBefore('-').substringBefore('+')
            .split('.')
            .map { it.toLongOrNull() ?: 0L }
        val leftCore = core(left)
        val rightCore = core(right)
        for (index in 0 until maxOf(leftCore.size, rightCore.size)) {
            val comparison = (leftCore.getOrNull(index) ?: 0L).compareTo(rightCore.getOrNull(index) ?: 0L)
            if (comparison != 0) return comparison
        }
        val leftPre = left.substringAfter('-', "").substringBefore('+')
        val rightPre = right.substringAfter('-', "").substringBefore('+')
        return when {
            leftPre.isEmpty() && rightPre.isNotEmpty() -> 1
            leftPre.isNotEmpty() && rightPre.isEmpty() -> -1
            else -> leftPre.compareTo(rightPre, ignoreCase = true)
        }
    }
}
