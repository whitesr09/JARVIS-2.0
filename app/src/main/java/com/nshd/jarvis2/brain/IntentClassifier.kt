package com.nshd.jarvis2.brain

import android.content.Context
import com.nshd.jarvis2.core.AppLog
import org.json.JSONObject

/**
 * v2.0.0 (#22): on-device intent classifier — a keyword/heuristic
 * pre-router that sits AFTER the deterministic quick patterns and
 * BEFORE the model loop (order: quick patterns → macros → custom
 * commands → classifier → model). It catches the PARAPHRASES the
 * anchored regexes miss — polite wrappers ("can you please…",
 * "jarvis, …"), synonyms ("make it louder", "ring Mom", "the screen
 * is too dark") and loose STT word order — for the top ~40 command
 * intents, with no network and no model call.
 *
 * Conservative by design: an intent only fires when its required
 * evidence is present AND its score clears [MIN_SCORE] with a clear
 * margin over the runner-up. Anything ambiguous returns null and
 * the model loop handles it, exactly as before.
 */
object IntentClassifier {

    data class Result(
        val toolName: String,
        val args: JSONObject,
        /** "battery" | "time" | "date" — answered locally by the pipeline. */
        val directReplyKind: String? = null,
        val intent: String,
        val score: Double
    )

    private const val MIN_SCORE = 0.62
    private const val MIN_MARGIN = 0.12

    /** Apps the classifier will open by loose name reference. */
    private val KNOWN_APPS = listOf(
        "whatsapp", "youtube", "spotify", "instagram", "telegram",
        "gmail", "chrome", "maps", "camera", "settings", "clock",
        "calculator", "gallery", "photos", "files", "play store",
        "calendar", "messages", "dialer", "phone"
    )

    private val POLITE_FILLER = setOf(
        "please", "jarvis", "hey", "ok", "okay", "can", "you", "could",
        "would", "will", "just", "now", "for", "me", "my", "the", "a",
        "an", "to", "it", "is", "do", "does", "i", "want", "need",
        "let", "us", "lets", "let's", "go", "ahead", "and"
    )

    fun classify(context: Context, rawText: String): Result? {
        val original = rawText.trim()
        if (original.isBlank()) return null
        val normalized = normalize(original)
        if (normalized.isBlank()) return null
        val tokens = normalized.split(" ").filter { it.isNotBlank() }
        val tokenSet = tokens.toSet()

        val candidates = mutableListOf<Result>()

        fun add(result: Result?) {
            if (result != null) candidates += result
        }

        // ── Direct answers ───────────────────────────────────────
        if ("battery" in tokenSet) {
            add(Result("direct_reply", JSONObject(), "battery", "battery", 0.95))
        }
        if (tokenSet.any { it == "time" } &&
            tokenSet.any { it in setOf("what", "whats", "current", "now") }
        ) {
            add(Result("direct_reply", JSONObject(), "time", "time", 0.9))
        }
        if (("date" in tokenSet || "today" in tokenSet && "day" in tokenSet) &&
            !normalized.contains("update")
        ) {
            add(Result("direct_reply", JSONObject(), "date", "date", 0.85))
        }

        // ── Open app (loose) ─────────────────────────────────────
        val openVerb = tokenSet.any {
            it in setOf("open", "launch", "start", "show", "bring")
        }
        if (openVerb) {
            val app = KNOWN_APPS.firstOrNull { appName ->
                appName.split(" ").all { it in tokenSet } &&
                    normalized.contains(appName)
            }
            if (app != null) {
                add(
                    Result(
                        "open_app",
                        JSONObject().put("app", app),
                        intent = "open_app",
                        score = 0.93
                    )
                )
            }
        }

        // ── Torch ────────────────────────────────────────────────
        val torchWords = tokenSet.any {
            it in setOf("torch", "flashlight", "flash", "light")
        }
        if (torchWords && tokenSet.any { it in setOf("on", "off") }) {
            val on = "on" in tokenSet && "off" !in tokenSet ||
                normalized.contains("turn on") || normalized.contains("switch on")
            add(
                Result(
                    "toggle_torch",
                    JSONObject().put("on", on),
                    intent = "torch",
                    score = 0.9
                )
            )
        }

        // ── Volume ───────────────────────────────────────────────
        if ("volume" in tokenSet || "louder" in tokenSet ||
            "quieter" in tokenSet || "sound" in tokenSet
        ) {
            val currentVolume = com.nshd.jarvis2.hands.DeepIntents(context)
                .currentVolumePercent()
            when {
                tokenSet.any {
                    it in setOf("up", "louder", "increase", "raise", "higher", "max")
                } -> add(
                    Result(
                        "set_volume",
                        JSONObject().put(
                            "percent",
                            if ("max" in tokenSet) 100 else (currentVolume + 20).coerceIn(0, 100)
                        ),
                        intent = "volume_up",
                        score = 0.88
                    )
                )
                tokenSet.any {
                    it in setOf("down", "quieter", "decrease", "lower", "reduce")
                } -> add(
                    Result(
                        "set_volume",
                        JSONObject().put(
                            "percent", (currentVolume - 20).coerceIn(0, 100)
                        ),
                        intent = "volume_down",
                        score = 0.88
                    )
                )
                "mute" in tokenSet -> add(
                    Result(
                        "set_volume",
                        JSONObject().put("percent", 0),
                        intent = "mute",
                        score = 0.9
                    )
                )
            }
        }

        // ── Brightness ───────────────────────────────────────────
        if ("brightness" in tokenSet || "brighter" in tokenSet ||
            "dimmer" in tokenSet || "dark" in tokenSet && "screen" in tokenSet
        ) {
            val currentBrightness = com.nshd.jarvis2.hands.DeepIntents(context)
                .currentBrightnessPercent()
            when {
                tokenSet.any {
                    it in setOf("up", "increase", "raise", "brighter", "bright")
                } -> add(
                    Result(
                        "set_brightness",
                        JSONObject().put(
                            "percent", (currentBrightness + 20).coerceIn(0, 100)
                        ),
                        intent = "brightness_up",
                        score = 0.85
                    )
                )
                tokenSet.any {
                    it in setOf("down", "decrease", "lower", "dimmer", "dim", "dark")
                } -> add(
                    Result(
                        "set_brightness",
                        JSONObject().put(
                            "percent", (currentBrightness - 20).coerceIn(0, 100)
                        ),
                        intent = "brightness_down",
                        score = 0.85
                    )
                )
            }
        }

        // ── Media transport ──────────────────────────────────────
        if (tokenSet.any { it in setOf("music", "song", "playback", "video") } ||
            "pause" in tokenSet || "resume" in tokenSet
        ) {
            when {
                "pause" in tokenSet || "stop" in tokenSet && "music" in tokenSet ->
                    add(
                        Result(
                            "media_control",
                            JSONObject().put("action", "pause"),
                            intent = "media_pause",
                            score = 0.87
                        )
                    )
                "resume" in tokenSet || "continue" in tokenSet -> add(
                    Result(
                        "media_control",
                        JSONObject().put("action", "play"),
                        intent = "media_resume",
                        score = 0.87
                    )
                )
                "next" in tokenSet -> add(
                    Result(
                        "media_control",
                        JSONObject().put("action", "next"),
                        intent = "media_next",
                        score = 0.87
                    )
                )
                "previous" in tokenSet || "back" in tokenSet && "song" in tokenSet ->
                    add(
                        Result(
                            "media_control",
                            JSONObject().put("action", "previous"),
                            intent = "media_previous",
                            score = 0.87
                        )
                    )
            }
        }

        // ── Screenshot ───────────────────────────────────────────
        if ("screenshot" in tokenSet || normalized.contains("screen shot")) {
            add(Result("take_screenshot", JSONObject(), intent = "screenshot", score = 0.92))
        }

        // ── Panels (Wi-Fi / Bluetooth / airplane) ────────────────
        if (normalized.contains("wi-fi") || normalized.contains("wifi")) {
            add(
                Result(
                    "open_panel",
                    JSONObject().put("panel", "wifi"),
                    intent = "panel_wifi",
                    score = 0.9
                )
            )
        }
        if ("bluetooth" in tokenSet) {
            add(
                Result(
                    "open_panel",
                    JSONObject().put("panel", "bluetooth"),
                    intent = "panel_bluetooth",
                    score = 0.9
                )
            )
        }
        if (normalized.contains("airplane") || normalized.contains("flight mode")) {
            add(
                Result(
                    "open_panel",
                    JSONObject().put("panel", "airplane"),
                    intent = "panel_airplane",
                    score = 0.9
                )
            )
        }

        // ── Silent mode ──────────────────────────────────────────
        if ("silent" in tokenSet || normalized.contains("do not disturb")) {
            val off = tokenSet.any { it in setOf("off", "disable", "ring") }
            add(
                Result(
                    "set_silent",
                    JSONObject().put("on", !off),
                    intent = "silent",
                    score = 0.88
                )
            )
        }

        // ── Lock / home / back ───────────────────────────────────
        if ("lock" in tokenSet) {
            add(Result("lock_screen", JSONObject(), intent = "lock", score = 0.9))
        }
        if (normalized == "go home" || normalized.contains("home screen")) {
            add(
                Result(
                    "press_key",
                    JSONObject().put("key", "home"),
                    intent = "home",
                    score = 0.85
                )
            )
        }

        // ── Call ("ring Mom", "give Mom a call") ─────────────────
        val callMatch = Regex(
            "(?:ring|call|dial|phone)\\s+(.+)$"
        ).find(normalized) ?: Regex(
            "give\\s+(.+?)\\s+a\\s+call$"
        ).find(normalized)
        if (callMatch != null) {
            val name = callMatch.groupValues[1].trim()
            if (name.isNotBlank() && name.split(" ").size <= 3 &&
                name !in KNOWN_APPS
            ) {
                add(
                    Result(
                        "call_contact",
                        JSONObject().put("contact", name),
                        intent = "call",
                        score = 0.86
                    )
                )
            }
        }

        // ── Loose media play ("i want to watch X", "put on X") ───
        val watchMatch = Regex("(?:watch|see)\\s+(.+)$").find(normalized)
        if (watchMatch != null && "video" in tokenSet || watchMatch != null &&
            tokenSet.any { it in setOf("video", "videos") }
        ) {
            val query = watchMatch!!.groupValues[1]
                .replace("video", "").replace("videos", "").trim()
            if (query.isNotBlank()) {
                add(
                    Result(
                        "youtube_play",
                        JSONObject().put("query", query),
                        intent = "youtube_play",
                        score = 0.8
                    )
                )
            }
        }
        val listenMatch = Regex("(?:listen to|hear)\\s+(.+)$").find(normalized)
        if (listenMatch != null) {
            val query = listenMatch.groupValues[1].trim()
            if (query.isNotBlank()) {
                add(
                    Result(
                        "spotify_play",
                        JSONObject().put("query", query),
                        intent = "spotify_play",
                        score = 0.8
                    )
                )
            }
        }

        if (candidates.isEmpty()) return null
        val sorted = candidates.sortedByDescending { it.score }
        val best = sorted.first()
        val runnerUp = sorted.getOrNull(1)?.score ?: 0.0
        if (best.score < MIN_SCORE) return null
        if (sorted.size > 1 && best.score - runnerUp < MIN_MARGIN &&
            sorted[1].toolName != best.toolName
        ) {
            AppLog.d(
                "Agent",
                "classifier: ambiguous (${best.intent}=${best.score} vs " +
                    "${sorted[1].intent}=${sorted[1].score}) — leaving to the model"
            )
            return null
        }
        AppLog.i(
            "Agent",
            "classifier: intent=${best.intent} score=${best.score} → ${best.toolName}"
        )
        return best
    }

    /** Lowercase, strip punctuation + politeness wrappers. */
    private fun normalize(text: String): String {
        val words = text.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        // Only strip filler at the EDGES — "turn the light on" must
        // keep its inner words intact.
        var start = 0
        var end = words.size
        while (start < end && words[start] in POLITE_FILLER) start++
        while (end > start && words[end - 1] in POLITE_FILLER) end--
        return words.subList(start, end).joinToString(" ")
    }
}
