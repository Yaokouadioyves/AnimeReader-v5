package com.animereader.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {

    private val latest = """
        {"tag_name":"v5.35","name":"AnimeReader 5.35","body":"Voix plus fluide",
         "assets":[{"name":"notes.txt","browser_download_url":"https://github.com/x/notes.txt"},
                   {"name":"AnimeReader-5.35.apk","browser_download_url":"https://github.com/Yaokouadioyves/AnimeReader-v5/releases/download/v5.35/AnimeReader-5.35.apk"}]}
    """.trimIndent()

    @Test fun parsesTheTagAndTheApk() {
        val r = Releases.parseLatest(latest)!!
        assertEquals(35, r.versionCode)
        assertEquals("AnimeReader 5.35", r.name)
        assertTrue(r.apkUrl.endsWith("AnimeReader-5.35.apk"))
        assertEquals("Voix plus fluide", r.notes)
    }

    @Test fun comparesWithTheInstalledBuild() {
        val r = Releases.parseLatest(latest)!!
        assertTrue(Releases.isNewer(r, 33))
        assertFalse(Releases.isNewer(r, 35))
        assertFalse(Releases.isNewer(r, 40))
    }

    @Test fun rejectsUnusableReleases() {
        assertNull(Releases.parseLatest("""{"tag_name":"nightly","assets":[]}"""))
        assertNull(Releases.parseLatest("""{"tag_name":"v5.36","assets":[{"name":"a.zip","browser_download_url":"https://x/a.zip"}]}"""))
        assertNull(Releases.parseLatest("""{"message":"Not Found"}"""))
        assertNull(Releases.parseLatest("not json"))
    }

    @Test fun versionCodeOfTag() {
        assertEquals(42, Releases.versionCodeOf("v5.42"))
        assertNull(Releases.versionCodeOf("5.42"))
        assertNull(Releases.versionCodeOf("v5.x"))
    }
}
