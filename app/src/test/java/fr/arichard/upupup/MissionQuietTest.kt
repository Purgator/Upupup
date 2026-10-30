package fr.arichard.upupup

import fr.arichard.upupup.core.MissionQuiet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionQuietTest {

    @Test
    fun `initial state is ringing with no lapses and the longest window`() {
        val quiet = MissionQuiet()
        assertFalse(quiet.isQuiet)
        assertEquals(0, quiet.lapses)
        assertEquals(30_000L, quiet.windowMs)
    }

    @Test
    fun `first activity transitions into quiet`() {
        val quiet = MissionQuiet()
        assertTrue(quiet.onActivity())
        assertTrue(quiet.isQuiet)
        assertEquals(0, quiet.lapses)
    }

    @Test
    fun `duplicate activity while quiet is not a transition`() {
        val quiet = MissionQuiet()
        assertTrue(quiet.onActivity())
        assertFalse(quiet.onActivity())
        assertFalse(quiet.onActivity())
        assertTrue(quiet.isQuiet)
    }

    @Test
    fun `lapse while quiet transitions out of quiet and counts`() {
        val quiet = MissionQuiet()
        quiet.onActivity()
        assertTrue(quiet.onLapse())
        assertFalse(quiet.isQuiet)
        assertEquals(1, quiet.lapses)
    }

    @Test
    fun `lapse when already ringing changes nothing`() {
        val quiet = MissionQuiet()
        assertFalse(quiet.onLapse())
        assertFalse(quiet.isQuiet)
        assertEquals(0, quiet.lapses)
        assertEquals(30_000L, quiet.windowMs)
    }

    @Test
    fun `second lapse in a row is ignored`() {
        val quiet = MissionQuiet()
        quiet.onActivity()
        assertTrue(quiet.onLapse())
        assertFalse(quiet.onLapse())
        assertEquals(1, quiet.lapses)
    }

    @Test
    fun `activity after a lapse transitions into quiet again`() {
        val quiet = MissionQuiet()
        quiet.onActivity()
        quiet.onLapse()
        assertTrue(quiet.onActivity())
        assertTrue(quiet.isQuiet)
    }

    @Test
    fun `window decreases with each lapse down to the floor`() {
        val quiet = MissionQuiet()
        val seen = mutableListOf(quiet.windowMs)
        repeat(3) {
            quiet.onActivity()
            quiet.onLapse()
            seen += quiet.windowMs
        }
        assertEquals(listOf(30_000L, 20_000L, 15_000L, 10_000L), seen)
    }

    @Test
    fun `window stays at the 10 second floor after many lapses`() {
        val quiet = MissionQuiet()
        repeat(20) {
            quiet.onActivity()
            quiet.onLapse()
            assertTrue(quiet.windowMs >= 10_000L)
        }
        assertEquals(20, quiet.lapses)
        assertEquals(10_000L, quiet.windowMs)
    }

    @Test
    fun `activity never resets the lapse count or the window`() {
        val quiet = MissionQuiet()
        quiet.onActivity()
        quiet.onLapse()
        quiet.onActivity()
        quiet.onActivity()
        assertEquals(1, quiet.lapses)
        assertEquals(20_000L, quiet.windowMs)
        quiet.onLapse()
        quiet.onActivity()
        assertEquals(2, quiet.lapses)
        assertEquals(15_000L, quiet.windowMs)
    }

    @Test
    fun `custom windows are used in order and the last one is the floor`() {
        val quiet = MissionQuiet(longArrayOf(500L, 200L))
        assertEquals(500L, quiet.windowMs)
        quiet.onActivity()
        quiet.onLapse()
        assertEquals(200L, quiet.windowMs)
        quiet.onActivity()
        quiet.onLapse()
        assertEquals(200L, quiet.windowMs)
    }

    @Test
    fun `single custom window is constant`() {
        val quiet = MissionQuiet(longArrayOf(42L))
        repeat(3) {
            quiet.onActivity()
            quiet.onLapse()
            assertEquals(42L, quiet.windowMs)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty custom windows are rejected`() {
        MissionQuiet(longArrayOf())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero custom window is rejected`() {
        MissionQuiet(longArrayOf(100L, 0L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative custom window is rejected`() {
        MissionQuiet(longArrayOf(-1L))
    }

    @Test
    fun `mutating the caller array after construction does not affect the policy`() {
        val windows = longArrayOf(300L, 100L)
        val quiet = MissionQuiet(windows)
        windows[0] = 1L
        windows[1] = -5L
        assertEquals(300L, quiet.windowMs)
        quiet.onActivity()
        quiet.onLapse()
        assertEquals(100L, quiet.windowMs)
    }

    @Test
    fun `default windows constant is 30, 20, 15 and 10 seconds`() {
        assertEquals(
            listOf(30_000L, 20_000L, 15_000L, 10_000L),
            MissionQuiet.DEFAULT_WINDOWS_MS.toList(),
        )
    }

    @Test
    fun `vibration follows wanted when quieting is disabled`() {
        assertTrue(MissionQuiet.shouldVibrate(wanted = true, quiet = null))
        assertFalse(MissionQuiet.shouldVibrate(wanted = false, quiet = null))
    }

    @Test
    fun `vibration is enabled before mission activity`() {
        assertTrue(MissionQuiet.shouldVibrate(wanted = true, quiet = MissionQuiet()))
    }

    @Test
    fun `vibration pauses when mission activity starts`() {
        val quiet = MissionQuiet()
        assertTrue(quiet.onActivity())
        assertFalse(MissionQuiet.shouldVibrate(wanted = true, quiet = quiet))
    }

    @Test
    fun `duplicate activity keeps vibration paused`() {
        val quiet = MissionQuiet()
        quiet.onActivity()
        assertFalse(quiet.onActivity())
        assertFalse(MissionQuiet.shouldVibrate(wanted = true, quiet = quiet))
    }

    @Test
    fun `vibration resumes when the quiet lease lapses`() {
        val quiet = MissionQuiet()
        quiet.onActivity()
        assertTrue(quiet.onLapse())
        assertTrue(MissionQuiet.shouldVibrate(wanted = true, quiet = quiet))
    }

    @Test
    fun `vibration stays in sync over several quiet leases`() {
        val quiet = MissionQuiet()
        repeat(5) {
            assertTrue(quiet.onActivity())
            assertFalse(MissionQuiet.shouldVibrate(wanted = true, quiet = quiet))
            assertTrue(quiet.onLapse())
            assertTrue(MissionQuiet.shouldVibrate(wanted = true, quiet = quiet))
        }
        assertEquals(10_000L, quiet.windowMs)
    }

    @Test
    fun `vibration remains off when the current alarm does not want it`() {
        val quiet = MissionQuiet()
        assertFalse(MissionQuiet.shouldVibrate(wanted = false, quiet = quiet))
        quiet.onActivity()
        assertFalse(MissionQuiet.shouldVibrate(wanted = false, quiet = quiet))
        quiet.onLapse()
        assertFalse(MissionQuiet.shouldVibrate(wanted = false, quiet = quiet))
    }

    @Test
    fun `ignored lapse while ringing keeps vibration enabled`() {
        val quiet = MissionQuiet()
        assertFalse(quiet.onLapse())
        assertTrue(MissionQuiet.shouldVibrate(wanted = true, quiet = quiet))
    }
}
