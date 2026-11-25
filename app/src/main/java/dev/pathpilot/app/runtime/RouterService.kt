package dev.pathpilot.app.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.pathpilot.app.R
import dev.pathpilot.core.RouterServer
import java.net.BindException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The IO coroutine owns the listener and locks, including cleanup if startup is cancelled. */
class RouterService : Service() {
    private val strings get() = AppLanguageController.wrapContext(this).resources
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serviceJob: Job? = null
    private var failure: String? = null
    private var destroyed = false
    private var cleanupComplete = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, strings.getString(R.string.ui_model_sharing), NotificationManager.IMPORTANCE_LOW).apply {
                description = strings.getString(R.string.ui_sharing_notification_description)
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RouterRuntime.stopping()
            val job = serviceJob
            if (job == null) finishShutdown(null) else job.cancel()
            return START_NOT_STICKY
        }
        if (serviceJob != null) return START_NOT_STICKY
        try {
            val notification = notification(strings.getString(R.string.ui_service_starting))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: Exception) {
            logFailure("startForeground", error)
            finishShutdown(strings.getString(R.string.ui_foreground_service_failed))
            return START_NOT_STICKY
        }
        // The UI can turn the switch off before Android delivers this start command.
        // Acknowledge startForeground promptly, then stop without opening a listener.
        if (RouterRuntime.state.value.stopping) {
            finishShutdown(null)
            return START_NOT_STICKY
        }
        if (!RouterRuntime.state.value.starting) RouterRuntime.starting()
        val bodyEntered = AtomicBoolean(false)
        serviceJob = serviceScope.launch {
            bodyEntered.set(true)
            var server: RouterServer? = null
            var wakeLock: PowerManager.WakeLock? = null
            var wifiLock: WifiManager.WifiLock? = null
            var terminalError: String? = null
            try {
                val store = AppStore.get(this@RouterService)
                currentCoroutineContext().ensureActive()
                store.storageError.value?.let { message ->
                    terminalError = message
                    return@launch
                }
                val config = store.config.value
                require(config.port in 1024..65535 && config.accessKey.length >= 24)
                val ownerContext = currentCoroutineContext()
                server = RouterServer(
                    configProvider = { store.config.value },
                    onRequest = { event ->
                        // NanoHTTPD workers and service cleanup run off the main thread.
                        // Keep final interrupted requests even after the service owner is cancelled.
                        store.requestHistory.record(event)
                        if (ownerContext.isActive) RouterRuntime.record(event)
                    },
                    codexCredentials = store::resolveCodexCredentials,
                )
                server.start()
                currentCoroutineContext().ensureActive()
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PathPilot:LanRouter")
                wakeLock.setReferenceCounted(false)
                wakeLock.acquire()
                // Ethernet and devices without a Wi-Fi radio do not require this optional lock.
                try {
                    @Suppress("DEPRECATION")
                    val lock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                        ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PathPilot:LanRouter")
                    wifiLock = lock
                    lock?.setReferenceCounted(false)
                    lock?.acquire()
                } catch (error: SecurityException) {
                    logFailure("optional Wi-Fi lock", error)
                } catch (error: UnsupportedOperationException) {
                    logFailure("optional Wi-Fi lock", error)
                }
                var addresses = LocalAddresses.discover(this@RouterService)
                withContext(Dispatchers.Main.immediate) {
                    if (!RouterRuntime.started(config.port, addresses)) throw CancellationException("Sharing was stopped during startup")
                    updateNotification(config.port, addresses)
                }
                while (isActive) {
                    delay(5_000)
                    val updated = LocalAddresses.discover(this@RouterService)
                    if (updated != addresses) {
                        addresses = updated
                        withContext(Dispatchers.Main.immediate) {
                            RouterRuntime.addressesChanged(updated)
                            updateNotification(config.port, updated)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logFailure("router startup or monitoring", error)
                terminalError = when (error) {
                    is BindException -> strings.getString(R.string.ui_port_in_use)
                    is SecurityException -> strings.getString(R.string.ui_sharing_permission_missing)
                    else -> strings.getString(R.string.ui_sharing_error)
                }
            } finally {
                // The switch remains in stopping until this owner closes the listener and locks
                // AND Android destroys its Service. A new owner cannot claim the same port early.
                releaseRouterResources(
                    markStopping = { withContext(Dispatchers.Main.immediate) { RouterRuntime.stopping() }; Unit },
                    stopServer = { server?.stop() },
                    releaseWifiLock = { wifiLock?.let { if (it.isHeld) it.release() } },
                    releaseWakeLock = { wakeLock?.let { if (it.isHeld) it.release() } },
                    onReleased = { error ->
                        if (error != null) {
                            logFailure("router cleanup", error)
                            terminalError = terminalError ?: strings.getString(R.string.ui_sharing_stop_error)
                        }
                        withContext(Dispatchers.Main.immediate) { finishShutdown(terminalError) }
                    },
                )
            }
        }.also { job ->
            // A stop can cancel a queued IO coroutine before its body (and finally) runs.
            job.invokeOnCompletion {
                Handler(Looper.getMainLooper()).post {
                    if (!bodyEntered.get() && !cleanupComplete) finishShutdown(failure)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun finishShutdown(message: String?) {
        failure = message
        cleanupComplete = true
        if (destroyed) RouterRuntime.stopped(message)
        else {
            RouterRuntime.stopping()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateNotification(port: Int, snapshot: LocalAddressSnapshot) {
        val candidate = snapshot.addresses.firstOrNull { it.kind == LocalAddressKind.WIFI || it.kind == LocalAddressKind.ETHERNET }
            ?: snapshot.addresses.firstOrNull { it.kind == LocalAddressKind.OTHER }
        val text = candidate?.let { "${it.ip}:${port} · ${it.interfaceName}" }
            ?: strings.getString(R.string.ui_loopback_notification, port)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, RouterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_pathpilot_mark)
            .setContentTitle(strings.getString(R.string.ui_model_sharing_notification))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, strings.getString(R.string.ui_stop), stopIntent).build())
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setContentIntent(
                PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        }
        return builder.build()
    }

    override fun onDestroy() {
        destroyed = true
        RouterRuntime.stopping()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (serviceJob == null || cleanupComplete) RouterRuntime.stopped(failure)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "pathpilot_router"
        private const val NOTIFICATION_ID = 8317
        private const val ACTION_STOP = "dev.pathpilot.action.STOP_ROUTER"
        private const val TAG = "PathPilotService"

        // Log stack locations without exception messages, which can contain upstream credentials.
        private fun logFailure(operation: String, error: Exception) {
            Log.e(TAG, "${operation}: ${error.javaClass.simpleName}\n${error.stackTrace.joinToString("\n")}")
        }

        fun start(context: Context) {
            if (!RouterRuntime.starting()) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, RouterService::class.java))
            } catch (error: Exception) {
                logFailure("request foreground service", error)
                RouterRuntime.error(AppLanguageController.wrapContext(context).getString(R.string.ui_sharing_start_failed))
            }
        }

        fun stop(context: Context) {
            if (!RouterRuntime.stopping()) return
            try {
                // Deliver a stop even if Android has not created the requested service yet.
                context.startService(Intent(context, RouterService::class.java).setAction(ACTION_STOP))
            } catch (error: IllegalStateException) {
                logFailure("request service stop", error)
                // A false result is not a lifecycle completion callback. Retain the stop
                // intent so a pending START cannot open its listener after this fallback.
                context.stopService(Intent(context, RouterService::class.java))
            }
        }
    }
}
