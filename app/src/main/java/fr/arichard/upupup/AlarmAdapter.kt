package fr.arichard.upupup

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import fr.arichard.upupup.core.Alarm
import fr.arichard.upupup.core.Format
import fr.arichard.upupup.core.Mission
import fr.arichard.upupup.databinding.ItemAlarmBinding
import java.util.Locale

class AlarmAdapter(
    private val onClick: (Alarm) -> Unit,
    private val onToggle: (Alarm, Boolean) -> Unit,
    private val onLongClick: (Alarm) -> Unit,
) : RecyclerView.Adapter<AlarmAdapter.Holder>() {

    private var alarms: List<Alarm> = emptyList()

    /** App-wide sound output, shown on cards whose alarm follows the default. */
    var defaultOutput: fr.arichard.upupup.core.Output = fr.arichard.upupup.core.Output.AUTO

    fun submit(list: List<Alarm>) {
        alarms = list
        @Suppress("NotifyDataSetChanged") // lists are tiny; diffing is not worth it
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemAlarmBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = alarms.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(alarms[position])

    inner class Holder(private val binding: ItemAlarmBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(alarm: Alarm) {
            val context = binding.root.context
            binding.alarmTime.text =
                String.format(Locale.ROOT, "%02d:%02d", alarm.hour, alarm.minute)
            binding.alarmTime.alpha = if (alarm.enabled) 1f else 0.4f

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
            val effectiveOutput = if (alarm.output == fr.arichard.upupup.core.Output.DEFAULT) {
                defaultOutput
            } else {
                alarm.output
            }
            binding.outputIcon.visibility = android.view.View.VISIBLE
            binding.outputIcon.setImageResource(
                when (effectiveOutput) {
                    fr.arichard.upupup.core.Output.SPEAKER -> R.drawable.ic_speaker
                    fr.arichard.upupup.core.Output.WIRED -> R.drawable.ic_headphones
                    fr.arichard.upupup.core.Output.BLUETOOTH -> R.drawable.ic_bluetooth
                    else -> R.drawable.ic_sound
                }
            )

            binding.missionIcon.visibility =
                if (alarm.mission == Mission.NONE) android.view.View.GONE
                else android.view.View.VISIBLE
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

            binding.alarmEnabled.setOnCheckedChangeListener(null)
            binding.alarmEnabled.isChecked = alarm.enabled
            binding.alarmEnabled.setOnCheckedChangeListener { _, checked ->
                onToggle(alarm, checked)
            }

            binding.root.setOnClickListener { onClick(alarm) }
            binding.root.setOnLongClickListener { onLongClick(alarm); true }
        }
    }

    companion object {
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
