package com.lagradost.cloudstream3.ui.universe

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure D-Pad navigation math of [MissaTvFocusHelper] (FLOW-02, NAV-02).
 *
 * These helpers back every deterministic remote-control move on Android TV: column-aligned
 * horizontal rails, hero banner snapping, uniform grids and adjacent-cell picking. They are
 * deliberately free of Android runtime state (`View`, `RecyclerView`, window) so the behaviour
 * the TV remote depends on can be verified on the JVM by `:app:testPrereleaseDebugUnitTest`.
 */
class MissaTvFocusHelperTest {

    // region Horizontal rail: column-aligned scrolling (NAV-02)

    @Test
    fun `rail does not scroll when the focused card is already fully visible`() {
        assertEquals(
            0,
            MissaTvFocusHelper.computeAlignedHorizontalScrollDx(
                leftEdge = 0,
                rightEdge = 600,
                childLeft = 100,
                childRight = 260,
                childWidth = 160
            )
        )
        // Last fully visible column: must stay static so column X coordinates never drift
        // between neighbouring rows.
        assertEquals(
            0,
            MissaTvFocusHelper.computeAlignedHorizontalScrollDx(
                leftEdge = 0,
                rightEdge = 600,
                childLeft = 340,
                childRight = 500,
                childWidth = 160
            )
        )
    }

    @Test
    fun `rail snaps a left-clipped card exactly to the left edge`() {
        assertEquals(
            -40,
            MissaTvFocusHelper.computeAlignedHorizontalScrollDx(
                leftEdge = 0,
                rightEdge = 600,
                childLeft = -40,
                childRight = 120,
                childWidth = 160
            )
        )
    }

    @Test
    fun `rail reveals a right-clipped card in whole column steps`() {
        // 600dp viewport / 160dp cards = 3 full columns -> the revealed card must land at
        // leftEdge + (3 - 1) * 160 = 320, keeping it pixel-aligned with the rows above.
        assertEquals(
            180,
            MissaTvFocusHelper.computeAlignedHorizontalScrollDx(
                leftEdge = 0,
                rightEdge = 600,
                childLeft = 500,
                childRight = 660,
                childWidth = 160
            )
        )
        assertEquals(
            320,
            MissaTvFocusHelper.computeAlignedHorizontalScrollDx(
                leftEdge = 0,
                rightEdge = 600,
                childLeft = 640,
                childRight = 800,
                childWidth = 160
            )
        )
    }

    @Test
    fun `rail survives a zero measured card width`() {
        assertEquals(
            2,
            MissaTvFocusHelper.computeAlignedHorizontalScrollDx(
                leftEdge = 0,
                rightEdge = 10,
                childLeft = 11,
                childRight = 11,
                childWidth = 0
            )
        )
    }

    // endregion

    // region Hero banner snap (FLOW-02)

    @Test
    fun `hero banner is restored completely when scrolling back up`() {
        assertEquals(
            -100,
            MissaTvFocusHelper.computeHeroBannerSnapDy(
                heroTop = -100,
                heroBottom = 100,
                viewportTop = 0,
                lastScrollDy = -1
            )
        )
    }

    @Test
    fun `hero banner tucks away when scrolling down past the 35 percent threshold`() {
        // Only 100 of 200dp still visible (50% < 65%) -> tuck the banner out of the way.
        assertEquals(
            100,
            MissaTvFocusHelper.computeHeroBannerSnapDy(
                heroTop = -100,
                heroBottom = 100,
                viewportTop = 0,
                lastScrollDy = 1
            )
        )
    }

    @Test
    fun `hero banner is restored on a small downward nudge`() {
        // 150 of 200dp still visible (75% >= 65%) -> accidental nudge, restore to viewportTop.
        assertEquals(
            -50,
            MissaTvFocusHelper.computeHeroBannerSnapDy(
                heroTop = -50,
                heroBottom = 150,
                viewportTop = 0,
                lastScrollDy = 1
            )
        )
    }

    @Test
    fun `hero banner is never tucked on a short page so bottom content stays reachable`() {
        assertEquals(
            0,
            MissaTvFocusHelper.computeHeroBannerSnapDy(
                heroTop = -100,
                heroBottom = 100,
                viewportTop = 0,
                lastScrollDy = 1,
                canScrollFurtherDown = false
            )
        )
    }

    @Test
    fun `hero banner snap is a no-op when the banner is not clipped`() {
        assertEquals(
            0,
            MissaTvFocusHelper.computeHeroBannerSnapDy(
                heroTop = 0,
                heroBottom = 200,
                viewportTop = 0,
                lastScrollDy = 1
            )
        )
        // Fully scrolled past: banner bottom already above the viewport.
        assertEquals(
            0,
            MissaTvFocusHelper.computeHeroBannerSnapDy(
                heroTop = -300,
                heroBottom = -100,
                viewportTop = 0,
                lastScrollDy = 1
            )
        )
    }

    // endregion

    // region Uniform grid navigation (NAV-02)

    private fun grid(
        fromPos: Int,
        itemCount: Int,
        spanCount: Int,
        direction: Int,
        isRtl: Boolean = false,
        trapBottomEdge: Boolean = false
    ) = MissaTvFocusHelper.computeUniformGridNextPosition(
        fromPos = fromPos,
        itemCount = itemCount,
        spanCount = spanCount,
        direction = direction,
        isRtl = isRtl,
        trapBottomEdge = trapBottomEdge
    )

    @Test
    fun `grid never wraps horizontally across a row boundary`() {
        // Column 2 (last) of a 3-column grid: DPAD_RIGHT must stay anchored on the border.
        assertEquals(2, grid(fromPos = 2, itemCount = 9, spanCount = 3, direction = View.FOCUS_RIGHT))
        assertEquals(5, grid(fromPos = 5, itemCount = 9, spanCount = 3, direction = View.FOCUS_RIGHT))
        // Column 0: DPAD_LEFT must stay anchored on the border.
        assertEquals(0, grid(fromPos = 0, itemCount = 9, spanCount = 3, direction = View.FOCUS_LEFT))
        assertEquals(3, grid(fromPos = 3, itemCount = 9, spanCount = 3, direction = View.FOCUS_LEFT))
    }

    @Test
    fun `grid steps one cell at a time horizontally`() {
        assertEquals(1, grid(fromPos = 0, itemCount = 9, spanCount = 3, direction = View.FOCUS_RIGHT))
        assertEquals(3, grid(fromPos = 4, itemCount = 9, spanCount = 3, direction = View.FOCUS_LEFT))
    }

    @Test
    fun `grid preserves the exact column index on vertical moves`() {
        assertEquals(1, grid(fromPos = 4, itemCount = 9, spanCount = 3, direction = View.FOCUS_UP))
        assertEquals(7, grid(fromPos = 4, itemCount = 9, spanCount = 3, direction = View.FOCUS_DOWN))
        assertEquals(5, grid(fromPos = 8, itemCount = 9, spanCount = 3, direction = View.FOCUS_UP))
    }

    @Test
    fun `grid escapes upward from the top row and traps the bottom edge on demand`() {
        assertEquals(-1, grid(fromPos = 1, itemCount = 9, spanCount = 3, direction = View.FOCUS_UP))
        assertEquals(-1, grid(fromPos = 7, itemCount = 9, spanCount = 3, direction = View.FOCUS_DOWN))
        assertEquals(
            7,
            grid(
                fromPos = 7,
                itemCount = 9,
                spanCount = 3,
                direction = View.FOCUS_DOWN,
                trapBottomEdge = true
            )
        )
    }

    @Test
    fun `grid clamps the last partial row instead of leaving the grid`() {
        // 8 items in 3 columns: position 5 steps down to the only cell of the partial row (7).
        assertEquals(7, grid(fromPos = 5, itemCount = 8, spanCount = 3, direction = View.FOCUS_DOWN))
        assertEquals(-1, grid(fromPos = 6, itemCount = 8, spanCount = 3, direction = View.FOCUS_DOWN))
    }

    @Test
    fun `grid mirrors lateral directions in RTL`() {
        assertEquals(3, grid(fromPos = 4, itemCount = 9, spanCount = 3, direction = View.FOCUS_RIGHT, isRtl = true))
        assertEquals(5, grid(fromPos = 4, itemCount = 9, spanCount = 3, direction = View.FOCUS_LEFT, isRtl = true))
        // Vertical moves are unaffected by layout direction.
        assertEquals(1, grid(fromPos = 4, itemCount = 9, spanCount = 3, direction = View.FOCUS_UP, isRtl = true))
    }

    @Test
    fun `grid rejects out of range positions`() {
        assertEquals(-1, grid(fromPos = -1, itemCount = 9, spanCount = 3, direction = View.FOCUS_DOWN))
        assertEquals(-1, grid(fromPos = 9, itemCount = 9, spanCount = 3, direction = View.FOCUS_DOWN))
        assertEquals(-1, grid(fromPos = 0, itemCount = 0, spanCount = 3, direction = View.FOCUS_DOWN))
    }

    // endregion

    // region Adjacent-cell picking

    @Test
    fun `closest horizontal index picks the nearest center and keeps the first on a tie`() {
        assertEquals(1, MissaTvFocusHelper.pickClosestHorizontalIndex(listOf(10, 50, 90), 45))
        assertEquals(0, MissaTvFocusHelper.pickClosestHorizontalIndex(listOf(10, 50, 90), 0))
        assertEquals(2, MissaTvFocusHelper.pickClosestHorizontalIndex(listOf(10, 50, 90), 200))
        assertEquals(0, MissaTvFocusHelper.pickClosestHorizontalIndex(listOf(40, 60), 50))
        assertEquals(-1, MissaTvFocusHelper.pickClosestHorizontalIndex(emptyList(), 50))
    }

    @Test
    fun `adjacent picking favours the horizontally overlapping candidate`() {
        val bounds = listOf(0 to 100, 110 to 210, 300 to 400)
        // Overlaps by 50dp vs 40dp, so the first cell wins even though the second is closer
        // to the right edge of the focused cell.
        assertEquals(0, MissaTvFocusHelper.pickBestAdjacentIndex(bounds, focusedLeft = 50, focusedRight = 150))
        assertEquals(2, MissaTvFocusHelper.pickBestAdjacentIndex(bounds, focusedLeft = 350, focusedRight = 450))
        assertEquals(0, MissaTvFocusHelper.pickBestAdjacentIndex(bounds, focusedLeft = 0, focusedRight = 50))
        assertEquals(
            -1,
            MissaTvFocusHelper.pickBestAdjacentIndex(emptyList(), focusedLeft = 0, focusedRight = 50)
        )
    }

    @Test
    fun `adjacent picking breaks overlap ties by the closest center`() {
        // Both candidates overlap the focused cell by exactly 60dp; the one whose center sits
        // closest to the focused center (40) must win -> index 1 (0..60, center 30).
        assertEquals(1, MissaTvFocusHelper.pickBestAdjacentIndex(listOf(20 to 100, 0 to 60), 0, 80))
    }

    @Test
    fun `adjacent picking falls back to the smallest horizontal gap`() {
        assertEquals(0, MissaTvFocusHelper.pickBestAdjacentIndex(listOf(100 to 200, 205 to 300), 0, 10))
        assertEquals(0, MissaTvFocusHelper.pickBestAdjacentIndex(listOf(0 to 50), 200, 260))
    }

    // endregion

    // region Focus search safety (detached-view crash guard)

    @Test
    fun `focus search outcome maps every attachment combination deterministically`() {
        assertEquals(
            MissaTvFocusHelper.FocusSearchOutcome.RETURN_FOCUSED_ONLY,
            MissaTvFocusHelper.resolveFocusSearchOutcome(
                sameAsFocused = true,
                focusedStillAttached = true,
                targetValidAndAttached = true
            )
        )
        // Focused was recycled by the synchronous scrollBy inside onInterceptFocusSearch:
        // request focus on the target but RETURN the focused view so neither RecyclerView
        // nor ViewRootImpl calls offsetDescendantRectToMyCoords on a detached view.
        assertEquals(
            MissaTvFocusHelper.FocusSearchOutcome.REQUEST_TARGET_AND_RETURN_FOCUSED,
            MissaTvFocusHelper.resolveFocusSearchOutcome(
                sameAsFocused = false,
                focusedStillAttached = false,
                targetValidAndAttached = true
            )
        )
        assertEquals(
            MissaTvFocusHelper.FocusSearchOutcome.RETURN_FOCUSED_ONLY,
            MissaTvFocusHelper.resolveFocusSearchOutcome(
                sameAsFocused = false,
                focusedStillAttached = false,
                targetValidAndAttached = false
            )
        )
        assertEquals(
            MissaTvFocusHelper.FocusSearchOutcome.RETURN_TARGET,
            MissaTvFocusHelper.resolveFocusSearchOutcome(
                sameAsFocused = false,
                focusedStillAttached = true,
                targetValidAndAttached = true
            )
        )
        assertEquals(
            MissaTvFocusHelper.FocusSearchOutcome.RETURN_NULL,
            MissaTvFocusHelper.resolveFocusSearchOutcome(
                sameAsFocused = false,
                focusedStillAttached = true,
                targetValidAndAttached = false
            )
        )
    }

    @Test
    fun `tv dims declare the 10 foot scale for sw600dp televisions`() {
        // Qualifier precedence: smallestWidth outranks UI mode, so a values-television overlay
        // alone is shadowed by values-sw600dp on every 720p/1080p/4K TV. The union directory
        // must exist and must carry the 10-foot hero scale.
        val dimsRoot = java.io.File("src/main/res").takeIf { it.isDirectory }
            ?: java.io.File("app/src/main/res")
        val unionDims = java.io.File(dimsRoot, "values-sw600dp-television/dimens.xml")
        val tabletDims = java.io.File(dimsRoot, "values-sw600dp/dimens.xml")
        assertTrue(unionDims.isFile)
        val union = unionDims.readText()
        val tablet = tabletDims.readText()

        for (dimen in listOf(
            "missa_text_screen_title",
            "missa_hero_height",
            "missa_section_horizontal_padding",
            "missa_hub_card_width"
        )) {
            assertTrue("missing $dimen in values-sw600dp-television/dimens.xml", union.contains(dimen))
        }

        assertTrue(
            "TV hero must stay taller than the tablet hero",
            dimenValue(union, "missa_hero_height") > dimenValue(tablet, "missa_hero_height")
        )
    }

    private fun dimenValue(dimensXml: String, name: String): Int {
        val regex = Regex("""<dimen name="$name">(\d+)dp</dimen>""")
        return regex.find(dimensXml)?.groupValues?.get(1)?.toInt()
            ?: throw AssertionError("no dp value for $name")
    }

    // endregion
}
