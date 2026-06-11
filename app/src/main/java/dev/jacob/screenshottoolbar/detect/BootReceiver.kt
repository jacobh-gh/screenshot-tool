package dev.jacob.screenshottoolbar.detect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("autostart", false)) {
            DetectionService.start(context)
        }
    }
}
