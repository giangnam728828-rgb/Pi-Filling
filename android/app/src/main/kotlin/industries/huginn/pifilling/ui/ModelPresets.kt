package industries.huginn.pifilling.ui

import android.content.Context
import industries.huginn.pifilling.sandbox.AgentProvider
import org.json.JSONArray


/**
 * Saved model presets, per provider.
 *
 * The request that created this: picking a model was all-or-nothing — one id,
 * stored nowhere, lost the moment the provider switched. A working setup for a
 * custom endpoint in particular (several model ids behind one Base URL) needs
 * to keep *all* of them and hop between them from the chat box.
 *
 * Presets are ordinary SharedPreferences like [CustomProviderSettings]: a model
 * id is not a secret. They are keyed per provider so a saved DeepSeek id never
 * appears under a custom endpoint, and capped so the chat-box quick switcher
 * stays a row, not a list.
 */
class ModelPresets(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saved ids for [provider], most recently used first. */
    fun saved(provider: AgentProvider): List<String> =
        JSONArray(prefs.getString(provider.id, null) ?: "[]")
            .let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() } }

    /**
     * Record a use: move [id] to the front (or add it), trimming to the cap.
     * Returns the updated list so a caller can render it without re-reading.
     */
    fun remember(provider: AgentProvider, id: String): List<String> {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return saved(provider)
        val kept = (listOf(trimmed) + saved(provider).filter { it != trimmed }).take(MAX_PRESETS)
        persist(provider, kept)
        return kept
    }

    /** Drop a preset again; the quick switcher must not offer what was un-saved. */
    fun forget(provider: AgentProvider, id: String): List<String> {
        val next = saved(provider).filter { it != id }
        persist(provider, next)
        return next
    }

    private fun persist(provider: AgentProvider, ids: List<String>) {
        prefs.edit().putString(provider.id, JSONArray(ids).toString()).apply()
    }

    companion object {
        private const val PREFS = "pifilling_model_presets"
        const val MAX_PRESETS = 6
    }
}
