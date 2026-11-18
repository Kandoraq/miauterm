package org.cortex.terminal

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
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

    fun postNotification(context: Context, release: Release): Boolean {
        val manager = NotificationManagerCompat.from(context)
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
        val leftParts = left.split('.', '-', '+')
        val rightParts = right.split('.', '-', '+')
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val a = leftParts.getOrNull(index).orEmpty()
            val b = rightParts.getOrNull(index).orEmpty()
            val numericA = a.toLongOrNull()
            val numericB = b.toLongOrNull()
            val comparison = when {
                numericA != null && numericB != null -> numericA.compareTo(numericB)
                numericA != null -> 1
                numericB != null -> -1
                else -> a.compareTo(b, ignoreCase = true)
            }
            if (comparison != 0) return comparison
        }
        return 0
    }
}
