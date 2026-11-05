package industries.huginn.pifilling.ui

import android.content.Context

/**
 * Persists the endpoint configuration for the "custom" provider.
 *
 * A Base URL and a model id are not secrets (the API key is, and lives in
 * [industries.huginn.pifilling.storage.SecureKeyStore]), so ordinary
 * SharedPreferences is the right home. Keeping them outside the ViewModel means
 * the endpoint survives a process death — otherwise every app restart would
 * silently fall back to a provider whose key was never entered.
 *
 * [provider] and [language] are deliberately *not* persisted: they are cheap UI
 * choices and re-deriving them on launch is harmless.
 */
class CustomProviderSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null).orEmpty()
        set(value) { prefs.edit().putString(KEY_BASE_URL, value.trim()).apply() }

    var model: String
        get() = prefs.getString(KEY_MODEL, null).orEmpty()
        set(value) { prefs.edit().putString(KEY_MODEL, value.trim()).apply() }

    /** Which wire shape the endpoint speaks. */
    var apiShape: String
        get() = prefs.getString(KEY_API_SHAPE, null) ?: OPENAI_COMPLETIONS
        set(value) {
            if (value == OPENAI_COMPLETIONS || value == ANTHROPIC_MESSAGES) {
                prefs.edit().putString(KEY_API_SHAPE, value).apply()
            }
        }

    /** True when enough has been filled in to actually start a session. */
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        const val OPENAI_COMPLETIONS = "openai-completions"
        const val ANTHROPIC_MESSAGES = "anthropic-messages"
        val SHAPES = listOf(OPENAI_COMPLETIONS, ANTHROPIC_MESSAGES)
    }

    private companion object {
        const val PREFS = "pifilling_custom_provider"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_API_SHAPE = "api_shape"
    }
}
