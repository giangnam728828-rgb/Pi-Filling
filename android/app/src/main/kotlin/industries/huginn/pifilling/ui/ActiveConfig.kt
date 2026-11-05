package industries.huginn.pifilling.ui

import android.content.Context
import industries.huginn.pifilling.sandbox.AgentProvider

/**
 * What the app is currently pointed at: provider, model, custom endpoint, repo.
 *
 * These used to live only in the ViewModel, which meant every process death —
 * and Android kills this app freely while it is in the background — reset the
 * user back to DeepSeek/deepseek-v4-pro. Choosing a provider, a model and a base
 * URL and then losing all three by leaving the app is not a preference, it is
 * data loss, and it is the one thing a settings screen must never do.
 *
 * Ordinary SharedPreferences: none of this is secret (the API key lives in
 * [industries.huginn.pifilling.storage.SecureKeyStore]).
 */
class ActiveConfig(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var provider: AgentProvider
        get() = AgentProvider.fromId(prefs.getString(KEY_PROVIDER, null))
        set(value) { prefs.edit().putString(KEY_PROVIDER, value.id).apply() }

    /**
     * Last model used *per provider*, so switching provider and back returns to
     * the model that was working there instead of the built-in default.
     */
    fun model(provider: AgentProvider): String? =
        prefs.getString(KEY_MODEL_PREFIX + provider.id, null)?.takeIf { it.isNotBlank() }

    fun setModel(provider: AgentProvider, id: String) {
        if (id.isBlank()) return
        prefs.edit().putString(KEY_MODEL_PREFIX + provider.id, id.trim()).apply()
    }

    /** Working directory inside the sandbox. */
    var repoPath: String
        get() = prefs.getString(KEY_REPO, null) ?: "/root/repo"
        set(value) { prefs.edit().putString(KEY_REPO, value.trim().ifBlank { "/root/repo" }).apply() }

    private companion object {
        const val PREFS = "pifilling_active_config"
        const val KEY_PROVIDER = "provider"
        const val KEY_MODEL_PREFIX = "model_"
        const val KEY_REPO = "repo_path"
    }
}
