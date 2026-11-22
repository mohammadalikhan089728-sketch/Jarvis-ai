package com.example

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Dual-Purpose Smart Notification Listener for JARVIS (Developed by Mozahid):
 * 1. Autonomous WhatsApp / Telegram / Instagram / SMS background Auto-Reply Chatting via RemoteInput.
 * 2. Zero-API Viral Milestone, Engagement Surge (15+ notifications in 5 mins), and Urgent Alert Detector
 *    that saves PendingCallAlertEntity items locally and triggers NetworkMonitorService auto-calls.
 */
class JarvisNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val aiClient = GeminiApiClient()

    private val recentProcessedFingerprints = ConcurrentHashMap<String, Long>()
    private val lastReplyTimeBySender = ConcurrentHashMap<String, Long>()
    private val socialBurstTimestamps = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val lastSurgeAlertByApp = ConcurrentHashMap<String, Long>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isConnected.value = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _isConnected.value = false
    }

    override fun onDestroy() {
        super.onDestroy()
        _isConnected.value = false
        serviceScope.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notification.extras ?: return
        val rawTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?.trim()
                .orEmpty()

        val rawMessage = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?.trim()
                .orEmpty()

        if (rawTitle.isBlank() && rawMessage.isBlank()) return

        val now = System.currentTimeMillis()

        // A. Zero-API Viral Milestone, Engagement Surge, and Urgent Message Detection
        inspectForViralOrUrgentAlert(pkg, rawTitle, rawMessage, now)

        // B. Autonomous Background Chat Auto-Reply via RemoteInput
        val appLabel = SUPPORTED_CHAT_APPS[pkg] ?: return
        val replyAction = findReplyAction(notification) ?: return
        if (rawTitle.isBlank() || rawMessage.isBlank()) return
        if (isNonReplyableSystemSummary(rawMessage)) return

        val fingerprint = "${pkg}|${rawTitle.lowercase(Locale.ROOT)}|${rawMessage.lowercase(Locale.ROOT)}"
        val previousSeen = recentProcessedFingerprints[fingerprint]
        if (previousSeen != null && (now - previousSeen) < DUPLICATE_WINDOW_MS) {
            return
        }
        recentProcessedFingerprints[fingerprint] = now
        pruneStaleCache(now)

        val senderKey = "${pkg}|${rawTitle.lowercase(Locale.ROOT)}"
        val lastReplied = lastReplyTimeBySender[senderKey] ?: 0L
        if ((now - lastReplied) < PER_SENDER_COOLDOWN_MS) {
            return
        }

        serviceScope.launch {
            val repository = JarvisRepository(JarvisDatabase.getInstance(applicationContext))
            val settings = repository.getSettings()
            if (!settings.autoReplyEnabled) return@launch

            val targetFilter = settings.autoReplyTargetContact.trim()
            if (targetFilter.isNotEmpty() &&
                !rawTitle.contains(targetFilter, ignoreCase = true)
            ) {
                return@launch
            }

            repository.logAutoChat(
                AutoChatLogsEntity(
                    senderName = rawTitle,
                    appPackage = pkg,
                    appLabel = appLabel,
                    direction = AUTO_CHAT_DIR_INCOMING,
                    messageText = rawMessage,
                    timestamp = now
                )
            )

            if (settings.geminiApiKey.isBlank()) {
                _lastAutoReplyBanner.value = "Auto-Reply skipped for $rawTitle: AI Core API key not set."
                return@launch
            }

            val recentConvo = repository.getAutoChatLogsForSender(rawTitle, limit = 8)
            val replyResult = aiClient.generateAutoChatReply(
                rawApiKey = settings.geminiApiKey,
                masterSystemPrompt = settings.masterSystemPrompt,
                senderName = rawTitle,
                appLabel = appLabel,
                incomingMessage = rawMessage,
                recentConversation = recentConvo,
                selectedEngine = settings.coreEngine
            )

            val replyText = replyResult.getOrNull()?.trim().orEmpty()
            if (replyText.isBlank()) return@launch

            val sent = sendDirectNotificationReply(replyAction, replyText)
            if (sent) {
                lastReplyTimeBySender[senderKey] = System.currentTimeMillis()
                repository.logAutoChat(
                    AutoChatLogsEntity(
                        senderName = rawTitle,
                        appPackage = pkg,
                        appLabel = appLabel,
                        direction = AUTO_CHAT_DIR_OUTGOING,
                        messageText = replyText,
                        timestamp = System.currentTimeMillis()
                    )
                )
                _lastAutoReplyBanner.value = "Auto-replied to $rawTitle ($appLabel): \"$replyText\""
            }
        }
    }

    private fun inspectForViralOrUrgentAlert(
        pkg: String,
        title: String,
        message: String,
        now: Long
    ) {
        val combinedLower = "$title $message".lowercase(Locale.ROOT)
        val socialLabel = SOCIAL_APPS[pkg]
        val chatLabel = SUPPORTED_CHAT_APPS[pkg]

        var alertTitle: String? = null
        var alertSummary: String? = null
        var alertCategory: String? = null
        val sourceLabel = socialLabel ?: chatLabel ?: if (pkg.contains("dialer") || pkg.contains("telecom")) "Phone" else null

        if (socialLabel != null) {
            // 1. Check viral / views milestone keywords
            val hasViralKeyword = VIRAL_KEYWORDS.any { combinedLower.contains(it) }
            if (hasViralKeyword) {
                alertTitle = "$socialLabel Milestone Alert"
                alertSummary = "$title: $message".trim().take(220)
                alertCategory = ALERT_CATEGORY_VIRAL
            }

            // 2. Track engagement surges (15+ likes/comments/notifications within 5 minutes)
            val isEngagement = combinedLower.contains("liked") ||
                combinedLower.contains("comment") ||
                combinedLower.contains("reel") ||
                combinedLower.contains("video") ||
                combinedLower.contains("followed") ||
                combinedLower.contains("subscribed")
            if (isEngagement) {
                val deque = socialBurstTimestamps.getOrPut(pkg) { ArrayDeque() }
                synchronized(deque) {
                    deque.addLast(now)
                    while (deque.isNotEmpty() && (now - deque.first()) > SURGE_WINDOW_MS) {
                        deque.removeFirst()
                    }
                    val lastSurge = lastSurgeAlertByApp[pkg] ?: 0L
                    if (deque.size >= SURGE_THRESHOLD_COUNT && (now - lastSurge) > SURGE_WINDOW_MS) {
                        lastSurgeAlertByApp[pkg] = now
                        alertTitle = "$socialLabel Viral Activity Surge!"
                        alertSummary = "${deque.size}+ rapid interactions detected in the last 5 minutes on $socialLabel."
                        alertCategory = ALERT_CATEGORY_SURGE
                    }
                }
            }
        }

        // 3. Check urgent messages / missed calls
        if (alertCategory == null && sourceLabel != null) {
            val isUrgent = URGENT_KEYWORDS.any { combinedLower.contains(it) }
            if (isUrgent) {
                alertTitle = "Urgent Alert from ${title.ifBlank { sourceLabel }}"
                alertSummary = message.ifBlank { title }.take(220)
                alertCategory = ALERT_CATEGORY_URGENT
            }
        }

        if (alertTitle != null && alertSummary != null && alertCategory != null && sourceLabel != null) {
            val finalTitle = alertTitle
            val finalSummary = alertSummary
            val finalCat = alertCategory
            serviceScope.launch {
                val repo = JarvisRepository(JarvisDatabase.getInstance(applicationContext))
                repo.addPendingCallAlert(
                    PendingCallAlertEntity(
                        title = finalTitle,
                        summary = finalSummary,
                        sourceApp = sourceLabel,
                        alertCategory = finalCat,
                        handled = false,
                        createdAt = now
                    )
                )
                NetworkMonitorService.triggerAlertCallIfOnline(applicationContext)
            }
        }
    }

    private fun findReplyAction(notification: Notification): Notification.Action? {
        val actions = notification.actions ?: return null
        return actions.firstOrNull { action ->
            action.actionIntent != null && !action.remoteInputs.isNullOrEmpty()
        }
    }

    private fun sendDirectNotificationReply(
        action: Notification.Action,
        replyText: String
    ): Boolean {
        val remoteInputs = action.remoteInputs ?: return false
        val pendingIntent = action.actionIntent ?: return false
        return try {
            val fillInIntent = Intent()
            val resultsBundle = Bundle()
            for (input in remoteInputs) {
                resultsBundle.putCharSequence(input.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(remoteInputs, fillInIntent, resultsBundle)
            pendingIntent.send(applicationContext, 0, fillInIntent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isNonReplyableSystemSummary(message: String): Boolean {
        val lower = message.lowercase(Locale.ROOT)
        return lower.contains("checking for new messages") ||
            (lower.contains("new messages") && lower.matches(Regex("^\\d+\\s+new messages.*"))) ||
            lower.contains("missed voice call") ||
            lower.contains("missed video call") ||
            lower.contains("incoming voice call") ||
            lower.contains("incoming video call") ||
            lower == "typing…" ||
            lower == "typing..."
    }

    private fun pruneStaleCache(now: Long) {
        if (recentProcessedFingerprints.size > 120) {
            recentProcessedFingerprints.entries.removeIf { (_, ts) ->
                (now - ts) > DUPLICATE_WINDOW_MS * 2
            }
        }
    }

    companion object {
        private const val DUPLICATE_WINDOW_MS = 12_000L
        private const val PER_SENDER_COOLDOWN_MS = 5_000L
        private const val SURGE_WINDOW_MS = 5 * 60 * 1000L // 5 minutes
        private const val SURGE_THRESHOLD_COUNT = 15

        private val VIRAL_KEYWORDS = listOf(
            "views", "plays", "viral", "subscribers", "10,000", "10k",
            "100k", "1m", "million", "lakh", "trending", "milestone"
        )

        private val URGENT_KEYWORDS = listOf(
            "urgent", "emergency", "call me", "missed call", "jaldi call", "turant"
        )

        val SOCIAL_APPS: Map<String, String> = mapOf(
            "com.instagram.android" to "Instagram",
            "com.google.android.youtube" to "YouTube",
            "com.google.android.apps.youtube.creator" to "YouTube Studio"
        )

        val SUPPORTED_CHAT_APPS: Map<String, String> = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "org.telegram.messenger" to "Telegram",
            "org.telegram.messenger.web" to "Telegram",
            "com.instagram.android" to "Instagram",
            "com.google.android.apps.messaging" to "Messages",
            "com.android.mms" to "SMS",
            "com.samsung.android.messaging" to "Messages"
        )

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _lastAutoReplyBanner = MutableStateFlow("")
        val lastAutoReplyBanner: StateFlow<String> = _lastAutoReplyBanner.asStateFlow()
    }
}
