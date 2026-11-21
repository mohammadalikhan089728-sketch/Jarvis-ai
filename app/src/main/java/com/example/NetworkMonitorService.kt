package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Monitors device network connectivity via ConnectivityManager.NetworkCallback.
 * The moment the phone comes online (Wi-Fi or Mobile Data) — or immediately if already online —
 * checks local Room DB for unhandled viral/urgent PendingCallAlertEntity items and triggers
 * an incoming JARVIS call (enforcing a strict 10-minute cooldown so it never spams).
 */
class NetworkMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectivityManager: ConnectivityManager? = null
    private var callbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            triggerAlertCallIfOnline(applicationContext)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                triggerAlertCallIfOnline(applicationContext)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildForegroundNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        registerCallbackIfNeeded()
        _isRunning.value = true
        triggerAlertCallIfOnline(applicationContext)
        return START_STICKY
    }

    private fun registerCallbackIfNeeded() {
        if (callbackRegistered) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, networkCallback)
            callbackRegistered = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (callbackRegistered) {
            runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
            callbackRegistered = false
        }
        serviceScope.cancel()
        _isRunning.value = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Smart Alert Monitor",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Monitors network return for pending viral & urgent JARVIS call alerts."
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            this,
            9202,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("JARVIS — Smart Alert Monitor")
            .setContentText("Watching for viral & urgent updates • Developed by Mozahid")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_network_monitor_channel"
        private const val NOTIFICATION_ID = 4201
        private const val AUTO_CALL_COOLDOWN_MS = 10 * 60 * 1000L // 10-minute cooldown

        @Volatile
        private var lastAutoCallTimestampMs: Long = 0L

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, NetworkMonitorService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, NetworkMonitorService::class.java))
            }
        }

        fun isDeviceOnline(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }

        fun triggerAlertCallIfOnline(context: Context) {
            if (!isDeviceOnline(context)) return
            if (JarvisIncomingCallActivity.isCallActive.value) return

            val now = System.currentTimeMillis()
            if ((now - lastAutoCallTimestampMs) < AUTO_CALL_COOLDOWN_MS) return

            CoroutineScope(Dispatchers.IO).launch {
                val repo = JarvisRepository(JarvisDatabase.getInstance(context.applicationContext))
                val settings = repo.getSettings()
                if (!settings.onlineAutoCallEnabled) return@launch

                val unhandled = repo.getUnhandledCallAlerts()
                if (unhandled.isEmpty()) return@launch

                lastAutoCallTimestampMs = System.currentTimeMillis()
                repo.markAllAlertsHandled()

                val topAlert = unhandled.first()
                val combinedSummary = unhandled.take(5).joinToString(" | ") {
                    "${it.sourceApp}: ${it.title} — ${it.summary}"
                }

                CallAlarmReceiver.launchIncomingCall(
                    context = context.applicationContext,
                    purpose = topAlert.title,
                    actionType = CALL_TYPE_ONLINE_ALERT,
                    alertSummary = combinedSummary
                )
            }
        }
    }
}
