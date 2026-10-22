package com.nshd.jarvis2.brain

import com.nshd.jarvis2.brain.tools.ToolExecutor
import com.nshd.jarvis2.brain.tools.ToolRegistry
import com.nshd.jarvis2.brain.tools.ToolResult
import com.nshd.jarvis2.core.AppLog
import com.nshd.jarvis2.ui.FriendlyErrors
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

sealed class ChatOutcome {
    data class Success(
        val reply: String,
        val providerLabel: String,
        val model: String
    ) : ChatOutcome()

    /** No provider has an API key configured yet. */
    object NoKey : ChatOutcome()

    /** Every configured provider failed; [summary] lists each failure briefly. */
    data class AllFailed(val summary: String) : ChatOutcome()
}

/**
 * Hedged free-tier failover: tries each configured provider in order
 * (active first, then preset order) and returns the first success. A 429,
 * a 5xx or a dead endpoint never ends the conversation — the next provider
 * gets its turn. Pattern carried over from the Gemini-FreeLLM app.
 */
class FailoverRouter(
    private val configsInOrder: List<ProviderConfig>,
    private val toolExecutor: ToolExecutor? = null,
    /** F5: cache source for live-only model guard substitutions. */
    private val store: ProviderStore? = null,
    /** F5: user-visible note when the guard substitutes a text model. */
    private val onSystemNote: (String) -> Unit = {}
) {

    suspend fun chat(
        messages: List<ChatMsg>,
        preferReasoningModel: Boolean,
        onDelta: (String) -> Unit = {}
    ): ChatOutcome {
        val configured = configsInOrder.filter { it.isConfigured }
        if (configured.isEmpty()) return ChatOutcome.NoKey

        val failures = mutableListOf<Pair<String, String>>()
        for (config in configured) {
            val model = selectModel(config, preferReasoningModel)
            if (model.isBlank()) {
                failures += config.label to "no model set"
                continue
            }
            try {
                // Streamed for responsiveness; deltas flow to the caller.
                // Failover still applies because a provider that dies before
                // emitting anything throws (see chatStream's rules).
                val reply = config.toProvider()
                    .chatStream(messages, model, tools = null, onDelta = onDelta)
                if (reply.content.isBlank()) throw AiException("Empty reply from model")
                return ChatOutcome.Success(reply.content, config.label, model)
            } catch (e: AiException) {
                failures += config.label to (e.message ?: "failed")
            } catch (e: Exception) {
                failures += config.label to (e.message ?: "unexpected error")
            }
        }
        // F5 error hygiene: raw provider text goes to AppLog ONLY; the
        // returned summary is FriendlyErrors-shortened per provider.
        AppLog.e(
            "API",
            "chat: all providers failed — " +
                failures.joinToString(" • ") { (label, raw) -> "$label: $raw" }
        )
        return ChatOutcome.AllFailed(
            failures.joinToString("  •  ") { (label, raw) ->
                "$label: ${FriendlyErrors.shortReason(raw)}"
            }
        )
    }

    /**
     * Tool-calling agent loop.
     *
     * The first provider that can complete a model call owns the loop.
     * Tool results are appended as standard OpenAI role="tool" messages.
     * If a provider fails after a tool has already executed, the loop does
     * NOT restart on another provider: replaying the conversation could
     * repeat a real-world action. It reports the partial state instead.
     */
    suspend fun agentLoop(
        systemAndHistory: List<ChatMsg>,
        maxSteps: Int = 10,
        preferReasoningModel: Boolean = false,
        onEvent: (ToolEvent) -> Unit = {},
        onAssistantDelta: (String) -> Unit = {}
    ): String {
        val configured = configsInOrder.filter { it.isConfigured }
        if (configured.isEmpty()) {
            return "Please configure your API Key in Settings → API & Connection first."
        }
        val executor = toolExecutor ?: return "Phone tools are not available in this session."
        val failures = mutableListOf<Pair<String, String>>()

        for (config in configured) {
            val model = selectModel(config, preferReasoningModel)
            if (model.isBlank()) {
                failures += config.label to "no model set"
                continue
            }
            val provider = config.toProvider()
            val conversation = systemAndHistory.toMutableList()
            var executedAnyTool = false
            // F5.1: first line of the most recent tool result — used
            // to say what DID happen if the model dies mid-task (E3).
            var lastToolOutcome: String? = null
            try {
                var step = 0
                while (step < maxSteps.coerceAtLeast(1)) {
                    // F6: keep the step prompt lean — tool results
                    // older than the last 6 steps collapse to their
                    // first line (the model rarely re-reads an old
                    // screen dump, but pays tokens for it every step).
                    compactOldToolResults(conversation, keepSteps = 6)
                    val modelStartedAt = System.currentTimeMillis()
                    val reply = chatStreamRetrying(
                        provider = provider,
                        messages = conversation,
                        model = model,
                        tools = ToolRegistry.openAiTools(),
                        providerLabel = config.label,
                        toolsExecuted = { executedAnyTool },
                        onDelta = onAssistantDelta
                    )
                    val modelMs = System.currentTimeMillis() - modelStartedAt
                    if (reply.toolCalls.isEmpty()) {
                        AppLog.d(
                            "Agent",
                            "step ${step + 1} model=${modelMs}ms action=0ms tool=none"
                        )
                        return reply.content.ifBlank {
                            "Done."
                        }
                    }

                    conversation += ChatMsg(
                        role = "assistant",
                        content = reply.content,
                        toolCallsJson = serializeToolCalls(reply.toolCalls)
                    )

                    var actionMs = 0L
                    var terminalReply: String? = null
                    for (call in reply.toolCalls) {
                        onEvent(ToolEvent(ToolEventKind.STARTED, call.name))
                        val args = try {
                            JSONObject(call.argumentsJson.ifBlank { "{}" })
                        } catch (e: Exception) {
                            JSONObject()
                        }
                        val toolStartedAt = System.currentTimeMillis()
                        val result: ToolResult = try {
                            executor.execute(call.name, args)
                        } catch (e: Exception) {
                            ToolResult(
                                false,
                                "Tool ${call.name} failed safely: ${e.message ?: "unexpected error"}"
                            )
                        }
                        val toolMs = System.currentTimeMillis() - toolStartedAt
                        actionMs += toolMs
                        AppLog.d(
                            "Agent",
                            "tool ${call.name} ${if (result.success) "ok" else "FAILED"} " +
                                "in ${toolMs}ms"
                        )
                        executedAnyTool = true
                        lastToolOutcome = result.message.lineSequence()
                            .firstOrNull()?.trim()?.trimEnd('.')
                            ?.takeIf { it.isNotBlank() }
                        onEvent(
                            ToolEvent(
                                kind = if (result.success) {
                                    ToolEventKind.SUCCESS
                                } else {
                                    ToolEventKind.FAILURE
                                },
                                toolName = call.name,
                                message = result.message
                            )
                        )
                        conversation += ChatMsg(
                            role = "tool",
                            content = result.message,
                            toolCallId = call.id
                        )
                        // F3 terminal media/open rule: a successful
                        // youtube_play / spotify_play / open_app ENDS
                        // the turn right here — the tool result's first
                        // line is the final summary. No more model round
                        // trips (extra trips were replaying the request).
                        if (result.success && call.name in ToolRegistry.TERMINAL_TOOLS) {
                            terminalReply = result.message.lineSequence()
                                .firstOrNull()?.trim().orEmpty()
                                .ifBlank { "Done." }
                            break
                        }
                        // Vision tools (see_screen) attach a screenshot:
                        // deliver it as a following user message with image
                        // content parts so the model can actually see it.
                        if (!result.imageBase64.isNullOrBlank()) {
                            conversation += ChatMsg(
                                role = "user",
                                content = "Screen image:",
                                imageBase64 = result.imageBase64
                            )
                        }
                    }
                    // F6: per-step timing split (model vs hands) — the
                    // "Agent" log now proves where a slow task spends
                    // its time instead of guessing.
                    AppLog.d(
                        "Agent",
                        "step ${step + 1} model=${modelMs}ms action=${actionMs}ms " +
                            "tool=${reply.toolCalls.joinToString(",") { it.name }}"
                    )
                    terminalReply?.let { return it }
                    step++
                }

                onEvent(
                    ToolEvent(
                        ToolEventKind.INFO,
                        "",
                        "Tool step limit reached — asking ${config.label} for a summary"
                    )
                )
                val summary = chatStreamRetrying(
                    provider = provider,
                    messages = conversation + ChatMsg(
                        "user",
                        "You have reached the tool-step limit. Summarise what was accomplished and what remains, without calling more tools."
                    ),
                    model = model,
                    tools = null,
                    providerLabel = config.label,
                    toolsExecuted = { executedAnyTool },
                    onDelta = onAssistantDelta
                )
                return summary.content.ifBlank {
                    "I reached the tool-step limit. The actions and results above show what was completed."
                }
            } catch (e: Exception) {
                val detail = e.message ?: "unexpected error"
                if (executedAnyTool) {
                    // Raw detail → AppLog only. F5.1 (E3): the user
                    // text is human — it says what DID happen (the
                    // last tool result), keeps the "I couldn't"
                    // failure prefix the UI keys on, and never names
                    // internal mechanics (providers / replay).
                    AppLog.e(
                        "Agent",
                        "agent loop died mid-task on ${config.id} after tool " +
                            "execution (no replay): $detail"
                    )
                    val outcome = lastToolOutcome
                        ?: "I had already taken a step on the phone"
                    val cause = if (isTransientFailure(detail)) {
                        "and then my connection dropped"
                    } else {
                        "and then the reply failed " +
                            "(${FriendlyErrors.shortReason(detail)})"
                    }
                    return "I couldn't finish that — $outcome, $cause. " +
                        "I didn't repeat the phone action on my own; " +
                        "say the command again and I'll retry it."
                }
                failures += config.label to detail
            }
        }
        AppLog.e(
            "Agent",
            "agent loop: all providers failed — " +
                failures.joinToString(" • ") { (label, raw) -> "$label: $raw" }
        )
        // F5.1 (E3): same human tone — no "provider"/"agent loop"
        // machinery words in the user-facing text.
        return "I couldn't reach the model — the connection or the API " +
            "key needs a look. (" +
            failures.joinToString("  •  ") { (label, raw) ->
                "$label: ${FriendlyErrors.shortReason(raw)}"
            } + ") The full error is in Settings → App Logs."
    }

    /**
     * F5.1 (E2): one provider's model call with same-provider retries
     * on TRANSIENT failures (connection / timeout / network / 5xx /
     * unavailable — a free tier dropping one call used to kill whole
     * tasks). Policy: before any tool has executed, ONE retry, then
     * the caller fails over to the next provider as before; after a
     * tool has executed, up to TWO retries on the SAME provider — the
     * provider is never switched after an action, because replaying
     * the conversation elsewhere could repeat the action (the
     * no-replay rule for ACTIONS stays absolute; ExecutionLedger also
     * prevents duplicate tool execution within the turn). Backoff
     * ~800 ms, then ~2 s. Retries are logged (Agent, WARN).
     */
    private suspend fun chatStreamRetrying(
        provider: AiProvider,
        messages: List<ChatMsg>,
        model: String,
        tools: JSONArray?,
        providerLabel: String,
        toolsExecuted: () -> Boolean,
        onDelta: (String) -> Unit
    ): ModelReply {
        var retriesUsed = 0
        while (true) {
            try {
                return provider.chatStream(messages, model, tools, onDelta)
            } catch (e: Exception) {
                val detail = e.message ?: "unexpected error"
                val maxRetries = if (toolsExecuted()) 2 else 1
                if (!isTransientFailure(detail) || retriesUsed >= maxRetries) throw e
                retriesUsed++
                val backoffMs = if (retriesUsed == 1) 800L else 2_000L
                AppLog.w(
                    "Agent",
                    "transient model failure on $providerLabel: $detail — " +
                        "retry $retriesUsed/$maxRetries on the SAME provider " +
                        "in ${backoffMs}ms (toolsExecuted=${toolsExecuted()})"
                )
                delay(backoffMs)
            }
        }
    }

    /**
     * F6: collapse tool results older than the last [keepSteps] tool
     * messages to their first line (+ marker). Keeps the recent
     * context exact while stopping long tasks from re-sending every
     * earlier screen dump on every step. Idempotent: an already
     * compacted message ends with the marker and is left alone.
     */
    private fun compactOldToolResults(conversation: MutableList<ChatMsg>, keepSteps: Int) {
        val toolIndices = conversation.indices.filter { conversation[it].role == "tool" }
        if (toolIndices.size <= keepSteps) return
        val compactable = toolIndices.dropLast(keepSteps)
        for (index in compactable) {
            val message = conversation[index]
            val content = message.content
            if (content.length <= 160 || content.endsWith(COMPACTED_MARKER)) continue
            val firstLine = content.lineSequence().firstOrNull()?.trim().orEmpty()
            conversation[index] = message.copy(
                content = (firstLine.ifBlank { "(tool result)" }) + COMPACTED_MARKER
            )
        }
    }

    /** F5.1: is this failure worth a same-provider retry? */
    private fun isTransientFailure(detail: String): Boolean {
        val lower = detail.lowercase()
        return lower.contains("connection") ||
            lower.contains("timeout") ||
            lower.contains("timed out") ||
            lower.contains("network") ||
            lower.contains("unavailable") ||
            lower.contains("500") || lower.contains("502") ||
            lower.contains("503") || lower.contains("504")
    }

    private fun selectModel(config: ProviderConfig, preferReasoningModel: Boolean): String {
        val chosen = when {
            preferReasoningModel && config.reasoningModel.isNotBlank() -> config.reasoningModel
            config.chatModel.isNotBlank() -> config.chatModel
            else -> config.reasoningModel
        }
        return guardTextModel(config, chosen)
    }

    // ── F5 live-only model guard ───────────────────────────────────────
    //
    // A NATIVE_AUDIO model (id contains "live"/"native-audio") only
    // speaks the Live WebSocket protocol — a REST chat completion
    // against it returns HTTP 400 ("…only supports real-time
    // bidirectional streaming…"). When such a model is configured as
    // the CHAT model, substitute a text model for THIS provider, in
    // order: (a) probe-cache WORKING text model, (b) discovered-models
    // cache first TEXT model, (c) per-provider default. The user's
    // configured value is never rewritten — the substitution is
    // per-call only, and the Live model setting is untouched.

    private fun guardTextModel(config: ProviderConfig, model: String): String {
        if (model.isBlank()) return model
        if (ModelDiscovery.classify(model) != ModelDiscovery.Kind.NATIVE_AUDIO) return model
        val substitute = findTextSubstitute(config)
        if (substitute == null) {
            AppLog.w(
                "Agent",
                "chat model '$model' on ${config.id} is live-only and no text " +
                    "substitute is cached — keeping it (friendly failure expected)"
            )
            return model
        }
        AppLog.w(
            "Agent",
            "live-only chat model '$model' on ${config.id} — substituting " +
                "text model '$substitute' for this call"
        )
        val noteKey = "${config.id}|$model"
        val shouldNote = synchronized(notedSubstitutions) {
            if (noteKey in notedSubstitutions) {
                false
            } else {
                notedSubstitutions += noteKey
                true
            }
        }
        if (shouldNote) {
            onSystemNote(
                "Chat model '$model' is for Live voice only — using '$substitute' here."
            )
        }
        return substitute
    }

    private fun findTextSubstitute(config: ProviderConfig): String? {
        val s = store
        if (s != null) {
            s.cachedProbeResults(config.id)
                .firstOrNull {
                    it.status == ProbeStatus.WORKING &&
                        ModelDiscovery.classify(it.modelId) == ModelDiscovery.Kind.TEXT
                }
                ?.let { return it.modelId }
            s.cachedModelIds(config.id)
                .firstOrNull {
                    ModelDiscovery.classify(it) == ModelDiscovery.Kind.TEXT
                }
                ?.let { return it }
        }
        return DEFAULT_TEXT_MODELS[config.id]
    }

    companion object {
        /** F6: suffix marking an already-compacted old tool result. */
        private const val COMPACTED_MARKER = " […]"

        /** Substitution source (c): sane default text model per provider. */
        private val DEFAULT_TEXT_MODELS = mapOf(
            "gemini" to "gemini-2.5-flash",
            "groq" to "llama-3.3-70b-versatile",
            "openrouter" to "openai/gpt-4o-mini"
            // freellm / custom: none — keep the original and fail friendly.
        )

        /** "provider|liveModel" pairs already announced this process run. */
        private val notedSubstitutions = mutableSetOf<String>()
    }

    private fun serializeToolCalls(calls: List<ToolCall>): String {
        val array = JSONArray()
        calls.forEach { call ->
            array.put(
                JSONObject()
                    .put("id", call.id)
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", call.name)
                            .put("arguments", call.argumentsJson.ifBlank { "{}" })
                    )
            )
        }
        return array.toString()
    }
}
