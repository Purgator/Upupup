package fr.arichard.upupup

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import fr.arichard.upupup.core.Format

/** Lap list for the stopwatch: newest lap on top, with its split from the previous one. */
class LapAdapter : RecyclerView.Adapter<LapAdapter.Holder>() {

    /** Cumulative lap times, oldest first (as stored). */
    private var laps: List<Long> = emptyList()

    fun submit(list: List<Long>) {
        laps = list
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lap, parent, false)
        return Holder(view)
    }

    override fun getItemCount() = laps.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        // Show newest first.
        val index = laps.size - 1 - position
        val cumulative = laps[index]
        val split = cumulative - (if (index > 0) laps[index - 1] else 0)
        holder.number.text = holder.itemView.context.getString(R.string.lap_number, index + 1)
        holder.split.text = Format.stopwatch(split)
        holder.total.text = Format.stopwatch(cumulative)
        holder.itemView.setLongPressMenu {
            val context = holder.itemView.context
            listOf(
                MenuAction(context.getString(R.string.menu_copy_lap)) {
                    context.copyToClipboard(lapsText(context, laps).lines()[index])
                },
                MenuAction(context.getString(R.string.menu_copy_laps)) {
                    context.copyToClipboard(lapsText(context, laps))
                },
            )
        }
    }

    class Holder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.lap_number)
        val split: TextView = view.findViewById(R.id.lap_split)
        val total: TextView = view.findViewById(R.id.lap_total)
    }

    companion object {
        /** One "Lap n  split  total" line per lap, oldest first: ready to paste. */
        fun lapsText(context: android.content.Context, laps: List<Long>): String =
            laps.mapIndexed { index, cumulative ->
                val split = cumulative - (if (index > 0) laps[index - 1] else 0)
                context.getString(R.string.lap_number, index + 1) + "  " +
                    Format.stopwatch(split) + "  " + Format.stopwatch(cumulative)
            }.joinToString("\n")
    }
}
