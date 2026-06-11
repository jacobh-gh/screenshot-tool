package dev.jacob.screenshottoolbar.popup

import android.content.Context
import android.net.Uri
import android.provider.Settings

object PopupPresenter {
    /**
     * Show the popup for a known screenshot [uri] (the main-profile
     * DetectionService path). [captureCount] frames captured so far in a
     * capture-more session (1 = a normal single screenshot).
     */
    fun show(context: Context, uri: Uri, captureCount: Int = 1) {
        if (Settings.canDrawOverlays(context)) {
            OverlayToolbar(context).show(uri, captureCount)
        } else {
            NotificationPopup.show(context, uri)
        }
    }

    /**
     * Show the popup from the accessibility service, which triggers for
     * screenshots in EVERY profile. [uri] is null for a Private Space
     * screenshot (its file lives in that profile and isn't readable here) —
     * the overlay then offers only Capture more, which captures fresh frames
     * and saves the stitched result to this (main) profile.
     */
    fun showFromAccessibility(context: Context, uri: Uri?) {
        if (uri != null) {
            show(context, uri)
        } else if (Settings.canDrawOverlays(context)) {
            OverlayToolbar(context).show(uri = null, captureCount = 1)
        }
        // No URI and no overlay permission → nothing we can usefully show.
    }
}
