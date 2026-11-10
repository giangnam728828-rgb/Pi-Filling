package industries.huginn.pifilling.ui

import android.content.Context

/**
 * Which colour scheme the app should draw in.
 *
 * [SYSTEM] is the default and means "whatever the phone is set to". The explicit
 * options exist because the two are genuinely different choices: someone may
 * keep the phone in dark mode but want this app light, or the reverse.
 *
 * Lives next to [LocaleSettings] and follows the same shape — a UI preference is
 * not a secret and belongs to the phone, not to a sandbox, so it survives
 * rebuilding the rootfs.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * Persists the theme choice. Read once when the ViewModel is built and applied
 * by PiFillingTheme, which also feeds [LocalPiFillingDark] so that the glass
 * surfaces stop guessing from the system setting.
 */
class ThemeSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var mode: ThemeMode
        get() = ThemeMode.fromName(prefs.getString(KEY_MODE, null))
        set(value) {
            prefs.edit().putString(KEY_MODE, value.name).apply()
        }

    private companion object {
        const val PREFS = "pifilling_theme"
        const val KEY_MODE = "mode"
    }
}
