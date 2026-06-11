package dev.jacob.screenshottoolbar.popup

import android.app.NotificationManager
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts

class DeleteActivity : ComponentActivity() {

    companion object {
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0).takeIf { it != 0 }?.let {
            getSystemService(NotificationManager::class.java).cancel(it)
        }
        val request = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
        launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }
}
