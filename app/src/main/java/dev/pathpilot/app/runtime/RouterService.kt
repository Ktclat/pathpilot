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
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.pathpilot.app.R
import dev.pathpilot.core.RouterServer
import java.net.BindException
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
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serviceJob: Job? = null
    private var failure: String? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "局域网共享", NotificationManager.IMPORTANCE_LOW).apply {
                description = "共享状态与停止操作"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            serviceJob?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (serviceJob != null) return START_NOT_STICKY
        try {
            val notification = notification("正在启动…")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: Exception) {
            logFailure("startForeground", error)
            fail("无法启动前台服务，请在应用中重试。")
            return START_NOT_STICKY
        }
        RouterRuntime.starting()
        serviceJob = serviceScope.launch {
            var server: RouterServer? = null
            var wakeLock: PowerManager.WakeLock? = null
            var wifiLock: WifiManager.WifiLock? = null
            try {
                val store = AppStore.get(this@RouterService)
                currentCoroutineContext().ensureActive()
                store.storageError.value?.let { message ->
                    withContext(Dispatchers.Main.immediate) { fail(message) }
                    return@launch
                }
                val config = store.config.value
                require(config.port in 1024..65535 && config.accessKey.length >= 24)
                val ownerContext = currentCoroutineContext()
                server = RouterServer(
                    configProvider = { store.config.value },
                    onRequest = { event -> if (ownerContext.isActive) RouterRuntime.record(event) },
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
                var addresses = LocalAddresses.ipv4()
                withContext(Dispatchers.Main.immediate) {
                    RouterRuntime.started(config.port, addresses)
                    updateNotification(config.port, addresses)
                }
                while (isActive) {
                    delay(5_000)
                    val updated = LocalAddresses.ipv4()
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
                val message = when (error) {
                    is BindException -> "端口已被占用，请修改端口。"
                    is SecurityException -> "共享服务缺少系统权限。"
                    else -> "共享服务出错，请重试。"
                }
                withContext(Dispatchers.Main.immediate) { fail(message) }
            } finally {
                // No UI updates here: a newer service instance may already be starting. These
                // resources remain local to this job, even when cancellation interrupted startup.
                try {
                    server?.stop()
                } finally {
                    try {
                        wifiLock?.let { if (it.isHeld) it.release() }
                    } finally {
                        wakeLock?.let { if (it.isHeld) it.release() }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun fail(message: String) {
        failure = message
        RouterRuntime.stopped(message)
        stopSelf()
    }

    private fun updateNotification(port: Int, addresses: List<String>) {
        val text = addresses.firstOrNull()?.let { "$it:$port" } ?: "端口 $port · 未发现局域网地址"
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, RouterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_pathpilot_mark)
            .setContentTitle("PathPilot · 局域网共享")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "停止", stopIntent).build())
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setContentIntent(
                PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        }
        return builder.build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        RouterRuntime.stopped(failure)
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
            Log.e(TAG, "$operation: ${error.javaClass.simpleName}\n${error.stackTrace.joinToString("\n")}")
        }

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RouterService::class.java))
            } catch (error: Exception) {
                logFailure("request foreground service", error)
                RouterRuntime.error("无法启动共享，请在应用中重试。")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RouterService::class.java))
        }
    }
}
