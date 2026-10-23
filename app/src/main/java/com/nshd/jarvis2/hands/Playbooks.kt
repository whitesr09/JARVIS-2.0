package com.nshd.jarvis2.hands

import com.nshd.jarvis2.core.AppLog

/**
 * F8 (#36): per-app selector playbooks.
 *
 * For the apps Jarvis drives most, the "key elements" (search field,
 * send button, play button, first result) are described ONCE here as
 * ordered (viewId / desc / text) descriptor lists — primary first,
 * fallbacks after — instead of being re-invented inside every deep
 * intent. The self-healing finder (#29) and the deep intents consult
 * the playbook for the FOREGROUND package; when the primary
 * descriptor hits, behaviour is exactly the pre-playbook behaviour
 * (the WhatsApp/Spotify primaries below ARE the checks the deep
 * intents used inline). Descriptors for apps without an inline
 * history (Instagram, Chrome, Gmail) are fallback-grade only: they
 * are consulted after the generic strategies miss, never instead of
 * a working primary.
 *
 * Positional picks (YouTube/Spotify first-result rows) stay in the
 * deep intents — a descriptor can't express "topmost card in the
 * results region", and pretending otherwise would weaken them.
 */
data class ElementDescriptor(
    val viewIdSuffix: String? = null,
    val descEquals: String? = null,
    val textEquals: String? = null,
    val descContains: String? = null,
    val textContains: String? = null
) {
    /** Which field matched [element], or null when none did. */
    fun matchedBy(element: UiElement): String? {
        if (viewIdSuffix != null && element.viewId.isNotBlank() &&
            element.viewId.endsWith(viewIdSuffix, ignoreCase = true)
        ) {
            return "viewId"
        }
        if (descEquals != null && element.desc.equals(descEquals, ignoreCase = true)) {
            return "desc"
        }
        if (textEquals != null && element.text.equals(textEquals, ignoreCase = true)) {
            return "text"
        }
        if (descContains != null && element.desc.contains(descContains, ignoreCase = true)) {
            return "desc~"
        }
        if (textContains != null && element.label.contains(textContains, ignoreCase = true)) {
            return "text~"
        }
        return null
    }

    fun matches(element: UiElement): Boolean = matchedBy(element) != null
}

/** The selector strategies for one app package, keyed by element role. */
data class AppPlaybook(
    val packageName: String,
    val roles: Map<String, List<ElementDescriptor>>
)

object Playbooks {

    const val ROLE_SEARCH_FIELD = "search_field"
    const val ROLE_FIRST_RESULT = "first_result"
    const val ROLE_SEND_BUTTON = "send_button"
    const val ROLE_PLAY_BUTTON = "play_button"
    const val ROLE_SEARCH_TAB = "search_tab"

    const val WHATSAPP_PACKAGE = "com.whatsapp"
    const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
    const val INSTAGRAM_PACKAGE = "com.instagram.android"
    const val SPOTIFY_PACKAGE = "com.spotify.music"
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    const val CHROME_PACKAGE = "com.android.chrome"
    const val GMAIL_PACKAGE = "com.google.android.gm"

    // WhatsApp's send control — the primary triple is verbatim the
    // check DeepIntents.whatsappMessage ran inline before F8.
    private val whatsappSend = listOf(
        ElementDescriptor(viewIdSuffix = ":id/send"),
        ElementDescriptor(descEquals = "Send"),
        ElementDescriptor(textEquals = "Send")
    )
    private val whatsappSearch = listOf(
        ElementDescriptor(descContains = "Search"),
        ElementDescriptor(viewIdSuffix = ":id/search_input")
    )

    private val books: Map<String, AppPlaybook> = listOf(
        AppPlaybook(
            WHATSAPP_PACKAGE,
            mapOf(
                ROLE_SEND_BUTTON to whatsappSend,
                ROLE_SEARCH_FIELD to whatsappSearch
            )
        ),
        AppPlaybook(
            WHATSAPP_BUSINESS_PACKAGE,
            mapOf(
                ROLE_SEND_BUTTON to whatsappSend,
                ROLE_SEARCH_FIELD to whatsappSearch
            )
        ),
        AppPlaybook(
            INSTAGRAM_PACKAGE,
            mapOf(
                ROLE_SEARCH_FIELD to listOf(
                    ElementDescriptor(descEquals = "Search"),
                    ElementDescriptor(textEquals = "Search")
                ),
                ROLE_SEND_BUTTON to listOf(
                    ElementDescriptor(descEquals = "Send"),
                    ElementDescriptor(textEquals = "Send")
                ),
                ROLE_PLAY_BUTTON to listOf(
                    ElementDescriptor(descEquals = "Play"),
                    ElementDescriptor(textEquals = "Play")
                )
            )
        ),
        AppPlaybook(
            SPOTIFY_PACKAGE,
            mapOf(
                // Primaries mirror DeepIntents' inline Spotify checks.
                ROLE_SEARCH_TAB to listOf(
                    ElementDescriptor(textEquals = "Search"),
                    ElementDescriptor(descEquals = "Search")
                ),
                ROLE_SEARCH_FIELD to listOf(
                    ElementDescriptor(textContains = "What do you want to listen to"),
                    ElementDescriptor(descContains = "What do you want to listen to"),
                    ElementDescriptor(viewIdSuffix = ":id/search_input")
                ),
                ROLE_PLAY_BUTTON to listOf(
                    ElementDescriptor(descEquals = "Play"),
                    ElementDescriptor(textEquals = "Play")
                )
            )
        ),
        AppPlaybook(
            YOUTUBE_PACKAGE,
            mapOf(
                ROLE_SEARCH_FIELD to listOf(
                    ElementDescriptor(descEquals = "Search"),
                    ElementDescriptor(textEquals = "Search")
                ),
                // Fallback only (the positional picker is primary):
                // a result card's merged label carries its view count.
                ROLE_FIRST_RESULT to listOf(
                    ElementDescriptor(descContains = "views"),
                    ElementDescriptor(textContains = "views")
                ),
                ROLE_PLAY_BUTTON to listOf(
                    ElementDescriptor(descEquals = "Play"),
                    ElementDescriptor(textEquals = "Play")
                )
            )
        ),
        AppPlaybook(
            CHROME_PACKAGE,
            mapOf(
                ROLE_SEARCH_FIELD to listOf(
                    ElementDescriptor(viewIdSuffix = ":id/search_box_text"),
                    ElementDescriptor(descContains = "Search"),
                    ElementDescriptor(textEquals = "Search")
                )
            )
        ),
        AppPlaybook(
            GMAIL_PACKAGE,
            mapOf(
                ROLE_SEARCH_FIELD to listOf(
                    ElementDescriptor(descContains = "Search"),
                    ElementDescriptor(viewIdSuffix = ":id/search_input")
                ),
                ROLE_SEND_BUTTON to listOf(
                    ElementDescriptor(descEquals = "Send"),
                    ElementDescriptor(textEquals = "Send")
                )
            )
        )
    ).associateBy { it.packageName }

    fun forPackage(packageName: String?): AppPlaybook? =
        packageName?.let { books[it] }

    /**
     * First element matching any descriptor of [role] for [packageName],
     * in descriptor order. Hits and misses are logged (tag "Hands")
     * so a layout change at the app side is visible in the log.
     */
    fun find(
        elements: List<UiElement>,
        packageName: String?,
        role: String
    ): UiElement? {
        val book = forPackage(packageName) ?: return null
        val descriptors = book.roles[role] ?: return null
        for (descriptor in descriptors) {
            val hit = elements.firstOrNull { descriptor.matches(it) }
            if (hit != null) {
                AppLog.d(
                    "Hands",
                    "playbook hit pkg=$packageName role=$role " +
                        "via=${descriptor.matchedBy(hit)}"
                )
                return hit
            }
        }
        AppLog.d("Hands", "playbook miss pkg=$packageName role=$role")
        return null
    }

    /** Map a free-text finder query to a playbook role, when it names one. */
    fun roleForQuery(normalizedQuery: String): String? = when {
        normalizedQuery.contains("send") -> ROLE_SEND_BUTTON
        normalizedQuery.contains("search") -> ROLE_SEARCH_FIELD
        normalizedQuery.contains("play") -> ROLE_PLAY_BUTTON
        else -> null
    }
}
