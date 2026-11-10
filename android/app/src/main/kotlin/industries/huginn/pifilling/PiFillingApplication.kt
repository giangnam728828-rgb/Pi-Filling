package industries.huginn.pifilling

import android.app.Application
import industries.huginn.pifilling.runtime.AgentController
import industries.huginn.pifilling.sandbox.LinuxSandboxManager
import industries.huginn.pifilling.storage.SecureKeyStore

/**
 * Holds the process-lifetime singletons. A DI framework (Koin/Hilt) is overkill
 * for the v1 surface; lazy properties on Application keep wiring obvious. Swap to
 * DI if the graph grows.
 */
class PiFillingApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // First thing in the process: a crash before this point leaves no trace
        // and this build has no logcat to fall back on.
        CrashReport.install(this)
    }

    val sandbox: LinuxSandboxManager by lazy { LinuxSandboxManager(this) }
    val keyStore: SecureKeyStore by lazy { SecureKeyStore(this) }
    val agent: AgentController by lazy { AgentController(this, sandbox, keyStore) }
}
