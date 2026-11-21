package com.example

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

/**
 * Schedules unlimited JARVIS incoming calls using AlarmManager.setAlarmClock(...)
 * so alarms reliably wake the phone out of Doze mode and ring over the lock screen.
 */
object CallAlarmScheduler {

    fun computeNextTriggerMillis(call: ScheduledCallEntity, nowMillis: Long = System.currentTimeMillis()): Long {
        val repeat = call.repeatDaysCsv.trim().uppercase(Locale.ROOT)
        val allowedDays: Set<Int> = when {
            repeat.isEmpty() || repeat == "ONCE" || repeat == "DAILY" -> emptySet()
            else -> repeat.split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in Calendar.SUNDAY..Calendar.SATURDAY }
                .toSet()
        }

        val candidate = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, call.hour.coerceIn(0, 23))
            set(Calendar.MINUTE, call.minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (candidate.timeInMillis <= nowMillis + 2_000L) {
            candidate.add(Calendar.DAY_OF_YEAR, 1)
        }

        if (allowedDays.isNotEmpty()) {
            var safety = 0
            while (candidate.get(Calendar.DAY_OF_WEEK) !in allowedDays && safety < 8) {
                candidate.add(Calendar.DAY_OF_YEAR, 1)
                safety++
            }
        }
        return candidate.timeInMillis
    }

    fun scheduleCall(context: Context, call: ScheduledCallEntity): Long? {
        if (!call.enabled || call.id <= 0L) {
            cancelCall(context, call.id)
            return null
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAtMillis = computeNextTriggerMillis(call)

        val broadcastIntent = Intent(context, CallAlarmReceiver::class.java).apply {
            action = CallAlarmReceiver.ACTION_SCHEDULED_CALL_RING
            putExtra(CallAlarmReceiver.EXTRA_CALL_ID, call.id)
            putExtra(CallAlarmReceiver.EXTRA_CALL_PURPOSE, call.purpose)
            putExtra(CallAlarmReceiver.EXTRA_CALL_ACTION_TYPE, call.actionType)
            putExtra(CallAlarmReceiver.EXTRA_REPEAT_CSV, call.repeatDaysCsv)
        }

        val operationPi = PendingIntent.getBroadcast(
            context,
            requestCodeFor(call.id),
            broadcastIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val showPi = PendingIntent.getActivity(
            context,
            requestCodeFor(call.id) + 50_000,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, showPi)
        return runCatching {
            alarmManager.setAlarmClock(alarmClockInfo, operationPi)
            triggerAtMillis
        }.getOrElse {
            // Safe fallback if exact alarm permission was revoked
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operationPi)
                triggerAtMillis
            }.getOrNull()
        }
    }

    fun cancelCall(context: Context, callId: Long) {
        if (callId <= 0L) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, CallAlarmReceiver::class.java).apply {
            action = CallAlarmReceiver.ACTION_SCHEDULED_CALL_RING
        }
        val pi = PendingIntent.getBroadcast(
            context,
            requestCodeFor(callId),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            alarmManager.cancel(pi)
            pi.cancel()
        }
    }

    suspend fun rescheduleAllEnabledCalls(context: Context) = withContext(Dispatchers.IO) {
        val repo = JarvisRepository(JarvisDatabase.getInstance(context.applicationContext))
        val calls = repo.getEnabledScheduledCalls()
        for (call in calls) {
            scheduleCall(context.applicationContext, call)
        }
    }

    fun formatTime12Hour(hour: Int, minute: Int): String {
        val amPm = if (hour >= 12) "PM" else "AM"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return String.format(Locale.US, "%d:%02d %s", displayHour, minute, amPm)
    }

    fun formatRepeatLabel(repeatCsv: String): String {
        return when (repeatCsv.trim().uppercase(Locale.ROOT)) {
            "ONCE" -> "Once"
            "DAILY", "" -> "Daily"
            else -> {
                val map = mapOf(
                    Calendar.SUNDAY to "Sun",
                    Calendar.MONDAY to "Mon",
                    Calendar.TUESDAY to "Tue",
                    Calendar.WEDNESDAY to "Wed",
                    Calendar.THURSDAY to "Thu",
                    Calendar.FRIDAY to "Fri",
                    Calendar.SATURDAY to "Sat"
                )
                repeatCsv.split(",")
                    .mapNotNull { it.trim().toIntOrNull()?.let { d -> map[d] } }
                    .joinToString(", ")
                    .ifBlank { "Daily" }
            }
        }
    }

    private fun requestCodeFor(callId: Long): Int = (20_000 + (callId % 25_000)).toInt()
}
