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
 *  - à gauche, un joystick circulaire : une boule centrale suit le pouce dans un anneau, sur
 *    8 directions. Le doigt qui a saisi la boule la garde jusqu'à ce qu'il se lève, même s'il
 *    sort de l'anneau : quarts de cercle et rotations complètes se font d'un seul geste ;
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

    /** Doigt qui tient la boule du joystick (identifiant du pointeur), ou -1. */
    private var stickPointer = -1
    /** Position de la boule par rapport au centre, limitée à la course du joystick. */
    private var knobDx = 0f
    private var knobDy = 0f

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
    private val knobFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 255, 255, 255) }
    private val knobFillHeld = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 245, 190, 0) }
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
        // Joystick : anneau, repères des 4 directions, puis la boule qui suit le pouce.
        canvas.drawCircle(padCx, padCy, padR, fill)
        canvas.drawCircle(padCx, padCy, padR, stroke)
        val mark = padR * 0.82f
        val dirs = listOf(
            KeyEvent.KEYCODE_DPAD_UP to (0f to -mark), KeyEvent.KEYCODE_DPAD_DOWN to (0f to mark),
            KeyEvent.KEYCODE_DPAD_LEFT to (-mark to 0f), KeyEvent.KEYCODE_DPAD_RIGHT to (mark to 0f)
        )
        for ((key, off) in dirs) {
            canvas.drawCircle(padCx + off.first, padCy + off.second, padR * 0.07f, if (key in pressed) fillPressed else fill)
        }
        val held = stickPointer != -1
        canvas.drawCircle(padCx + knobDx, padCy + knobDy, padR * KNOB_RADIUS, if (held) knobFillHeld else knobFill)
        canvas.drawCircle(padCx + knobDx, padCy + knobDy, padR * KNOB_RADIUS, stroke)
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

    /** Le doigt se pose-t-il sur le joystick (anneau et une marge autour) ? */
    private fun onStick(x: Float, y: Float): Boolean = hypot(x - padCx, y - padCy) <= padR * 1.25f

    /** Place la boule sous le doigt (sans dépasser sa course) et renvoie les directions tenues. */
    private fun moveStick(x: Float, y: Float): Set<Int> {
        val dx = x - padCx
        val dy = y - padCy
        val travel = padR * KNOB_TRAVEL
        val dist = hypot(dx, dy)
        val scale = if (dist > travel && dist > 0f) travel / dist else 1f
        knobDx = dx * scale
        knobDy = dy * scale
        return stickKeys(dx, dy, padR * DEAD_ZONE)
    }

    private fun releaseStick() {
        stickPointer = -1
        knobDx = 0f
        knobDy = 0f
    }

    /** Bouton visé par un doigt en (x, y). */
    private fun buttonAt(x: Float, y: Float): Set<Int> {
        for (z in buttons + small) {
            val hit = if (z in small) z.r * 1.6f else z.r * 1.15f
            if (hypot(x - z.cx, y - z.cy) <= hit) return setOf(z.keyCode)
        }
        return emptySet()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val knobBefore = knobDx to knobDy
        val heldBefore = stickPointer
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                val x = event.getX(i)
                val y = event.getY(i)
                if (stickPointer == -1 && onStick(x, y)) {
                    stickPointer = id
                    pointerKeys[id] = moveStick(x, y)
                } else {
                    pointerKeys[id] = buttonAt(x, y)
                }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                val id = event.getPointerId(i)
                pointerKeys[id] = if (id == stickPointer) moveStick(event.getX(i), event.getY(i))
                else buttonAt(event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                pointerKeys.remove(id)
                if (id == stickPointer) releaseStick()
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerKeys.clear()
                releaseStick()
            }
        }
        val now = pointerKeys.values.flatten().toSet()
        val released = pressed - now
        val newly = now - pressed
        released.forEach { onKey(it, false) }
        newly.forEach { onKey(it, true) }
        // Vibration pour les boutons seulement : le joystick en rotation vibrerait sans arrêt.
        if (newly.any { it !in DIRECTION_KEYS }) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (released.isNotEmpty() || newly.isNotEmpty()) {
            pressed.clear()
            pressed.addAll(now)
        }
        if (released.isNotEmpty() || newly.isNotEmpty() || knobBefore != (knobDx to knobDy) || heldBefore != stickPointer) {
            invalidate()
        }
        return true
    }

    /** Relâche tout (pause, perte de focus). */
    fun releaseAll() {
        pressed.forEach { onKey(it, false) }
        pressed.clear()
        pointerKeys.clear()
        releaseStick()
        invalidate()
    }

    companion object {
        /** Rayon de la boule, course de la boule et zone morte, en part du rayon de l'anneau. */
        private const val KNOB_RADIUS = 0.42f
        private const val KNOB_TRAVEL = 0.58f
        private const val DEAD_ZONE = 0.22f

        private val DIRECTION_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
        )

        /**
         * Directions tenues pour une boule déplacée de ([dx], [dy]) depuis le centre (y vers le
         * bas). Rien dans la zone morte ; au-delà, 8 secteurs de 45° : les diagonales tiennent
         * deux directions à la fois, comme le levier à 8 directions de la Neo Geo.
         */
        internal fun stickKeys(dx: Float, dy: Float, deadZone: Float): Set<Int> {
            if (hypot(dx, dy) <= deadZone) return emptySet()
            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).let { if (it < 0) it + 360 else it }
            return when ((((angle + 22.5) % 360) / 45).toInt()) {
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
    }
}
