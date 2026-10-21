package com.nshd.jarvis2.brain

/** Factory defaults for one provider. Everything stays user-editable. */
data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val chatModel: String,
    val reasoningModel: String
)

object ProviderPresets {
    val GEMINI = ProviderPreset(
        id = "gemini",
        label = "Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        chatModel = "gemini-2.5-flash",
        reasoningModel = "gemini-2.5-pro"
    )
    val GROQ = ProviderPreset(
        id = "groq",
        label = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        chatModel = "llama-3.3-70b-versatile",
        reasoningModel = "llama-3.3-70b-versatile"
    )
    val FREELLM = ProviderPreset(
        id = "freellm",
        label = "FreeLLMAPI",
        baseUrl = "https://nshd-freellm-api.onrender.com/v1",
        chatModel = "",
        reasoningModel = ""
    )
    val OPENROUTER = ProviderPreset(
        id = "openrouter",
        label = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        chatModel = "google/gemini-2.5-flash",
        reasoningModel = "deepseek/deepseek-r1"
    )
    val CUSTOM = ProviderPreset(
        id = "custom",
        label = "Custom",
        baseUrl = "",
        chatModel = "",
        reasoningModel = ""
    )

    /** Failover order = this preset order (active provider is moved first). */
    val ALL: List<ProviderPreset> = listOf(GEMINI, GROQ, FREELLM, OPENROUTER, CUSTOM)

    fun byId(id: String): ProviderPreset = ALL.firstOrNull { it.id == id } ?: GEMINI
}
