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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Autonomous Background Auto-Reply Chat Listener for WhatsApp, Telegram, Instagram, and SMS.
 * Reads incoming chat notifications, generates natural human-like replies strictly via
 * gemini-3.5-flash using the user's Master Prompt, sends replies silently via RemoteInput,
 * and stores all incoming/outgoing messages in local Room Database (AutoChatLogsEntity).
 */
class JarvisNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val geminiApiClient = GeminiApiClient()

    // Loop-prevention caches: deduplicate identical messages and throttle rapid replies per sender
    private val recentProcessedFingerprints = ConcurrentHashMap<String, Long>()
    private val lastReplyTimeBySender = ConcurrentHashMap<String, Long>()

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

        val appLabel = SUPPORTED_CHAT_APPS[pkg] ?: return
        val notification = sbn.notification ?: return

        // Ignore group summary notifications to avoid duplicate triggers
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val replyAction = findReplyAction(notification) ?: return
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

        if (rawTitle.isBlank() || rawMessage.isBlank()) return
        // Skip generic non-message summaries like "3 new messages" or "Checking for new messages"
        if (isNonReplyableSystemSummary(rawMessage)) return

        val now = System.currentTimeMillis()
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

            // Always record incoming supported chat notifications locally when Auto-Reply is enabled
            if (!settings.autoReplyEnabled) return@launch

            // Optional target contact filter (if blank, reply to all supported incoming chats)
            val targetFilter = settings.autoReplyTargetContact.trim()
            if (targetFilter.isNotEmpty() &&
                !rawTitle.contains(targetFilter, ignoreCase = true)
            ) {
                return@launch
            }

            // Save incoming message locally
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
                _lastAutoReplyBanner.value = "Auto-Reply skipped for $rawTitle: Gemini API key not set."
                return@launch
            }

            val recentConvo = repository.getAutoChatLogsForSender(rawTitle, limit = 8)
            val replyResult = geminiApiClient.generateAutoChatReply(
                rawApiKey = settings.geminiApiKey,
                masterSystemPrompt = settings.masterSystemPrompt,
                senderName = rawTitle,
                appLabel = appLabel,
                incomingMessage = rawMessage,
                recentConversation = recentConvo
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
            lower.contains("new messages") && lower.matches(Regex("^\\d+\\s+new messages.*")) ||
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
