package industries.huginn.pifilling.phone

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract

import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import industries.huginn.pifilling.wrapper.HostCapability
import industries.huginn.pifilling.wrapper.HostCapabilityRefusal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull

import kotlinx.serialization.json.put
import kotlinx.serialization.json.putInt
import kotlinx.serialization.json.putJson
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * Phone control, as host capabilities.
 *
 * The agent sits in a proot rootfs with no route to Android, so "operate the
 * phone" is a request over the JSONL host channel and the OS is the final
 * arbiter: alarms, calendar events and opened apps all surface in the system's
 * own UIs where the user confirms. That is deliberate — the capability set is
 * small, stock, and reversible, and a refusal (ungranted permission, missing
 * app) is a normal outcome the agent is told to move past, not a crash.
 *
 * This is the "run builds, then open the result / remind me" half of the
 * promise: the sandbox does the coding, and these five actions let the agent
 * reach back out to the device without any new privileges.
 */
class PhoneCapabilities(private val context: Context) {

    /** All capabilities, ready to register in the allow-list. */
    fun all(): List<HostCapability> = listOf(Notify(), Open(), Alarm(), Timer(), Calendar())

    private fun notifications(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val channel = NotificationChannel(CHANNEL_ID, "Agent", NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = "Notifications posted by the Pi-Filling agent"
            notifications().createNotificationChannel(channel)
        }
    }

    /** POST_NOTIFICATIONS is runtime on API 33+. */
    private fun requirePostNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                throw HostCapabilityRefusal(
                    HostCapabilityRefusal.NOT_PERMITTED,
                    "notifications are not enabled for this app",
                )
            }
        }
    }

    private fun str(params: JsonObject, key: String): String =
        (params[key] as? JsonPrimitive)?.contentOrNullSafe()?.trim().orEmpty()

    private fun int(params: JsonObject, key: String): Int? =
        (params[key] as? JsonPrimitive)?.intOrNull

    private fun JsonPrimitive.contentOrNullSafe(): String? = runCatching { content }.getOrNull()

    private inner class Notify : HostCapability {
        override val name = "phone.notify"
        override suspend fun invoke(params: JsonObject): JsonObject {
            val title = str(params, "title")
            val text = str(params, "text")
            if (title.isEmpty() || text.isEmpty()) {
                throw HostCapabilityRefusal(HostCapabilityRefusal.NOT_PERMITTED, "title and text are required")
            }
            requirePostNotifications()
            ensureChannel()
            val id = System.currentTimeMillis().toInt()
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            notifications().notify(id, notification)
            return buildJsonObject {
                put("posted", true)
                putInt("id", id)
            }
        }
    }

    private inner class Open : HostCapability {
        override val name = "phone.open"
        override suspend fun invoke(params: JsonObject): JsonObject {
            val url = str(params, "url")
            val app = str(params, "app")
            if (url.isNotEmpty()) return openUrl(url)
            if (app.isNotEmpty()) return openApp(app)
            throw HostCapabilityRefusal(HostCapabilityRefusal.NOT_PERMITTED, "give a url or an app")
        }

        private fun openUrl(url: String): JsonObject {
            val uri = runCatching { Uri.parse(url) }.getOrNull()
                ?: throw HostCapabilityRefusal(HostCapabilityRefusal.NOT_PERMITTED, "unparseable url: $url")
            if (uri.scheme !in setOf("http", "https", "app", "custom")) {
                throw HostCapabilityRefusal(HostCapabilityRefusal.NOT_PERMITTED, "only http(s)/app links are opened")
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (context.packageManager.resolveActivity(intent, 0) == null) {
                throw HostCapabilityRefusal(HostCapabilityRefusal.NOT_PERMITTED, "no app can open $url")
            }
            context.startActivity(intent)
            return buildJsonObject { put("opened", "url"); putJson("url", kotlinx.serialization.json.JsonPrimitive(url)) }
        }

        private fun openApp(app: String): JsonObject {
            val pm = context.packageManager
            // By package name first (cheap), then by visible label.
            val byPackage = runCatching { pm.getApplicationInfo(app, 0) }.getOrNull()
            val target = if (byPackage != null) byPackage else {
                pm.getInstalledApplications(0).firstOrNull { info ->
                    runCatching { info.loadLabel(pm).toString() }.getOrNull()?.equals(app, true) == true
                }
            }
            if (target == null) {
                throw HostCapabilityRefusal(HostCapabilityRefusal.NOT_PERMITTED, "app not found: $app")
            }
            val intent = pm.getLaunchIntentForPackage(target.packageName)
                ?: throw HostCapabilityRefusal(HostCapabilityRefusal.NOT_PERMITTED, "no launch intent for ${target.packageName}")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return buildJsonObject {
                put("opened", "app")
                putJson("package", kotlinx.serialization.json.JsonPrimitive(target.packageName))
            }
        }
    }

    private inner class Alarm : HostCapability {
        override val name = "phone.alarm"
        override suspend fun invoke(params: JsonObject): JsonObject {
            val hour = int(params, "hour") ?: throw HostCapabilityRefusal(HostCapabilityRefusal.NOT_PERMITTED, "hour required")
            val minute = int(params, "minute") ?: throw HostCapabilityRefusal(HostCapabilityRefusal.NOT_PERMITTED, "minute required")
            if (hour !in 0..23 || minute !in 0..59) {
                throw HostCapabilityRefusal(HostCapabilityRefusal.NOT_PERMITTED, "hour must be 0-23, minute 0-59")
            }
            val clock = context.packageManager.getInstalledApplications(0)
                .firstOrNull { info -> info.packageName.contains("deskclock", ignoreCase = true) }
            val intent = Intent(Intent.ACTION_SET_ALARM).apply {
                putExtra("hour", hour)
                putExtra("minutes", minute)
                str(params, "message").takeIf { it.isNotEmpty() }?.let { putExtra("message", it) }
                // Point at a real clock app when one is present so the confirm
                // dialog lands somewhere the user recognises.
                clock?.let { setPackage(it.packageName) }
            }
            if (context.packageManager.resolveActivity(intent, 0) == null) {
                // No clock app to host the intent: degrade to a reminder notification.
                requirePostNotifications(); ensureChannel()
                val text = "${String.format("%02d:%02d", hour, minute)} — ${str(params, "message").ifEmpty { "alarm" }}"
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_alarm_alert)
                    .setContentTitle("Alarm")
                    .setContentText(text)
                    .setWhen(System.currentTimeMillis())
                    .build()
                    .let { notifications().notify(ALARM_TAG, it, System.currentTimeMillis().toInt()) }
                return buildJsonObject { put("set", "notification"); put("reason", "no clock app found") }
            }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return buildJsonObject { put("set", "alarm") }
        }
    }

    private inner class Timer : HostCapability {
        override val name = "phone.timer"
        override suspend fun invoke(params: JsonObject): JsonObject {
            val seconds = int(params, "seconds") ?: throw HostCapabilityRefusal(HostCapabilityRefusal.NOT_PERMITTED, "seconds required")
            if (seconds < 1 || seconds > TimeUnit.DAYS.toSeconds(1)) {
                throw HostCapabilityRefusal(HostCapabilityRefusal.NOT_PERMITTED, "seconds must be 1-86400")
            }
            requirePostNotifications(); ensureChannel()
            val base = SystemClock.elapsedRealtime()
            val content = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_delete)
                .setContentTitle("Timer finished")
                .setContentText(str(params, "message").ifEmpty { "Your ${seconds}s timer is up." })
                .setAutoCancel(true)
                .apply {
                    // Countdown chronometers need API 29; below that the timer
                    // still ends, it just shows elapsed time until then.
                    setUsesChronometer(true, base)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        setChronometerCountDown(true)
                    }
                }
            notifications().notify(TIMER_TAG, content.build(), base.toInt())
            // Belt-and-suspenders: also fire an alarm so the user is reminded even
            // if the chronometer notification is dismissed early. ELAPSED_REALTIME
            // is measured from boot, not wall clock, so the base must match.
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context,
                base.toInt(),
                Intent(context, TimerFiredReceiver::class.java).putExtra("msg", str(params, "message")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.set(AlarmManager.ELAPSED_REALTIME, base + seconds * 1000L, pi)
            return buildJsonObject {
                put("started", true)
                putInt("seconds", seconds)
            }
        }
    }

    private inner class Calendar : HostCapability {
        override val name = "phone.calendar"
        override suspend fun invoke(params: JsonObject): JsonObject {
            val title = str(params, "title")
            val begin = str(params, "begin")
            if (title.isEmpty() || begin.isEmpty()) {
                throw HostCapabilityRefusal(HostCapabilityRefusal.NOT_PERMITTED, "title and begin are required")
            }
            val allDay = (params["allDay"] as? JsonPrimitive)?.booleanOrNull ?: false
            val zone = ZoneId.systemDefault()
            val startMs = parseInstant(begin, allDay, zone)
            val endMs = str(params, "end").let { if (it.isEmpty()) startMs + (if (allDay) 24L else 1L) * 3_600_000L else parseInstant(it, allDay, zone) }
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
                if (allDay) putExtra(CalendarContract.Events.ALL_DAY, true)
                str(params, "location").takeIf { it.isNotEmpty() }?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                str(params, "description").takeIf { it.isNotEmpty() }?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            }
            if (context.packageManager.resolveActivity(intent, 0) == null) {
                throw HostCapabilityRefusal(HostCapabilityRefusal.NOT_PERMITTED, "no calendar app found")
            }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return buildJsonObject { put("queued", true) }
        }

        private fun parseInstant(value: String, allDay: Boolean, zone: ZoneId): Long = when {
            allDay || !value.contains('T') ->
                LocalDate.parse(value).atStartOfDay(zone).toInstant(ZoneOffset.UTC).toEpochMilli()
            value.endsWith("Z") -> Instant.parse(value).toEpochMilli()
            else -> LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
        }
    }

    companion object {
        const val CHANNEL_ID = "pifilling_agent_channel"
        const val ALARM_TAG = "pifilling-alarm"
        const val TIMER_TAG = "pifilling-timer"
        const val TAG = "PhoneCapabilities"

        /** Exposed to [TimerFiredReceiver] without making it a member. */
        fun channelIdForReceiver(): String = CHANNEL_ID
    }
}

/**
 * Receiver for the timer's hard alarm so a dismissed chronometer still wakes the
 * user. Kept in this file next to the capability that schedules it.
 */
class TimerFiredReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val msg = intent.getStringExtra("msg") ?: "Your timer is up."
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = PhoneCapabilities.channelIdForReceiver()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            nm.createNotificationChannel(
                NotificationChannel(channel, "Agent", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        nm.notify(
            "timerfired",
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_delete)
                .setContentTitle("Timer finished")
                .setContentText(msg)
                .setAutoCancel(true)
                .build(),
            System.currentTimeMillis().toInt(),
        )
    }
}
