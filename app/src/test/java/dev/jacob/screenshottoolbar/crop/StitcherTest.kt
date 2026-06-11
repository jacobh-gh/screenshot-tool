package dev.jacob.screenshottoolbar.crop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StitcherTest {

    /** Frame whose row i has luminance hash = base + i (monotonic, easy to reason about). */
    private fun ramp(base: Int, height: Int) =
        Frame(IntArray(height) { base + it }, height)

    @Test
    fun findsCleanOverlap() {
        // prev rows 0..99 ; next starts at row 60 of prev (40-row overlap)
        val prev = ramp(0, 100)
        val next = ramp(60, 100)
        // next's top (value 60) sits at prev row 60.
        assertEquals(60, Stitcher.findOverlap(prev, next, minOverlap = 10, tolerance = 0))
    }

    @Test
    fun findsOverlapWithinTolerance() {
        val prev = ramp(0, 100)
        val nextRows = IntArray(100) { 60 + it + (if (it % 5 == 0) 1 else 0) } // small noise
        val next = Frame(nextRows, 100)
        val offset = Stitcher.findOverlap(prev, next, minOverlap = 10, tolerance = 2)
        assertEquals(60, offset)
    }

    @Test
    fun returnsNullWhenIdentical() {
        // Content didn't scroll: next == prev. No forward progress → null (end of content).
        val prev = ramp(0, 100)
        val next = ramp(0, 100)
        assertNull(Stitcher.findOverlap(prev, next, minOverlap = 10, tolerance = 0))
    }

    @Test
    fun returnsNullWhenNoCredibleMatch() {
        val prev = ramp(0, 100)
        val next = ramp(1000, 100) // totally different content
        assertNull(Stitcher.findOverlap(prev, next, minOverlap = 10, tolerance = 0))
    }

    @Test
    fun bandOverlapIgnoresStickyTopChrome() {
        // Sticky top 30 rows identical in both frames; content scrolled by 40.
        // findOverlap (from next[0]) would be defeated by the sticky band that
        // doesn't match prev's scrolled content; findScrollOffset must not be.
        val prev = Frame(IntArray(200) { if (it < 30) 7 else it }, 200)
        val next = Frame(IntArray(200) { if (it < 30) 7 else it + 40 }, 200)
        // content row r (>=30) of next equals content row r+40 of prev → offset 40.
        assertEquals(40, Stitcher.findScrollOffset(prev, next, topMargin = 30, bottomMargin = 0, minMatch = 20, tolerance = 0))
    }

    @Test
    fun bandOverlapPrefersTrueOffsetOverShorterPartialMatch() {
        // Content scrolled by 80. A SHORTER offset compares far more rows and
        // could win on raw match COUNT from incidental matches, even though a
        // smaller FRACTION of its compared rows align. Fraction-based scoring
        // must pick the true offset (80), where (almost) every compared row
        // aligns. prev is a strictly increasing ramp (no aliasing) plus a value
        // band that gives a short offset some — but not full — incidental hits.
        val h = 400
        // Distinct values so the only fully-aligning offset is the real one.
        val prev = Frame(IntArray(h) { it * 2 }, h)
        // next = prev scrolled by 80: next[r] == prev[r + 80] == (r+80)*2.
        val next = Frame(IntArray(h) { r -> (r + 80) * 2 }, h)
        val offset = Stitcher.findScrollOffset(
            prev, next, topMargin = 20, bottomMargin = 20, minMatch = 40, tolerance = 0,
        )
        assertEquals(80, offset)
    }

    @Test
    fun bandOverlapNullWhenNoScroll() {
        val prev = Frame(IntArray(200) { if (it < 30) 7 else it }, 200)
        val next = Frame(IntArray(200) { if (it < 30) 7 else it }, 200)
        assertNull(Stitcher.findScrollOffset(prev, next, topMargin = 30, bottomMargin = 0, minMatch = 20, tolerance = 0))
    }

    @Test
    fun detectsStaticTopAndBottomChrome() {
        // 5 rows of identical top chrome, 3 of identical bottom, middle varies.
        fun frame(mid: Int) = Frame(
            IntArray(20) { r ->
                when {
                    r < 5 -> 7        // sticky header
                    r >= 17 -> 9      // bottom bar
                    else -> mid + r
                }
            },
            20,
        )
        val frames = listOf(frame(100), frame(200), frame(300))
        assertEquals(5 to 3, Stitcher.staticChrome(frames))
    }

    @Test
    fun noStaticChromeWhenEverythingMoves() {
        val frames = listOf(ramp(0, 20), ramp(50, 20), ramp(100, 20))
        assertEquals(0 to 0, Stitcher.staticChrome(frames))
    }

    @Test
    fun stitchPlanSingleFrameIsPassthrough() {
        val plan = Stitcher.stitchPlan(listOf(ramp(0, 100)), minOverlap = 10, tolerance = 0)
        assertEquals(listOf(CopyRange(frameIndex = 0, srcTop = 0, srcBottom = 100)), plan)
    }

    @Test
    fun stitchPlanTwoOverlappingFrames() {
        // frame0 full; frame1 contributes only the rows below the overlap.
        val f0 = ramp(0, 100)
        val f1 = ramp(60, 100) // overlap offset 60 → new rows are next[40..100]
        val plan = Stitcher.stitchPlan(listOf(f0, f1), minOverlap = 10, tolerance = 0)
        assertEquals(
            listOf(
                CopyRange(0, 0, 100),
                CopyRange(1, 40, 100),
            ),
            plan,
        )
    }

    @Test
    fun stitchPlanStopsAtIdenticalFrame() {
        // third frame identical to second → treated as end; not included.
        val f0 = ramp(0, 100)
        val f1 = ramp(60, 100)
        val f2 = ramp(60, 100)
        val plan = Stitcher.stitchPlan(listOf(f0, f1, f2), minOverlap = 10, tolerance = 0)
        assertEquals(
            listOf(
                CopyRange(0, 0, 100),
                CopyRange(1, 40, 100),
            ),
            plan,
        )
    }

    @Test
    fun stitchPlanKeepsStaticBottomBarOnlyOnLastSegment() {
        // 6-row sticky bottom bar repeated in every frame; content scrolls by 10.
        // The bar must be stripped from the first segment and kept on the last,
        // so the stitched image ends on the bar (not the bar floating mid-image).
        val h = 50
        val barTop = h - 6
        fun frame(mid: Int) = Frame(
            IntArray(h) { r -> if (r >= barTop) 9 else mid + r },
            h,
        )
        val plan = Stitcher.stitchPlan(listOf(frame(100), frame(110)), minOverlap = 5, tolerance = 0)
        // First segment stops above the bar.
        assertEquals(barTop, plan.first().srcBottom)
        // Last segment runs all the way to the bottom (includes the bar once).
        assertEquals(h, plan.last().srcBottom)
    }

    @Test
    fun stitchPlanForcesMinBottomChromeEvenWhenNotDetectedStatic() {
        // Simulate the gesture pill: the bottom 4 rows VARY across frames (so
        // staticChrome won't catch them) yet must still be stripped from the
        // first segment and re-added only on the last via minBottomChrome.
        val h = 60
        val barTop = h - 4
        fun frame(mid: Int) = Frame(
            IntArray(h) { r -> if (r >= barTop) mid + r * 7 else mid + r }, // bottom varies
            h,
        )
        val plan = Stitcher.stitchPlan(
            listOf(frame(100), frame(110)),
            minOverlap = 5, tolerance = 0, minBottomChrome = 4,
        )
        assertEquals("first segment stops above the forced bottom strip", barTop, plan.first().srcBottom)
        assertEquals("last segment keeps the bottom strip", h, plan.last().srcBottom)
    }

    @Test
    fun stitchPlanExcludesStaticChromeFromInteriorFrames() {
        // 5-row sticky header repeated; frames scroll by 10.
        fun frame(mid: Int) = Frame(
            IntArray(50) { r -> if (r < 5) 7 else mid + r },
            50,
        )
        val plan = Stitcher.stitchPlan(listOf(frame(100), frame(110)), minOverlap = 5, tolerance = 0)
        // First frame keeps its header; second frame's header rows are skipped.
        assertEquals(0, plan[0].srcTop)
        assertTrue("interior frame should start at or below the static header", plan[1].srcTop >= 5)
    }
}
