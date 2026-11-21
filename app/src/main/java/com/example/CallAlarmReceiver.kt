package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives AlarmManager.setAlarmClock triggers, acquires a brief WakeLock, reschedules recurring calls,
 * and launches JarvisIncomingCallActivity over the lock screen via FullScreenIntent + direct launch.
 */
class CallAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val callId = intent.getLongExtra(EXTRA_CALL_ID, -1L)
        val purpose = intent.getStringExtra(EXTRA_CALL_PURPOSE).orEmpty()
            .ifBlank { "Scheduled JARVIS Check-In" }
        val actionType = intent.getStringExtra(EXTRA_CALL_ACTION_TYPE).orEmpty()
            .ifBlank { CALL_TYPE_CUSTOM_REMINDER }
        val repeatCsv = intent.getStringExtra(EXTRA_REPEAT_CSV).orEmpty()

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (callId > 0L) {
                    val repo = JarvisRepository(JarvisDatabase.getInstance(context.applicationContext))
                    if (repeatCsv.equals("ONCE", ignoreCase = true)) {
                        repo.setScheduledCallEnabled(callId, false)
                    } else {
                        val updated = repo.getScheduledCallById(callId)
                        if (updated != null && updated.enabled) {
                            CallAlarmScheduler.scheduleCall(context.applicationContext, updated)
                        }
                    }
                }
                launchIncomingCall(
                    context = context.applicationContext,
                    purpose = purpose,
                    actionType = actionType,
                    alertSummary = ""
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_SCHEDULED_CALL_RING = "com.example.jarvis.ACTION_SCHEDULED_CALL_RING"
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_CALL_PURPOSE = "extra_call_purpose"
        const val EXTRA_CALL_ACTION_TYPE = "extra_call_action_type"
        const val EXTRA_REPEAT_CSV = "extra_repeat_csv"
        const val EXTRA_ALERT_SUMMARY = "extra_alert_summary"

        private const val CALL_CHANNEL_ID = "jarvis_incoming_call_channel"
        const val CALL_NOTIFICATION_ID = 7701

        fun launchIncomingCall(
            context: Context,
            purpose: String,
            actionType: String,
            alertSummary: String = ""
        ) {
            // Wake device CPU briefly while the full-screen incoming call activity turns the screen on
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "JARVIS:IncomingCallWakeLock"
            )
            runCatching { wakeLock.acquire(15_000L) }

            val callIntent = Intent(context, JarvisIncomingCallActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
                )
                putExtra(EXTRA_CALL_PURPOSE, purpose)
                putExtra(EXTRA_CALL_ACTION_TYPE, actionType)
                putExtra(EXTRA_ALERT_SUMMARY, alertSummary)
            }

            ensureCallNotificationChannel(context)
            val fullScreenPi = PendingIntent.getActivity(
                context,
                7702,
                callIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("J.A.R.V.I.S. • Incoming Call")
                .setContentText("$purpose — Developed by Mozahid")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(true)
                .setFullScreenIntent(fullScreenPi, true)
                .setContentIntent(fullScreenPi)
                .build()

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            runCatching { nm.notify(CALL_NOTIFICATION_ID, notification) }

            // Direct activity launch for unlocked screens or devices allowing background call starts
            runCatching { context.startActivity(callIntent) }
        }

        fun dismissCallNotification(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            runCatching { nm.cancel(CALL_NOTIFICATION_ID) }
        }

        private fun ensureCallNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val existing = nm.getNotificationChannel(CALL_CHANNEL_ID)
                if (existing == null) {
                    val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    val audioAttrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    val channel = NotificationChannel(
                        CALL_CHANNEL_ID,
                        "JARVIS Incoming Calls",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Full-screen incoming calls from JARVIS (Developed by Mozahid)."
                        setSound(ringtoneUri, audioAttrs)
                        enableVibration(true)
                        lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                    }
                    nm.createNotificationChannel(channel)
                }
            }
        }
    }
}
