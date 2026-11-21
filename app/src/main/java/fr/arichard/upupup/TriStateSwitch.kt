package fr.arichard.upupup

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import com.google.android.material.color.MaterialColors
import fr.arichard.upupup.core.SwitchState
import kotlin.math.abs

/**
 * An alarm switch with a resting position in the middle: off (left), "skip the next
 * ring" (centre, with a skip glyph on the thumb) and on (right). It only displays the
 * state it is given; taps are reported through the regular click listener and the owner
 * decides what the next state is.
 */
class TriStateSwitch @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var state: SwitchState = SwitchState.OFF
        private set

    /** Thumb position: 0 = off, 0.5 = skip, 1 = on. Animated between states. */
    private var position = 0f
    private var animator: ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val trackWidth = 56 * density
    private val trackHeight = 32 * density

    private val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val onPrimary =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary)
    private val offTrack = MaterialColors.getColor(
        this, com.google.android.material.R.attr.colorSurfaceContainerHighest
    )
    private val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val track = RectF()
    private val glyph = Path()

    init {
        isClickable = true
        isFocusable = true
        // The ripple spreads from the thumb area, like a stock switch.
        val ripple = android.util.TypedValue()
        context.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, ripple, true
        )
        setBackgroundResource(ripple.resourceId)
    }

    /** Shows [newState], sliding the thumb there when [animate] is set. */
    fun setState(newState: SwitchState, animate: Boolean) {
        val target = positionOf(newState)
        state = newState
        ViewCompat.setStateDescription(this, describe(newState))
        animator?.cancel()
        if (!animate || !isLaidOut) {
            position = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(position, target).apply {
            duration = 260
            interpolator = OvershootInterpolator(1.4f)
            addUpdateListener {
                position = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun performClick(): Boolean {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        return super.performClick()
    }

    private fun positionOf(state: SwitchState) = when (state) {
        SwitchState.OFF -> 0f
        SwitchState.SKIP -> 0.5f
        SwitchState.ON -> 1f
    }

    private fun describe(state: SwitchState): String = context.getString(
        when (state) {
            SwitchState.OFF -> R.string.off
            SwitchState.SKIP -> R.string.switch_state_skip
            SwitchState.ON -> R.string.on
        }
    )

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Padded up to a comfortable 48dp-high touch target around the 32dp track.
        val width = (trackWidth + 8 * density).toInt()
        val height = (48 * density).toInt()
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        // The overshoot may briefly push past the ends; colours stay within range.
        val p = position.coerceIn(0f, 1f)
        val left = (width - trackWidth) / 2f
        val top = (height - trackHeight) / 2f
        track.set(left, top, left + trackWidth, top + trackHeight)
        val radius = trackHeight / 2f

        fill.color = ColorUtils.blendARGB(offTrack, primary, p)
        canvas.drawRoundRect(track, radius, radius, fill)
        // Off keeps the Material outline; it fades away as the track fills with colour.
        stroke.color = outline
        stroke.alpha = (255 * (1 - p)).toInt()
        val inset = stroke.strokeWidth / 2f
        track.inset(inset, inset)
        canvas.drawRoundRect(track, radius - inset, radius - inset, stroke)

        // Thumb: small and muted when off, large and bright when on.
        val travel = trackWidth - trackHeight
        val cx = left + radius + travel * position
        val cy = height / 2f
        val thumbRadius = (8 + 4 * p) * density
        fill.color = ColorUtils.blendARGB(outline, onPrimary, p)
        canvas.drawCircle(cx, cy, thumbRadius, fill)

        // Skip glyph (▶|), only visible around the middle position.
        val glyphAlpha = (1 - abs(p - 0.5f) * 4).coerceIn(0f, 1f)
        if (glyphAlpha > 0f) {
            val u = density
            glyph.rewind()
            glyph.moveTo(cx - 4.2f * u, cy - 4.2f * u)
            glyph.lineTo(cx + 1.6f * u, cy)
            glyph.lineTo(cx - 4.2f * u, cy + 4.2f * u)
            glyph.close()
            glyph.addRect(
                cx + 2.6f * u, cy - 4.2f * u, cx + 4.4f * u, cy + 4.2f * u, Path.Direction.CW
            )
            fill.color = primary
            fill.alpha = (255 * glyphAlpha).toInt()
            canvas.drawPath(glyph, fill)
            fill.alpha = 255
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        position = positionOf(state)
        super.onDetachedFromWindow()
    }
}
