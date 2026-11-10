package industries.huginn.pifilling

import android.app.Application
import android.content.Context
import java.io.File

/**
 * Writes the last fatal exception to internal storage and hands it back on the
 * next launch.
 *
 * Why this exists: this environment has no adb and no logcat — the phone is a
 * proot container with no way to read another app's log. When the app dies on
 * startup that leaves nothing to debug from, so the app records its own crash
 * and shows it on the next start. The report is deliberately rendered with
 * plain android.widget views rather than Compose: if Compose (or the theme, or
 * a ViewModel) is what is crashing, a Compose-based report screen would crash
 * the same way and tell us nothing.
 */
object CrashReport {

    private const val FILE_NAME = "last-crash.txt"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** Call as early as possible in Application.onCreate. */
    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val report = describe(app, thread, error)
            runCatching { file(app).writeText(report) }
            // Also write somewhere retrievable. The app's private directory is
            // not readable from a host shell on this device, so a crash report
            // that only lives there cannot be quoted back without the user
            // transcribing it by hand.
            runCatching {
                val dir = File("/sdcard/Download/DSH-Go")
                if (dir.exists() || dir.mkdirs()) {
                    val stamp = java.text.SimpleDateFormat(
                        "yyyyMMdd-HHmmss", java.util.Locale.US,
                    ).format(java.util.Date())
                    File(dir, "pifilling-crash-$stamp.txt").writeText(report)
                }
            }
            // Let the platform do its normal thing (kill the process, report to
            // the store), but only after the writes above have landed.
            previous?.uncaughtException(thread, error)
        }
    }

    private fun describe(app: Application, thread: Thread, error: Throwable): String = buildString {
        appendLine("Pi-Filling crashed")
        appendLine()
        appendLine("version:  ${versionName(app)}")
        appendLine("thread:   ${thread.name}")
        appendLine()
        appendLine(error.stackTraceToString())
        var cause = error.cause
        while (cause != null && cause !== error) {
            appendLine("Caused by:")
            appendLine(cause.stackTraceToString())
            cause = cause.cause
        }
    }

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    /** The previous crash, if the last run died. */
    fun read(context: Context): String? =
        file(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        file(context).delete()
    }
}

/**
 * The screen shown instead of the app when the previous launch crashed.
 *
 * Plain views, scrollable, selectable text so the report can be copied out by
 * long-pressing it.
 */
class CrashReportView(context: Context, text: String, onDismiss: () -> Unit) :
    android.widget.ScrollView(context) {

    init {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val column = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val body = android.widget.TextView(context).apply {
            this.text = text
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        val dismiss = android.widget.Button(context).apply {
            this.text = "清除并重试"
            setOnClickListener { onDismiss() }
        }

        column.addView(body)
        column.addView(dismiss)
        addView(column)
    }
}
