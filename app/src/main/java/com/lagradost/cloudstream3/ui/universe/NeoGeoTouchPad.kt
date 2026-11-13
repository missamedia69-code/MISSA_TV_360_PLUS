package com.lagradost.cloudstream3.ui.universe

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Manette tactile Neo Geo (téléphone et tablette), dessinée par-dessus le jeu.
 *
 *  - à gauche, une croix à 8 directions (le pouce peut glisser d'une direction à l'autre) ;
 *  - à droite, les quatre boutons A B C D en arc, comme sur la manette d'origine ;
 *  - en haut, PIÈCE et START.
 *
 * Plusieurs doigts à la fois (direction + deux boutons). Chaque changement est envoyé par
 * [onKey] avec les codes de touche Android de manette, que LibretroDroid transmet au cœur :
 * A = BUTTON_B, B = BUTTON_A, C = BUTTON_Y, D = BUTTON_X (correspondance de Geolith).
 */
@SuppressLint("ViewConstructor")
class NeoGeoTouchPad(
    context: Context,
    private val onKey: (keyCode: Int, pressed: Boolean) -> Unit
) : View(context) {

    private data class Zone(val label: String, val keyCode: Int, var cx: Float = 0f, var cy: Float = 0f, var r: Float = 0f)

    private val density = resources.displayMetrics.density
    private val buttons = listOf(
        Zone("A", KeyEvent.KEYCODE_BUTTON_B),
        Zone("B", KeyEvent.KEYCODE_BUTTON_A),
        Zone("C", KeyEvent.KEYCODE_BUTTON_Y),
        Zone("D", KeyEvent.KEYCODE_BUTTON_X)
    )
    private val coin = Zone("PIÈCE", KeyEvent.KEYCODE_BUTTON_SELECT)
    private val start = Zone("START", KeyEvent.KEYCODE_BUTTON_START)
    private val small = listOf(coin, start)

    private var padCx = 0f
    private var padCy = 0f
    private var padR = 0f

    /** Touches de chaque doigt posé (identifiant du pointeur -> codes de touche). */
    private val pointerKeys = HashMap<Int, Set<Int>>()
    private val pressed = HashSet<Int>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val fillPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 245, 190, 0) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.argb(140, 255, 255, 255)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val unit = min(w, h).toFloat()
        padR = unit * 0.17f
        padCx = padR + 24f * density
        padCy = h - padR - 24f * density
        val br = unit * 0.075f
        // Arc A B C D : de bas-gauche vers haut-droite, comme sur la manette Neo Geo.
        val baseX = w - 24f * density - br
        val baseY = h - 24f * density - br
        val step = br * 2.25f
        val offsets = listOf(-3f to 0.0f, -2f to -0.55f, -1f to -0.9f, 0f to -1.05f)
        buttons.forEachIndexed { i, z ->
            z.r = br
            z.cx = baseX + offsets[i].first * step
            z.cy = baseY + offsets[i].second * step
        }
        val sr = unit * 0.05f
        coin.r = sr; coin.cx = w / 2f - sr * 1.6f; coin.cy = sr + 16f * density
        start.r = sr; start.cx = w / 2f + sr * 1.6f; start.cy = sr + 16f * density
        text.textSize = br * 0.7f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Croix
        canvas.drawCircle(padCx, padCy, padR, fill)
        canvas.drawCircle(padCx, padCy, padR, stroke)
        val arm = padR * 0.62f
        val dirs = listOf(
            KeyEvent.KEYCODE_DPAD_UP to (0f to -arm), KeyEvent.KEYCODE_DPAD_DOWN to (0f to arm),
            KeyEvent.KEYCODE_DPAD_LEFT to (-arm to 0f), KeyEvent.KEYCODE_DPAD_RIGHT to (arm to 0f)
        )
        for ((key, off) in dirs) {
            canvas.drawCircle(padCx + off.first, padCy + off.second, padR * 0.24f, if (key in pressed) fillPressed else fill)
        }
        // Boutons
        for (z in buttons) {
            canvas.drawCircle(z.cx, z.cy, z.r, if (z.keyCode in pressed) fillPressed else fill)
            canvas.drawCircle(z.cx, z.cy, z.r, stroke)
            canvas.drawText(z.label, z.cx, z.cy + text.textSize * 0.35f, text)
        }
        val saved = text.textSize
        text.textSize = coin.r * 0.55f
        for (z in small) {
            canvas.drawRoundRect(z.cx - z.r * 1.4f, z.cy - z.r * 0.6f, z.cx + z.r * 1.4f, z.cy + z.r * 0.6f, z.r, z.r,
                if (z.keyCode in pressed) fillPressed else fill)
            canvas.drawText(z.label, z.cx, z.cy + text.textSize * 0.35f, text)
        }
        text.textSize = saved
    }

    /** Touches visées par un doigt en (x, y). */
    private fun keysAt(x: Float, y: Float): Set<Int> {
        val dx = x - padCx
        val dy = y - padCy
        if (hypot(dx, dy) <= padR * 1.5f && hypot(dx, dy) > padR * 0.18f) {
            // 8 secteurs de 45° : diagonales = deux directions à la fois.
            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).let { if (it < 0) it + 360 else it }
            val sector = (((angle + 22.5) % 360) / 45).toInt()
            return when (sector) {
                0 -> setOf(KeyEvent.KEYCODE_DPAD_RIGHT)
                1 -> setOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN)
                2 -> setOf(KeyEvent.KEYCODE_DPAD_DOWN)
                3 -> setOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
                4 -> setOf(KeyEvent.KEYCODE_DPAD_LEFT)
                5 -> setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP)
                6 -> setOf(KeyEvent.KEYCODE_DPAD_UP)
                else -> setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
            }
        }
        for (z in buttons + small) {
            val hit = if (z in small) z.r * 1.6f else z.r * 1.15f
            if (hypot(x - z.cx, y - z.cy) <= hit) return setOf(z.keyCode)
        }
        return emptySet()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                pointerKeys[event.getPointerId(i)] = keysAt(event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                pointerKeys[event.getPointerId(i)] = keysAt(event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> pointerKeys.remove(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_CANCEL -> pointerKeys.clear()
        }
        val now = pointerKeys.values.flatten().toSet()
        val released = pressed - now
        val newly = now - pressed
        released.forEach { onKey(it, false) }
        newly.forEach { onKey(it, true) }
        if (newly.isNotEmpty()) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (released.isNotEmpty() || newly.isNotEmpty()) {
            pressed.clear()
            pressed.addAll(now)
            invalidate()
        }
        return true
    }

    /** Relâche tout (pause, perte de focus). */
    fun releaseAll() {
        pressed.forEach { onKey(it, false) }
        pressed.clear()
        pointerKeys.clear()
        invalidate()
    }
}
