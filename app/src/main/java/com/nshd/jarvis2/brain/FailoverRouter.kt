package com.nshd.jarvis2.brain

import com.nshd.jarvis2.brain.tools.ToolExecutor
import com.nshd.jarvis2.brain.tools.ToolRegistry
import com.nshd.jarvis2.brain.tools.ToolResult
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
    private val toolExecutor: ToolExecutor? = null
) {

    suspend fun chat(
        messages: List<ChatMsg>,
        preferReasoningModel: Boolean
    ): ChatOutcome {
        val configured = configsInOrder.filter { it.isConfigured }
        if (configured.isEmpty()) return ChatOutcome.NoKey

        val failures = mutableListOf<String>()
        for (config in configured) {
            val model = selectModel(config, preferReasoningModel)
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
        onEvent: (String) -> Unit = {}
    ): String {
        val configured = configsInOrder.filter { it.isConfigured }
        if (configured.isEmpty()) {
            return "Please configure your API Key in Settings → API & Connection first."
        }
        val executor = toolExecutor ?: return "Phone tools are not available in this session."
        val failures = mutableListOf<String>()

        for (config in configured) {
            val model = selectModel(config, preferReasoningModel)
            if (model.isBlank()) {
                failures += "${config.label}: no model set"
                continue
            }
            val provider = config.toProvider()
            val conversation = systemAndHistory.toMutableList()
            var executedAnyTool = false
            try {
                var step = 0
                while (step < maxSteps.coerceAtLeast(1)) {
                    val reply = provider.chatWithTools(
                        conversation,
                        model,
                        ToolRegistry.openAiTools()
                    )
                    if (reply.toolCalls.isEmpty()) {
                        return reply.content.ifBlank {
                            "Done."
                        }
                    }

                    conversation += ChatMsg(
                        role = "assistant",
                        content = reply.content,
                        toolCallsJson = serializeToolCalls(reply.toolCalls)
                    )

                    for (call in reply.toolCalls) {
                        onEvent("🔧 ${call.name}")
                        val args = try {
                            JSONObject(call.argumentsJson.ifBlank { "{}" })
                        } catch (e: Exception) {
                            JSONObject()
                        }
                        val result: ToolResult = try {
                            executor.execute(call.name, args)
                        } catch (e: Exception) {
                            ToolResult(
                                false,
                                "Tool ${call.name} failed safely: ${e.message ?: "unexpected error"}"
                            )
                        }
                        executedAnyTool = true
                        val firstLine = result.message.lineSequence().firstOrNull().orEmpty()
                        onEvent("${if (result.success) "✅" else "❌"} ${call.name}: $firstLine")
                        conversation += ChatMsg(
                            role = "tool",
                            content = result.message,
                            toolCallId = call.id
                        )
                    }
                    step++
                }

                onEvent("⚠️ Tool step limit reached — asking ${config.label} for a summary")
                val summary = provider.chatWithTools(
                    conversation + ChatMsg(
                        "user",
                        "You have reached the tool-step limit. Summarise what was accomplished and what remains, without calling more tools."
                    ),
                    model,
                    tools = null
                )
                return summary.content.ifBlank {
                    "I reached the tool-step limit. The actions and results above show what was completed."
                }
            } catch (e: Exception) {
                val detail = e.message ?: "unexpected error"
                if (executedAnyTool) {
                    return "I took action, then ${config.label} failed before the task could finish: $detail. " +
                        "I did not replay the task on another provider, because that could repeat a phone action."
                }
                failures += "${config.label}: $detail"
            }
        }
        return "I couldn't reach any provider for the agent loop: ${failures.joinToString("  •  ")}"
    }

    private fun selectModel(config: ProviderConfig, preferReasoningModel: Boolean): String =
        when {
            preferReasoningModel && config.reasoningModel.isNotBlank() -> config.reasoningModel
            config.chatModel.isNotBlank() -> config.chatModel
            else -> config.reasoningModel
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
