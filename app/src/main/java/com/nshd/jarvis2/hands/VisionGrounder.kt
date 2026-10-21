package com.nshd.jarvis2.hands

import android.content.Context
import android.util.Base64
import com.nshd.jarvis2.brain.ChatMsg
import com.nshd.jarvis2.brain.ProviderStore
import com.nshd.jarvis2.service.JarvisAccessibilityService
import com.nshd.jarvis2.service.ScreenShareState
import org.json.JSONObject

/** A grounded tap point in 0..1000 space, or a failure with a reason. */
sealed class GroundingResult {
    data class Found(val x: Int, val y: Int, val providerLabel: String) : GroundingResult()
    data class Failed(val message: String) : GroundingResult()
}

/**
 * Vision pipeline (Level 5): image acquisition + model grounding.
 *
 * Image source priority (shared by the see_screen / vision_tap tools):
 *   1. MediaProjection screen share — freshest frame, works everywhere.
 *   2. Accessibility takeScreenshot — prompt-free still.
 *   3. Nothing → the tools report how to enable a source.
 *
 * Grounding asks a configured provider's chat model to locate a described
 * target and return strict JSON coordinates in 0–1000 space.
 */
class VisionGrounder(context: Context) {

    private val appContext = context.applicationContext

    /** Base64 JPEG of the current screen from the best available source. */
    suspend fun captureImageBase64(): String? {
        ScreenShareState.latestJpegBase64.value?.takeIf { it.isNotBlank() }?.let { return it }
        ScreenShareState.latestJpeg.value?.let { jpeg ->
            return Base64.encodeToString(jpeg, Base64.NO_WRAP)
        }
        val service = JarvisAccessibilityService.instance ?: return null
        return service.captureScreenshotBase64()
    }

    /** Which source a capture would use right now (for user-facing text). */
    fun captureSourceName(): String? = when {
        ScreenShareState.active.value -> "screen share"
        JarvisAccessibilityService.instance != null -> "accessibility screenshot"
        else -> null
    }

    suspend fun ground(description: String, imageBase64: String): GroundingResult {
        val store = ProviderStore(appContext)
        val configured = store.orderedConfigs().filter { it.isConfigured }
        if (configured.isEmpty()) {
            return GroundingResult.Failed(
                "No API provider is configured, so no vision model can look at the " +
                    "screenshot. Configure a provider in Settings → API & Connection."
            )
        }
        val prompt = "You are a UI grounding engine. Find the center of: $description. " +
            "Reply ONLY JSON {\"x\": <0-1000>, \"y\": <0-1000>} or {\"x\": -1} if not visible."
        val failures = mutableListOf<String>()
        for (config in configured) {
            val model = config.chatModel.ifBlank { config.reasoningModel }
            if (model.isBlank()) {
                failures += "${config.label}: no model set"
                continue
            }
            val reply = try {
                config.toProvider().chat(
                    listOf(
                        ChatMsg(
                            role = "user",
                            content = prompt,
                            imageBase64 = imageBase64
                        )
                    ),
                    model
                )
            } catch (e: Exception) {
                failures += "${config.label}: ${e.message ?: "request failed"}"
                continue
            }
            when (val parsed = parseGrounding(reply)) {
                is GroundingResult.Found -> return parsed.copy(providerLabel = config.label)
                is GroundingResult.Failed -> {
                    if (parsed.message == NOT_VISIBLE) {
                        return GroundingResult.Failed(
                            "The vision model (${config.label}) could not see \"$description\" " +
                                "on this screen."
                        )
                    }
                    failures += "${config.label}: unparseable reply"
                }
            }
        }
        return GroundingResult.Failed(
            "No vision model answered with usable coordinates: ${failures.joinToString("  •  ")}"
        )
    }

    private fun parseGrounding(reply: String): GroundingResult {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return GroundingResult.Failed(PARSE_FAILED)
        val json = try {
            JSONObject(reply.substring(start, end + 1))
        } catch (e: Exception) {
            return GroundingResult.Failed(PARSE_FAILED)
        }
        val x = json.optInt("x", -1)
        if (x < 0) return GroundingResult.Failed(NOT_VISIBLE)
        val y = json.optInt("y", -1)
        if (y !in 0..1000) return GroundingResult.Failed(PARSE_FAILED)
        return GroundingResult.Found(x.coerceIn(0, 1000), y, "")
    }

    private companion object {
        const val NOT_VISIBLE = "__not_visible__"
        const val PARSE_FAILED = "__parse_failed__"
    }
}
