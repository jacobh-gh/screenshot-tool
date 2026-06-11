package dev.jacob.screenshottoolbar.detect

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotFilterTest {

    private val now = 1_000_000L

    private fun row(
        uri: String = "content://media/external/images/media/1",
        path: String? = "Pictures/Screenshots/",
        ageSec: Long = 2,
        pending: Boolean = false,
    ) = MediaRow(uri, path, now - ageSec, pending)

    @Test
    fun acceptsRecentScreenshot() {
        val filter = ScreenshotFilter()
        assertEquals(listOf(row()), filter.newScreenshots(listOf(row()), now))
    }

    @Test
    fun rejectsNonScreenshotPath() {
        val filter = ScreenshotFilter()
        val camera = row(path = "DCIM/Camera/")
        assertEquals(emptyList<MediaRow>(), filter.newScreenshots(listOf(camera), now))
    }

    @Test
    fun rejectsNullPath() {
        val filter = ScreenshotFilter()
        assertEquals(emptyList<MediaRow>(), filter.newScreenshots(listOf(row(path = null)), now))
    }

    @Test
    fun rejectsPendingRow() {
        val filter = ScreenshotFilter()
        assertEquals(emptyList<MediaRow>(), filter.newScreenshots(listOf(row(pending = true)), now))
    }

    @Test
    fun rejectsOldRow() {
        val filter = ScreenshotFilter()
        assertEquals(emptyList<MediaRow>(), filter.newScreenshots(listOf(row(ageSec = 60)), now))
    }

    @Test
    fun dedupesAlreadySeenUri() {
        val filter = ScreenshotFilter()
        filter.newScreenshots(listOf(row()), now)
        assertEquals(emptyList<MediaRow>(), filter.newScreenshots(listOf(row()), now))
    }

    @Test
    fun acceptsDistinctUrisSeparately() {
        val filter = ScreenshotFilter()
        val a = row(uri = "content://media/external/images/media/1")
        val b = row(uri = "content://media/external/images/media/2")
        assertEquals(listOf(a), filter.newScreenshots(listOf(a), now))
        assertEquals(listOf(b), filter.newScreenshots(listOf(a, b), now))
    }

    @Test
    fun caseInsensitiveScreenshotsMatch() {
        val filter = ScreenshotFilter()
        val r = row(path = "Pictures/screenshots/")
        assertEquals(listOf(r), filter.newScreenshots(listOf(r), now))
    }
}
