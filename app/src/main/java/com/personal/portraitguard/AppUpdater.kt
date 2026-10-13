package com.personal.portraitguard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String?,
)

/** Checks GitHub Releases for a newer build and installs its APK. Release tags look like v1.0.<versionCode>. */
object AppUpdater {
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/tiger-git2022/portrait-guard/releases/latest"
    const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    fun currentVersionCode(context: Context): Int =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)).toInt()

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    suspend fun fetchLatest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val json = connect(LATEST_RELEASE_URL, "application/vnd.github+json").use { connection ->
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        }
        val tag = json.getString("tag_name")
        val versionCode = Regex("""(\d+)$""").find(tag)?.value?.toIntOrNull()
            ?: throw IOException("无法从标签 $tag 解析版本号")
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map(assets::getJSONObject)
            .firstOrNull { it.getString("name").endsWith(".apk", ignoreCase = true) }
            ?: throw IOException("最新发布中没有 APK 文件")
        ReleaseInfo(
            versionCode = versionCode,
            versionName = tag.removePrefix("v"),
            notes = json.stringOrNull("body").orEmpty().trim(),
            apkUrl = apk.getString("browser_download_url"),
            apkSize = apk.optLong("size", -1L),
            sha256 = apk.stringOrNull("digest")?.removePrefix("sha256:")?.takeIf { it.length == 64 },
        )
    }

    /** Downloads the APK into cache/updates, reporting progress in 0..1 (null when the size is unknown). */
    suspend fun download(context: Context, release: ReleaseInfo, onProgress: (Float?) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply {
                deleteRecursively()
                mkdirs()
            }
            val file = File(dir, "PortraitGuard-${release.versionName}.apk")
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            connect(release.apkUrl, "application/octet-stream").use { connection ->
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                var lastPercent = -1
                onProgress(null)
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val percent = (downloaded * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(downloaded.toFloat() / total)
                                }
                            }
                        }
                    }
                }
                if (total > 0 && downloaded != total) throw IOException("下载不完整，请重试")
            }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && !release.sha256.equals(actualSha256, ignoreCase = true)) {
                file.delete()
                throw IOException("安装包校验失败，请重试")
            }
            val archive = context.packageManager.getPackageArchiveInfo(file.path, 0)
            if (archive?.packageName != context.packageName) {
                file.delete()
                throw IOException("下载的文件不是有效的安装包")
            }
            file
        }

    fun canInstallPackages(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
        )
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private fun connect(url: String, accept: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("User-Agent", "PortraitGuard-Updater")
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw IOException(if (code == HttpURLConnection.HTTP_NOT_FOUND) "暂无已发布的版本" else "服务器返回 HTTP $code")
        }
        return connection
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }

    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key)
}
