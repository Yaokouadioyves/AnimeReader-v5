package com.animereader.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.animereader.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * In-app updates from the public GitHub releases: look up the latest build, download its APK
 * into the cache and hand it to the system installer. Every APK is signed with the same key,
 * so Android installs it over the current app and keeps the settings.
 */
class AppUpdater(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** The latest release if it is newer than the installed app, else null (also on errors). */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(Releases.LATEST_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "AnimeReader/${BuildConfig.VERSION_NAME}")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                Releases.parseLatest(resp.body?.string().orEmpty())
            }
        }.getOrNull()?.takeIf { Releases.isNewer(it, BuildConfig.VERSION_CODE) }
    }

    /** Downloads the APK of [release]; [onProgress] gets 0..100 (or -1 when the size is unknown). */
    suspend fun download(release: Release, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the version being installed
        val out = File(dir, "AnimeReader-5.${release.versionCode}.apk")
        val req = Request.Builder().url(release.apkUrl)
            .header("User-Agent", "AnimeReader/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("téléchargement HTTP ${resp.code}")
            val body = resp.body ?: error("téléchargement vide")
            val total = body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -2
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != last) { last = pct; onProgress(pct) }
                    }
                }
            }
        }
        out
    }

    /** Android asks once per app before it may install APKs. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows AnimeReader to install updates. */
    fun openInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Hands the downloaded APK to the system installer (it shows its own confirmation). */
    fun install(apk: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { context.startActivity(intent); true } catch (_: ActivityNotFoundException) { false }
    }
}
