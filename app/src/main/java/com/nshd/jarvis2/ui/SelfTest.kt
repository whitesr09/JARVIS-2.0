package com.nshd.jarvis2.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.nshd.jarvis2.JarvisSettings
import com.nshd.jarvis2.brain.ProbeStatus
import com.nshd.jarvis2.brain.ProviderStore
import com.nshd.jarvis2.core.AppLog
import com.nshd.jarvis2.service.JarvisAccessibilityService
import com.nshd.jarvis2.voice.UniversalVoice
import com.nshd.jarvis2.voice.VoskModelManager
import java.io.File

/** One self-test line: PASS/FAIL + a one-line detail + an optional Fix deep link. */
data class SelfTestResult(
    val name: String,
    val passed: Boolean,
    val detail: String,
    val fixLabel: String? = null,
    val fixIntent: Intent? = null
)

/**
 * F8 (#106): the in-app self-test — eight sequential LIVE checks of
 * everything a hands-free session depends on, each with a one-line
 * verdict and a "Fix" deep link where one exists. No network calls:
 * the provider check reads only the cached probe results. Every
 * result is also written to AppLog (tag "App": selftest …) so a
 * shared diagnostics pack carries them.
 */
object SelfTest {

    fun run(context: Context, settings: JarvisSettings): List<SelfTestResult> {
        val appContext = context.applicationContext
        val results = mutableListOf<SelfTestResult>()

        // 1) Microphone permission.
        val micGranted = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        results += SelfTestResult(
            name = "Microphone permission",
            passed = micGranted,
            detail = if (micGranted) {
                "Granted — Jarvis can hear you"
            } else {
                "Not granted — voice input can't start"
            },
            fixLabel = if (micGranted) null else "App settings",
            fixIntent = if (micGranted) null else appDetailsIntent(appContext)
        )

        // 2) SpeechRecognizer + effective STT language.
        val recognizerAvailable = runCatching {
            SpeechRecognizer.isRecognitionAvailable(appContext)
        }.getOrDefault(false)
        val languageName = UniversalVoice.sttDisplayName(settings.language)
        results += SelfTestResult(
            name = "Speech recognition",
            passed = recognizerAvailable,
            detail = if (recognizerAvailable) {
                "Available — listening language: $languageName"
            } else {
                "No speech recognition service on this device"
            }
        )

        // 3) v2.0.2: TTS is removed — replies are always text-only, so
        // there is no TTS engine check anymore (check numbering kept
        // stable for the diagnostics log).
        results += SelfTestResult(
            name = "Text-to-speech",
            passed = true,
            detail = "Removed in v2.0.2 — replies are text-only by design"
        )

        // 4) Accessibility service alive.
        val a11yAlive = JarvisAccessibilityService.instance != null
        results += SelfTestResult(
            name = "Accessibility (Digital Hands)",
            passed = a11yAlive,
            detail = if (a11yAlive) {
                "Connected — taps, typing and screen reading work"
            } else {
                "Not connected — hands features are off"
            },
            fixLabel = if (a11yAlive) null else "Accessibility settings",
            fixIntent = if (a11yAlive) {
                null
            } else {
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
        )

        // 5) Overlay permission.
        val overlayGranted = Settings.canDrawOverlays(appContext)
        results += SelfTestResult(
            name = "Overlay permission",
            passed = overlayGranted,
            detail = if (overlayGranted) {
                "Granted — the floating avatar + task panel can show"
            } else {
                "Not granted — no floating avatar or task panel"
            },
            fixLabel = if (overlayGranted) null else "Overlay settings",
            fixIntent = if (overlayGranted) {
                null
            } else {
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${appContext.packageName}")
                )
            }
        )

        // 6) Provider configured + last probe latency (cached only).
        val store = ProviderStore(appContext)
        val configured = store.orderedConfigs().filter { it.isConfigured }
        val providerResult = if (configured.isEmpty()) {
            SelfTestResult(
                name = "AI provider",
                passed = false,
                detail = "No provider has an API key configured"
            )
        } else {
            val active = configured.first()
            val bestProbe = store.cachedProbeResults(active.id)
                .filter { it.status == ProbeStatus.WORKING }
                .minByOrNull { it.latencyMs }
            SelfTestResult(
                name = "AI provider",
                passed = true,
                detail = "${active.label} configured (chat: " +
                    "${active.chatModel.ifBlank { "(none)" }})" +
                    if (bestProbe != null) {
                        " • fastest probe ${bestProbe.latencyMs}ms " +
                            "(${bestProbe.modelId})"
                    } else {
                        " • not probed yet"
                    }
            )
        }
        results += providerResult

        // 7) Vosk wake model state (also the offline-commands model).
        val voskReady = VoskModelManager.status(appContext) ==
            VoskModelManager.ModelStatus.READY
        results += SelfTestResult(
            name = "Offline voice model (Vosk)",
            passed = voskReady,
            detail = if (voskReady) {
                "Downloaded — wake word + offline commands available"
            } else {
                "Not downloaded — no wake word, no offline commands"
            }
        )

        // 8) Storage writable (logs dir probe).
        val storageOk = runCatching {
            val dir = File(appContext.filesDir, "logs").apply { mkdirs() }
            val probe = File(dir, ".selftest-probe")
            probe.writeText("ok")
            val readBack = probe.readText()
            probe.delete()
            readBack == "ok"
        }.getOrDefault(false)
        results += SelfTestResult(
            name = "Storage (logs)",
            passed = storageOk,
            detail = if (storageOk) {
                "Writable — logs and diagnostics can be saved"
            } else {
                "Logs directory is not writable"
            }
        )

        results.forEach { result ->
            AppLog.i(
                "App",
                "selftest ${result.name}: " +
                    "${if (result.passed) "PASS" else "FAIL"} — ${result.detail}"
            )
        }
        val passedCount = results.count { it.passed }
        AppLog.i("App", "selftest complete: $passedCount/${results.size} passed")
        return results
    }

    private fun appDetailsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
}
