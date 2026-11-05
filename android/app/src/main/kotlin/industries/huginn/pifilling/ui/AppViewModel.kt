package industries.huginn.pifilling.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import industries.huginn.pifilling.runtime.AgentController
import industries.huginn.pifilling.sandbox.AgentProvider
import industries.huginn.pifilling.sandbox.SandboxState
import industries.huginn.pifilling.wrapper.WrapperEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * UI state holder. Bridges [AgentController]'s flows to Compose and accumulates
 * a lightweight transcript from the wrapper's event stream. The transcript model
 * is intentionally minimal for the scaffold — a production UI would render tool
 * calls, diffs, and token/cost meters from the same events.
 */
class AppViewModel(private val agent: AgentController) : ViewModel() {

    val sandboxState: StateFlow<SandboxState> = agent.sandboxState

    val sessionState: StateFlow<AgentController.SessionState> =
        agent.session.stateIn(viewModelScope, SharingStarted.Eagerly, agent.session.value)

    private val _provider = MutableStateFlow(AgentProvider.DEFAULT)
    val provider: StateFlow<AgentProvider> = _provider.asStateFlow()

    /**
     * Which model the next session will run. Defaults to the provider's strongest
     * entry and resets when the provider changes, because a model id is only
     * meaningful to the service that serves it.
     */
    private val _model = MutableStateFlow(AgentProvider.DEFAULT.defaultModel)
    val model: StateFlow<String> = _model.asStateFlow()

    fun setModel(next: String) {
        _model.value = next
        if (_provider.value.isCustom) savedCustomModel = next
    }

    private val _hasApiKey = MutableStateFlow(agent.keyStore.hasApiKey(AgentProvider.DEFAULT))
    val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()

    /**
     * Switching provider re-reads whether *that* provider has a key, so the UI
     * asks for a credential per provider rather than assuming one covers both.
     */
    fun setProvider(next: AgentProvider) {
        _provider.value = next
        _hasApiKey.value = agent.keyStore.hasApiKey(next)
        // A model id belongs to the service that serves it, so carrying the old
        // choice across would send e.g. `glm-5.1` to Anthropic and fail at the
        // first prompt. Snap back to the new provider's default instead.
        _model.value = if (next.isCustom) savedCustomModel else next.defaultModel
    }

    private val _language = MutableStateFlow(agent.localeSettings.language)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    /**
     * Changes take effect immediately: [PiFillingApp] builds a locale-overridden
     * Context from this flow, so nothing has to be recreated and no in-progress
     * session is interrupted by a language switch.
     */
    fun setLanguage(next: AppLanguage) {
        agent.localeSettings.language = next
        _language.value = next
    }

    // Custom endpoint. Backed by SharedPreferences so a process death does not
    // dump the user back onto a provider they have no key for.
    private val custom = agent.customProviderSettings

    private val _customBaseUrl = MutableStateFlow(custom.baseUrl)
    val customBaseUrl: StateFlow<String> = _customBaseUrl.asStateFlow()

    private val _customApiShape = MutableStateFlow(custom.apiShape)
    val customApiShape: StateFlow<String> = _customApiShape.asStateFlow()

    fun setCustomBaseUrl(value: String) {
        _customBaseUrl.value = value
        custom.baseUrl = value
    }

    fun setCustomApiShape(value: String) {
        _customApiShape.value = value
        custom.apiShape = value
    }

    /** The custom provider's model id is the ordinary [model] flow, persisted here. */
    private var savedCustomModel: String
        get() = custom.model
        set(value) { custom.model = value }

    /**
     * Guest-side repo path. Lives here rather than in the chat screen so
     * [send] can start a session on the user's behalf: a chat app should not
     * require a "connect" step before the first message.
     */
    private val _repoPath = MutableStateFlow("/root/repo")
    val repoPath: StateFlow<String> = _repoPath.asStateFlow()

    fun setRepoPath(next: String) {
        _repoPath.value = next.trim().ifBlank { "/root/repo" }
    }

    /** Queued while the session starts; flushed on [SessionState.Ready]. */
    private var pendingPrompt: String? = null

    private val _transcript = MutableStateFlow<List<TranscriptLine>>(emptyList())
    val transcript: StateFlow<List<TranscriptLine>> = _transcript.asStateFlow()

    data class TranscriptLine(val role: String, val text: String)

    init {
        agent.onEvent = ::onWrapperEvent
        viewModelScope.launch {
            agent.session.collect { state ->
                // The first message may arrive before the wrapper is up. Hold it,
                // then send the moment the session is usable — from the user's
                // point of view they just typed and got a reply.
                val pending = pendingPrompt
                if (state is AgentController.SessionState.Ready && pending != null) {
                    pendingPrompt = null
                    agent.prompt(pending)
                } else if (state is AgentController.SessionState.Failed) {
                    pendingPrompt = null
                }
            }
        }
    }

    fun saveApiKey(key: String) {
        val target = _provider.value
        viewModelScope.launch(Dispatchers.IO) {
            agent.keyStore.setApiKey(key.trim(), target)
            _hasApiKey.value = true
        }
    }

    private val _hasGitHubToken = MutableStateFlow(agent.keyStore.hasGitHubToken())
    val hasGitHubToken: StateFlow<Boolean> = _hasGitHubToken.asStateFlow()

    fun saveGitHubToken(token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            agent.keyStore.setGitHubToken(token.trim())
            _hasGitHubToken.value = true
        }
    }

    private val _voiceEnabled = MutableStateFlow(agent.voiceSettings.enabled)
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled.asStateFlow()

    /**
     * The user's own switch. Deliberately not reachable by the agent: it can ask
     * what the setting is and can toggle auto-speak, but turning speech on is a
     * decision a model should not be able to make for someone whose phone is in
     * a pocket.
     */
    fun setVoiceEnabled(enabled: Boolean) {
        agent.voiceSettings.enabled = enabled
        _voiceEnabled.value = enabled
        if (!enabled) viewModelScope.launch { agent.stopSpeaking() }
    }

    fun clearGitHubToken() {
        viewModelScope.launch(Dispatchers.IO) {
            agent.keyStore.clearGitHubToken()
            _hasGitHubToken.value = false
        }
    }

    fun clearApiKey() {
        val target = _provider.value
        viewModelScope.launch(Dispatchers.IO) {
            agent.keyStore.clearApiKey(target)
            _hasApiKey.value = false
        }
    }

    fun provision() = agent.provision()

    /** Comma-joined list of the tools the sandbox promises to have installed. */
    val provisionedTools: String
        get() = agent.sandbox.provisionedPackages.joinToString(" · ")

    /**
     * Start a fresh chat. Keeps the provider, key and endpoint: "new chat" means
     * new conversation, not "reconfigure the app".
     */
    fun resetChat() {
        agent.endSession()
        _transcript.value = emptyList()
    }

    fun startSession(repoGuestPath: String): Boolean {
        val p = _provider.value
        if (p.isCustom && (_customBaseUrl.value.isBlank() || _model.value.isBlank())) {
            return false
        }
        agent.startSession(
            repoGuestPath = repoGuestPath,
            provider = p,
            model = _model.value,
            baseUrl = if (p.isCustom) _customBaseUrl.value else null,
            apiShape = _customApiShape.value,
        )
        return true
    }

    fun send(text: String) {
        if (text.isBlank()) return
        append("you", text)
        val state = agent.session.value
        if (state is AgentController.SessionState.Ready ||
            state is AgentController.SessionState.Streaming
        ) {
            agent.prompt(text)
        } else {
            // No session yet: start one in the background and queue the message.
            // Dropping the text or showing "no active session" would both make
            // the app feel broken on the very first use.
            pendingPrompt = text
            if (!startSession(_repoPath.value)) {
                // Refusing silently would look identical to a hung request. Say
                // what is missing instead, in the same place the reply would be.
                pendingPrompt = null
                append("system", "error: " + _missingConfigHint())
            }
        }
    }

    /**
     * Why a session could not start, phrased as the one thing the user must fix.
     * Only the custom provider has settings it can be missing; every other
     * provider is blocked solely on its key, which the UI already gates on.
     */
    private fun _missingConfigHint(): String {
        val p = _provider.value
        if (!p.isCustom) return "no session"
        return when {
            custom.baseUrl.isBlank() -> "fill in the Base URL in Settings"
            _model.value.isBlank() -> "fill in the model ID in Settings"
            else -> "no session"
        }
    }

    fun abort() = agent.abort()

    fun endSession() = agent.endSession()

    private fun onWrapperEvent(event: WrapperEvent) {
        // Accumulate streamed assistant text deltas; tag other events as system.
        when (event.type) {
            "message_update" -> {
                // The wrapper nests the streamed chunk as
                //   data.assistantMessageEvent = { type: "text_delta", delta: "..." }
                // Neither `assistantMessageEvent.text` nor a top-level `data.delta`
                // exists, so the previous lookup never matched and the agent's
                // answer never reached the transcript.
                //
                // The type check is not optional: `thinking_delta` carries a
                // `delta` field too, and reading it unconditionally would splice
                // the model's reasoning into the visible reply.
                val assistantEvent = event.data["assistantMessageEvent"]?.jsonObject
                if (assistantEvent?.get("type")?.jsonPrimitive?.contentOrNull == "text_delta") {
                    val delta = assistantEvent["delta"]?.jsonPrimitive?.contentOrNull
                    if (!delta.isNullOrEmpty()) appendToLast("assistant", delta)
                }
            }
            "tool_execution_start" -> {
                val name = event.data["toolName"]?.jsonPrimitive?.contentOrNull ?: "tool"
                append("system", "▸ $name")
            }
            "agent_end" -> append("system", "— done —")
            "wrapper_error" -> append(
                "system",
                "error: ${event.data["message"]?.jsonPrimitive?.contentOrNull.orEmpty()}",
            )
        }
    }

    private fun append(role: String, text: String) {
        _transcript.value = _transcript.value + TranscriptLine(role, text)
    }

    private fun appendToLast(role: String, delta: String) {
        val current = _transcript.value
        val last = current.lastOrNull()
        _transcript.value = if (last != null && last.role == role) {
            current.dropLast(1) + last.copy(text = last.text + delta)
        } else {
            current + TranscriptLine(role, delta)
        }
    }

    companion object {
        fun factory(agent: AgentController): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppViewModel(agent) as T
            }
    }
}
