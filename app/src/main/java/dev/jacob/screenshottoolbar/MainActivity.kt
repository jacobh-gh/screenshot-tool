package dev.jacob.screenshottoolbar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.jacob.screenshottoolbar.access.ToolbarAccessibilityService
import dev.jacob.screenshottoolbar.detect.DetectionService
import dev.jacob.screenshottoolbar.popup.PopupPresenter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // Surface supplies the themed background AND content color;
                // without it Compose text defaults to black on the dark theme.
                Surface(Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }
}

private fun latestScreenshot(context: Context): Uri? =
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
        arrayOf("%Screenshots%"),
        "${MediaStore.Images.Media.DATE_ADDED} DESC",
    )?.use { c ->
        if (c.moveToFirst()) {
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
        } else null
    }

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Bumped on every resume so permission/service rows re-read real state
    // after the user returns from Settings.
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var serviceRunning by remember(refresh) { mutableStateOf(DetectionService.isRunning) }
    var autostart by remember { mutableStateOf(prefs.getBoolean("autostart", false)) }
    val mediaGranted = remember(refresh) {
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED
    }
    val notifGranted = remember(refresh) {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
    val overlayGranted = remember(refresh) { Settings.canDrawOverlays(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Screenshot Toolbar", style = MaterialTheme.typography.headlineMedium)

        StatusRow("Photo access", mediaGranted) {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES))
        }
        StatusRow("Notifications", notifGranted) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        StatusRow("Overlay (optional)", overlayGranted) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
            )
        }

        val accessibilityGranted = remember(refresh) { ToolbarAccessibilityService.isGranted(context) }
        val accessibilityUsable = remember(refresh) { ToolbarAccessibilityService.isUsable(context) }
        // Enabled in settings but no live service: it isn't running. This is the
        // permanent state in the Private Space (the service never binds there)
        // and a transient state right after install before first bind.
        val a11yEnabledNotActive = accessibilityGranted && !accessibilityUsable
        var hideChrome by remember { mutableStateOf(prefs.getBoolean("hide_system_chrome", false)) }
        StatusRow(
            if (a11yEnabledNotActive) "Accessibility (enabled, not active — N/A in Private Space)"
            else "Accessibility (for capture more)",
            accessibilityUsable,
        ) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    accessibilityUsable -> "Hide system screenshot UI"
                    a11yEnabledNotActive -> "Hide system screenshot UI (accessibility not active)"
                    else -> "Hide system screenshot UI (needs accessibility)"
                },
                Modifier.weight(1f),
            )
            Switch(
                checked = hideChrome && accessibilityUsable,
                enabled = accessibilityUsable,
                onCheckedChange = { on ->
                    hideChrome = on
                    prefs.edit().putBoolean("hide_system_chrome", on).apply()
                },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Detection service", Modifier.weight(1f))
            Switch(checked = serviceRunning, onCheckedChange = { on ->
                if (on) DetectionService.start(context) else DetectionService.stop(context)
                serviceRunning = on
            })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Start on boot", Modifier.weight(1f))
            Checkbox(checked = autostart, onCheckedChange = { on ->
                autostart = on
                prefs.edit().putBoolean("autostart", on).apply()
            })
        }

        OutlinedButton(onClick = {
            val uri = latestScreenshot(context)
            if (uri == null) {
                Toast.makeText(context, "No screenshots found", Toast.LENGTH_SHORT).show()
            } else {
                PopupPresenter.show(context, uri)
            }
        }) { Text("Test popup with latest screenshot") }
    }
}

@Composable
private fun StatusRow(label: String, granted: Boolean, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (granted) "✓" else "✗", Modifier.padding(end = 8.dp))
        Text(label, Modifier.weight(1f))
        if (!granted) {
            OutlinedButton(onClick = onGrant) { Text("Grant") }
        }
    }
}
