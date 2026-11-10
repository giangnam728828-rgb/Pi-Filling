package industries.huginn.pifilling.phone

import android.content.Context

/**
 * Which phone-control capabilities the agent is allowed to use.
 *
 * The agent may *ask* for a capability by name, but the user owns the decision
 * of what this app may do on the phone — the same rule that keeps voice behind
 * a master switch. Every capability defaults to **on** here because each one
 * already ends in an OS-owned confirmation (alarm/calendar/open intents) or a
 * dismissible notification; the switches exist so a user who wants none of it
 * can say so, and a session started afterwards simply does not offer the tools.
 *
 * Ordinary SharedPreferences, not the encrypted store: none of this is a
 * secret, and it is a property of the phone rather than of a sandbox.
 */
class PhoneSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The capability names, paired with the flag key backing each. The list is
     * the single source of truth for both the settings screen and the registry
     * filter in AgentController, so a new capability cannot silently miss one.
     */
    val capabilities: List<Capability> = listOf(
        Capability("phone.notify", "notify", default = true),
        Capability("phone.open", "open", default = true),
        Capability("phone.alarm", "alarm", default = true),
        Capability("phone.timer", "timer", default = true),
        Capability("phone.calendar", "calendar", default = true),
    )

    data class Capability(val name: String, val key: String, val default: Boolean)

    fun enabled(capabilityName: String): Boolean =
        capabilities.firstOrNull { it.name == capabilityName }?.let { cap ->
            prefs.getBoolean(cap.key, cap.default)
        } ?: false

    fun setEnabled(capabilityName: String, enabled: Boolean) {
        capabilities.firstOrNull { it.name == capabilityName }?.let { cap ->
            prefs.edit().putBoolean(cap.key, enabled).apply()
        }
    }

    /** How many of the phone capabilities are switched on, for a status badge. */
    fun enabledCount(): Int = capabilities.count { enabled(it.name) }

    private companion object {
        const val PREFS = "pifilling_phone"
    }
}
