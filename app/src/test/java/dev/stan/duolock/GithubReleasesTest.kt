package dev.stan.duolock

import dev.stan.duolock.updates.GithubReleases
import dev.stan.duolock.updates.ReleaseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GithubReleasesTest {

    // Shaped like the real releases/latest payload, trimmed to the fields we
    // read plus a couple we deliberately ignore.
    private val payload = """
        {
          "url": "https://api.github.com/repos/Staninna/duolock/releases/1",
          "tag_name": "v1.0.1",
          "name": "v1.0.1",
          "draft": false,
          "prerelease": false,
          "body": "## What's Changed\n* Clearer lock screen\n",
          "author": {"login": "Staninna"},
          "assets": [
            {"name": "notes.txt", "size": 12, "browser_download_url": "https://example.com/notes.txt"},
            {"name": "duolock-v1.0.1.apk", "size": 9437184,
             "browser_download_url": "https://github.com/Staninna/duolock/releases/download/v1.0.1/duolock-v1.0.1.apk"}
          ]
        }
    """.trimIndent()

    @Test
    fun `reads the tag, notes and apk asset`() {
        val release = GithubReleases.parse(payload)
        assertEquals("v1.0.1", release.tag)
        assertEquals(9437184L, release.sizeBytes)
        assertEquals(
            "https://github.com/Staninna/duolock/releases/download/v1.0.1/duolock-v1.0.1.apk",
            release.apkUrl,
        )
        assertEquals("## What's Changed\n* Clearer lock screen", release.notes)
    }

    @Test
    fun `a release with no apk is an error, not a silent no-op`() {
        val noApk = """{"tag_name": "v1.0.1", "body": "", "assets": []}"""
        val e = assertThrows(ReleaseException::class.java) { GithubReleases.parse(noApk) }
        assertEquals("Release v1.0.1 has no APK attached.", e.message)
    }

    @Test
    fun `garbage is an error`() {
        assertThrows(ReleaseException::class.java) { GithubReleases.parse("not json") }
    }
}
