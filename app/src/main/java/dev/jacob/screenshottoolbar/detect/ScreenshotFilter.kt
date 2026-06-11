package dev.jacob.screenshottoolbar.detect

/** One row from a MediaStore.Images query, decoupled from Android types for testability. */
data class MediaRow(
    val uri: String,
    val relativePath: String?,
    val dateAddedEpochSec: Long,
    val isPending: Boolean,
)

/**
 * Stateful filter: returns only rows that are finished, recent screenshots
 * not yet handed out by a previous call. Not thread-safe; call from one thread.
 */
class ScreenshotFilter(
    private val recencyWindowSec: Long = 10,
    private val rememberLimit: Int = 64,
) {
    private val seen = ArrayDeque<String>()

    fun newScreenshots(rows: List<MediaRow>, nowEpochSec: Long): List<MediaRow> =
        rows.filter { row ->
            !row.isPending &&
                row.relativePath?.contains("Screenshots", ignoreCase = true) == true &&
                nowEpochSec - row.dateAddedEpochSec <= recencyWindowSec &&
                row.uri !in seen
        }.onEach { remember(it.uri) }

    private fun remember(uri: String) {
        seen.addLast(uri)
        while (seen.size > rememberLimit) seen.removeFirst()
    }
}
