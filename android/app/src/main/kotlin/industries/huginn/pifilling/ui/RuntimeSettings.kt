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

    /**
     * Which apk mirror leads the guest's `etc/apk/repositories`.
     *
     * Stores a mirror *base URL* (no branch suffix). An empty string means
     * "follow the locale guess": zh-locale devices lead with the Tsinghua
     * mirror, everything else uses the CDN order. Storing a URL rather than an
     * enum index lets a user paste a mirror we have never heard of, which
     * matters when their carrier blocks the ones we do know.
     */
    var apkMirror: String
        get() = prefs.getString(KEY_APK_MIRROR, "") ?: ""
        set(value) { prefs.edit().putString(KEY_APK_MIRROR, value.trim()).apply() }

    /** True when the user has not picked a mirror and we fall back to locale. */
    val apkMirrorIsAutomatic: Boolean get() = apkMirror.isEmpty()

    private companion object {
        const val PREFS = "pifilling_runtime"
        const val KEY_AUTO_INSTALL = "auto_install"
        const val KEY_APK_MIRROR = "apk_mirror"
    }
}
