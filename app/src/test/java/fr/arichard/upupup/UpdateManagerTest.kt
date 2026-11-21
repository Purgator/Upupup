package fr.arichard.upupup

import fr.arichard.upupup.core.UpdateManager
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun `newer versions are detected`() {
        assertTrue(UpdateManager.isNewer("1.1", "1.0"))
        assertTrue(UpdateManager.isNewer("1.10", "1.9"))
        assertTrue(UpdateManager.isNewer("2.0", "1.99"))
        assertTrue(UpdateManager.isNewer("1.0.1", "1.0"))
    }

    @Test
    fun `equal or older versions are not newer`() {
        assertFalse(UpdateManager.isNewer("1.0", "1.0"))
        assertFalse(UpdateManager.isNewer("1.0", "1.1"))
        assertFalse(UpdateManager.isNewer("0.9", "1.0"))
    }

    @Test
    fun `garbage version parts count as zero`() {
        assertTrue(UpdateManager.isNewer("1.1", "1.beta"))
        assertFalse(UpdateManager.isNewer("abc", "1.0"))
    }

    @Test
    fun `suffixed segments use their first digit run only`() {
        // "7-rc1" must parse as 7, not as the digit-concatenation 71.
        assertFalse(UpdateManager.isNewer("1.7-rc1", "1.8"))
        assertTrue(UpdateManager.isNewer("1.8", "1.7-beta2"))
        assertTrue(UpdateManager.isNewer("v1.8", "v1.7"))
    }

    private fun newestTag(json: String): String? =
        UpdateManager.newestRelease(JSONArray(json))?.getString("tag_name")

    @Test
    fun `beta channel takes the highest version, pre-release or not`() {
        val releases = """[
            {"tag_name": "v1.9", "prerelease": true},
            {"tag_name": "v1.10", "prerelease": true},
            {"tag_name": "v1.8", "prerelease": false}
        ]"""
        assertEquals("v1.10", newestTag(releases))
    }

    @Test
    fun `beta channel still gets a stable release newer than every pre-release`() {
        val releases = """[
            {"tag_name": "v2.0", "prerelease": false},
            {"tag_name": "v1.9", "prerelease": true}
        ]"""
        assertEquals("v2.0", newestTag(releases))
    }

    @Test
    fun `beta channel ignores drafts and copes with an empty list`() {
        val releases = """[
            {"tag_name": "v3.0", "draft": true},
            {"tag_name": "v1.9", "prerelease": true}
        ]"""
        assertEquals("v1.9", newestTag(releases))
        assertNull(newestTag("[]"))
    }
}
