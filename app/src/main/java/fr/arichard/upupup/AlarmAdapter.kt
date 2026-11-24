package fr.arichard.upupup

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import fr.arichard.upupup.core.Alarm
import fr.arichard.upupup.core.Format
import fr.arichard.upupup.core.Mission
import fr.arichard.upupup.core.Output
import fr.arichard.upupup.core.SwitchState
import fr.arichard.upupup.databinding.ItemAlarmBinding
import java.util.Locale

class AlarmAdapter(
    private val onClick: (Alarm) -> Unit,
    /** The switch was tapped: the owner moves the alarm to its next state. */
    private val onSwitch: (Alarm) -> Unit,
    /** Shortcuts offered when the card or its switch is long-pressed. */
    private val menu: (Alarm) -> List<MenuAction>,
    private val onUpcomingAction: (Alarm, Upcoming) -> Unit,
) : RecyclerView.Adapter<AlarmAdapter.Holder>() {

    /**
     * What a card says about its alarm's next ring; [UpcomingState.at] is the snooze end
     * for SNOOZED and the time of the cancelled ring for SKIPPED.
     */
    enum class Upcoming { SOON, SNOOZED, SKIPPED }
    data class UpcomingState(val kind: Upcoming, val at: Long = 0)

    private var alarms: List<Alarm> = emptyList()

    /** Per alarm id; alarms with nothing to say about their next ring are absent. */
    var upcoming: Map<Long, UpcomingState> = emptyMap()
        private set

    /** The alarm that rings next, whose card gets an accent border; null when all are off. */
    var nextId: Long? = null
        private set

    /** Switch position last drawn per alarm, so a change slides instead of jumping. */
    private val shownStates = mutableMapOf<Long, SwitchState>()

    /** App-wide sound output, shown on cards whose alarm follows the default. */
    var defaultOutput: Output = Output.AUTO

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<Alarm>, upcoming: Map<Long, UpcomingState>, nextId: Long?) {
        alarms = list
        this.upcoming = upcoming
        this.nextId = nextId
        shownStates.keys.retainAll(list.map { it.id }.toSet())
        @Suppress("NotifyDataSetChanged") // lists are tiny; diffing is not worth it
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemAlarmBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = alarms.size

    override fun getItemId(position: Int) = alarms[position].id

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(alarms[position])

    private fun switchState(alarm: Alarm): SwitchState = when {
        !alarm.enabled -> SwitchState.OFF
        upcoming[alarm.id]?.kind == Upcoming.SKIPPED -> SwitchState.SKIP
        else -> SwitchState.ON
    }

    inner class Holder(private val binding: ItemAlarmBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /** The card's own outline, put back when it is not the next one to ring. */
        private val plainStrokeColor = binding.root.strokeColorStateList
        private val plainStrokeWidth = binding.root.strokeWidth

        fun bind(alarm: Alarm) {
            val context = binding.root.context
            if (alarm.id == nextId) {
                binding.root.strokeColor = MaterialColors.getColor(
                    binding.root, com.google.android.material.R.attr.colorPrimary
                )
                binding.root.strokeWidth =
                    (context.resources.displayMetrics.density * NEXT_STROKE_DP).toInt()
            } else {
                if (plainStrokeColor != null) binding.root.setStrokeColor(plainStrokeColor)
                binding.root.strokeWidth = plainStrokeWidth
            }
            val switchState = switchState(alarm)
            binding.alarmTime.text =
                String.format(Locale.ROOT, "%02d:%02d", alarm.hour, alarm.minute)
            val timeAlpha = when (switchState) {
                SwitchState.ON -> 1f
                SwitchState.SKIP -> 0.65f
                SwitchState.OFF -> 0.4f
            }

            val parts = mutableListOf(Format.days(context, alarm.days))
            if (alarm.label.isNotBlank()) parts.add(alarm.label)
            when (alarm.mission) {
                Mission.SHAKE -> parts.add(
                    context.getString(R.string.mission_shake_desc, alarm.missionLevel)
                )
                Mission.MATH -> parts.add(
                    context.getString(
                        R.string.mission_math_desc, mathLevelName(context, alarm.missionLevel)
                    )
                )
                Mission.TYPING -> parts.add(
                    context.getString(R.string.mission_typing_desc, alarm.missionLevel)
                )
                Mission.STEPS -> parts.add(
                    context.getString(R.string.mission_steps_desc, alarm.missionLevel)
                )
                Mission.MEMORY -> parts.add(
                    context.getString(
                        R.string.mission_memory_desc, mathLevelName(context, alarm.missionLevel)
                    )
                )
                Mission.NONE -> Unit
            }
            binding.alarmDetails.text = parts.joinToString(" · ")

            // Always show where this alarm will ring: its override, or the app default.
            val effectiveOutput =
                if (alarm.output == Output.DEFAULT) defaultOutput else alarm.output
            binding.outputIcon.visibility = View.VISIBLE
            binding.outputIcon.setImageResource(
                when (effectiveOutput) {
                    Output.SPEAKER -> R.drawable.ic_speaker
                    Output.WIRED -> R.drawable.ic_headphones
                    Output.BLUETOOTH -> R.drawable.ic_bluetooth
                    else -> R.drawable.ic_sound
                }
            )

            binding.missionIcon.visibility =
                if (alarm.mission == Mission.NONE) View.GONE else View.VISIBLE
            binding.missionIcon.setImageResource(
                when (alarm.mission) {
                    Mission.SHAKE -> R.drawable.ic_mission_shake
                    Mission.MATH -> R.drawable.ic_mission_math
                    Mission.TYPING -> R.drawable.ic_mission_typing
                    Mission.STEPS -> R.drawable.ic_mission_steps
                    Mission.MEMORY -> R.drawable.ic_mission_memory
                    Mission.NONE -> R.drawable.ic_mission_none
                }
            )

            // Start from the position this alarm was last drawn at, then slide to the
            // new one: a tap, or a skipped ring whose time just passed, is seen moving.
            val shown = shownStates[alarm.id]
            if (shown != null && shown != switchState) {
                binding.alarmEnabled.setState(shown, animate = false)
                binding.alarmEnabled.setState(switchState, animate = true)
                binding.alarmTime.animate().alpha(timeAlpha).setDuration(260).start()
            } else {
                binding.alarmEnabled.setState(switchState, animate = false)
                binding.alarmTime.animate().cancel()
                binding.alarmTime.alpha = timeAlpha
            }
            shownStates[alarm.id] = switchState
            binding.alarmEnabled.contentDescription = binding.alarmTime.text
            binding.alarmEnabled.setOnClickListener { onSwitch(alarm) }
            binding.alarmEnabled.setLongPressMenu { menu(alarm) }

            val state = upcoming[alarm.id]
            binding.upcomingRow.visibility = if (state == null) View.GONE else View.VISIBLE
            if (state != null) {
                val status = when (state.kind) {
                    Upcoming.SOON -> null
                    Upcoming.SNOOZED -> context.getString(
                        R.string.snoozed_until, Format.time(context, state.at)
                    )
                    Upcoming.SKIPPED -> context.getString(
                        R.string.skip_status, Format.dayTime(context, state.at)
                    )
                }
                binding.upcomingStatus.text = status
                binding.upcomingStatus.visibility = if (status == null) View.GONE else View.VISIBLE
                binding.upcomingButton.setText(
                    if (state.kind == Upcoming.SKIPPED) R.string.restore_ring
                    else R.string.skip_this_ring
                )
                binding.upcomingButton.setOnClickListener { onUpcomingAction(alarm, state.kind) }
            }

            binding.root.setOnClickListener { onClick(alarm) }
            binding.root.setLongPressMenu { menu(alarm) }
        }
    }

    companion object {
        private const val NEXT_STROKE_DP = 2


        fun mathLevelName(context: android.content.Context, level: Int): String =
            context.getString(
                when (level) {
                    1 -> R.string.math_easy
                    2 -> R.string.math_medium
                    else -> R.string.math_hard
                }
            )
    }
}
