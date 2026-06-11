package dev.jacob.screenshottoolbar.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import dev.jacob.screenshottoolbar.detect.DetectionService
import dev.jacob.screenshottoolbar.popup.PopupPresenter

class ToolbarAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ToolbarAccessibility"
        private const val SYSTEMUI = "com.android.systemui"
        private const val PREVIEW_ID = "$SYSTEMUI:id/screenshot_preview"
        private const val STATIC_ID = "$SYSTEMUI:id/screenshot_static"
        // How long to wait for the screenshot preview window to render after a
        // SystemUI event before concluding "this wasn't a screenshot".
        private const val PREVIEW_WAIT_MS = 1200L
        private const val PREVIEW_POLL_MS = 100L

        @Volatile
        var instance: ToolbarAccessibilityService? = null
            private set

        /**
         * Whether our accessibility service is granted, per the system — works
         * across processes and profiles (Private Space), unlike a static
         * instance check which only reflects the current process. Used for the
         * status row and to decide whether to offer "Capture more".
         */
        fun isGranted(context: Context): Boolean {
            val am = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            return am.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ).any { it.resolveInfo.serviceInfo.packageName == context.packageName }
        }

        /**
         * Whether the accessibility-backed features (chrome dismiss, capture
         * more) can actually run here: granted AND a live bound service exists.
         * In the Private Space profile the service does not bind even when
         * enabled, so `isGranted` is true but `instance` is null — this returns
         * false there, letting the UI degrade honestly. See research notes.
         */
        fun isUsable(context: Context): Boolean = isGranted(context) && instance != null

        /**
         * Add one more frame to the capture-more session for [seedUri] (the
         * original screenshot). Starts a session on first call. [onResult] gets
         * the running frame count and the stitched Uri once more frames exist.
         */
        /** Capture one more frame; [onCaptured] fires right after capture with
         *  the running frame count (stitching is deferred to [finalizeStitch]
         *  so the toolbar can reappear immediately). */
        fun captureMore(onCaptured: (frameCount: Int) -> Unit): Boolean {
            val svc = instance ?: return false
            svc.captureMore(onCaptured)
            return true
        }

        /** Capture just the current screen (one frame, no scroll) into a fresh
         *  session — used to crop/share a Private Space screenshot whose
         *  original file isn't readable here. */
        fun captureSingle(onCaptured: () -> Unit): Boolean {
            val svc = instance ?: return false
            svc.session?.recycle()
            svc.session = ScrollCaptureSession(svc)
            svc.session!!.captureFirst { onCaptured() }
            return true
        }

        /** Stitch the current session's frames and save (replacing the prior
         *  save). Called lazily when the user taps Crop/Share so the visible
         *  capture loop isn't slowed by PNG encoding. */
        fun finalizeStitch(): Uri? = instance?.session?.saveStitchedReplacing()

        fun hasActiveSession(): Boolean = instance?.session != null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var session: ScrollCaptureSession? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "Accessibility service destroyed")
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    // Debounce: SystemUI fires several window events per screenshot.
    private var lastPreviewHandledAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != SYSTEMUI) return
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        // Only the bound (main-profile) service drives this. It sees the
        // SystemUI screenshot preview for screenshots in EVERY profile, so it
        // is our universal trigger — including Private Space, where no
        // per-profile service can bind.
        if (!DetectionService.isRunning) return

        // SystemUI fires window events for MANY things that are NOT screenshots
        // — the volume panel, the notification shade, QS tiles, status-bar
        // updates. Acting on the package alone popped the toolbar at random (on
        // volume presses, etc.). Only proceed once the actual screenshot-preview
        // window is on screen. It can render a beat after the first event, so
        // poll briefly (matching the chrome-dismiss timing) rather than checking
        // only the current instant, so real screenshots aren't missed.
        whenScreenshotPreviewAppears {
            if (prefs.getBoolean("hide_system_chrome", false)) {
                for (delay in longArrayOf(0, 250, 800)) {
                    handler.postDelayed({ dismissSystemChrome() }, delay)
                }
            }
            // Debounce only on a CONFIRMED screenshot, so a real screenshot
            // shortly after unrelated SystemUI activity isn't swallowed.
            val now = System.currentTimeMillis()
            if (now - lastPreviewHandledAt < 2500) return@whenScreenshotPreviewAppears
            lastPreviewHandledAt = now
            resolveAndShow(attempt = 0)
        }
    }

    /** True if a SystemUI window containing the screenshot preview/static view
     *  is currently present — the reliable "a screenshot was just taken"
     *  signal, as opposed to any other SystemUI window event. */
    private fun screenshotPreviewPresent(): Boolean = windows.any { w ->
        val root = w.root ?: return@any false
        root.packageName == SYSTEMUI &&
            (root.findAccessibilityNodeInfosByViewId(PREVIEW_ID).isNotEmpty() ||
                root.findAccessibilityNodeInfosByViewId(STATIC_ID).isNotEmpty())
    }

    // Guards against stacking multiple polling chains from a burst of events.
    private var awaitingPreview = false

    /**
     * Run [onConfirmed] once the screenshot-preview window appears, polling for
     * up to [PREVIEW_WAIT_MS]. If it never appears, this was not a screenshot
     * (volume panel, shade, etc.) and nothing happens — no spurious popup.
     */
    private fun whenScreenshotPreviewAppears(onConfirmed: () -> Unit) {
        if (screenshotPreviewPresent()) { onConfirmed(); return }
        if (awaitingPreview) return
        awaitingPreview = true
        val deadline = System.currentTimeMillis() + PREVIEW_WAIT_MS
        fun poll() {
            if (screenshotPreviewPresent()) {
                awaitingPreview = false
                onConfirmed()
            } else if (System.currentTimeMillis() >= deadline) {
                awaitingPreview = false // not a screenshot; stay quiet
            } else {
                handler.postDelayed({ poll() }, PREVIEW_POLL_MS)
            }
        }
        handler.postDelayed({ poll() }, PREVIEW_POLL_MS)
    }

    /**
     * A screenshot was just taken (in any profile). Look for its URI in this
     * (main) profile's MediaStore, retrying because the row is written
     * asynchronously (IS_PENDING) after the preview appears. If found, it's a
     * main-profile screenshot → full popup. If still absent after retries,
     * it's a Private Space screenshot (saved to that profile's storage,
     * invisible here) → offer Capture more, which captures fresh frames and
     * saves the stitched result to this profile.
     */
    private fun resolveAndShow(attempt: Int) {
        // A new screenshot starts a fresh capture-more session — otherwise the
        // next "Capture more" would append to the previous screenshot's frames
        // (mangled stitch / runaway count).
        if (attempt == 0) endSession()
        val uri = recentScreenshotUri()
        if (uri != null || attempt >= 3) {
            PopupPresenter.showFromAccessibility(this, uri)
        } else {
            handler.postDelayed({ resolveAndShow(attempt + 1) }, 450)
        }
    }

    private fun endSession() {
        session?.recycle()
        session = null
    }

    private fun recentScreenshotUri(): Uri? {
        val nowSec = System.currentTimeMillis() / 1000
        return contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.IS_PENDING} = 0",
            arrayOf("%Screenshots%", (nowSec - 10).toString()),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            if (c.moveToFirst()) {
                android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)
                )
            } else null
        }
    }

    private fun dismissSystemChrome() {
        windows.forEach { w ->
            val root = w.root ?: return@forEach
            if (root.packageName != SYSTEMUI) return@forEach
            val isScreenshotUi =
                root.findAccessibilityNodeInfosByViewId(PREVIEW_ID).isNotEmpty() ||
                    root.findAccessibilityNodeInfosByViewId(STATIC_ID).isNotEmpty()
            if (!isScreenshotUi) return@forEach
            if (tryActionDismiss(root)) {
                Log.i(TAG, "System screenshot chrome dismissed (ACTION_DISMISS)")
            } else if (swipePreviewAway(root)) {
                Log.i(TAG, "System screenshot chrome dismissed (swipe gesture)")
            }
        }
    }

    private fun tryActionDismiss(node: AccessibilityNodeInfo): Boolean {
        if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS } &&
            node.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
        ) return true
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { if (tryActionDismiss(it)) return true }
        }
        return false
    }

    private fun swipePreviewAway(root: AccessibilityNodeInfo): Boolean {
        val preview = root.findAccessibilityNodeInfosByViewId(PREVIEW_ID).firstOrNull()
            ?: return false
        val bounds = Rect().also { preview.getBoundsInScreen(it) }
        if (bounds.isEmpty) return false
        // Fast leftward fling to the left screen edge — AOSP dismisses the
        // screenshot preview (and its action chips) on a horizontal fling.
        // Gesture paths must stay on-screen (>= 0), so clamp the end to x=1.
        val path = Path().apply {
            moveTo(bounds.exactCenterX(), bounds.exactCenterY())
            lineTo(1f, bounds.exactCenterY())
        }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
                .build(),
            null,
            null,
        )
        return true
    }

    // ---- Capture more (incremental) ----

    /**
     * Add one frame to the capture-more session, then stitch + save. The first
     * call captures the current screen as the base; later calls scroll a
     * partial step and capture again. [onResult] gets the running frame count
     * and the stitched image Uri (saved to Pictures/Screenshots, replacing the
     * previous stitched file so we don't accumulate). The caller dismisses the
     * popup before calling, so no UI of ours pollutes the captured frames.
     */
    private fun captureMore(onCaptured: (Int) -> Unit) {
        val first = session == null
        if (first) session = ScrollCaptureSession(this)
        val s = session ?: return
        val handle: (Int, Boolean) -> Unit = { count, advanced ->
            handler.post {
                if (!advanced && count >= 2) {
                    Toast.makeText(this, "Reached the end", Toast.LENGTH_SHORT).show()
                }
                // Report the count immediately (toolbar reappears now); stitch
                // is deferred to finalizeStitch() when Crop/Share is tapped.
                onCaptured(count)
            }
        }
        if (first) {
            // First tap: grab the current screen as the base frame, then scroll
            // and grab one more, so a single tap yields a real 2-frame capture.
            s.captureFirst { s.captureMore(handle) }
        } else {
            s.captureMore(handle)
        }
    }
}
