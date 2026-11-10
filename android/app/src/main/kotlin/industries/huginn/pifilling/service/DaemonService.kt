package industries.huginn.pifilling.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import industries.huginn.pifilling.MainActivity
import industries.huginn.pifilling.R

/**
 * Foreground service that holds the app process at foreground priority while a
 * sandbox/agent run is in flight, so Android doesn't reclaim it mid-task.
 *
 * Ported from Kai's DaemonService:
 *  - [onStartCommand] returns START_STICKY so the OS recreates it after a kill;
 *  - [onCreate] wraps startForeground in try/catch and bails cleanly on failure;
 *  - [onTimeout] (API 34+) tears down when the ~6h/24h dataSync budget is spent
 *    rather than letting the framework crash the service;
 *  - MainActivity.onStart() re-asserts the service (idempotent) to recover from
 *    OEM battery-manager kills.
 */
class DaemonService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Must succeed or the framework kills the app with
        // ForegroundServiceDidNotStartInTimeException. `stopSelf()` is NOT an
        // escape: the timeout still fires, because the system tracks "did a
        // startForeground call happen", not "is the service still alive".
        //
        // Retry without the type first: Android 14+ rejects a `dataSync` FGS
        // started from the background, and dropping the type is what makes the
        // call legal there. The service is only a keep-alive hint, so losing the
        // declared type costs nothing functional.
        val promoted = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
        }.recoverCatching {
            Log.w(TAG, "typed startForeground refused; retrying without type", it)
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), 0)
        }

        if (promoted.isFailure) {
            // Both attempts failed. Stop, and accept that the process loses its
            // keep-alive: the wrapper is still tracked by the registry, and a
            // crash here would take the whole session with it.
            Log.e(TAG, "startForeground failed; stopping", promoted.exceptionOrNull())
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?) = null

    /**
     * API 34+ fires this when the cumulative dataSync foreground budget is
     * exhausted. Tear down cleanly; the user reopens the app to resume, at which
     * point MainActivity.onStart re-asserts the service.
     */
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "dataSync FGS timed out; stopping")
        stop()
    }

    override fun onTimeout(startId: Int, fgsType: Int) = onTimeout(startId)

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.daemon_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.daemon_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: Intent(this, MainActivity::class.java)

        val pending = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.daemon_notification_title))
            .setContentText(getString(R.string.daemon_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        /**
         * Whether the keep-alive service may be started at all.
         *
         * Off while  is failing on this platform: the framework
         * treats a service that does not reach the foreground within five
         * seconds as an app crash, and a keep-alive hint is not worth crashing
         * for. The wrapper process is held and tracked independently, so the
         * session works without it.
         */
        const val ENABLED = false

        private const val TAG = "DaemonService"
        private const val CHANNEL_ID = "pifilling_daemon_channel"
        private const val NOTIFICATION_ID = 9001

        /** Start the FGS. No-op-safe if already running (startForegroundService is idempotent). */
        fun start(context: Context) {
            val intent = Intent(context, DaemonService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // Android 12+ throws ForegroundServiceStartNotAllowedException if
                // the app isn't currently foreground. Caller retries from onStart.
                Log.w(TAG, "could not start daemon (app not foreground?): ${e.message}")
            }
        }

        /**
         * True when the platform lets this app start a foreground service now.
         *
         * Android 12+ refuses when the app is in the background, and Android 14+
         * additionally restricts the `dataSync` type. Asking first avoids the
         * start-then-fail path, which the framework reports as an app crash even
         * though nothing the user did was wrong.
         */
        fun canStart(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            val am = context.getSystemService(android.app.ActivityManager::class.java)
                ?: return false
            // `runningAppProcesses` only lists our own process on API 26+, which
            // is exactly what is needed here. A null list means the platform
            // will not say; treat that as "not allowed" so the caller skips the
            // start rather than triggering a crash the user cannot explain.
            val self = am.runningAppProcesses?.firstOrNull {
                it.pid == android.os.Process.myPid()
            } ?: return false
            return self.importance <=
                android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DaemonService::class.java))
        }
    }
}
