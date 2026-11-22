package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Automatic YouTube Video Performance & View Milestone Monitor (WorkManager).
 *
 * - Polls monitored videos in 1 batched YouTube Data API v3 call (quota-friendly!).
 * - Evaluates user-configurable view milestones (100, 500, 1,000, 5,000, 10,000, + custom).
 * - Prevents duplicate alerts permanently via `reachedMilestonesCsv` in Room DB.
 * - Reuses the EXISTING in-app JARVIS calling system (`CallAlarmReceiver.launchIncomingCall` ->
 *   `JarvisIncomingCallActivity`) with `CALL_TYPE_YOUTUBE_MILESTONE`.
 * - Respects Android Do Not Disturb settings and Android 10+ background activity launch restrictions
 *   (falls back cleanly to an actionable notification that opens the JARVIS in-app call when tapped).
 */
class YouTubeMilestoneMonitorWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val outcome = YouTubeMilestoneMonitorScheduler.runSingleCheckNow(applicationContext)
        return if (outcome.isSuccess) Result.success() else Result.retry()
    }
}

data class MilestoneCheckSummary(
    val videosChecked: Int,
    val milestonesTriggered: Int,
    val statusMessage: String
)

object YouTubeMilestoneMonitorScheduler {

    private const val PERIODIC_WORK_NAME = "jarvis_youtube_milestone_monitor"
    private const val MILESTONE_CHANNEL_ID = "jarvis_yt_milestone_channel"
    private const val MILESTONE_NOTIF_BASE_ID = 8400

    fun syncPeriodicSchedule(context: Context) {
        val appContext = context.applicationContext
        val cfg = SecureCredentialVault.getInstance(appContext).loadConfig()
        val wm = WorkManager.getInstance(appContext)

        if (!cfg.monitoringEnabled) {
            wm.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }

        val intervalMin = cfg.monitoringIntervalMinutes.coerceAtLeast(15).toLong()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<YouTubeMilestoneMonitorWorker>(intervalMin, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        wm.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun triggerImmediateBackgroundCheck(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val req = OneTimeWorkRequestBuilder<YouTubeMilestoneMonitorWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueue(req)
    }

    /**
     * Pure helper for evaluating newly crossed milestones (easily unit-testable!).
     * Returns only milestones that are <= currentViews AND NOT already in reachedSet.
     */
    fun detectNewCrossedMilestones(
        currentViews: Long,
        configuredMilestones: List<Long>,
        alreadyReached: Set<Long>
    ): List<Long> {
        return configuredMilestones
            .filter { it > 0L && currentViews >= it && it !in alreadyReached }
            .sorted()
    }

    suspend fun runSingleCheckNow(context: Context): kotlin.Result<MilestoneCheckSummary> {
        val appContext = context.applicationContext
        val repo = JarvisRepository(JarvisDatabase.getInstance(appContext))
        val vault = SecureCredentialVault.getInstance(appContext)
        val cfg = vault.loadConfig()

        val monitored = repo.getActiveMonitoredVideos()
        if (monitored.isEmpty()) {
            return kotlin.Result.success(MilestoneCheckSummary(0, 0, "No monitored videos registered yet."))
        }

        val ytClient = YouTubeAuthAndApiClient(appContext)
        val statsRes = ytClient.fetchBatchVideoStats(monitored.map { it.videoId })
        val statsMap = statsRes.getOrElse { return kotlin.Result.failure(it) }

        val thresholds = cfg.parsedMilestones()
        var triggeredCount = 0

        for (video in monitored) {
            val fresh = statsMap[video.videoId] ?: continue
            val reachedSet = video.reachedMilestonesCsv
                .split(',')
                .mapNotNull { it.trim().toLongOrNull() }
                .toMutableSet()

            val newlyCrossed = detectNewCrossedMilestones(fresh.views, thresholds, reachedSet)
            val growthDelta = (fresh.views - video.currentViews).coerceAtLeast(0L)

            if (newlyCrossed.isNotEmpty()) {
                // Mark ALL newly crossed thresholds as reached immediately so duplicate calls NEVER fire
                reachedSet.addAll(newlyCrossed)
                val highestMilestone = newlyCrossed.max()
                triggeredCount++

                val milestoneMessage = buildMilestoneBriefing(
                    videoTitle = fresh.title,
                    milestone = highestMilestone,
                    currentViews = fresh.views,
                    growthDelta = growthDelta
                )

                // Record alert in existing local call-alert table
                repo.addPendingCallAlert(
                    PendingCallAlertEntity(
                        title = "YouTube Milestone: ${formatCount(highestMilestone)} Views",
                        summary = milestoneMessage,
                        sourceApp = "YouTube",
                        alertCategory = ALERT_CATEGORY_VIRAL,
                        handled = true
                    )
                )

                dispatchMilestoneAlertOrInAppCall(
                    context = appContext,
                    config = cfg,
                    videoId = video.videoId,
                    videoTitle = fresh.title,
                    milestone = highestMilestone,
                    briefingText = milestoneMessage
                )
            }

            repo.upsertMonitoredVideo(
                video.copy(
                    title = fresh.title.ifBlank { video.title },
                    thumbnailUrl = fresh.thumbnailUrl.ifBlank { video.thumbnailUrl },
                    previousViews = video.currentViews,
                    currentViews = fresh.views,
                    currentLikes = fresh.likes,
                    currentComments = fresh.comments,
                    reachedMilestonesCsv = reachedSet.sorted().joinToString(","),
                    lastCheckedAt = System.currentTimeMillis()
                )
            )
            repo.recordVideoViewSnapshot(
                videoId = video.videoId,
                views = fresh.views,
                likes = fresh.likes,
                comments = fresh.comments
            )
        }

        val summaryText = "Checked ${monitored.size} monitored video(s); $triggeredCount new milestone(s) triggered."
        return kotlin.Result.success(MilestoneCheckSummary(monitored.size, triggeredCount, summaryText))
    }

    fun buildMilestoneBriefing(
        videoTitle: String,
        milestone: Long,
        currentViews: Long,
        growthDelta: Long
    ): String {
        val mStr = formatCount(milestone)
        val vStr = formatCount(currentViews)
        val growthNote = if (growthDelta > 0L) " (+${formatCount(growthDelta)} since last check)" else ""
        return "Sir, your video \"$videoTitle\" has crossed $mStr views and now has $vStr views$growthNote. Its performance is improving. I have content and SEO strategy suggestions for your next upload — would you like to discuss them?"
    }

    private fun dispatchMilestoneAlertOrInAppCall(
        context: Context,
        config: YouTubeCreatorConfig,
        videoId: String,
        videoTitle: String,
        milestone: Long,
        briefingText: String
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val isDndActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        } else {
            false
        }

        // Respect Do Not Disturb strictly when configured
        if (config.respectDoNotDisturb && isDndActive) {
            if (config.backgroundNotificationFallback) {
                postMilestoneNotification(context, videoId, videoTitle, milestone, briefingText, silent = true)
            }
            return
        }

        if (config.milestoneInAppCallEnabled) {
            // Reuse EXISTING JARVIS In-App Incoming Call System (JarvisIncomingCallActivity)!
            // When app is foregrounded or full-screen intent is permitted, it launches the existing call UI;
            // under Android background launch restrictions, the high-priority call notification lets the user answer with 1 tap.
            CallAlarmReceiver.launchIncomingCall(
                context = context,
                purpose = "YouTube Milestone: ${formatCount(milestone)} Views • ${videoTitle.take(40)}",
                actionType = CALL_TYPE_YOUTUBE_MILESTONE,
                alertSummary = briefingText
            )
        } else if (config.backgroundNotificationFallback) {
            postMilestoneNotification(context, videoId, videoTitle, milestone, briefingText, silent = false)
        }
    }

    private fun postMilestoneNotification(
        context: Context,
        videoId: String,
        videoTitle: String,
        milestone: Long,
        briefingText: String,
        silent: Boolean
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MILESTONE_CHANNEL_ID,
                "JARVIS YouTube Milestones",
                if (silent) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when monitored YouTube videos cross configured view milestones."
            }
            nm.createNotificationChannel(channel)
        }

        val callIntent = Intent(context, JarvisIncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(CallAlarmReceiver.EXTRA_CALL_PURPOSE, "YouTube Milestone: ${formatCount(milestone)} Views")
            putExtra(CallAlarmReceiver.EXTRA_CALL_ACTION_TYPE, CALL_TYPE_YOUTUBE_MILESTONE)
            putExtra(CallAlarmReceiver.EXTRA_ALERT_SUMMARY, briefingText)
        }
        val pi = PendingIntent.getActivity(
            context,
            (videoId.hashCode() and 0x7FFF) + 100,
            callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, MILESTONE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("J.A.R.V.I.S. • ${formatCount(milestone)} Views Reached!")
            .setContentText("\"$videoTitle\" — Tap to start JARVIS voice strategy briefing.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(briefingText))
            .setAutoCancel(true)
            .setSilent(silent)
            .setContentIntent(pi)
            .build()

        runCatching { nm.notify(MILESTONE_NOTIF_BASE_ID + (videoId.hashCode() and 0xFF), notif) }
    }

    private fun formatCount(value: Long): String {
        return String.format(java.util.Locale.US, "%,d", value)
    }
}
