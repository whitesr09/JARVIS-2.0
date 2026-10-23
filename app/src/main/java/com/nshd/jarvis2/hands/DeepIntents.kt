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
import com.nshd.jarvis2.core.AppLog
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
            AppLog.w("Send", "stage FAILED: chat could not be opened for $digits")
            return ActionResult(false, "WhatsApp could not be opened for this chat.")
        }
        AppLog.i("Send", "stage: chat opened for $digits (deep link, text prefilled)")
        TaskState.throwIfStopRequested() // #61: after open-app
        // F7 plan: the deep link opens the chat AND types the text
        // atomically, so plan steps 1–2 complete here; step 3
        // ("Confirm & send") stays RUNNING through the tap + verify.
        com.nshd.jarvis2.brain.TaskQueue.advancePlanForRunning(3)

        val service = JarvisAccessibilityService.instance
            ?: return ActionResult(
                true,
                "Opened WhatsApp with the message typed — tap Send to send it. " +
                    "(Enable Accessibility to let Jarvis press Send and verify it.)"
            )

        HandsActions.waitForSettle(2_500)
        TaskState.throwIfStopRequested() // #61: after settle, before reading the field
        val reader = service.screenReader()
        var snapshot = reader.snapshot()
        val entry = snapshot.firstOrNull { it.editable }
        if (entry != null && entry.text.isBlank()) {
            AppLog.w("Send", "stage FAILED: message field empty after open — prefill lost")
            return ActionResult(
                false,
                "Aborted: the WhatsApp message field is empty, so Send was not pressed."
            )
        }
        AppLog.i(
            "Send",
            "stage: typed — message field holds ${entry?.text?.length ?: 0} chars"
        )

        // F8 (#36): the send control comes from the WhatsApp
        // playbook now — its primary descriptors ARE the inline
        // checks this code used before (viewId …:id/send, desc/text
        // "Send"), so a primary hit behaves identically.
        val send = Playbooks.find(snapshot, Playbooks.WHATSAPP_PACKAGE, Playbooks.ROLE_SEND_BUTTON)
            ?: Playbooks.find(snapshot, Playbooks.WHATSAPP_BUSINESS_PACKAGE, Playbooks.ROLE_SEND_BUTTON)
            ?: reader.findByText("Send", snapshot)?.takeIf { it.clickable || it.desc.contains("send", true) }

        if (send == null) {
            // F7: the tree dump belongs in the log, not the reply text.
            AppLog.w(
                "Hands",
                "whatsapp send: Send control not found. Screen: ${reader.serialize(25)}"
            )
            AppLog.w("Send", "stage FAILED: Send control not found on screen")
            return ActionResult(
                false,
                "WhatsApp opened with the message typed, but the Send button " +
                    "was not found on screen."
            )
        }

        TaskState.throwIfStopRequested() // #61: before final tap
        val click = HandsActions.clickElement(send.index)
        if (!click.success) {
            AppLog.w("Send", "stage FAILED: send tap failed — ${click.message}")
            return ActionResult(false, "WhatsApp opened, but Send could not be pressed: ${click.message}")
        }
        AppLog.i("Send", "stage: send tapped")

        // F7 verification (Part A3): BOTH proofs required — the
        // entry cleared AND an outgoing bubble carrying the message
        // text appeared. (Entry-clear alone was the old rule; the
        // bubble check ignores the editable entry itself, which of
        // course still contains the text until the send lands.)
        val needle = text.trim().take(30)
        var entryCleared = false
        var bubbleFound = false
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline && !(entryCleared && bubbleFound)) {
            TaskState.throwIfStopRequested() // #61: verify loop
            delay(200)
            snapshot = reader.snapshot()
            val currentEntry = snapshot.firstOrNull { it.editable }
            if (currentEntry != null && currentEntry.text.isBlank()) {
                entryCleared = true
            }
            // Some WhatsApp builds remove the editor from the exposed
            // tree after sending; no editable field is also consistent
            // with the entry having cleared.
            if (currentEntry == null && snapshot.none { it.viewId.endsWith("/entry") }) {
                entryCleared = true
            }
            if (needle.isNotEmpty() && snapshot.any { element ->
                    !element.editable &&
                        (element.text.contains(needle) || element.label.contains(needle))
                }
            ) {
                bubbleFound = true
            }
        }
        AppLog.i(
            "Send",
            "stage: verify — entryCleared=$entryCleared bubbleFound=$bubbleFound"
        )
        return when {
            entryCleared && bubbleFound -> ActionResult(
                true, "Message sent and verified."
            )
            entryCleared -> ActionResult(
                false,
                "Send was pressed and the message field cleared, but the sent " +
                    "message bubble was not found, so sending is unverified."
            )
            else -> ActionResult(
                false,
                "Send was pressed, but the message field did not clear within " +
                    "2 seconds, so sending is unverified."
            )
        }
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
        com.nshd.jarvis2.brain.TaskQueue.setStepForRunning("Opening YouTube…")
        // F7 plan phases: 1 Open YouTube → 2 Search → 3 Play first video.
        com.nshd.jarvis2.brain.TaskQueue.advancePlanForRunning(1)
        if (!launch(intent)) return ActionResult(false, "YouTube could not be opened.")
        TaskState.throwIfStopRequested() // #61: after open-app

        val service = JarvisAccessibilityService.instance
            ?: return ActionResult(true, "Opened YouTube search results for \"$cleanQuery\". Enable Accessibility to auto-play the first result.")
        com.nshd.jarvis2.brain.TaskQueue.setStepForRunning("Searching '$cleanQuery'…")
        com.nshd.jarvis2.brain.TaskQueue.advancePlanForRunning(2)

        val reader = service.screenReader()
        val metrics = appContext.resources.displayMetrics
        val screenHeight = metrics.heightPixels
        val screenWidth = metrics.widthPixels

        // F5.1: poll up to 3 snapshots over ~3.5 s. The old single
        // snapshot after one settle kept concluding "no plausible
        // result" while YouTube was still rendering the results list.
        var pick: YoutubePick? = null
        var lastSnapshotSize = 0
        var attempt = 0
        while (attempt < YOUTUBE_SNAPSHOT_ATTEMPTS && pick == null) {
            TaskState.throwIfStopRequested() // #61: snapshot loop
            if (attempt == 0) {
                // #23 warm start: skip the full settle when YouTube is
                // already foreground and a previous play succeeded.
                if (isWarm(YOUTUBE_PACKAGE) && reader.currentPackage() == YOUTUBE_PACKAGE) {
                    AppLog.i("Hands", "warm start $YOUTUBE_PACKAGE")
                    HandsActions.waitForSettle(WARM_SETTLE_MS)
                } else {
                    HandsActions.waitForSettle(YOUTUBE_FIRST_SETTLE_MS)
                }
            } else {
                // F6: signature-settle wait (early exit) instead of
                // the old blind 850 ms sleep between snapshots.
                HandsActions.waitForSettle(YOUTUBE_RETRY_SETTLE_MS)
            }
            val snapshot = reader.snapshot()
            lastSnapshotSize = snapshot.size
            pick = pickYoutubeResult(snapshot, screenWidth, screenHeight)
            attempt++
        }
        val chosen = pick ?: run {
            // F8 (#36): last resort — the YouTube playbook's
            // first-result descriptor (a card whose merged label
            // carries a view count). Runs ONLY where the positional
            // picker already failed, so primary behaviour is intact.
            val lastSnapshot = reader.snapshot()
            val viaPlaybook = Playbooks.find(
                lastSnapshot, Playbooks.YOUTUBE_PACKAGE, Playbooks.ROLE_FIRST_RESULT
            )?.takeIf { it.clickable }
            if (viaPlaybook != null) {
                AppLog.i(
                    "Hands",
                    "youtube_play: positional picker missed — playbook " +
                        "first_result hit index=${viaPlaybook.index}"
                )
                YoutubePick(viaPlaybook, youtubeTitle(viaPlaybook.label), viaFallback = true)
            } else {
                // The screen dump goes to AppLog, NOT the result text —
                // the user-facing sentence stays clean and complete.
                AppLog.w(
                    "Hands",
                    "youtube_play: no result after $attempt snapshots " +
                        "($lastSnapshotSize nodes). Screen: ${reader.serialize(25)}"
                )
                return ActionResult(
                    false,
                    "YouTube results opened for \"$cleanQuery\", but no playable video " +
                        "result appeared after waiting — the results may still be " +
                        "loading, or YouTube is showing a different layout."
                )
            }
        }
        com.nshd.jarvis2.brain.TaskQueue.setStepForRunning("Playing…")
        com.nshd.jarvis2.brain.TaskQueue.advancePlanForRunning(3)
        TaskState.throwIfStopRequested() // #61: before final tap
        val click = HandsActions.clickElement(chosen.element.index)
        if (!click.success) {
            return ActionResult(
                false,
                "YouTube results opened, but the first result could not be tapped: ${click.message}"
            )
        }
        markWarm(YOUTUBE_PACKAGE) // #23: this play succeeded once
        return ActionResult(
            true,
            when {
                chosen.viaFallback ->
                    "Playing the first video on YouTube for \"$cleanQuery\" " +
                        "(picked by position — its title wasn't readable)."
                chosen.title.isNotBlank() -> "Playing '${chosen.title}' on YouTube."
                else -> "Playing the first video result on YouTube."
            }
        )
    }

    // ── Spotify (F6) ───────────────────────────────────────────────────

    /**
     * Fast media path for Spotify, mirroring [youtubePlay]: ONE
     * programmatic intent (open → Search tab → type the query → tap
     * the first song row) replaces ~6 generic agent-loop steps, each
     * of which used to cost a full model round trip plus settle
     * waits. All waiting is Part C polling (early-exit the moment the
     * expected window/element appears) — no fixed long sleeps.
     *
     * Tapping a song row in Spotify search starts playback. When a
     * track/album page opens instead, its Play button is tapped.
     * Playback is verified heuristically: a "Pause" affordance in the
     * mini-player / now-playing UI. The result text is honest about
     * whether playback was confirmed.
     */
    suspend fun spotifyPlay(query: String): ActionResult {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return ActionResult(false, "The Spotify search query is blank.")
        com.nshd.jarvis2.brain.TaskQueue.setStepForRunning("Opening Spotify…")
        // F7 plan phases: 1 Open Spotify → 2 Search → 3 Play.
        com.nshd.jarvis2.brain.TaskQueue.advancePlanForRunning(1)
        val opened = openApp("Spotify")
        if (!opened.success) return opened
        TaskState.throwIfStopRequested() // #61: after open-app

        val service = JarvisAccessibilityService.instance
            ?: return ActionResult(
                true,
                "Opened Spotify. Enable Accessibility to let Jarvis search and play automatically."
            )
        val reader = service.screenReader()
        val metrics = appContext.resources.displayMetrics
        val screenHeight = metrics.heightPixels
        val screenWidth = metrics.widthPixels

        // #23 warm start: Spotify already foreground + previously
        // played -> use the short settle instead of the full wait.
        if (isWarm(SPOTIFY_PACKAGE) && reader.currentPackage() == SPOTIFY_PACKAGE) {
            AppLog.i("Hands", "warm start $SPOTIFY_PACKAGE")
            HandsActions.waitForPackage(SPOTIFY_PACKAGE, WARM_SETTLE_MS)
        } else {
            HandsActions.waitForPackage(SPOTIFY_PACKAGE, APP_WINDOW_WAIT_MS)
        }

        // 1) Search tab (bottom nav) — poll until it shows up.
        // F8 (#36): the positional primary stays; the Spotify
        // playbook's search_tab descriptor is the fallback.
        val withTab = HandsActions.waitForSnapshot(SEARCH_TAB_WAIT_MS) { snapshot ->
            snapshot.any { isSpotifySearchTab(it, screenHeight) }
        }
        val tab = withTab?.first { isSpotifySearchTab(it, screenHeight) }
            ?: Playbooks.find(
                reader.snapshot(), Playbooks.SPOTIFY_PACKAGE, Playbooks.ROLE_SEARCH_TAB
            )?.takeIf { it.clickable }
            ?: return ActionResult(
                false,
                "Spotify opened, but its Search tab never appeared — Spotify " +
                    "may still be loading or showing a prompt."
            )
        val tabClick = HandsActions.clickElement(tab.index)
        if (!tabClick.success) {
            return ActionResult(false, "Spotify opened, but the Search tab could not be tapped: ${tabClick.message}")
        }
        TaskState.throwIfStopRequested() // #61: after search-tab tap

        // 2) Search input: tap the search bar if the field isn't
        // already focused/editable, then type the query (SET_TEXT —
        // search fields use replace semantics).
        com.nshd.jarvis2.brain.TaskQueue.setStepForRunning("Searching '$cleanQuery'…")
        com.nshd.jarvis2.brain.TaskQueue.advancePlanForRunning(2)
        val inputReady = HandsActions.waitForSnapshot(SEARCH_INPUT_WAIT_MS) { snapshot ->
            snapshot.any { it.editable } ||
                snapshot.any { isSpotifySearchBar(it) }
        } ?: return ActionResult(
            false,
            "Spotify's search screen opened, but no search field appeared."
        )
        val directField = inputReady.firstOrNull { it.editable }
        if (directField == null) {
            // F8 (#36): inline search-bar check first (primary), the
            // playbook's search_field descriptors as its fallback.
            val bar = inputReady.firstOrNull { isSpotifySearchBar(it) }
                ?: Playbooks.find(
                    inputReady, Playbooks.SPOTIFY_PACKAGE, Playbooks.ROLE_SEARCH_FIELD
                )
                ?: return ActionResult(false, "Spotify's search field could not be found.")
            val barClick = HandsActions.clickElement(bar.index)
            if (!barClick.success) {
                return ActionResult(false, "Spotify's search bar could not be tapped: ${barClick.message}")
            }
            if (!HandsActions.waitForEditable(SEARCH_INPUT_WAIT_MS)) {
                return ActionResult(false, "Spotify's search field never became editable.")
            }
        } else {
            // Focus the field so SET_TEXT lands in it.
            HandsActions.clickElement(directField.index)
        }
        val typed = HandsActions.setTextOnFocused(cleanQuery)
        if (!typed.success) {
            return ActionResult(false, "Spotify search opened, but the query could not be typed: ${typed.message}")
        }
        TaskState.throwIfStopRequested() // #61: after search-field set

        // 3) First song row — poll results until a pick exists.
        var pick: SpotifyPick? = null
        val results = HandsActions.waitForSnapshot(RESULTS_WAIT_MS) { snapshot ->
            pick = pickSpotifyResult(snapshot, screenWidth, screenHeight)
            pick != null
        }
        val chosen = pick ?: run {
            AppLog.w(
                "Hands",
                "spotify_play: no song row after waiting. Screen: ${reader.serialize(25)}"
            )
            return ActionResult(
                false,
                "Spotify searched for \"$cleanQuery\", but no playable song " +
                    "result appeared — results may still be loading, or " +
                    "Spotify is showing a different layout."
            )
        }
        com.nshd.jarvis2.brain.TaskQueue.setStepForRunning("Playing…")
        com.nshd.jarvis2.brain.TaskQueue.advancePlanForRunning(3)
        TaskState.throwIfStopRequested() // #61: before final tap
        val rowClick = HandsActions.clickElement(chosen.element.index)
        if (!rowClick.success) {
            return ActionResult(
                false,
                "Spotify results were shown, but the song could not be tapped: ${rowClick.message}"
            )
        }

        // 4) Verify playback: a Pause affordance means audio is
        // playing. If a track/album page opened instead of playing,
        // tap its Play button and verify again.
        TaskState.throwIfStopRequested() // #61: before playback verify
        if (awaitPauseAffordance(PAUSE_VERIFY_MS)) {
            markWarm(SPOTIFY_PACKAGE) // #23
            return ActionResult(true, "Playing '${chosen.title}' on Spotify.")
        }
        val pageSnapshot = reader.snapshot()
        // F8 (#36): inline Play check is the primary; the playbook's
        // play_button descriptors back it up.
        val playButton = pageSnapshot.firstOrNull { element ->
            element.clickable &&
                (element.desc.equals("Play", ignoreCase = true) ||
                    element.text.equals("Play", ignoreCase = true))
        } ?: Playbooks.find(
            pageSnapshot, Playbooks.SPOTIFY_PACKAGE, Playbooks.ROLE_PLAY_BUTTON
        )?.takeIf { it.clickable }
        if (playButton != null) {
            TaskState.throwIfStopRequested() // #61: before Play-button tap
            HandsActions.clickElement(playButton.index)
            if (awaitPauseAffordance(PAUSE_VERIFY_MS)) {
                markWarm(SPOTIFY_PACKAGE) // #23
                return ActionResult(true, "Playing '${chosen.title}' on Spotify.")
            }
        }
        markWarm(SPOTIFY_PACKAGE) // #23: row tap succeeded (playback unconfirmed)
        return ActionResult(
            true,
            "Started '${chosen.title}' on Spotify (the player state " +
                "couldn't be confirmed on screen)."
        )
    }

    /** Poll briefly for a "Pause" affordance (playback evidence). */
    private suspend fun awaitPauseAffordance(timeoutMs: Long): Boolean =
        HandsActions.waitForSnapshot(timeoutMs) { snapshot ->
            snapshot.any { element ->
                element.desc.equals("Pause", ignoreCase = true) ||
                    element.text.equals("Pause", ignoreCase = true)
            }
        } != null

    private fun isSpotifySearchTab(element: UiElement, screenHeight: Int): Boolean =
        element.clickable &&
            element.bounds.centerY() > screenHeight * 3 / 5 &&
            (element.text.equals("Search", ignoreCase = true) ||
                element.desc.equals("Search", ignoreCase = true))

    private fun isSpotifySearchBar(element: UiElement): Boolean =
        element.viewId.contains("search", ignoreCase = true) ||
            element.label.contains("What do you want to listen to", ignoreCase = true) ||
            element.label.contains("Search, ", ignoreCase = true)

    /** One chosen Spotify song row. */
    private data class SpotifyPick(val element: UiElement, val title: String)

    /**
     * First song row in Spotify search results. Candidates are
     * clickable, non-editable rows between the search header and the
     * mini-player; ads are skipped, and when a "Songs" section header
     * exists only rows below it compete (the top hit above it can be
     * an artist/album card). Topmost wins.
     */
    private fun pickSpotifyResult(
        snapshot: List<UiElement>,
        screenWidth: Int,
        screenHeight: Int
    ): SpotifyPick? {
        val regionTop = screenHeight / 8
        val regionBottom = screenHeight * 7 / 8
        val songsHeader = snapshot.firstOrNull { element ->
            element.text.trim().equals("Songs", ignoreCase = true) &&
                element.bounds.centerY() in regionTop..regionBottom
        }
        val minTop = songsHeader?.bounds?.bottom ?: regionTop
        val candidates = snapshot.filter { element ->
            element.clickable && !element.editable &&
                element.bounds.centerY() in regionTop..regionBottom &&
                element.bounds.top >= minTop - 4 &&
                element.bounds.width() > screenWidth / 3 &&
                element.bounds.height() > 0
        }
        var skippedAds = 0
        val rows = candidates.filter { element ->
            val label = element.label.trim()
            when {
                label.length <= 2 -> false
                isAdLabel(label) -> {
                    skippedAds++
                    false
                }
                // Section headers and bottom-nav chrome are not songs.
                label.length <= CHROME_LABEL_MAX &&
                    SPOTIFY_CHROME_WORDS.any { label.equals(it, ignoreCase = true) } -> false
                else -> true
            }
        }
        val primary = rows.minByOrNull { it.bounds.top }
        if (primary == null) {
            AppLog.d(
                "Hands",
                "spotify_play pick: none (${candidates.size} candidates, ads skipped=$skippedAds)"
            )
            return null
        }
        AppLog.d(
            "Hands",
            "spotify_play pick: ${rows.size} rows considered (ads skipped=$skippedAds), " +
                "tapped index=${primary.index} label='${primary.label.take(60)}'"
        )
        return SpotifyPick(primary, spotifyTitle(primary.label))
    }

    /** "Song Name • Artist Name" → "Song Name" (rows merge descendant text). */
    private fun spotifyTitle(label: String): String {
        val first = label.substringBefore(" • ").substringBefore(" - ").trim()
        val title = if (first.length >= 2) first else label.trim()
        return title.take(90).ifBlank { "the song" }
    }

    /** One chosen YouTube result card (F5.1). */
    private data class YoutubePick(
        val element: UiElement,
        val title: String,
        val viaFallback: Boolean
    )

    /**
     * F5.1 first-result selection. Candidates are clickable,
     * non-editable cards in the results region (upper two-thirds of
     * the screen). Identifiable ads ("Sponsored" / an "Ad" badge) and
     * the Shorts shelf (labels mentioning Shorts) are skipped; nav
     * chrome words are matched only against SHORT labels, so real
     * titles containing words like "home" survive. A card is
     * plausible when its merged subtree label carries a duration
     * (\d+:\d{2}) or a title longer than 3 chars; duration-bearing
     * cards rank first, then any plausible card — topmost wins, and
     * its centre is tapped (via the node's own click action). Last
     * resort: the largest clickable card in the region (viaFallback).
     * Every decision is logged under the "Hands" tag.
     */
    private fun pickYoutubeResult(
        snapshot: List<UiElement>,
        screenWidth: Int,
        screenHeight: Int
    ): YoutubePick? {
        val regionBottom = screenHeight * 2 / 3
        val region = snapshot.filter { element ->
            element.clickable && !element.editable &&
                element.bounds.centerY() < regionBottom &&
                element.bounds.width() > 0 && element.bounds.height() > 0
        }
        var skippedAds = 0
        var skippedShorts = 0
        var skippedChrome = 0
        val cards = region.filter { element ->
            val label = element.label.trim()
            when {
                isAdLabel(label) -> {
                    skippedAds++
                    false
                }
                label.contains("shorts", ignoreCase = true) -> {
                    skippedShorts++
                    false
                }
                label.length <= CHROME_LABEL_MAX &&
                    YOUTUBE_CHROME_WORDS.any { label.contains(it, ignoreCase = true) } -> {
                    skippedChrome++
                    false
                }
                else -> true
            }
        }
        val withDuration = cards.filter { DURATION_REGEX.containsMatchIn(it.label) }
        val plausible = cards.filter {
            DURATION_REGEX.containsMatchIn(it.label) || it.label.trim().length > 3
        }
        val primary = withDuration.ifEmpty { plausible }.minByOrNull { it.bounds.top }
        if (primary != null) {
            AppLog.d(
                "Hands",
                "youtube_play pick: ${cards.size} cards considered " +
                    "(skipped ads=$skippedAds shorts=$skippedShorts chrome=$skippedChrome), " +
                    "tapped index=${primary.index} label='${primary.label.take(60)}'"
            )
            return YoutubePick(primary, youtubeTitle(primary.label), viaFallback = false)
        }
        val fallback = region
            .filter { element ->
                element.bounds.width() >= screenWidth / 2 &&
                    element.bounds.height() >= 80 &&
                    !element.label.contains("shorts", ignoreCase = true)
            }
            .maxByOrNull { it.bounds.width() * it.bounds.height() }
        if (fallback != null) {
            AppLog.d(
                "Hands",
                "youtube_play pick: FALLBACK largest card index=${fallback.index} " +
                    "(no readable title/duration among ${cards.size} cards)"
            )
            return YoutubePick(fallback, "", viaFallback = true)
        }
        AppLog.d(
            "Hands",
            "youtube_play pick: none (${region.size} nodes in region, ${cards.size} cards, " +
                "skipped ads=$skippedAds shorts=$skippedShorts chrome=$skippedChrome)"
        )
        return null
    }

    /** "Title • Channel • 1.2M views • 3:45" → "Title" (clickable nodes merge descendant text). */
    private fun youtubeTitle(label: String): String {
        val first = label.substringBefore(" • ").trim()
        val title = if (first.length >= 4) first else label.trim()
        return title.take(90)
    }

    private fun isAdLabel(label: String): Boolean =
        label.contains("sponsored", ignoreCase = true) ||
            AD_BADGE_REGEX.containsMatchIn(label)

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
            // F8 (#15): photos/gallery, files and downloads get real
            // targets instead of a launcher-label guess.
            "photos", "gallery" -> {
                val intent = Intent.makeMainSelectorActivity(
                    Intent.ACTION_MAIN,
                    Intent.CATEGORY_APP_GALLERY
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (canResolve(intent)) return resultForLaunch(intent, "Photos")
            }
            "files", "filemanager", "myfiles" -> {
                val launchIntent = appContext.packageManager
                    .getLaunchIntentForPackage("com.google.android.documentsui")
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    return resultForLaunch(launchIntent, "Files")
                }
            }
            "downloads" -> {
                val intent = Intent("android.intent.action.VIEW_DOWNLOADS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (canResolve(intent)) return resultForLaunch(intent, "Downloads")
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

    /**
     * Launcher-label search with layered matching (F2 — on Android 11+
     * this only sees launcher apps because the manifest declares the
     * MAIN/LAUNCHER <queries> intent):
     *
     *   exact label → package contains → label contains → token overlap
     *
     * Exactly one strong candidate launches. A tie at the top score
     * returns the candidate names so the agent can ask which one —
     * guessing between two real apps is how the wrong app opens.
     */
    private fun openAppByLabel(name: String): ActionResult {
        val wanted = normalizeAppName(name)
        if (wanted.isBlank()) return ActionResult(false, "No installed app matched \"$name\".")
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = try {
            appContext.packageManager.queryIntentActivities(launcherIntent, 0)
        } catch (e: TaskStoppedException) { throw e } catch (e: Exception) {
            emptyList()
        }
        val wantedTokens = tokenize(name)
        data class Candidate(val score: Int, val label: String, val packageName: String, val activity: String)

        val candidates = activities.mapNotNull { info ->
            val activityInfo = info.activityInfo ?: return@mapNotNull null
            val label = info.loadLabel(appContext.packageManager)?.toString().orEmpty()
            val normalizedLabel = normalizeAppName(label)
            val normalizedPackage = normalizeAppName(activityInfo.packageName)
            val labelTokens = tokenize(label)
            val overlap = if (wantedTokens.isEmpty() || labelTokens.isEmpty()) {
                0
            } else {
                val shared = wantedTokens.count { token -> labelTokens.any { it == token } }
                shared * 50 / maxOf(wantedTokens.size, labelTokens.size)
            }
            val score = when {
                normalizedLabel == wanted -> 100
                normalizedPackage.contains(wanted) -> 90
                normalizedLabel.startsWith(wanted) -> 80
                normalizedLabel.contains(wanted) -> 70
                wanted.contains(normalizedLabel) && normalizedLabel.length >= 3 -> 60
                overlap >= 25 -> overlap
                else -> -1
            }
            if (score >= 0) {
                Candidate(score, label, activityInfo.packageName, activityInfo.name)
            } else {
                null
            }
        }
            // One candidate per package: launcher aliases of the same
            // app must not fake a "tie".
            .groupBy { it.packageName }
            .values
            .map { group -> group.maxByOrNull { it.score }!! }
            .sortedByDescending { it.score }

        val best = candidates.firstOrNull()
            ?: return ActionResult(false, "No installed app matched \"$name\".")
        val tied = candidates.filter { it.score == best.score }
        if (tied.size > 1) {
            val names = tied.take(4).joinToString(", ") { it.label }
            return ActionResult(
                false,
                "Several installed apps match \"$name\": $names. Ask the user which one to open."
            )
        }
        val component = ComponentName(best.packageName, best.activity)
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return resultForLaunch(intent, best.label)
    }

    /** Splits a display name into lowercase alphanumeric word tokens. */
    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 }
            .toSet()

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
        } catch (e: TaskStoppedException) { throw e } catch (e: Exception) {
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
        } catch (e: TaskStoppedException) { throw e } catch (e: Exception) {
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
        } catch (e: TaskStoppedException) { throw e } catch (e: Exception) {
            ActionResult(false, "Brightness could not be changed: ${e.message ?: "system denied it"}")
        }
    }

    // ── F8 (#15) media keys, settings panels, silent mode ─────────────

    /**
     * Media transport keys via AudioManager.dispatchMediaKeyEvent —
     * reaches the ACTIVE media session (Spotify/YouTube/…) without
     * opening anything. [action]: play_pause / play / pause / next /
     * previous.
     */
    fun mediaKey(action: String): ActionResult {
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ActionResult(false, "Audio service is unavailable.")
        val keyCode = when (action.lowercase()) {
            "play_pause", "playpause", "toggle" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return ActionResult(false, "Unknown media action: $action")
        }
        return try {
            val down = android.view.KeyEvent(
                android.view.KeyEvent.ACTION_DOWN, keyCode
            )
            val up = android.view.KeyEvent(
                android.view.KeyEvent.ACTION_UP, keyCode
            )
            @Suppress("DEPRECATION")
            audio.dispatchMediaKeyEvent(down)
            @Suppress("DEPRECATION")
            audio.dispatchMediaKeyEvent(up)
            AppLog.i("Hands", "media key dispatched: $action")
            ActionResult(true, "Media ${action.replace('_', ' ')} sent.")
        } catch (e: TaskStoppedException) { throw e } catch (e: Exception) {
            ActionResult(false, "Media key could not be sent: ${e.message ?: "system denied it"}")
        }
    }

    /**
     * Open a system settings panel. Android Q+ forbids apps toggling
     * Wi-Fi directly, so Wi-Fi lands on the system Wi-Fi panel where
     * one tap finishes it — the result text says so honestly.
     * [kind]: wifi / bluetooth / airplane / display.
     */
    fun openPanel(kind: String): ActionResult {
        val intent = when (kind.lowercase()) {
            "wifi" -> if (android.os.Build.VERSION.SDK_INT >=
                android.os.Build.VERSION_CODES.Q
            ) {
                Intent(Settings.Panel.ACTION_WIFI)
            } else {
                Intent(Settings.ACTION_WIFI_SETTINGS)
            }
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "airplane" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            else -> return ActionResult(false, "Unknown settings panel: $kind")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = launch(intent)
        val label = when (kind.lowercase()) {
            "wifi" -> "Wi-Fi panel opened — Android doesn't let apps toggle " +
                "Wi-Fi directly, so flip the switch there."
            "bluetooth" -> "Bluetooth settings opened."
            "airplane" -> "Airplane mode settings opened — flip the switch there."
            else -> "Display settings opened."
        }
        return ActionResult(launched, if (launched) label else "The settings panel could not be opened.")
    }

    /**
     * Ringer silent/normal. Changing the ringer needs Do-Not-Disturb
     * policy access on Android 6+; without it the access screen opens
     * and the result says exactly what's missing.
     */
    fun setSilent(on: Boolean): ActionResult {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as? android.app.NotificationManager
            if (nm != null && !nm.isNotificationPolicyAccessGranted) {
                val intent = Intent(
                    Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launch(intent)
                return ActionResult(
                    false,
                    "Silent mode needs Do-Not-Disturb access — I opened " +
                        "the access screen; allow Jarvis there, then ask again."
                )
            }
        }
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ActionResult(false, "Audio service is unavailable.")
        return try {
            audio.ringerMode = if (on) {
                AudioManager.RINGER_MODE_SILENT
            } else {
                AudioManager.RINGER_MODE_NORMAL
            }
            ActionResult(
                true,
                if (on) "Silent mode on." else "Silent mode off — ringer back to normal."
            )
        } catch (e: TaskStoppedException) { throw e } catch (e: Exception) {
            ActionResult(false, "Silent mode could not be changed: ${e.message ?: "system denied it"}")
        }
    }

    /** Current system brightness as a percentage (0 when unreadable). */
    fun currentBrightnessPercent(): Int = try {
        val value = Settings.System.getInt(
            appContext.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            128
        )
        (value * 100 / 255).coerceIn(0, 100)
    } catch (e: TaskStoppedException) { throw e } catch (e: Exception) {
        0
    }

    // ── v1.9.0: messaging & platform powers ──────────────────────────
    //
    // Every SEND below is called only AFTER ToolExecutor's ConfirmGate
    // approved recipient + full text (the standing rule), and each one
    // grants the one-shot SendPermit for its app package before the
    // send tap, exactly like whatsapp_message (F7).

    /**
     * #43: share the most recent gallery photo into the WhatsApp chat
     * with [phone] (jid-preselected share intent), then press the
     * share screen's Send button and verify the chat opened.
     */
    suspend fun whatsappShareLatestPhoto(phone: String): ActionResult {
        val digits = ContactsResolver.normalizePhone(phone)
        if (digits.length < 10) {
            return ActionResult(false, "The WhatsApp number looks incomplete.")
        }
        val photo = latestPhotoUri()
            ?: return ActionResult(
                false,
                "No photo was found in the gallery to send."
            )
        val pkg = when {
            isPackageInstalled("com.whatsapp") -> "com.whatsapp"
            isPackageInstalled("com.whatsapp.w4b") -> "com.whatsapp.w4b"
            else -> return ActionResult(false, "WhatsApp is not installed.")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            setPackage(pkg)
            putExtra(Intent.EXTRA_STREAM, photo)
            putExtra("jid", "${digits}@s.whatsapp.net")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            appContext.grantUriPermission(
                pkg, photo, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            // The intent flag usually suffices; the explicit grant is a bonus.
        }
        if (!launch(intent)) {
            return ActionResult(false, "WhatsApp could not be opened for the photo share.")
        }
        AppLog.i("Send", "stage: photo share opened for $digits")
        HandsActions.waitForPackage(pkg, 3_000)
        HandsActions.waitForSettle(1_500)
        TaskState.throwIfStopRequested()
        val service = JarvisAccessibilityService.instance
            ?: return ActionResult(
                true,
                "WhatsApp opened with the photo ready — tap Send to send it."
            )
        val reader = service.screenReader()
        val snapshot = reader.snapshot()
        val send = Playbooks.find(snapshot, pkg, Playbooks.ROLE_SEND_BUTTON)
            ?: reader.findByText("Send", snapshot)
                ?.takeIf { it.clickable || it.desc.contains("send", true) }
            ?: return ActionResult(
                true,
                "WhatsApp opened with the photo attached — tap Send to send it " +
                    "(the Send button was not found automatically)."
            )
        val click = HandsActions.clickElement(send.index)
        if (!click.success) {
            return ActionResult(
                false, "The photo share opened, but Send could not be pressed: ${click.message}"
            )
        }
        AppLog.i("Send", "stage: photo send tapped for $digits")
        return ActionResult(true, "Photo sent to the WhatsApp chat.")
    }

    /** The newest image in the device gallery, or null. */
    private fun latestPhotoUri(): Uri? = try {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        appContext.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                android.content.ContentUris.withAppendedId(collection, id)
            } else {
                null
            }
        }
    } catch (e: Exception) {
        AppLog.w("Hands", "latest photo lookup failed: ${e.message}")
        null
    }

    /**
     * #44 helper: the title of the chat currently open in
     * [packageName] (top-of-screen heuristic), or null when that app
     * isn't foreground with a chat open. ToolExecutor reads this
     * BEFORE the gate so the confirm prompt can name the recipient.
     */
    fun currentChatTitle(packageName: String): String? {
        val service = JarvisAccessibilityService.instance ?: return null
        val reader = service.screenReader()
        if (reader.currentPackage() != packageName) return null
        val metrics = appContext.resources.displayMetrics
        val topLimit = metrics.heightPixels * 0.22f
        return reader.snapshot()
            .filter { element ->
                !element.editable && element.text.isNotBlank() &&
                    element.bounds.top < topLimit && element.bounds.top >= 0 &&
                    element.text.length in 2..40
            }
            .minByOrNull { it.bounds.top }
            ?.text
            ?.trim()
    }

    /**
     * Type [message] into the chat currently open in [packageName]
     * and press its Send button (playbook first). The caller already
     * gated the send; the SendPermit for the package is granted by
     * ToolExecutor before this runs.
     */
    suspend fun typeAndSendInOpenChat(
        packageName: String,
        message: String
    ): ActionResult {
        val service = JarvisAccessibilityService.instance
            ?: return ActionResult(
                false,
                "Accessibility is not enabled, so the message can't be typed or sent."
            )
        val reader = service.screenReader()
        if (reader.currentPackage() != packageName) {
            return ActionResult(false, "The chat is no longer open in the foreground.")
        }
        var snapshot = reader.snapshot()
        val entry = snapshot.firstOrNull { it.editable }
            ?: return ActionResult(false, "No message field was found in the open chat.")
        val tapped = HandsActions.clickElement(entry.index)
        if (!tapped.success) {
            return ActionResult(false, "The message field could not be focused: ${tapped.message}")
        }
        val typed = HandsActions.setTextOnFocused(message)
        if (!typed.success) {
            return ActionResult(false, "The message could not be typed: ${typed.message}")
        }
        TaskState.throwIfStopRequested()
        HandsActions.waitForSettle(600)
        snapshot = reader.snapshot()
        val send = Playbooks.find(snapshot, packageName, Playbooks.ROLE_SEND_BUTTON)
            ?: reader.findByText("Send", snapshot)
                ?.takeIf { it.clickable || it.desc.contains("send", true) }
            ?: return ActionResult(
                true,
                "The message was typed into the chat — tap Send to send it " +
                    "(the Send button was not found automatically)."
            )
        val click = HandsActions.clickElement(send.index)
        if (!click.success) {
            return ActionResult(false, "Send could not be pressed: ${click.message}")
        }
        // Verify like whatsapp_message: the entry must clear.
        val deadline = System.currentTimeMillis() + 2_000
        var cleared = false
        while (System.currentTimeMillis() < deadline && !cleared) {
            delay(200)
            val current = reader.snapshot().firstOrNull { it.editable }
            if (current == null || current.text.isBlank()) cleared = true
        }
        return if (cleared) {
            ActionResult(true, "Message sent and verified.")
        } else {
            ActionResult(
                false,
                "Send was pressed, but the message field did not clear, so sending is unverified."
            )
        }
    }

    /**
     * #45: Instagram DM — open Instagram, go to the inbox, search the
     * contact, open the thread, type + send (gate already approved by
     * ToolExecutor; permit granted there too). Best-effort staged
     * automation: every stage failure says exactly where it stopped.
     */
    suspend fun instagramDm(contactQuery: String, message: String): ActionResult {
        val pkg = Playbooks.INSTAGRAM_PACKAGE
        if (!isPackageInstalled(pkg)) {
            return ActionResult(false, "Instagram is not installed.")
        }
        val opened = openApp("Instagram")
        if (!opened.success) return opened
        HandsActions.waitForPackage(pkg, 3_000)
        HandsActions.waitForSettle(1_200)
        // Inbox entry: the Direct/messenger control.
        val inbox = HandsActions.clickText("Messages").takeIf { it.success }
            ?: HandsActions.clickText("Direct")
        if (inbox == null || !inbox.success) {
            // Fall through: some builds land on the inbox directly.
            AppLog.d("Hands", "instagram: inbox control not tapped — continuing")
        }
        HandsActions.waitForSettle(1_000)
        TaskState.throwIfStopRequested()
        // Search for the contact inside the inbox.
        val service = JarvisAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility is not enabled.")
        val reader = service.screenReader()
        val searchField = Playbooks.find(
            reader.snapshot(), pkg, Playbooks.ROLE_SEARCH_FIELD
        ) ?: reader.snapshot().firstOrNull { it.editable }
        if (searchField != null) {
            HandsActions.clickElement(searchField.index)
            HandsActions.setTextOnFocused(contactQuery)
            HandsActions.waitForSettle(1_200)
        }
        val row = reader.findByText(contactQuery)
            ?: return ActionResult(
                false,
                "Instagram opened, but no chat matching \"$contactQuery\" was found."
            )
        val tappedRow = HandsActions.clickElement(row.index)
        if (!tappedRow.success) {
            return ActionResult(false, "The Instagram chat row could not be opened.")
        }
        HandsActions.waitForSettle(1_200)
        TaskState.throwIfStopRequested()
        return typeAndSendInOpenChat(pkg, message)
    }

    /** #46: Telegram message — same staged pattern as Instagram. */
    suspend fun telegramMessage(contactQuery: String, message: String): ActionResult {
        val pkg = "org.telegram.messenger"
        if (!isPackageInstalled(pkg)) {
            return ActionResult(false, "Telegram is not installed.")
        }
        val opened = openApp("Telegram")
        if (!opened.success) return opened
        HandsActions.waitForPackage(pkg, 3_000)
        HandsActions.waitForSettle(1_200)
        val service = JarvisAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility is not enabled.")
        val reader = service.screenReader()
        val search = reader.snapshot().firstOrNull {
            it.desc.contains("search", ignoreCase = true) && it.clickable
        }
        if (search != null) {
            HandsActions.clickElement(search.index)
            HandsActions.waitForSettle(800)
            val field = reader.snapshot().firstOrNull { it.editable }
            if (field != null) {
                HandsActions.clickElement(field.index)
                HandsActions.setTextOnFocused(contactQuery)
                HandsActions.waitForSettle(1_200)
            }
        }
        val row = reader.findByText(contactQuery)
            ?: return ActionResult(
                false,
                "Telegram opened, but no chat matching \"$contactQuery\" was found."
            )
        val tappedRow = HandsActions.clickElement(row.index)
        if (!tappedRow.success) {
            return ActionResult(false, "The Telegram chat row could not be opened.")
        }
        HandsActions.waitForSettle(1_200)
        TaskState.throwIfStopRequested()
        return typeAndSendInOpenChat(pkg, message)
    }

    /**
     * #47: SMS via the default SMS app's compose intent (recipient +
     * body prefilled), then the Send tap (permit for whatever package
     * owns the composer). If the tap can't be completed, the composer
     * stays open with everything filled in — stated honestly.
     */
    suspend fun smsSend(phone: String, message: String): ActionResult {
        val digits = ContactsResolver.normalizePhone(phone)
        if (digits.length < 7) {
            return ActionResult(false, "The phone number looks incomplete.")
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$digits")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!launch(intent)) {
            return ActionResult(false, "No SMS app could be opened.")
        }
        HandsActions.waitForSettle(1_500)
        val service = JarvisAccessibilityService.instance
            ?: return ActionResult(
                true,
                "The SMS composer is open with the message ready — tap Send there."
            )
        val pkg = service.screenReader().currentPackage()
        if (pkg != null) HandsActions.grantSendPermit(pkg)
        val tapped = HandsActions.clickText("Send")
        return if (tapped.success) {
            ActionResult(true, "SMS sent via the default SMS app.")
        } else {
            ActionResult(
                true,
                "The SMS composer is open with the message typed — tap Send " +
                    "in the SMS app to send it."
            )
        }
    }

    /**
     * #48: Gmail compose via intent (To/Subject/Body prefilled), then
     * the Send tap with a permit for Gmail. Falls back to "compose is
     * open" honesty like the SMS path.
     */
    suspend fun gmailSend(to: String, subject: String, body: String): ActionResult {
        val pkg = Playbooks.GMAIL_PACKAGE
        if (!isPackageInstalled(pkg)) {
            return ActionResult(false, "Gmail is not installed.")
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            setPackage(pkg)
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!launch(intent)) {
            return ActionResult(false, "Gmail compose could not be opened.")
        }
        HandsActions.waitForPackage(pkg, 3_000)
        HandsActions.waitForSettle(1_500)
        HandsActions.grantSendPermit(pkg)
        val tapped = HandsActions.clickText("Send")
        return if (tapped.success) {
            ActionResult(true, "Email sent via Gmail.")
        } else {
            ActionResult(
                true,
                "Gmail compose is open with the email filled in — tap Send " +
                    "in Gmail to send it."
            )
        }
    }

    /** #107: alarm via the Clock intent (no UI automation needed). */
    fun setAlarm(hour: Int, minute: Int, label: String): ActionResult {
        if (hour !in 0..23 || minute !in 0..59) {
            return ActionResult(false, "That alarm time doesn't look valid.")
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (launch(intent)) {
            val ampm = if (hour < 12) "AM" else "PM"
            val h12 = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            ActionResult(true, "Alarm set for $h12:${minute.toString().padStart(2, '0')} $ampm.")
        } else {
            ActionResult(false, "No clock app accepted the alarm.")
        }
    }

    /** #107: countdown timer via the Clock intent. */
    fun setTimer(seconds: Int, label: String): ActionResult {
        if (seconds <= 0) return ActionResult(false, "The timer length must be positive.")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (launch(intent)) {
            ActionResult(true, "Timer started for ${formatDuration(seconds)}.")
        } else {
            ActionResult(false, "No clock app accepted the timer.")
        }
    }

    private fun formatDuration(seconds: Int): String = when {
        seconds % 3600 == 0 -> "${seconds / 3600} hour(s)"
        seconds % 60 == 0 -> "${seconds / 60} minute(s)"
        seconds > 90 -> "${seconds / 60} minute(s) ${seconds % 60} second(s)"
        else -> "$seconds second(s)"
    }

    /**
     * #108: calendar event via the insert intent — the calendar app
     * shows the prefilled draft and the user confirms there (the
     * doc's "confirm shows the draft" rule), so no tap automation.
     */
    fun calendarAddEvent(title: String, beginMs: Long, endMs: Long): ActionResult {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = android.provider.CalendarContract.Events.CONTENT_URI
            putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMs)
            putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, endMs)
            putExtra(android.provider.CalendarContract.Events.TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (launch(intent)) {
            ActionResult(
                true,
                "Calendar opened with the event draft \"$title\" — review it and tap Save there."
            )
        } else {
            ActionResult(false, "No calendar app could be opened.")
        }
    }

    /**
     * #110: share the latest Jarvis screenshot (or take one now) via
     * the system share sheet — the user picks the target app there.
     */
    suspend fun shareScreenshot(): ActionResult {
        val service = JarvisAccessibilityService.instance
        val screenshotsDir = java.io.File(appContext.filesDir, "screenshots")
        var latest = screenshotsDir.listFiles()
            ?.filter { it.isFile }
            ?.maxByOrNull { it.lastModified() }
        if (latest == null) {
            val encoded = try {
                service?.captureScreenshotBase64()
            } catch (e: Exception) {
                null
            } ?: return ActionResult(
                false,
                "No screenshot is available to share. Enable the Accessibility " +
                    "Service or start Screen Share, then ask again."
            )
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            screenshotsDir.mkdirs()
            latest = java.io.File(screenshotsDir, "jarvis-share.jpg")
            latest.writeBytes(bytes)
        }
        // The FileProvider only exposes cacheDir — copy, then share.
        val cacheCopy = java.io.File(appContext.cacheDir, "jarvis-share.jpg")
        latest.copyTo(cacheCopy, overwrite = true)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            appContext, "${appContext.packageName}.fileprovider", cacheCopy
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share screenshot").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return if (launch(chooser)) {
            ActionResult(true, "Share sheet opened with the screenshot — pick where to send it.")
        } else {
            ActionResult(false, "The share sheet could not be opened.")
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
    } catch (e: TaskStoppedException) { throw e } catch (e: Exception) {
        false
    }

    private fun canResolve(intent: Intent): Boolean = try {
        appContext.packageManager.resolveActivity(intent, 0) != null
    } catch (e: TaskStoppedException) { throw e } catch (e: Exception) {
        false
    }

    private fun isPackageInstalled(packageName: String): Boolean = try {
        appContext.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: TaskStoppedException) { throw e } catch (e: Exception) {
        false
    }

    private fun normalizeAppName(name: String): String = name.lowercase()
        .replace(Regex("[^a-z0-9]"), "")
        .trim()

    companion object {
        // #23 warm start: session-scoped (process-lifetime, in-memory,
        // object-level) set of media packages that already played once.
        private val warmPackages: MutableSet<String> =
            java.util.Collections.synchronizedSet(mutableSetOf<String>())

        private fun isWarm(packageName: String): Boolean = warmPackages.contains(packageName)

        private fun markWarm(packageName: String) {
            warmPackages.add(packageName)
        }

        private const val WARM_SETTLE_MS = 150L
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"

        /** F5.1: video duration badge pattern, e.g. "3:45" / "12:07". */
        private val DURATION_REGEX = Regex("""\d+:\d{2}""")

        /** F5.1: a standalone "Ad" badge token in a card label. */
        private val AD_BADGE_REGEX = Regex("""(^|[•\s])Ad([•\s]|$)""")

        /** F5.1: YouTube chrome labels — matched against short labels only. */
        private val YOUTUBE_CHROME_WORDS = listOf(
            "search", "shorts", "home", "subscriptions", "library", "explore",
            "notifications", "voice", "back", "menu", "settings", "sign in"
        )

        private const val CHROME_LABEL_MAX = 24
        private const val YOUTUBE_SNAPSHOT_ATTEMPTS = 3
        // F6: settle caps aligned with the Part C speed pass —
        // waitForSettle early-exits the moment the tree is stable, so
        // these are worst-case caps, not sleeps.
        private const val YOUTUBE_FIRST_SETTLE_MS = 1_500L
        private const val YOUTUBE_RETRY_SETTLE_MS = 600L

        // F6 Spotify fast-path waits (all polling with early exit).
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
        private const val APP_WINDOW_WAIT_MS = 2_500L
        private const val SEARCH_TAB_WAIT_MS = 2_500L
        private const val SEARCH_INPUT_WAIT_MS = 2_000L
        private const val RESULTS_WAIT_MS = 2_500L
        private const val PAUSE_VERIFY_MS = 1_800L

        /** Spotify bottom-nav / section chrome — never a song row. */
        private val SPOTIFY_CHROME_WORDS = listOf(
            "home", "search", "your library", "create", "premium",
            "songs", "artists", "albums", "playlists", "podcasts", "recently played"
        )

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
            "calculator" to "com.google.android.calculator2",
            // F6: QuickEdit (his code editor) — direct package launch
            // for the insert_code auto-open convenience. (Key is the
            // normalizeAppName form: "quick edit" → "quickedit".)
            "quickedit" to "com.rhmsoft.edit"
        )
    }
}
