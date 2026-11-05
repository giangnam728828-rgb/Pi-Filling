package industries.huginn.pifilling.ui

import android.content.Context

/**
 * Runtime behaviour the user is allowed to decide for themselves.
 *
 * [autoInstall] exists because provisioning was previously invisible and
 * unconditional: the app announced that tools were bundled, installed them (or
 * failed to, silently) in the background, and gave no way to see or refuse the
 * ~350 MB of traffic involved. On a metered connection that is not a decision an
 * app should make on someone's behalf.
 */
class RuntimeSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Install/repair sandbox contents automatically at startup. */
    var autoInstall: Boolean
        get() = prefs.getBoolean(KEY_AUTO_INSTALL, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_INSTALL, value).apply() }

    private companion object {
        const val PREFS = "pifilling_runtime"
        const val KEY_AUTO_INSTALL = "auto_install"
    }
}
