package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.UIHelper.getResourceColor
import dev.kotlinds.libretrokmp.JoypadButton
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Responsive 8-way multi-touch directional pad for MISSA TV 360 Retro Player (GAME-03, GAME-05).
 *
 * Supports cardinal and diagonal directions in a single thumb slide, triggers tactile
 * haptic feedback on sector transitions, calls [performClick] on release for accessibility
 * compliance, and resolves all colors strictly from semantic `missa*` theme attributes.
 */
class MissaRetroDpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onDirectionsChanged: ((Set<Int>) -> Unit)? = null

    private val activeDirections = LinkedHashSet<Int>()

    private val baseFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getResourceColor(R.attr.missaSurfaceElevated, 0.72f)
    }

    private val baseStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 1.5f
        color = context.getResourceColor(R.attr.missaTextPrimary, 0.24f)
    }

    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getResourceColor(R.attr.missaSurfaceCard, 0.88f)
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getResourceColor(R.attr.missaAccentPrimary, 0.85f)
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getResourceColor(R.attr.missaTextPrimary, 0.88f)
    }

    private val activeArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getResourceColor(R.attr.missaOnAccentPrimary, 1f)
    }

    private val arrowPath = Path()

    init {
        isClickable = true
        isFocusable = false
        contentDescription = context.getString(R.string.universe_retro_dpad_desc)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - baseStrokePaint.strokeWidth
        if (radius <= 8f) return

        // Outer circular pad
        canvas.drawCircle(cx, cy, radius, baseFillPaint)
        canvas.drawCircle(cx, cy, radius, baseStrokePaint)

        // Inner cross arms
        val armHalf = radius * 0.28f
        val armReach = radius * 0.86f
        val corner = armHalf * 0.35f

        canvas.drawRoundRect(cx - armHalf, cy - armReach, cx + armHalf, cy + armReach, corner, corner, crossPaint)
        canvas.drawRoundRect(cx - armReach, cy - armHalf, cx + armReach, cy + armHalf, corner, corner, crossPaint)

        // Highlight active arms
        if (JoypadButton.UP in activeDirections) {
            canvas.drawRoundRect(cx - armHalf, cy - armReach, cx + armHalf, cy - armHalf * 0.3f, corner, corner, highlightPaint)
        }
        if (JoypadButton.DOWN in activeDirections) {
            canvas.drawRoundRect(cx - armHalf, cy + armHalf * 0.3f, cx + armHalf, cy + armReach, corner, corner, highlightPaint)
        }
        if (JoypadButton.LEFT in activeDirections) {
            canvas.drawRoundRect(cx - armReach, cy - armHalf, cx - armHalf * 0.3f, cy + armHalf, corner, corner, highlightPaint)
        }
        if (JoypadButton.RIGHT in activeDirections) {
            canvas.drawRoundRect(cx + armHalf * 0.3f, cy - armHalf, cx + armReach, cy + armHalf, corner, corner, highlightPaint)
        }

        // Center pivot dot
        canvas.drawCircle(cx, cy, armHalf * 0.38f, baseStrokePaint)

        // Directional chevrons
        val tipDist = radius * 0.74f
        val baseDist = radius * 0.54f
        val wing = armHalf * 0.48f

        drawTriangle(
            canvas,
            cx, cy - tipDist,
            cx - wing, cy - baseDist,
            cx + wing, cy - baseDist,
            if (JoypadButton.UP in activeDirections) activeArrowPaint else arrowPaint
        )
        drawTriangle(
            canvas,
            cx, cy + tipDist,
            cx - wing, cy + baseDist,
            cx + wing, cy + baseDist,
            if (JoypadButton.DOWN in activeDirections) activeArrowPaint else arrowPaint
        )
        drawTriangle(
            canvas,
            cx - tipDist, cy,
            cx - baseDist, cy - wing,
            cx - baseDist, cy + wing,
            if (JoypadButton.LEFT in activeDirections) activeArrowPaint else arrowPaint
        )
        drawTriangle(
            canvas,
            cx + tipDist, cy,
            cx + baseDist, cy - wing,
            cx + baseDist, cy + wing,
            if (JoypadButton.RIGHT in activeDirections) activeArrowPaint else arrowPaint
        )
    }

    private fun drawTriangle(
        canvas: Canvas,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        x3: Float, y3: Float,
        paint: Paint
    ) {
        arrowPath.reset()
        arrowPath.moveTo(x1, y1)
        arrowPath.lineTo(x2, y2)
        arrowPath.lineTo(x3, y3)
        arrowPath.close()
        canvas.drawPath(arrowPath, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_MOVE -> {
                val computed = computeDirections(event)
                updateActiveDirections(computed)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val upIndex = event.actionIndex
                val computed = computeDirections(event, ignorePointerIndex = upIndex)
                updateActiveDirections(computed)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateActiveDirections(emptySet())
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                updateActiveDirections(emptySet())
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun computeDirections(event: MotionEvent, ignorePointerIndex: Int = -1): Set<Int> {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f
        val deadZone = radius * 0.17f
        val result = LinkedHashSet<Int>()

        for (i in 0 until event.pointerCount) {
            if (i == ignorePointerIndex) continue
            val dx = event.getX(i) - cx
            val dy = event.getY(i) - cy
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (dist < deadZone) continue

            // Angle in degrees [-180, 180], 0 = RIGHT, 90 = DOWN, -90 = UP
            val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
            // 8 sectors of 45 degrees each (22.5 deg half-sector) -> cardinals + diagonals!
            when {
                deg >= -22.5 && deg < 22.5 -> result.add(JoypadButton.RIGHT)
                deg >= 22.5 && deg < 67.5 -> {
                    result.add(JoypadButton.RIGHT)
                    result.add(JoypadButton.DOWN)
                }
                deg >= 67.5 && deg < 112.5 -> result.add(JoypadButton.DOWN)
                deg >= 112.5 && deg < 157.5 -> {
                    result.add(JoypadButton.DOWN)
                    result.add(JoypadButton.LEFT)
                }
                deg >= -67.5 && deg < -22.5 -> {
                    result.add(JoypadButton.UP)
                    result.add(JoypadButton.RIGHT)
                }
                deg >= -112.5 && deg < -67.5 -> result.add(JoypadButton.UP)
                deg >= -157.5 && deg < -112.5 -> {
                    result.add(JoypadButton.UP)
                    result.add(JoypadButton.LEFT)
                }
                else -> result.add(JoypadButton.LEFT)
            }
        }
        return result
    }

    private fun updateActiveDirections(next: Set<Int>) {
        if (activeDirections == next) return
        val gainedNewPress = next.any { it !in activeDirections }
        activeDirections.clear()
        activeDirections.addAll(next)
        if (gainedNewPress) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        onDirectionsChanged?.invoke(activeDirections.toSet())
        invalidate()
    }
}
