package com.nshd.jarvis2.hands

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.nshd.jarvis2.service.JarvisAccessibilityService
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max

/** F8 (#29): a finder hit plus the strategy that produced it. */
data class FindResult(val element: UiElement, val strategy: String)

/** One model-addressable element from an accessibility snapshot. */
data class UiElement(
    val index: Int,
    val text: String,
    val desc: String,
    val viewId: String,
    val className: String,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?,
    val enabled: Boolean
) {
    val label: String get() = text.ifBlank { desc }
}

/**
 * Converts the focused window's accessibility tree into the compact,
 * numbered observation format used by the agent.
 *
 * Traversal is bounded (300 visited nodes). The returned list is capped at
 * 75 and prioritises elements that are both interactive and labelled, then
 * other interactive elements, then text-bearing elements.
 */
class ScreenReader(private val service: JarvisAccessibilityService) {

    private data class Candidate(
        val order: Int,
        val text: String,
        val desc: String,
        val viewId: String,
        val className: String,
        val bounds: Rect,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val checkable: Boolean,
        val checked: Boolean?,
        val enabled: Boolean
    ) {
        val label: String get() = text.ifBlank { desc }
        val score: Int
            get() {
                var value = 0
                if (clickable || editable || scrollable) value += 4
                if (label.isNotBlank()) value += 3
                if (editable) value += 2
                if (checkable) value += 1
                return value
            }
    }

    private data class Scan(val packageName: String?, val candidates: List<Candidate>)

    fun currentPackage(): String? = service.activeRoot()?.packageName?.toString()
        ?: JarvisAccessibilityService.lastPackageName

    fun snapshot(): List<UiElement> {
        val scan = scan()
        val selected = scan.candidates
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.order })
            .take(MAX_ELEMENTS)
            // Restore reading order after prioritised selection. Indices are
            // assigned in this stable screen order.
            .sortedBy { it.order }
        val elements = selected.mapIndexed { index, candidate ->
            candidate.toUiElement(index)
        }
        service.updateLastSnapshot(elements, scan.packageName)
        return elements
    }

    /** Screen signature: package plus the bounded tree's identifying data. */
    fun signature(): Int {
        val scan = scan()
        var hash = scan.packageName?.hashCode() ?: 0
        scan.candidates.forEach { candidate ->
            val key = buildString {
                append(candidate.text).append('|')
                append(candidate.desc).append('|')
                append(candidate.viewId).append('|')
                append(candidate.className).append('|')
                append(candidate.bounds.flattenToString()).append('|')
                append(candidate.clickable).append(candidate.editable)
            }
            hash = 31 * hash + key.hashCode()
        }
        return hash
    }

    fun serialize(maxElements: Int = MAX_ELEMENTS): String {
        val elements = snapshot().take(maxElements.coerceIn(1, MAX_ELEMENTS))
        val pkg = currentPackage() ?: "unknown"
        if (elements.isEmpty()) return "App: $pkg\nVisible Elements: (none exposed by accessibility)"
        return buildString {
            append("App: ").append(pkg).append("\nVisible Elements:")
            elements.forEach { element ->
                append("\n[#").append(element.index).append("] ")
                append(element.label.ifBlank { simpleClassName(element.className) })
                if (element.editable || element.className.contains("EditText", true)) {
                    append(" (InputField)")
                }
                if (element.className.contains("Switch", true) ||
                    element.className.contains("Toggle", true) || element.checked != null
                ) {
                    append(" (Switch: ").append(if (element.checked == true) "ON" else "OFF").append(")")
                }
                if (element.scrollable) append(" (Scrollable)")
                if (!element.enabled) append(" (Disabled)")
            }
        }
    }

    /**
     * F6 compact serialization for the agent loop's per-step decision
     * context: at most [maxElements] elements (default 40), labels
     * truncated to ~40 chars, and pure container nodes (no label of
     * their own and not themselves interactive) dropped. The full
     * [serialize] (75 elements, full labels) stays untouched for
     * inspect_screen / see_screen-style reads — only the step prompt
     * gets leaner, which is where free-tier latency actually lives.
     */
    fun serializeCompact(maxElements: Int = 40, labelMax: Int = 40): String {
        val (pkg, lines) = compactEntries(maxElements, labelMax)
        if (lines.isEmpty()) {
            return "App: $pkg\nVisible Elements: (none exposed by accessibility)"
        }
        return buildString {
            append("App: ").append(pkg).append("\nVisible Elements:")
            lines.forEach { line -> append("\n").append(line) }
        }
    }

    /**
     * F8 (#21): the compact serialization as (package, entry lines) —
     * the same content [serializeCompact] renders, one string per
     * element ("[#3] Label (InputField)"), so the agent loop can diff
     * steps entry-by-entry instead of re-sending the whole screen.
     */
    fun compactEntries(
        maxElements: Int = 40,
        labelMax: Int = 40
    ): Pair<String, List<String>> {
        val all = snapshot()
        val pkg = currentPackage() ?: "unknown"
        // "Pure container" in snapshot terms: not clickable/editable/
        // scrollable/checkable AND its label is only merged descendant
        // text that duplicates a child — indistinguishable here, so
        // the safe cut is: keep interactive elements and elements
        // whose OWN text/desc is non-blank (Candidate keeps them
        // merged; the observable proxy is a non-blank label).
        val kept = all.filter { element ->
            element.clickable || element.editable || element.scrollable ||
                element.checked != null || element.label.isNotBlank()
        }.take(maxElements.coerceIn(1, MAX_ELEMENTS))
        val lines = kept.map { element ->
            buildString {
                append("[#").append(element.index).append("] ")
                val label = element.label.ifBlank { simpleClassName(element.className) }
                append(if (label.length > labelMax) label.take(labelMax - 1) + "…" else label)
                if (element.editable || element.className.contains("EditText", true)) {
                    append(" (InputField)")
                }
                if (element.className.contains("Switch", true) ||
                    element.className.contains("Toggle", true) || element.checked != null
                ) {
                    append(" (Switch: ").append(if (element.checked == true) "ON" else "OFF").append(")")
                }
                if (element.scrollable) append(" (Scrollable)")
                if (!element.enabled) append(" (Disabled)")
            }
        }
        return pkg to lines
    }

    /**
     * F8 (#29): self-healing finder. Strategy chain, in order:
     * exact text → contains text → content-description exact →
     * content-description contains → viewId suffix → playbook
     * descriptor for the foreground package (when the query names a
     * known role) → the legacy prefix/fuzzy tail of [findByText] →
     * relative position hint (top-most clickable in [region]:
     * "top"/"bottom") when the caller supplies one. Every fallback
     * runs ONLY on a miss of the strategies before it, so existing
     * callers see identical primary behaviour. The returned
     * [FindResult.strategy] names what matched (logged by callers).
     */
    fun findSmart(
        query: String,
        elements: List<UiElement> = snapshot(),
        packageName: String? = null,
        region: String? = null
    ): FindResult? {
        val wanted = normalize(query)
        if (wanted.isBlank()) return null
        elements.firstOrNull { normalize(it.text) == wanted }
            ?.let { return FindResult(it, "exact-text") }
        elements.firstOrNull { normalize(it.text).contains(wanted) }
            ?.let { return FindResult(it, "contains-text") }
        elements.firstOrNull { normalize(it.desc) == wanted }
            ?.let { return FindResult(it, "exact-desc") }
        elements.firstOrNull { normalize(it.desc).contains(wanted) }
            ?.let { return FindResult(it, "contains-desc") }
        val rawQuery = query.trim()
        elements.firstOrNull { element ->
            element.viewId.isNotBlank() &&
                (element.viewId.endsWith(rawQuery, ignoreCase = true) ||
                    element.viewId.substringAfterLast('/')
                        .equals(rawQuery, ignoreCase = true))
        }?.let { return FindResult(it, "viewid-suffix") }
        if (packageName != null) {
            Playbooks.roleForQuery(wanted)?.let { role ->
                Playbooks.find(elements, packageName, role)
                    ?.let { return FindResult(it, "playbook") }
            }
        }
        // Legacy tail (startsWith → fuzzy) via the original finder,
        // minus the strategies already tried above.
        findByText(query, elements)?.let { element ->
            val strategy = if (
                normalize(element.text).startsWith(wanted) ||
                normalize(element.desc).startsWith(wanted)
            ) {
                "prefix"
            } else {
                "fuzzy"
            }
            return FindResult(element, strategy)
        }
        if (region != null) {
            val clickables = elements.filter { it.clickable }
            val picked = when (region.lowercase()) {
                "top" -> clickables.minByOrNull { it.bounds.top }
                "bottom" -> clickables.maxByOrNull { it.bounds.top }
                else -> null
            }
            picked?.let { return FindResult(it, "position-$region") }
        }
        return null
    }

    /** Exact matches win, then prefix/contains, then a small edit-distance match. */
    fun findByText(query: String, elements: List<UiElement> = snapshot()): UiElement? {
        val wanted = normalize(query)
        if (wanted.isBlank()) return null

        elements.firstOrNull {
            normalize(it.text) == wanted || normalize(it.desc) == wanted
        }?.let { return it }
        elements.firstOrNull {
            normalize(it.text).startsWith(wanted) || normalize(it.desc).startsWith(wanted)
        }?.let { return it }
        elements.firstOrNull {
            normalize(it.text).contains(wanted) || normalize(it.desc).contains(wanted)
        }?.let { return it }

        val threshold = max(2, wanted.length / 3)
        return elements
            .mapNotNull { element ->
                val distances = listOf(itDistance(wanted, normalize(element.text)),
                    itDistance(wanted, normalize(element.desc))).filter { it >= 0 }
                val distance = distances.minOrNull() ?: return@mapNotNull null
                if (distance <= threshold) element to distance else null
            }
            .minByOrNull { it.second }
            ?.first
    }

    private fun scan(): Scan {
        val root = service.activeRoot() ?: return Scan(null, emptyList())
        val packageName = root.packageName?.toString()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val candidates = mutableListOf<Candidate>()
        var visited = 0
        var order = 0

        while (!queue.isEmpty() && visited < MAX_VISITED_NODES) {
            val node = queue.removeFirst()
            visited++

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val interactive = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable
            val visible = node.isVisibleToUser
            if (visible && bounds.width() > 0 && bounds.height() > 0 &&
                (interactive || text.isNotBlank() || desc.isNotBlank())
            ) {
                val effectiveText = if (text.isBlank() && node.isClickable) {
                    descendantText(node, maxDepth = 3)
                } else {
                    text
                }
                candidates += Candidate(
                    order = order++,
                    text = effectiveText,
                    desc = desc,
                    viewId = node.viewIdResourceName.orEmpty(),
                    className = node.className?.toString().orEmpty(),
                    bounds = Rect(bounds),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    checkable = node.isCheckable,
                    checked = if (node.isCheckable) node.isChecked else null,
                    enabled = node.isEnabled
                )
            }

            for (i in 0 until node.childCount) {
                if (visited + queue.size >= MAX_VISITED_NODES) break
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return Scan(packageName, candidates)
    }

    private fun descendantText(node: AccessibilityNodeInfo, maxDepth: Int): String {
        if (maxDepth < 0) return ""
        val labels = linkedSetOf<String>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it to 0) }
        var visited = 0
        while (!queue.isEmpty() && visited < 24 && labels.size < 4) {
            val (child, depth) = queue.removeFirst()
            visited++
            child.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { labels += it }
            child.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { labels += it }
            if (depth < maxDepth) {
                for (i in 0 until child.childCount) child.getChild(i)?.let { queue.add(it to depth + 1) }
            }
        }
        return labels.joinToString(" • ")
    }

    private fun Candidate.toUiElement(index: Int): UiElement = UiElement(
        index = index,
        text = text,
        desc = desc,
        viewId = viewId,
        className = className,
        bounds = Rect(bounds),
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
        checked = checked,
        enabled = enabled
    )

    private fun simpleClassName(className: String): String =
        className.substringAfterLast('.').ifBlank { "Element" }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun itDistance(wanted: String, candidate: String): Int {
        if (candidate.isBlank()) return -1
        // Avoid expensive fuzzy matching against very long labels; their
        // exact/contains checks have already had a chance.
        if (abs(wanted.length - candidate.length) > 4) return -1
        return levenshtein(wanted, candidate)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }
            val swap = previous; previous = current; current = swap
        }
        return previous[b.length]
    }

    companion object {
        private const val MAX_VISITED_NODES = 300
        private const val MAX_ELEMENTS = 75
    }
}
