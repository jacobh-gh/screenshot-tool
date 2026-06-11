package dev.jacob.screenshottoolbar.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.graphics.applyCanvas
import dev.jacob.screenshottoolbar.crop.CopyRange
import dev.jacob.screenshottoolbar.crop.Frame
import dev.jacob.screenshottoolbar.crop.Stitcher
import java.util.concurrent.Executors

/**
 * Incremental "capture more": each [captureMore] call scrolls the foreground
 * app a partial step and captures one more frame, accumulating them. The user
 * controls length by tapping "Capture more" again (or not). [finalizeAndSave]
 * stitches everything captured so far into one tall PNG in Pictures/Screenshots
 * (re-triggering the normal popup for crop/share).
 *
 * Frames are captured with our UI hidden, so nothing of ours pollutes the
 * stitch. Overlap detection ignores sticky headers/bars via a content-band
 * match (see Stitcher.findScrollOffset).
 */
class ScrollCaptureSession(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "ScrollCapture"
        // Smaller partial scroll → larger overlap (~55%) between frames, giving
        // the row matcher more rows to lock onto so the seam lands precisely.
        // Costs one extra tap for the same coverage; reliability wins here.
        private const val SCROLL_FRACTION = 0.45f
        private const val SETTLE_MS = 650L          // wait for scroll + fling to settle
        // Per-row luminance differs by more than a hair between frames because
        // of sub-pixel text rendering when content shifts, so the tolerance has
        // to be generous; the band match still needs MIN_MATCH_ROWS aligned rows
        // to accept an offset, which keeps false positives out.
        private const val ROW_TOLERANCE = 16
        private const val MIN_MATCH_ROWS = 60
        // The OS gesture pill / nav bar at the very bottom isn't reliably caught
        // by static-row detection (content scrolling behind it changes those
        // pixels), so force it stripped from every frame but the last. Kept tiny
        // (≈2.5% of height) and far below the per-step scroll distance, so it is
        // always re-revealed by the next frame — never drops real content.
        private const val GESTURE_BAR_FRAC = 0.025f
        private const val SYSTEMUI = "com.android.systemui"
        private const val PREVIEW_ID = "$SYSTEMUI:id/screenshot_preview"
        private const val STATIC_ID = "$SYSTEMUI:id/screenshot_static"
        // How long to wait for the system screenshot chrome to slide away before
        // grabbing frame 0, polled in small steps so we proceed the instant it's
        // gone rather than always paying a fixed delay.
        private const val CHROME_POLL_MS = 80L
        private const val CHROME_MAX_WAIT_MS = 1200L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val captureExec = Executors.newSingleThreadExecutor()
    private val bitmaps = mutableListOf<Bitmap>()
    private val frames = mutableListOf<Frame>()
    private var lastSavedUri: Uri? = null

    /**
     * Capture the current screen as the first frame (no scroll). The caller
     * (OverlayToolbar.dismissThenRun) guarantees our overlay is already
     * detached before invoking this. We additionally wait until the system
     * screenshot chrome has finished sliding away, so its half-dismissed
     * controls aren't baked into frame 0.
     */
    fun captureFirst(onResult: (frameCount: Int) -> Unit) {
        capture { onResult(bitmaps.size) }
    }

    /**
     * True while anything that must NOT be in a captured frame is on screen:
     * SystemUI's screenshot preview/static chrome, OR any window belonging to
     * us (our floating toolbar). Checking our own window directly — rather than
     * trusting a fixed post-dismiss delay — guarantees the toolbar is never
     * baked into a frame no matter how the show/dismiss timing races.
     */
    private fun obstructionPresent(): Boolean = service.windows.any { w ->
        val root = w.root ?: return@any false
        when (root.packageName) {
            service.packageName -> true // our own overlay toolbar
            SYSTEMUI -> root.findAccessibilityNodeInfosByViewId(PREVIEW_ID).isNotEmpty() ||
                root.findAccessibilityNodeInfosByViewId(STATIC_ID).isNotEmpty()
            else -> false
        }
    }

    /** Run [action] once nothing of ours/SystemUI's chrome is on screen, or at [deadline]. */
    private fun whenChromeGone(deadline: Long, action: () -> Unit) {
        if (!obstructionPresent() || System.currentTimeMillis() >= deadline) {
            action()
        } else {
            handler.postDelayed({ whenChromeGone(deadline, action) }, CHROME_POLL_MS)
        }
    }

    /** Scroll one partial step, then capture another frame. */
    fun captureMore(onResult: (frameCount: Int, advanced: Boolean) -> Unit) {
        if (!scrollForward()) {
            onResult(bitmaps.size, false)
            return
        }
        handler.postDelayed({
            capture {
                // "Advanced" = the screen actually changed. Use exact frame
                // equality, not the band-match offset: the offset heuristic can
                // fail to LOCATE the scroll distance even when content clearly
                // moved, and falsely reporting "reached the end" would stop a
                // legitimate long capture. Identical signatures = nothing moved.
                val advanced = if (frames.size >= 2) {
                    !frames[frames.size - 2].rows.contentEquals(frames.last().rows)
                } else true
                onResult(bitmaps.size, advanced)
            }
        }, SETTLE_MS)
    }

    /**
     * Capture one frame, but only once nothing of ours (the toolbar) or the
     * system screenshot chrome is on screen — polled up to [CHROME_MAX_WAIT_MS].
     * This is the single chokepoint that keeps our own UI out of every frame,
     * regardless of how the dismiss/re-show timing races around it.
     */
    private fun capture(done: () -> Unit) {
        whenChromeGone(deadline = System.currentTimeMillis() + CHROME_MAX_WAIT_MS) {
            captureNow(done)
        }
    }

    private fun captureNow(done: () -> Unit) {
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            captureExec,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val wrapped = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val bmp = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                    wrapped?.recycle()
                    result.hardwareBuffer.close()
                    if (bmp != null) handler.post {
                        bitmaps += bmp
                        frames += bmp.toFrame()
                        done()
                    } else handler.post(done)
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "takeScreenshot failed code=$errorCode")
                    handler.post(done)
                }
            },
        )
    }

    private fun scrollForward(): Boolean {
        // Pick the LARGEST scrollable region in the foreground app, not the
        // first one found: nested-scroll layouts (a comment thread inside a
        // CoordinatorLayout) often expose a small scrollable container whose
        // own swipe is consumed by a collapsing header and doesn't move the
        // list. The biggest scrollable is the actual content list.
        val node = service.windows
            .asSequence()
            .mapNotNull { it.root }
            .filter { it.packageName != service.packageName && it.packageName != "com.android.systemui" }
            .flatMap { collectScrollables(it).asSequence() }
            .maxByOrNull { n -> Rect().also { n.getBoundsInScreen(it) }.let { it.width() * it.height() } }
        val bounds = node?.let { Rect().also { r -> it.getBoundsInScreen(r) } }
        if (bounds == null || bounds.isEmpty) return false
        val x = bounds.exactCenterX()
        // Start near the bottom, end near the top → a long, slow drag the system
        // reads as a deliberate scroll on every tap (a too-short/too-fast stroke
        // sometimes registered only on the first tap).
        val from = bounds.bottom - bounds.height() * 0.08f
        val to = (from - bounds.height() * SCROLL_FRACTION).coerceAtLeast(bounds.top + 1f)
        val path = Path().apply { moveTo(x, from); lineTo(x, to) }
        return service.dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 450))
                .build(),
            null, null,
        )
    }

    private fun collectScrollables(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) {
            if (n.isScrollable) found += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(node)
        return found
    }

    /** Free captured frames when the session ends (a new screenshot starts a
     *  fresh one). Does NOT delete the saved stitched file — that's a finished
     *  result the user keeps. */
    fun recycle() {
        bitmaps.forEach { it.recycle() }
        bitmaps.clear()
        frames.clear()
    }

    /**
     * Stitch all captured frames and save to Pictures/Screenshots, deleting
     * the file saved by the previous call so the session leaves exactly one
     * growing image. Returns the new Uri (or null).
     */
    fun saveStitchedReplacing(): Uri? {
        if (bitmaps.isEmpty()) return null
        val plan = stitchPlanForCaptured()
        val uri = runCatching { saveStitched(plan) }.getOrElse {
            Log.e(TAG, "stitch/save failed", it); null
        }
        // Remove the previous stitched file (the new one supersedes it).
        lastSavedUri?.let { old -> runCatching { service.contentResolver.delete(old, null, null) } }
        lastSavedUri = uri
        return uri
    }

    private fun stitchPlanForCaptured(): List<CopyRange> {
        // Single frame: passthrough.
        if (bitmaps.size == 1) return listOf(CopyRange(0, 0, bitmaps[0].height))
        // Delegate to the pure, unit-tested planner (band match + static-chrome
        // handling + end-of-content stop) rather than duplicating that logic.
        val gestureBar = (frames[0].height * GESTURE_BAR_FRAC).toInt()
        val plan = Stitcher.stitchPlan(
            frames,
            minOverlap = MIN_MATCH_ROWS,
            tolerance = ROW_TOLERANCE,
            minBottomChrome = gestureBar,
        )
        // Stitcher.stitchPlan stops at the first non-advancing frame. If the
        // band match couldn't find an overlap for a frame that we KNOW came
        // from a real scroll (the scroll gesture moved content), we'd otherwise
        // silently drop everything past it and the result collapses to frame 0.
        // Guard against that: if the plan only contains the first frame yet we
        // captured several, fall back to concatenating each frame's lower
        // portion so the user still gets a longer image instead of nothing.
        if (plan.size == 1 && bitmaps.size > 1) {
            Log.w(TAG, "overlap match failed for all ${bitmaps.size} frames; using fallback concat")
            return fallbackConcatPlan()
        }
        return plan
    }

    /**
     * When luminance matching can't find overlaps (e.g. heavy anti-aliasing or
     * an unusual layout), still produce a growing image: keep the first frame
     * whole and append the bottom [SCROLL_FRACTION] of each later frame (the
     * region most likely to be newly revealed by a partial scroll). Imperfect
     * but far better than discarding the capture.
     */
    private fun fallbackConcatPlan(): List<CopyRange> {
        val h = frames[0].height
        val newRows = (h * SCROLL_FRACTION).toInt()
        val plan = mutableListOf(CopyRange(0, 0, h))
        for (i in 1 until frames.size) {
            val top = (frames[i].height - newRows).coerceAtLeast(0)
            if (top < frames[i].height) plan += CopyRange(i, top, frames[i].height)
        }
        return plan
    }

    private fun saveStitched(plan: List<CopyRange>): Uri? {
        val width = bitmaps.first().width
        val totalHeight = plan.sumOf { it.srcBottom - it.srcTop }
        val out = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        out.applyCanvas {
            var y = 0
            plan.forEach { range ->
                val h = range.srcBottom - range.srcTop
                drawBitmap(
                    bitmaps[range.frameIndex],
                    Rect(0, range.srcTop, width, range.srcBottom),
                    Rect(0, y, width, y + h),
                    null,
                )
                y += h
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_long_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = service.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out.recycle()
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}

/** Reduce a bitmap to one luminance value per row (sampled across the width). */
private fun Bitmap.toFrame(): Frame {
    val step = (width / 64).coerceAtLeast(1)
    val rows = IntArray(height)
    for (y in 0 until height) {
        var sum = 0L
        var n = 0
        var x = 0
        while (x < width) {
            val p = getPixel(x, y)
            sum += (((p shr 16) and 0xFF) * 30 + ((p shr 8) and 0xFF) * 59 + (p and 0xFF) * 11) / 100
            n++
            x += step
        }
        rows[y] = (sum / n).toInt()
    }
    return Frame(rows, height)
}
