package dev.jacob.screenshottoolbar.crop

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.min
import kotlin.math.roundToInt

/** A successfully saved result the user can still share from the crop screen. */
data class SaveResult(val uri: Uri, val message: String)

class CropActivity : ComponentActivity() {

    private var result by mutableStateOf<SaveResult?>(null)
    private var pendingOverwrite: (() -> Unit)? = null
    private val writeLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) pendingOverwrite?.invoke()
        pendingOverwrite = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(contentResolver, uri)
            ) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
        }.getOrNull()
        if (bitmap == null) {
            Toast.makeText(this, "Couldn't open screenshot", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CropScreen(
                    bitmap = bitmap,
                    result = result,
                    onSaveCopy = { saveCopy(bitmap, it) },
                    onOverwrite = { overwrite(uri, bitmap, it) },
                    onShare = { shareCropped(bitmap, it) },
                    onShareResult = { r -> shareResult(r.uri) },
                    onDone = { finish() },
                )
            }
        }
    }

    private fun cropped(bitmap: Bitmap, r: ImageRect): Bitmap =
        Bitmap.createBitmap(bitmap, r.x, r.y, r.width, r.height)

    private fun saveCopy(bitmap: Bitmap, r: ImageRect) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_cropped_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            // Pictures/Cropped, NOT .../Screenshots — saving there would re-trigger our own detector.
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cropped")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val outUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (outUri == null) {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
            return
        }
        contentResolver.openOutputStream(outUri)?.use { out ->
            cropped(bitmap, r).compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(outUri, values, null, null)
        result = SaveResult(outUri, "Saved to Pictures/Cropped")
    }

    private fun overwrite(uri: Uri, bitmap: Bitmap, r: ImageRect) {
        pendingOverwrite = {
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                cropped(bitmap, r).compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            result = SaveResult(uri, "Screenshot overwritten")
        }
        val request = MediaStore.createWriteRequest(contentResolver, listOf(uri))
        writeLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }

    private fun shareResult(uri: Uri) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                null,
            )
        )
    }

    private fun shareCropped(bitmap: Bitmap, r: ImageRect) {
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "crop_${System.currentTimeMillis()}.png")
        file.outputStream().use { cropped(bitmap, r).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shareUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                null,
            )
        )
    }
}

/** Holds crop state across recompositions; display rect is set once layout size is known. */
private class CropState {
    var displayed: CropRect? = null
    var crop by mutableStateOf(CropRect(0f, 0f, 0f, 0f))

    /** Until the user touches the crop, the default rect keeps tracking layout/inset changes. */
    var userAdjusted = false

    /** View zoom (≥1) and pan, for fine cropping of long screenshots. The crop
     *  rect itself stays in un-zoomed (base) display coords; zoom/pan only
     *  affect how the image + overlay are drawn and how touches map back. */
    var zoom by mutableStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
}

@Composable
fun CropScreen(
    bitmap: Bitmap,
    result: SaveResult?,
    onSaveCopy: (ImageRect) -> Unit,
    onOverwrite: (ImageRect) -> Unit,
    onShare: (ImageRect) -> Unit,
    onShareResult: (SaveResult) -> Unit,
    onDone: () -> Unit,
) {
    val state = remember { CropState() }
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    val statusBarPx = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()

    Column(
        Modifier.fillMaxSize().background(Color.Black)
            .statusBarsPadding().navigationBarsPadding()
    ) {
        // The margin keeps corner handles away from the screen edges so
        // they're grabbable without moving the frame first.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
            val containerW = constraints.maxWidth.toFloat()
            val containerH = constraints.maxHeight.toFloat()
            val scale = min(containerW / bitmap.width, containerH / bitmap.height)
            val dispW = bitmap.width * scale
            val dispH = bitmap.height * scale
            val displayed = CropRect(
                (containerW - dispW) / 2f,
                (containerH - dispH) / 2f,
                (containerW + dispW) / 2f,
                (containerH + dispH) / 2f,
            )
            val touchRadius = with(LocalDensity.current) { 24.dp.toPx() }
            state.displayed = displayed
            if (!state.userAdjusted) {
                // Default crop excludes the screenshot's own status-bar strip —
                // usually unwanted. Dragging the top handle up re-includes it.
                // Recomputed on every recomposition (insets can arrive after the
                // first frame) until the user adjusts the crop themselves.
                val statusStrip = statusBarPx * (displayed.height / bitmap.height)
                val defaultTop = (displayed.top + statusStrip)
                    .coerceAtMost(displayed.bottom - touchRadius * 2)
                val default = CropRect(displayed.left, defaultTop, displayed.right, displayed.bottom)
                if (state.crop != default) state.crop = default
            }
            // Pivot for the zoom transform: the container centre. A base
            // (un-zoomed) point b is drawn at screen = (b - pivot)*zoom + pivot
            // + pan; the inverse maps touches back to base coords so the crop
            // math keeps working in a single, zoom-independent coordinate space.
            val pivot = Offset(containerW / 2f, containerH / 2f)
            fun toBase(screen: Offset): Offset =
                (screen - pivot - state.pan) / state.zoom + pivot

            // Keep pan within bounds so the image can't be dragged off-screen:
            // at zoom z the image half-extends z× past the pivot, and the extra
            // room to pan is (z-1)× the half-container in each axis.
            fun clampPan(p: Offset, z: Float): Offset {
                val maxX = (z - 1f) * containerW / 2f
                val maxY = (z - 1f) * containerH / 2f
                return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
            }

            Canvas(
                Modifier.fillMaxSize().pointerInput(displayed) {
                    awaitEachGesture {
                        val first = awaitFirstDown()
                        // Decide the gesture's role from where the first finger
                        // landed (in base coords): on a handle → adjust crop;
                        // otherwise → pan/zoom the view.
                        val startBase = toBase(first.position)
                        val handle = CropMath.hitTest(state.crop, startBase.x, startBase.y, touchRadius)
                        if (handle != null) state.userAdjusted = true
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (handle != null && pressed.size == 1) {
                                // Single-finger handle drag: convert the screen
                                // delta to base coords (divide by zoom).
                                val d = panChange / state.zoom
                                state.crop = CropMath.drag(
                                    state.crop, handle, d.x, d.y, displayed, touchRadius * 2,
                                )
                            } else {
                                // Pinch zoom and/or pan the view.
                                val newZoom = (state.zoom * zoomChange).coerceIn(1f, 6f)
                                state.zoom = newZoom
                                state.pan = clampPan(state.pan + panChange, newZoom)
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } while (true)
                    }
                }
            ) {
                withTransform({
                    translate(state.pan.x, state.pan.y)
                    scale(state.zoom, state.zoom, pivot = pivot)
                }) {
                    drawImage(
                        imageBitmap,
                        dstOffset = IntOffset(displayed.left.roundToInt(), displayed.top.roundToInt()),
                        dstSize = IntSize(dispW.roundToInt(), dispH.roundToInt()),
                    )
                    val crop = state.crop
                    val dim = Color.Black.copy(alpha = 0.55f)
                    // Dim everything outside the crop rect (top/bottom/left/right strips).
                    drawRect(dim, Offset(0f, 0f), Size(size.width, crop.top))
                    drawRect(dim, Offset(0f, crop.bottom), Size(size.width, size.height - crop.bottom))
                    drawRect(dim, Offset(0f, crop.top), Size(crop.left, crop.height))
                    drawRect(dim, Offset(crop.right, crop.top), Size(size.width - crop.right, crop.height))
                    // Stroke + handle sizes are divided by zoom so they keep a
                    // constant on-screen thickness/radius as the view scales.
                    drawRect(
                        Color.White,
                        Offset(crop.left, crop.top),
                        Size(crop.width, crop.height),
                        style = Stroke(2.dp.toPx() / state.zoom),
                    )
                    listOf(
                        crop.left to crop.top, crop.right to crop.top,
                        crop.left to crop.bottom, crop.right to crop.bottom,
                    ).forEach { (hx, hy) ->
                        drawCircle(Color.White, radius = 8.dp.toPx() / state.zoom, center = Offset(hx, hy))
                    }
                    val midX = (crop.left + crop.right) / 2f
                    val midY = (crop.top + crop.bottom) / 2f
                    listOf(
                        midX to crop.top, midX to crop.bottom,
                        crop.left to midY, crop.right to midY,
                    ).forEach { (hx, hy) ->
                        drawCircle(Color.White, radius = 5.dp.toPx() / state.zoom, center = Offset(hx, hy))
                    }
                }
            }
        }

        fun imageRect(): ImageRect? = state.displayed?.let {
            CropMath.toImageRect(state.crop, it, bitmap.width, bitmap.height)
        }

        if (result == null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Free" to null, "1:1" to 1f, "4:3" to 4f / 3f, "16:9" to 16f / 9f)
                    .forEach { (label, aspect) ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                state.displayed?.let { d ->
                                    state.userAdjusted = true
                                    state.crop = if (aspect == null) d else CropMath.aspectRect(d, aspect)
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                // Pinch to zoom for fine cropping; this resets back to fit.
                if (state.zoom > 1f) {
                    FilterChip(
                        selected = true,
                        onClick = { state.zoom = 1f; state.pan = Offset.Zero },
                        label = { Text("Reset zoom") },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = { imageRect()?.let(onShare) }) { Text("Share") }
                TextButton(onClick = { imageRect()?.let(onOverwrite) }) { Text("Overwrite") }
                Button(onClick = { imageRect()?.let(onSaveCopy) }) { Text("Save copy") }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✓ ${result.message}", Modifier.weight(1f), color = Color.White)
            }
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = onDone) { Text("Done") }
                Button(onClick = { onShareResult(result) }) { Text("Share") }
            }
        }
    }
}
