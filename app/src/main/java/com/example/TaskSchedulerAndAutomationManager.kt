package com.example

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ParsedAutomationTask(
    val taskType: String,
    val target: String,
    val messageText: String,
    val appPackage: String,
    val triggerAtMillis: Long?, // null => execute immediately
    val humanTimeLabel: String,
    val repeatDaily: Boolean = false
)

/**
 * BroadcastReceiver fired by AlarmManager.setExactAndAllowWhileIdle for scheduled JARVIS tasks
 * (WhatsApp messages, Instagram DMs, direct calls, SMS, alarms, app launches) even when screen is off.
 */
class ScheduledTaskReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val taskId = intent?.getLongExtra(TaskSchedulerAndAutomationManager.EXTRA_TASK_ID, -1L) ?: -1L
        if (taskId <= 0L) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val wakeLock = TaskSchedulerAndAutomationManager.acquireWakeLock(appContext, 35_000L)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = JarvisRepository(JarvisDatabase.getInstance(appContext))
                val task = repo.getScheduledTaskById(taskId) ?: return@launch
                if (task.status != TASK_STATUS_PENDING) return@launch

                // Wake lock-screen / Activity briefly if screen is off so Accessibility automation can interact cleanly
                TaskSchedulerAndAutomationManager.wakeScreenIfNeeded(appContext, taskId)
                delay(1_200L)

                val resultMsg = TaskSchedulerAndAutomationManager.executeTaskNow(appContext, task)
                repo.updateScheduledTaskStatus(task.id, TASK_STATUS_EXECUTED)
                repo.logInteraction(
                    ChatHistoryEntity(
                        userTranscript = "SCHEDULED:${task.taskType} -> ${task.target}",
                        executedAction = "AUTO_TASK:${task.taskType}",
                        actionParamsJson = "{}",
                        jarvisReply = resultMsg,
                        isOffline = true,
                        latencyMs = 0L
                    )
                )

                if (task.repeatDaily) {
                    val nextDay = task.copy(
                        id = 0L,
                        triggerAtMillis = task.triggerAtMillis + TimeUnit.DAYS.toMillis(1),
                        status = TASK_STATUS_PENDING
                    )
                    TaskSchedulerAndAutomationManager.scheduleTask(appContext, repo, nextDay)
                }
            } finally {
                runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
                pendingResult.finish()
            }
        }
    }
}

/**
 * Resilient WorkManager backup worker ensuring scheduled tasks execute even if an OEM killed exact alarms.
 */
class ScheduledTaskWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(TaskSchedulerAndAutomationManager.EXTRA_TASK_ID, -1L)
        if (taskId <= 0L) return Result.success()

        val repo = JarvisRepository(JarvisDatabase.getInstance(applicationContext))
        val task = repo.getScheduledTaskById(taskId) ?: return Result.success()
        if (task.status != TASK_STATUS_PENDING) {
            return Result.success()
        }

        val wakeLock = TaskSchedulerAndAutomationManager.acquireWakeLock(applicationContext, 35_000L)
        return try {
            TaskSchedulerAndAutomationManager.wakeScreenIfNeeded(applicationContext, taskId)
            delay(1_200L)
            val resultMsg = TaskSchedulerAndAutomationManager.executeTaskNow(applicationContext, task)
            repo.updateScheduledTaskStatus(task.id, TASK_STATUS_EXECUTED)
            repo.logInteraction(
                ChatHistoryEntity(
                    userTranscript = "SCHEDULED:${task.taskType} -> ${task.target}",
                    executedAction = "AUTO_TASK:${task.taskType}",
                    actionParamsJson = "{}",
                    jarvisReply = resultMsg,
                    isOffline = true,
                    latencyMs = 0L
                )
            )
            Result.success()
        } catch (_: Exception) {
            repo.updateScheduledTaskStatus(task.id, TASK_STATUS_FAILED)
            Result.failure()
        } finally {
            runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
        }
    }
}

/**
 * Unified Universal App, Social Messaging & System Action Engine (Instant + Scheduled) for J.A.R.V.I.S.
 * Backed by AlarmManager (setExactAndAllowWhileIdle) + WorkManager + foreground WakeLock + AccessibilityService.
 */
object TaskSchedulerAndAutomationManager {

    const val EXTRA_TASK_ID = "com.example.jarvis.EXTRA_SCHEDULED_TASK_ID"
    const val EXTRA_WAKE_FOR_TASK = "com.example.jarvis.EXTRA_WAKE_FOR_TASK"
    private const val ACTION_SETTLE_MS = 750L

    fun acquireWakeLock(context: Context, timeoutMs: Long = 30_000L): PowerManager.WakeLock? {
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE
            val wl = pm.newWakeLock(flags, "jarvis:scheduled_task_wake")
            wl.acquire(timeoutMs)
            wl
        }.getOrElse {
            runCatching {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:scheduled_task_cpu")
                wl.acquire(timeoutMs)
                wl
            }.getOrNull()
        }
    }

    fun wakeScreenIfNeeded(context: Context, taskId: Long) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            val wakeIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_WAKE_FOR_TASK, true)
                putExtra(EXTRA_TASK_ID, taskId)
            }
            runCatching { context.startActivity(wakeIntent) }
        }
    }

    /**
     * Persists a scheduled task in Room DB and arms BOTH AlarmManager.setExactAndAllowWhileIdle
     * AND a resilient WorkManager OneTimeWorkRequest backup.
     */
    suspend fun scheduleTask(
        context: Context,
        repository: JarvisRepository,
        task: ScheduledTaskEntity
    ): ScheduledTaskEntity {
        val savedId = repository.saveScheduledTask(task)
        val saved = task.copy(id = savedId)
        armAlarmAndWorker(context, saved)
        return saved
    }

    fun armAlarmAndWorker(context: Context, task: ScheduledTaskEntity) {
        val appContext = context.applicationContext
        val triggerAt = task.triggerAtMillis.coerceAtLeast(System.currentTimeMillis() + 3_000L)

        // 1. Primary: AlarmManager.setExactAndAllowWhileIdle (fires reliably even in Doze/screen-off)
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(appContext, ScheduledTaskReceiver::class.java).apply {
            putExtra(EXTRA_TASK_ID, task.id)
        }
        val pi = PendingIntent.getBroadcast(
            appContext,
            taskRequestCode(task.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        runCatching {
            if (canScheduleExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                val showIntent = PendingIntent.getActivity(
                    appContext,
                    taskRequestCode(task.id) + 1000,
                    Intent(appContext, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), pi)
            }
        }

        // 2. Backup: WorkManager delayed backup (+15s grace so AlarmManager runs first; Worker no-ops if already EXECUTED)
        val delayMs = (triggerAt - System.currentTimeMillis()).coerceAtLeast(5_000L) + 15_000L
        val workData = Data.Builder()
            .putLong(EXTRA_TASK_ID, task.id)
            .build()
        val request = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workData)
            .addTag(workUniqueName(task.id))
            .build()

        runCatching {
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                workUniqueName(task.id),
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    fun cancelTask(context: Context, taskId: Long) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(appContext, ScheduledTaskReceiver::class.java).apply {
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val pi = PendingIntent.getBroadcast(
            appContext,
            taskRequestCode(taskId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching { alarmManager.cancel(pi) }
        runCatching { WorkManager.getInstance(appContext).cancelUniqueWork(workUniqueName(taskId)) }
    }

    suspend fun rescheduleAllPendingTasks(context: Context) {
        val repo = JarvisRepository(JarvisDatabase.getInstance(context.applicationContext))
        val pending = repo.getPendingScheduledTasks()
        val now = System.currentTimeMillis()
        for (task in pending) {
            if (task.triggerAtMillis > now - 60_000L) {
                armAlarmAndWorker(context, task)
            }
        }
    }

    /**
     * Executes any automation task immediately via Android Intents + AccessibilityService
     * with mandatory 750ms post-action UI settle and interactive window refresh.
     */
    suspend fun executeTaskNow(context: Context, task: ScheduledTaskEntity): String {
        val appContext = context.applicationContext
        val offline = OfflineCommandProcessor(appContext)
        return when (task.taskType) {
            TASK_TYPE_WHATSAPP_MSG -> executeWhatsAppMessage(appContext, offline, task.target, task.messageText)
            TASK_TYPE_INSTAGRAM_DM -> executeInstagramDm(appContext, task.target, task.messageText)
            TASK_TYPE_INSTAGRAM_REELS -> executeInstagramReels(appContext)
            TASK_TYPE_PHONE_CALL -> offline.initiateCall(task.target)
            TASK_TYPE_SMS -> executeSmsMessage(appContext, offline, task.target, task.messageText)
            TASK_TYPE_ALARM -> executeSystemAlarm(appContext, task.triggerAtMillis, task.messageText.ifBlank { "J.A.R.V.I.S. Alarm" })
            TASK_TYPE_TIMER -> executeSystemTimer(appContext, task.target.toIntOrNull() ?: 60, task.messageText.ifBlank { "J.A.R.V.I.S. Timer" })
            TASK_TYPE_YOUTUBE_PLAY -> executeYouTubeSearchAndPlay(appContext, task.target)
            TASK_TYPE_GOOGLE_SEARCH -> executeGoogleSearch(appContext, task.target)
            TASK_TYPE_OPEN_APP -> {
                val launched = offline.openInstalledApp(task.target.ifBlank { task.appPackage })
                if (launched != null) "$launched open kar diya hai Sir."
                else "App '${task.target}' device par nahi mila Sir."
            }
            else -> "Task execute kar diya hai Sir."
        }
    }

    /**
     * Full autonomous WhatsApp messaging (supports com.whatsapp & com.whatsapp.w4b):
     * 1. Resolves phone number directly if digits or contact found -> deep link opens exact chat with prefilled message -> taps Send.
     * 2. Otherwise launches WhatsApp -> 750ms settle -> taps Search -> types contact name -> 750ms settle -> taps chat -> 750ms settle -> types message -> 750ms settle -> taps Send!
     */
    suspend fun executeWhatsAppMessage(
        context: Context,
        offline: OfflineCommandProcessor,
        recipient: String,
        message: String
    ): String {
        val a11y = JarvisAccessibilityService.instance
        val pm = context.packageManager
        val waPackage = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { pkg ->
            pm.getLaunchIntentForPackage(pkg) != null
        } ?: return "WhatsApp is phone mein installed nahi hai Sir."

        val cleanTarget = recipient.trim()
        val cleanMsg = message.trim().ifBlank { "Hello" }
        val resolvedNumber = offline.resolvePhoneNumberOrNull(cleanTarget)

        // Path A: Direct phone-number deep link into chat (fastest & 100% accurate)
        if (!resolvedNumber.isNullOrBlank()) {
            val digitsOnly = resolvedNumber.filter { it.isDigit() }
            val uri = Uri.parse(
                "https://api.whatsapp.com/send?phone=$digitsOnly&text=${Uri.encode(cleanMsg)}"
            )
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(waPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) {
                if (a11y == null) {
                    return "WhatsApp chat khol diya hai Sir. Auto-send ke liye Accessibility on kar dijiye."
                }
                delay(1_800L)
                a11y.settleAndRefreshScreen(ACTION_SETTLE_MS)
                val sent = a11y.tapSendButtonOnScreen()
                if (!sent) {
                    delay(900L)
                    a11y.tapSendButtonOnScreen()
                }
                return "Boss, $cleanTarget ko WhatsApp par message bhej diya hai."
            }
        }

        // Path B: Accessibility UI Navigation (Open WhatsApp -> Search -> Tap Chat -> Type -> Tap Send)
        val launchIntent = pm.getLaunchIntentForPackage(waPackage)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return "WhatsApp launch nahi ho paya Sir."
        context.startActivity(launchIntent)

        if (a11y == null) {
            return "WhatsApp khol diya hai Sir. Auto-typing aur send ke liye Accessibility Service enable kijiye."
        }

        delay(1_300L)
        a11y.settleAndRefreshScreen(ACTION_SETTLE_MS)

        // Tap Search icon / bar
        val searchTapped = a11y.tapByViewId("menuitem_search") ||
            a11y.tapByViewId("search_bar") ||
            a11y.tapByText("Search") ||
            a11y.tapByText("Ask Meta AI or Search")
        if (searchTapped) {
            a11y.settleAndRefreshScreen(ACTION_SETTLE_MS)
        }

        // Type recipient name
        a11y.typeText(cleanTarget, targetFieldHint = "Search", submitAfterTyping = false)
        a11y.settleAndRefreshScreen(ACTION_SETTLE_MS)

        // Tap matching contact in search results
        val contactOpened = a11y.tapByText(cleanTarget) || a11y.tapFirstContactResultRow()
        if (!contactOpened) {
            return "WhatsApp par '$cleanTarget' search kar diya hai Sir, please chat confirm karein."
        }
        a11y.settleAndRefreshScreen(ACTION_SETTLE_MS)

        // Type exact message into WhatsApp input field
        val typed = a11y.typeText(cleanMsg, targetFieldHint = "Message", submitAfterTyping = false) ||
            a11y.typeText(cleanMsg, targetFieldHint = null, submitAfterTyping = false)
        if (!typed) {
            return "Chat khol diya hai Sir, lekin message box nahi mila."
        }
        a11y.settleAndRefreshScreen(ACTION_SETTLE_MS)

        // Tap Send button
        val sent = a11y.tapSendButtonOnScreen()
        return if (sent) {
            "Done Boss, $cleanTarget ko WhatsApp par \"$cleanMsg\" bhej diya hai."
        } else {
            "Message type kar diya hai Sir, Send button tap karne ke liye ready hai."
        }
    }

    /**
     * Full autonomous Instagram DM workflow:
     * Open Instagram -> 750ms settle -> Tap Direct Messages -> 750ms settle -> Search recipient ->
     * 750ms settle -> Select thread -> 750ms settle -> Type message -> 750ms settle -> Tap Send!
     */
    suspend fun executeInstagramDm(
        context: Context,
        recipient: String,
        message: String
    ): String {
        val a11y = JarvisAccessibilityService.instance
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage("com.instagram.android")?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return "Instagram is phone mein installed nahi hai Sir."

        context.startActivity(launchIntent)
        if (a11y == null) {
            return "Instagram open kar diya hai Sir. Auto-DM ke liye Accessibility Service enable karein."
        }

        val cleanTarget = recipient.trim().removePrefix("@")
        val cleanMsg = message.trim().ifBlank { "Hello" }

        delay(1_600L)
        a11y.settleAndRefreshScreen(ACTION_SETTLE_MS)

        // 1. Navigate to Direct Messages (Messenger / Direct icon top-right)
        val dmOpened = a11y.tapByViewId("action_bar_inbox_button") ||
            a11y.tapByText("Direct") ||
            a11y.tapByText("Messages") ||
            a11y.tapByText("Messenger") ||
            a11y.tapRelativeScreenPoint(0.92f, 0.085f)
        if (!dmOpened) {
            return "Instagram open ho gaya hai Sir, lekin Direct Messages icon nahi mila."
        }
        a11y.settleAndRefreshScreen(ACTION_SETTLE_MS)

        // 2. Tap Search bar inside Direct Messages
        a11y.tapByViewId("search_edit_text") ||
            a11y.tapByText("Search") ||
            a11y.tapRelativeScreenPoint(0.45f, 0.16f)
        a11y.settleAndRefreshScreen(ACTION_SETTLE_MS)

        // 3. Type recipient username/name
        a11y.typeText(cleanTarget, targetFieldHint = "Search", submitAfterTyping = false) ||
            a11y.typeText(cleanTarget, targetFieldHint = null, submitAfterTyping = false)
        a11y.settleAndRefreshScreen(ACTION_SETTLE_MS)

        // 4. Open matching user thread
        val userTapped = a11y.tapByText(cleanTarget) || a11y.tapFirstContactResultRow()
        if (!userTapped) {
            return "Instagram DM mein '$cleanTarget' search kar diya hai Sir."
        }
        a11y.settleAndRefreshScreen(ACTION_SETTLE_MS)

        // 5. Type exact message
        val typed = a11y.typeText(cleanMsg, targetFieldHint = "Message", submitAfterTyping = false) ||
            a11y.typeText(cleanMsg, targetFieldHint = null, submitAfterTyping = false)
        if (!typed) {
            return "Instagram chat khol diya hai Sir, message box focus nahi hua."
        }
        a11y.settleAndRefreshScreen(ACTION_SETTLE_MS)

        // 6. Tap Send
        val sent = a11y.tapSendButtonOnScreen()
        return if (sent) {
            "Done Boss, Instagram par $cleanTarget ko message bhej diya hai."
        } else {
            "Instagram DM type ho gaya hai Sir, Send ke liye ready hai."
        }
    }

    /**
     * Opens Instagram and navigates straight to Reels tab with 750ms settle.
     */
    suspend fun executeInstagramReels(context: Context): String {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage("com.instagram.android")?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return "Instagram installed nahi hai Sir."

        context.startActivity(launchIntent)
        val a11y = JarvisAccessibilityService.instance
        if (a11y != null) {
            delay(1_500L)
            a11y.settleAndRefreshScreen(ACTION_SETTLE_MS)
            val tapped = a11y.tapByViewId("clips_tab") ||
                a11y.tapByText("Reels") ||
                a11y.tapRelativeScreenPoint(0.70f, 0.955f)
            if (tapped) {
                a11y.settleAndRefreshScreen(ACTION_SETTLE_MS)
            }
        }
        return "Instagram khol kar Reels chala di hain Sir."
    }

    suspend fun executeSmsMessage(
        context: Context,
        offline: OfflineCommandProcessor,
        recipient: String,
        message: String
    ): String {
        val number = offline.resolvePhoneNumberOrNull(recipient) ?: recipient.filter { it.isDigit() || it == '+' }
        if (number.isBlank()) {
            return "$recipient ka number Contacts mein nahi mila Sir."
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            return "SMS app open nahi ho paya Sir."
        }
        val a11y = JarvisAccessibilityService.instance
        if (a11y != null) {
            delay(1_200L)
            a11y.settleAndRefreshScreen(ACTION_SETTLE_MS)
            a11y.tapSendButtonOnScreen()
        }
        return "Boss, $recipient ko SMS bhej diya hai."
    }

    fun executeSystemAlarm(context: Context, triggerAtMillis: Long, label: String): String {
        val cal = Calendar.getInstance().apply { timeInMillis = triggerAtMillis }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ok = runCatching { context.startActivity(intent); true }.getOrDefault(false)
        val formatted = CallAlarmScheduler.formatTime12Hour(hour, minute)
        return if (ok) {
            "Done Sir, $formatted ka alarm set kar diya hai."
        } else {
            "Alarm app open nahi ho paya Sir."
        }
    }

    fun executeSystemTimer(context: Context, seconds: Int, label: String): String {
        val safeSec = seconds.coerceIn(5, 86_400)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, safeSec)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ok = runCatching { context.startActivity(intent); true }.getOrDefault(false)
        val display = if (safeSec >= 60) "${safeSec / 60} minute" else "$safeSec second"
        return if (ok) {
            "Done Sir, $display ka timer chalu kar diya hai."
        } else {
            "Timer set nahi ho paya Sir."
        }
    }

    suspend fun executeYouTubeSearchAndPlay(context: Context, query: String): String {
        val cleanQ = query.trim()
        val intent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage("com.google.android.youtube")
            putExtra("query", cleanQ)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching { context.startActivity(intent); true }.getOrDefault(false)
        if (!launched) {
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(cleanQ)}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            runCatching { context.startActivity(webIntent) }
        }
        val a11y = JarvisAccessibilityService.instance
        if (a11y != null) {
            delay(1_800L)
            a11y.settleAndRefreshScreen(ACTION_SETTLE_MS)
            // Tap first video thumbnail/result area cleanly
            a11y.tapRelativeScreenPoint(0.50f, 0.34f)
            a11y.settleAndRefreshScreen(ACTION_SETTLE_MS)
        }
        return "YouTube par \"$cleanQ\" chala diya hai Sir."
    }

    fun executeGoogleSearch(context: Context, query: String): String {
        val cleanQ = query.trim()
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=${Uri.encode(cleanQ)}")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val ok = runCatching { context.startActivity(intent); true }.getOrDefault(false)
        return if (ok) "Google par \"$cleanQ\" search kar diya hai Sir." else "Browser open nahi ho paya Sir."
    }

    suspend fun launchCameraCapture(context: Context, isVideo: Boolean): String {
        val action = if (isVideo) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE
        val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val ok = runCatching { context.startActivity(intent); true }.getOrDefault(false)
        if (!ok) return "Camera open nahi ho paya Sir."
        return if (isVideo) "Video camera ready hai Sir." else "Camera open kar diya hai Sir, photo ke liye ready hai."
    }

    /**
     * Parses natural Hinglish/English voice commands for instant OR scheduled WhatsApp, Instagram DM/Reels,
     * SMS, Alarms, Timers, YouTube play, and Google search.
     */
    fun parseVoiceAutomationCommand(rawQuery: String): ParsedAutomationTask? {
        val query = rawQuery.trim()
        val lower = query.lowercase(Locale.ROOT)

        // 1. Instagram Reels shortcut ("Instagram kholo aur reel chalao")
        if (lower.contains("instagram") && (lower.contains("reel") || lower.contains("reels"))) {
            val trigger = parseOptionalTriggerTime(lower)
            return ParsedAutomationTask(
                taskType = TASK_TYPE_INSTAGRAM_REELS,
                target = "Instagram Reels",
                messageText = "",
                appPackage = "com.instagram.android",
                triggerAtMillis = trigger?.first,
                humanTimeLabel = trigger?.second ?: "Now"
            )
        }

        // 2. WhatsApp Message (Scheduled or Instant)
        if (lower.contains("whatsapp") && (lower.contains("bhej") || lower.contains("send") || lower.contains("message") || lower.contains("msg"))) {
            val trigger = parseOptionalTriggerTime(lower)
            val (target, msg) = extractRecipientAndMessage(query, platformKeyword = "whatsapp")
            if (target.isNotBlank()) {
                return ParsedAutomationTask(
                    taskType = TASK_TYPE_WHATSAPP_MSG,
                    target = target,
                    messageText = msg.ifBlank { "Hello" },
                    appPackage = "com.whatsapp",
                    triggerAtMillis = trigger?.first,
                    humanTimeLabel = trigger?.second ?: "Now",
                    repeatDaily = lower.contains("daily") || lower.contains("roz")
                )
            }
        }

        // 3. Instagram DM (Scheduled or Instant)
        if (lower.contains("instagram") && (lower.contains("bhej") || lower.contains("send") || lower.contains("dm") || lower.contains("message") || lower.contains("msg"))) {
            val trigger = parseOptionalTriggerTime(lower)
            val (target, msg) = extractRecipientAndMessage(query, platformKeyword = "instagram")
            if (target.isNotBlank()) {
                return ParsedAutomationTask(
                    taskType = TASK_TYPE_INSTAGRAM_DM,
                    target = target,
                    messageText = msg.ifBlank { "Hello" },
                    appPackage = "com.instagram.android",
                    triggerAtMillis = trigger?.first,
                    humanTimeLabel = trigger?.second ?: "Now",
                    repeatDaily = lower.contains("daily") || lower.contains("roz")
                )
            }
        }

        // 4. SMS (Scheduled or Instant)
        if ((lower.contains("sms") || lower.contains("text message")) && (lower.contains("bhej") || lower.contains("send"))) {
            val trigger = parseOptionalTriggerTime(lower)
            val (target, msg) = extractRecipientAndMessage(query, platformKeyword = "sms")
            if (target.isNotBlank()) {
                return ParsedAutomationTask(
                    taskType = TASK_TYPE_SMS,
                    target = target,
                    messageText = msg.ifBlank { "Hello" },
                    appPackage = "",
                    triggerAtMillis = trigger?.first,
                    humanTimeLabel = trigger?.second ?: "Now"
                )
            }
        }

        // 5. System Alarm ("6 baje ka alarm laga do", "Set alarm for 7:30 AM")
        if (lower.contains("alarm") && (lower.contains("set") || lower.contains("laga") || lower.contains("lagao") || lower.contains("kar"))) {
            val trigger = parseOptionalTriggerTime(lower)
            if (trigger != null) {
                return ParsedAutomationTask(
                    taskType = TASK_TYPE_ALARM,
                    target = trigger.second,
                    messageText = "J.A.R.V.I.S. Alarm",
                    appPackage = "",
                    triggerAtMillis = trigger.first,
                    humanTimeLabel = trigger.second
                )
            }
        }

        // 6. System Timer ("5 minute ka timer laga do", "Set timer for 2 minutes")
        if (lower.contains("timer")) {
            val durMatch = Regex("(\\d{1,3})\\s*(second|sec|minute|min|ghante|hour)", RegexOption.IGNORE_CASE).find(lower)
            if (durMatch != null) {
                val amount = durMatch.groupValues[1].toIntOrNull() ?: 1
                val unit = durMatch.groupValues[2].lowercase(Locale.ROOT)
                val seconds = when {
                    unit.startsWith("h") || unit.startsWith("g") -> amount * 3600
                    unit.startsWith("m") -> amount * 60
                    else -> amount
                }
                return ParsedAutomationTask(
                    taskType = TASK_TYPE_TIMER,
                    target = seconds.toString(),
                    messageText = "J.A.R.V.I.S. Timer",
                    appPackage = "",
                    triggerAtMillis = null,
                    humanTimeLabel = "Now"
                )
            }
        }

        // 7. Single-step YouTube Search & Play (multi-step commands like "Go to home screen, open YouTube, search MrBeast, open a video and play it"
        //    are handled sequentially by NaturalLanguageActionPlanner so every step runs and ONLY the clean keyword is searched)
        if (lower.contains("youtube") && (lower.contains("play") || lower.contains("chalao") || lower.contains("search") || lower.contains("bajao") || lower.contains("sunao"))) {
            val isMultiStep = lower.contains("home screen") || lower.contains("go home") ||
                lower.contains("open a video") || lower.contains("open video") || lower.contains(",")
            if (!isMultiStep) {
                val cleaned = NaturalLanguageActionPlanner.extractCleanYouTubeSearchKeyword(query)
                if (cleaned.length >= 2) {
                    return ParsedAutomationTask(
                        taskType = TASK_TYPE_YOUTUBE_PLAY,
                        target = cleaned,
                        messageText = "",
                        appPackage = "com.google.android.youtube",
                        triggerAtMillis = null,
                        humanTimeLabel = "Now"
                    )
                }
            }
        }

        // 8. Google / Chrome Search ("Google pe ... search karo", "Search ... on Chrome")
        if ((lower.contains("google") || lower.contains("chrome")) && (lower.contains("search") || lower.contains("dhundo") || lower.contains("khojo"))) {
            val cleaned = query
                .replace(Regex("(?i)\\b(google|chrome|pe|par|mein|me|on|open|kholo|aur|and|search|karo|dhundo|khojo|for)\\b"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (cleaned.length >= 2) {
                return ParsedAutomationTask(
                    taskType = TASK_TYPE_GOOGLE_SEARCH,
                    target = cleaned,
                    messageText = "",
                    appPackage = "com.android.chrome",
                    triggerAtMillis = null,
                    humanTimeLabel = "Now"
                )
            }
        }

        return null
    }

    private fun parseOptionalTriggerTime(lower: String): Pair<Long, String>? {
        // Check relative time first ("10 minute baad", "1 ghante baad", "in 5 minutes")
        val relMatch = Regex(
            "(?:in\\s+|after\\s+)?(\\d{1,3})\\s*(minutes?|mins?|min|ghante|ghanta|hours?|hrs?|hr)(?:\\s*(?:baad|bad|later))?",
            RegexOption.IGNORE_CASE
        ).find(lower)
        if (relMatch != null && (lower.contains("baad") || lower.contains("bad") || lower.contains("in ") || lower.contains("after ") || lower.contains("later"))) {
            val amount = relMatch.groupValues[1].toIntOrNull()?.coerceIn(1, 720) ?: return null
            val unit = relMatch.groupValues[2].lowercase(Locale.ROOT)
            val delayMs = if (unit.startsWith("h") || unit.startsWith("g")) {
                amount * 3_600_000L
            } else {
                amount * 60_000L
            }
            val triggerAt = System.currentTimeMillis() + delayMs
            val fmt = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(triggerAt))
            return triggerAt to fmt
        }

        // Check clock time ("6 baje", "7:30 pm", "subah 8 baje", "at 9:15 am")
        val hasClockMarker = lower.contains("baje") || lower.contains("am") || lower.contains("pm") || lower.contains(":")
        if (!hasClockMarker) return null

        val clockMatch = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?|baje)?", RegexOption.IGNORE_CASE).find(lower)
            ?: return null
        var hour = clockMatch.groupValues[1].toIntOrNull() ?: return null
        val minute = clockMatch.groupValues[2].toIntOrNull()?.coerceIn(0, 59) ?: 0
        val suffix = clockMatch.groupValues[3].lowercase(Locale.ROOT).replace(".", "")

        val isMorning = lower.contains("subah") || lower.contains("morning") || suffix == "am"
        val isEvening = lower.contains("shaam") || lower.contains("raat") || lower.contains("dopahar") ||
            lower.contains("evening") || lower.contains("night") || lower.contains("afternoon") || suffix == "pm"

        if (isEvening && hour in 1..11) hour += 12
        else if (isMorning && hour == 12) hour = 0
        hour = hour.coerceIn(0, 23)

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 5_000L) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val label = CallAlarmScheduler.formatTime12Hour(hour, minute)
        return cal.timeInMillis to label
    }

    private fun extractRecipientAndMessage(query: String, platformKeyword: String): Pair<String, String> {
        // Strip leading time phrases like "6 baje", "subah 7:30 baje", "10 minute baad", "abhi"
        val cleaned = query
            .replace(Regex("(?i)\\b(kal|aaj|subah|shaam|raat|abhi|please|jarvis|boss|sir)\\b"), " ")
            .replace(Regex("(?i)\\d{1,2}(?::\\d{2})?\\s*(?:a\\.?m\\.?|p\\.?m\\.?|baje)"), " ")
            .replace(Regex("(?i)(?:in\\s+|after\\s+)?\\d{1,3}\\s*(?:minutes?|mins?|min|ghante|ghanta|hours?)\\s*(?:baad|bad|later)?"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Pattern 1 (Hinglish): "[Name] ko WhatsApp/Instagram pe [Message] bhej dena"
        val hindiPattern1 = Regex(
            "(?i)(.+?)\\s+ko\\s+(?:$platformKeyword\\s+(?:pe|par|mein|me)?\\s*)?(.+?)\\s*(?:bhej(?:\\s+dena|\\s+do|o)?|send\\s+kar(?:\\s+do|o|na)?|message\\s+bhej.*)$"
        ).find(cleaned)
        if (hindiPattern1 != null) {
            val rawTarget = hindiPattern1.groupValues[1]
                .replace(Regex("(?i)\\b($platformKeyword|pe|par|mein|me|dm|message|msg)\\b"), "")
                .trim()
            val rawMsg = hindiPattern1.groupValues[2]
                .replace(Regex("(?i)^(?:message|msg|dm|ki|ke)\\s+"), "")
                .trim()
            if (rawTarget.isNotBlank()) return rawTarget to rawMsg
        }

        // Pattern 2 (Hinglish): "WhatsApp/Instagram pe [Name] ko [Message] bhej dena"
        val hindiPattern2 = Regex(
            "(?i)$platformKeyword\\s+(?:pe|par|mein|me)\\s+(.+?)\\s+ko\\s+(.+?)\\s*(?:bhej(?:\\s+dena|\\s+do|o)?|send.*)$"
        ).find(cleaned)
        if (hindiPattern2 != null) {
            val rawTarget = hindiPattern2.groupValues[1].trim()
            val rawMsg = hindiPattern2.groupValues[2]
                .replace(Regex("(?i)^(?:message|msg|dm|ki)\\s+"), "")
                .trim()
            if (rawTarget.isNotBlank()) return rawTarget to rawMsg
        }

        // Pattern 3 (English): "Send [Message] to [Name] on WhatsApp/Instagram"
        val engPattern = Regex(
            "(?i)(?:send|message|dm)\\s+(?:a\\s+message\\s+to\\s+|to\\s+)?([^,]+?)(?:\\s+on\\s+$platformKeyword)?\\s+(?:saying|that|message|:)\\s*(.+)$"
        ).find(cleaned)
        if (engPattern != null) {
            val rawTarget = engPattern.groupValues[1].replace(Regex("(?i)\\bon\\s+$platformKeyword\\b"), "").trim()
            val rawMsg = engPattern.groupValues[2].trim()
            if (rawTarget.isNotBlank()) return rawTarget to rawMsg
        }

        // Pattern 4 (English): "Send [Message] to [Name]"
        val engSimple = Regex(
            "(?i)send\\s+(.+?)\\s+to\\s+(.+?)(?:\\s+on\\s+$platformKeyword)?$"
        ).find(cleaned)
        if (engSimple != null) {
            val rawMsg = engSimple.groupValues[1].replace(Regex("(?i)\\b($platformKeyword|message|dm)\\b"), "").trim()
            val rawTarget = engSimple.groupValues[2].replace(Regex("(?i)\\bon\\s+$platformKeyword\\b"), "").trim()
            if (rawTarget.isNotBlank()) return rawTarget to rawMsg
        }

        return "" to ""
    }

    fun formatTaskTriggerTime(triggerAtMillis: Long): String {
        return SimpleDateFormat("EEE, h:mm a", Locale.getDefault()).format(Date(triggerAtMillis))
    }

    private fun taskRequestCode(taskId: Long): Int = 50_000 + (taskId % 10_000L).toInt()
    private fun workUniqueName(taskId: Long): String = "jarvis_scheduled_task_$taskId"
}
