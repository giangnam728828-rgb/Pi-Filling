package industries.huginn.pifilling

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import industries.huginn.pifilling.runtime.AgentController
import industries.huginn.pifilling.ui.AppLanguage
import industries.huginn.pifilling.ui.AppViewModel
import industries.huginn.pifilling.ui.ThemeMode
import industries.huginn.pifilling.ui.PiFillingApp
import industries.huginn.pifilling.ui.theme.PiFillingTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    private val agent: AgentController
        get() = (application as PiFillingApplication).agent

    // The ViewModel lives here, not inside setContent, so the Activity-result
    // callbacks below can reach it.
    private val vm: AppViewModel by viewModels { AppViewModel.factory(agent) }

    // ---- voice input -------------------------------------------------------
    // These must be registered on the Activity: a launcher created inside the
    // composition threw "No ActivityResultRegistryOwner was provided via
    // LocalActivityResultRegistryOwner" and killed the app on first frame.
    // Registering at field level (as the notification permission above does)
    // is evaluated in onCreate, before the activity is STARTED, which is the
    // window Activity-result registration requires.
    private val speechRecognizer =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val spoken = result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                // The composer appends it to whatever is already typed.
                vm.onSpeechResult(spoken)
            }
        }

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchRecognizer()
            } else {
                vm.notifyRes(R.string.ui_mic_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // If the previous launch died, show why instead of re-entering the same
        // crash. Plain views only — the normal path (Compose, theme, ViewModel)
        // is exactly what we cannot trust here.
        val previousCrash = CrashReport.read(this)
        if (previousCrash != null) {
            setContentView(
                CrashReportView(this, previousCrash) {
                    CrashReport.clear(this)
                    recreate()
                },
            )
            return
        }

        maybeRequestNotificationPermission()

        setContent {
            val mode by vm.themeMode.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM ->
                    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                        Configuration.UI_MODE_NIGHT_YES
            }

            // The status and navigation bar icons are drawn by the system, so a
            // light theme on a dark phone would otherwise leave white icons on a
            // light background. Re-applying edge-to-edge with the resolved mode
            // keeps them legible in all three settings.
            LaunchedEffect(dark) {
                val scrim = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(scrim, scrim) { dark },
                    navigationBarStyle = SystemBarStyle.auto(scrim, scrim) { dark },
                )
            }

            PiFillingTheme(darkTheme = dark) {
                PiFillingApp(vm = vm, onVoiceInput = ::startVoiceInput)
            }
        }
    }

    /**
     * Re-assert the daemon every time the activity is foregrounded. onCreate
     * alone is not enough: aggressive OEM battery managers kill the FGS while
     * the activity is backgrounded. startForegroundService is idempotent when
     * the service is already up. (Kai's MainActivity.onStart pattern.)
     */
    override fun onStart() {
        super.onStart()
        agent.reassertDaemonIfActive(this)
    }

    /**
     * Last chance to save the conversation: after this the process may be
     * killed without further notice, and the throttled writer may not have run.
     */
    override fun onStop() {
        vm.flushHistory()
        super.onStop()
    }

    private fun startVoiceInput() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchRecognizer()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchRecognizer() {
        val langTag = when (vm.language.value) {
            AppLanguage.CHINESE -> "zh-CN"
            AppLanguage.CHINESE_TRADITIONAL -> "zh-TW"
            AppLanguage.ENGLISH -> "en-US"
            AppLanguage.SYSTEM -> null
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            langTag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            speechRecognizer.launch(intent)
        } catch (_: Exception) {
            // No recognizer installed, or the OEM's is disabled.
            vm.notifyRes(R.string.ui_mic_unavailable)
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
