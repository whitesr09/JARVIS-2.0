package com.nshd.jarvis2.hands

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.nshd.jarvis2.service.JarvisAccessibilityService
import kotlinx.coroutines.delay

/**
 * Deep Android intents and hardware controls.
 *
 * Intents are always preferred over simulated taps. Accessibility is used
 * only to verify or finish a flow (notably WhatsApp's send button and the
 * entry-empty proof that a message actually sent).
 */
class DeepIntents(context: Context) {
    private val appContext = context.applicationContext

    // ── WhatsApp ───────────────────────────────────────────────────────

    suspend fun whatsappMessage(phone: String, text: String): ActionResult {
        val digits = ContactsResolver.normalizePhone(phone)
        if (digits.length < 10) return ActionResult(false, "The WhatsApp number looks incomplete.")
        val url = "https://api.whatsapp.com/send?phone=$digits&text=${Uri.encode(text)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isPackageInstalled("com.whatsapp")) setPackage("com.whatsapp")
            else if (isPackageInstalled("com.whatsapp.w4b")) setPackage("com.whatsapp.w4b")
        }
        if (!launch(intent)) {
            return ActionResult(false, "WhatsApp could not be opened for this chat.")
        }

        val service = JarvisAccessibilityService.instance
            ?: return ActionResult(
                true,
                "Opened WhatsApp with the message typed — tap Send to send it. " +
                    "(Enable Accessibility to let Jarvis press Send and verify it.)"
            )

        HandsActions.waitForSettle(2_500)
        val reader = service.screenReader()
        var snapshot = reader.snapshot()
        val entry = snapshot.firstOrNull { it.editable }
        if (entry != null && entry.text.isBlank()) {
            return ActionResult(
                false,
                "Aborted: the WhatsApp message field is empty, so Send was not pressed."
            )
        }

        val send = snapshot.firstOrNull { element ->
            element.viewId == "com.whatsapp:id/send" ||
                element.viewId == "com.whatsapp.w4b:id/send" ||
                element.desc.equals("Send", ignoreCase = true) ||
                element.text.equals("Send", ignoreCase = true)
        } ?: reader.findByText("Send", snapshot)?.takeIf { it.clickable || it.desc.contains("send", true) }

        if (send == null) {
            return ActionResult(
                false,
                "WhatsApp opened with the message typed, but the Send control was not found. Screen: " +
                    reader.serialize(15)
            )
        }

        val click = HandsActions.clickElement(send.index)
        if (!click.success) return ActionResult(false, "WhatsApp opened, but Send could not be pressed: ${click.message}")

        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            delay(200)
            snapshot = reader.snapshot()
            val currentEntry = snapshot.firstOrNull { it.editable }
            if (currentEntry != null && currentEntry.text.isBlank()) {
                return ActionResult(true, "WhatsApp message sent and verified (message field is empty).")
            }
            // Some WhatsApp builds remove the editor from the exposed tree
            // after sending; no editable field is also consistent with sent.
            if (currentEntry == null && snapshot.none { it.viewId.endsWith("/entry") }) {
                return ActionResult(true, "WhatsApp message sent (editor cleared).")
            }
        }
        return ActionResult(
            false,
            "Send was pressed, but the message field did not clear within 2 seconds, so sending is unverified."
        )
    }

    // ── YouTube ────────────────────────────────────────────────────────

    suspend fun youtubePlay(query: String): ActionResult {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return ActionResult(false, "The YouTube search query is blank.")
        val resultsUri = Uri.parse(
            "https://www.youtube.com/results?search_query=${Uri.encode(cleanQuery)}"
        )
        val intent = Intent(Intent.ACTION_VIEW, resultsUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isPackageInstalled("com.google.android.youtube")) {
                setPackage("com.google.android.youtube")
            }
        }
        if (!launch(intent)) return ActionResult(false, "YouTube could not be opened.")

        val service = JarvisAccessibilityService.instance
            ?: return ActionResult(true, "Opened YouTube search results for \"$cleanQuery\". Enable Accessibility to auto-play the first result.")

        HandsActions.waitForSettle(3_000)
        val reader = service.screenReader()
        val snapshot = reader.snapshot()
        val screenHeight = appContext.resources.displayMetrics.heightPixels
        val blockedWords = listOf(
            "search", "shorts", "home", "subscriptions", "library", "explore",
            "notifications", "voice", "back", "menu", "settings", "sign in"
        )
        val candidate = snapshot
            .filter { element ->
                val label = element.label
                element.clickable &&
                    label.length > 15 &&
                    blockedWords.none { label.contains(it, ignoreCase = true) } &&
                    element.bounds.centerY() < screenHeight * 2 / 3
            }
            .minByOrNull { it.bounds.top }
            ?: return ActionResult(
                false,
                "YouTube results opened, but no plausible first video result was found. Screen: " +
                    reader.serialize(25)
            )
        val click = HandsActions.clickElement(candidate.index)
        return ActionResult(
            click.success,
            if (click.success) "Playing the first YouTube result: ${candidate.label}"
            else "YouTube results opened, but the first result could not be tapped: ${click.message}"
        )
    }

    // ── Apps ───────────────────────────────────────────────────────────

    fun openApp(name: String): ActionResult {
        val key = normalizeAppName(name)
        when (key) {
            "camera" -> {
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return resultForLaunch(intent, "Camera")
            }
            "settings" -> {
                val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return resultForLaunch(intent, "Settings")
            }
            "phone", "dialer" -> {
                val intent = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return resultForLaunch(intent, "Phone")
            }
            "messages", "sms" -> {
                val intent = Intent.makeMainSelectorActivity(
                    Intent.ACTION_MAIN,
                    Intent.CATEGORY_APP_MESSAGING
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return resultForLaunch(intent, "Messages")
            }
            "clock" -> {
                val alarms = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (canResolve(alarms)) return resultForLaunch(alarms, "Clock")
            }
            "calculator" -> {
                val intent = Intent.makeMainSelectorActivity(
                    Intent.ACTION_MAIN,
                    Intent.CATEGORY_APP_CALCULATOR
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (canResolve(intent)) return resultForLaunch(intent, "Calculator")
            }
        }

        val packageName = PACKAGE_MAP[key]
        if (packageName != null) {
            val launchIntent = appContext.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return resultForLaunch(launchIntent, name)
            }
            // Package is absent/disabled or exposes no launcher entry:
            // fall through to the launcher-label search below.
        }

        return openAppByLabel(name)
    }

    private fun openAppByLabel(name: String): ActionResult {
        val wanted = normalizeAppName(name)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = try {
            appContext.packageManager.queryIntentActivities(launcherIntent, 0)
        } catch (e: Exception) {
            emptyList()
        }
        val matches = activities.mapNotNull { info ->
            val label = info.loadLabel(appContext.packageManager)?.toString().orEmpty()
            val normalized = normalizeAppName(label)
            val score = when {
                normalized == wanted -> 100
                normalized.startsWith(wanted) -> 80
                normalized.contains(wanted) -> 60
                else -> -1
            }
            if (score >= 0) Triple(score, label, info.activityInfo) else null
        }.sortedByDescending { it.first }
        val best = matches.firstOrNull()
            ?: return ActionResult(false, "No installed app matched \"$name\".")
        val component = ComponentName(best.third.packageName, best.third.name)
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return resultForLaunch(intent, best.second)
    }

    // ── Calling ────────────────────────────────────────────────────────

    fun callNumber(phone: String, displayName: String): ActionResult {
        val digits = ContactsResolver.normalizePhone(phone)
        if (digits.length < 10) return ActionResult(false, "The phone number for $displayName looks incomplete.")
        val hasCallPermission = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(
            if (hasCallPermission) Intent.ACTION_CALL else Intent.ACTION_DIAL,
            Uri.parse("tel:$digits")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = launch(intent)
        return ActionResult(
            launched,
            when {
                !launched -> "The phone app could not be opened for $displayName."
                hasCallPermission -> "Calling $displayName."
                else -> "Opened the dialer for $displayName — grant Phone Calls permission to place calls directly."
            }
        )
    }

    // ── Hardware ───────────────────────────────────────────────────────

    fun setVolume(percent: Int): ActionResult {
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ActionResult(false, "Audio service is unavailable.")
        val safePercent = percent.coerceIn(0, 100)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * safePercent / 100.0).toInt().coerceIn(0, max)
        return try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            ActionResult(true, "Media volume set to $safePercent%.")
        } catch (e: Exception) {
            ActionResult(false, "Volume could not be changed: ${e.message ?: "system denied it"}")
        }
    }

    fun currentVolumePercent(): Int {
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 50
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    fun adjustVolume(deltaPercent: Int): ActionResult =
        setVolume(currentVolumePercent() + deltaPercent)

    fun toggleTorch(on: Boolean): ActionResult {
        val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ActionResult(false, "Camera service is unavailable.")
        return try {
            val backCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull()
            ?: return ActionResult(false, "No camera is available for the torch.")
            cameraManager.setTorchMode(backCameraId, on)
            ActionResult(true, "Torch turned ${if (on) "on" else "off"}.")
        } catch (e: Exception) {
            ActionResult(false, "Torch could not be changed: ${e.message ?: "camera busy or denied"}")
        }
    }

    fun setBrightness(percent: Int): ActionResult {
        val safePercent = percent.coerceIn(0, 100)
        if (!Settings.System.canWrite(appContext)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${appContext.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launch(intent)
            return ActionResult(
                false,
                "Brightness permission is needed. I opened Modify System Settings — allow it, then ask again."
            )
        }
        return try {
            val resolver = appContext.contentResolver
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val value = (255 * safePercent / 100.0).toInt().coerceIn(0, 255)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
            ActionResult(true, "Brightness set to $safePercent%.")
        } catch (e: Exception) {
            ActionResult(false, "Brightness could not be changed: ${e.message ?: "system denied it"}")
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun resultForLaunch(intent: Intent, label: String): ActionResult {
        val launched = launch(intent)
        return ActionResult(launched, if (launched) "Opened $label." else "Could not open $label.")
    }

    private fun launch(intent: Intent): Boolean = try {
        appContext.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun canResolve(intent: Intent): Boolean = try {
        appContext.packageManager.resolveActivity(intent, 0) != null
    } catch (e: Exception) {
        false
    }

    private fun isPackageInstalled(packageName: String): Boolean = try {
        appContext.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun normalizeAppName(name: String): String = name.lowercase()
        .replace(Regex("[^a-z0-9]"), "")
        .trim()

    companion object {
        private val PACKAGE_MAP = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "instagram" to "com.instagram.android",
            "chrome" to "com.android.chrome",
            "spotify" to "com.spotify.music",
            "telegram" to "org.telegram.messenger",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "googlemaps" to "com.google.android.apps.maps",
            "playstore" to "com.android.vending",
            "googleplay" to "com.android.vending",
            "googleplaystore" to "com.android.vending",
            "clock" to "com.google.android.deskclock",
            "calculator" to "com.google.android.calculator2"
        )
    }
}
