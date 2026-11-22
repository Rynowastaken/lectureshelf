package com.ryno.classrecorder.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val versionTag: String,
    val title: String,
    val releaseNotes: String,
    val apkName: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long,
    val htmlUrl: String
) {
    val cleanVersion: String get() = versionTag.trim().removePrefix("v").removePrefix("V")
}

sealed interface UpdateCheckResult {
    data class UpdateAvailable(val update: AppUpdateInfo) : UpdateCheckResult
    data class UpToDate(val latestVersion: String) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

sealed interface InstallLaunchResult {
    data object Launched : InstallLaunchResult
    data class NeedsInstallPermission(val settingsIntent: Intent) : InstallLaunchResult
    data class Error(val message: String) : InstallLaunchResult
}

/**
 * Lightweight, zero-dependency GitHub Releases updater for LectureShelf.
 */
object AppUpdater {

    const val GITHUB_OWNER = "Rynowastaken"
    const val GITHUB_REPO = "lectureshelf"
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    private const val USER_AGENT = "LectureShelf-Android-Updater"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    fun currentVersionName(context: Context): String = runCatching {
        val pkg = context.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                pkg,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            ).versionName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, 0).versionName
        }
    }.getOrNull().orEmpty().ifBlank { "0.0.0" }

    fun checkForUpdate(currentVersion: String): UpdateCheckResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return UpdateCheckResult.Error("GitHub returned HTTP $code")
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val release = parseReleaseJson(body)
                ?: return UpdateCheckResult.Error("No APK asset found in latest release")

            if (isNewerVersion(release.versionTag, currentVersion)) {
                UpdateCheckResult.UpdateAvailable(release)
            } else {
                UpdateCheckResult.UpToDate(release.cleanVersion)
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message?.take(120) ?: "Unable to reach GitHub")
        } finally {
            conn?.disconnect()
        }
    }

    fun downloadApk(
        context: Context,
        update: AppUpdateInfo,
        onProgress: (Int) -> Unit
    ): Result<File> {
        var conn: HttpURLConnection? = null
        return try {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val safeName = update.apkName
                .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
                .ifBlank { "LectureShelf-${update.cleanVersion}.apk" }
            val finalFile = File(updatesDir, safeName)
            val partFile = File(updatesDir, "$safeName.part")

            // Clean older cached APKs so cache doesn't grow unbounded.
            updatesDir.listFiles()?.forEach { old ->
                if (old.name != finalFile.name && old.name != partFile.name) {
                    runCatching { old.delete() }
                }
            }

            conn = openFollowingRedirects(update.apkDownloadUrl)
            val code = conn.responseCode
            if (code !in 200..299) {
                return Result.failure(IllegalStateException("Download failed (HTTP $code)"))
            }

            val totalBytes = conn.contentLengthLong.takeIf { it > 0L } ?: update.apkSizeBytes
            var downloaded = 0L
            var lastReportedPct = -1

            BufferedInputStream(conn.inputStream, 64 * 1024).use { input ->
                FileOutputStream(partFile).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        downloaded += read
                        if (totalBytes > 0L) {
                            val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    output.flush()
                }
            }

            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            onProgress(100)
            Result.success(finalFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    fun installApk(context: Context, apkFile: File): InstallLaunchResult {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            return InstallLaunchResult.Error("Downloaded APK is missing")
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return InstallLaunchResult.NeedsInstallPermission(intent)
        }
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            InstallLaunchResult.Launched
        } catch (e: Exception) {
            InstallLaunchResult.Error("Could not open installer: ${e.message ?: "unknown error"}")
        }
    }

    private fun openFollowingRedirects(url: String, maxHops: Int = 5): HttpURLConnection {
        var currentUrl = url
        repeat(maxHops) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IllegalStateException("Redirect missing Location header")
                currentUrl = loc
            } else {
                return conn
            }
        }
        throw IllegalStateException("Too many redirects while downloading APK")
    }

    internal fun parseReleaseJson(rawJson: String): AppUpdateInfo? {
        val obj = JSONObject(rawJson)
        val tag = obj.optString("tag_name").trim()
        if (tag.isEmpty()) return null
        val assets = obj.optJSONArray("assets") ?: return null

        var bestName = ""
        var bestUrl = ""
        var bestSize = 0L
        var bestScore = -1

        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name").trim()
            val url = asset.optString("browser_download_url").trim()
            if (!name.endsWith(".apk", ignoreCase = true) || url.isEmpty()) continue
            val score = when {
                name.contains("arm64", ignoreCase = true) -> 2
                else -> 1
            }
            if (score > bestScore) {
                bestScore = score
                bestName = name
                bestUrl = url
                bestSize = asset.optLong("size", 0L)
            }
        }
        if (bestUrl.isEmpty()) return null

        return AppUpdateInfo(
            versionTag = tag,
            title = obj.optString("name").trim().ifEmpty { "LectureShelf $tag" },
            releaseNotes = obj.optString("body").trim(),
            apkName = bestName,
            apkDownloadUrl = bestUrl,
            apkSizeBytes = bestSize,
            htmlUrl = obj.optString("html_url").trim()
        )
    }

    /**
     * Numeric semver comparison (`v0.3.10 > 0.3.3`, `v0.3.3 == 0.3.3-uitest`).
     */
    fun isNewerVersion(latestTag: String, currentVersion: String): Boolean {
        val latest = parseSemverTuple(latestTag)
        val current = parseSemverTuple(currentVersion)
        val maxLen = maxOf(latest.size, current.size)
        for (i in 0 until maxLen) {
            val l = latest.getOrElse(i) { 0 }
            val c = current.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }

    private fun parseSemverTuple(raw: String): List<Int> {
        val core = raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        if (core.isEmpty()) return listOf(0)
        return core.split('.').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
    }
}
