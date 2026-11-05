package industries.huginn.pifilling.ui

import android.content.Context

/**
 * Which language the interface should be drawn in.
 *
 * [SYSTEM] is the default and means "whatever the phone is set to" — the app
 * then behaves like any other localised app, because the resources carry an
 * English default and a Chinese translation. The explicit options exist because
 * a lot of people run their phone in one language but want this particular app
 * in another; forcing a device-wide locale change for one app is not a
 * reasonable thing to ask of them.
 */
enum class AppLanguage(
    /** BCP-47 tag to force, or null to follow the system. */
    val tag: String?,
    /** Name in its own language, so the menu is readable whichever one is active. */
    val displayName: String,
) {
    SYSTEM(null, ""),
    CHINESE("zh-CN", "中文"),
    ENGLISH("en", "English"),
    ;

    companion object {
        fun fromName(name: String?): AppLanguage =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * Persists the language choice. Ordinary SharedPreferences, matching
 * [industries.huginn.pifilling.voice.VoiceSettings]: a language preference is
 * not a secret, and it belongs to the phone rather than to a sandbox, so it
 * survives rebuilding the rootfs.
 */
class LocaleSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var language: AppLanguage
        get() = AppLanguage.fromName(prefs.getString(KEY_LANGUAGE, null))
        set(value) {
            prefs.edit().putString(KEY_LANGUAGE, value.name).apply()
        }

    private companion object {
        const val PREFS = "pifilling_locale"
        const val KEY_LANGUAGE = "language"
    }
}
