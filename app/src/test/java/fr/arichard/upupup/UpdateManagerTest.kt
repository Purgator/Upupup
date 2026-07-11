package fr.arichard.upupup

import fr.arichard.upupup.core.UpdateManager
import org.junit.Assert.assertFalse
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
}
