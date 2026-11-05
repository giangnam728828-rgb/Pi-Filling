package industries.huginn.pifilling.runtime

import industries.huginn.pifilling.sandbox.AgentProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks a provider which models the current key can reach.
 *
 * Both wire protocols answer the same shape — `{"data":[{"id":"..."}]}` — so one
 * parser covers OpenAI-compatible services and Anthropic alike; only the auth
 * header and the URL differ. This exists because a hand-written model list is
 * wrong the week after it ships: providers add and retire ids constantly, and a
 * stale id fails at the first prompt with an error that reads like a bug.
 */
class ModelCatalog {

    /**
     * Returns the model ids, best-first where the service expresses an order.
     *
     * Throws with a message that is safe to put in front of a user: the raw
     * response body can contain an HTML error page from a CDN, and a wall of
     * markup in a toast is not a diagnosis.
     */
    suspend fun fetch(
        provider: AgentProvider,
        apiKey: String,
        baseUrl: String? = null,
    ): List<String> = withContext(Dispatchers.IO) {
        val url = resolveUrl(provider, baseUrl)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            when (provider.authStyle) {
                AgentProvider.AuthStyle.BEARER ->
                    setRequestProperty("Authorization", "Bearer $apiKey")
                AgentProvider.AuthStyle.X_API_KEY -> {
                    setRequestProperty("x-api-key", apiKey)
                    // Required by Anthropic; harmlessly ignored by most relays.
                    setRequestProperty("anthropic-version", "2023-06-01")
                }
            }
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw ModelFetchException(describe(status, detail, url))
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveUrl(provider: AgentProvider, baseUrl: String?): String {
        if (!provider.isCustom) return provider.modelsUrl
        val base = baseUrl?.trim()?.trimEnd('/')
            ?: throw ModelFetchException("先填 Base URL")
        // The Base URL may or may not already include the version segment that
        // precedes /models, so accept both spellings rather than demanding one.
        return if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
    }

    private fun parse(body: String): List<String> {
        val data = JSONObject(body).optJSONArray("data")
            ?: throw ModelFetchException("这个接口没有返回模型列表")
        val ids = buildList {
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                // Anthropic and OpenAI both use `id`; a few relays use `name`.
                val id = item.optString("id").ifBlank { item.optString("name") }
                if (id.isNotBlank()) add(id)
            }
        }.distinct()
        if (ids.isEmpty()) throw ModelFetchException("这个接口没有返回任何模型")
        return ids
    }

    private fun describe(status: Int, detail: String, url: String): String = when (status) {
        401, 403 -> "密钥被拒绝（HTTP $status）。检查密钥是否有效、是否有权限。"
        404 -> "接口地址不存在（HTTP 404）：$url"
        429 -> "请求太频繁（HTTP 429），稍后再试。"
        in 500..599 -> "服务端出错（HTTP $status），稍后再试。"
        else -> "获取模型失败（HTTP $status）"
    }
}

class ModelFetchException(message: String) : Exception(message)
