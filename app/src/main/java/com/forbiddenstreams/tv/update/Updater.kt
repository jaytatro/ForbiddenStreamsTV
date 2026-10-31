package com.forbiddenstreams.tv.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.forbiddenstreams.tv.data.Http
import com.forbiddenstreams.tv.data.jsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class AppUpdate(val build: Int, val url: String, val current: Int)

/**
 * Checks GitHub for a newer build (release tags are "build-<N>", N = the app's versionCode),
 * downloads the APK and opens Android's installer.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/jaytatro/ForbiddenStreamsTV/releases/latest"

    @Suppress("DEPRECATION")
    fun currentBuild(context: Context): Int = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    } catch (e: Exception) { 0 }

    /** The newer build, or null when this one is the newest. */
    suspend fun check(context: Context): AppUpdate? = withContext(Dispatchers.IO) {
        val j = Http.client.newCall(
            Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").header("User-Agent", "ForbiddenStreamsTV").build(),
        ).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Update check failed (HTTP ${r.code})")
            jsonObject(r.body?.string() ?: "")
        } ?: throw IOException("Update check failed")
        val build = j.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return@withContext null
        val assets = j.optJSONArray("assets")
        var url: String? = null
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
        }
        val cur = currentBuild(context)
        if (url == null || build <= cur) null else AppUpdate(build, url, cur)
    }

    private fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "updates").apply { mkdirs() }

    /** Downloads the APK; [progress] gets 0..100 (or -1 when the size is unknown). */
    suspend fun download(context: Context, u: AppUpdate, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val out = File(dir(context), "ForbiddenStreamsTV-${u.build}.apk")
        dir(context).listFiles()?.forEach { if (it != out) it.delete() }
        Http.client.newCall(Request.Builder().url(u.url).header("User-Agent", "ForbiddenStreamsTV").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed (HTTP ${r.code})")
            val body = r.body ?: throw IOException("Download failed")
            val total = body.contentLength()
            var done = 0L
            var last = -2
            body.byteStream().use { input ->
                FileOutputStream(out).use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != last) { last = pct; withContext(Dispatchers.Main) { progress(pct) } }
                    }
                }
            }
        }
        out
    }

    /** True if Android lets this app open the installer (Android 8+ asks once per app). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Opens the "Install unknown apps" switch for this app. */
    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Opens Android's installer for the downloaded APK. */
    fun install(context: Context, apk: File) {
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        } else {
            Uri.fromFile(apk)
        }
        @Suppress("DEPRECATION")
        val i = Intent(if (Build.VERSION.SDK_INT >= 24) Intent.ACTION_INSTALL_PACKAGE else Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }
}
