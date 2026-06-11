package dev.jacob.screenshottoolbar.crop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CropMathTest {

    private val bounds = CropRect(0f, 0f, 100f, 200f)

    @Test
    fun hitTestFindsCorners() {
        val rect = CropRect(10f, 10f, 90f, 190f)
        assertEquals(Handle.TOP_LEFT, CropMath.hitTest(rect, 12f, 8f, 10f))
        assertEquals(Handle.TOP_RIGHT, CropMath.hitTest(rect, 88f, 12f, 10f))
        assertEquals(Handle.BOTTOM_LEFT, CropMath.hitTest(rect, 10f, 190f, 10f))
        assertEquals(Handle.BOTTOM_RIGHT, CropMath.hitTest(rect, 90f, 188f, 10f))
    }

    @Test
    fun hitTestFindsEdges() {
        val rect = CropRect(10f, 10f, 90f, 190f)
        assertEquals(Handle.LEFT, CropMath.hitTest(rect, 12f, 100f, 10f))
        assertEquals(Handle.RIGHT, CropMath.hitTest(rect, 88f, 100f, 10f))
        assertEquals(Handle.TOP, CropMath.hitTest(rect, 50f, 12f, 10f))
        assertEquals(Handle.BOTTOM, CropMath.hitTest(rect, 50f, 188f, 10f))
    }

    @Test
    fun cornersWinOverEdges() {
        val rect = CropRect(10f, 10f, 90f, 190f)
        assertEquals(Handle.TOP_LEFT, CropMath.hitTest(rect, 12f, 12f, 10f))
    }

    @Test
    fun dragEdgeResizesOneSideOnly() {
        val rect = CropRect(10f, 10f, 90f, 190f)
        assertEquals(
            CropRect(20f, 10f, 90f, 190f),
            CropMath.drag(rect, Handle.LEFT, 10f, 50f, bounds, 20f),
        )
        assertEquals(
            CropRect(10f, 10f, 90f, 180f),
            CropMath.drag(rect, Handle.BOTTOM, 50f, -10f, bounds, 20f),
        )
    }

    @Test
    fun dragEdgeRespectsMinSizeAndBounds() {
        val rect = CropRect(10f, 10f, 90f, 190f)
        assertEquals(
            CropRect(10f, 10f, 30f, 190f),
            CropMath.drag(rect, Handle.RIGHT, -500f, 0f, bounds, 20f),
        )
        assertEquals(
            CropRect(10f, 0f, 90f, 190f),
            CropMath.drag(rect, Handle.TOP, 0f, -500f, bounds, 20f),
        )
    }

    @Test
    fun hitTestInsideAndOutside() {
        val rect = CropRect(10f, 10f, 90f, 190f)
        assertEquals(Handle.INSIDE, CropMath.hitTest(rect, 50f, 100f, 10f))
        assertNull(CropMath.hitTest(rect, 99f, 5f, 3f))
    }

    @Test
    fun dragInsideMovesWithoutResizing() {
        val rect = CropRect(10f, 10f, 50f, 50f)
        val moved = CropMath.drag(rect, Handle.INSIDE, 5f, -5f, bounds, 20f)
        assertEquals(CropRect(15f, 5f, 55f, 45f), moved)
    }

    @Test
    fun dragInsideClampsToBounds() {
        val rect = CropRect(10f, 10f, 50f, 50f)
        val moved = CropMath.drag(rect, Handle.INSIDE, -100f, -100f, bounds, 20f)
        assertEquals(CropRect(0f, 0f, 40f, 40f), moved)
    }

    @Test
    fun dragCornerResizes() {
        val rect = CropRect(10f, 10f, 90f, 190f)
        val resized = CropMath.drag(rect, Handle.TOP_LEFT, 10f, 20f, bounds, 20f)
        assertEquals(CropRect(20f, 30f, 90f, 190f), resized)
    }

    @Test
    fun dragCornerRespectsMinSize() {
        val rect = CropRect(10f, 10f, 90f, 190f)
        val resized = CropMath.drag(rect, Handle.BOTTOM_RIGHT, -500f, -500f, bounds, 20f)
        assertEquals(CropRect(10f, 10f, 30f, 30f), resized)
    }

    @Test
    fun dragCornerClampsToBounds() {
        val rect = CropRect(10f, 10f, 90f, 190f)
        val resized = CropMath.drag(rect, Handle.BOTTOM_RIGHT, 500f, 500f, bounds, 20f)
        assertEquals(CropRect(10f, 10f, 100f, 200f), resized)
    }

    @Test
    fun aspectRectFitsWidthLimitedBounds() {
        // bounds 100x200 (tall): a 1:1 rect is width-limited -> 100x100 centered
        val square = CropMath.aspectRect(bounds, 1f)
        assertEquals(CropRect(0f, 50f, 100f, 150f), square)
    }

    @Test
    fun aspectRectFitsHeightLimitedBounds() {
        val wide = CropRect(0f, 0f, 400f, 100f)
        // 1:1 in 400x100 is height-limited -> 100x100 centered
        assertEquals(CropRect(150f, 0f, 250f, 100f), CropMath.aspectRect(wide, 1f))
    }

    @Test
    fun toImageRectMapsDisplayToPixels() {
        // image 1000x2000 displayed at 100x200 offset (0,0): scale 10x
        val displayed = CropRect(0f, 0f, 100f, 200f)
        val crop = CropRect(10f, 20f, 60f, 120f)
        assertEquals(ImageRect(100, 200, 500, 1000), CropMath.toImageRect(crop, displayed, 1000, 2000))
    }

    @Test
    fun toImageRectHandlesDisplayOffset() {
        // same image but displayed shifted by (50, 25)
        val displayed = CropRect(50f, 25f, 150f, 225f)
        val crop = CropRect(60f, 45f, 110f, 145f)
        assertEquals(ImageRect(100, 200, 500, 1000), CropMath.toImageRect(crop, displayed, 1000, 2000))
    }

    @Test
    fun toImageRectClampsToImageEdges() {
        val displayed = CropRect(0f, 0f, 100f, 200f)
        val crop = CropRect(0f, 0f, 100f, 200f)
        assertEquals(ImageRect(0, 0, 1000, 2000), CropMath.toImageRect(crop, displayed, 1000, 2000))
    }
}
