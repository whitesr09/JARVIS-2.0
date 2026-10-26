package com.nshd.jarvis2.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.nshd.jarvis2.MainActivity
import com.nshd.jarvis2.voice.VoiceSessionManager
import com.nshd.jarvis2.voice.VoiceStatus

/**
 * v1.9.0 (#113): Quick Settings tile for the voice session.
 *
 * Tap while a session is live → stops it. Tap while idle → opens
 * the app straight into the Voice screen (the session itself starts
 * through the normal in-app flow, so every permission prompt and
 * provider check still applies).
 */
class VoiceTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    // Android 14+ requires the PendingIntent overload. The deprecated
    // Intent overload remains necessary for Android 13 and earlier.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val active = VoiceSessionManager.uiState.value.status != VoiceStatus.IDLE
        if (active) {
            VoiceSessionManager.stop()
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_OPEN_SCREEN, "voice")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val active = VoiceSessionManager.uiState.value.status != VoiceStatus.IDLE
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Jarvis Voice"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (active) "Tap to stop" else "Tap to talk"
        }
        tile.updateTile()
    }
}
