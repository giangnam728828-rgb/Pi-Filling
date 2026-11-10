package industries.huginn.pifilling.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import industries.huginn.pifilling.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import industries.huginn.pifilling.runtime.AgentController
import industries.huginn.pifilling.runtime.ModelCatalog
import industries.huginn.pifilling.runtime.ModelFetchException
import industries.huginn.pifilling.sandbox.AgentProvider
import industries.huginn.pifilling.sandbox.RootfsDownloader
import industries.huginn.pifilling.sandbox.SandboxState
import industries.huginn.pifilling.sandbox.Toolchain
import industries.huginn.pifilling.sandbox.ToolchainState
import industries.huginn.pifilling.wrapper.WrapperEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
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

    private val config = agent.activeConfig

    // Restored from disk, not from a constant: Android kills this process while
    // it is in the background, and the old code came back up as DeepSeek/
    // deepseek-v4-pro no matter what the user had chosen.
    private val _provider = MutableStateFlow(config.provider)
    val provider: StateFlow<AgentProvider> = _provider.asStateFlow()

    /**
     * Models offered for the current provider.
     *
     * Starts as the curated list and is replaced by a live fetch once the key is
     * present, because a hardcoded list goes stale and a stale id fails at the
     * first prompt with an error that reads like a bug.
     */
    private val _models = MutableStateFlow(config.provider.models)
    val models: StateFlow<List<String>> = _models.asStateFlow()

    private val _fetchingModels = MutableStateFlow(false)
    val fetchingModels: StateFlow<Boolean> = _fetchingModels.asStateFlow()

    /** Set when a fetch fails; cleared by the next attempt. Shown inline, not as a toast. */
    private val _modelFetchError = MutableStateFlow<UiText?>(null)
    val modelFetchError: StateFlow<UiText?> = _modelFetchError.asStateFlow()

    private val _model = MutableStateFlow(
        config.model(config.provider) ?: config.provider.defaultModel,
    )
    val model: StateFlow<String> = _model.asStateFlow()

    fun setModel(next: String) {
        _model.value = next
        // Write through immediately: a model chosen now must still be the model
        // after the launcher evicts the app.
        config.setModel(_provider.value, next)
        if (_provider.value.isCustom) savedCustomModel = next
        // Remember the *configuration*, not just the id: for a custom endpoint
        // the address is part of what was chosen.
        rememberPreset(currentProfile(next))
    }

    /** The configuration that describes [model] right now. */
    fun profileFor(model: String): ModelProfile = currentProfile(model)

    /** The configuration that describes [model] right now. */
    private fun currentProfile(model: String): ModelProfile =
        if (_provider.value.isCustom) {
            ModelProfile(
                model = model,
                baseUrl = agent.customProviderSettings.baseUrl,
                apiShape = agent.customProviderSettings.apiShape,
                reasoning = agent.customProviderSettings.reasoning,
            )
        } else {
            ModelProfile(model = model)
        }

    // ---- model switching ---------------------------------------------------
    private val _pendingModelSwitch = MutableStateFlow<String?>(null)

    /** Non-null while the "this starts a new conversation" prompt is up. */
    val pendingModelSwitch: StateFlow<String?> = _pendingModelSwitch.asStateFlow()

    /**
     * Ask to change models.
     *
     * A live wrapper is bound to the model it was started with, so switching
     * means ending the session — and ending the session used to silently throw
     * the conversation away. The user now gets asked first; with no session
     * running there is nothing to lose, so the switch is immediate.
     */
    fun requestModelSwitch(id: String) {
        if (id == _model.value) return
        if (!sessionIsLive()) {
            setModel(id)
            return
        }
        _pendingModelSwitch.value = id
    }

    private fun sessionIsLive(): Boolean {
        val state = agent.session.value
        return state is AgentController.SessionState.Ready ||
            state is AgentController.SessionState.Streaming ||
            state is AgentController.SessionState.Connecting
    }

    fun confirmModelSwitch() {
        val id = _pendingModelSwitch.value ?: return
        _pendingModelSwitch.value = null
        setModel(id)
        resetChat()
    }

    /** Cancel keeps both the model and the conversation. */
    fun cancelModelSwitch() {
        _pendingModelSwitch.value = null
    }

    // ---- model presets -----------------------------------------------------
    //
    // The ask behind these: one saved model was too little — a working setup
    // switches between several ids. Picking a model (from the list, the free
    // text, or a preset) remembers it for the active provider; the composer's
    // quick switcher and the sheet's "saved" section serve them back.

    // ---- model groups ------------------------------------------------------
    //
    // A group is the whole configuration — service, model, endpoint, key — so
    // switching between two setups is one action instead of retyping an address.
    // This is what the earlier "profile" work was reaching for and did not
    // finish: the key was still per-provider and the endpoint still global.

    private val groupStore = ModelGroupStore(agent.context)

    private val _groups = MutableStateFlow(groupStore.all())
    val groups: StateFlow<List<ModelGroup>> = _groups.asStateFlow()

    private val _activeGroupId = MutableStateFlow(groupStore.active()?.id)
    val activeGroupId: StateFlow<String?> = _activeGroupId.asStateFlow()

    /** The credential for [group], read from its own slot. */
    fun keyFor(group: ModelGroup): String? =
        agent.keyStore.getSecret(ModelGroup.keySlot(group.id))

    fun hasKeyFor(group: ModelGroup): Boolean =
        agent.keyStore.hasSecret(ModelGroup.keySlot(group.id))

    fun saveGroup(group: ModelGroup, key: String? = null) {
        if (!key.isNullOrBlank()) {
            agent.keyStore.setSecret(ModelGroup.keySlot(group.id), key)
        }
        _groups.value = groupStore.save(group)
        _activeGroupId.value = group.id
    }

    fun deleteGroup(id: String) {
        agent.keyStore.clearSecret(ModelGroup.keySlot(id))
        _groups.value = groupStore.delete(id)
        _activeGroupId.value = groupStore.active()?.id
    }

    /**
     * Make [group] the running configuration.
     *
     * Everything moves together: provider, model, endpoint, shape and credential.
     * Applying three of the five is how a saved setup ends up unusable.
     */
    fun activateGroup(group: ModelGroup) {
        groupStore.setActive(group.id)
        _activeGroupId.value = group.id
        setProvider(group.provider)
        if (group.provider.isCustom) {
            agent.customProviderSettings.baseUrl = group.baseUrl
            agent.customProviderSettings.apiShape = group.apiShape
            agent.customProviderSettings.reasoning = group.reasoning
        }
        _model.value = group.model
        config.setModel(group.provider, group.model)
        _hasApiKey.value = hasKeyFor(group)
    }

    private val _presets = MutableStateFlow(agent.modelPresets.savedProfiles(_provider.value))

    /**
     * Saved configurations, most recently used first.
     *
     * Profiles rather than bare ids: the endpoint travels with the model, so
     * switching to a different service also switches the URL and the credential.
     * Storing only the id is what made a second endpoint unusable — the model
     * changed and the address did not.
     */
    val presets: StateFlow<List<ModelProfile>> = _presets.asStateFlow()

    private fun rememberPreset(profile: ModelProfile) {
        _presets.value = agent.modelPresets.remember(_provider.value, profile)
    }

    fun forgetPreset(profileKey: String) {
        _presets.value = agent.modelPresets.forget(_provider.value, profileKey)
    }

    /**
     * Switch to a saved profile, bringing its endpoint and credential with it.
     *
     * This is the whole point of storing profiles: choosing a model is choosing
     * a service, and the three things that describe a service — model, URL,
     * credential — move together or not at all.
     */
    fun selectProfile(profile: ModelProfile) {
        if (_provider.value.isCustom) {
            agent.customProviderSettings.baseUrl = profile.baseUrl
            agent.customProviderSettings.apiShape = profile.apiShape
            agent.customProviderSettings.reasoning = profile.reasoning
            _hasApiKey.value = agent.keyStore.hasApiKey(_provider.value)
        }
        _model.value = profile.model
        config.setModel(_provider.value, profile.model)
        rememberPreset(profile)
    }

    fun setProvider(next: AgentProvider) {
        _provider.value = next
        config.provider = next
        _hasApiKey.value = agent.keyStore.hasApiKey(next)
        _presets.value = agent.modelPresets.savedProfiles(next)
        // A model id belongs to the service that serves it, so carrying the old
        // choice across would send e.g. `glm-5.1` to Anthropic and fail at the
        // first prompt. Snap back to the new provider's own list instead.
        _models.value = if (next.isCustom) _customModels.value else next.models
        _model.value = if (next.isCustom) {
            savedCustomModel.ifBlank { config.model(next).orEmpty() }
        } else {
            config.model(next) ?: next.defaultModel
        }
        _model.value = _model.value.ifBlank { next.defaultModel }
        config.setModel(next, _model.value)
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

    private val _customReasoning = MutableStateFlow(custom.reasoning)
    val customReasoning: StateFlow<Boolean> = _customReasoning.asStateFlow()

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

    fun setCustomReasoning(value: Boolean) {
        _customReasoning.value = value
        custom.reasoning = value
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

    private val _transcript = MutableStateFlow<List<ChatItem>>(emptyList())
    val transcript: StateFlow<List<ChatItem>> = _transcript.asStateFlow()

    // Item ids are assigned once and never reused: the list keys on them and
    // they are persisted. A UUID rather than a counter because a restored
    // conversation would otherwise have to reseed a counter, and a collision
    // there is a duplicate LazyColumn key - a crash, not a cosmetic bug.
    private fun newItemId(): String = UUID.randomUUID().toString() 

    /**
     * Guest-side repo path. Lives here rather than in the chat screen so [send]
     * can start a session on the user's behalf: a chat app should not require a
     * "connect" step before the first message.
     */
    private val _repoPath = MutableStateFlow(config.repoPath)
    val repoPath: StateFlow<String> = _repoPath.asStateFlow()

    fun setRepoPath(next: String) {
        _repoPath.value = next.trim().ifBlank { "/root/repo" }
        config.repoPath = _repoPath.value
        notifyRes(R.string.ui_saved_working_dir)
    }

    /** Queued while the session starts; flushed on [AgentController.SessionState.Ready]. */
    private var pendingPrompt: String? = null

    init {
        agent.onEvent = ::onWrapperEvent
        // Anything the agent chooses to speak makes auto-speak stand down for
        // the rest of that run; see maybeAutoSpeak().
        agent.onSpoken = { spokeThisRun = true }
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
            notifyRes(R.string.ui_saved_api_key)
            fetchModels()
        }
    }

    fun clearApiKey() {
        val target = _provider.value
        viewModelScope.launch {
            withContext(Dispatchers.IO) { agent.keyStore.clearApiKey(target) }
            _hasApiKey.value = false
            _keyHint.value = null
            notifyRes(R.string.ui_key_cleared)
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
                _modelFetchError.value = UiText.Res(R.string.ui_fetch_models_needs_key)
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
                    _modelFetchError.value =
                        UiText.Raw(e.message ?: e.javaClass.simpleName)
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
            notifyRes(R.string.ui_saved_github_token)
        }
    }

    fun clearGitHubToken() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { agent.keyStore.clearGitHubToken() }
            _hasGitHubToken.value = false
        }
    }

    // ---- theme -------------------------------------------------------------
    private val _themeMode = MutableStateFlow(agent.themeSettings.mode)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        agent.themeSettings.mode = mode
        _themeMode.value = mode
    }

    // ---- voice detail ------------------------------------------------------
    // VoiceSettings has carried these three since the TTS work landed, but no
    // screen ever exposed them, so auto-speak could be turned on by nobody and
    // rate/pitch were stuck at 1.0.

    private val _autoSpeak = MutableStateFlow(agent.voiceSettings.autoSpeak)
    val autoSpeak: StateFlow<Boolean> = _autoSpeak.asStateFlow()

    fun setAutoSpeak(on: Boolean) {
        agent.voiceSettings.autoSpeak = on
        _autoSpeak.value = on
    }

    private val _voiceRate = MutableStateFlow(agent.voiceSettings.rate)
    val voiceRate: StateFlow<Float> = _voiceRate.asStateFlow()

    fun setVoiceRate(value: Float) {
        agent.voiceSettings.rate = value
        _voiceRate.value = agent.voiceSettings.rate
    }

    private val _voicePitch = MutableStateFlow(agent.voiceSettings.pitch)
    val voicePitch: StateFlow<Float> = _voicePitch.asStateFlow()

    fun setVoicePitch(value: Float) {
        agent.voiceSettings.pitch = value
        _voicePitch.value = agent.voiceSettings.pitch
    }

    // Whether the agent spoke by itself during the current run, and what was
    // last auto-spoken (so a repeated agent_end cannot say the same thing twice).
    /**
     * How to say "there is a code block here" when reading a reply aloud.
     *
     * The ViewModel has no Context, so the UI resolves this from resources and
     * hands it over — same rule as every other user-visible string. Auto-speak
     * needs it without a UI interaction, which is why it is a field rather than
     * a parameter.
     */
    var speechCodeBlockHint: String = ""

    private var spokeThisRun = false
    private var lastAutoSpokenId: String? = null

    private val _voiceEnabled = MutableStateFlow(agent.voiceSettings.enabled)
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled.asStateFlow()

    fun setVoiceEnabled(enabled: Boolean) {
        agent.voiceSettings.enabled = enabled
        _voiceEnabled.value = enabled
        if (!enabled) viewModelScope.launch { agent.stopSpeaking() }
    }

    private val _voiceInputEnabled = MutableStateFlow(agent.voiceSettings.voiceInputEnabled)
    val voiceInputEnabled: StateFlow<Boolean> = _voiceInputEnabled.asStateFlow()

    fun setVoiceInputEnabled(enabled: Boolean) {
        agent.voiceSettings.voiceInputEnabled = enabled
        _voiceInputEnabled.value = enabled
    }

    // ---- phone capabilities -------------------------------------------------

    /** Capability name -> allowed, mirroring [PhoneSettings] for the settings UI. */
    private val _phoneCaps = MutableStateFlow(
        agent.phoneSettings.capabilities.associate { it.name to agent.phoneSettings.enabled(it.name) },
    )
    val phoneCaps: StateFlow<Map<String, Boolean>> = _phoneCaps.asStateFlow()

    /** The capability names in the order the settings sheet should list them. */
    val phoneCapabilityNames: List<String> =
        agent.phoneSettings.capabilities.map { it.name }

    fun setPhoneCapability(name: String, enabled: Boolean) {
        agent.phoneSettings.setEnabled(name, enabled)
        _phoneCaps.value = _phoneCaps.value + (name to enabled)
        // The allow-list is built at session start, so a live session keeps its
        // current tools; say so rather than silently doing nothing.
        notifyRes(R.string.ui_next_session)
    }

    fun provision() = agent.provision()

    // ---- sandbox inspection -------------------------------------------------

    /**
     * Transient confirmation for actions with no visible result of their own.
     *
     * The complaints were specific: after saving a key or an endpoint there was
     * no signal at all, so it was impossible to tell "saved" from "tapped the
     * wrong thing". Every write path now ends here.
     */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    // ---- localised messages ------------------------------------------------
    // BUFFERED, not a bare SharedFlow: an unbounded replayless flow drops
    // anything emitted before the UI subscribes, and these messages are
    // fire-and-forget confirmations the user would never see again.
    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    /** A confirmation for the snackbar. */
    fun notifyRes(@StringRes id: Int, vararg args: Any) {
        _messages.trySend(UiMessage(id, args.toList()))
    }

    /** A system line in the conversation; the UI resolves it before storing. */
    private fun systemRes(@StringRes id: Int, vararg args: Any) {
        _messages.trySend(UiMessage(id, args.toList(), toTranscript = true))
    }

    /**
     * Called by the UI once it has turned a [UiMessage] into text. The
     * transcript keeps finished sentences on purpose: switching language later
     * should not retranslate a conversation that already happened.
     */
    fun appendSystemLine(text: String) {
        append("system", text)
    }

    // ---- composer draft ----------------------------------------------------
    // The composer is hosted inside an AnimatedContent in PiFillingApp, which
    // tears the chat composition down whenever settings open. Holding the draft
    // in a `remember` there meant opening settings silently discarded whatever
    // you had half-typed. It lives here instead, so it outlives the composition
    // (and a configuration change).
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    fun setDraft(text: String) {
        _draft.value = text
    }

    /** Called by MainActivity with whatever the recognizer heard. */
    fun onSpeechResult(text: String?) {
        val spoken = text?.trim().orEmpty()
        if (spoken.isEmpty()) return
        // Recognized speech appends rather than replaces, so a spoken follow-up
        // never destroys what was already typed.
        val current = _draft.value
        _draft.value = if (current.isBlank()) spoken else current + "\n" + spoken
    }

    fun notify(message: String) {
        _notice.value = message
        viewModelScope.launch {
            delay(2200)
            // Only clear if nothing newer replaced it, so two quick actions do
            // not cut each other's confirmation short.
            if (_notice.value == message) _notice.value = null
        }
    }

    private val _autoInstall = MutableStateFlow(agent.runtimeSettings.autoInstall)
    val autoInstall: StateFlow<Boolean> = _autoInstall.asStateFlow()

    fun setAutoInstall(enabled: Boolean) {
        agent.runtimeSettings.autoInstall = enabled
        _autoInstall.value = enabled
    }

    private val _sandboxReport = MutableStateFlow<UiText?>(null)
    val sandboxReport: StateFlow<UiText?> = _sandboxReport.asStateFlow()

    private val _sandboxBusy = MutableStateFlow(false)
    val sandboxBusy: StateFlow<Boolean> = _sandboxBusy.asStateFlow()

    /**
     * Live output of the sandbox's own commands, newest last, capped short.
     *
     * The install buttons used to go quiet for minutes at a time; apk and npm
     * both print what they are doing, so showing that is the difference between
     * "working" and "hung" — the one thing this sheet could not answer.
     */
    private val _sandboxLog = MutableStateFlow<List<String>>(emptyList())
    val sandboxLog: StateFlow<List<String>> = _sandboxLog.asStateFlow()

    private fun appendSandboxLog(line: String?) {
        val text = line?.trimEnd()?.takeIf { it.isNotBlank() } ?: return
        _sandboxLog.value = (_sandboxLog.value + text).takeLast(SANDBOX_LOG_LINES)
    }

    private fun clearSandboxLog() { _sandboxLog.value = emptyList() }

    /**
     * Clear registry entries whose processes are confirmed gone.
     *
     * A process that died without being released — app crash, OS kill — leaves
     * an entry that shuts the gate permanently. This is the exit, and it only
     * opens when every recorded process is confirmed dead.
     */
    fun reconcileRegistry() {
        viewModelScope.launch {
            val result = runCatching { agent.reconcileRegistry() }
                .getOrElse { "失败: ${it.message}" }
            notify(result)
        }
    }

    /**
     * Clear a reservation that never started a process.
     *
     * Only succeeds when the record has no pid and the registry is empty, so a
     * stalled spawn can be recovered without risking a live process.
     */
    fun abandonStalledReservation() {
        viewModelScope.launch {
            val result = runCatching { agent.abandonStalledReservation() }
                .getOrElse { "失败: ${it.message}" }
            notify(result)
        }
    }

    /**
     * Export the recovery state to a file the user can retrieve.
     *
     * The app's private directory is not reachable from a host shell on this
     * device, so this is the only way to inspect what the wipe machinery
     * recorded during a run.
     */
    fun exportRecoveryState() {
        val result = runCatching { agent.exportRecoveryState() }
            .getOrElse { "导出失败: ${it.message}" }
        notify(result)
    }

    /** Ask the guest what it actually has installed. */
    fun refreshSandboxReport() {
        if (_sandboxBusy.value) return
        viewModelScope.launch {
            _sandboxBusy.value = true
            _sandboxReport.value = runCatching { UiText.Raw(agent.inspectSandbox()) }
                .getOrElse { UiText.Res(R.string.ui_check_failed, listOf(it.message ?: it.javaClass.simpleName)) }
            _sandboxBusy.value = false
        }
    }

    /** Install the guest's apk packages explicitly. */
    fun installSandboxTools() {
        if (_sandboxBusy.value) return
        viewModelScope.launch {
            _sandboxBusy.value = true
            clearSandboxLog()
            _sandboxReport.value = runCatching {
                UiText.Raw(agent.installSandboxTools { line -> appendSandboxLog(line) })
            }
                .getOrElse { UiText.Res(R.string.ui_op_failed, listOf(it.message ?: it.javaClass.simpleName)) }
            _sandboxBusy.value = false
            _sandboxReport.value = runCatching { UiText.Raw(agent.inspectSandbox()) }
                .getOrElse { _sandboxReport.value }
        }
    }

    // ---- apk source --------------------------------------------------------

    /**
     * Which apk mirror leads the guest's repositories, as a base URL.
     * Empty means "follow the locale guess" (zh devices lead with Tsinghua).
     */
    val apkMirror: StateFlow<String> =
        MutableStateFlow(agent.runtimeSettings.apkMirror)

    /** Mirrors we know about, for the picker. */
    val knownApkMirrors: List<String> get() = RootfsDownloader.MIRRORS

    /** Set the apk mirror. An empty string restores the automatic (locale) choice. */
    fun setApkMirror(url: String) {
        agent.runtimeSettings.apkMirror = url
        (apkMirror as MutableStateFlow<String>).value = url
        // Re-point the guest at the new mirror on the next install/top-up;
        // nothing is downloaded here, so this is cheap and safe to do now.
        notifyRes(R.string.ui_apk_mirror_set)
    }

    /** Force a wrapper redeploy; with [installDeps] also re-run the package install. */
    fun repairSandbox(installDeps: Boolean) {
        if (_sandboxBusy.value) return
        viewModelScope.launch {
            _sandboxBusy.value = true
            clearSandboxLog()
            _sandboxReport.value = runCatching {
                UiText.Raw(agent.redeploySandbox(installDeps) { line -> appendSandboxLog(line) })
            }
                .getOrElse { UiText.Res(R.string.ui_op_failed, listOf(it.message ?: it.javaClass.simpleName)) }
            _sandboxBusy.value = false
            // The report is the interesting part; refresh it so the screen shows
            // the state after the repair rather than the text of the repair.
            _sandboxReport.value = runCatching { UiText.Raw(agent.inspectSandbox()) }
                .getOrElse { _sandboxReport.value }
        }
    }

    /** Human-readable list of the tools the sandbox promises to have installed. */
    // ---- optional toolchains ----------------------------------------------

    private val _toolchains = MutableStateFlow<Map<String, ToolchainState>>(emptyMap())
    val toolchains: StateFlow<Map<String, ToolchainState>> = _toolchains.asStateFlow()

    private val _toolchainBusy = MutableStateFlow<String?>(null)
    val toolchainBusy: StateFlow<String?> = _toolchainBusy.asStateFlow()

    private val _toolchainError = MutableStateFlow<String?>(null)
    val toolchainError: StateFlow<String?> = _toolchainError.asStateFlow()

    /** Probe the sandbox; cheap and safe to call whenever the sheet opens. */
    fun refreshToolchains() {
        viewModelScope.launch {
            runCatching { agent.sandbox.toolchainStates() }
                .onSuccess { _toolchains.value = it }
                .onFailure { _toolchainError.value = it.message }
        }
    }

    fun installToolchain(tc: Toolchain) {
        if (_toolchainBusy.value != null) return
        _toolchainBusy.value = tc.id
        _toolchainError.value = null
        viewModelScope.launch {
            val error = runCatching { agent.sandbox.installToolchain(tc) }
                .getOrElse { it.message ?: "未知错误" }
            _toolchainBusy.value = null
            _toolchainError.value = error
            refreshToolchains()
        }
    }

    fun removeToolchain(tc: Toolchain) {
        if (_toolchainBusy.value != null) return
        _toolchainBusy.value = tc.id
        _toolchainError.value = null
        viewModelScope.launch {
            val error = runCatching { agent.sandbox.removeToolchain(tc) }
                .getOrElse { it.message ?: "未知错误" }
            _toolchainBusy.value = null
            _toolchainError.value = error
            refreshToolchains()
        }
    }

    val provisionedTools: String
        get() = agent.sandbox.provisionedPackages.joinToString(" · ")

    // ---- chat history ------------------------------------------------------
    //
    // The wrapper cannot restore a conversation: it holds one Agent for the life
    // of a process, and nothing in its protocol accepts prior messages. So a
    // reopened conversation is a transcript to read, not a session to continue.

    private val store = ChatHistoryStore(agent.filesDir)

    private val _history = MutableStateFlow<List<StoredSession>>(emptyList())

    /** Saved conversations, newest first, for the drawer. */
    val history: StateFlow<List<StoredSession>> = _history.asStateFlow()

    /** Non-null while an archived conversation is open read-only. */
    private val _viewingSessionId = MutableStateFlow<String?>(null)
    val viewingSessionId: StateFlow<String?> = _viewingSessionId.asStateFlow()

    val isViewingHistory: Boolean get() = _viewingSessionId.value != null

    /**
     * The conversation the drawer should highlight.
     *
     * A live conversation and an archived one being read are different states,
     * but the drawer only needs "which row is the user in". Exposing that one
     * answer here keeps the UI from having to know about both.
     */
    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    /**
     * True while the open conversation was reopened from history and the agent
     * has not started a new run in it yet.
     *
     * The transcript is real; the agent's memory is not. The banner that says so
     * clears itself on the first new message, because after that the agent has
     * its own context for everything below.
     */
    private val _continuedFromHistory = MutableStateFlow(false)
    val continuedFromHistory: StateFlow<Boolean> = _continuedFromHistory.asStateFlow()

    /** Recompute the highlighted row; called whenever either id changes. */
    private fun refreshActiveSessionId() {
        _activeSessionId.value = _viewingSessionId.value ?: currentSessionId
    }

    /**
     * This conversation's own id.
     *
     * Restored from disk when the app reopens, so the conversation the user was
     * in is the one the drawer highlights and the one they come back to. It used
     * to be minted fresh on every launch, which made reopening the app feel like
     * the previous conversation had been thrown away.
     */
    private var currentSessionId: String =
        store.readOpenSession()?.id ?: ChatHistoryStore.newSessionId()

    private var lastSaveAt = 0L
    private var saveJob: Job? = null

    fun loadHistory() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { store.load() }
            _history.value = loaded
            // Restore the conversation that was open. Without this the app
            // reopened into an empty transcript while the drawer highlighted a
            // row — the user's last conversation looked deleted.
            //
            // Only a *live* one is restored as editable: an archived session is
            // read-only, and silently attaching it to a new run would let the
            // agent continue a conversation the user had finished.
            if (_transcript.value.isEmpty()) {
                val open = store.readOpenSession()
                when {
                    // An id that is not in the history means the conversation
                    // never got a message — it was a fresh chat, so a blank one
                    // is the correct restoration.
                    open != null && loaded.none { it.id == open.id } -> Unit

                    open != null -> loaded.firstOrNull { it.id == open.id }?.let { session ->
                        _transcript.value = session.items
                        if (open.viewing) {
                            // Came back to an archived conversation: keep it
                            // read-only, and point the drawer at it.
                            _viewingSessionId.value = session.id
                        } else {
                            currentSessionId = session.id
                        }
                    }

                    // No record at all (first launch after an upgrade): the most
                    // recently touched conversation is the best guess, and a
                    // blank screen would look like the history had been lost.
                    loaded.isNotEmpty() -> {
                        currentSessionId = loaded.first().id
                        _transcript.value = loaded.first().items
                    }
                }
            }
            refreshActiveSessionId()
        }
    }

    /**
     * Persist the open conversation.
     *
     * Throttled: a streaming reply would otherwise write the whole transcript on
     * every token. Callers that matter (agent_end, backgrounding, a conversation
     * switch) force it.
     */
    private fun persistCurrent(force: Boolean = false) {
        if (isViewingHistory) return
        val items = _transcript.value
        // An empty conversation is still a conversation: the drawer needs a row
        // to highlight, and reopening the app should land where the user left
        // off. Skipping empty transcripts meant a brand-new chat had no row at
        // all, so the selected state could not be shown anywhere.
        //
        // The guard against writing *nothing* is kept: a transcript with no
        // content at all and no title would be a blank row.
        if (items.isEmpty()) {
            if (_history.value.any { it.id == currentSessionId }) return
        }

        val elapsed = System.currentTimeMillis() - lastSaveAt
        if (!force && elapsed < SAVE_THROTTLE_MS) {
            if (saveJob == null) {
                saveJob = viewModelScope.launch {
                    delay(SAVE_THROTTLE_MS - elapsed)
                    saveJob = null
                    persistCurrent(force = true)
                }
            }
            return
        }
        lastSaveAt = System.currentTimeMillis()

        // Record which conversation is open, on every save.
        //
        // It used to be written only when starting a new conversation, so a user
        // who stayed in one chat never had its id persisted — and reopening the
        // app had nothing to restore, which is exactly the "always lands on a new
        // chat" that was reported.
        store.writeOpenSession(currentSessionId, viewing = false)

        val session = StoredSession(
            id = currentSessionId,
            title = ChatHistoryStore.titleOf(items),
            updatedAt = System.currentTimeMillis(),
            items = ChatHistoryStore.capSize(items),
        )
        val merged = (listOf(session) + _history.value.filterNot { it.id == session.id })
            .sortedByDescending { it.updatedAt }
            .take(ChatHistoryStore.MAX_SESSIONS)
        _history.value = merged
        viewModelScope.launch(Dispatchers.IO) { store.save(merged) }
    }

    /**
     * Write immediately and **wait**, for the moment the process is dying.
     *
     * `persistCurrent` schedules the disk write and returns, so a kill right
     * after it could leave `open-session.id` pointing at a conversation whose
     * contents never landed. Here the content is written first and only then is
     * the pointer updated — a pointer to nothing is worse than no pointer.
     *
     * Bounded: a blocked write must not hold up the main thread indefinitely.
     */
    fun flushHistory() {
        val items = _transcript.value
        if (isViewingHistory) return
        val session = StoredSession(
            id = currentSessionId,
            title = ChatHistoryStore.titleOf(items),
            updatedAt = System.currentTimeMillis(),
            items = ChatHistoryStore.capSize(items),
        )
        val merged = (listOf(session) + _history.value.filterNot { it.id == session.id })
            .sortedByDescending { it.updatedAt }
            .take(ChatHistoryStore.MAX_SESSIONS)
        _history.value = merged
        val wrote = runCatching {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(FLUSH_TIMEOUT_MS) { store.saveBlocking(merged) } != null
            }
        }.getOrDefault(false)
        // Only after the content is on disk.
        if (wrote) store.writeOpenSession(currentSessionId, viewing = false)
    }

    /** Done with a conversation: store it, then start an empty one. */
    fun archiveAndStartNew() {
        persistCurrent(force = true)
        agent.endSession()
        _transcript.value = emptyList()
        _viewingSessionId.value = null
        _continuedFromHistory.value = false
        currentSessionId = ChatHistoryStore.newSessionId()
        store.writeOpenSession(currentSessionId, viewing = false)
        pendingPrompt = null
        lastAutoSpokenId = null
        refreshActiveSessionId()
    }

    /** Open a stored conversation. Read-only by construction. */
    /**
     * Reopen an archived conversation **as the current one**, ready to continue.
     *
     * It used to open read-only with a "start a new chat from here" button. That
     * read as the app having thrown the conversation away: the user tapped a
     * history row expecting to carry on, and instead got a banner telling them
     * they could start over.
     *
     * What cannot be carried over is the agent's own memory — the wrapper process
     * that held it has exited, and nothing in its protocol accepts prior
     * messages. So the transcript returns as editable history and the next
     * message starts a fresh agent run with the conversation above it visible.
     * The UI says so, rather than pretending continuity that does not exist.
     */
    fun openHistorySession(id: String) {
        val session = _history.value.firstOrNull { it.id == id } ?: return
        persistCurrent(force = true)
        agent.endSession()
        _transcript.value = session.items
        // Becomes the live conversation: same id, so further messages extend it
        // rather than forking a copy.
        _viewingSessionId.value = null
        currentSessionId = session.id
        _continuedFromHistory.value = true
        store.writeOpenSession(session.id, viewing = false)
        refreshActiveSessionId()
        pendingPrompt = null
        lastAutoSpokenId = null
    }

    fun deleteHistorySession(id: String) {
        val remaining = _history.value.filterNot { it.id == id }
        _history.value = remaining
        if (_viewingSessionId.value == id) {
            agent.endSession()
            _transcript.value = emptyList()
            _viewingSessionId.value = null
            currentSessionId = ChatHistoryStore.newSessionId()
            refreshActiveSessionId()
        }
        viewModelScope.launch(Dispatchers.IO) { store.save(remaining) }
    }

    fun clearHistory() {
        _history.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) { store.clear() }
    }

    /** Start a fresh conversation; provider, key and endpoint are kept. */
    fun resetChat() = archiveAndStartNew()

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
            reasoning = _customReasoning.value,
        )
        return true
    }

    fun send(text: String) {
        if (text.isBlank()) return
        append("you", text)
        when (val state = agent.session.value) {
            is AgentController.SessionState.Ready -> agent.prompt(text)
            is AgentController.SessionState.Streaming -> {
                // Resend / regenerate while the previous answer is still coming:
                // stop that run and queue this one — the init collector flushes
                // it as soon as the session settles back to Ready.
                agent.abort()
                pendingPrompt = text
            }
            is AgentController.SessionState.Connecting,
            is AgentController.SessionState.Provisioning -> {
                // A session is already on its way up; queue rather than start a
                // second wrapper on top of it.
                pendingPrompt = text
            }
            else -> {
                // No session yet: start one in the background and queue the message.
                // Dropping the text or showing "no active session" would both make
                // the app feel broken on the very first use.
                pendingPrompt = text
                if (!startSession(_repoPath.value)) {
                    pendingPrompt = null
                    systemRes(R.string.ui_transcript_error, ResArg(missingConfigHint()))
                }
            }
        }
    }

    // ---- message actions (copy / edit / regenerate / read aloud) ------------
    //
    // What a normal chat app offers that this one did not: every message can be
    // copied, a reply can be read aloud or regenerated, and the last user
    // message can be pulled back into the composer for editing.

    /** Plain-text rendering of the whole conversation, for "copy all". */
    fun transcriptText(): String =
        _transcript.value.joinToString("\n\n") { item ->
            when (item) {
                is ChatItem.MessageItem -> when (item.role) {
                    "you" -> "you: ${item.text}"
                    "system" -> "· ${item.text}"
                    else -> item.text
                }
                is ChatItem.ToolItem -> "\u25b8 ${item.name}"
            }
        }

    /**
     * Remove the last user message and everything after it, returning its text.
     * The shared step behind "edit" (text goes back into the composer) and
     * "regenerate" (text is sent again).
     */
    fun retractAfterLastUser(): String? {
        val items = _transcript.value
        val lastUserIndex = items.indexOfLast { it is ChatItem.MessageItem && it.role == "you" }
        if (lastUserIndex < 0) return null
        val text = (items[lastUserIndex] as ChatItem.MessageItem).text
        _transcript.value = items.subList(0, lastUserIndex)
        return text
    }

    /** Re-ask the last user message: drop the tail and send it again. */
    fun regenerate() {
        val text = retractAfterLastUser() ?: return
        send(text)
    }

    /** Pull the last user message back into the composer for editing. */
    fun editLastUser(): String? {
        // A reply still streaming belongs to the old version of this message;
        // stop it before the text is retracted.
        if (agent.session.value is AgentController.SessionState.Streaming) agent.abort()
        return retractAfterLastUser()
    }

    /** Read one message aloud on the user's tap; independent of the voice switch. */
    fun speakMessage(text: String) {
        viewModelScope.launch {
            runCatching { agent.speakText(text) }
                .onFailure { notifyRes(R.string.ui_speak_failed) }
        }
    }

    /** Why a session could not start, phrased as the one thing the user must fix. */
    @StringRes
    private fun missingConfigHint(): Int {
        val p = _provider.value
        if (!p.isCustom) return R.string.ui_transcript_no_session
        return when {
            custom.baseUrl.isBlank() -> R.string.ui_transcript_need_base_url
            _model.value.isBlank() -> R.string.ui_transcript_need_model_id
            else -> R.string.ui_transcript_no_session
        }
    }

    /**
     * Read the finished reply aloud when the user asked for that.
     *
     * Skipped when the agent already spoke during this run: an agent that calls
     * `tts.speak` has decided what to say, and speaking the whole reply again
     * right after would say it twice. Also skipped when the text is identical to
     * the last thing auto-speak said, because agent_end can fire again for the
     * same reply (a regenerate that produces the same answer, a reconnect).
     */
    private fun maybeAutoSpeak() {
        if (!agent.voiceSettings.enabled || !agent.voiceSettings.autoSpeak) return
        if (spokeThisRun) return
        val reply = _transcript.value
            .lastOrNull { it is ChatItem.MessageItem && it.role == "assistant" }
            as? ChatItem.MessageItem ?: return
        val text = markdownToSpeech(reply.text, speechCodeBlockHint).trim()
        if (text.isEmpty()) return
        // Compared by id, not by text: asking the same question twice produces
        // two different replies that may read identically, and both should be
        // spoken. What must not repeat is the *same* message being spoken twice.
        if (reply.id == lastAutoSpokenId) return
        lastAutoSpokenId = reply.id
        viewModelScope.launch {
            runCatching { agent.speakText(text) }
        }
    }

    fun abort() = agent.abort()

    fun endSession() = agent.endSession()

    private fun onWrapperEvent(event: WrapperEvent) {
        when (event.type) {
            "agent_start" -> spokeThisRun = false
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
                val callId = event.data["toolCallId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val name = event.data["toolName"]?.jsonPrimitive?.contentOrNull ?: "tool"
                val before = _transcript.value.size
                _transcript.value = _transcript.value + ChatItem.ToolItem(
                    id = newItemId(),
                    toolCallId = callId,
                    name = name,
                    status = ToolStatus.RUNNING,
                    args = clip(event.data["args"]?.toString().orEmpty()),
                )
            }
            // The matching completion. Payload is
            //   { toolCallId, toolName, args, result, isError }
            // `tool_execution_update` is deliberately ignored: it fires for every
            // partial chunk, and rebuilding the list on each one would make the
            // transcript scroll like a terminal.
            "tool_execution_end" -> {
                val callId = event.data["toolCallId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val failed = event.data["isError"]?.jsonPrimitive?.booleanOrNull == true
                val result = clip(event.data["result"]?.toString().orEmpty())
                val status = if (failed) ToolStatus.FAILED else ToolStatus.SUCCESS
                val items = _transcript.value
                val index = items.indexOfLast { it is ChatItem.ToolItem && it.toolCallId == callId }
                _transcript.value = if (index >= 0) {
                    items.toMutableList().also {
                        it[index] = (it[index] as ChatItem.ToolItem)
                            .copy(status = status, result = result)
                    }
                } else {
                    // An end without a start (a reconnect mid-tool, or a tool the
                    // agent ran before we were listening): keep it rather than
                    // dropping the only evidence it happened.
                    items + ChatItem.ToolItem(
                        id = newItemId(),
                        toolCallId = callId,
                        name = event.data["toolName"]?.jsonPrimitive?.contentOrNull ?: "tool",
                        status = status,
                        result = result,
                    )
                }
            }
            "agent_end" -> {
                systemRes(R.string.ui_transcript_done)
                maybeAutoSpeak()
                persistCurrent(force = true)
            }
            "wrapper_error" -> {
                val raw = event.data["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
                systemRes(R.string.ui_transcript_error, raw)
                // A "temporarily unable / retry later" upstream is not a config bug;
                // tell the user plainly that waiting a moment and resending works.
                if (Regex("(?i)temporarily|retry later|unavailable|overloaded").containsMatchIn(raw)) {
                    systemRes(R.string.ui_service_busy)
                }
            }
        }
    }

    /** Keep tool payloads bounded; a full `ls -R` should not live in memory. */
    private fun clip(text: String): String =
        if (text.length <= TOOL_PAYLOAD_LIMIT) text else text.take(TOOL_PAYLOAD_LIMIT) + "…"

    private fun append(role: String, text: String) {
        _transcript.value = _transcript.value + ChatItem.MessageItem(newItemId(), role, text)
        // Save on the user's own message immediately: from this point the
        // conversation exists and must survive a kill, even if no reply ever
        // arrives. Agent text is saved on the same throttle as streaming.
        if (role == "you") _continuedFromHistory.value = false
        persistCurrent(force = role == "you")
    }

    /**
     * Extend the reply that is currently streaming.
     *
     * Only the last row can be extended: if a tool ran in between, the text that
     * follows it is a new message rather than a continuation of the old one,
     * which is also how the agent sees it.
     */
    private fun appendToLast(role: String, delta: String) {
        val current = _transcript.value
        val last = current.lastOrNull()
        _transcript.value = if (last is ChatItem.MessageItem && last.role == role) {
            current.dropLast(1) + last.copy(text = last.text + delta)
        } else {
            current + ChatItem.MessageItem(newItemId(), role, delta)
        }
        // Throttled: this fires per token, and writing the whole transcript on
        // every one would dominate the frame budget. Two seconds is short enough
        // that a kill loses at most a sentence.
        persistCurrent(force = false)
    }

    companion object {
        /** Enough to see what apk/npm are doing, short enough to stay readable. */
        private const val SANDBOX_LOG_LINES = 120

        /** How long a dying app will wait for its transcript to reach disk. */
        private const val FLUSH_TIMEOUT_MS = 1_500L

        /** Streaming would otherwise write the transcript on every token. */
        private const val SAVE_THROTTLE_MS = 2_000L

        /** Tool arguments and results are shown, not archived. */
        private const val TOOL_PAYLOAD_LIMIT = 4_000

        fun factory(agent: AgentController): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppViewModel(agent) as T
            }
    }
}
