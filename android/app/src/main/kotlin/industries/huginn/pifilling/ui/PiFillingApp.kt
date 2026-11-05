package industries.huginn.pifilling.ui

import android.content.res.Configuration
import java.util.Locale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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

/** Format variant of [str], for strings carrying `%1$s` placeholders. */
@Composable
@ReadOnlyComposable
private fun strf(id: Int, vararg args: Any): String =
    LocalContext.current.getString(id, *args)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiFillingApp(vm: AppViewModel) {
    val hasKey by vm.hasApiKey.collectAsStateWithLifecycle()
    val provider by vm.provider.collectAsStateWithLifecycle()
    val sandbox by vm.sandboxState.collectAsStateWithLifecycle()
    val session by vm.sessionState.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()

    // A per-app language override without recreating the Activity: build a
    // Context whose configuration carries the chosen locale and publish it as
    // LocalContext, so every str() below resolves against it. [AppLanguage.SYSTEM]
    // passes the ambient context straight through, which is what makes the app
    // follow the phone's own language.
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
                    title = { Text(str(R.string.app_name)) },
                    actions = { LanguageMenu(language, vm::setLanguage) },
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .fillMaxSize(),
            ) {
                when {
                    !hasKey -> ApiKeyEntry(
                        provider = provider,
                        onProviderChange = vm::setProvider,
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
        // The active choice is shown in its own language so the button stays
        // legible even to someone who cannot read the current UI language.
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

@Composable
private fun ApiKeyEntry(
    provider: AgentProvider,
    onProviderChange: (AgentProvider) -> Unit,
    onSave: (String) -> Unit,
) {
    // Keyed on the provider so switching clears any half-typed credential
    // rather than carrying it across to a different service.
    var key by remember(provider) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(str(R.string.ui_provider), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentProvider.entries.forEach { candidate ->
                if (candidate == provider) {
                    Button(onClick = {}) { Text(candidate.label) }
                } else {
                    OutlinedButton(onClick = { onProviderChange(candidate) }) {
                        Text(candidate.label)
                    }
                }
            }
        }
        Text(
            strf(R.string.ui_api_key_title, provider.label),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            str(R.string.ui_key_storage_note),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(provider.keyHint) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSave(key) }, enabled = key.isNotBlank()) {
            Text(str(R.string.ui_save_key))
        }
    }
}

@Composable
private fun SandboxSetup(state: SandboxState, onProvision: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(str(R.string.ui_linux_sandbox), style = MaterialTheme.typography.titleMedium)
        when (state) {
            is SandboxState.NotInstalled -> {
                Text(str(R.string.ui_sandbox_provision_hint))
                Button(onClick = onProvision) { Text(str(R.string.ui_setup_sandbox)) }
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
                )
                Button(onClick = onProvision) { Text(str(R.string.ui_retry)) }
            }
        }
    }
}

@Composable
private fun ProgressRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text(label)
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

    Column(modifier = Modifier.fillMaxSize()) {
        // Reachable in every state, deliberately. It was first written into the
        // idle branch only, which meant speech could not be switched off while
        // the agent was speaking — the one moment someone actually reaches for
        // it. The agent may toggle auto-speak but never this; that stays a
        // person's decision, and turning it off stops whatever is mid-utterance.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Switch(checked = voiceOn, onCheckedChange = vm::setVoiceEnabled)
            Text(
                str(if (voiceOn) R.string.ui_voice_on else R.string.ui_voice_off),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when (session) {
            is SessionState.Idle, is SessionState.Failed -> {
                OutlinedTextField(
                    value = repoPath,
                    onValueChange = { repoPath = it },
                    label = { Text(str(R.string.ui_repo_path)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                // Optional: only `git push` needs it. Kept here rather than behind
                // the key-entry gate so a session that never pushes is not blocked
                // on a credential it will not use.
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
                Spacer(Modifier.height(4.dp))
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
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.startSession(repoPath) }, modifier = Modifier.fillMaxWidth()) {
                    Text(str(R.string.ui_start_session))
                }
                if (session is SessionState.Failed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        strf(R.string.ui_failed, session.message),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            is SessionState.Provisioning, is SessionState.Connecting ->
                ProgressRow(str(R.string.ui_connecting))
            is SessionState.Ready, is SessionState.Streaming -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(transcript) { line ->
                        Text(
                            text = if (line.role == "you") "$youPrefix ${line.text}" else line.text,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().imePadding(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text(str(R.string.ui_ask_placeholder)) },
                        modifier = Modifier.weight(1f),
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
