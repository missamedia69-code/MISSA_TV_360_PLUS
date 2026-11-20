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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Manette tactile Neo Geo (téléphone et tablette), dessinée par-dessus le jeu.
 *
 *  - moitié gauche de l'écran : **joystick flottant**. Il se place là où le pouce se pose
 *    (pas besoin de viser un cercle), une petite poussée suffit pour une direction, et il
 *    suit le pouce quand celui-ci part trop loin, pour revenir en arrière tout de suite ;
 *  - à droite, les quatre boutons A B C D en arc, comme sur la manette d'origine. Le pouce
 *    posé entre deux boutons voisins appuie sur les deux (A+B, B+C...), utile pour les coups
 *    spéciaux ;
 *  - en haut, PIÈCE et START.
 *
 * Diagonales : une direction est prise dès que sa composante dépasse 35 % de la poussée
 * (au lieu de huit secteurs égaux de 45°) : les diagonales sont plus larges, donc « haut +
 * droite » se fait sans pousser loin ni viser précisément.
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

    // ---------------------------------------------------------------- joystick flottant

    /** Position de repos (affichée en transparence quand aucun doigt n'est posé). */
    private var restX = 0f
    private var restY = 0f
    /** Rayon du socle dessiné, et distance au-delà de laquelle le socle suit le pouce. */
    private var stickR = 0f
    /** En dessous de cette poussée : aucune direction (petits tremblements du pouce). */
    private var deadZone = 0f
    /** Limite droite de la zone du joystick (moitié gauche, sans la bande de PIÈCE/START). */
    private var stickZoneRight = 0f
    private var stickZoneTop = 0f

    private var stickPointer = -1
    private var originX = 0f
    private var originY = 0f
    private var knobX = 0f
    private var knobY = 0f

    /** Touches de chaque doigt posé (identifiant du pointeur -> codes de touche). */
    private val pointerKeys = HashMap<Int, Set<Int>>()
    private val pressed = HashSet<Int>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) }
    private val fillIdle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(32, 255, 255, 255) }
    private val fillPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 245, 190, 0) }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 255, 255, 255) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.argb(130, 255, 255, 255)
    }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val unit = min(w, h).toFloat()
        // Socle plus petit qu'avant (0,17 -> 0,13 de la petite dimension) : le pouce n'a plus
        // besoin de traverser un grand cercle pour changer de direction.
        stickR = unit * 0.13f
        deadZone = 10f * density
        restX = stickR + 28f * density
        restY = h - stickR - 28f * density
        stickZoneRight = w * 0.5f

        val br = unit * 0.08f
        // Arc A B C D : de bas-gauche vers haut-droite, comme sur la manette Neo Geo.
        val baseX = w - 22f * density - br
        val baseY = h - 22f * density - br
        val step = br * 2.2f
        val offsets = listOf(-3f to 0.0f, -2f to -0.55f, -1f to -0.9f, 0f to -1.05f)
        buttons.forEachIndexed { i, z ->
            z.r = br
            z.cx = baseX + offsets[i].first * step
            z.cy = baseY + offsets[i].second * step
        }
        val sr = unit * 0.05f
        coin.r = sr; coin.cx = w / 2f - sr * 1.7f; coin.cy = sr + 14f * density
        start.r = sr; start.cx = w / 2f + sr * 1.7f; start.cy = sr + 14f * density
        // La bande du haut reste aux petits boutons : le joystick commence en dessous.
        stickZoneTop = coin.cy + coin.r * 1.4f
        text.textSize = br * 0.7f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Joystick : socle là où le pouce s'est posé, ou en transparence à sa place de repos.
        val active = stickPointer >= 0
        val cx = if (active) originX else restX
        val cy = if (active) originY else restY
        canvas.drawCircle(cx, cy, stickR, if (active) fill else fillIdle)
        canvas.drawCircle(cx, cy, stickR, stroke)
        val arm = stickR * 0.72f
        val tri = stickR * 0.12f
        val dirs = listOf(
            KeyEvent.KEYCODE_DPAD_UP to (0f to -1f), KeyEvent.KEYCODE_DPAD_DOWN to (0f to 1f),
            KeyEvent.KEYCODE_DPAD_LEFT to (-1f to 0f), KeyEvent.KEYCODE_DPAD_RIGHT to (1f to 0f)
        )
        for ((key, d) in dirs) {
            val px = cx + d.first * arm
            val py = cy + d.second * arm
            canvas.drawCircle(px, py, tri, if (key in pressed) fillPressed else arrow)
        }
        val kx = if (active) knobX else cx
        val ky = if (active) knobY else cy
        canvas.drawCircle(kx, ky, stickR * 0.42f, if (active) knob else fillIdle)

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

    /** Directions pour un pouce en (x, y), le socle étant en (originX, originY). */
    private fun stickKeys(x: Float, y: Float): Set<Int> {
        var dx = x - originX
        var dy = y - originY
        var dist = hypot(dx, dy)
        // Pouce parti trop loin : le socle le suit (revenir en arrière est alors immédiat).
        val maxR = stickR
        if (dist > maxR) {
            originX = x - dx / dist * maxR
            originY = y - dy / dist * maxR
            dx = x - originX
            dy = y - originY
            dist = maxR
        }
        knobX = x
        knobY = y
        if (dist < deadZone) return emptySet()
        // Une direction est prise dès que sa part dépasse 35 % de la poussée : diagonales larges.
        val threshold = dist * 0.35f
        val keys = HashSet<Int>(2)
        if (dx > threshold) keys += KeyEvent.KEYCODE_DPAD_RIGHT
        if (dx < -threshold) keys += KeyEvent.KEYCODE_DPAD_LEFT
        if (dy > threshold) keys += KeyEvent.KEYCODE_DPAD_DOWN
        if (dy < -threshold) keys += KeyEvent.KEYCODE_DPAD_UP
        return keys
    }

    /** Boutons visés par un doigt en (x, y) : un ou deux voisins, jamais rien entre eux. */
    private fun buttonKeys(x: Float, y: Float): Set<Int> {
        for (z in small) {
            if (abs(x - z.cx) <= z.r * 1.7f && abs(y - z.cy) <= z.r * 1.1f) return setOf(z.keyCode)
        }
        val byDistance = buttons.map { it to hypot(x - it.cx, y - it.cy) }.sortedBy { it.second }
        val nearest = byDistance.first()
        if (nearest.second > nearest.first.r * 1.8f) return emptySet()
        // Pouce posé à cheval sur deux boutons voisins : les deux à la fois.
        val second = byDistance[1]
        return if (second.second <= second.first.r * 1.2f && nearest.second <= nearest.first.r * 1.2f) {
            setOf(nearest.first.keyCode, second.first.keyCode)
        } else {
            setOf(nearest.first.keyCode)
        }
    }

    private fun isStickZone(x: Float, y: Float) = x < stickZoneRight && y > stickZoneTop

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                val x = event.getX(i)
                val y = event.getY(i)
                if (stickPointer < 0 && isStickZone(x, y)) {
                    // Le socle se pose sous le pouce (en restant entier dans l'écran).
                    stickPointer = id
                    originX = x.coerceIn(stickR, width - stickR)
                    originY = y.coerceIn(stickR, height - stickR)
                    pointerKeys[id] = stickKeys(x, y)
                } else {
                    pointerKeys[id] = buttonKeys(x, y)
                }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                val id = event.getPointerId(i)
                val x = event.getX(i)
                val y = event.getY(i)
                pointerKeys[id] = if (id == stickPointer) stickKeys(x, y) else buttonKeys(x, y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                pointerKeys.remove(id)
                if (id == stickPointer) stickPointer = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerKeys.clear()
                stickPointer = -1
            }
        }
        val now = pointerKeys.values.flatten().toSet()
        val released = pressed - now
        val newly = now - pressed
        released.forEach { onKey(it, false) }
        newly.forEach { onKey(it, true) }
        if (newly.any { it !in DIRECTIONS }) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (released.isNotEmpty() || newly.isNotEmpty()) {
            pressed.clear()
            pressed.addAll(now)
        }
        // Le pouce bouge à chaque mouvement : le joystick est redessiné même sans changement.
        invalidate()
        return true
    }

    /** Relâche tout (pause, perte de focus). */
    fun releaseAll() {
        pressed.forEach { onKey(it, false) }
        pressed.clear()
        pointerKeys.clear()
        stickPointer = -1
        invalidate()
    }

    private companion object {
        val DIRECTIONS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
        )
    }
}
