package industries.huginn.pifilling.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import industries.huginn.pifilling.runtime.AgentController
import industries.huginn.pifilling.runtime.ModelCatalog
import industries.huginn.pifilling.runtime.ModelFetchException
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * UI state holder. Bridges [AgentController]'s flows to Compose and accumulates
 * a lightweight transcript from the wrapper's event stream.
 */
class AppViewModel(private val agent: AgentController) : ViewModel() {

    val sandboxState: StateFlow<SandboxState> = agent.sandboxState

    val sessionState: StateFlow<AgentController.SessionState> =
        agent.session.stateIn(viewModelScope, SharingStarted.Eagerly, agent.session.value)

    /** Last failure with the wrapper's stderr attached, for the error sheet. */
    val diagnostic: StateFlow<String?> = agent.lastDiagnostic

    private val catalog = ModelCatalog()

    // ---- provider / model --------------------------------------------------

    private val _provider = MutableStateFlow(AgentProvider.DEFAULT)
    val provider: StateFlow<AgentProvider> = _provider.asStateFlow()

    /**
     * Models offered for the current provider.
     *
     * Starts as the curated list and is replaced by a live fetch once the key is
     * present, because a hardcoded list goes stale and a stale id fails at the
     * first prompt with an error that reads like a bug.
     */
    private val _models = MutableStateFlow(AgentProvider.DEFAULT.models)
    val models: StateFlow<List<String>> = _models.asStateFlow()

    private val _fetchingModels = MutableStateFlow(false)
    val fetchingModels: StateFlow<Boolean> = _fetchingModels.asStateFlow()

    /** Set when a fetch fails; cleared by the next attempt. Shown inline, not as a toast. */
    private val _modelFetchError = MutableStateFlow<String?>(null)
    val modelFetchError: StateFlow<String?> = _modelFetchError.asStateFlow()

    private val _model = MutableStateFlow(AgentProvider.DEFAULT.defaultModel)
    val model: StateFlow<String> = _model.asStateFlow()

    fun setModel(next: String) {
        _model.value = next
        if (_provider.value.isCustom) savedCustomModel = next
    }

    fun setProvider(next: AgentProvider) {
        _provider.value = next
        _hasApiKey.value = agent.keyStore.hasApiKey(next)
        // A model id belongs to the service that serves it, so carrying the old
        // choice across would send e.g. `glm-5.1` to Anthropic and fail at the
        // first prompt. Snap back to the new provider's own list instead.
        _models.value = if (next.isCustom) _customModels.value else next.models
        _model.value = if (next.isCustom) savedCustomModel else next.defaultModel
        _modelFetchError.value = null
        // A key that is already stored means a live list is worth having right
        // away; otherwise the picker just shows what the app shipped.
        if (_hasApiKey.value) fetchModels()
    }

    private val _hasApiKey = MutableStateFlow(agent.keyStore.hasApiKey(AgentProvider.DEFAULT))
    val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()

    /**
     * Last four characters of the stored key, so the settings screen can say
     * "which credential is this?" instead of a bare "saved". Never the whole key.
     */
    private val _keyHint = MutableStateFlow<String?>(null)
    val keyHint: StateFlow<String?> = _keyHint.asStateFlow()

    // ---- custom endpoint ---------------------------------------------------

    private val custom = agent.customProviderSettings

    private val _customBaseUrl = MutableStateFlow(custom.baseUrl)
    val customBaseUrl: StateFlow<String> = _customBaseUrl.asStateFlow()

    private val _customApiShape = MutableStateFlow(custom.apiShape)
    val customApiShape: StateFlow<String> = _customApiShape.asStateFlow()

    /** Model ids discovered for the custom endpoint; kept across provider switches. */
    private val _customModels = MutableStateFlow(
        custom.model.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList(),
    )
    val customModels: StateFlow<List<String>> = _customModels.asStateFlow()

    fun setCustomBaseUrl(value: String) {
        _customBaseUrl.value = value
        custom.baseUrl = value
    }

    fun setCustomApiShape(value: String) {
        _customApiShape.value = value
        custom.apiShape = value
    }

    private var savedCustomModel: String
        get() = custom.model
        set(value) { custom.model = value }

    // ---- language ----------------------------------------------------------

    private val _language = MutableStateFlow(agent.localeSettings.language)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    fun setLanguage(next: AppLanguage) {
        agent.localeSettings.language = next
        _language.value = next
    }

    // ---- chat --------------------------------------------------------------

    private val _transcript = MutableStateFlow<List<TranscriptLine>>(emptyList())
    val transcript: StateFlow<List<TranscriptLine>> = _transcript.asStateFlow()

    data class TranscriptLine(val role: String, val text: String)

    /**
     * Guest-side repo path. Lives here rather than in the chat screen so [send]
     * can start a session on the user's behalf: a chat app should not require a
     * "connect" step before the first message.
     */
    private val _repoPath = MutableStateFlow("/root/repo")
    val repoPath: StateFlow<String> = _repoPath.asStateFlow()

    fun setRepoPath(next: String) {
        _repoPath.value = next.trim().ifBlank { "/root/repo" }
    }

    /** Queued while the session starts; flushed on [AgentController.SessionState.Ready]. */
    private var pendingPrompt: String? = null

    init {
        agent.onEvent = ::onWrapperEvent
        viewModelScope.launch {
            agent.session.collect { state ->
                val pending = pendingPrompt
                if (state is AgentController.SessionState.Ready && pending != null) {
                    pendingPrompt = null
                    agent.prompt(pending)
                } else if (state is AgentController.SessionState.Failed) {
                    pendingPrompt = null
                }
            }
        }
        // Populate the last-four hint for whichever provider the app opens on.
        viewModelScope.launch { refreshKeyHint() }
    }

    fun saveApiKey(key: String) {
        val target = _provider.value
        viewModelScope.launch {
            withContext(Dispatchers.IO) { agent.keyStore.setApiKey(key.trim(), target) }
            _hasApiKey.value = true
            refreshKeyHint()
            fetchModels()
        }
    }

    fun clearApiKey() {
        val target = _provider.value
        viewModelScope.launch {
            withContext(Dispatchers.IO) { agent.keyStore.clearApiKey(target) }
            _hasApiKey.value = false
            _keyHint.value = null
            _models.value = if (target.isCustom) _customModels.value else target.models
        }
    }

    private suspend fun refreshKeyHint() {
        val target = _provider.value
        val stored = withContext(Dispatchers.IO) { agent.keyStore.getApiKey(target) }
        _keyHint.value = stored?.takeIf { it.length > 8 }?.let { "••••" + it.takeLast(4) }
    }

    /**
     * Replace the model list with what the service actually offers.
     *
     * Failure is reported inline rather than thrown: the shipped list is still
     * usable, so a rejected key or an offline phone should not block the app.
     */
    fun fetchModels() {
        if (_fetchingModels.value) return
        val target = _provider.value
        viewModelScope.launch {
            val key = withContext(Dispatchers.IO) { agent.keyStore.getApiKey(target) }
            if (key.isNullOrBlank()) {
                _modelFetchError.value = "先保存密钥"
                return@launch
            }
            _fetchingModels.value = true
            _modelFetchError.value = null
            runCatching {
                catalog.fetch(
                    provider = target,
                    apiKey = key,
                    baseUrl = if (target.isCustom) _customBaseUrl.value else null,
                )
            }
                .onSuccess { ids ->
                    if (target.isCustom) {
                        _customModels.value = ids
                        if (_provider.value.isCustom) _models.value = ids
                        // The stored id may predate the fetch; adopt the first if unset.
                        if (savedCustomModel.isBlank()) savedCustomModel = ids.first()
                        if (_model.value.isBlank()) _model.value = savedCustomModel
                    } else {
                        if (_provider.value == target) _models.value = ids
                        // Keep a deliberately chosen model; otherwise move to the
                        // provider's best-known default if the fetch dropped it.
                        if (_model.value !in ids && _provider.value == target) {
                            _model.value = target.defaultModel.takeIf { it in ids } ?: ids.first()
                        }
                    }
                }
                .onFailure { e ->
                    _modelFetchError.value = when (e) {
                        is ModelFetchException -> e.message
                        else -> e.message ?: e.javaClass.simpleName
                    }
                }
            _fetchingModels.value = false
        }
    }

    private val _hasGitHubToken = MutableStateFlow(agent.keyStore.hasGitHubToken())
    val hasGitHubToken: StateFlow<Boolean> = _hasGitHubToken.asStateFlow()

    fun saveGitHubToken(token: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { agent.keyStore.setGitHubToken(token.trim()) }
            _hasGitHubToken.value = true
        }
    }

    fun clearGitHubToken() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { agent.keyStore.clearGitHubToken() }
            _hasGitHubToken.value = false
        }
    }

    private val _voiceEnabled = MutableStateFlow(agent.voiceSettings.enabled)
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled.asStateFlow()

    fun setVoiceEnabled(enabled: Boolean) {
        agent.voiceSettings.enabled = enabled
        _voiceEnabled.value = enabled
        if (!enabled) viewModelScope.launch { agent.stopSpeaking() }
    }

    fun provision() = agent.provision()

    // ---- sandbox inspection -------------------------------------------------

    private val _sandboxReport = MutableStateFlow<String?>(null)
    val sandboxReport: StateFlow<String?> = _sandboxReport.asStateFlow()

    private val _sandboxBusy = MutableStateFlow(false)
    val sandboxBusy: StateFlow<Boolean> = _sandboxBusy.asStateFlow()

    /** Ask the guest what it actually has installed. */
    fun refreshSandboxReport() {
        if (_sandboxBusy.value) return
        viewModelScope.launch {
            _sandboxBusy.value = true
            _sandboxReport.value = runCatching { agent.inspectSandbox() }
                .getOrElse { "检查失败: " + (it.message ?: it.javaClass.simpleName) }
            _sandboxBusy.value = false
        }
    }

    /** Force a wrapper redeploy; with [installDeps] also re-run the package install. */
    fun repairSandbox(installDeps: Boolean) {
        if (_sandboxBusy.value) return
        viewModelScope.launch {
            _sandboxBusy.value = true
            _sandboxReport.value = runCatching { agent.redeploySandbox(installDeps) }
                .getOrElse { "失败: " + (it.message ?: it.javaClass.simpleName) }
            _sandboxBusy.value = false
            // The report is the interesting part; refresh it so the screen shows
            // the state after the repair rather than the text of the repair.
            _sandboxReport.value = runCatching { agent.inspectSandbox() }
                .getOrElse { _sandboxReport.value.orEmpty() }
        }
    }

    /** Human-readable list of the tools the sandbox promises to have installed. */
    val provisionedTools: String
        get() = agent.sandbox.provisionedPackages.joinToString(" · ")

    /** Start a fresh conversation; provider, key and endpoint are kept. */
    fun resetChat() {
        agent.endSession()
        _transcript.value = emptyList()
        pendingPrompt = null
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
                pendingPrompt = null
                append("system", "error: " + missingConfigHint())
            }
        }
    }

    /** Why a session could not start, phrased as the one thing the user must fix. */
    private fun missingConfigHint(): String {
        val p = _provider.value
        if (!p.isCustom) return "no session"
        return when {
            custom.baseUrl.isBlank() -> "先填 Base URL"
            _model.value.isBlank() -> "先填模型 ID"
            else -> "no session"
        }
    }

    fun abort() = agent.abort()

    fun endSession() = agent.endSession()

    private fun onWrapperEvent(event: WrapperEvent) {
        when (event.type) {
            "message_update" -> {
                // The wrapper nests the streamed chunk as
                //   data.assistantMessageEvent = { type: "text_delta", delta: "..." }
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
