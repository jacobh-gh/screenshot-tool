package dev.jacob.screenshottoolbar.popup

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.jacob.screenshottoolbar.access.ToolbarAccessibilityService
import dev.jacob.screenshottoolbar.crop.CropActivity

/**
 * Floating One-UI-style toolbar shown over whatever app is open.
 * Requires SYSTEM_ALERT_WINDOW; caller checks that. Falls back to the
 * notification popup if the window can't be added.
 */
class OverlayToolbar(private val context: Context) {

    /**
     * @param uri the screenshot to act on directly, or null when triggered by
     *   the accessibility service for a Private Space screenshot whose file
     *   isn't readable here.
     * @param captureCount frames captured so far (badge shown when > 1).
     * @param captureSessionActive when true, a capture-more session is in
     *   progress: Crop/Share/Delete act on the freshly-stitched result, which
     *   is finalized lazily on tap (keeps the capture loop snappy).
     */
    fun show(uri: Uri?, captureCount: Int = 1, captureSessionActive: Boolean = false) {
        val wm = context.getSystemService(WindowManager::class.java)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xEE202124.toInt())
            }
        }

        val handler = Handler(Looper.getMainLooper())
        var removed = false
        val dismiss = {
            if (!removed) {
                removed = true
                runCatching { wm.removeView(root) }
            }
        }

        // Remove the overlay and run [block] only AFTER the window is truly
        // detached (plus a couple of frames for the compositor) — otherwise a
        // capture fired right after removeView() still bakes the toolbar into
        // the screenshot. Event-driven, so it's both reliable and fast.
        fun dismissThenRun(block: () -> Unit) {
            handler.removeCallbacksAndMessages(null) // cancel auto-dismiss
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    root.removeOnAttachStateChangeListener(this)
                    handler.postDelayed(block, 80)
                }
            })
            dismiss()
        }

        if (uri != null) {
            root.addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                runCatching {
                    setImageBitmap(context.contentResolver.loadThumbnail(uri, Size(256, 256), null))
                }
            })
        }

        if (captureCount > 1) {
            root.addView(TextView(context).apply {
                text = "×$captureCount"
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(0, 0, dp(8), 0)
            })
        }

        fun addButton(iconRes: Int, desc: String, onClick: () -> Unit) {
            root.addView(ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                setImageResource(iconRes)
                background = null
                contentDescription = desc
                setColorFilter(0xFFFFFFFF.toInt())
                setOnClickListener {
                    onClick()
                    dismiss()
                }
            })
        }

        // The URI to crop/share/delete: the direct screenshot, or — during a
        // capture-more session — the freshly stitched result, finalized lazily
        // on tap so the capture loop stays snappy.
        fun targetUri(): Uri? =
            if (captureSessionActive) ToolbarAccessibilityService.finalizeStitch() else uri

        val canActOnImage = uri != null || captureSessionActive
        val a11yUsable = ToolbarAccessibilityService.isUsable(context)

        fun openCrop(cropUri: Uri) {
            context.startActivity(Intent(context, CropActivity::class.java).apply {
                data = cropUri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        fun shareImage(shareUri: Uri) {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, shareUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    null,
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        // For a Private Space screenshot (no readable original) with no capture
        // session yet, Crop/Share act on a single fresh frame of the current
        // screen — captured into this profile so it can be acted on. Covers
        // "I don't need to scroll".
        fun captureSingleThen(use: (Uri) -> Unit) {
            dismissThenRun {
                ToolbarAccessibilityService.captureSingle {
                    ToolbarAccessibilityService.finalizeStitch()?.let { stitched ->
                        handler.post { use(stitched) }
                    }
                }
            }
        }

        if (canActOnImage) {
            addButton(android.R.drawable.ic_menu_crop, "Crop") { targetUri()?.let(::openCrop) }
        } else if (a11yUsable) {
            addButton(android.R.drawable.ic_menu_crop, "Crop") { captureSingleThen(::openCrop) }
        }
        // Capture more: scrolls + captures one more frame (toolbar dismissed so
        // it isn't in the shot), then re-shows immediately with the new count.
        // Works for the foreground app in ANY profile via the bound
        // main-profile service. Stitching is deferred to Crop/Share.
        if (ToolbarAccessibilityService.isUsable(context)) {
            addButton(android.R.drawable.ic_menu_add, "Capture more") {
                dismissThenRun {
                    val started = ToolbarAccessibilityService.captureMore { count ->
                        OverlayToolbar(context).show(
                            uri = null, captureCount = count, captureSessionActive = true,
                        )
                    }
                    if (!started) {
                        android.widget.Toast.makeText(
                            context,
                            "Capture more needs the accessibility service active",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
        if (canActOnImage) {
            addButton(android.R.drawable.ic_menu_share, "Share") { targetUri()?.let(::shareImage) }
        } else if (a11yUsable) {
            // Private Space, no capture session yet: share a single fresh frame
            // (no reason to hide Share when Crop is already offered this way).
            addButton(android.R.drawable.ic_menu_share, "Share") { captureSingleThen(::shareImage) }
        }
        if (canActOnImage) {
            // Delete only the original (a capture-more result is a new file the
            // user just made — Dismiss is enough there).
            if (uri != null && !captureSessionActive) {
                addButton(android.R.drawable.ic_menu_delete, "Delete") {
                    context.startActivity(Intent(context, DeleteActivity::class.java).apply {
                        data = uri
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            }
        }
        addButton(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss") { }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(96)
        }

        runCatching { wm.addView(root, params) }.onFailure {
            Log.w("OverlayToolbar", "addView failed, falling back to notification", it)
            if (uri != null) NotificationPopup.show(context, uri)
            return
        }
        handler.postDelayed({ dismiss() }, 6_000)
    }
}
