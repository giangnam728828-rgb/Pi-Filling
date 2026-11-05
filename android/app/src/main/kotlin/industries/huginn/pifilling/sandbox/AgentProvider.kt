package industries.huginn.pifilling.sandbox

/**
 * LLM providers Layer 1 can drive the wrapper against.
 *
 * These mirror the `PROVIDERS` table in `node-wrapper/src/wrapper.mjs`: [id] is
 * what gets passed as `--provider`, [keyEnv] is the environment variable the
 * wrapper reads that provider's credential from. Keeping the two in step is the
 * whole contract — the wrapper rejects an unknown `--provider`, and each
 * provider reads only its own variable, so a DeepSeek key will not satisfy
 * Anthropic.
 *
 * The domestic entries exist because `api.anthropic.com` is blocked on many
 * mainland carriers: it answers `403` rather than timing out, which the agent
 * loop surfaced as an opaque `Request timed out` long after the key had been
 * accepted. Every endpoint below is reachable from mainland China directly.
 *
 * [models] is the curated subset offered in the picker. pi-ai's builtin
 * catalogs carry far more, and the wrapper accepts any id it knows, so this
 * list is about discoverability rather than a hard limit.
 */
enum class AgentProvider(
    val id: String,
    val label: String,
    val keyEnv: String,
    val keyHint: String,
    val defaultModel: String,
    /** Ordered best-first; the first entry is what [defaultModel] should be. */
    val models: List<String>,
) {
    DEEPSEEK(
        id = "deepseek",
        label = "DeepSeek",
        keyEnv = "DEEPSEEK_API_KEY",
        keyHint = "sk-...",
        defaultModel = "deepseek-v4-pro",
        models = listOf("deepseek-v4-pro", "deepseek-v4-flash"),
    ),
    ANTHROPIC(
        id = "anthropic",
        label = "Anthropic",
        keyEnv = "ANTHROPIC_API_KEY",
        keyHint = "sk-ant-...",
        defaultModel = "claude-sonnet-4-6",
        models = listOf(
            "claude-sonnet-4-6",
            "claude-opus-4-6",
            "claude-haiku-4-5-20251001",
        ),
    ),
    MOONSHOT(
        id = "moonshot",
        label = "Kimi",
        keyEnv = "MOONSHOT_API_KEY",
        keyHint = "sk-...",
        defaultModel = "kimi-k2-thinking",
        models = listOf("kimi-k2-thinking", "kimi-k2-thinking-turbo", "kimi-k2-0905-preview"),
    ),
    ZAI(
        id = "zai",
        label = "智谱 GLM",
        keyEnv = "ZAI_API_KEY",
        keyHint = "xxx.xxx",
        defaultModel = "glm-5.1",
        models = listOf("glm-5.1", "glm-5-turbo", "glm-4.7"),
    ),
    MINIMAX(
        id = "minimax",
        label = "MiniMax",
        keyEnv = "MINIMAX_API_KEY",
        keyHint = "eyJ...",
        defaultModel = "MiniMax-M3",
        models = listOf("MiniMax-M3", "MiniMax-M2.7-highspeed"),
    ),
    OPENROUTER(
        id = "openrouter",
        label = "OpenRouter",
        keyEnv = "OPENROUTER_API_KEY",
        keyHint = "sk-or-...",
        defaultModel = "anthropic/claude-sonnet-4.5",
        models = listOf(
            "anthropic/claude-sonnet-4.5",
            "google/gemini-2.5-pro",
            "deepseek/deepseek-chat",
        ),
    ),
    CUSTOM(
        id = "custom",
        label = "自定义",
        keyEnv = "CUSTOM_API_KEY",
        keyHint = "sk-...",
        defaultModel = "",
        // No curated list: the user types the model id their endpoint serves.
        models = emptyList(),
    ),
    ;

    /** Whether the provider needs a Base URL and a wire-protocol choice. */
    val isCustom: Boolean get() = this == CUSTOM

    /**
     * Preference key for this provider's stored credential. Anthropic keeps the
     * historical `anthropic_api_key` name so an existing install does not lose
     * its key when provider support lands.
     */
    val prefKey: String get() = "${id}_api_key"

    companion object {
        val DEFAULT = DEEPSEEK

        fun fromId(id: String?): AgentProvider =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
