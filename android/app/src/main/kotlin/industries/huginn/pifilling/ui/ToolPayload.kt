package industries.huginn.pifilling.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reading a tool call's arguments and result without knowing which tool ran.
 *
 * The wrapper forwards `args` and `result` exactly as the agent produced them,
 * so their shape varies per tool. Everything here is best-effort: an
 * unparseable payload is shown as-is rather than hidden, because the raw text is
 * usually still the answer the user wanted to see.
 */
private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/** The command a tool ran: its `command` argument, or the whole argument object. */
fun toolCommand(args: String): String {
    if (args.isBlank()) return ""
    val parsed = runCatching { json.parseToJsonElement(args) }.getOrNull() ?: return args
    val obj = parsed as? JsonObject ?: return args
    // bash / sh style tools name it "command"; a few use "cmd" or "script".
    for (key in listOf("command", "cmd", "script", "code")) {
        val value = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (!value.isNullOrBlank()) return value
    }
    return prettyPrint(parsed)
}

/**
 * The text a tool produced.
 *
 * Tool results are usually `{ content: [ { type: "text", text: "..." } ] }`, but
 * a string result happens too, and so does something unexpected — in which case
 * the raw payload is the most useful thing to show.
 */
fun toolOutput(result: String): String {
    if (result.isBlank()) return ""
    val parsed = runCatching { json.parseToJsonElement(result) }.getOrNull() ?: return result

    if (parsed is JsonPrimitive && parsed.isString) return parsed.content

    val obj = parsed as? JsonObject
    if (obj != null) {
        val content = obj["content"] as? JsonArray
        if (content != null) {
            val text = content.mapNotNull { part ->
                when (part) {
                    is JsonPrimitive -> part.contentOrNullSafe()
                    is JsonObject -> (part["text"] as? JsonPrimitive)
                        ?.takeIf { it.isString }?.content
                    else -> null
                }
            }.filter { it.isNotBlank() }
            if (text.isNotEmpty()) return text.joinToString("\n")
        }
        // Some tools return a bare { output: "..." } or { stdout: "..." }.
        for (key in listOf("output", "stdout", "text", "result")) {
            val value = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (!value.isNullOrBlank()) return value
        }
    }
    return prettyPrint(parsed)
}

private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (isString) content else null

private fun prettyPrint(element: JsonElement): String = runCatching {
    json.encodeToString(JsonElement.serializer(), element)
}.getOrDefault(element.toString())

/**
 * Cut [text] to [maxLines], saying so rather than pretending the rest is absent.
 * Returns the visible text and whether anything was dropped.
 */
fun truncateLines(text: String, maxLines: Int): Pair<String, Boolean> {
    val lines = text.split('\n')
    if (lines.size <= maxLines) return text to false
    return lines.take(maxLines).joinToString("\n") to true
}
