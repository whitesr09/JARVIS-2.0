package com.nshd.jarvis2.brain

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
class FailoverRouter(private val configsInOrder: List<ProviderConfig>) {

    suspend fun chat(
        messages: List<ChatMsg>,
        preferReasoningModel: Boolean
    ): ChatOutcome {
        val configured = configsInOrder.filter { it.isConfigured }
        if (configured.isEmpty()) return ChatOutcome.NoKey

        val failures = mutableListOf<String>()
        for (config in configured) {
            val model = when {
                preferReasoningModel && config.reasoningModel.isNotBlank() ->
                    config.reasoningModel
                config.chatModel.isNotBlank() -> config.chatModel
                else -> config.reasoningModel
            }
            if (model.isBlank()) {
                failures += "${config.label}: no model set"
                continue
            }
            try {
                val reply = config.toProvider().chat(messages, model)
                return ChatOutcome.Success(reply, config.label, model)
            } catch (e: AiException) {
                failures += "${config.label}: ${e.message ?: "failed"}"
            } catch (e: Exception) {
                failures += "${config.label}: ${e.message ?: "unexpected error"}"
            }
        }
        return ChatOutcome.AllFailed(failures.joinToString("  •  "))
    }
}
