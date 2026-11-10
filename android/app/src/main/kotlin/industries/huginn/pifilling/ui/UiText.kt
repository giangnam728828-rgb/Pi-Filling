package industries.huginn.pifilling.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Text the ViewModel wants shown, without knowing how to spell it.
 *
 * AppViewModel has no Context, so for a long time it simply hardcoded Chinese
 * and English literals — which meant the language setting changed the interface
 * but not the confirmations, the errors or the transcript's system lines. These
 * types let it say *which* string it means and leave the spelling to the UI,
 * which is the only layer that knows the chosen locale.
 */
sealed interface UiText {
    /** A string resource, possibly formatted. */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** Already a sentence: agent output, exception messages, command output. */
    data class Raw(val text: String) : UiText
}

/**
 * A resource id used as a *format argument*.
 *
 * Needed for the one shape that nests: "error: %1$s", where the thing after the
 * colon is itself a resource ("fill in the Base URL first"). Keeping the nesting
 * symbolic means both halves come out in the same language.
 */
data class ResArg(@StringRes val id: Int)

/**
 * One thing to say, emitted by the ViewModel.
 *
 * [toTranscript] decides the destination: a transient confirmation goes to the
 * snackbar, while a system line belongs in the conversation and is appended
 * once the UI has resolved it — the transcript stores finished text, so a later
 * language change does not rewrite history.
 */
data class UiMessage(
    @StringRes val id: Int,
    val args: List<Any> = emptyList(),
    val toTranscript: Boolean = false,
)

/** Resolve under the *localized* context, i.e. inside the user's language. */
@Composable
fun UiText.resolve(): String {
    val context = LocalContext.current
    return when (this) {
        is UiText.Raw -> text
        is UiText.Res -> context.getString(id, *args.map { it.resolveArg(context) }.toTypedArray())
    }
}

@Composable
fun UiMessage.resolve(): String {
    val context = LocalContext.current
    return context.getString(id, *args.map { it.resolveArg(context) }.toTypedArray())
}

private fun Any.resolveArg(context: android.content.Context): Any =
    if (this is ResArg) context.getString(id) else this
