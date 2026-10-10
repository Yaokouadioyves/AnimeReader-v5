package com.animereader.update

import org.json.JSONObject

/** A published version: CI build number (= versionCode), its APK and release notes. */
data class Release(val versionCode: Int, val name: String, val apkUrl: String, val notes: String)

/**
 * Reads GitHub's "latest release" JSON. The CI tags every build of main `v5.<build number>`
 * and the build number is also the app's versionCode, so comparing them tells whether the
 * installed app is behind. Pure (org.json only) so it is JVM-unit-testable.
 */
object Releases {

    const val LATEST_API = "https://api.github.com/repos/Yaokouadioyves/AnimeReader-v5/releases/latest"

    /** Null when the JSON has no usable `v5.N` tag or no APK attached. */
    fun parseLatest(json: String): Release? = runCatching {
        val o = JSONObject(json)
        val code = versionCodeOf(o.optString("tag_name")) ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        var apk: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                apk = a.optString("browser_download_url").takeIf { it.startsWith("https://") }
                if (apk != null) break
            }
        }
        Release(code, o.optString("name").ifBlank { "AnimeReader 5.$code" }, apk ?: return null, o.optString("body"))
    }.getOrNull()

    /** "v5.42" -> 42. */
    fun versionCodeOf(tag: String): Int? = Regex("""^v\d+\.(\d+)$""").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    fun isNewer(release: Release, installedVersionCode: Int): Boolean = release.versionCode > installedVersionCode
}
