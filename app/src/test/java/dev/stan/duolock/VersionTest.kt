package dev.stan.duolock

import dev.stan.duolock.updates.Version
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {

    @Test
    fun `a higher patch is newer`() {
        assertTrue(Version.isNewer("1.0.1", "1.0.0"))
        assertFalse(Version.isNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun `the same version is not newer`() {
        assertFalse(Version.isNewer("1.0.0", "1.0.0"))
    }

    @Test
    fun `the tag's v prefix is ignored`() {
        assertTrue(Version.isNewer("v1.2.0", "1.1.9"))
        assertFalse(Version.isNewer("v1.0.0", "1.0.0"))
    }

    @Test
    fun `ragged segment counts compare by value`() {
        assertTrue(Version.isNewer("1.1", "1.0.9"))
        assertFalse(Version.isNewer("1.0", "1.0.0"))
        assertTrue(Version.isNewer("1.0.0.1", "1.0.0"))
    }

    @Test
    fun `double digit segments are not compared as text`() {
        assertTrue(Version.isNewer("1.10.0", "1.9.0"))
    }

    @Test
    fun `junk is never newer`() {
        assertFalse(Version.isNewer("nightly", "1.0.0"))
        assertFalse(Version.isNewer("1.0.0", "nightly"))
        assertFalse(Version.isNewer("1.0.0-rc1", "1.0.0"))
        assertFalse(Version.isNewer("", "1.0.0"))
    }
}
