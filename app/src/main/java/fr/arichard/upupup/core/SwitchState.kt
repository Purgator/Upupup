package fr.arichard.upupup.core

/**
 * The three positions of an alarm's switch. [SKIP] is the half-way one: the alarm stays
 * enabled but its next ring is cancelled, and the switch slides back to [ON] by itself
 * once that ring's time has passed.
 */
enum class SwitchState {
    OFF, SKIP, ON;

    /**
     * Where one tap leads: ON → SKIP → OFF → ON. An alarm with nothing to skip
     * ([canSkip] false: a one-shot alarm) goes straight from ON to OFF.
     */
    fun afterTap(canSkip: Boolean): SwitchState = when (this) {
        ON -> if (canSkip) SKIP else OFF
        SKIP -> OFF
        OFF -> ON
    }

    companion object {
        /** [skipUntil] is the trigger time of the ring dismissed in advance, or 0. */
        fun of(alarm: Alarm, now: Long, skipUntil: Long): SwitchState = when {
            !alarm.enabled -> OFF
            alarm.days.isNotEmpty() && skipUntil > now -> SKIP
            else -> ON
        }
    }
}
