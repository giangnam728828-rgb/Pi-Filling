package industries.huginn.pifilling.ui

import android.content.Context
import industries.huginn.pifilling.sandbox.AgentProvider
import org.json.JSONArray
import org.json.JSONObject

/**
 * A complete, named model configuration: service, model, endpoint and key.
 *
 * The request that produced this: two setups — say DeepSeek with its own key, and
 * a self-hosted endpoint with a different key — had to be re-entered every time
 * you switched, because the endpoint was a single global value and the key was
 * keyed by provider. Choosing a model meant retyping the address.
 *
 * A group is the whole decision in one object, so switching between two services
 * is one tap and nothing needs retyping. [id] is stable and is what the key store
 * is keyed by, so two groups against the same provider keep separate credentials.
 */
data class ModelGroup(
    /** Stable identity; also the key-store slot suffix. */
    val id: String,
    /** Shown in the picker. Defaults to the provider label when blank. */
    val name: String,
    val providerId: String,
    val model: String,
    /** Empty for built-in providers, whose endpoint is fixed. */
    val baseUrl: String = "",
    val apiShape: String = "openai-completions",
    val reasoning: Boolean = false,
    /** Disabled groups stay in the list but cannot be activated. */
    val enabled: Boolean = true,
) {
    val provider: AgentProvider
        get() = AgentProvider.entries.firstOrNull { it.id == providerId }
            ?: AgentProvider.DEFAULT

    /** What the picker shows: a name if given, otherwise provider · model. */
    fun displayName(): String =
        name.ifBlank { "${provider.label} · ${model.ifBlank { "?" }}" }

    /** One line of context under the name. */
    fun subtitle(): String = when {
        baseUrl.isNotBlank() -> baseUrl
        else -> model
    }

    companion object {
        /**
         * Slot suffix for the credential.
         *
         * Grouped by id, not by provider: two groups on the same provider are two
         * services with two keys, and sharing a slot would have them overwrite
         * each other.
         */
        fun keySlot(id: String): String = "group:$id"

        fun newId(): String =
            "g" + System.currentTimeMillis().toString(36) + "-" +
                (0..999).random().toString().padStart(3, '0')
    }
}

/**
 * Saved model groups, plus which one is active.
 *
 * Ordinary SharedPreferences: a model id and an endpoint are not secrets. The key
 * lives in [industries.huginn.pifilling.storage.SecureKeyStore] under
 * [ModelGroup.keySlot].
 */
class ModelGroupStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<ModelGroup> {
        val raw = prefs.getString(KEY_GROUPS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ModelGroup(
                    id = id,
                    name = o.optString("name"),
                    providerId = o.optString("providerId"),
                    model = o.optString("model"),
                    baseUrl = o.optString("baseUrl"),
                    apiShape = o.optString("apiShape", "openai-completions"),
                    reasoning = o.optBoolean("reasoning", false),
                    enabled = o.optBoolean("enabled", true),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** The active group, or null when none was saved. */
    fun active(): ModelGroup? {
        val id = prefs.getString(KEY_ACTIVE, null) ?: return null
        return all().firstOrNull { it.id == id }
    }

    fun setActive(id: String) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    /** Insert or replace by id; a disabled group is stored without activation. */
    fun save(group: ModelGroup): List<ModelGroup> {
        val next = (all().filterNot { it.id == group.id } + group)
        persist(next)
        if (group.enabled) setActive(group.id)
        return next
    }

    fun delete(id: String): List<ModelGroup> {
        val next = all().filterNot { it.id == id }
        persist(next)
        if (prefs.getString(KEY_ACTIVE, null) == id) {
            // Fall back to whatever is left rather than pointing at nothing.
            next.firstOrNull()?.let { setActive(it.id) }
                ?: prefs.edit().remove(KEY_ACTIVE).apply()
        }
        return next
    }

    private fun persist(groups: List<ModelGroup>) {
        val arr = JSONArray()
        groups.forEach { g ->
            arr.put(
                JSONObject().apply {
                    put("id", g.id)
                    put("name", g.name)
                    put("providerId", g.providerId)
                    put("model", g.model)
                    put("baseUrl", g.baseUrl)
                    put("apiShape", g.apiShape)
                    put("reasoning", g.reasoning)
                    put("enabled", g.enabled)
                },
            )
        }
        prefs.edit().putString(KEY_GROUPS, arr.toString()).apply()
    }

    private companion object {
        const val PREFS = "pifilling_model_groups"
        const val KEY_GROUPS = "groups"
        const val KEY_ACTIVE = "active"
    }
}
