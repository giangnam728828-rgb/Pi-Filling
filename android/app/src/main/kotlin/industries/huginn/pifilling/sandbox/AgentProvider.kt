package industries.huginn.pifilling.sandbox

/**
 * LLM providers Layer 1 can drive the wrapper against.
 *
 * These mirror the `PROVIDERS` table in `node-wrapper/src/wrapper.mjs`: [id] is
 * what gets passed as `--provider`, [keyEnv] is the environment variable the
 * wrapper reads that provider's credential from.
 *
 * The domestic entries exist because `api.anthropic.com` answers `403` on many
 * mainland carriers rather than timing out, which the agent loop surfaced as an
 * opaque "Request timed out" long after the key had been accepted.
 *
 * [modelsUrl] and [authStyle] are for the *host-side* model listing feature:
 * asking the service which models the key can actually reach beats shipping a
 * hand-maintained list that drifts the moment a provider ships a new model.
 */
enum class AgentProvider(
    val id: String,
    val label: String,
    val keyEnv: String,
    val keyHint: String,
    val defaultModel: String,
    /** Ordered best-first; used until a live fetch replaces it. */
    val models: List<String>,
    /** Endpoint that lists models for this service. */
    val modelsUrl: String,
    /** Which header carries the credential when listing models. */
    val authStyle: AuthStyle,
) {
    DEEPSEEK(
        id = "deepseek",
        label = "DeepSeek",
        keyEnv = "DEEPSEEK_API_KEY",
        keyHint = "sk-...",
        defaultModel = "deepseek-v4-pro",
        models = listOf("deepseek-v4-pro", "deepseek-v4-flash"),
        modelsUrl = "https://api.deepseek.com/models",
        authStyle = AuthStyle.BEARER,
    ),
    MOONSHOT(
        id = "moonshot",
        label = "Kimi",
        keyEnv = "MOONSHOT_API_KEY",
        keyHint = "sk-...",
        defaultModel = "kimi-k2-thinking",
        models = listOf("kimi-k2-thinking", "kimi-k2-thinking-turbo", "kimi-k2-0905-preview"),
        modelsUrl = "https://api.moonshot.cn/v1/models",
        authStyle = AuthStyle.BEARER,
    ),
    ZAI(
        id = "zai",
        label = "智谱 GLM",
        keyEnv = "ZAI_API_KEY",
        keyHint = "xxx.xxx",
        defaultModel = "glm-5.1",
        models = listOf("glm-5.1", "glm-5-turbo", "glm-4.7"),
        modelsUrl = "https://open.bigmodel.cn/api/coding/paas/v4/models",
        authStyle = AuthStyle.BEARER,
    ),
    MINIMAX(
        id = "minimax",
        label = "MiniMax",
        keyEnv = "MINIMAX_API_KEY",
        keyHint = "eyJ...",
        defaultModel = "MiniMax-M3",
        models = listOf("MiniMax-M3", "MiniMax-M2.7-highspeed"),
        modelsUrl = "https://api.minimaxi.com/anthropic/v1/models",
        authStyle = AuthStyle.X_API_KEY,
    ),
    ANTHROPIC(
        id = "anthropic",
        label = "Anthropic",
        keyEnv = "ANTHROPIC_API_KEY",
        keyHint = "sk-ant-...",
        defaultModel = "claude-sonnet-4-6",
        models = listOf("claude-sonnet-4-6", "claude-opus-4-6", "claude-haiku-4-5-20251001"),
        modelsUrl = "https://api.anthropic.com/v1/models",
        authStyle = AuthStyle.X_API_KEY,
    ),
    OPENROUTER(
        id = "openrouter",
        label = "OpenRouter",
        keyEnv = "OPENROUTER_API_KEY",
        keyHint = "sk-or-...",
        defaultModel = "anthropic/claude-sonnet-4.5",
        models = listOf("anthropic/claude-sonnet-4.5", "google/gemini-2.5-pro"),
        modelsUrl = "https://openrouter.ai/api/v1/models",
        authStyle = AuthStyle.BEARER,
    ),
    CUSTOM(
        id = "custom",
        label = "自定义",
        keyEnv = "CUSTOM_API_KEY",
        keyHint = "sk-...",
        defaultModel = "",
        models = emptyList(),
        // Filled in from the user's Base URL at fetch time.
        modelsUrl = "",
        authStyle = AuthStyle.BEARER,
    ),
    ;

    /** Whether the provider needs a Base URL and a wire-protocol choice. */
    val isCustom: Boolean get() = this == CUSTOM

    /** Providers whose endpoint is known ahead of time, so a fetch needs no extra setup. */
    val hasFixedEndpoint: Boolean get() = !isCustom

    enum class AuthStyle {
        /** `Authorization: Bearer <key>` — OpenAI-compatible services. */
        BEARER,

        /** `x-api-key: <key>` — Anthropic and anything speaking its protocol. */
        X_API_KEY,
    }

    companion object {
        val DEFAULT = DEEPSEEK

        /** Order used by the provider picker: best mainland reachability first. */
        val pickerOrder: List<AgentProvider> = listOf(
            DEEPSEEK, MOONSHOT, ZAI, MINIMAX, ANTHROPIC, OPENROUTER, CUSTOM,
        )

        fun fromId(id: String?): AgentProvider =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
