package industries.huginn.pifilling.runtime

import android.content.Context
import android.util.Log
import industries.huginn.pifilling.wrapper.WrapperTimeoutException
import industries.huginn.pifilling.sandbox.LinuxSandboxManager
import industries.huginn.pifilling.sandbox.AgentProvider
import industries.huginn.pifilling.sandbox.SandboxState
import industries.huginn.pifilling.service.DaemonService
import industries.huginn.pifilling.storage.SecureKeyStore
import industries.huginn.pifilling.voice.TtsCapabilities
import industries.huginn.pifilling.phone.PhoneCapabilities
import industries.huginn.pifilling.phone.PhoneSettings
import industries.huginn.pifilling.ui.LocaleSettings
import industries.huginn.pifilling.ui.ThemeSettings
import industries.huginn.pifilling.ui.ModelPresets
import industries.huginn.pifilling.ui.ActiveConfig
import industries.huginn.pifilling.ui.CustomProviderSettings
import industries.huginn.pifilling.ui.RuntimeSettings
import industries.huginn.pifilling.voice.VoiceSettings
import industries.huginn.pifilling.wrapper.HostCapabilityRegistry
import industries.huginn.pifilling.wrapper.WrapperClient
import industries.huginn.pifilling.wrapper.WrapperEventType
import industries.huginn.pifilling.wrapper.WrapperProcessExitedException
import industries.huginn.pifilling.wrapper.WrapperResponse
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-lifetime orchestrator that the UI observes. Owns the sandbox, the key
 * store, and (when a session is live) one [WrapperClient]. Kept off the Activity
 * so a run survives configuration changes and brief backgrounding; the
 * [DaemonService] keeps the OS from reclaiming the process while a session runs.
 *
 * This is deliberately thin scaffolding: it demonstrates the Layer 1 -> Layer 3
 * wiring contract end-to-end. Transcript modeling, cost metering, and run
 * history are Stage 1.4/1.5 concerns layered on top of [events].
 */
class AgentController(
    private val appContext: Context,
    val sandbox: LinuxSandboxManager,
    val keyStore: SecureKeyStore,
) {
    // Without a handler an uncaught exception in any child reaches the thread's
    // default handler, which on Android means the app dies. Every call site
    // below should be catching, but the wrapper is a separate process that can
    // fail in ways call sites do not anticipate, so this is the backstop rather
    // than the plan. See CONVENTIONS.md C04.
    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "unhandled failure in agent scope", e)
        _session.value = SessionState.Failed(e.message ?: e.javaClass.simpleName)
    }

    private val scope = CoroutineScope(SupervisorJob() + exceptionHandler)

    /**
     * What this host is willing to do on the agent's behalf. Built once and
     * allow-listed here: the agent may ask for a capability by name, but only
     * Layer 1 decides what exists. See wrapper/HostCapability.kt.
     */
    val voiceSettings = VoiceSettings(appContext)

    /** UI language choice. Device-level, like [voiceSettings], and not a secret. */
    val localeSettings = LocaleSettings(appContext)

    /** App-private storage; history lives under it and must stay there. */
    val filesDir: java.io.File get() = appContext.filesDir

    /**
     * The application context, for preference stores that live outside the
     * controller.
     *
     * Deliberately narrow: the model-group store needs SharedPreferences and
     * nothing else, and handing out the context lets it own its own persistence
     * rather than routing every read through here.
     */
    val context: Context get() = appContext
    val themeSettings = ThemeSettings(appContext)
    val modelPresets = ModelPresets(appContext)

    /** Provider/model/endpoint/repo the app is pointed at; persisted across kills. */
    val activeConfig = ActiveConfig(appContext)

    /**
     * Endpoint configuration for the custom provider. Device-level: a Base URL
     * describes an external service, not this sandbox, so it must survive a
     * rootfs rebuild the way the API key itself does.
     */
    val customProviderSettings = CustomProviderSettings(appContext)

    /** User-controlled runtime behaviour, e.g. whether setup runs automatically. */
    val runtimeSettings = RuntimeSettings(appContext)
    private val tts = TtsCapabilities(appContext, voiceSettings)

    /** Fires whenever something is spoken, whoever asked for it. */
    var onSpoken: ((String) -> Unit)? = null
    private val phone = PhoneCapabilities(appContext)

    /** Which phone controls the user has allowed the agent to use. */
    val phoneSettings = PhoneSettings(appContext)

    /**
     * Built per session rather than once at startup: toggling a capability in
     * settings must change what the next session offers, and the allow-list is
     * what decides that. A capability left off simply is not registered, so the
     * agent cannot ask for it at all.
     */
    private fun currentHostCapabilities(): HostCapabilityRegistry =
        HostCapabilityRegistry(
            tts.all() + phone.all().filter { phoneSettings.enabled(it.name) },
        )

    init {
        // Top up an already-provisioned sandbox as soon as the app starts,
        // rather than waiting for the first session. A new build that widens
        // APK_PACKAGES should have its tools by the time the user can type a
        // prompt; the per-session call in startSession() remains as a retry.
        // Respect the user's choice: an automatic ~350 MB install on a metered
        // connection is exactly the kind of thing an app should not do silently.
        if (runtimeSettings.autoInstall) {
            scope.launch(Dispatchers.IO) {
                runCatching { sandbox.ensureCurrent() }
                    .onFailure { Log.w(TAG, "startup sandbox upgrade failed", it) }
            }
        }
    }

    /** Stop any speech in progress. Exposed so the UI can offer a stop control. */
    init {
        tts.onSpoken = { spoken -> onSpoken?.invoke(spoken) }
    }

    suspend fun stopSpeaking() = tts.stop()

    /**
     * Speak one message on the user's explicit request (the "read aloud" action
     * on a reply). Unlike the agent's own `voice_speak` tool this does not check
     * the voice master switch: a person who tapped "read it to me" has already
     * made the decision the switch exists to gate.
     */
    suspend fun speakText(text: String) = tts.speakNow(text)

    private val _session = MutableStateFlow<SessionState>(SessionState.Idle)
    val session: StateFlow<SessionState> = _session.asStateFlow()

    val sandboxState: StateFlow<SandboxState> get() = sandbox.state

    /**
     * Last failure worth showing in a diagnostics view.
     *
     * Kept separate from [SessionState.Failed] because the session state is
     * transient (the next attempt overwrites it) while this is the thing a user
     * needs to read after the fact: which command failed, and what the wrapper
     * actually printed.
     */
    private val _lastDiagnostic = MutableStateFlow<String?>(null)
    val lastDiagnostic: StateFlow<String?> = _lastDiagnostic.asStateFlow()

    private var client: WrapperClient? = null
    private var process: Process? = null
    private var lastReady: SessionState.Ready? = null
    private var eventsJob: Job? = null

    sealed interface SessionState {
        data object Idle : SessionState
        data object Provisioning : SessionState
        data object Connecting : SessionState
        data class Ready(val model: String, val repoPath: String, val hasMemory: Boolean) : SessionState
        data object Streaming : SessionState
        data class Failed(val message: String) : SessionState
    }

    /** Clear registry entries whose processes are confirmed gone. */
    suspend fun reconcileRegistry(): String = sandbox.reconcileRegistry()

    /** Abandon a reservation that provably never started a process. */
    suspend fun abandonStalledReservation(): String = sandbox.abandonStalledReservation()

    /** Snapshot of the recovery state, for on-device acceptance. */
    fun recoverySnapshot(): String = sandbox.recoverySnapshot()

    /** Write the recovery snapshot somewhere the user can retrieve it. */
    fun exportRecoveryState(): String = sandbox.exportRecoveryState()

    /** Report what is actually installed in the guest, for the sandbox screen. */
    suspend fun inspectSandbox(): String = sandbox.inspect()

    /** Install the guest's apk packages on user request. */
    suspend fun installSandboxTools(onLine: ((String) -> Unit)? = null): String =
        sandbox.installTools(onLine)

    /** Redeploy the wrapper (and optionally its dependencies) on user request. */
    suspend fun redeploySandbox(installDeps: Boolean, onLine: ((String) -> Unit)? = null): String =
        sandbox.redeploy(installDeps, onLine)

    /** Provision the sandbox if needed. Surfaces progress via [sandboxState]. */
    fun provision() {
        scope.launch {
            _session.value = SessionState.Provisioning
            try {
                sandbox.setup()
                _session.value = SessionState.Idle
            } catch (e: Exception) {
                _session.value = SessionState.Failed(e.message ?: "provision failed")
            }
        }
    }

    /**
     * Start an agent session against [repoGuestPath] (a path inside the rootfs).
     * Requires a stored API key and a [SandboxState.Ready] sandbox.
     */
    fun startSession(
        repoGuestPath: String,
        provider: AgentProvider = AgentProvider.DEFAULT,
        model: String? = null,
        /** Custom provider only: endpoint and wire protocol. */
        baseUrl: String? = null,
        apiShape: String = "openai-completions",
        /** Custom provider only: the endpoint is a reasoning model. */
        reasoning: Boolean = false,
    ) {
        scope.launch {
            // Key decryption touches the AndroidKeyStore + disk; keep it off the
            // default dispatcher's small pool.
            val apiKey = withContext(Dispatchers.IO) { keyStore.getApiKey(provider) }
            // Optional: only `git push` needs it, so its absence must not block
            // a session that never pushes.
            val gitHubToken = withContext(Dispatchers.IO) { keyStore.getGitHubToken() }
            if (apiKey.isNullOrBlank()) {
                _session.value = SessionState.Failed("no ${provider.label} API key set")
                return@launch
            }
            if (!sandbox.isReady) {
                _session.value = SessionState.Failed("sandbox not provisioned")
                return@launch
            }
            // A sandbox provisioned by an older build never re-enters setup(),
            // so apply any missing setup steps here — this is the point where a
            // missing git identity would otherwise surface as a failed commit.
            withContext(Dispatchers.IO) { sandbox.ensureCurrent() }
            try {
                _session.value = SessionState.Connecting
                // The foreground service is a keep-alive hint only; the wrapper
                // process is already held by `process` and tracked in the
                // registry. It has been the source of repeated
                // ForegroundServiceDidNotStartInTimeException crashes on this
                // device, and a hint is not worth a crash: disabled until the
                // startForeground failure can be diagnosed from a real log.
                // See DaemonService.ENABLED.

                // The wrapper refuses to start when --repo is not an existing
                // directory (exit code 2), and a fresh sandbox has no /root/repo,
                // so create it here rather than letting the session die with an
                // exit code that says nothing about the cause.
                withContext(Dispatchers.IO) { sandbox.ensureRepoDir(repoGuestPath) }

                val proc = withContext(Dispatchers.IO) {
                    sandbox.startWrapper(
                        apiKey = apiKey,
                        repoGuestPath = repoGuestPath,
                        provider = provider,
                        model = model,
                        gitHubToken = gitHubToken,
                        baseUrl = baseUrl,
                        apiShape = apiShape,
                        reasoning = reasoning,
                    )
                }
                val wc = WrapperClient(proc, scope, hostCapabilities = currentHostCapabilities())
                process = proc
                client = wc

                eventsJob = scope.launch { drainEvents(wc) }
                wc.start()

                val ready = wc.awaitReady()
                val readyState = SessionState.Ready(ready.model, ready.repoPath, ready.hasMemory)
                lastReady = readyState
                _session.value = readyState
            } catch (e: Exception) {
                Log.e(TAG, "startSession failed", e)
                val detail = describeFailure(e)
                _lastDiagnostic.value = detail
                _session.value = SessionState.Failed(detail)
                teardown()
            }
        }
    }

    fun prompt(text: String) {
        val wc = client ?: run {
            _lastDiagnostic.value = "没有活动会话：会话可能已经退出，再发一条消息会自动重连。"
            _session.value = SessionState.Failed("no active session")
            return
        }
        scope.launch {
            // The wrapper is a separate process under proot; it can die or wedge
            // mid-request. Unguarded, both surfaced as an app crash rather than a
            // failed session — this was the only unguarded caller (C04).
            try {
                when (val resp = wc.prompt(text)) {
                    is WrapperResponse.Ok -> _session.value = SessionState.Streaming
                    is WrapperResponse.Err ->
                        _session.value = SessionState.Failed("${resp.code}: ${resp.message}")
                }
            } catch (e: WrapperProcessExitedException) {
                Log.w(TAG, "wrapper exited during prompt", e)
                _session.value = SessionState.Failed("wrapper exited (code ${e.exitCode})")
                teardown()
            } catch (e: WrapperTimeoutException) {
                // Alive but unresponsive: leave the process up so abort/shutdown
                // still have something to talk to, and let the user decide.
                Log.w(TAG, "wrapper timed out during prompt", e)
                _session.value = SessionState.Failed(e.message ?: "wrapper timed out")
            }
        }
    }

    /**
     * Turn an exception into something a person can act on.
     *
     * The wrapper's stderr is the only place the real cause appears (missing
     * node, failed dependency install, rejected CLI arguments, refused key), and
     * it used to be discarded, leaving every failure looking identical.
     */
    private fun describeFailure(e: Exception): String {
        val tail = (e as? WrapperProcessExitedException)?.stderrTail
            .orEmpty()
            .ifEmpty { runCatching { client?.snapshotStderr().orEmpty() }.getOrDefault(emptyList()) }
        val head = e.message ?: e.javaClass.simpleName
        return if (tail.isEmpty()) head else head + "\n" + tail.takeLast(8).joinToString("\n")
    }

    fun abort() {
        val wc = client ?: return
        scope.launch { runCatching { wc.abort() } }
    }

    /**
     * Re-assert the foreground service if a session is live. Called from
     * MainActivity.onStart to recover from OEM battery-manager kills.
     */
    fun reassertDaemonIfActive(context: Context) {
        val active = _session.value.let {
            it is SessionState.Connecting || it is SessionState.Ready || it is SessionState.Streaming
        }
        if (active && DaemonService.ENABLED && DaemonService.canStart(context)) {
            DaemonService.start(context)
        }
    }

    fun endSession() {
        scope.launch {
            runCatching { client?.shutdown() }
            teardown()
            _session.value = SessionState.Idle
        }
    }

    private suspend fun drainEvents(wc: WrapperClient) {
        wc.events.collect { event ->
            when (event.type) {
                WrapperEventType.AGENT_END -> {
                    // A run finished; return to the live session's Ready state.
                    if (_session.value is SessionState.Streaming) {
                        _session.value = lastReady ?: SessionState.Idle
                    }
                }
                WrapperEventType.ERROR -> Log.w(TAG, "wrapper_error: ${event.data}")
            }
            onEvent(event)
        }
    }

    /** Override / observe in the UI layer; default no-op keeps the controller UI-agnostic. */
    var onEvent: (industries.huginn.pifilling.wrapper.WrapperEvent) -> Unit = {}

    /**
     * Stop the wrapper and confirm it actually stopped.
     *
     * The previous version issued `destroy()` and cleared the fields, which
     * treats "the request was sent" as "the process is gone". The wrapper spawns
     * shells, so a surviving child keeps holding the rootfs while the app
     * believes the session is closed — and a later wipe would then find nothing
     * in the registry and delete underneath it.
     *
     * Returns true only when the process is confirmed gone. A false result must
     * keep the environment gated; it must not be reported as a clean teardown.
     */
    private suspend fun teardown(): Boolean {
        eventsJob?.cancel()
        eventsJob = null
        runCatching { client?.close() }

        val proc = process
        process = null
        client = null

        val rootGone = if (proc == null) {
            true
        } else {
            runCatching {
                proc.destroy()
                if (!proc.waitFor(TEARDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    proc.waitFor(TEARDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } else {
                    true
                }
            }.getOrDefault(false)
        }

        DaemonService.stop(appContext)

        if (!rootGone) {
            // The wrapper may still be running. Block the environment rather than
            // pretending the session closed: whatever it spawned is unaccounted
            // for, and the reservation policy has no way to prove otherwise.
            Log.e(TAG, "wrapper did not confirm exit; environment stays gated")
            updateGate(blocked = true, persistenceFailed = false)
            return false
        }

        // The wrapper is confirmed gone, so its registry entry is stale. Leaving
        // it would shut the environment gate permanently: the next session would
        // be refused with "the process registry still has entries", and the only
        // exit would be the manual reconcile button. Releasing it here is what
        // makes a closed session actually release the environment.
        return true
    }

    /**
     * Publish the maintenance block. Written in one place so the flow and the
     * gate cannot disagree about whether spawning is allowed.
     */
    private fun updateGate(blocked: Boolean, persistenceFailed: Boolean) {
        sandbox.setMaintenanceGate(blocked, persistenceFailed)
    }

    private companion object {
        const val TAG = "AgentController"

        /** How long to wait for the wrapper to exit after being asked. */
        const val TEARDOWN_TIMEOUT_SECONDS = 3L
    }
}
