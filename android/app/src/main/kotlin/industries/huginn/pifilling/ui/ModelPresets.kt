package industries.huginn.pifilling.ui

import android.content.Context
import industries.huginn.pifilling.sandbox.AgentProvider
import org.json.JSONArray
import org.json.JSONObject

/**
 * A saved model configuration: the model, where it lives, and how to talk to it.
 *
 * The earlier version stored only the model id. That works for a provider whose
 * endpoint is fixed, and fails for the one case that matters most — a custom
 * endpoint with several models behind it. Switching to a different service meant
 * the URL stayed behind, so the model was reachable in the picker and unusable in
 * practice; the user had to re-enter the address every time.
 *
 * The credential is keyed by this profile too (see [profileKey]), so two
 * services with different keys can be swapped without retyping either.
 */
data class ModelProfile(
    val model: String,
    /** Empty for the built-in providers, whose endpoint is fixed. */
    val baseUrl: String = "",
    /** Wire protocol for a custom endpoint. Ignored by the built-ins. */
    val apiShape: String = "openai-completions",
    /** True when the endpoint reasons; forwarded as `--reasoning`. */
    val reasoning: Boolean = false,
) {
    /**
     * Stable identity for this configuration.
     *
     * Used as the key-store slot, so the credential follows the endpoint rather
     * than the provider: pointing at a second OpenAI-compatible service must not
     * overwrite the first one's key.
     */
    val profileKey: String
        get() = if (baseUrl.isBlank()) "model" else "model@${baseUrl.trimEnd('/')}"

    /** What the picker shows. The model alone is ambiguous across services. */
    fun displayName(): String =
        if (baseUrl.isBlank()) model else "$model · ${hostOf(baseUrl)}"

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
}

/**
 * Saved model profiles, per provider.
 *
 * Ordinary SharedPreferences like [CustomProviderSettings]: a model id and an
 * endpoint are not secrets. The credential is stored separately, in
 * [industries.huginn.pifilling.storage.SecureKeyStore], under
 * [ModelProfile.profileKey].
 */
class ModelPresets(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saved profiles for [provider], most recently used first. */
    fun savedProfiles(provider: AgentProvider): List<ModelProfile> {
        val raw = prefs.getString(provider.id, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val model = o.optString("model").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ModelProfile(
                    model = model,
                    baseUrl = o.optString("baseUrl"),
                    apiShape = o.optString("apiShape", "openai-completions"),
                    reasoning = o.optBoolean("reasoning", false),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Model ids only, for callers that do not care about the endpoint. */
    fun saved(provider: AgentProvider): List<String> =
        savedProfiles(provider).map { it.model }

    /**
     * Record a use: move [profile] to the front (or add it), trimming to the cap.
     *
     * Identity is the whole profile, not just the model: the same model id
     * against two endpoints is two entries, because they are two configurations.
     */
    fun remember(provider: AgentProvider, profile: ModelProfile): List<ModelProfile> {
        val trimmed = profile.copy(model = profile.model.trim())
        if (trimmed.model.isEmpty()) return savedProfiles(provider)
        val kept = (listOf(trimmed) + savedProfiles(provider).filter { it.profileKey != trimmed.profileKey })
            .take(MAX_PRESETS)
        persist(provider, kept)
        return kept
    }

    /** Drop a profile again; the quick switcher must not offer what was removed. */
    fun forget(provider: AgentProvider, profileKey: String): List<ModelProfile> {
        val next = savedProfiles(provider).filter { it.profileKey != profileKey }
        persist(provider, next)
        return next
    }

    /** The most recently used profile, or null when nothing is saved. */
    fun mostRecent(provider: AgentProvider): ModelProfile? = savedProfiles(provider).firstOrNull()

    private fun persist(provider: AgentProvider, profiles: List<ModelProfile>) {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("model", p.model)
                    put("baseUrl", p.baseUrl)
                    put("apiShape", p.apiShape)
                    put("reasoning", p.reasoning)
                },
            )
        }
        prefs.edit().putString(provider.id, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "pifilling_model_presets"
        const val MAX_PRESETS = 6
    }
}
