package industries.huginn.pifilling.ui

import android.app.Activity
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import java.util.Locale
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import industries.huginn.pifilling.R
import industries.huginn.pifilling.runtime.AgentController.SessionState
import industries.huginn.pifilling.sandbox.AgentProvider
import industries.huginn.pifilling.sandbox.SandboxState
import industries.huginn.pifilling.sandbox.Toolchain
import industries.huginn.pifilling.sandbox.ToolchainState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Token
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextOverflow
import industries.huginn.pifilling.ui.theme.LocalPiFillingDark
import industries.huginn.pifilling.storage.StorageReport
import industries.huginn.pifilling.storage.StorageUsage
import industries.huginn.pifilling.storage.StorageUsageReader
import industries.huginn.pifilling.ui.theme.PiTheme

/**
 * Resolve a UI string from the *localised* context.
 *
 * Deliberately not `stringResource`: that helper goes through whatever context
 * the running Compose version happens to read. Reading [LocalContext] directly
 * is the one thing guaranteed to pick up the context provided below.
 */
@Composable
@ReadOnlyComposable
private fun str(id: Int): String = LocalContext.current.getString(id)

@Composable
@ReadOnlyComposable
private fun strf(id: Int, vararg args: Any): String =
    LocalContext.current.getString(id, *args)

/** Which sheet is open over the chat. Only one at a time. */
private enum class Sheet {
    NONE, PROVIDER, MODEL, KEY, ENDPOINT, SANDBOX, FOLDER, DIAG, PHONE, TOKEN, ABOUT,
    THEME, TOOLS, RATE, PITCH, CLEAR_HISTORY, STORAGE, MODEL_SERVICE, TOOLCHAINS,
}

/** Rows the settings status card can jump to. */
private enum class SettingsTarget { MODEL, KEY, SANDBOX, GITHUB }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiFillingApp(vm: AppViewModel, onVoiceInput: () -> Unit) {
    val hasKey by vm.hasApiKey.collectAsStateWithLifecycle()
    val provider by vm.provider.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val sandbox by vm.sandboxState.collectAsStateWithLifecycle()
    val session by vm.sessionState.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()
    val keyHint by vm.keyHint.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val voiceRate by vm.voiceRate.collectAsStateWithLifecycle()
    val voicePitch by vm.voicePitch.collectAsStateWithLifecycle()

    val transcript by vm.transcript.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val viewingId by vm.viewingSessionId.collectAsStateWithLifecycle()
    val activeSessionId by vm.activeSessionId.collectAsStateWithLifecycle()
    val continuedFromHistory by vm.continuedFromHistory.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val activeGroupId by vm.activeGroupId.collectAsStateWithLifecycle()

    // Leaving a conversation that is still live means ending it, so the user is
    // asked first and the current one is written to disk before we switch.
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val sessionLive = session is SessionState.Ready || session is SessionState.Streaming ||
        session is SessionState.Connecting
    fun requestLeaveCurrent(action: () -> Unit) {
        if (viewingId == null && sessionLive && transcript.isNotEmpty()) {
            pendingLeave = action
        } else {
            action()
        }
    }

    LaunchedEffect(vm) { vm.loadHistory() }

    var settingsOpen by remember { mutableStateOf(false) }
    var providersOpen by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(Sheet.NONE) }

    // Hoisted out of ChatPane: the chat and the settings screen share one
    // AnimatedContent, so opening settings destroys the chat composition. A
    // list state remembered down there came back at the top of the transcript
    // every time. Up here it survives the switch.
    val chatListState = rememberLazyListState()

    // Hoisted for the same reason as the list state: if this lived inside
    // ChatPane it would be re-created as `true` on every return from settings,
    // and the first emission of the follow effect would yank a reader who had
    // deliberately scrolled up back to the bottom.
    val chatFollowBottom = remember { mutableStateOf(true) }

    // Same reason: a tool row the user unfolded must stay unfolded after a trip
    // through settings, which tears the whole chat composition down.
    val chatExpandedTools = remember { mutableStateMapOf<String, Boolean>() }

    // Ids come from whichever conversation is open, so a map carried across a
    // switch would unfold unrelated rows in the new one.
    LaunchedEffect(viewingId) { chatExpandedTools.clear() }

    val baseContext = LocalContext.current
    val localizedContext = remember(baseContext, language) {
        val tag = language.tag ?: return@remember baseContext
        val config = Configuration(baseContext.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        baseContext.createConfigurationContext(config)
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val notice by vm.notice.collectAsStateWithLifecycle()

    // Every save ends in a confirmation, because the previous build gave none:
    // tapping "save" and seeing nothing happen was indistinguishable from a
    // tap that missed.
    LaunchedEffect(notice) {
        notice?.let { snackbar.showSnackbar(it) }
    }

    // Back must go up a level, not out of the app. Pressing back inside settings
    // used to close the whole app, which reads as a crash.
    BackHandler(enabled = sheet != Sheet.NONE || providersOpen || settingsOpen) {
        when {
            sheet != Sheet.NONE -> sheet = Sheet.NONE
            providersOpen -> providersOpen = false
            else -> settingsOpen = false
        }
    }

    CompositionLocalProvider(LocalContext provides localizedContext) {
        // Everything the ViewModel wants to say is resolved here, under the
        // localized context — resolving it anywhere else (e.g. with the
        // Activity's base context) would ignore the language setting.
    }

    // Leaving a live conversation for the drawer's history or a new chat.
    val leave = pendingLeave
    if (leave != null) {
        AlertDialog(
            onDismissRequest = { pendingLeave = null },
            title = { Text(str(R.string.ui_switch_away_title)) },
            text = { Text(str(R.string.ui_switch_away_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingLeave = null
                    vm.flushHistory()
                    leave()
                }) {
                    Text(str(R.string.ui_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLeave = null }) {
                    Text(str(R.string.ui_cancel))
                }
            },
        )
    }

        val localizer = LocalContext.current
        // Auto-speak reads replies without a UI interaction, so the "code block"
        // wording has to be pushed down rather than passed along.
        LaunchedEffect(vm, localizer) {
            vm.speechCodeBlockHint = localizer.getString(R.string.ui_speech_code_block)
        }
        LaunchedEffect(vm, localizer) {
            vm.messages.collect { msg ->
                val text = localizer.getString(
                    msg.id,
                    *msg.args.map { if (it is ResArg) localizer.getString(it.id) else it }
                        .toTypedArray(),
                )
                if (msg.toTranscript) vm.appendSystemLine(text) else vm.notify(text)
            }
        }
        AuroraBackground {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // Swipe-to-open only from the chat: inside settings the gesture would
            // fight the vertical scroll of the list.
            gesturesEnabled = !settingsOpen && !providersOpen,
            drawerContent = {
                AppDrawer(
                    provider = provider,
                    model = model,
                    sandboxReady = sandbox is SandboxState.Ready,
                    settingsOpen = settingsOpen,
                    history = history,
                    viewingSessionId = viewingId,
                    activeSessionId = activeSessionId,
                    onNewChat = {
                        requestLeaveCurrent {
                            vm.archiveAndStartNew()
                            settingsOpen = false
                            scope.launch { drawerState.close() }
                        }
                    },
                    onOpenHistory = { id ->
                        requestLeaveCurrent { vm.openHistorySession(id) }
                        settingsOpen = false
                        scope.launch { drawerState.close() }
                    },
                    onDeleteHistory = { vm.deleteHistorySession(it) },
                    onOpenChat = {
                        settingsOpen = false
                        scope.launch { drawerState.close() }
                    },
                    onOpenSettings = {
                        settingsOpen = true
                        scope.launch { drawerState.close() }
                    },
                )
            },
        ) {
            Scaffold(
                // Transparent so the aurora backdrop shows through; the glass
                // panes inside supply their own contrast.
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    TopAppBar(
                        title = {
                            if (providersOpen) {
                                Text(
                                    str(R.string.ui_model_providers),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            } else if (settingsOpen) {
                                Text(
                                    str(R.string.ui_settings),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            } else {
                                // Clean header like Today: just the app name.
                                // Model selection lives in the composer pill.
                                Text(
                                    str(R.string.app_name),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        },
                        navigationIcon = {
                            if (providersOpen) {
                                // The provider page is the second level of settings:
                                // it should read as "one card wider", not as a separate
                                // screen with its own way back, so the bar stays clean.
                                Spacer(Modifier.size(24.dp))
                            } else if (settingsOpen) {
                                IconButton(onClick = { settingsOpen = false }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = str(R.string.ui_back),
                                    )
                                }
                            } else {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Filled.Menu, contentDescription = str(R.string.ui_chat))
                                }
                            }
                        },
                        actions = {
                            if (!settingsOpen) {
                                IconButton(onClick = vm::resetChat) {
                                    Icon(Icons.Filled.Add, contentDescription = str(R.string.ui_new_chat))
                                }
                            }
                            // Only on the chat screen: once settings are open
                            // the leading back arrow is the way out, and a second
                            // control that also closed them read as a duplicate.
                            if (!settingsOpen) {
                                IconButton(onClick = { settingsOpen = true }) {
                                    Icon(
                                        Icons.Filled.Settings,
                                        contentDescription = str(R.string.ui_settings),
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                        ),
                    )
                },
            ) { padding ->
                // Full-bleed content, Today-style: the list runs under the
                // floating top bar and the composer, which are overlays. The
                // screens inset themselves; the chat scrolls edge to edge.
                Column(Modifier.fillMaxSize()) {
                    // The two "screens" cross-fade with a small vertical drift instead of
                    // a hard cut, so opening settings reads as navigation, not a reload.
                    AnimatedContent(
                        targetState = if (providersOpen) 2 else if (settingsOpen) 1 else 0,
                        transitionSpec = {
                            fadeIn(tween(220, easing = FastOutSlowInEasing)) togetherWith
                                fadeOut(tween(180, easing = FastOutSlowInEasing))
                        },
                    ) { level ->
                        if (level == 2) {
                            // These two screens are not overlays: they must sit
                            // BELOW the top bar, not under it. Only the chat is
                            // full-bleed by design, so only it ignores padding.
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(top = padding.calculateTopPadding()),
                            ) {
                                ProvidersScreen(
                                    vm = vm,
                                    groups = groups,
                                    activeGroupId = activeGroupId,
                                    onBack = { providersOpen = false },
                                )
                            }
                        } else if (level == 1) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(top = padding.calculateTopPadding()),
                            ) {
                                SettingsScreen(
                                    vm = vm,
                                    provider = provider,
                                    model = model,
                                    modelsCount = models.size,
                                    keyHint = keyHint,
                                    sandbox = sandbox,
                                    language = language,
                                    onOpenSheet = { sheet = it },
                                    onOpenProviders = { providersOpen = true },
                                    onBack = { settingsOpen = false },
                                )
                            }
                        } else {
                            ChatPane(
                                vm = vm,
                                listState = chatListState,
                                followBottom = chatFollowBottom,
                                chatExpandedTools = chatExpandedTools,
                                transcript = transcript,
                                viewingId = viewingId,
                                continuedFromHistory = continuedFromHistory,
                                groups = groups,
                                activeGroupId = activeGroupId,
                                onVoiceInput = onVoiceInput,
                                provider = provider,
                                model = model,
                                session = session,
                                sandbox = sandbox,
                                hasKey = hasKey,
                                onOpenModel = { sheet = Sheet.MODEL },
                                onOpenSettings = { settingsOpen = true },
                                onOpenDiag = { sheet = Sheet.DIAG },
                            )
                        }
                    }
                }
            }

            // ---- sheets ----------------------------------------------------
            val dismiss = { sheet = Sheet.NONE }
            when (sheet) {
                Sheet.PROVIDER -> ProviderSheet(
                    current = provider,
                    onPick = { vm.setProvider(it); dismiss() },
                    onDismiss = dismiss,
                )
                Sheet.MODEL -> ModelSheet(
                    vm = vm,
                    models = models,
                    current = model,
                    presets = presets,
                    onPick = { vm.selectProfile(it); dismiss() },
                    onForget = { vm.forgetPreset(it) },
                    onDismiss = dismiss,
                )
                Sheet.KEY -> KeySheet(
                    provider = provider,
                    keyHint = keyHint,
                    onSave = { vm.saveApiKey(it); dismiss() },
                    onClear = { vm.clearApiKey() },
                    onDismiss = dismiss,
                )
                Sheet.ENDPOINT -> EndpointSheet(vm = vm, onDismiss = dismiss)
                Sheet.SANDBOX -> SandboxSheet(vm = vm, sandbox = sandbox, onProvision = vm::provision, onDismiss = dismiss)
                Sheet.FOLDER -> FolderSheet(vm = vm, onDismiss = dismiss)
                Sheet.DIAG -> DiagnosticSheet(vm = vm, onDismiss = dismiss)
                Sheet.PHONE -> PhoneControlSheet(vm = vm, onDismiss = dismiss)
                Sheet.TOKEN -> TokenSheet(vm = vm, onDismiss = dismiss)
                Sheet.TOOLCHAINS -> ToolchainSheet(vm = vm, onDismiss = dismiss)
                Sheet.MODEL_SERVICE -> ModelServiceSheet(
                    vm = vm,
                    provider = provider,
                    model = model,
                    models = models,
                    presets = presets,
                    hasKey = hasKey,
                    keyHint = keyHint,
                    onPickProvider = { vm.setProvider(it) },
                    onPickModel = { vm.selectProfile(it); dismiss() },
                    onForgetPreset = { vm.forgetPreset(it) },
                    onOpenSheet = { sheet = it },
                    onDismiss = dismiss,
                )
                Sheet.STORAGE -> StorageSheet(onDismiss = dismiss)
                Sheet.ABOUT -> AboutSheet(onDismiss = dismiss)
                Sheet.THEME -> ThemeSheet(vm = vm, onDismiss = dismiss)
                Sheet.CLEAR_HISTORY -> ClearHistorySheet(
                    count = history.size,
                    onClear = { vm.clearHistory(); dismiss() },
                    onDismiss = dismiss,
                )
                Sheet.TOOLS -> ToolsSheet(vm = vm, onDismiss = dismiss)
                Sheet.RATE -> ValueSliderSheet(
                    title = str(R.string.ui_voice_rate),
                    hint = str(R.string.ui_voice_rate_hint),
                    value = voiceRate,
                    format = { String.format(Locale.US, "%.2f\u00d7", it) },
                    onChange = vm::setVoiceRate,
                    onDismiss = dismiss,
                )
                Sheet.PITCH -> ValueSliderSheet(
                    title = str(R.string.ui_voice_pitch),
                    hint = str(R.string.ui_voice_pitch_hint),
                    value = voicePitch,
                    format = { String.format(Locale.US, "%.2f", it) },
                    onChange = vm::setVoicePitch,
                    onDismiss = dismiss,
                )
                Sheet.NONE -> Unit
            }
        }
        }
    }

// ---- drawer ---------------------------------------------------------------

@Composable
private fun AppDrawer(
    provider: AgentProvider,
    model: String,
    sandboxReady: Boolean,
    settingsOpen: Boolean,
    history: List<StoredSession>,
    viewingSessionId: String?,
    /** The conversation that is open right now, live or archived. */
    activeSessionId: String?,
    onNewChat: () -> Unit,
    onOpenHistory: (String) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var deleteCandidate by remember { mutableStateOf<StoredSession?>(null) }
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
        modifier = Modifier.widthIn(max = 300.dp),
        windowInsets = WindowInsets(0),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
        ) {
            Column(
                Modifier
                    // Insets live on the content, so the title clears the status
                    // bar and the last row clears the gesture bar.
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 20.dp,
                        end = 20.dp,
                        top = 20.dp,
                        bottom = 24.dp,
                    ),
            ) {
                Text(
                    str(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(PiTheme.spacing.xs))
                // Model and sandbox state used to be two labelSmall lines of
                // equal weight, so neither read as the more important one. The
                // model is what the user chose; the sandbox state is a fact
                // about the machine. They now differ in weight, not just colour.
                Text(
                    "${provider.label} · $model",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(PiTheme.spacing.lg))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PiTheme.spacing.sm),
                ) {
                    // A dot rather than a coloured word: it reads at a glance
                    // without adding another line of text to scan.
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(
                                if (sandboxReady) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                CircleShape,
                            ),
                    )
                    Text(
                        if (sandboxReady) str(R.string.ui_sandbox_ready) else str(R.string.ui_not_set),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sandboxReady) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f))

                // Actions, grouped and separated from the conversation list.
                //
                // They used to run straight into the history heading with a hairline
                // between, which read as one long list of seven things rather than
                // "things you can do" and "things you have". The group gets its own
                // breathing room and the divider sits outside both.
                Column(verticalArrangement = Arrangement.spacedBy(PiTheme.spacing.xxs)) {
                    DrawerItem(
                        icon = Icons.Filled.Add,
                        label = str(R.string.ui_new_chat),
                        selected = false,
                        onClick = onNewChat,
                    )
                    DrawerItem(
                        icon = Icons.AutoMirrored.Filled.Send,
                        label = str(R.string.ui_chat),
                        selected = !settingsOpen,
                        onClick = onOpenChat,
                    )
                    DrawerItem(
                        icon = Icons.Filled.Settings,
                        label = str(R.string.ui_settings),
                        selected = settingsOpen,
                        onClick = onOpenSettings,
                    )
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f),
                    modifier = Modifier.padding(
                        start = PiTheme.spacing.lg,
                        end = PiTheme.spacing.lg,
                        top = PiTheme.spacing.md,
                        bottom = PiTheme.spacing.md,
                    ),
                )
                // An overline rather than a semibold label: it is a section marker,
                // not a heading, and the tracked capitals say so without competing
                // with the conversation titles below it.
                Text(
                    str(R.string.ui_history).uppercase(),
                    style = PiTheme.typography.overline,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.padding(
                        start = PiTheme.spacing.xxxl,
                        bottom = PiTheme.spacing.sm,
                    ),
                )
                if (history.isEmpty()) {
                    Text(
                        str(R.string.ui_history_empty),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            start = PiTheme.spacing.section,
                            end = PiTheme.spacing.group,
                            bottom = PiTheme.spacing.lg,
                        ),
                    )
                } else {
                    // Plain column inside the scrollable drawer: a nested LazyColumn
                    // with weight() inside verticalScroll is illegal and was what
                    // made the rows pile on top of each other.
                    Column(Modifier.padding(top = 4.dp)) {
                        history.forEach { session ->
                            HistoryItem(
                                session = session,
                                selected = session.id == activeSessionId,
                                onOpen = { onOpenHistory(session.id) },
                                onLongPress = { deleteCandidate = session },
                            )
                        }
                    }
                }
            }
        }
    }

    // Deleting is destructive and one long press away, so it asks.
    deleteCandidate?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(str(R.string.ui_history_delete_title)) },
            text = { Text(str(R.string.ui_history_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteHistory(session.id)
                    deleteCandidate = null
                }) {
                    Text(str(R.string.ui_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text(str(R.string.ui_cancel))
                }
            },
        )
    }
}

/**
 * "3 分钟前" / "3 minutes ago".
 *
 * Resolved from resources rather than DateUtils, because DateUtils follows the
 * device locale and the app has its own language setting.
 */
@Composable
private fun relativeTime(at: Long): String {
    val minutes = ((System.currentTimeMillis() - at) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> str(R.string.ui_time_just_now)
        minutes < 60 -> strf(R.string.ui_time_minutes_ago, minutes)
        minutes < 60 * 24 -> strf(R.string.ui_time_hours_ago, minutes / 60)
        minutes < 60 * 24 * 30 -> strf(R.string.ui_time_days_ago, minutes / (60 * 24))
        else -> strf(R.string.ui_time_months_ago, minutes / (60 * 24 * 30))
    }
}

/** One saved conversation in the drawer: title plus how long ago it was touched. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryItem(
    session: StoredSession,
    selected: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    // Same shape, same highlight, same ripple clipping as `DrawerItem`.
    //
    // The two used different radii, different selected alphas and — because this
    // one lacked `clip` — different ripple shapes, so the list looked like it
    // belonged to a different screen than the actions above it.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PiTheme.spacing.lg, vertical = PiTheme.spacing.xxs)
            .clip(PiTheme.radius.lgShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                else Color.Transparent,
                PiTheme.radius.lgShape,
            )
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(
                horizontal = PiTheme.spacing.xxxl,
                vertical = PiTheme.spacing.lg,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                session.title.ifBlank { str(R.string.ui_chat) },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                relativeTime(session.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DrawerItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // A selected row is marked three ways, not one: a filled background alone
    // was at alpha 0.16 and effectively invisible on the glass pane, which made
    // "which conversation am I in?" a guess. The bar and the weight change are
    // what actually read at a glance.
    val rowColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    } else {
        Color.Transparent
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PiTheme.spacing.lg, vertical = PiTheme.spacing.xxs)
            // Clip before the ripple: `clickable`'s indication is rectangular by
            // default, so a pressed row showed a square grey block over a rounded
            // background — which is what made the settings row look wrong next to
            // the others.
            .clip(PiTheme.radius.lgShape)
            .background(rowColor, PiTheme.radius.lgShape)
            .clickable(onClick = onClick)
            .padding(
                horizontal = PiTheme.spacing.xxxl,
                vertical = PiTheme.spacing.xxl,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiTheme.spacing.xxl),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = contentColor,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---- chat -----------------------------------------------------------------

/** A few pixels of slack so "at the bottom" is not a pixel-exact test. */
private const val BOTTOM_SLOP_PX = 24

private val ChatItem.isUserMessage: Boolean
    get() = this is ChatItem.MessageItem && role == "you"

private val ChatItem.isAssistantMessage: Boolean
    get() = this is ChatItem.MessageItem && role == "assistant"

/** What the long-press sheet shows above its actions. */
private fun ChatItem.sheetSubtitle(): String {
    val raw = when (this) {
        is ChatItem.MessageItem -> text
        is ChatItem.ToolItem -> "\u25b8 $name"
    }
    return raw.take(60) + if (raw.length > 60) "…" else ""
}

/** The row the user long-pressed. Identity is the item's id, not a position. */
private data class LineAction(val item: ChatItem)

@Composable
private fun ChatPane(
    vm: AppViewModel,
    listState: LazyListState,
    followBottom: MutableState<Boolean>,
    chatExpandedTools: MutableMap<String, Boolean>,
    transcript: List<ChatItem>,
    viewingId: String?,
    /** True when this conversation was reopened from history. */
    continuedFromHistory: Boolean,
    groups: List<ModelGroup>,
    activeGroupId: String?,
    onVoiceInput: () -> Unit,
    provider: AgentProvider,
    model: String,
    session: SessionState,
    sandbox: SandboxState,
    hasKey: Boolean,
    onOpenModel: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiag: () -> Unit,
) {
    val presets by vm.presets.collectAsStateWithLifecycle()
    val voiceInputOn by vm.voiceInputEnabled.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()
    val input by vm.draft.collectAsStateWithLifecycle()
    var action by remember { mutableStateOf<LineAction?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val streaming = session is SessionState.Streaming
    val connecting = session is SessionState.Connecting || session is SessionState.Provisioning

    // Strings are resolved once at composition so the menu's click handlers
    // (plain lambdas, not composable context) can use them.
    val copiedLabel = str(R.string.ui_copied)
    val codeBlockHint = str(R.string.ui_speech_code_block)

    // Which tool rows are unfolded. Hoisted by the caller (like the list state)
    // so a round trip through settings does not fold everything back up.
    val expandedTools = chatExpandedTools

    // Opening a link is an explicit tap on a link, never automatic.
    fun openLink(url: String) {
        if (!isHttpUrl(url)) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { vm.notifyRes(R.string.ui_open_link_failed) }
    }

    // ---- follow the bottom ------------------------------------------------
    // Watching transcript.size missed the whole point: a streaming reply is one
    // row that keeps growing, so the count never changes while the text runs off
    // the bottom of the screen. This watches the last row's *content* instead.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset + BOTTOM_SLOP_PX
        }
    }
    // Leaving the bottom (the user scrolled up) stops the auto-follow; coming
    // back resumes it, so returning to the bottom is the way to re-engage.
    LaunchedEffect(atBottom) { followBottom.value = atBottom }

    LaunchedEffect(listState) {
        snapshotFlow {
            val last = transcript.lastOrNull()
            val signature = when (last) {
                is ChatItem.MessageItem -> last.text.length
                is ChatItem.ToolItem -> last.status.ordinal * 100_000 + last.result.length
                null -> 0
            }
            transcript.size to signature
        }.collect {
            if (followBottom.value && transcript.isNotEmpty()) {
                // Int.MAX_VALUE asks for the very end of the content; the list
                // clamps it, which is how a growing last row stays pinned to the
                // bottom instead of scrolling to the top of a tall message.
                listState.scrollToItem(transcript.lastIndex, Int.MAX_VALUE)
            }
        }
    }

    // ---- voice input: system recognizer, so 普通话 just works ------------------
    // No in-app recording: the tap opens the OS speech recognizer, which asks
    // for the microphone permission itself. The launcher lives on MainActivity
    // (Activity-result registration is not allowed from a subcomposition) and
    // the recognized text is appended to the draft in AppViewModel, so it
    // survives this composition being torn down.

    val blocked = !hasKey || sandbox is SandboxState.NotInstalled ||
        sandbox is SandboxState.Downloading || sandbox is SandboxState.Extracting

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            when {
                blocked -> SetupPrompt(
                    hasKey = hasKey,
                    sandbox = sandbox,
                    onOpenSettings = onOpenSettings,
                    onProvision = vm::provision,
                )
                transcript.isEmpty() && !connecting -> WelcomeState(
                    model = model,
                    onPick = vm::send,
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // Top clears the floating bar; bottom clears the composer.
                    // Messages scroll edge to edge beneath both overlays.
                    contentPadding = PaddingValues(
                        start = 12.dp, top = 116.dp, end = 12.dp, bottom = 178.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (continuedFromHistory) {
                        item(key = "continued-banner") { ContinuedBanner() }
                    }
                    items(transcript, key = { it.id }) { item ->
                        MessageBubble(
                            item = item,
                            onLongPress = { action = LineAction(item) },
                            expanded = expandedTools[item.id] == true,
                            onToggleExpanded = {
                                expandedTools[item.id] = expandedTools[item.id] != true
                            },
                            onOpenLink = ::openLink,
                            onCopyCode = { code ->
                                clipboard.setText(AnnotatedString(code))
                                vm.notify(copiedLabel)
                            },
                        )
                    }
                    // While the request is in flight and nothing has come back yet,
                    // the three pulsing dots say "it is thinking", which a blank
                    // pane and a disabled button never did.
                    val lastIsAssistant =
                        (transcript.lastOrNull() as? ChatItem.MessageItem)?.role == "assistant"
                    if (streaming && !lastIsAssistant) {
                        item { ThinkingRow() }
                    }
                }
            }

            // Only offered when there is something to go back to: it appears
            // when the user has scrolled away from the live end of the reply.
            if (transcript.isNotEmpty() && !atBottom) {
                val scope = rememberCoroutineScope()
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.scrollToItem(transcript.lastIndex, Int.MAX_VALUE)
                            followBottom.value = true
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 186.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(
                        Icons.Filled.ArrowDownward,
                        contentDescription = str(R.string.ui_jump_to_bottom),
                    )
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
        if (connecting) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    str(R.string.ui_connecting),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (session is SessionState.Failed) {
            // A one-line "failed" tells the user nothing they can act on. The
            // chip opens the full diagnostic — exit code plus the wrapper's own
            // stderr — because that is the only place the real cause appears.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable { onOpenDiag() },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        strf(R.string.ui_failed, session.message.lineSequence().first()),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "\u203a",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }

        // A stored conversation is a transcript, not a session: the agent has no
        // memory of it, so letting the user type here would be a lie.
        // A reopened conversation is editable. The banner above the composer
        // explains the one thing that does not carry over — the agent's memory —
        // instead of locking the field and offering to start over.
        Composer(
            value = input,
            onValueChange = vm::setDraft,
            enabled = !blocked,
            streaming = streaming,
            modelChip = model,
            providerLabel = provider.label,
            presets = presets,
            groups = groups,
            activeGroupId = activeGroupId,
            onPickGroup = { vm.activateGroup(it) },
            voiceInput = voiceInputOn,
            onVoice = onVoiceInput,
            onModelClick = onOpenModel,
            onPickPreset = { vm.selectProfile(it) },
            onSend = {
                val text = input.trim()
                if (text.isNotEmpty()) {
                    vm.send(text)
                    vm.setDraft("")
                }
            },
            onStop = vm::abort,
            onNewChat = vm::resetChat,
        )
        }
    }

    // Switching models ends the running session, which throws the conversation
    // away. Ask first — it used to just happen.
    val pendingModel by vm.pendingModelSwitch.collectAsStateWithLifecycle()
    pendingModel?.let {
        AlertDialog(
            onDismissRequest = vm::cancelModelSwitch,
            title = { Text(str(R.string.ui_model_switch_title)) },
            text = { Text(str(R.string.ui_model_switch_body)) },
            confirmButton = {
                TextButton(onClick = vm::confirmModelSwitch) {
                    Text(str(R.string.ui_model_switch_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelModelSwitch) { Text(str(R.string.ui_cancel)) }
            },
        )
    }

    // ---- long-press message menu --------------------------------------------
    // What a normal chat app offers: copy one message or the whole conversation,
    // read a reply aloud, and for the last exchange regenerate or edit.
    action?.let { act ->
        // Position is looked up by identity, not stored with the row: a message
        // stays "the last one" until something is actually appended after it.
        val lastUserId = transcript.lastOrNull { it.isUserMessage }?.id
        val lastAssistantId = transcript.lastOrNull { it.isAssistantMessage }?.id
        val item = act.item
        // A message copies its raw Markdown — the source, not the rendering.
        // A tool row has no message text, so it copies what the row is about:
        // the command and what it printed.
        val text = item.messageText ?: (item as ChatItem.ToolItem).let { tool ->
            buildString {
                append(tool.name)
                val command = toolCommand(tool.args)
                if (command.isNotBlank()) append('\n').append(command)
                val output = toolOutput(tool.result)
                if (output.isNotBlank()) append('\n').append(output)
            }
        }
        MessageActionSheet(
            item = item,
            canSpeak = item.isAssistantMessage,
            canRegenerate = item.id == lastUserId || item.id == lastAssistantId,
            canEdit = item.id == lastUserId,
            onCopy = {
                clipboard.setText(AnnotatedString(text))
                vm.notify(copiedLabel)
                action = null
            },
            onCopyAll = {
                clipboard.setText(AnnotatedString(vm.transcriptText()))
                vm.notify(copiedLabel)
                action = null
            },
            onSpeak = {
                vm.speakMessage(markdownToSpeech(text, codeBlockHint))
                action = null
            },
            onRegenerate = {
                vm.regenerate()
                action = null
            },
            onEdit = {
                vm.editLastUser()?.let { vm.setDraft(it) }
                action = null
            },
            onDismiss = { action = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionSheet(
    item: ChatItem,
    canSpeak: Boolean,
    canRegenerate: Boolean,
    canEdit: Boolean,
    onCopy: () -> Unit,
    onCopyAll: () -> Unit,
    onSpeak: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    CenterDialog(onDismiss = onDismiss) {
        Text(
            item.sheetSubtitle(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f))
        MenuRow(Icons.Filled.ContentCopy, str(R.string.ui_copy), onCopy)
        MenuRow(Icons.Filled.CopyAll, str(R.string.ui_copy_all), onCopyAll)
        if (canSpeak) MenuRow(Icons.Filled.PlayArrow, str(R.string.ui_read_aloud), onSpeak)
        if (canRegenerate) MenuRow(Icons.Filled.Refresh, str(R.string.ui_regenerate), onRegenerate)
        if (canEdit) MenuRow(Icons.Filled.Edit, str(R.string.ui_edit_message), onEdit)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SetupPrompt(
    hasKey: Boolean,
    sandbox: SandboxState,
    onOpenSettings: () -> Unit,
    onProvision: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (!hasKey) str(R.string.ui_provider_hint) else str(R.string.ui_sandbox_provision_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            !hasKey -> Button(onClick = onOpenSettings) { Text(str(R.string.ui_settings)) }
            sandbox is SandboxState.NotInstalled ->
                Button(onClick = onProvision) { Text(str(R.string.ui_setup_sandbox)) }
            else -> {
                val frac = (sandbox as? SandboxState.Downloading)?.fraction
                if (frac != null) {
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun WelcomeState(model: String, onPick: (String) -> Unit) {
    // A slow float keeps the empty state feeling alive without stealing attention.
    val float = rememberInfiniteTransition(label = "welcome-float")
    val avatarY by float.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "welcome-y",
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.size(52.dp).offset(y = (-6 * avatarY).dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "\u03c0",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        Text(
            str(R.string.ui_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            // Explicit, not inherited. Without it the text takes
            // `LocalContentColor`, which is not the surface colour here — in dark
            // mode it resolved to near-black on a near-black backdrop and the
            // whole empty state disappeared.
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            str(R.string.ui_welcome_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            model,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        // Suggestions as a 2-column grid rather than three full-width bars: it
        // reads as a launcher instead of a form, and the empty state is the
        // first thing anyone sees.
        val quick = listOf(
            R.string.ui_quick_1 to Icons.Filled.Folder,
            R.string.ui_quick_2 to Icons.Filled.Storage,
            R.string.ui_quick_3 to Icons.Filled.Memory,
            R.string.ui_quick_4 to Icons.Filled.Refresh,
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            quick.chunked(2).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowItems.forEach { (id, icon) ->
                        val label = str(id)
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(PiTheme.radius.lgShape)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    shape = PiTheme.radius.lgShape,
                                )
                                .clickable { onPick(label) }
                                .padding(
                                    horizontal = PiTheme.spacing.xl,
                                    vertical = PiTheme.spacing.xl,
                                ),
                            verticalArrangement = Arrangement.spacedBy(PiTheme.spacing.md),
                        ) {
                            // No background behind the icon. It was a tinted
                            // rounded square inside a rounded glass pane, which
                            // read as a frame drawn within a frame; the icon's
                            // own colour already carries the emphasis.
                            Box(
                                Modifier.size(30.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingRow() {
    val transition = rememberInfiniteTransition(label = "thinking")
    Row(
        Modifier.fillMaxWidth().padding(start = 42.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 11.dp, vertical = 8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) { i ->
                    // Staggered start delays make three dots read as a pulse.
                    val alpha by transition.animateFloat(
                        initialValue = 0.25f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(550, delayMillis = i * 180),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "dot$i",
                    )
                    Box(
                        Modifier
                            .size(5.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha), CircleShape),
                    )
                }
            }
        }
        Text(
            str(R.string.ui_thinking),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AgentAvatar() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(26.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                "\u03c0",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * How wide a message bubble may grow.
 *
 * Not a design token: it is a typographic measure, chosen so a line of prose
 * stays around 45 characters on this screen width. The tokens describe spacing
 * and shape; this describes reading.
 */
private val BubbleMaxWidth = 310.dp

/**
 * How far a tool row is indented from the page edge.
 *
 * Aligned to the text column of an assistant reply, so tool chatter sits under
 * the prose it belongs to rather than at the message edge.
 */
private val ToolRowIndent = 36.dp

/** The composer grows with its content until it would crowd the transcript. */
private val ComposerMinHeight = 42.dp
private val ComposerMaxHeight = 140.dp

@Composable
private fun MessageBubble(
    item: ChatItem,
    onLongPress: () -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenLink: (String) -> Unit,
    onCopyCode: (String) -> Unit,
) {
    // New lines ease in; the streaming last line keeps this identity as it grows,
    // so it animates once and then just extends in place.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    AnimatedVisibility(
        visible = entered,
        enter = fadeIn(tween(200, easing = FastOutSlowInEasing)) +
            slideInVertically(tween(200, easing = FastOutSlowInEasing)) { it / 4 },
    ) {
        MessageBody(item, onLongPress, expanded, onToggleExpanded, onOpenLink, onCopyCode)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBody(
    item: ChatItem,
    onLongPress: () -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenLink: (String) -> Unit,
    onCopyCode: (String) -> Unit,
) {
    if (item is ChatItem.ToolItem) {
        ToolCallRow(
            item = item,
            expanded = expanded,
            onToggle = onToggleExpanded,
            onLongPress = onLongPress,
        )
        return
    }
    val message = item as ChatItem.MessageItem
    when (message.role) {
        // The user's own words, right-aligned and tinted with clear container.
        //
        // Uses primary surface with border for unmistakable distinction from assistant messages.
        "you" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                modifier = Modifier
                    .widthIn(max = BubbleMaxWidth)
                    .combinedClickable(onClick = {}, onLongClick = onLongPress),
                shape = RoundedCornerShape(
                    topStart = 20.dp, topEnd = 20.dp,
                    bottomEnd = 16.dp, bottomStart = 20.dp,
                ),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 2.dp,
            ) {
                Text(
                    message.text,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        // Assistant messages: clear surface card with subtle border, left-aligned.
        "assistant" -> Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(message.id) {
                    detectTapGestures(onLongPress = { onLongPress() })
                },
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = BubbleMaxWidth)
                    .combinedClickable(onClick = {}, onLongClick = onLongPress),
                shape = RoundedCornerShape(
                    topStart = 20.dp, topEnd = 20.dp,
                    bottomEnd = 20.dp, bottomStart = 16.dp,
                ),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 1.dp,
            ) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    MarkdownMessage(
                        markdown = message.text,
                        onLongPress = onLongPress,
                        onOpenLink = onOpenLink,
                        onCopyCode = onCopyCode,
                    )
                }
            }
        }// Tool chatter and status: a narrow inline chip, so it reads as an aside
        // and never competes with the reply for attention. Solid surface, no glass.
        else -> Row(Modifier.fillMaxWidth().padding(start = ToolRowIndent)) {
            Surface(
                modifier = Modifier
                    .combinedClickable(onClick = {}, onLongClick = onLongPress)
                    .padding(horizontal = PiTheme.spacing.lg, vertical = PiTheme.spacing.xs),
                shape = PiTheme.radius.mdShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                tonalElevation = 1.dp,
            ) {
                Text(
                    message.text,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A tool call, folded to one line until the user asks for the details.
 *
 * Collapsed it is a status and a name — the transcript stays readable while the
 * agent works. Expanded it shows the command and the output, because that is
 * what "why did it fail?" actually needs.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolCallRow(
    item: ChatItem.ToolItem,
    expanded: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    val command = remember(item.args) { toolCommand(item.args) }
    val output = remember(item.result) { toolOutput(item.result) }
    val (visibleOutput, truncated) = remember(output) { truncateLines(output, TOOL_OUTPUT_MAX_LINES) }

    val statusColor = when (item.status) {
        ToolStatus.RUNNING -> MaterialTheme.colorScheme.onSurfaceVariant
        ToolStatus.SUCCESS -> MaterialTheme.colorScheme.secondary
        ToolStatus.FAILED -> MaterialTheme.colorScheme.error
        // Neither running nor failed: it simply never finished, so it gets the
        // muted colour of a fact rather than the red of an error.
        ToolStatus.INTERRUPTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(Modifier.fillMaxWidth().padding(start = ToolRowIndent)) {
        Row(
            Modifier
                .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
                .padding(
                    horizontal = PiTheme.spacing.lg,
                    vertical = PiTheme.spacing.sm,
                )
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = PiTheme.radius.mdShape,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PiTheme.spacing.sm),
        ) {
            when (item.status) {
                ToolStatus.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    strokeWidth = 1.5.dp,
                    color = statusColor,
                )
                ToolStatus.SUCCESS -> Text(
                    "\u2713",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                )
                ToolStatus.FAILED -> Text(
                    "\u2715",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                )
                ToolStatus.INTERRUPTED -> Text(
                    "\u25cb",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                )
            }
            Text(
                if (item.status == ToolStatus.INTERRUPTED) {
                    "${item.name} \u00b7 ${str(R.string.ui_tool_interrupted)}"
                } else {
                    item.name
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (expanded) "\u25be" else "\u25b8",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }

        if (expanded) {
            Column(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(10.dp),
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (command.isNotBlank()) {
                    ToolSectionLabel(str(R.string.ui_tool_command))
                    ToolPreformatted(command)
                }
                if (visibleOutput.isNotBlank()) {
                    ToolSectionLabel(str(R.string.ui_tool_output))
                    ToolPreformatted(visibleOutput)
                    if (truncated) {
                        Text(
                            strf(R.string.ui_truncated, TOOL_OUTPUT_MAX_LINES),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (command.isBlank() && visibleOutput.isBlank()) {
                    Text(
                        str(R.string.ui_tool_empty),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
    )
}

/** Tool payloads scroll sideways: wrapped commands lose their meaning. */
@Composable
private fun ToolPreformatted(text: String) {
    Text(
        text,
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
        lineHeight = 17.sp,
        softWrap = false,
    )
}

/**
 * Shown at the top of a conversation reopened from history.
 *
 * The transcript is real, the agent's memory is not: the wrapper process that
 * held it exited when the session ended, and its protocol accepts no prior
 * messages. Saying that plainly is better than either pretending the context
 * survived or refusing to let the user continue at all.
 */
@Composable
private fun ContinuedBanner() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Filled.History,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            str(R.string.ui_history_continued_banner),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/** How much of a tool's output an expanded row shows before saying it stopped. */
private const val TOOL_OUTPUT_MAX_LINES = 400

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    streaming: Boolean,
    modelChip: String,
    providerLabel: String,
    /** Saved configurations for the active provider, most recent first. */
    presets: List<ModelProfile>,
    /** Every saved group, all providers — the primary switcher. */
    groups: List<ModelGroup>,
    activeGroupId: String?,
    onPickGroup: (ModelGroup) -> Unit,
    /** Whether the microphone button is shown; off = the user hid voice input. */
    voiceInput: Boolean,
    /** Open the system speech recognizer; the recognized text is appended. */
    onVoice: () -> Unit,
    onModelClick: () -> Unit,
    /** Start a fresh conversation from the composer. */
    onNewChat: () -> Unit,
    /** Switch to a saved configuration, endpoint included. */
    onPickPreset: (ModelProfile) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    var switcherOpen by remember { mutableStateOf(false) }
    // 短名：模型 id 太长时只显示最后一段（Today/同类做法），完整名进弹窗
    fun shortModel(m: String): String {
        if (m.isBlank()) return ""
        val cleaned = m.substringAfterLast('/')
        return if (cleaned.length > 22) cleaned.take(20) + "…" else cleaned
    }
    // 当前配置组决定徽标；没有组时按供应商名回退
    val activeGroup = groups.firstOrNull { it.id == activeGroupId }
    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp)
                // Today 风格：纯白大圆角卡片，柔和小阴影
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(26.dp))
                .clip(RoundedCornerShape(26.dp))
                .background(Color.White),
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 上：输入区（Eta 布局——先写字，控制键在下面一行）
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ComposerMinHeight, max = ComposerMaxHeight),
                    decorationBox = { inner ->
                        Box(
                            Modifier.padding(
                                start = 22.dp,
                                end = 22.dp,
                                top = 16.dp,
                                bottom = 6.dp,
                            ),
                        ) {
                            if (value.isEmpty()) {
                                Text(
                                    str(R.string.ui_input_placeholder),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                        .copy(alpha = 0.65f),
                                )
                            }
                            inner()
                        }
                    },
                )
                // 下：动作排（Eta 布局）——[+] [供应商徽标+模型▾] … [麦克] [发送]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 10.dp, top = 2.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onNewChat,
                        enabled = enabled,
                        modifier = Modifier.size(42.dp),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = str(R.string.ui_new_chat),
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    // 模型胶囊：徽标 + 短名，点开切换面板
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(onClick = { switcherOpen = true })
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        ProviderBadge(
                            provider = activeGroup?.provider
                                ?: AgentProvider.entries.firstOrNull { it.label == providerLabel }
                                ?: AgentProvider.DEFAULT,
                            size = 22.dp,
                        )
                        Text(
                            shortModel(modelChip),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            "\u25be",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (streaming) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(10.dp))
                    } else if (voiceInput) {
                        IconButton(
                            onClick = onVoice,
                            enabled = enabled,
                            modifier = Modifier.size(42.dp),
                        ) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = str(R.string.ui_voice_input),
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Spacer(Modifier.size(6.dp))
                    }
                    FilledIconButton(
                        onClick = if (streaming) onStop else onSend,
                        enabled = enabled && (streaming || value.isNotBlank()),
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (enabled && (streaming || value.isNotBlank()))
                                MaterialTheme.colorScheme.primary else Color(0xFFE3E6EF),
                            contentColor = if (enabled && (streaming || value.isNotBlank()))
                                Color.White else Color(0xFF9AA1B2),
                        ),
                    ) {
                        Icon(
                            if (streaming) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
                            contentDescription = str(
                                if (streaming) R.string.ui_stop else R.string.ui_send,
                            ),
                        )
                    }
                }
            }
        }
    }
    // Eta 式切换面板：所有供应商的配置组一屏列出，一键切换
    if (switcherOpen) {
        ModelPickerPanel(
            groups = groups,
            activeGroupId = activeGroupId,
            onPick = {
                onPickGroup(it)
                switcherOpen = false
            },
            onManage = onModelClick,
            onDismiss = { switcherOpen = false },
        )
    }
}

/**
 * Eta/Today 式模型切换面板：所有配置组整屏列出——徽标、名称、副标题，
 * 点一下整组切换（服务+模型+端点+密钥一起换），当前组打勾高亮。
 */
@Composable
private fun ModelPickerPanel(
    groups: List<ModelGroup>,
    activeGroupId: String?,
    onPick: (ModelGroup) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    CenterDialog(onDismiss = onDismiss) {
        SheetHeader(str(R.string.ui_choose_model))
        if (groups.isEmpty()) {
            Text(
                str(R.string.ui_not_set),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
            )
        } else {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groups.forEach { g ->
                    val active = g.id == activeGroupId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (active) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                            )
                            .clickable(enabled = g.enabled) { onPick(g) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ProviderBadge(
                            provider = g.provider,
                            size = 38.dp,
                            shape = RoundedCornerShape(12.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                g.displayName(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (g.enabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                            Text(
                                g.subtitle(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (active) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onManage) {
                Text(str(R.string.ui_model_providers))
            }
        }
    }
}

/** 供应商徽标：品牌色圆角块 + 首字，一眼认出当前模型背后的服务。 */
@Composable
private fun ProviderBadge(
    provider: AgentProvider,
    size: Dp,
    shape: Shape = CircleShape,
) {
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(providerAccent(provider.id)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            provider.label.first().uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

private fun providerAccent(id: String): Color = when (id) {
    "deepseek" -> Color(0xFF4D6BFE)
    "moonshot" -> Color(0xFF20242C)
    "zai" -> Color(0xFF3E7BFA)
    "minimax" -> Color(0xFFE85C41)
    "anthropic" -> Color(0xFFD97757)
    "openrouter" -> Color(0xFF6467F2)
    else -> Color(0xFF5B6BD6) // custom 或未知：回退品牌靛蓝
}

/**
 * A centered floating panel — the app's replacement for bottom sheets.
 *
 * Bottom sheets force a long reach to the bottom edge and drag a thumb across
 * the content on the way out; a centered panel opens where the eye already is,
 * scales in from 92%, and dismisses with a tap outside or the back button.
 * Same content, same header, one material.
 */
@Composable
private fun CenterDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // A full-screen sheet, not a floating card: the previous centred panel was
    // taller than the space between the status bar and the keyboard, so its top
    // and bottom rows were clipped. This occupies the whole window, insets its
    // own content, and scrolls — nothing can hide behind the system bars.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            // Close row
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = str(R.string.ui_back),
                    )
                }
                Spacer(Modifier.weight(1f))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) { content() }
        }
    }
}

// ---- settings -------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    vm: AppViewModel,
    provider: AgentProvider,
    model: String,
    modelsCount: Int,
    keyHint: String?,
    sandbox: SandboxState,
    language: AppLanguage,
    onOpenSheet: (Sheet) -> Unit,
    onOpenProviders: () -> Unit,
    onBack: () -> Unit,
) {
    var langOpen by remember { mutableStateOf(false) }
    val presets by vm.presets.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val activeGroupId by vm.activeGroupId.collectAsStateWithLifecycle()
    val hasKey by vm.hasApiKey.collectAsStateWithLifecycle()
    val autoInstall by vm.autoInstall.collectAsStateWithLifecycle()
    val voiceOn by vm.voiceEnabled.collectAsStateWithLifecycle()
    val voiceInputOn by vm.voiceInputEnabled.collectAsStateWithLifecycle()
    val voiceAuto by vm.autoSpeak.collectAsStateWithLifecycle()
    val voiceRate by vm.voiceRate.collectAsStateWithLifecycle()
    val voicePitch by vm.voicePitch.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val phoneCaps by vm.phoneCaps.collectAsStateWithLifecycle()
    val hasToken by vm.hasGitHubToken.collectAsStateWithLifecycle()
    val repoPath by vm.repoPath.collectAsStateWithLifecycle()
    val diagnostic by vm.diagnostic.collectAsStateWithLifecycle()
    val customBaseUrl by vm.customBaseUrl.collectAsStateWithLifecycle()
    val sandboxReady = sandbox is SandboxState.Ready
    val phoneOn = phoneCaps.values.count { it }
    val phoneTotal = phoneCaps.size
    val toolCount = remember { vm.provisionedTools.split(" · ").count { it.isNotBlank() } }
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    // Measured off the main thread: walking a 1 GB rootfs is not a frame's work.
    // Null until the first result arrives, so the row can say "measuring" rather
    // than showing a zero it will have to take back.
    var storage by remember { mutableStateOf<StorageReport?>(null) }
    LaunchedEffect(Unit) {
        storage = withContext(Dispatchers.IO) { StorageUsageReader.read(context.filesDir) }
    }

    // The status card doubles as a table of contents. Rows record where they
    // landed in the window; a tap turns that into a scroll offset. The offset has
    // to be measured fresh, because a row's position in the window moves as the
    // list scrolls.
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val rowY = remember { mutableStateMapOf<SettingsTarget, Int>() }
    var columnY by remember { mutableIntStateOf(0) }

    fun jumpTo(target: SettingsTarget) {
        val raw = rowY[target] ?: return
        scope.launch { scroll.animateScrollTo((raw - columnY + scroll.value).coerceAtLeast(0)) }
    }

    // No statusBarsPadding here: the Scaffold's top-bar padding (applied by the
    // caller) already places this screen below the status bar and the app bar.
    // Adding it again would double the top inset.
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .onGloballyPositioned { columnY = it.positionInRoot().y.toInt() }
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Hero: the four things a user checks first, each a tap away from the row
        // that sets it. Solid card matching Today settings style.
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "\u03c0",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            provider.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            model.ifBlank { str(R.string.ui_not_set) },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(str(R.string.ui_choose_model), hasKey || model.isNotBlank()) {
                        jumpTo(SettingsTarget.MODEL)
                    }
                    StatusPill(str(R.string.ui_api_key), hasKey) { jumpTo(SettingsTarget.KEY) }
                    StatusPill(str(R.string.ui_linux_sandbox), sandboxReady) {
                        jumpTo(SettingsTarget.SANDBOX)
                    }
                    StatusPill(str(R.string.ui_github_token), hasToken) { jumpTo(SettingsTarget.GITHUB) }
                }
            }
        }

        // Model / provider / endpoint / key — one row, one decision.
        Section(
            index = 0,
            title = str(R.string.ui_model_service),
            icon = Icons.Filled.Hub,
        ) {
            MeasuredRow({ rowY[SettingsTarget.MODEL] = it }) {
                SettingsRow(
                    icon = Icons.Filled.Hub,
                    title = str(R.string.ui_model_providers),
                    hint = str(R.string.ui_model_providers_hint),
                    value = strf(R.string.ui_providers_count, groups.size),
                    monospace = false,
                    ok = hasKey,
                    onClick = onOpenProviders,
                )
            }
        }

        // GitHub token + working directory.
        Section(
            index = 1,
            title = str(R.string.ui_group_github),
            icon = Icons.Filled.Token,
        ) {
            MeasuredRow({ rowY[SettingsTarget.GITHUB] = it }) {
                SettingsRow(
                    icon = Icons.Filled.Token,
                    title = str(R.string.ui_github_token),
                    hint = str(R.string.ui_github_token_note),
                    value = if (hasToken) str(R.string.ui_saved) else str(R.string.ui_not_set),
                    ok = hasToken,
                    onClick = { onOpenSheet(Sheet.TOKEN) },
                )
            }
            RowDivider()
            SettingsRow(
                icon = Icons.Filled.Folder,
                title = str(R.string.ui_working_dir),
                hint = str(R.string.ui_repo_path_hint),
                value = repoPath,
                monospace = true,
                onClick = { onOpenSheet(Sheet.FOLDER) },
            )
        }

        // Runtime: sandbox, toolchains, diagnostics, phone control, clear history.
        Section(
            index = 2,
            title = str(R.string.ui_group_runtime),
            icon = Icons.Filled.Inventory2,
        ) {
            MeasuredRow({ rowY[SettingsTarget.SANDBOX] = it }) {
                SettingsRow(
                    icon = Icons.Filled.Inventory2,
                    title = str(R.string.ui_linux_sandbox),
                    hint = str(R.string.ui_sandbox_row_hint),
                    value = sandboxHeadline(sandbox),
                    ok = sandboxReady,
                    onClick = { onOpenSheet(Sheet.SANDBOX) },
                )
            }
            RowDivider()
            SettingsSwitchRow(
                icon = Icons.Filled.Download,
                title = str(R.string.ui_auto_install),
                hint = str(R.string.ui_auto_install_hint),
                checked = autoInstall,
                onChange = { vm.setAutoInstall(it) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Filled.Handyman,
                title = str(R.string.ui_toolchains),
                hint = str(R.string.ui_toolchains_hint),
                value = toolCount.toString(),
                onClick = { onOpenSheet(Sheet.TOOLCHAINS) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Filled.BugReport,
                title = str(R.string.ui_diagnostics),
                hint = str(R.string.ui_diagnostics_hint),
                value = diagnostic?.lineSequence()?.firstOrNull().orEmpty(),
                onClick = { onOpenSheet(Sheet.DIAG) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Filled.PhoneAndroid,
                title = str(R.string.ui_phone_control),
                hint = str(R.string.ui_phone_control_hint),
                value = str(if (phoneOn > 0) R.string.ui_on else R.string.ui_off),
                ok = phoneOn > 0,
                badge = strf(R.string.ui_phone_x_on, phoneOn, phoneTotal),
                onClick = { onOpenSheet(Sheet.PHONE) },
            )
            RowDivider()
            // Last row of the group on purpose: destructive, and one tap away
            // from the switches above it.
            SettingsRow(
                icon = Icons.Filled.DeleteForever,
                title = str(R.string.ui_clear_history),
                hint = str(R.string.ui_clear_history_hint),
                value = "",
                danger = true,
                onClick = { onOpenSheet(Sheet.CLEAR_HISTORY) },
            )
        }

        // Appearance: language + theme.
        Section(
            index = 3,
            title = str(R.string.ui_group_appearance),
            icon = Icons.Filled.Palette,
        ) {
            SettingsRow(
                icon = Icons.Filled.Language,
                title = str(R.string.ui_language),
                hint = str(R.string.ui_language_hint),
                value = language.displayName.ifBlank { str(R.string.ui_language_system) },
                onClick = { langOpen = true },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Filled.Palette,
                title = str(R.string.ui_theme),
                hint = str(R.string.ui_theme_hint),
                value = themeLabel(themeMode),
                onClick = { onOpenSheet(Sheet.THEME) },
            )
        }

        // Voice: speaking + input + rate / pitch.
        Section(
            index = 4,
            title = str(R.string.ui_group_voice),
            icon = Icons.Filled.RecordVoiceOver,
        ) {
            SettingsSwitchRow(
                icon = Icons.Filled.RecordVoiceOver,
                title = str(R.string.ui_voice),
                hint = str(R.string.ui_voice_hint),
                checked = voiceOn,
                onChange = vm::setVoiceEnabled,
            )
            RowDivider()
            SettingsSwitchRow(
                icon = Icons.Filled.Mic,
                title = str(R.string.ui_voice_input),
                hint = str(R.string.ui_voice_input_hint),
                checked = voiceInputOn,
                onChange = vm::setVoiceInputEnabled,
            )
            RowDivider()
            // Everything below is about speaking replies, so it is meaningless
            // while speech itself is off: greyed rather than hidden, so the
            // controls do not appear from nowhere when the switch is flipped.
            SettingsSwitchRow(
                icon = Icons.Filled.VolumeUp,
                title = str(R.string.ui_voice_auto),
                hint = str(R.string.ui_voice_auto_hint),
                checked = voiceAuto,
                enabled = voiceOn,
                onChange = vm::setAutoSpeak,
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Filled.Speed,
                title = str(R.string.ui_voice_rate),
                hint = str(R.string.ui_voice_rate_hint),
                value = String.format(Locale.US, "%.2f\u00d7", voiceRate),
                enabled = voiceOn,
                onClick = { onOpenSheet(Sheet.RATE) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Filled.GraphicEq,
                title = str(R.string.ui_voice_pitch),
                hint = str(R.string.ui_voice_pitch_hint),
                value = String.format(Locale.US, "%.2f", voicePitch),
                enabled = voiceOn,
                onClick = { onOpenSheet(Sheet.PITCH) },
            )
        }

        // About: storage usage + version.
        Section(
            index = 5,
            title = str(R.string.ui_group_about),
            icon = Icons.Filled.Info,
        ) {
            // Storage before version: it is the row a user opens this group for
            // when something is taking up space, and version is a fact they read
            // once.
            SettingsRow(
                icon = Icons.Filled.Storage,
                title = str(R.string.ui_storage_usage),
                hint = when (val s = storage) {
                    null -> str(R.string.ui_storage_measuring)
                    is StorageReport.Failed -> strf(R.string.ui_storage_failed, s.reason)
                    is StorageReport.Partial -> strf(
                        R.string.ui_storage_partial,
                        s.unreadable.joinToString("、"),
                    )
                    is StorageReport.Measured -> str(R.string.ui_storage_hint)
                },
                value = when (val s = storage) {
                    null -> "…"
                    is StorageReport.Failed -> str(R.string.ui_not_set)
                    is StorageReport.Measured -> StorageUsageReader.format(s.usage.totalBytes)
                    is StorageReport.Partial -> StorageUsageReader.format(s.usage.totalBytes) + "+"
                },
                // Unknown is not zero: a failed measurement shows no green dot.
                ok = (storage as? StorageReport.Measured) != null,
                onClick = { onOpenSheet(Sheet.STORAGE) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Filled.Info,
                title = str(R.string.ui_about),
                hint = str(R.string.ui_about_body),
                value = version,
                onClick = { onOpenSheet(Sheet.ABOUT) },
            )
        }
    }

    if (langOpen) {
        CenterDialog(onDismiss = { langOpen = false }) {
            SheetHeader(str(R.string.ui_language))
            AppLanguage.entries.forEach { option ->
                SheetRow(
                    label = option.displayName.ifBlank { str(R.string.ui_language_system) },
                    selected = option == language,
                    onClick = { vm.setLanguage(option); langOpen = false },
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

// ---- model providers (full page, Eta-style) --------------------------------

/**
 * The provider management page: add entries from the catalog, see every saved
 * configuration as a card, tap to activate, long-press to edit.
 *
 * Replaces the old bottom-sheet picker: a page can breathe, show the endpoint
 * under the name, and carry a radio column like the rest of the system.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProvidersScreen(
    vm: AppViewModel,
    groups: List<ModelGroup>,
    activeGroupId: String?,
    onBack: () -> Unit,
) {
    var editTarget by remember { mutableStateOf<ModelGroup?>(null) }
    var creating by remember { mutableStateOf(false) }
    var creatingAnthropic by remember { mutableStateOf(false) }
    var catalogOpen by remember { mutableStateOf(false) }

    if (creating || creatingAnthropic || editTarget != null) {
        GroupEditSheet(
            vm = vm,
            existing = editTarget,
            anthropicPreset = creatingAnthropic,
            onDone = {
                creating = false
                creatingAnthropic = false
                editTarget = null
            },
            onDismiss = {
                creating = false
                creatingAnthropic = false
                editTarget = null
            },
        )
        return
    }

    // No statusBarsPadding here: the Scaffold's top-bar padding (applied by the
    // caller) already places this screen below the status bar and the app bar.
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // ---- add section ----
        Text(
            str(R.string.ui_add_provider).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            modifier = Modifier.padding(start = 10.dp),
            letterSpacing = 0.6.sp,
        )
        ProviderCard {
            ProviderRow(
                icon = Icons.Filled.Settings,
                title = str(R.string.ui_add_from_directory),
                hint = str(R.string.ui_add_from_directory_hint),
                onClick = { catalogOpen = true },
            )
            ProviderDivider()
            ProviderRow(
                icon = Icons.Filled.Language,
                title = str(R.string.ui_add_openai),
                hint = str(R.string.ui_add_openai_hint),
                onClick = { creating = true },
            )
            ProviderDivider()
            ProviderRow(
                icon = Icons.Filled.Storage,
                title = str(R.string.ui_add_anthropic),
                hint = str(R.string.ui_add_anthropic_hint),
                onClick = { creatingAnthropic = true },
            )
        }

        // ---- configured section ----
        Text(
            strf(R.string.ui_providers_count, groups.size),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp),
        )
        if (groups.isEmpty()) {
            ProviderCard {
                Text(
                    str(R.string.ui_model_groups_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(18.dp),
                )
            }
        } else {
            ProviderCard {
                groups.forEachIndexed { i, group ->
                    val isActive = group.id == activeGroupId
                    val usable = group.enabled
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { if (usable) vm.activateGroup(group) },
                                onLongClick = { editTarget = group },
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        ProviderBadge(group)
                        Column(Modifier.weight(1f)) {
                            Text(
                                group.displayName(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = when {
                                    !usable -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    isActive -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                group.baseUrl.ifBlank { group.model },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                buildString {
                                    append(
                                        if (group.apiShape == CustomProviderSettings.ANTHROPIC_MESSAGES) {
                                            "Anthropic Messages"
                                        } else {
                                            "OpenAI-compatible"
                                        },
                                    )
                                    append(" · ")
                                    append(strf(R.string.ui_models_count_short, 1))
                                    if (!usable) {
                                        append(" · ")
                                        append(str(R.string.ui_provider_disabled))
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = if (usable) 1f else 0.55f,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .then(
                                    if (isActive) Modifier.border(
                                        2.dp, MaterialTheme.colorScheme.primary, CircleShape,
                                    ) else Modifier.border(
                                        2.dp,
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        CircleShape,
                                    ),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isActive) {
                                Box(
                                    Modifier
                                        .size(11.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                                )
                            }
                        }
                    }
                    if (i != groups.lastIndex) ProviderDivider()
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }

    if (catalogOpen) {
        ProviderCatalogSheet(
            onPick = { _ ->
                catalogOpen = false
                creating = true
            },
            onDismiss = { catalogOpen = false },
        )
    }
}

/** White (or dark-elevated) rounded container, the Eta settings card. */
@Composable
private fun ProviderCard(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                color = Color.White,
                shape = RoundedCornerShape(20.dp),
            )
            .border(
                0.5.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                RoundedCornerShape(20.dp),
            ),
    ) { Column { content() } }
}

@Composable
private fun ProviderDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f),
        modifier = Modifier.padding(start = 58.dp),
    )
}

/** One add-entry row: icon, title, hint, chevron. */
@Composable
private fun ProviderRow(
    icon: ImageVector,
    title: String,
    hint: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f),
                    RoundedCornerShape(11.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "\u203a",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

/**
 * A colored monogram badge: the first letter of the group name on a tinted
 * circle, so custom endpoints are recognizable at a glance like built-in
 * provider logos are.
 */
@Composable
private fun ProviderBadge(group: ModelGroup) {
    val accent = when (group.providerId) {
        "deepseek" -> Color(0xFF4D6BFE)
        "moonshot" -> Color(0xFF0E0E0E)
        "zai" -> Color(0xFF3B82F6)
        "minimax" -> Color(0xFFF59E0B)
        "anthropic" -> Color(0xFFD97757)
        "openrouter" -> Color(0xFF6467F2)
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        Modifier
            .size(34.dp)
            .background(accent.copy(alpha = 0.10f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (group.name.ifBlank { group.provider.label }).trim().take(1).uppercase(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
    }
}

/** The built-in catalog, laid out like the add rows above it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderCatalogSheet(
    onPick: (AgentProvider) -> Unit,
    onDismiss: () -> Unit,
) {
    CenterDialog(onDismiss = onDismiss) {
        SheetHeader(str(R.string.ui_add_from_directory))
        AgentProvider.pickerOrder.forEachIndexed { i, candidate ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(candidate) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f),
                            RoundedCornerShape(11.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        candidate.label.take(1),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        candidate.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        candidate.defaultModel.ifBlank { str(R.string.ui_custom_model_hint) },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (i != AgentProvider.pickerOrder.lastIndex) ProviderDivider()
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Create or edit one provider configuration: name, service, model, endpoint,
 * key, wire protocol, enabled. One screen, saved together.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupEditSheet(
    vm: AppViewModel,
    existing: ModelGroup?,
    /** Preselect the Anthropic wire protocol (the "add Anthropic" entry). */
    anthropicPreset: Boolean,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var providerId by remember {
        mutableStateOf(existing?.providerId ?: AgentProvider.DEFAULT.id)
    }
    var model by remember { mutableStateOf(existing?.model.orEmpty()) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var key by remember { mutableStateOf("") }
    var reasoning by remember { mutableStateOf(existing?.reasoning ?: false) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var apiShape by remember {
        mutableStateOf(
            existing?.apiShape
                ?: if (anthropicPreset) CustomProviderSettings.ANTHROPIC_MESSAGES
                else CustomProviderSettings.OPENAI_COMPLETIONS,
        )
    }
    var keyVisible by remember { mutableStateOf(false) }

    val provider = AgentProvider.entries.firstOrNull { it.id == providerId }
        ?: AgentProvider.DEFAULT
    val models by vm.models.collectAsStateWithLifecycle()

    CenterDialog(onDismiss = onDismiss) {
        SheetHeader(str(if (existing == null) R.string.ui_group_new else R.string.ui_edit))

        Column {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(str(R.string.ui_provider_name)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiTheme.spacing.gutter),
        )

        SectionLabel(str(R.string.ui_choose_provider))
        AgentProvider.pickerOrder.forEach { candidate ->
            SheetRow(
                label = candidate.label,
                selected = candidate.id == providerId,
                onClick = {
                    providerId = candidate.id
                    model = ""
                    if (!candidate.isCustom) baseUrl = ""
                },
            )
        }

        SectionLabel(str(R.string.ui_choose_model))
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text(str(R.string.ui_custom_model_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiTheme.spacing.gutter),
        )
        models.take(6).forEach { id ->
            SheetRow(label = id, selected = id == model, monospace = true, onClick = { model = id })
        }

        if (provider.isCustom) {
            SectionLabel(str(R.string.ui_endpoint))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("https://...") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiTheme.spacing.gutter),
            )
        }

        // Wire protocol: what the endpoint actually speaks.
        SectionLabel(str(R.string.ui_endpoint_mode))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PiTheme.spacing.gutter),
            horizontalArrangement = Arrangement.spacedBy(PiTheme.spacing.md),
        ) {
            CustomProviderSettings.SHAPES.forEach { shape ->
                val label = if (shape == CustomProviderSettings.OPENAI_COMPLETIONS) {
                    "OpenAI-compatible"
                } else {
                    "Anthropic Messages"
                }
                if (shape == apiShape) {
                    Button(onClick = { apiShape = shape }) { Text(label) }
                } else {
                    OutlinedButton(onClick = { apiShape = shape }) { Text(label) }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PiTheme.spacing.gutter, vertical = PiTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = reasoning, onCheckedChange = { reasoning = it })
            Spacer(Modifier.size(PiTheme.spacing.md))
            Text(
                str(R.string.ui_reasoning_model),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionLabel(provider.keyEnv)
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = {
                Text(
                    if (existing != null && vm.hasKeyFor(existing)) {
                        str(R.string.ui_key_saved_hint)
                    } else {
                        provider.keyHint
                    },
                )
            },
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        if (keyVisible) Icons.Filled.Lock else Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiTheme.spacing.gutter),
        )

        // Enabled: a stored config you keep but do not use right now.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PiTheme.spacing.gutter, vertical = PiTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = enabled, onCheckedChange = { enabled = it })
            Spacer(Modifier.size(PiTheme.spacing.md))
            Text(
                str(R.string.ui_enable_this_provider),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PiTheme.spacing.gutter, vertical = PiTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(PiTheme.spacing.md),
        ) {
            if (existing != null) {
                OutlinedButton(onClick = { vm.deleteGroup(existing.id); onDone() }) {
                    Text(str(R.string.ui_delete), color = MaterialTheme.colorScheme.error)
                }
            }
            FilledTonalButton(
                onClick = {
                    val group = ModelGroup(
                        id = existing?.id ?: ModelGroup.newId(),
                        name = name.trim(),
                        providerId = providerId,
                        model = model.trim(),
                        baseUrl = baseUrl.trim(),
                        apiShape = apiShape,
                        reasoning = reasoning,
                        enabled = enabled,
                    )
                    vm.saveGroup(group, key.takeIf { it.isNotBlank() })
                    if (enabled) vm.activateGroup(group)
                    onDone()
                },
                // A group with no model cannot start a session; refusing here is
                // clearer than saving it and failing on the first message.
                enabled = model.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text(str(R.string.ui_save))
            }
        }
        Spacer(Modifier.height(PiTheme.spacing.group))
        }
    }
}

/** A row the status card can jump to; it reports its position in the window. */
@Composable
private fun MeasuredRow(onMeasured: (Int) -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.onGloballyPositioned { onMeasured(it.positionInRoot().y.toInt()) }) {
        content()
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> str(R.string.ui_theme_system)
    ThemeMode.LIGHT -> str(R.string.ui_theme_light)
    ThemeMode.DARK -> str(R.string.ui_theme_dark)
}

/** State pill in the hero card; tapping it scrolls to the row that owns it. */
@Composable
private fun StatusPill(label: String, ok: Boolean, onClick: () -> Unit) {
    val tint = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).background(tint, CircleShape))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
        )
    }
}

/** Accent color per section index, so each group gets its own icon hue. */
@Composable
private fun sectionAccent(index: Int) = when (index % 6) {
    0 -> MaterialTheme.colorScheme.primary
    1 -> MaterialTheme.colorScheme.secondary
    2 -> MaterialTheme.colorScheme.tertiary
    3 -> MaterialTheme.colorScheme.error
    4 -> MaterialTheme.colorScheme.primaryContainer
    else -> MaterialTheme.colorScheme.secondaryContainer
}

/** Collapsible settings section: a tinted icon + title header, animated content. */
@Composable
private fun Section(
    index: Int,
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    var isExpanded by remember { mutableStateOf(true) }
    val chevronAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "chevron",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .border(
                0.5.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                RoundedCornerShape(20.dp),
            ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(sectionAccent(index).copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = sectionAccent(index),
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer { rotationZ = chevronAngle },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 4 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 4 },
        ) {
            Column(
                Modifier.padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                content()
            }
        }
    }
}


@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    /** One line under the title: what this row actually does. */
    hint: String,
    value: String,
    monospace: Boolean = false,
    /** Green/red dot next to the value: this row's subject is working right now. */
    ok: Boolean? = null,
    /** Right-hand text instead of a chevron, e.g. a state word. */
    badge: String? = null,
    /** False greys the row out and swallows taps. */
    enabled: Boolean = true,
    /** Destructive: tinted red so it never gets tapped by accident. */
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val titleColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
        .copy(alpha = if (enabled) 0.9f else 0.38f)
    val iconTint = accent.copy(alpha = if (enabled) 1f else 0.38f)

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainer.copy(alpha = if (enabled) 1f else 0.6f),
                    RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = iconTint)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = titleColor,
            )
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = hintColor,
                maxLines = 2,
            )
        }
        if (value.isNotBlank()) {
            Row(
                modifier = Modifier.widthIn(max = 116.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (ok != null) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(
                                if (ok) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                CircleShape,
                            ),
                    )
                }
                Text(
                    value,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                    color = hintColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (badge != null) {
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f),
            )
        }
        Text(
            "\u203a",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.35f else 0.15f),
        )
    }
}

/** Switch row, same shape as [SettingsRow] so the list stays aligned. */
@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    hint: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val titleColor = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
        .copy(alpha = if (enabled) 0.9f else 0.38f)

    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (enabled) Modifier.clickable { onChange(!checked) } else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainer.copy(alpha = if (enabled) 1f else 0.6f),
                    RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = titleColor)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = hintColor)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Hairline divider aligned with the row icon column. */
@Composable
private fun RowDivider() =
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 72.dp, end = 16.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)),
    )

// ---- sheets ---------------------------------------------------------------

@Composable
private fun SheetHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(
            start = PiTheme.spacing.gutter,
            end = PiTheme.spacing.gutter,
            bottom = PiTheme.spacing.lg,
        ),
        color = MaterialTheme.colorScheme.onSurface,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f))
    Spacer(Modifier.height(PiTheme.spacing.sm))
}

@Composable
private fun SheetRow(
    label: String,
    selected: Boolean,
    monospace: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
        if (selected) {
            Text(
                "\u2713",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Optional toolchains: what is bundled, and what can be added.
 *
 * The bundled image covers Node, Git, Python and the shell essentials offline.
 * Everything else has to be fetched, so it is opt-in and the screen says so on
 * each row rather than discovering it halfway through a download.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolchainSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val states by vm.toolchains.collectAsStateWithLifecycle()
    val busy by vm.toolchainBusy.collectAsStateWithLifecycle()
    val error by vm.toolchainError.collectAsStateWithLifecycle()
    val installed = vm.provisionedTools

    LaunchedEffect(Unit) { vm.refreshToolchains() }

    CenterDialog(onDismiss = onDismiss) {
        SheetHeader(str(R.string.ui_toolchains))

        Text(
            str(R.string.ui_toolchains_core),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = PiTheme.spacing.gutter,
                end = PiTheme.spacing.gutter,
                bottom = PiTheme.spacing.sm,
            ),
        )
        Text(
            installed,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = PiTheme.spacing.gutter,
                end = PiTheme.spacing.gutter,
                bottom = PiTheme.spacing.md,
            ),
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f),
            modifier = Modifier.padding(vertical = PiTheme.spacing.sm),
        )

        Text(
            str(R.string.ui_toolchains_optional),
            style = PiTheme.typography.overline,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(
                start = PiTheme.spacing.gutter,
                bottom = PiTheme.spacing.xs,
            ),
        )
        Text(
            str(R.string.ui_toolchains_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = PiTheme.spacing.gutter,
                end = PiTheme.spacing.gutter,
                bottom = PiTheme.spacing.md,
            ),
        )

        Toolchain.ALL.forEach { tc ->
            val state = states[tc.id] ?: ToolchainState.ABSENT
            val isBusy = busy == tc.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiTheme.spacing.gutter, vertical = PiTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PiTheme.spacing.md),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        tc.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        tc.hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        // Size and network requirement, before the tap.
                        strf(R.string.ui_toolchains_size, tc.approxMegabytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                when {
                    isBusy -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    state == ToolchainState.PRESENT -> TextButton(
                        onClick = { vm.removeToolchain(tc) },
                    ) {
                        Text(str(R.string.ui_toolchains_remove), color = MaterialTheme.colorScheme.error)
                    }
                    else -> TextButton(onClick = { vm.installToolchain(tc) }) {
                        Text(str(R.string.ui_toolchains_install))
                    }
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.06f),
            )
        }

        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(
                    start = PiTheme.spacing.gutter,
                    end = PiTheme.spacing.gutter,
                    top = PiTheme.spacing.md,
                ),
            )
        }
        Spacer(Modifier.height(PiTheme.spacing.group))
    }
}

/**
 * The list of saved configurations, and the way to make or edit one.
 *
 * This is the piece the earlier "one row for the model service" did not deliver:
 * merging four settings rows into one sheet removed taps but still left exactly
 * one configuration. Two services meant retyping an address and a key on every
 * switch. A group holds the whole thing, so switching is one tap and nothing is
 * retyped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelServiceSheet(
    vm: AppViewModel,
    provider: AgentProvider,
    model: String,
    models: List<String>,
    presets: List<ModelProfile>,
    hasKey: Boolean,
    keyHint: String?,
    onPickProvider: (AgentProvider) -> Unit,
    onPickModel: (ModelProfile) -> Unit,
    onForgetPreset: (String) -> Unit,
    onOpenSheet: (Sheet) -> Unit,
    onDismiss: () -> Unit,
) {
    CenterDialog(onDismiss = onDismiss) {
        SheetHeader(str(R.string.ui_model_service))

        // 1. Who serves it.
        SectionLabel(str(R.string.ui_choose_provider))
        AgentProvider.pickerOrder.forEach { candidate ->
            SheetRow(
                label = candidate.label,
                selected = candidate == provider,
                onClick = { onPickProvider(candidate) },
            )
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f),
            modifier = Modifier.padding(vertical = PiTheme.spacing.md),
        )

        // 2. Which model, with the endpoint shown when it is not the default.
        SectionLabel(str(R.string.ui_choose_model))
        if (presets.isNotEmpty()) {
            presets.forEach { profile ->
                val inUse = profile.model == model
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPickModel(profile) }
                        .padding(horizontal = PiTheme.spacing.gutter, vertical = PiTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PiTheme.spacing.md),
                ) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = str(R.string.ui_remove_saved),
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onForgetPreset(profile.profileKey) },
                        tint = if (inUse) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            profile.model,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = if (inUse) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        if (profile.baseUrl.isNotBlank()) {
                            Text(
                                profile.baseUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f),
                modifier = Modifier.padding(vertical = PiTheme.spacing.xs),
            )
        }
        // The provider's own list, for anything not yet saved.
        models.filter { id -> presets.none { it.model == id } }.take(8).forEach { id ->
            SheetRow(
                label = id,
                selected = id == model,
                monospace = true,
                onClick = { onPickModel(vm.profileFor(id)) },
            )
        }

        // 3. Where it lives, for a custom endpoint only.
        if (provider.isCustom) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f),
                modifier = Modifier.padding(vertical = PiTheme.spacing.md),
            )
            SectionLabel(str(R.string.ui_endpoint))
            val baseUrl by vm.customBaseUrl.collectAsStateWithLifecycle()
            OutlinedButton(
                onClick = { onOpenSheet(Sheet.ENDPOINT) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PiTheme.spacing.gutter),
            ) {
                Text(
                    baseUrl.ifBlank { str(R.string.ui_not_set) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 4. The key, last: it is the thing that makes the rest usable.
        HorizontalDivider(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f),
            modifier = Modifier.padding(vertical = PiTheme.spacing.md),
        )
        SectionLabel(provider.keyEnv)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PiTheme.spacing.gutter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PiTheme.spacing.md),
        ) {
            Text(
                keyHint ?: str(if (hasKey) R.string.ui_key_saved else R.string.ui_not_set),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = if (hasKey) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { onOpenSheet(Sheet.KEY) }) {
                Text(str(if (hasKey) R.string.ui_saved else R.string.ui_not_set))
            }
        }

        Spacer(Modifier.height(PiTheme.spacing.group))
    }
}

/** A section heading inside a sheet. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = PiTheme.typography.overline,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = Modifier.padding(
            start = PiTheme.spacing.gutter,
            top = PiTheme.spacing.md,
            bottom = PiTheme.spacing.sm,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSheet(
    current: AgentProvider,
    onPick: (AgentProvider) -> Unit,
    onDismiss: () -> Unit,
) {
    CenterDialog(onDismiss = onDismiss) {
        SheetHeader(str(R.string.ui_provider))
        AgentProvider.pickerOrder.forEach { candidate ->
            SheetRow(
                label = candidate.label,
                selected = candidate == current,
                onClick = { onPick(candidate) },
            )
        }
        Text(
            str(R.string.ui_provider_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(
    vm: AppViewModel,
    models: List<String>,
    current: String,
    presets: List<ModelProfile>,
    onPick: (ModelProfile) -> Unit,
    onForget: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val fetching by vm.fetchingModels.collectAsStateWithLifecycle()
    val errorText by vm.modelFetchError.collectAsStateWithLifecycle()
    val error = errorText?.resolve()
    var manual by remember { mutableStateOf("") }
    val isCustom = vm.provider.collectAsStateWithLifecycle().value.isCustom

    CenterDialog(onDismiss = onDismiss) {
        SheetHeader(str(R.string.ui_choose_model))

        // Saved presets first: the whole point of keeping several is that the
        // ones you actually use sit at the top and are one tap away.
        AnimatedVisibility(
            visible = presets.isNotEmpty(),
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 },
            exit = fadeOut(tween(120)),
        ) {
            Column(
                Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    str(R.string.ui_saved_models),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                presets.forEach { profile ->
                    val inUse = profile.model == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(profile) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = str(R.string.ui_remove_saved),
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { onForget(profile.profileKey) },
                            tint = if (inUse) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                profile.model,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                            // The address is part of the configuration, so it is
                            // shown here rather than only in the settings sheet:
                            // two saved entries can share a model id.
                            if (profile.baseUrl.isNotBlank()) {
                                Text(
                                    profile.baseUrl,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (inUse) {
                            Text(
                                str(R.string.ui_in_use),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalButton(onClick = vm::fetchModels, enabled = !fetching) {
                if (fetching) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(str(if (fetching) R.string.ui_fetching else R.string.ui_fetch_models))
            }
            Text(
                strf(R.string.ui_models_available, models.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
            )
        }

        Column(
            Modifier
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            models.forEach { id ->
                SheetRow(label = id, selected = id == current, monospace = true) {
                    onPick(vm.profileFor(id))
                }
            }
            if (models.isEmpty()) {
                Text(
                    str(R.string.ui_not_set),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                )
            }
        }

        // Free text matters for a custom endpoint (we cannot know its ids) and is
        // a useful escape hatch everywhere else, e.g. a model newer than the list.
        OutlinedTextField(
            value = manual,
            onValueChange = { manual = it },
            label = { Text(str(R.string.ui_custom_model_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { if (manual.isNotBlank()) onPick(vm.profileFor(manual.trim())) },
            ),
        )
        TextButton(
            onClick = { if (manual.isNotBlank()) onPick(vm.profileFor(manual.trim())) },
            enabled = manual.isNotBlank(),
            modifier = Modifier.padding(horizontal = 12.dp),
        ) { Text(str(R.string.ui_use_model)) }

        if (isCustom) {
            Text(
                str(R.string.ui_custom_endpoint_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeySheet(
    provider: AgentProvider,
    keyHint: String?,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var key by remember(provider) { mutableStateOf("") }
    CenterDialog(onDismiss = onDismiss) {
        SheetHeader(strf(R.string.ui_api_key_title, provider.label))
        Text(
            str(R.string.ui_key_storage_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
        )
        Text(
            keyHint ?: str(R.string.ui_not_set),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = if (keyHint != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(provider.keyHint) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (key.isNotBlank()) onSave(key.trim()) }),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(onClick = { onSave(key.trim()) }, enabled = key.isNotBlank()) {
                Text(str(R.string.ui_save_key))
            }
            if (keyHint != null) {
                OutlinedButton(onClick = { onClear(); onDismiss() }) {
                    Text(str(R.string.ui_forget_key))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndpointSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val baseUrl by vm.customBaseUrl.collectAsStateWithLifecycle()
    val apiShape by vm.customApiShape.collectAsStateWithLifecycle()
    CenterDialog(onDismiss = onDismiss) {
        SheetHeader(str(R.string.ui_custom_endpoint))
        Text(
            str(R.string.ui_custom_endpoint_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = vm::setCustomBaseUrl,
            label = { Text(str(R.string.ui_base_url_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CustomProviderSettings.SHAPES.forEach { shape ->
                val label = if (shape == CustomProviderSettings.OPENAI_COMPLETIONS) {
                    str(R.string.ui_shape_openai)
                } else {
                    str(R.string.ui_shape_anthropic)
                }
                if (shape == apiShape) {
                    Button(onClick = { vm.setCustomApiShape(shape) }) { Text(label) }
                } else {
                    OutlinedButton(onClick = { vm.setCustomApiShape(shape) }) { Text(label) }
                }
            }
        }
        val reasoning by vm.customReasoning.collectAsStateWithLifecycle()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Switch(checked = reasoning, onCheckedChange = vm::setCustomReasoning)
            Column(Modifier.weight(1f)) {
                Text(str(R.string.ui_reasoning_model), style = MaterialTheme.typography.bodyMedium)
                Text(
                    str(R.string.ui_reasoning_model_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val repoPath by vm.repoPath.collectAsStateWithLifecycle()
    var draft by remember(repoPath) { mutableStateOf(repoPath) }

    CenterDialog(onDismiss = onDismiss) {
        SheetHeader(str(R.string.ui_repo_path))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
            label = { Text(str(R.string.ui_repo_path)) },
        )
        Text(
            str(R.string.ui_repo_path_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 22.dp),
        )
        Button(
            onClick = { vm.setRepoPath(draft); onDismiss() },
            enabled = draft.isNotBlank() && draft != repoPath,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
        ) { Text(str(R.string.ui_save)) }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The GitHub token that lets the agent push commits. Split out of the repo-path
 * sheet so "keys" live in one place and read as their own thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TokenSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val tokenSaved by vm.hasGitHubToken.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf("") }

    CenterDialog(onDismiss = onDismiss) {
        SheetHeader(str(R.string.ui_github_token))
        Text(
            str(R.string.ui_github_token_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = {
                Text(
                    str(
                        if (tokenSaved) R.string.ui_github_token_saved
                        else R.string.ui_github_token_hint,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { vm.saveGitHubToken(token); token = "" },
                enabled = token.isNotBlank(),
            ) { Text(str(R.string.ui_save_token)) }
            if (tokenSaved) {
                OutlinedButton(onClick = vm::clearGitHubToken) {
                    Text(str(R.string.ui_forget_token))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Per-capability phone control switches. Each action already ends in an
 * OS-owned confirmation or a dismissible notification; these switches let a
 * user who wants none of it take the tools away from the next session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneControlSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val caps by vm.phoneCaps.collectAsStateWithLifecycle()
    CenterDialog(onDismiss = onDismiss) {
        SheetHeader(str(R.string.ui_phone_control))
        Text(
            str(R.string.ui_phone_control_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
        )
        vm.phoneCapabilityNames.forEach { name ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    phoneCapabilityIcon(name),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    str(phoneCapabilityLabel(name)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Switch(
                    checked = caps[name] != false,
                    onCheckedChange = { vm.setPhoneCapability(name, it) },
                )
            }
        }
        Text(
            str(R.string.ui_next_session),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
        )
        Spacer(Modifier.height(20.dp))
    }
}

private fun phoneCapabilityLabel(name: String): Int = when (name) {
    "phone.notify" -> R.string.ui_phone_notify
    "phone.open" -> R.string.ui_phone_open
    "phone.alarm" -> R.string.ui_phone_alarm
    "phone.timer" -> R.string.ui_phone_timer
    "phone.calendar" -> R.string.ui_phone_calendar
    else -> R.string.ui_phone_notify
}

private fun phoneCapabilityIcon(name: String): ImageVector = when (name) {
    "phone.notify" -> Icons.Filled.Notifications
    "phone.open" -> Icons.Filled.OpenInNew
    "phone.alarm" -> Icons.Filled.Alarm
    "phone.timer" -> Icons.Filled.Timer
    "phone.calendar" -> Icons.Filled.CalendarMonth
    else -> Icons.Filled.Phone
}

/**
 * The storage breakdown.
 *
 * A single total would be useless: the number is dominated by the sandbox, and
 * knowing that is what tells a user whether clearing anything is worth it.
 * Every figure comes from one measurement taken when the sheet opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<StorageReport?>(null) }
    var measuring by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        measuring = true
        report = withContext(Dispatchers.IO) { StorageUsageReader.read(context.filesDir) }
        measuring = false
    }

    CenterDialog(onDismiss = onDismiss) {
        SheetHeader(str(R.string.ui_storage_usage))
        Column(
            Modifier.padding(horizontal = PiTheme.spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(PiTheme.spacing.md),
        ) {
            when (val r = report) {
                null -> Text(
                    str(R.string.ui_storage_measuring),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is StorageReport.Failed -> Text(
                    strf(R.string.ui_storage_failed, r.reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                is StorageReport.Measured -> {
                    StorageBreakdown(r.usage, total = r.usage.totalBytes)
                    StorageDetail(context.filesDir)
                }
                is StorageReport.Partial -> {
                    StorageBreakdown(r.usage, total = r.usage.totalBytes)
                    Text(
                        strf(R.string.ui_storage_partial, r.unreadable.joinToString("、")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Text(
                str(R.string.ui_storage_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { reload++ },
                enabled = !measuring,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(str(R.string.ui_storage_refresh)) }
            Spacer(Modifier.height(PiTheme.spacing.lg))
        }
    }
}

/**
 * The largest items inside the app directory.
 *
 * A total answers "how much"; this answers "what". When a figure is far larger
 * than it should be, the cause is almost always one or two leftovers — an
 * archive never deleted, a staging directory from a failed install — and those
 * are invisible in a category total.
 */
@Composable
private fun StorageDetail(filesDir: java.io.File) {
    val entries = remember(filesDir) {
        StorageUsageReader.largestEntries(filesDir, limit = 8)
    }
    if (entries.isEmpty()) return
    Text(
        str(R.string.ui_storage_detail),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = PiTheme.spacing.lg),
    )
    Column(verticalArrangement = Arrangement.spacedBy(PiTheme.spacing.xs)) {
        entries.forEach { (name, bytes) ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    StorageUsageReader.format(bytes),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One row per category, then the total. */
@Composable
private fun StorageBreakdown(usage: StorageUsage, total: Long) {
    val rows = listOf(
        R.string.ui_storage_sandbox to usage.sandboxBytes,
        R.string.ui_storage_meta to usage.metaBytes,
        R.string.ui_storage_history to usage.historyBytes,
        R.string.ui_storage_other to usage.otherBytes,
    )
    Column(verticalArrangement = Arrangement.spacedBy(PiTheme.spacing.lg)) {
        rows.forEach { (label, bytes) ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    str(label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    StorageUsageReader.format(bytes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                str(R.string.ui_storage_total),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                StorageUsageReader.format(total),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    CenterDialog(onDismiss = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "\u03c0",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Column {
                Text(
                    str(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    (if (version.isNotBlank()) version + " · " else "") + str(R.string.ui_about_body),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val mode by vm.themeMode.collectAsStateWithLifecycle()
    CenterDialog(onDismiss = onDismiss) {
        SheetHeader(str(R.string.ui_theme))
        Text(
            str(R.string.ui_theme_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
        )
        ThemeMode.entries.forEach { option ->
            SheetRow(
                label = themeLabel(option),
                selected = option == mode,
                onClick = { vm.setThemeMode(option); onDismiss() },
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** What the build promises to put in the sandbox. Read-only on purpose: the
 *  list is baked into the APK, so there is nothing here to re-fetch. */
/** Destructive, so it asks once more and says exactly what will be lost. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClearHistorySheet(count: Int, onClear: () -> Unit, onDismiss: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    CenterDialog(onDismiss = onDismiss) {
        SheetHeader(str(R.string.ui_clear_history))
        Text(
            if (confirming) str(R.string.ui_clear_history_confirm)
            else strf(R.string.ui_history_count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = if (confirming) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { if (confirming) onClear() else confirming = true },
                enabled = count > 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(
                    if (confirming) str(R.string.ui_delete) else str(R.string.ui_clear_history),
                )
            }
            TextButton(onClick = onDismiss) { Text(str(R.string.ui_cancel)) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolsSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    CenterDialog(onDismiss = onDismiss) {
        SheetHeader(str(R.string.ui_tools_title))
        Text(
            str(R.string.ui_tools_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
        )
        Text(
            vm.provisionedTools,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * A bottom sheet holding one slider.
 *
 * Rate and pitch live in a sheet rather than inline: a slider inside the
 * scrolling settings list swallows vertical drags, so scrolling past it would
 * change the value instead of scrolling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ValueSliderSheet(
    title: String,
    hint: String,
    value: Float,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    CenterDialog(onDismiss = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                format(draft),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
        )
        Slider(
            value = draft,
            onValueChange = { draft = it; onChange(it) },
            valueRange = 0.5f..2.0f,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { draft = 1f; onChange(1f) }) {
                Text(str(R.string.ui_reset))
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onDismiss) { Text(str(R.string.ui_close)) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val detail by vm.diagnostic.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val body = detail ?: str(R.string.ui_diagnostics_empty)

    CenterDialog(onDismiss = onDismiss) {
        SheetHeader(str(R.string.ui_diagnostics))
        Column(
            Modifier.padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                str(R.string.ui_diagnostics_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Writes the recovery state to Download/DSH-Go/. The app's private
            // directory is unreachable from a host shell on this device, so
            // without this there is no way to verify that a spawn was reserved,
            // registered and released.
            FilledTonalButton(
                onClick = { vm.exportRecoveryState() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(str(R.string.ui_export_recovery)) }
            // A process that died without being released keeps the gate shut
            // forever. This is the exit, and it only opens when every recorded
            // process is confirmed gone.
            OutlinedButton(
                onClick = { vm.reconcileRegistry() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(str(R.string.ui_reconcile_registry)) }
            // A reservation that never started a process would otherwise lock
            // the environment permanently, with clearing app data as the only
            // way out — losing the sandbox and the conversation history.
            OutlinedButton(
                onClick = { vm.abandonStalledReservation() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(str(R.string.ui_clear_reservation)) }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Selectable and copyable: a diagnostic nobody can quote is a
                // diagnostic nobody can report.
                SelectionContainer {
                    Text(
                        body,
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { clipboard.setText(AnnotatedString(body)) }) {
                    Text(str(R.string.ui_copy))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}


/**
 * One-line description of what the sandbox is doing.
 *
 * Exhaustive by construction: adding a state without describing it here is a
 * compile error, which is the only reason the old hand-written lists stayed
 * correct as long as they did.
 */
@Composable
private fun sandboxHeadline(state: SandboxState): String = when (state) {
    is SandboxState.Ready -> str(R.string.ui_sandbox_ready)
    is SandboxState.NotInstalled -> str(R.string.ui_sandbox_provision_hint)
    is SandboxState.Checking -> when (state.step) {
        SandboxState.CheckStep.ABI -> str(R.string.ui_sandbox_checking_abi)
        SandboxState.CheckStep.BUNDLED_ASSET -> str(R.string.ui_sandbox_checking_asset)
        SandboxState.CheckStep.DISK_SPACE -> str(R.string.ui_sandbox_checking_disk)
        SandboxState.CheckStep.EXISTING_ENV -> str(R.string.ui_sandbox_checking)
    }
    is SandboxState.Downloading -> str(R.string.ui_downloading_rootfs)
    is SandboxState.Extracting ->
        str(if (state.fromBundled) R.string.ui_sandbox_extracting_bundled
        else R.string.ui_extracting_rootfs)
    is SandboxState.Installing -> when (state.step) {
        SandboxState.InstallStep.APK_UPDATE -> str(R.string.ui_installing)
        SandboxState.InstallStep.APK_ADD -> str(R.string.ui_installing)
        SandboxState.InstallStep.WRAPPER_SOURCE -> str(R.string.ui_sandbox_deploying)
    }
    is SandboxState.Deploying -> str(R.string.ui_sandbox_deploying)
    is SandboxState.Verifying -> str(R.string.ui_sandbox_verifying)
    is SandboxState.Upgrading -> str(R.string.ui_sandbox_upgrading)
    is SandboxState.Error -> strf(R.string.ui_setup_failed, state.message)
    is SandboxState.Cancelled -> str(R.string.ui_sandbox_cancelled)
}

/** Progress details for the stages that have them; nothing for the rest. */
@Composable
private fun SandboxProgressBlock(state: SandboxState) {
    when (state) {
        is SandboxState.Downloading -> {
            val frac = state.fraction
            if (frac != null) {
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            } else {
                // No Content-Length: say so with an indeterminate bar instead of
                // inventing a percentage.
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                buildList {
                    if (state.bytesTotal != null) {
                        add(strf(R.string.ui_sandbox_progress_bytes,
                            formatBytes(state.bytesRead), formatBytes(state.bytesTotal)))
                    } else {
                        add(formatBytes(state.bytesRead))
                    }
                    state.bytesPerSecond?.let {
                        add(strf(R.string.ui_sandbox_progress_speed, formatBytes(it)))
                    }
                    if (state.mirrorHost.isNotBlank()) {
                        add(strf(R.string.ui_sandbox_mirror,
                            state.mirrorIndex, state.mirrorCount, state.mirrorHost))
                    }
                    state.etaSeconds?.takeIf { it > 0 }?.let {
                        add(strf(R.string.ui_sandbox_eta, it))
                    }
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is SandboxState.Extracting -> {
            val frac = state.fraction
            if (frac != null) {
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                buildList {
                    state.bytesTotal?.let {
                        add(strf(R.string.ui_sandbox_progress_bytes,
                            formatBytes(state.bytesRead), formatBytes(it)))
                    }
                    // Say plainly that this is not a write-completion figure.
                    add(str(R.string.ui_sandbox_extract_note))
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is SandboxState.Installing -> {
            when (state.step) {
                // Updating the index has no reliable percentage. An
                // indeterminate bar that says so beats one frozen at 0%, which
                // read as "stuck" and is what the user reported.
                SandboxState.InstallStep.APK_UPDATE -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        str(R.string.ui_sandbox_updating_index),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Real package progress. Indeterminate until apk reports its
                // first counter line, determinate from there — and it now
                // advances through the download phase too, not just the install.
                SandboxState.InstallStep.APK_ADD -> {
                    if (state.completed > 0) {
                        LinearProgressIndicator(
                            progress = {
                                if (state.total > 0) state.completed.toFloat() / state.total else 0f
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            strf(R.string.ui_sandbox_installing_pkg, state.completed, state.total,
                                state.currentPackage ?: "-"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // Still in the download sub-phase: apk reports no total
                        // here, so say so instead of a stuck "0/N".
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            str(R.string.ui_sandbox_downloading_pkgs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // The wrapper source is a single copy; nothing to measure.
                SandboxState.InstallStep.WRAPPER_SOURCE -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        str(R.string.ui_sandbox_deploying),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // A top-up in progress. Previously this fell into the "draw nothing"
        // else-branch, so the moment a top-up started the progress area was
        // empty — one reason the screen looked like it just spun.
        is SandboxState.Upgrading -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                str(R.string.ui_sandbox_upgrading),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is SandboxState.Error -> {
            Text(
                strf(R.string.ui_sandbox_error_stage, stageLabel(state.stage)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                str(if (state.retryable) R.string.ui_sandbox_retryable
                else R.string.ui_sandbox_not_retryable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is SandboxState.Cancelled -> {
            if (state.failed.isNotEmpty()) {
                Text(
                    strf(R.string.ui_sandbox_cancel_partial, state.failed.joinToString(", ")),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Checking / Deploying / Verifying / Upgrading / Ready / NotInstalled:
        // no measurable progress, and none is invented.
        else -> Unit
    }
}

@Composable
private fun stageLabel(stage: SandboxState.Stage): String = when (stage) {
    SandboxState.Stage.CHECK -> str(R.string.ui_stage_check)
    SandboxState.Stage.DOWNLOAD -> str(R.string.ui_stage_download)
    SandboxState.Stage.EXTRACT -> str(R.string.ui_stage_extract)
    SandboxState.Stage.INSTALL -> str(R.string.ui_stage_install)
    SandboxState.Stage.DEPLOY -> str(R.string.ui_stage_deploy)
    SandboxState.Stage.VERIFY -> str(R.string.ui_stage_verify)
    SandboxState.Stage.UPGRADE -> str(R.string.ui_stage_upgrade)
}

/** "84.2 MB" — sized for a phone, not for a byte counter. */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SandboxSheet(
    vm: AppViewModel,
    sandbox: SandboxState,
    onProvision: () -> Unit,
    onDismiss: () -> Unit,
) {
    val reportText by vm.sandboxReport.collectAsStateWithLifecycle()
    val report = reportText?.resolve()
    val busy by vm.sandboxBusy.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    // Probe on open: the whole point of this screen is showing what is really
    // there, and a cached answer would be exactly the stale reassurance the app
    // used to give.
    LaunchedEffect(Unit) { vm.refreshSandboxReport() }

    val autoInstall by vm.autoInstall.collectAsStateWithLifecycle()

    CenterDialog(onDismiss = onDismiss) {
        SheetHeader(str(R.string.ui_sandbox_tools))
        Column(
            Modifier.padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Explicit opt-in for automatic setup: the download is large and
            // may be metered, so the user decides rather than the app.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Switch(checked = autoInstall, onCheckedChange = {
                    vm.setAutoInstall(it)
                    vm.notifyRes(
                        if (it) R.string.ui_auto_install_on else R.string.ui_auto_install_off,
                    )
                })
                Column(Modifier.weight(1f)) {
                    Text(str(R.string.ui_auto_install), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        str(R.string.ui_auto_install_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                sandboxHeadline(sandbox),
                style = MaterialTheme.typography.bodyMedium,
                color = if (sandbox is SandboxState.Error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )

            // Real progress, or an honest absence of it. Stages that cannot
            // measure themselves show their name and nothing else rather than a
            // bar that pretends to advance.
            SandboxProgressBlock(sandbox)

            Text(
                str(R.string.ui_tools_bundled),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                vm.provisionedTools,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.refreshSandboxReport() }, enabled = !busy) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(str(R.string.ui_check_now))
                }
                OutlinedButton(onClick = { vm.repairSandbox(false) }, enabled = !busy) {
                    Text(str(R.string.ui_redeploy))
                }
            }
            FilledTonalButton(
                onClick = { vm.installSandboxTools() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(str(R.string.ui_install_tools)) }

            // Which apk mirror leads. The screenshot failure was apk fetching
            // from European CDNs that the device could not reach; letting the
            // user pick a reachable mirror is the difference between "tools
            // install" and "tools never install" on a carrier that blocks one.
            val currentMirror by vm.apkMirror.collectAsStateWithLifecycle()
            var mirrorMenu by remember { mutableStateOf(false) }
            Text(
                str(R.string.ui_apk_mirror_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box {
                OutlinedButton(
                    onClick = { mirrorMenu = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (currentMirror.isBlank()) str(R.string.ui_apk_mirror_auto)
                        else currentMirror.substringAfter("//").trimEnd('/'),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(expanded = mirrorMenu, onDismissRequest = { mirrorMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(str(R.string.ui_apk_mirror_auto)) },
                        onClick = {
                            mirrorMenu = false
                            vm.setApkMirror("")
                        },
                    )
                    vm.knownApkMirrors.forEach { mirror ->
                        DropdownMenuItem(
                            text = { Text(mirror.substringAfter("//").trimEnd('/')) },
                            onClick = {
                                mirrorMenu = false
                                vm.setApkMirror(mirror)
                            },
                        )
                    }
                }
            }

            val log by vm.sandboxLog.collectAsStateWithLifecycle()
            if (busy || log.isNotEmpty()) {
                LiveLogPanel(
                    lines = log,
                    busy = busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        report ?: str(R.string.ui_check_pending),
                        modifier = Modifier
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.repairSandbox(true) }, enabled = !busy) {
                    Text(str(R.string.ui_install_deps))
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(report.orEmpty())) }) {
                    Text(str(R.string.ui_copy))
                }
            }

            if (!sandbox.allowsNewSession) {
                Button(onClick = onProvision, modifier = Modifier.fillMaxWidth()) {
                    Text(str(R.string.ui_setup_sandbox))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Live guest output for a long-running sandbox command.
 *
 * apk and npm both narrate what they are doing; the previous build showed only a
 * disabled button, so a four-minute dependency install looked identical to a
 * dead app. This keeps the newest line in view and states what is happening.
 */
@Composable
private fun LiveLogPanel(
    lines: List<String>,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    // Follow the tail, but only while the user has not scrolled away from it.
    LaunchedEffect(lines.size, busy) {
        if (lines.isNotEmpty()) scroll.animateScrollTo(scroll.maxValue)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
        modifier = modifier,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                }
                Text(
                    if (busy) str(R.string.ui_running_now) else str(R.string.ui_last_output),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                lines.takeLast(40).joinToString("\n").ifBlank { str(R.string.ui_waiting_output) },
                modifier = Modifier
                    .heightIn(max = 160.dp)
                    .verticalScroll(scroll),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
