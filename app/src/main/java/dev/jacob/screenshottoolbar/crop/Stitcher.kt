package dev.jacob.screenshottoolbar.crop

import kotlin.math.abs

/**
 * One captured frame reduced to a per-row luminance signature. Decoupled from
 * Bitmap so the stitching logic is pure and unit-testable; the session code
 * builds a Frame by hashing each row of the captured bitmap.
 */
data class Frame(val rows: IntArray, val height: Int) {
    override fun equals(other: Any?) =
        other is Frame && height == other.height && rows.contentEquals(other.rows)

    override fun hashCode() = 31 * height + rows.contentHashCode()
}

/** A contiguous row range of one frame to copy into the stitched output. */
data class CopyRange(val frameIndex: Int, val srcTop: Int, val srcBottom: Int)

object Stitcher {

    /** Fraction of frame height ignored at each edge of the content region when
     *  matching, so anti-aliased edge rows / gesture-pill fringe don't perturb
     *  the chosen offset. ~1.5% — small enough not to starve the match. */
    const val EDGE_MARGIN_FRAC = 0.015f

    /**
     * Vertical offset within [prev] where [next]'s top row aligns — i.e. how
     * far the content scrolled between frames. Returns null when there's no
     * credible forward match (content unchanged, or scrolled past all overlap
     * = end of content).
     *
     * @param minOverlap minimum number of matching rows required to trust a match.
     * @param tolerance per-row luminance difference still considered "equal".
     */
    fun findOverlap(prev: Frame, next: Frame, minOverlap: Int, tolerance: Int): Int? {
        // Among all offsets > 0 whose every overlapping row is within tolerance,
        // pick the one with the lowest total error (the true scroll distance).
        // offset > 0 means identical frames (no scroll) report null.
        var best: Int? = null
        var bestAvgError = Double.MAX_VALUE
        for (offset in 1..(prev.height - minOverlap)) {
            val overlapRows = minOf(prev.height - offset, next.height)
            if (overlapRows < minOverlap) continue
            val error = rowError(prev, offset, next, 0, overlapRows, tolerance) ?: continue
            // Average error so a short bottom overlap can't win on raw sum alone.
            val avg = error.toDouble() / overlapRows
            if (avg < bestAvgError) {
                bestAvgError = avg
                best = offset
            }
        }
        return best
    }

    /**
     * Scroll offset between two real frames, robust to sticky headers/bars:
     * matches a content band of [prev] (excluding [topMargin]/[bottomMargin])
     * against [next] at each candidate offset, scoring by how many band rows
     * align within tolerance. Returns the offset with the most matches (>=
     * [minMatch]), or null if nothing aligns better than the no-scroll case.
     */
    fun findScrollOffset(
        prev: Frame,
        next: Frame,
        topMargin: Int,
        bottomMargin: Int,
        minMatch: Int,
        tolerance: Int,
    ): Int? {
        val bandTop = topMargin
        val bandBottom = prev.height - bottomMargin
        if (bandBottom - bandTop < minMatch) return null
        var best: Int? = null
        var bestScore = -1.0
        var bestAvgError = Double.MAX_VALUE
        // offset > 0 (must scroll); cap so the band still fits within next.
        for (offset in 1..(prev.height - 1)) {
            var matches = 0
            var compared = 0
            var errorSum = 0L
            for (r in bandTop until bandBottom) {
                val nr = r - offset
                if (nr < topMargin || nr >= next.height - bottomMargin) continue
                compared++
                val d = abs(prev.rows[r] - next.rows[nr])
                if (d <= tolerance) {
                    matches++
                    errorSum += d
                }
            }
            if (matches < minMatch || compared == 0) continue
            // Score by the FRACTION of compared rows that match, not the raw
            // count: a small offset compares more rows and would otherwise win
            // on count alone, biasing toward too-short scrolls (duplicated
            // strips at the seam). Break near-ties by lowest average per-row
            // error so the alignment lands on the true offset, not one a few
            // rows off that happens to clear tolerance.
            val score = matches.toDouble() / compared
            val avgError = errorSum.toDouble() / matches
            val betterScore = score > bestScore + 0.01
            val tieBetterError = score > bestScore - 0.01 && avgError < bestAvgError
            if (betterScore || tieBetterError) {
                bestScore = score
                bestAvgError = avgError
                best = offset
            }
        }
        return best
    }

    /** Sum of per-row abs differences, or null if any row exceeds tolerance. */
    private fun rowError(
        a: Frame, aStart: Int,
        b: Frame, bStart: Int,
        count: Int,
        tolerance: Int,
    ): Long? {
        var sum = 0L
        for (i in 0 until count) {
            val d = abs(a.rows[aStart + i] - b.rows[bStart + i])
            if (d > tolerance) return null
            sum += d
        }
        return sum
    }

    /**
     * Rows at the top and bottom that stay (within [tolerance]) the same across
     * every frame — sticky headers / bottom bars (and chat apps' emoji+input
     * strips) that should appear once, not repeated in every stitched segment.
     * Returns (topCount, bottomCount).
     *
     * Tolerance matters on real captures: a truly-static bar still jitters by a
     * luminance point or two between hardware-buffer copies, and exact equality
     * (tolerance 0) would cut the static run short at the first jittery row,
     * under-detecting the bar and letting it repeat in the stitch.
     */
    fun staticChrome(frames: List<Frame>, tolerance: Int = 0): Pair<Int, Int> {
        if (frames.size < 2) return 0 to 0
        val h = frames.first().height
        fun rowStatic(r: Int) = frames.all { abs(it.rows[r] - frames.first().rows[r]) <= tolerance }
        var top = 0
        while (top < h && rowStatic(top)) top++
        var bottom = 0
        while (bottom < h - top && rowStatic(h - 1 - bottom)) bottom++
        return top to bottom
    }

    /**
     * Plan of which row ranges of which frames compose the tall stitched
     * image. Stops at the first frame that doesn't advance (end of content).
     * Static chrome (sticky header / bottom bar) is emitted ONCE: the top
     * header is kept only on the first segment, and the bottom bar only on the
     * LAST segment (so the stitched image ends cleanly on the bar, instead of
     * the bar floating in the middle after the first frame). Interior frames
     * contribute only their newly-revealed rows below the overlap.
     */
    fun stitchPlan(
        frames: List<Frame>,
        minOverlap: Int,
        tolerance: Int,
        minTopChrome: Int = 0,
        minBottomChrome: Int = 0,
    ): List<CopyRange> {
        if (frames.isEmpty()) return emptyList()
        // Static chrome must be NEARLY identical across frames (only capture
        // jitter), so use a much tighter tolerance than the overlap matcher —
        // otherwise a near-uniform dark background could be mistaken for static
        // and strip real content. A quarter of the overlap tolerance (min 2).
        val staticTol = (tolerance / 4).coerceAtLeast(2)
        val (detectedTop, detectedBottom) = staticChrome(frames, staticTol)
        // Force at least the caller's minimums. The bottom minimum covers the OS
        // gesture pill, whose few rows aren't detected as static because content
        // scrolling behind/around it changes those pixels frame-to-frame, so it
        // otherwise repeats down the stitch. These minimums must stay well below
        // the per-step scroll distance, or trimming them would drop content that
        // the next frame's overlap doesn't re-reveal.
        val topChrome = maxOf(detectedTop, minTopChrome)
        val bottomChrome = maxOf(detectedBottom, minBottomChrome)
        // Match overlap on the scrolling content region only — a sticky header
        // or bottom bar never matches the scrolled content and would otherwise
        // hide the real overlap.
        fun content(f: Frame): Frame {
            val from = topChrome
            val to = f.height - bottomChrome
            return Frame(f.rows.copyOfRange(from, to), to - from)
        }

        // First segment: keep the top header, but strip the bottom bar (it is
        // re-added once on the final segment below).
        // Small margins inside the content region for the matcher to ignore:
        // anti-aliased edge rows and residual pixels around the gesture pill
        // that vary frame-to-frame and would otherwise perturb the offset.
        val edgeMargin = (frames[0].height * EDGE_MARGIN_FRAC).toInt()

        val plan = mutableListOf(CopyRange(0, 0, frames[0].height - bottomChrome))
        var prevContent = content(frames[0])
        for (i in 1 until frames.size) {
            val nextContent = content(frames[i])
            // Robust band match (tolerates outlier rows) on the content region,
            // ignoring the edge margins. findScrollOffset returns the offset in
            // CONTENT coordinates, same basis as findOverlap's result.
            val offset = findScrollOffset(
                prevContent, nextContent,
                topMargin = edgeMargin,
                bottomMargin = edgeMargin,
                minMatch = minOverlap,
                tolerance = tolerance,
            ) ?: break
            // Rows of `next` (full coords) newly revealed below the overlap.
            val overlapRows = prevContent.height - offset
            val newTop = topChrome + overlapRows
            if (newTop < frames[i].height - bottomChrome) {
                plan += CopyRange(i, newTop, frames[i].height - bottomChrome)
            }
            prevContent = nextContent
        }
        // Re-add the static bottom bar once, on the last segment, so the image
        // ends on it. The last segment is whichever frame contributed last
        // (the loop may have stopped early at end-of-content).
        if (bottomChrome > 0) {
            val last = plan.last()
            plan[plan.size - 1] = last.copy(srcBottom = frames[last.frameIndex].height)
        }
        return plan
    }
}
