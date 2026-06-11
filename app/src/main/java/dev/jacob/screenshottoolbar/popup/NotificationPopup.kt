package dev.jacob.screenshottoolbar.popup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.util.Size
import dev.jacob.screenshottoolbar.R
import dev.jacob.screenshottoolbar.crop.CropActivity

object NotificationPopup {
    private const val CHANNEL_ID = "screenshot_popup"

    fun show(context: Context, uri: Uri) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screenshot actions", NotificationManager.IMPORTANCE_HIGH)
        )
        val notifId = uri.hashCode()
        val thumb = runCatching {
            context.contentResolver.loadThumbnail(uri, Size(960, 960), null)
        }.getOrNull()

        val shareIntent = Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            null,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val deleteIntent = Intent(context, DeleteActivity::class.java).apply {
            data = uri
            putExtra(DeleteActivity.EXTRA_NOTIFICATION_ID, notifId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val cropIntent = Intent(context, CropActivity::class.java).apply {
            data = uri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Screenshot taken")
            .setContentText("Crop, share, or delete")
            .setAutoCancel(true)
            .addAction(action(context, "Crop", cropIntent, notifId + 3))
            .addAction(action(context, "Share", shareIntent, notifId + 1))
            .addAction(action(context, "Delete", deleteIntent, notifId + 2))
        if (thumb != null) {
            builder.style = Notification.BigPictureStyle().bigPicture(thumb)
        }
        nm.notify(notifId, builder.build())
    }

    fun action(context: Context, title: String, intent: Intent, requestCode: Int): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_launcher),
            title,
            PendingIntent.getActivity(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()
}
