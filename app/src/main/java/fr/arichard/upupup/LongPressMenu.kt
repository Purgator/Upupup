package fr.arichard.upupup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu

/** One entry of a long-press menu. */
class MenuAction(val title: CharSequence, val run: () -> Unit)

/**
 * Long-pressing this view pops a small menu of shortcuts next to it. [actions] is asked
 * each time, so the menu always matches the current state; an empty list means there is
 * nothing to offer right now and the long press is left alone.
 */
fun View.setLongPressMenu(actions: () -> List<MenuAction>) {
    setOnLongClickListener { anchor ->
        val list = actions()
        if (list.isEmpty()) return@setOnLongClickListener false
        PopupMenu(anchor.context, anchor).apply {
            list.forEachIndexed { index, action -> menu.add(0, index, index, action.title) }
            setOnMenuItemClickListener { item ->
                list[item.itemId].run()
                true
            }
        }.show()
        true
    }
}

/** Copies [text] and confirms it, unless the system already shows its own confirmation. */
fun Context.copyToClipboard(text: String) {
    getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
    if (Build.VERSION.SDK_INT < 33) {
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
