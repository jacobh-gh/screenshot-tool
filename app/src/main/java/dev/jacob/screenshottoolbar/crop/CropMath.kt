package dev.jacob.screenshottoolbar.crop

import kotlin.math.abs
import kotlin.math.roundToInt

/** Axis-aligned rect in display (float) coordinates. */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Crop region in image pixel coordinates. */
data class ImageRect(val x: Int, val y: Int, val width: Int, val height: Int)

enum class Handle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, LEFT, TOP, RIGHT, BOTTOM, INSIDE }

object CropMath {

    /**
     * Which handle (or the interior) a touch at (x, y) grabs, or null.
     * Corners win over edges, edges win over INSIDE.
     */
    fun hitTest(rect: CropRect, x: Float, y: Float, touchRadius: Float): Handle? {
        fun near(px: Float, py: Float) = abs(x - px) <= touchRadius && abs(y - py) <= touchRadius
        val withinX = x in rect.left..rect.right
        val withinY = y in rect.top..rect.bottom
        return when {
            near(rect.left, rect.top) -> Handle.TOP_LEFT
            near(rect.right, rect.top) -> Handle.TOP_RIGHT
            near(rect.left, rect.bottom) -> Handle.BOTTOM_LEFT
            near(rect.right, rect.bottom) -> Handle.BOTTOM_RIGHT
            withinY && abs(x - rect.left) <= touchRadius -> Handle.LEFT
            withinY && abs(x - rect.right) <= touchRadius -> Handle.RIGHT
            withinX && abs(y - rect.top) <= touchRadius -> Handle.TOP
            withinX && abs(y - rect.bottom) <= touchRadius -> Handle.BOTTOM
            withinX && withinY -> Handle.INSIDE
            else -> null
        }
    }

    /** Apply a drag delta to a handle, clamped to bounds and a minimum size. */
    fun drag(
        rect: CropRect,
        handle: Handle,
        dx: Float,
        dy: Float,
        bounds: CropRect,
        minSize: Float,
    ): CropRect = when (handle) {
        Handle.INSIDE -> {
            val cdx = dx.coerceIn(bounds.left - rect.left, bounds.right - rect.right)
            val cdy = dy.coerceIn(bounds.top - rect.top, bounds.bottom - rect.bottom)
            CropRect(rect.left + cdx, rect.top + cdy, rect.right + cdx, rect.bottom + cdy)
        }
        Handle.TOP_LEFT -> rect.copy(
            left = (rect.left + dx).coerceIn(bounds.left, rect.right - minSize),
            top = (rect.top + dy).coerceIn(bounds.top, rect.bottom - minSize),
        )
        Handle.TOP_RIGHT -> rect.copy(
            right = (rect.right + dx).coerceIn(rect.left + minSize, bounds.right),
            top = (rect.top + dy).coerceIn(bounds.top, rect.bottom - minSize),
        )
        Handle.BOTTOM_LEFT -> rect.copy(
            left = (rect.left + dx).coerceIn(bounds.left, rect.right - minSize),
            bottom = (rect.bottom + dy).coerceIn(rect.top + minSize, bounds.bottom),
        )
        Handle.BOTTOM_RIGHT -> rect.copy(
            right = (rect.right + dx).coerceIn(rect.left + minSize, bounds.right),
            bottom = (rect.bottom + dy).coerceIn(rect.top + minSize, bounds.bottom),
        )
        Handle.LEFT -> rect.copy(
            left = (rect.left + dx).coerceIn(bounds.left, rect.right - minSize),
        )
        Handle.RIGHT -> rect.copy(
            right = (rect.right + dx).coerceIn(rect.left + minSize, bounds.right),
        )
        Handle.TOP -> rect.copy(
            top = (rect.top + dy).coerceIn(bounds.top, rect.bottom - minSize),
        )
        Handle.BOTTOM -> rect.copy(
            bottom = (rect.bottom + dy).coerceIn(rect.top + minSize, bounds.bottom),
        )
    }

    /** Largest rect of the given aspect (width/height) centered within bounds. */
    fun aspectRect(bounds: CropRect, aspect: Float): CropRect {
        val (w, h) = if (bounds.width / bounds.height > aspect) {
            bounds.height * aspect to bounds.height
        } else {
            bounds.width to bounds.width / aspect
        }
        val cx = (bounds.left + bounds.right) / 2f
        val cy = (bounds.top + bounds.bottom) / 2f
        return CropRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    /** Map a crop rect in display coords to image pixel coords, clamped inside the image. */
    fun toImageRect(crop: CropRect, displayed: CropRect, imageWidth: Int, imageHeight: Int): ImageRect {
        val sx = imageWidth / displayed.width
        val sy = imageHeight / displayed.height
        val x = ((crop.left - displayed.left) * sx).roundToInt().coerceIn(0, imageWidth - 1)
        val y = ((crop.top - displayed.top) * sy).roundToInt().coerceIn(0, imageHeight - 1)
        val w = (crop.width * sx).roundToInt().coerceIn(1, imageWidth - x)
        val h = (crop.height * sy).roundToInt().coerceIn(1, imageHeight - y)
        return ImageRect(x, y, w, h)
    }
}
