package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restores all scheduled JARVIS calls, Always-On Background Wake Word service,
 * and Smart Network Alert Monitor automatically after device reboot or app update.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext
                CallAlarmScheduler.rescheduleAllEnabledCalls(appContext)
                val repo = JarvisRepository(JarvisDatabase.getInstance(appContext))
                val settings = repo.getSettings()
                if (settings.alwaysOnWakeWordEnabled) {
                    JarvisWakeWordService.start(appContext)
                }
                if (settings.onlineAutoCallEnabled) {
                    NetworkMonitorService.start(appContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
