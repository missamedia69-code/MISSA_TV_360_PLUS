package com.lagradost.cloudstream3.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/**
 * AUDIT H11: the visual-quality guards that can be checked on the JVM, with no emulator.
 *
 * Four regressions the project has already paid for once, now watched by
 * `:app:testPrereleaseDebugUnitTest`:
 *
 * 1. **The wordmark must stay legible in every theme.** The amber that fills a pill is far
 *    too light to read as text on a light page: `missaYellowPrimaryLight` (#E0A800) sits at
 *    2.15:1 on white and 1.95:1 on the lavender page, where the acceptance criterion of the
 *    audit is 4.5:1. The accent used as ink is now a separate attribute,
 *    `missaAccentOnSurface`, and this test recomputes the ratio of the eight themes from
 *    the XML, sheen included.
 * 2. **`android:alpha` on a `<item>` of a `<layer-list>`** (D.1). `LayerDrawableItem`
 *    declares no `alpha` attribute, so it is silently dropped and the layer paints at full
 *    intensity: that is how the strip ended up with a gold block on the left and an emerald
 *    one on the right.
 * 3. **The dither tile must stay 32x32** (HEADER-01). A `<bitmap>` child passes its
 *    intrinsic size to `LayerDrawable.getMinimumHeight()`, and the ribbon is a
 *    `wrap_content` LinearLayout, so a 128x128 tile pinned the whole header to 128px.
 * 4. **No hardcoded colour in a layout.** Everything themed must read the theme, or it
 *    silently breaks the eight themes. The player overlays are the one exception: they are
 *    drawn over video, which is not a themed surface.
 */
class MissaThemeContrastTest {

    // region Resource access

    private val resDir: File by lazy {
        var dir = File(".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "src/main/res")
            if (candidate.isDirectory) return@lazy candidate
            dir = dir.parentFile ?: return@repeat
        }
        throw AssertionError(
            "res/ introuvable depuis ${File(".").absoluteFile}. " +
                "Ce test lit les XML de res/ et doit tourner depuis le module app."
        )
    }

    private val colors: Map<String, String> by lazy {
        val out = HashMap<String, String>()
        val re = Regex("<color\\s+name=\"([^\"]+)\"[^>]*>([^<]*)</color>")
        re.findAll(File(resDir, "values/colors.xml").readText()).forEach {
            out[it.groupValues[1]] = it.groupValues[2].trim()
        }
        out
    }

    private val styles: Map<String, List<Pair<String?, Map<String, String>>>> by lazy {
        val text = File(resDir, "values/styles.xml").readText()
        val styleRe = Regex(
            "<style\\s+name=\"([^\"]+)\"(\\s+parent=\"([^\"]*)\")?\\s*>([\\s\\S]*?)</style>"
        )
        val itemRe = Regex("<item\\s+name=\"([^\"]+)\"[^>]*>([^<]*)</item>")
        val out = LinkedHashMap<String, MutableList<Pair<String?, Map<String, String>>>>()
        styleRe.findAll(text).forEach { m ->
            val items = itemRe.findAll(m.groupValues[4])
                .associate { it.groupValues[1] to it.groupValues[2].trim() }
            val list = out.getOrPut(m.groupValues[1]) { ArrayList() }
            list.add(m.groupValues[3].ifBlank { null } to items)
        }
        out
    }

    // endregion

    // region Colour maths

    private fun resolve(value: String?, depth: Int = 0): String? {
        val v = value?.trim() ?: return null
        if (depth > 8) return null
        if (v.startsWith("@color/")) {
            return resolve(colors[v.removePrefix("@color/")], depth + 1)
        }
        return when {
            v.matches(Regex("#[0-9A-Fa-f]{8}")) -> v.uppercase()
            v.matches(Regex("#[0-9A-Fa-f]{6}")) -> "#FF" + v.substring(1).uppercase()
            v.matches(Regex("#[0-9A-Fa-f]{3}")) ->
                "#FF" + v.substring(1).toCharArray().joinToString("") { "$it$it" }.uppercase()
            else -> null
        }
    }

    private fun luminance(hex: String): Double {
        val h = if (hex.length == 9) hex.substring(3) else hex.substring(1)

        fun channel(index: Int): Double {
            val c = h.substring(index, index + 2).toInt(16) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4)
    }

    private fun contrast(foreground: String, background: String): Double {
        val a = luminance(foreground)
        val b = luminance(background)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    /** Paints [foreground] (with its alpha) over an opaque [background]. */
    private fun composite(foreground: String, background: String): String {
        val h = foreground.substring(1)
        val alpha = if (h.length == 8) h.substring(0, 2).toInt(16) / 255.0 else 1.0
        val f = if (h.length == 8) h.substring(2) else h
        val b = if (background.length == 9) background.substring(3) else background.substring(1)

        fun mix(index: Int): Int {
            val fg = f.substring(index, index + 2).toInt(16)
            val bg = b.substring(index, index + 2).toInt(16)
            return (alpha * fg + (1 - alpha) * bg).toInt()
        }
        return "#FF%02X%02X%02X".format(mix(0), mix(2), mix(4))
    }

    // endregion

    // region Theme model

    /**
     * Themes are applied with `theme.applyStyle(mode, true)` (see `CommonActivity.loadThemes`),
     * so every mode is an overlay on the manifest theme and inherits what it does not
     * redefine. The XML `parent` is therefore not enough: `AmoledMode` and `DraculaMode`
     * declare no parent at all and still carry the whole `AppTheme` palette.
     */
    private fun themeAttrs(name: String, seen: MutableSet<String> = HashSet()): Map<String, String> {
        if (!seen.add(name)) return emptyMap()
        val out = LinkedHashMap<String, String>()
        if (name != BASE_THEME) out.putAll(themeAttrs(BASE_THEME, seen))
        styles[name]?.forEach { (parent, items) ->
            if (parent != null &&
                parent != name &&
                !parent.startsWith("Theme.") &&
                !parent.startsWith("@") &&
                !parent.startsWith("Widget.")
            ) {
                out.putAll(themeAttrs(parent, seen))
            }
            out.putAll(items)
        }
        return out
    }

    // endregion

    // region 1. Wordmark contrast in the eight themes

    @Test
    fun `the MISSA TV wordmark clears 4_5 to 1 in every theme`() {
        val failures = ArrayList<String>()
        var resolved = 0
        for (theme in THEMES) {
            val attrs = themeAttrs(theme)
            val ink = resolve(attrs["missaAccentOnSurface"])
            val background = resolve(attrs["primaryBlackBackground"])
            if (ink == null || background == null) {
                // Monet reads its colours from the wallpaper at run time: nothing to check here.
                continue
            }
            resolved++
            // The strip paints a 4% white sheen on top of the theme background, which is
            // what the wordmark is actually drawn on.
            val ratio = contrast(ink, composite(STRIP_SHEEN, background))
            if (ratio < WORDMARK_MIN_CONTRAST) {
                failures.add(
                    "  $theme: ${"%.2f".format(ratio)}:1 (wordmark $ink on $background)"
                )
            }
        }
        assertTrue(
            "Au moins 7 themes sur 8 doivent pouvoir etre resolus, $resolved l'ont ete.",
            resolved >= 7
        )
        assertTrue(
            "Le logo MISSA TV doit passer $WORDMARK_MIN_CONTRAST:1 dans tous les themes :\n" +
                failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // endregion

    // region 2. D.1: no android:alpha on a layer-list item

    @Test
    fun `no layer-list item carries android alpha`() {
        val offenders = drawableFiles()
            .map { it to it.readText() }
            .filter { (_, text) -> text.contains("<layer-list") }
            .flatMap { (file, text) ->
                Regex("<item[^>]*>").findAll(text)
                    .filter { it.value.contains("android:alpha") }
                    .map { "${file.name}: ${it.value.trim()}" }
                    .toList()
            }
        assertTrue(
            "android:alpha ne s'applique pas a un <item> de <layer-list> : LayerDrawableItem " +
                "ne declare pas cet attribut, la couche est peinte a pleine intensite (D.1).\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    // endregion

    // region 3. HEADER-01: the dither tile stays 32x32

    @Test
    fun `the dither tile stays 32x32`() {
        val tile = drawableResources().firstOrNull { it.nameWithoutExtension == DITHER_TILE }
            ?: throw AssertionError("$DITHER_TILE introuvable dans res/drawable*")
        val bytes = tile.readBytes()
        // PNG: 8-byte signature, then the IHDR chunk with width and height at offsets 16 and 20.
        if (bytes.size < 24 || String(bytes, 12, 4, Charsets.US_ASCII) != "IHDR") {
            throw AssertionError("${tile.name} n'est pas un PNG lisible (IHDR absent)")
        }
        val width = readInt(bytes, 16)
        val height = readInt(bytes, 20)
        assertTrue(
            "${tile.name} mesure ${width}x${height}. Un <bitmap> transmet sa taille " +
                "intrinseque au layer-list, et le ruban est en wrap_content : une tuile " +
                "plus grande que 32x32 recree la zone morte de 128px (HEADER-01).",
            width <= DITHER_MAX_SIZE && height <= DITHER_MAX_SIZE
        )
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    // endregion

    // region 4. No hardcoded colour in a layout

    @Test
    fun `no layout hardcodes a colour`() {
        val re = Regex(
            "android:(textColor|background|backgroundTint|foreground|tint|indeterminateTint|" +
                "progressTint|thumbTint|strokeColor|cardBackgroundColor|color)\\s*=\\s*\"#"
        )
        val offenders = layoutFiles()
            .filter { it.name !in LAYOUT_COLOUR_ALLOWLIST }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> re.containsMatchIn(line) }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
            }
        assertTrue(
            "Une couleur en dur casse les huit themes. Seules les surcouches du lecteur sont " +
                "exemptes : elles sont dessinees par-dessus la video, qui n'est pas une " +
                "surface themee.\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    // endregion

    private fun drawableResources(): List<File> = resDir.listFiles()
        .orEmpty()
        .filter { it.isDirectory && it.name.startsWith("drawable") }
        .flatMap { dir -> dir.listFiles().orEmpty().toList() }

    private fun drawableFiles(): List<File> =
        drawableResources().filter { it.extension == "xml" }

    private fun layoutFiles(): List<File> = resDir.listFiles()
        .orEmpty()
        .filter { it.isDirectory && it.name.startsWith("layout") }
        .flatMap { dir -> (dir.listFiles().orEmpty()).filter { it.extension == "xml" } }

    companion object {
        private const val BASE_THEME = "AppTheme"
        private const val STRIP_SHEEN = "#0AFFFFFF"
        private const val WORDMARK_MIN_CONTRAST = 4.5
        private const val DITHER_TILE = "missa_dither_noise"
        private const val DITHER_MAX_SIZE = 32

        /** `CommonActivity.loadThemes`, minus System which maps onto one of the others. */
        private val THEMES = listOf(
            "AppTheme",
            "LightMode",
            "AmoledMode",
            "AmoledModeLight",
            "MonetMode",
            "DraculaMode",
            "LavenderMode",
            "SilentBlueMode"
        )

        private val LAYOUT_COLOUR_ALLOWLIST = setOf(
            "player_custom_layout.xml",
            "player_custom_layout_tv.xml",
            "trailer_custom_layout.xml"
        )
    }
}
