package fr.arichard.upupup

import fr.arichard.upupup.core.Alarm
import fr.arichard.upupup.core.SwitchState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class SwitchStateTest {

    private val now = 1_000_000L
    private val repeating = Alarm(id = 1, hour = 7, minute = 30, days = setOf(Calendar.MONDAY))
    private val oneShot = Alarm(id = 2, hour = 7, minute = 30)

    @Test
    fun `repeating alarm cycles on, skip, off, on`() {
        var state = SwitchState.ON
        val seen = (1..3).map {
            state = state.afterTap(canSkip = true)
            state
        }
        assertEquals(listOf(SwitchState.SKIP, SwitchState.OFF, SwitchState.ON), seen)
    }

    @Test
    fun `one-shot alarm has nothing to skip and goes straight off`() {
        assertEquals(SwitchState.OFF, SwitchState.ON.afterTap(canSkip = false))
        assertEquals(SwitchState.ON, SwitchState.OFF.afterTap(canSkip = false))
    }

    @Test
    fun `switch sits half-way only while the skipped ring is still ahead`() {
        val skipped = now + 60_000
        assertEquals(SwitchState.ON, SwitchState.of(repeating, now, skipUntil = 0))
        assertEquals(SwitchState.SKIP, SwitchState.of(repeating, now, skipUntil = skipped))
        // The skipped time has come: back on by itself, nothing to clean up.
        assertEquals(SwitchState.ON, SwitchState.of(repeating, skipped, skipUntil = skipped))
        assertEquals(SwitchState.ON, SwitchState.of(repeating, skipped + 1, skipUntil = skipped))
    }

    @Test
    fun `disabled alarm is off whatever was skipped, one-shot is never half-way`() {
        val off = repeating.copy(enabled = false)
        assertEquals(SwitchState.OFF, SwitchState.of(off, now, skipUntil = now + 60_000))
        assertEquals(SwitchState.ON, SwitchState.of(oneShot, now, skipUntil = now + 60_000))
    }

    @Test
    fun `skipping a snoozed ring leaves every regular occurrence in place`() {
        // Snoozed at 07:32 until 07:35: cancelling that snooze skips "until" 07:35, and
        // the next ring is the regular one, exactly as if nothing had been skipped.
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.WEDNESDAY)
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 32)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val at = cal.timeInMillis
        val daily = Alarm(
            id = 3, hour = 7, minute = 30,
            days = setOf(Calendar.WEDNESDAY, Calendar.THURSDAY)
        )
        val snoozeEnd = at + 3 * 60_000
        assertEquals(daily.nextTrigger(at), daily.nextRing(at, skipUntil = snoozeEnd))
        assertEquals(SwitchState.SKIP, SwitchState.of(daily, at, skipUntil = snoozeEnd))
    }
}
