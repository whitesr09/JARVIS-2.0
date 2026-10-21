package com.nshd.jarvis2.ui

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.nshd.jarvis2.service.ScreenShareService
import com.nshd.jarvis2.service.ScreenShareState

/**
 * Start/stop handle for the MediaProjection screen share, shared by the
 * Settings card and the Voice screen chip so the consent flow exists
 * exactly once: system consent dialog → [ScreenShareService].
 */
class ScreenShareControl(
    private val context: Context,
    val active: Boolean,
    private val launchConsent: () -> Unit
) {
    fun toggle() {
        if (active) ScreenShareService.stop(context) else launchConsent()
    }
}

@Composable
fun rememberScreenShareControl(): ScreenShareControl {
    val context = LocalContext.current
    val active by ScreenShareState.active.collectAsState()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenShareService.start(context, result.resultCode, data)
        }
    }
    return remember(active, launcher) {
        ScreenShareControl(
            context = context,
            active = active,
            launchConsent = {
                val manager = context.getSystemService(MediaProjectionManager::class.java)
                val intent = manager?.createScreenCaptureIntent()
                if (intent != null) launcher.launch(intent)
            }
        )
    }
}
