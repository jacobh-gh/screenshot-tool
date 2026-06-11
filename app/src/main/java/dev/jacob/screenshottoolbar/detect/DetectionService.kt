package dev.jacob.screenshottoolbar.detect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import dev.jacob.screenshottoolbar.R
import dev.jacob.screenshottoolbar.access.ToolbarAccessibilityService
import dev.jacob.screenshottoolbar.popup.PopupPresenter

class DetectionService : Service() {

    companion object {
        private const val TAG = "DetectionService"
        private const val CHANNEL_ID = "detection"
        private const val NOTIF_ID = 1

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DetectionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DetectionService::class.java))
        }
    }

    private val filter = ScreenshotFilter()
    private lateinit var observer: ContentObserver

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Detection service", NotificationManager.IMPORTANCE_MIN)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Watching for screenshots")
            .build()
        startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                checkForScreenshots()
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
        )
        isRunning = true
        Log.i(TAG, "Detection service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    private fun checkForScreenshots() {
        val nowSec = System.currentTimeMillis() / 1000
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.IS_PENDING,
        )
        val rows = mutableListOf<MediaRow>()
        // The external volume can be transiently unavailable (e.g. while a
        // profile is starting/stopping), which makes this query throw
        // "Volume external_primary not found". Don't let that crash the
        // service — just skip this change event.
        val cursor = try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Images.Media.DATE_ADDED} >= ?",
                arrayOf((nowSec - 10).toString()),
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "MediaStore query failed (volume unavailable?), skipping", e)
            return
        }
        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val pendingCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)
                )
                rows += MediaRow(
                    uri = uri.toString(),
                    relativePath = c.getString(pathCol),
                    dateAddedEpochSec = c.getLong(dateCol),
                    isPending = c.getInt(pendingCol) == 1,
                )
            }
        }
        filter.newScreenshots(rows, nowSec).forEach { row ->
            Log.i(TAG, "Screenshot detected: ${row.uri} (${row.relativePath})")
            onScreenshot(Uri.parse(row.uri))
        }
    }

    private fun onScreenshot(uri: Uri) {
        // When the accessibility service is bound it drives the popup for ALL
        // screenshots (it sees every profile's screenshot via SystemUI), so
        // skip here to avoid a duplicate popup. Without accessibility, this is
        // the only popup path (main-profile screenshots, no capture more).
        if (ToolbarAccessibilityService.isUsable(this)) return
        PopupPresenter.show(this, uri)
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        isRunning = false
        Log.i(TAG, "Detection service stopped")
        super.onDestroy()
    }
}
