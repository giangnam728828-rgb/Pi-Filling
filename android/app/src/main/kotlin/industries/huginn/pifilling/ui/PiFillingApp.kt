package industries.huginn.pifilling.ui

import android.content.res.Configuration
import java.util.Locale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import industries.huginn.pifilling.R
import industries.huginn.pifilling.runtime.AgentController.SessionState
import industries.huginn.pifilling.sandbox.AgentProvider
import industries.huginn.pifilling.sandbox.SandboxState

/**
 * Resolve a UI string from the *localised* context.
 *
 * Deliberately not `stringResource`: that helper goes through whatever context
 * or resources the running Compose version happens to read, which has changed
 * between releases. Reading [LocalContext] directly is the one thing guaranteed
 * to pick up the context provided by [PiFillingApp]'s language override.
 */
@Composable
@ReadOnlyComposable
private fun str(id: Int): String = LocalContext.current.getString(id)

@Composable
@ReadOnlyComposable
private fun strf(id: Int, vararg args: Any): String =
    LocalContext.current.getString(id, *args)

/**
 * Two screens, not one: a chat surface and a settings sheet. The previous build
 * stacked the provider picker, key entry, repo path and token *above* the
 * transcript, so the app opened as a configuration form with a chat box
 * squeezed underneath it. Settings are visited once; the chat is used every
 * time, so the chat is now what the app opens on.
 */
private enum class Screen { CHAT, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiFillingApp(vm: AppViewModel) {
    val hasKey by vm.hasApiKey.collectAsStateWithLifecycle()
    val provider by vm.provider.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val sandbox by vm.sandboxState.collectAsStateWithLifecycle()
    val session by vm.sessionState.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()
    val customBaseUrl by vm.customBaseUrl.collectAsStateWithLifecycle()
    val customApiShape by vm.customApiShape.collectAsStateWithLifecycle()

    var screen by remember { mutableStateOf(Screen.CHAT) }
    // Force settings on first run: there is no key yet, and a chat box that
    // silently does nothing is the worst possible first impression.
    LaunchedEffect(hasKey) { if (!hasKey) screen = Screen.SETTINGS }
    LaunchedEffect(provider) { if (!hasKey) screen = Screen.SETTINGS }

    val baseContext = LocalContext.current
    val localizedContext = remember(baseContext, language) {
        val tag = language.tag ?: return@remember baseContext
        val config = Configuration(baseContext.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        baseContext.createConfigurationContext(config)
    }

    CompositionLocalProvider(LocalContext provides localizedContext) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                if (screen == Screen.SETTINGS) str(R.string.ui_settings)
                                else str(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (screen == Screen.CHAT) {
                                Text(
                                    "${provider.label} · $model",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (screen == Screen.SETTINGS && hasKey) {
                            IconButton(onClick = { screen = Screen.CHAT }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.ui_back))
                            }
                        }
                    },
                    actions = {
                        if (screen == Screen.CHAT) {
                            TextButton(onClick = vm::resetChat) { Text(str(R.string.ui_new_chat)) }
                            IconButton(onClick = { screen = Screen.SETTINGS }) {
                                Icon(Icons.Filled.Settings, contentDescription = str(R.string.ui_settings))
                            }
                        } else {
                            LanguageMenu(language, vm::setLanguage)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when {
                    screen == Screen.SETTINGS -> SettingsScreen(
                        vm = vm,
                        provider = provider,
                        model = model,
                        baseUrl = customBaseUrl,
                        apiShape = customApiShape,
                        hasKey = hasKey,
                        sandbox = sandbox,
                    )
                    !hasKey -> SettingsPrompt(onOpen = { screen = Screen.SETTINGS })
                    sandbox is SandboxState.NotInstalled -> SandboxSetup(sandbox, vm::provision)
                    sandbox is SandboxState.Downloading || sandbox is SandboxState.Extracting ->
                        SandboxSetup(sandbox, vm::provision)
                    else -> ChatScreen(vm, session)
                }
            }
        }
    }
}

@Composable
private fun SettingsPrompt(onOpen: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(str(R.string.ui_welcome_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            str(R.string.ui_provider_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onOpen) { Text(str(R.string.ui_settings)) }
    }
}

@Composable
private fun LanguageMenu(current: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (current) {
        AppLanguage.SYSTEM -> str(R.string.ui_language_system)
        else -> current.displayName
    }
    Box {
        TextButton(onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppLanguage.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (option) {
                                AppLanguage.SYSTEM -> str(R.string.ui_language_system)
                                else -> option.displayName
                            },
                        )
                    },
                    onClick = { onSelect(option); expanded = false },
                )
            }
        }
    }
}

// ---- chat -----------------------------------------------------------------

@Composable
private fun ChatScreen(vm: AppViewModel, session: SessionState) {
    val transcript by vm.transcript.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val streaming = session is SessionState.Streaming
    val connecting = session is SessionState.Connecting || session is SessionState.Provisioning

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (transcript.isEmpty() && !connecting) {
                WelcomeState(onPick = { input = it })
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(transcript) { line -> MessageBubble(line) }
                }
            }
        }

        AnimatedVisibility(connecting) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    str(R.string.ui_connecting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (session is SessionState.Failed) {
            Text(
                strf(R.string.ui_failed, session.message),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Composer(
            value = input,
            onValueChange = { input = it },
            streaming = streaming,
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
private fun WelcomeState(onPick: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("π", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Text(str(R.string.ui_welcome_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            str(R.string.ui_welcome_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf(R.string.ui_quick_1, R.string.ui_quick_2, R.string.ui_quick_3).forEach { id ->
            AssistChip(onClick = { onPick(str(id)) }, label = { Text(str(id)) })
        }
    }
}

/** One transcript line, shaped by role so replies, tool chatter and errors read differently. */
@Composable
private fun MessageBubble(line: AppViewModel.TranscriptLine) {
    when (line.role) {
        "you" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(
                    line.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        "assistant" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Surface(
                shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(
                    line.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                line.text,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    streaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Column(Modifier.background(MaterialTheme.colorScheme.background)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(str(R.string.ui_input_placeholder)) },
                modifier = Modifier.weight(1f).heightIn(max = 140.dp),
                maxLines = 6,
                shape = RoundedCornerShape(22.dp),
                // Multi-line: this is a coding agent, and a prompt with a stack
                // trace or a snippet in it should not be flattened to one line.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
            )
            FilledIconButton(
                onClick = if (streaming) onStop else onSend,
                enabled = streaming || value.isNotBlank(),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    if (streaming) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = str(if (streaming) R.string.ui_stop else R.string.ui_send),
                )
            }
        }
    }
}

// ---- settings -------------------------------------------------------------

@Composable
private fun SettingsScreen(
    vm: AppViewModel,
    provider: AgentProvider,
    model: String,
    baseUrl: String,
    apiShape: String,
    hasKey: Boolean,
    sandbox: SandboxState,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ProviderSection(provider, model, vm::setProvider, vm::setModel)

        if (provider.isCustom) {
            CustomEndpointSection(baseUrl, apiShape, vm::setCustomBaseUrl, vm::setCustomApiShape)
        }

        ApiKeySection(provider, hasKey, vm::saveApiKey, vm::clearApiKey)

        SandboxSection(sandbox, vm.provisionedTools, vm::provision)

        VoiceSection(vm)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
private fun ProviderSection(
    provider: AgentProvider,
    model: String,
    onProviderChange: (AgentProvider) -> Unit,
    onModelChange: (String) -> Unit,
) {
    SectionCard(str(R.string.ui_provider)) {
        Text(
            str(R.string.ui_provider_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Three per row: all seven chips on one line overflow a phone width.
        AgentProvider.entries.toList().chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { candidate ->
                    FilterChip(
                        selected = candidate == provider,
                        onClick = { onProviderChange(candidate) },
                        label = { Text(candidate.label, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }
        if (provider.isCustom) {
            OutlinedTextField(
                value = model,
                onValueChange = onModelChange,
                label = { Text(str(R.string.ui_custom_model_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ModelPicker(model, provider.models, onModelChange)
        }
    }
}

@Composable
private fun CustomEndpointSection(
    baseUrl: String,
    apiShape: String,
    onBaseUrlChange: (String) -> Unit,
    onApiShapeChange: (String) -> Unit,
) {
    SectionCard(str(R.string.ui_custom_endpoint)) {
        Text(
            str(R.string.ui_custom_endpoint_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            label = { Text(str(R.string.ui_base_url_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CustomProviderSettings.SHAPES.forEach { shape ->
                FilterChip(
                    selected = shape == apiShape,
                    onClick = { onApiShapeChange(shape) },
                    label = {
                        Text(
                            if (shape == CustomProviderSettings.OPENAI_COMPLETIONS) {
                                str(R.string.ui_shape_openai)
                            } else {
                                str(R.string.ui_shape_anthropic)
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelPicker(model: String, options: List<String>, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(model, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, fontFamily = FontFamily.Monospace) },
                    onClick = { onPick(option); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun ApiKeySection(
    provider: AgentProvider,
    hasKey: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    // Keyed on the provider so switching clears a half-typed credential instead
    // of carrying it across to a different service.
    var key by remember(provider) { mutableStateOf("") }

    SectionCard(strf(R.string.ui_api_key_title, provider.label)) {
        Text(
            str(R.string.ui_key_storage_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hasKey) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(str(R.string.ui_key_saved), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onClear) { Text(str(R.string.ui_forget_key)) }
            }
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(provider.keyHint) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (key.isNotBlank()) { onSave(key); key = "" } }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSave(key); key = "" },
            enabled = key.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(str(R.string.ui_save_key)) }
    }
}

@Composable
private fun SandboxSection(state: SandboxState, tools: String, onProvision: () -> Unit) {
    SectionCard(str(R.string.ui_linux_sandbox)) {
        when (state) {
            is SandboxState.NotInstalled -> {
                Text(str(R.string.ui_sandbox_provision_hint), style = MaterialTheme.typography.bodySmall)
                Button(onClick = onProvision, modifier = Modifier.fillMaxWidth()) {
                    Text(str(R.string.ui_setup_sandbox))
                }
            }
            is SandboxState.Upgrading -> ProgressRow(str(R.string.ui_sandbox_upgrading))
            is SandboxState.Downloading -> {
                Text(str(R.string.ui_downloading_rootfs), style = MaterialTheme.typography.bodySmall)
                val frac = state.fraction
                if (frac != null) {
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            is SandboxState.Extracting -> ProgressRow(str(R.string.ui_extracting_rootfs))
            is SandboxState.Installing -> ProgressRow(str(R.string.ui_installing))
            is SandboxState.Ready -> {
                Text(str(R.string.ui_sandbox_ready), style = MaterialTheme.typography.bodyMedium)
                // Listing what is actually installed is the honest answer to
                // "did the tools get preinstalled?" — no log reading required.
                Text(
                    str(R.string.ui_tools_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    tools,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is SandboxState.Error -> {
                Text(
                    strf(R.string.ui_setup_failed, state.message),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onProvision, modifier = Modifier.fillMaxWidth()) {
                    Text(str(R.string.ui_retry))
                }
            }
        }
    }
}

@Composable
private fun VoiceSection(vm: AppViewModel) {
    val voiceOn by vm.voiceEnabled.collectAsStateWithLifecycle()
    SectionCard(str(R.string.ui_voice)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Switch(checked = voiceOn, onCheckedChange = vm::setVoiceEnabled)
            Text(
                str(if (voiceOn) R.string.ui_voice_on else R.string.ui_voice_off),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ProgressRow(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SandboxSetup(state: SandboxState, onProvision: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Text(str(R.string.ui_linux_sandbox), style = MaterialTheme.typography.titleMedium)
        when (state) {
            is SandboxState.NotInstalled -> {
                Text(str(R.string.ui_sandbox_provision_hint), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onProvision) { Text(str(R.string.ui_setup_sandbox)) }
            }
            is SandboxState.Downloading -> {
                Text(str(R.string.ui_downloading_rootfs), style = MaterialTheme.typography.bodyMedium)
                val frac = state.fraction
                if (frac != null) {
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            is SandboxState.Extracting -> ProgressRow(str(R.string.ui_extracting_rootfs))
            is SandboxState.Installing -> ProgressRow(str(R.string.ui_installing))
            is SandboxState.Upgrading -> ProgressRow(str(R.string.ui_sandbox_upgrading))
            else -> ProgressRow(str(R.string.ui_sandbox_checking))
        }
    }
}
