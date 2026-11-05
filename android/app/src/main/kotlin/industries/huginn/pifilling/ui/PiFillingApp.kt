package industries.huginn.pifilling.ui

import android.content.res.Configuration
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
 * between releases. Reading [LocalContext] directly is the one thing that is
 * guaranteed to pick up the context provided by [PiFillingApp]'s language
 * override, on every version.
 */
@Composable
@ReadOnlyComposable
private fun str(id: Int): String = LocalContext.current.getString(id)

@Composable
@ReadOnlyComposable
private fun strf(id: Int, vararg args: Any): String =
    LocalContext.current.getString(id, *args)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiFillingApp(vm: AppViewModel) {
    val hasKey by vm.hasApiKey.collectAsStateWithLifecycle()
    val provider by vm.provider.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val sandbox by vm.sandboxState.collectAsStateWithLifecycle()
    val session by vm.sessionState.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()

    // Per-app language override without recreating the Activity: build a Context
    // whose configuration carries the chosen locale and publish it as LocalContext,
    // so every str() resolves against it. SYSTEM passes the ambient context
    // through, which is what makes the app follow the phone's own language.
    val baseContext = LocalContext.current
    val localizedContext = remember(baseContext, language) {
        val tag = language.tag ?: return@remember baseContext
        val config = Configuration(baseContext.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        baseContext.createConfigurationContext(config)
    }

    CompositionLocalProvider(LocalContext provides localizedContext) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                str(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                model,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = { LanguageMenu(language, vm::setLanguage) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                when {
                    !hasKey -> ApiKeyEntry(
                        provider = provider,
                        model = model,
                        onProviderChange = vm::setProvider,
                        onModelChange = vm::setModel,
                        onSave = vm::saveApiKey,
                    )
                    sandbox !is SandboxState.Ready -> SandboxSetup(sandbox, onProvision = vm::provision)
                    else -> SessionScreen(vm, session)
                }
            }
        }
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
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** A titled card. The first build drew everything as one flat column, which read as an unfinished form. */
@Composable
private fun Section(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
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
                    onClick = {
                        onPick(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ApiKeyEntry(
    provider: AgentProvider,
    model: String,
    onProviderChange: (AgentProvider) -> Unit,
    onModelChange: (String) -> Unit,
    onSave: (String) -> Unit,
) {
    // Keyed on the provider so switching clears any half-typed credential rather
    // than carrying it across to a different service.
    var key by remember(provider) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Section(title = str(R.string.ui_provider)) {
            Text(
                str(R.string.ui_provider_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Two rows of chips rather than one long line: six providers do not
            // fit a phone width and the original Row clipped the last one.
            // `entries` is an EnumEntries, which has no `chunked`; materialise it
            // as a list first.
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
        }

        Section(title = str(R.string.ui_model)) {
            ModelPicker(model, provider.models, onModelChange)
        }

        Section(title = strf(R.string.ui_api_key_title, provider.label)) {
            Text(
                str(R.string.ui_key_storage_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(provider.keyHint) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSave(key) },
                enabled = key.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(str(R.string.ui_save_key)) }
        }
    }
}

@Composable
private fun SandboxSetup(state: SandboxState, onProvision: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Section(title = str(R.string.ui_linux_sandbox)) {
            when (state) {
                is SandboxState.NotInstalled -> {
                    Text(str(R.string.ui_sandbox_provision_hint))
                    Button(onClick = onProvision, modifier = Modifier.fillMaxWidth()) {
                        Text(str(R.string.ui_setup_sandbox))
                    }
                }
                is SandboxState.Downloading -> {
                    Text(str(R.string.ui_downloading_rootfs))
                    val frac = state.fraction
                    if (frac != null) {
                        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                is SandboxState.Extracting -> ProgressRow(str(R.string.ui_extracting_rootfs))
                is SandboxState.Installing -> ProgressRow(strf(R.string.ui_installing, state.message))
                is SandboxState.Ready -> Text(str(R.string.ui_sandbox_ready))
                is SandboxState.Error -> {
                    Text(
                        strf(R.string.ui_setup_failed, state.message),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onProvision, modifier = Modifier.fillMaxWidth()) {
                        Text(str(R.string.ui_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressRow(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** One transcript line, styled by role so the agent's prose and tool chatter are distinguishable at a glance. */
@Composable
private fun TranscriptRow(role: String, text: String, youPrefix: String) {
    when (role) {
        "you" -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Card(
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Text(
                    text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        "system" -> Text(
            text,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Text(
            text,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SessionScreen(vm: AppViewModel, session: SessionState) {
    val transcript by vm.transcript.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    // Default to a repo path inside the sandbox; a production UI lets the user pick/clone one.
    var repoPath by remember { mutableStateOf("/root/repo") }
    val hasStoredToken by vm.hasGitHubToken.collectAsStateWithLifecycle()
    val voiceOn by vm.voiceEnabled.collectAsStateWithLifecycle()
    var gitHubToken by remember { mutableStateOf("") }
    val youPrefix = str(R.string.ui_you_prefix)

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        // Reachable in every state, deliberately: speech could not otherwise be
        // switched off while the agent was speaking, which is the one moment
        // someone actually reaches for it.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        ) {
            Switch(checked = voiceOn, onCheckedChange = vm::setVoiceEnabled)
            Text(
                str(if (voiceOn) R.string.ui_voice_on else R.string.ui_voice_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (session) {
            is SessionState.Idle, is SessionState.Failed -> {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Section(title = str(R.string.ui_repo_path)) {
                        OutlinedTextField(
                            value = repoPath,
                            onValueChange = { repoPath = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // Optional: only `git push` needs it, so a session that never
                        // pushes is not blocked on a credential it will not use.
                        OutlinedTextField(
                            value = gitHubToken,
                            onValueChange = { gitHubToken = it },
                            label = {
                                Text(
                                    str(
                                        if (hasStoredToken) R.string.ui_github_token_saved
                                        else R.string.ui_github_token_hint,
                                    ),
                                )
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    vm.saveGitHubToken(gitHubToken)
                                    gitHubToken = ""
                                },
                                enabled = gitHubToken.isNotBlank(),
                            ) { Text(str(R.string.ui_save_token)) }
                            if (hasStoredToken) {
                                TextButton(onClick = vm::clearGitHubToken) {
                                    Text(str(R.string.ui_forget_token))
                                }
                            }
                        }
                    }
                    Button(
                        onClick = { vm.startSession(repoPath) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(str(R.string.ui_start_session)) }
                    if (session is SessionState.Failed) {
                        Text(
                            strf(R.string.ui_failed, session.message),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            is SessionState.Provisioning, is SessionState.Connecting ->
                Section(null) { ProgressRow(str(R.string.ui_connecting)) }

            is SessionState.Ready, is SessionState.Streaming -> {
                if (transcript.isEmpty()) {
                    Text(
                        str(R.string.ui_transcript_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(transcript) { line ->
                        TranscriptRow(line.role, line.text, youPrefix)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text(str(R.string.ui_ask_placeholder)) },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                    )
                    if (session is SessionState.Streaming) {
                        OutlinedButton(onClick = vm::abort) { Text(str(R.string.ui_stop)) }
                    } else {
                        Button(
                            onClick = { vm.send(input); input = "" },
                            enabled = input.isNotBlank(),
                        ) { Text(str(R.string.ui_send)) }
                    }
                }
                TextButton(onClick = vm::endSession) { Text(str(R.string.ui_end_session)) }
            }
        }
    }
}
