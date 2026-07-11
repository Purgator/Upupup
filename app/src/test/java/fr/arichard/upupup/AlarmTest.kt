package fr.arichard.upupup

import fr.arichard.upupup.core.Alarm
import fr.arichard.upupup.core.Mission
import fr.arichard.upupup.core.Output
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AlarmTest {

    private fun at(dayOfWeek: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            // Move to a known Monday then to the wanted day, to keep the test date-independent.
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `one-shot alarm rings today when time is ahead`() {
        val now = at(Calendar.WEDNESDAY, 6, 0)
        val alarm = Alarm(id = 1, hour = 7, minute = 30)
        val next = alarm.nextTrigger(now)
        assertEquals(now + 90 * 60_000, next)
    }

    @Test
    fun `one-shot alarm rings tomorrow when time has passed`() {
        val now = at(Calendar.WEDNESDAY, 8, 0)
        val alarm = Alarm(id = 1, hour = 7, minute = 30)
        val next = alarm.nextTrigger(now)
        assertEquals(now + (24 * 60 - 30) * 60_000L, next)
    }

    @Test
    fun `repeating alarm skips to selected weekday`() {
        val now = at(Calendar.WEDNESDAY, 8, 0)
        val alarm = Alarm(id = 1, hour = 7, minute = 30, days = setOf(Calendar.FRIDAY))
        val next = alarm.nextTrigger(now)
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(Calendar.FRIDAY, cal.get(Calendar.DAY_OF_WEEK))
        assertEquals(7, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
        assertTrue(next > now)
    }

    @Test
    fun `repeating alarm same day later time rings today`() {
        val now = at(Calendar.WEDNESDAY, 6, 0)
        val alarm = Alarm(id = 1, hour = 7, minute = 30, days = setOf(Calendar.WEDNESDAY))
        assertEquals(now + 90 * 60_000, alarm.nextTrigger(now))
    }

    @Test
    fun `repeating alarm same day passed time rings next week`() {
        val now = at(Calendar.WEDNESDAY, 8, 0)
        val alarm = Alarm(id = 1, hour = 7, minute = 30, days = setOf(Calendar.WEDNESDAY))
        val next = alarm.nextTrigger(now)
        assertTrue(next - now in (6 * 24 * 60 * 60_000L)..(7 * 24 * 60 * 60_000L))
    }

    @Test
    fun `json round-trip preserves all fields`() {
        val alarm = Alarm(
            id = 42, hour = 6, minute = 15,
            days = setOf(Calendar.MONDAY, Calendar.SUNDAY),
            label = "Gym", enabled = false, soundUri = "content://media/alarm/7",
            volume = 55, rampUp = false, vibrate = false,
            snoozeMinutes = 10, maxSnoozes = 3,
            mission = Mission.MATH, missionLevel = 2, output = Output.BLUETOOTH,
        )
        assertEquals(alarm, Alarm.fromJson(alarm.toJson()))
    }

    @Test
    fun `json defaults survive missing fields`() {
        val alarm = Alarm.fromJson(org.json.JSONObject("""{"id":1,"hour":7,"minute":0}"""))
        assertEquals(Mission.NONE, alarm.mission)
        assertEquals(Output.AUTO, alarm.output)
        assertTrue(alarm.enabled)
    }
}
