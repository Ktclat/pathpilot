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
import androidx.core.content.ContextCompat
import dev.pathpilot.app.R
import dev.pathpilot.core.RouterServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** User-controlled foreground service: no boot receiver, automatic restart or background login. */
class RouterService : Service() {
    private var server: RouterServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var failure: String? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "局域网 API 共享", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示手机上的 API 路由服务状态，并允许随时停止共享"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val notification = notification("正在启动共享…")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            if (server != null) return START_NOT_STICKY
            val store = AppStore.get(this)
            check(store.storageError.value == null) { "Encrypted configuration unavailable" }
            val config = store.config.value
            require(config.port in 1024..65535 && config.accessKey.length >= 24)
            val created = RouterServer(
                configProvider = { store.config.value },
                onRequest = RouterRuntime::record,
                codexCredentials = store::resolveCodexCredentials,
            )
            server = created
            created.start()
            acquireLocks()
            RouterRuntime.started(config.port)
            updateNotification(config.port)
            serviceScope.launch {
                while (isActive) {
                    delay(5_000)
                    val addresses = LocalAddresses.ipv4()
                    if (RouterRuntime.state.value.addresses != addresses) {
                        RouterRuntime.addressesChanged(addresses)
                        updateNotification(config.port)
                    }
                }
            }
        } catch (error: Exception) {
            failure = "共享服务启动失败（${error.javaClass.simpleName}）；请检查端口是否被占用及加密配置状态。"
            RouterRuntime.stopped(failure)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PathPilot:LanRouter").apply {
                setReferenceCounted(false)
                acquire()
            }
        // A device may expose Ethernet without a usable Wi-Fi radio. The optional Wi-Fi lock
        // must not prevent its LAN server from starting; the CPU wake lock remains held.
        runCatching {
            @Suppress("DEPRECATION")
            val lock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PathPilot:LanRouter")
            wifiLock = lock
            lock?.setReferenceCounted(false)
            lock?.acquire()
        }
    }

    private fun updateNotification(port: Int) {
        val addresses = RouterRuntime.state.value.addresses
        val text = addresses.firstOrNull()?.let { "$it:$port · 点击打开管理，或停止共享" }
            ?: "端口 $port 已监听；连接 Wi-Fi 或开启热点后可获取局域网地址"
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, RouterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_pathpilot_mark)
            .setContentTitle("PathPilot · 正在共享 API")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "停止共享", stopIntent).build())
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setContentIntent(
                PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        }
        return builder.build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        runCatching { server?.stop() }
        server = null
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        wifiLock = null
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        RouterRuntime.stopped(failure)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "pathpilot_router"
        private const val NOTIFICATION_ID = 8317
        private const val ACTION_STOP = "dev.pathpilot.action.STOP_ROUTER"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RouterService::class.java))
            } catch (error: Exception) {
                RouterRuntime.error("无法启动共享服务（${error.javaClass.simpleName}），请在应用前台重试。")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RouterService::class.java))
        }
    }
}
