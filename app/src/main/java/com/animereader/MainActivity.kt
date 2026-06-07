package com.animereader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.animereader.data.SettingsRepository
import com.animereader.service.OverlayController
import com.animereader.ui.AppRoot

/**
 * Hosts the Compose UI and owns the permission flow required before the overlay
 * service can run: draw-over-other-apps → notifications (API 33+) → MediaProjection.
 */
class MainActivity : ComponentActivity() {

    private lateinit var repo: SettingsRepository
    private lateinit var mpm: MediaProjectionManager

    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var overlayPermLauncher: ActivityResultLauncher<Intent>
    private lateinit var notifPermLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SettingsRepository(this)
        mpm = getSystemService(MediaProjectionManager::class.java)

        projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                OverlayController.start(this, result.resultCode, data)
            } else {
                toast("Capture d’écran refusée")
            }
        }
        overlayPermLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) ensureNotifThenProject()
            else toast("Permission de superposition requise")
        }
        notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Proceed to projection regardless — the service still runs, only the notification may be silent.
            requestProjection()
        }

        setContent {
            AppRoot(
                repo = repo,
                onStartReader = { startReaderFlow() },
                onStopReader = { OverlayController.stop(this) },
            )
        }
    }

    private fun startReaderFlow() {
        if (!Settings.canDrawOverlays(this)) {
            overlayPermLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        ensureNotifThenProject()
    }

    private fun ensureNotifThenProject() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestProjection()
    }

    private fun requestProjection() {
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
