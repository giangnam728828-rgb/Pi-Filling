package industries.huginn.pifilling.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import java.util.Locale
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.infiniteTransition
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.Visibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.longClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VpnKey
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import industries.huginn.pifilling.R
import industries.huginn.pifilling.runtime.AgentController.SessionState
import industries.huginn.pifilling.sandbox.AgentProvider
import industries.huginn.pifilling.sandbox.SandboxState
import kotlinx.coroutines.launch

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
private enum class Sheet { NONE, PROVIDER, MODEL, KEY, ENDPOINT, SANDBOX, FOLDER, DIAG }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiFillingApp(vm: AppViewModel) {
    val hasKey by vm.hasApiKey.collectAsStateWithLifecycle()
    val provider by vm.provider.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val sandbox by vm.sandboxState.collectAsStateWithLifecycle()
    val session by vm.sessionState.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()
    val keyHint by vm.keyHint.collectAsStateWithLifecycle()

    var settingsOpen by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(Sheet.NONE) }

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
    BackHandler(enabled = sheet != Sheet.NONE || settingsOpen) {
        if (sheet != Sheet.NONE) sheet = Sheet.NONE else settingsOpen = false
    }

    CompositionLocalProvider(LocalContext provides localizedContext) {
        AuroraBackground {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // Swipe-to-open only from the chat: inside settings the gesture would
            // fight the vertical scroll of the list.
            gesturesEnabled = !settingsOpen,
            drawerContent = {
                AppDrawer(
                    provider = provider,
                    model = model,
                    sandboxReady = sandbox is SandboxState.Ready,
                    settingsOpen = settingsOpen,
                    onNewChat = {
                        vm.resetChat()
                        settingsOpen = false
                        scope.launch { drawerState.close() }
                    },
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
                            Column {
                                Text(
                                    if (settingsOpen) str(R.string.ui_settings)
                                    else provider.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    if (settingsOpen) str(R.string.app_name) else model,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        navigationIcon = {
                            if (settingsOpen) {
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
                            IconButton(
                                onClick = {
                                    if (settingsOpen) settingsOpen = false
                                    else settingsOpen = true
                                },
                            ) {
                                Icon(
                                    if (settingsOpen) Icons.Filled.Menu else Icons.Filled.Settings,
                                    contentDescription = str(R.string.ui_settings),
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                        ),
                    )
                },
            ) { padding ->
                Column(Modifier.padding(padding).fillMaxSize()) {
                    // The two "screens" cross-fade with a small vertical drift instead of
                    // a hard cut, so opening settings reads as navigation, not a reload.
                    AnimatedContent(
                        targetState = settingsOpen,
                        transitionSpecification = {
                            tween(durationMillis = 220, easing = FastOutSlowInEasing)
                        },
                        contentTransform = {
                            fadeIn(
                                animationSpec = tween(220, easing = FastOutSlowInEasing),
                                initialVisibility = Visibility.Hidden,
                            ).togetherWith(
                                fadeOut(
                                    animationSpec = tween(180, easing = FastOutSlowInEasing),
                                    targetVisibility = Visibility.Hidden,
                                ),
                            )
                        },
                    ) { open ->
                        if (open) {
                            SettingsScreen(
                                vm = vm,
                                provider = provider,
                                model = model,
                                modelsCount = models.size,
                                keyHint = keyHint,
                                sandbox = sandbox,
                                language = language,
                                onOpenSheet = { sheet = it },
                            )
                        } else {
                            ChatPane(
                                vm = vm,
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
                    presets = vm.presets.collectAsStateWithLifecycle().value,
                    onPick = { vm.setModel(it); dismiss() },
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
                Sheet.NONE -> Unit
            }
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
    onNewChat: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    ModalDrawerSheet(
        drawerContainerColor = Color.Transparent,
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
        modifier = Modifier.widthIn(max = 300.dp),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .glass(shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp), elevation = 10.dp),
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 16.dp)) {
                Text(
                    str(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${provider.label} · $model",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (sandboxReady) str(R.string.ui_sandbox_ready) else str(R.string.ui_not_set),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (sandboxReady) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f))

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
    }
}

@Composable
private fun DrawerItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                RoundedCornerShape(24.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---- chat -----------------------------------------------------------------

@Composable
private fun ChatPane(
    vm: AppViewModel,
    provider: AgentProvider,
    model: String,
    session: SessionState,
    sandbox: SandboxState,
    hasKey: Boolean,
    onOpenModel: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiag: () -> Unit,
) {
    val transcript by vm.transcript.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val streaming = session is SessionState.Streaming
    val connecting = session is SessionState.Connecting || session is SessionState.Provisioning

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.lastIndex)
    }

    val blocked = !hasKey || sandbox is SandboxState.NotInstalled ||
        sandbox is SandboxState.Downloading || sandbox is SandboxState.Extracting

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
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
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(transcript) { line -> MessageBubble(line) }
                    // While the request is in flight and nothing has come back yet,
                    // the three pulsing dots say "it is thinking", which a blank
                    // pane and a disabled button never did.
                    item {
                        val waitingAssistant = streaming && transcript.lastOrNull()?.role != "assistant"
                        AnimatedVisibility(
                            visible = waitingAssistant,
                            enter = fadeIn(tween(160)),
                            exit = fadeOut(tween(120)),
                        ) {
                            ThinkingRow()
                        }
                    }
                }
            }
        }

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
                    .glass(
                        shape = RoundedCornerShape(16.dp),
                        elevation = 2.dp,
                        tint = MaterialTheme.colorScheme.errorContainer,
                    )
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

        Composer(
            value = input,
            onValueChange = { input = it },
            enabled = !blocked,
            streaming = streaming,
            modelChip = model,
            providerLabel = provider.label,
            presets = vm.presets.collectAsStateWithLifecycle().value,
            onModelClick = onOpenModel,
            onPickPreset = { vm.switchModel(it) },
            onSend = {
                val text = input.trim()
                if (text.isNotEmpty()) {
                    vm.send(text)
                    input = ""
                }
            },
            onStop = vm::abort,
        )
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
    Column(
        modifier = Modifier.fillMaxSize().padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(52.dp),
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
        Text(
            str(R.string.ui_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
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
        Spacer(Modifier.height(4.dp))
        listOf(R.string.ui_quick_1, R.string.ui_quick_2, R.string.ui_quick_3).forEach { id ->
            // Resolved in composable scope: `str()` cannot be called from the
            // click lambda, which outlives this composition.
            val label = str(id)
            Box(
                Modifier
                    .fillMaxWidth()
                    .glass(shape = RoundedCornerShape(16.dp), strength = 0.85f, elevation = 2.dp)
                    .clickable { onPick(label) },
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
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
                .glass(shape = RoundedCornerShape(10.dp), strength = 0.7f, elevation = 1.dp)
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

@Composable
private fun MessageBubble(line: AppViewModel.TranscriptLine) {
    // New lines ease in; the streaming last line keeps this identity as it grows,
    // so it animates once and then just extends in place.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    AnimatedVisibility(
        visible = entered,
        enter = fadeIn(tween(200, easing = FastOutSlowInEasing)).togetherWith(
            slideInVertically(tween(200, easing = FastOutSlowInEasing)) { it / 4 },
        ),
    ) {
        MessageBody(line)
    }
}

@Composable
private fun MessageBody(line: AppViewModel.TranscriptLine) {
    when (line.role) {
        "you" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .glass(
                        shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                        elevation = 3.dp,
                    ),
            ) {
                Text(
                    line.text,
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        "assistant" -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AgentAvatar()
            Box(
                Modifier
                    .widthIn(max = 300.dp)
                    .glass(shape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp), elevation = 3.dp),
            ) {
                Text(
                    line.text,
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        // Tool chatter and status: a narrow inline chip, so it reads as an aside
        // and never competes with the reply for attention.
        else -> Row(Modifier.fillMaxWidth().padding(start = 34.dp)) {
            Box(
                Modifier.glass(shape = RoundedCornerShape(10.dp), strength = 0.7f, elevation = 1.dp),
            ) {
                Text(
                    line.text,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    streaming: Boolean,
    modelChip: String,
    providerLabel: String,
    /** Saved presets for the active provider, most recent first. */
    presets: List<String>,
    onModelClick: () -> Unit,
    /** Switch the running model straight from the chat box. */
    onPickPreset: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    var switcherOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        // Model chip sits above the field, the way a chat assistant advertises
        // what it is running. The swap button next to it is the quick switcher:
        // tap, pick a saved model, keep typing — no settings round-trip.
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .glass(shape = RoundedCornerShape(20.dp), strength = 0.8f, elevation = 1.dp)
                    .clickable(onClick = onModelClick),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        providerLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        modelChip.ifBlank { str(R.string.ui_not_set) },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (presets.size > 1) {
                Box {
                    FilledIconButton(
                        onClick = { switcherOpen = true },
                        modifier = Modifier.size(34.dp),
                        enabled = enabled,
                    ) {
                        Icon(
                            Icons.Filled.SwapVert,
                            contentDescription = str(R.string.ui_switch_model),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = switcherOpen,
                        onDismissRequest = { switcherOpen = false },
                    ) {
                        presets.filter { it != modelChip }.forEach { id ->
                            DropdownMenuItem(
                                text = {
                                    Text(id, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Star,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                onClick = {
                                    onPickPreset(id)
                                    switcherOpen = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(str(R.string.ui_manage_models)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            onClick = {
                                switcherOpen = false
                                onModelClick()
                            },
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp)
                .glass(shape = RoundedCornerShape(26.dp), elevation = 6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // A borderless field: the glass pane is the field's border, and
                // a second outline inside it looked like a nested box.
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 42.dp, max = 140.dp)
                        .padding(start = 10.dp, top = 10.dp, bottom = 10.dp),
                    decorationBox = { inner ->
                        if (value.isEmpty()) {
                            Text(
                                str(R.string.ui_input_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 10.dp, start = 10.dp),
                            )
                        }
                        inner()
                    },
                )
                FilledIconButton(
                    onClick = if (streaming) onStop else onSend,
                    enabled = enabled && (streaming || value.isNotBlank()),
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        if (streaming) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = str(if (streaming) R.string.ui_stop else R.string.ui_send),
                    )
                }
            }
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
) {
    var langOpen by remember { mutableStateOf(false) }
    val presets by vm.presets.collectAsStateWithLifecycle()
    val sandboxReady = sandbox is SandboxState.Ready

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Identity card first: it answers "what am I even talking to" before any
        // individual setting does, which is the question the old flat list of
        // rows never answered.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .glass(shape = RoundedCornerShape(24.dp), elevation = 4.dp, tint = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(46.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "\u03c0",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        provider.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        model.ifBlank { str(R.string.ui_not_set) },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        (keyHint ?: str(R.string.ui_not_set)) + "  ·  " +
                            (if (sandboxReady) str(R.string.ui_sandbox_ready)
                            else str(R.string.ui_not_set)),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sandboxReady) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        StaggeredGroup(0, str(R.string.ui_model_service)) {
            SettingsRow(
                icon = Icons.Filled.Memory,
                title = str(R.string.ui_choose_provider),
                value = provider.label,
                onClick = { onOpenSheet(Sheet.PROVIDER) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Filled.Refresh,
                title = str(R.string.ui_choose_model),
                value = model.ifBlank { str(R.string.ui_not_set) },
                monospace = true,
                onClick = { onOpenSheet(Sheet.MODEL) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Filled.Star,
                title = str(R.string.ui_saved_models),
                value = if (presets.isNotEmpty()) presets.joinToString(" · ").take(60)
                else str(R.string.ui_not_set),
                monospace = false,
                onClick = { onOpenSheet(Sheet.MODEL) },
            )
            if (provider.isCustom) {
                RowDivider()
                SettingsRow(
                    icon = Icons.Filled.Settings,
                    title = str(R.string.ui_endpoint),
                    value = vm.customBaseUrl.collectAsStateWithLifecycle().value
                        .ifBlank { str(R.string.ui_not_set) },
                    onClick = { onOpenSheet(Sheet.ENDPOINT) },
                )
            }
        }

        StaggeredGroup(1, str(R.string.ui_api_key)) {
            SettingsRow(
                icon = Icons.Filled.VpnKey,
                title = provider.keyEnv,
                value = keyHint ?: (
                    if (vm.hasApiKey.collectAsStateWithLifecycle().value) {
                        str(R.string.ui_key_saved)
                    } else {
                        str(R.string.ui_not_set)
                    }
                    ),
                onClick = { onOpenSheet(Sheet.KEY) },
            )
        }

        StaggeredGroup(2, str(R.string.ui_sandbox_tools)) {
            SettingsRow(
                icon = Icons.Filled.Folder,
                title = str(R.string.ui_linux_sandbox),
                value = when (sandbox) {
                    is SandboxState.Ready -> str(R.string.ui_sandbox_ready)
                    is SandboxState.Upgrading -> str(R.string.ui_sandbox_upgrading)
                    is SandboxState.Error -> str(R.string.ui_setup_failed)
                    is SandboxState.NotInstalled -> str(R.string.ui_not_set)
                    else -> str(R.string.ui_sandbox_checking)
                },
                onClick = { onOpenSheet(Sheet.SANDBOX) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Filled.Folder,
                title = str(R.string.ui_repo_path),
                value = vm.repoPath.collectAsStateWithLifecycle().value,
                monospace = true,
                onClick = { onOpenSheet(Sheet.FOLDER) },
            )
            RowDivider()
            SettingsRow(
                icon = Icons.Filled.Settings,
                title = str(R.string.ui_diagnostics),
                value = vm.diagnostic.collectAsStateWithLifecycle().value
                    ?.lineSequence()?.firstOrNull()
                    ?: str(R.string.ui_diagnostics_empty),
                onClick = { onOpenSheet(Sheet.DIAG) },
            )
        }

        StaggeredGroup(3, str(R.string.ui_about)) {
            SettingsRow(
                icon = Icons.Filled.Language,
                title = str(R.string.ui_language_system),
                value = language.displayName.ifBlank { str(R.string.ui_language_system) },
                onClick = { langOpen = true },
            )
        }

        Text(
            strf(R.string.ui_models_available, modelsCount) + "  ·  " + str(R.string.app_name),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, bottom = 20.dp),
        )
    }

    if (langOpen) {
        ModalBottomSheet(
        containerColor = sheetContainer(),
        shape = sheetShape(),
        onDismissRequest = { langOpen = false }) {
            SheetHeader(str(R.string.ui_language_system))
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

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glass(shape = RoundedCornerShape(20.dp), strength = 0.9f, elevation = 3.dp),
        ) {
            Column { content() }
        }
    }
}

/**
 * [SettingsGroup] that eases into the screen on open. Each group gets a larger
 * delay so the list "settles" top-to-bottom instead of popping in at once —
 * the transition the settings page was missing.
 */
@Composable
private fun StaggeredGroup(index: Int, title: String, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(260, delayMillis = index * 70)).togetherWith(
            slideInVertically(tween(260, delayMillis = index * 70)) { it / 6 },
        ),
    ) {
        SettingsGroup(title, content)
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.07f),
        modifier = Modifier.padding(start = 52.dp),
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    value: String,
    monospace: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "\u203a",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- sheets ---------------------------------------------------------------

@Composable
private fun SheetHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 10.dp),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f))
    Spacer(Modifier.height(6.dp))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSheet(
    current: AgentProvider,
    onPick: (AgentProvider) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        containerColor = sheetContainer(),
        shape = sheetShape(),
        onDismissRequest = onDismiss) {
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
    presets: List<String>,
    onPick: (String) -> Unit,
    onForget: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val fetching by vm.fetchingModels.collectAsStateWithLifecycle()
    val error by vm.modelFetchError.collectAsStateWithLifecycle()
    var manual by remember { mutableStateOf("") }
    val isCustom = vm.provider.collectAsStateWithLifecycle().value.isCustom

    ModalBottomSheet(
        containerColor = sheetContainer(),
        shape = sheetShape(),
        onDismissRequest = onDismiss) {
        SheetHeader(str(R.string.ui_choose_model))

        // Saved presets first: the whole point of keeping several is that the
        // ones you actually use sit at the top and are one tap away.
        AnimatedVisibility(
            visible = presets.isNotEmpty(),
            enter = fadeIn(tween(180)).togetherWith(slideInVertically(tween(180)) { it / 3 }),
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
                presets.forEach { id ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(id) }
                            .longClickable(onLongClick = { onForget(id); true })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (id == current) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Text(
                            id,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        if (id == current) {
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
                SheetRow(label = id, selected = id == current, monospace = true) { onPick(id) }
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
            keyboardActions = KeyboardActions(onDone = { if (manual.isNotBlank()) onPick(manual.trim()) }),
        )
        TextButton(
            onClick = { if (manual.isNotBlank()) onPick(manual.trim()) },
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
    ModalBottomSheet(
        containerColor = sheetContainer(),
        shape = sheetShape(),
        onDismissRequest = onDismiss) {
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
    ModalBottomSheet(
        containerColor = sheetContainer(),
        shape = sheetShape(),
        onDismissRequest = onDismiss) {
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
    val tokenSaved by vm.hasGitHubToken.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf("") }

    ModalBottomSheet(
        containerColor = sheetContainer(),
        shape = sheetShape(),
        onDismissRequest = onDismiss) {
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

        HorizontalDivider(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f),
            modifier = Modifier.padding(vertical = 8.dp),
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val detail by vm.diagnostic.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val body = detail ?: str(R.string.ui_diagnostics_empty)

    ModalBottomSheet(
        containerColor = sheetContainer(),
        shape = sheetShape(),
        onDismissRequest = onDismiss) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SandboxSheet(
    vm: AppViewModel,
    sandbox: SandboxState,
    onProvision: () -> Unit,
    onDismiss: () -> Unit,
) {
    val report by vm.sandboxReport.collectAsStateWithLifecycle()
    val busy by vm.sandboxBusy.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    // Probe on open: the whole point of this screen is showing what is really
    // there, and a cached answer would be exactly the stale reassurance the app
    // used to give.
    LaunchedEffect(Unit) { vm.refreshSandboxReport() }

    val autoInstall by vm.autoInstall.collectAsStateWithLifecycle()

    ModalBottomSheet(
        containerColor = sheetContainer(),
        shape = sheetShape(),
        onDismissRequest = onDismiss) {
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
                    vm.notify(if (it) "已开启自动安装" else "已关闭自动安装")
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
                when (sandbox) {
                    is SandboxState.Ready -> str(R.string.ui_sandbox_ready)
                    is SandboxState.Upgrading -> str(R.string.ui_sandbox_upgrading)
                    is SandboxState.Downloading -> str(R.string.ui_downloading_rootfs)
                    is SandboxState.Extracting -> str(R.string.ui_extracting_rootfs)
                    is SandboxState.Installing -> str(R.string.ui_installing)
                    is SandboxState.Error -> strf(R.string.ui_setup_failed, sandbox.message)
                    is SandboxState.NotInstalled -> str(R.string.ui_sandbox_provision_hint)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (sandbox is SandboxState.Error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )

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

            if (sandbox is SandboxState.NotInstalled || sandbox is SandboxState.Error) {
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
