package dev.jacob.screenshottoolbar.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.jacob.screenshottoolbar.detect.DetectionService

class ToggleTileService : TileService() {

    override fun onStartListening() {
        updateState()
    }

    override fun onClick() {
        // Set the tile state optimistically: the service starts asynchronously,
        // so reading isRunning right after start() would still report false.
        val starting = !DetectionService.isRunning
        if (starting) {
            DetectionService.start(this)
        } else {
            DetectionService.stop(this)
        }
        qsTile?.apply {
            state = if (starting) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    private fun updateState() {
        qsTile?.apply {
            state = if (DetectionService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
