package com.example

import android.content.Context
import android.media.AudioManager
import org.json.JSONObject
import java.util.Locale

data class RelativeDelayMatch(
    val delayMillis: Long,
    val humanDelayLabel: String,
    val purpose: String
)

sealed interface LocalRouteResult {
    /** Input was empty or < 3 characters (noise guard — 0 API tokens spent). */
    data object IgnoredNoise : LocalRouteResult

    /** Command was NOT handled locally; forward to remote AI Core. */
    data object NotHandled : LocalRouteResult

    /** Command was executed 100% locally with ZERO API tokens. */
    data class Handled(
        val actionTag: String,
        val paramsJson: String,
        val reply: String,
        val shouldEndCall: Boolean = false
    ) : LocalRouteResult
}

/**
 * Zero-Token Local Offline Instant Intent Router for JARVIS (Developed by Mozahid).
 * Intercepts common navigation, hardware, memory-saving, call-hangup, and relative call-scheduling
 * commands BEFORE touching any remote AI API (0 API tokens, <30ms execution).
 */
class LocalCommandRouter(
    private val appContext: Context,
    private val repository: JarvisRepository,
    private val offlineProcessor: OfflineCommandProcessor,
    private val memoryManager: MemoryManager
) {

    suspend fun tryRoute(
        rawQuery: String,
        inActiveCall: Boolean = false,
        defaultCallPurpose: String = "JARVIS Follow-Up Call",
        defaultCallActionType: String = CALL_TYPE_CUSTOM_REMINDER
    ): LocalRouteResult {
        val query = rawQuery.trim()
        // Strict noise & token guard: ignore empty or < 3-char speech fragments
        if (isShortOrNoise(query)) {
            return LocalRouteResult.IgnoredNoise
        }

        val lower = query.lowercase(Locale.ROOT)
        val wantsEndCall = matchesEndCallIntent(lower)
        val relativeCall = parseRelativeCallDelay(query, lower, defaultCallPurpose)

        // 1. Combined In-Call Hangup + Relative Re-Call ("Theek hai abhi cut kar do, 10 minute baad call karna")
        if (relativeCall != null && (wantsEndCall || inActiveCall)) {
            val saved = CallAlarmScheduler.scheduleRelativeCall(
                context = appContext,
                repository = repository,
                delayMillis = relativeCall.delayMillis,
                purpose = relativeCall.purpose,
                actionType = defaultCallActionType
            )
            val timeStr = CallAlarmScheduler.formatTime12Hour(saved.hour, saved.minute)
            val reply = if (wantsEndCall) {
                "Theek hai Sir, ${relativeCall.humanDelayLabel} baad $timeStr par call karunga. Abhi call cut kar raha hu."
            } else {
                "Done Sir, ${relativeCall.humanDelayLabel} baad $timeStr par call schedule kar diya hai."
            }
            return LocalRouteResult.Handled(
                actionTag = if (wantsEndCall) "LOCAL:CALL_RESCHEDULE_HANGUP" else "LOCAL:CALL_SCHEDULE_RELATIVE",
                paramsJson = JSONObject()
                    .put("delayMs", relativeCall.delayMillis)
                    .put("time", timeStr)
                    .put("hangup", wantsEndCall)
                    .toString(),
                reply = reply,
                shouldEndCall = wantsEndCall
            )
        }

        // 2. Standalone Relative Call Scheduling outside a call ("10 minute baad call karna")
        if (relativeCall != null) {
            val saved = CallAlarmScheduler.scheduleRelativeCall(
                context = appContext,
                repository = repository,
                delayMillis = relativeCall.delayMillis,
                purpose = relativeCall.purpose,
                actionType = inferCallTypeFromText(lower, defaultCallActionType)
            )
            val timeStr = CallAlarmScheduler.formatTime12Hour(saved.hour, saved.minute)
            return LocalRouteResult.Handled(
                actionTag = "LOCAL:CALL_SCHEDULE_RELATIVE",
                paramsJson = JSONObject()
                    .put("delayMs", relativeCall.delayMillis)
                    .put("time", timeStr)
                    .toString(),
                reply = "Done Sir, ${relativeCall.humanDelayLabel} baad $timeStr par JARVIS aapko call karega.",
                shouldEndCall = false
            )
        }

        // 3. Pure In-Call Hangup ("Call cut kar do", "Phone rakh do", "End call")
        if (wantsEndCall && inActiveCall) {
            return LocalRouteResult.Handled(
                actionTag = "LOCAL:CALL_END",
                paramsJson = "{}",
                reply = "Theek hai Sir, abhi call cut kar raha hu. Take care!",
                shouldEndCall = true
            )
        }

        // 4. Explicit Long-Term Memory Save ("Yaad rakhna...", "Remember...") — 0 API tokens
        val rememberedFact = memoryManager.tryCaptureExplicitRememberCommand(query)
        if (rememberedFact != null) {
            return LocalRouteResult.Handled(
                actionTag = "LOCAL:MEMORY_SAVED",
                paramsJson = JSONObject().put("fact", rememberedFact).toString(),
                reply = "Samajh gaya Sir, maine apni Memory Vault mein save kar liya hai."
            )
        }

        // Skip device navigation/hardware routing during an active call unless explicitly spoken
        if (inActiveCall) {
            return LocalRouteResult.NotHandled
        }

        val a11y = JarvisAccessibilityService.instance

        // 5. Instant Navigation & Hardware Controls (0 API Tokens!)
        when {
            lower.contains("home screen") ||
                lower.contains("home kholo") ||
                lower.contains("home par jao") ||
                lower.contains("home pe jao") ||
                lower == "go home" ||
                lower == "home" -> {
                val ok = a11y?.performGlobalSystemAction("HOME") == true
                return LocalRouteResult.Handled(
                    actionTag = "LOCAL:HOME",
                    paramsJson = "{}",
                    reply = if (ok) "Home screen khol diya hai Sir." else "Please enable JARVIS Accessibility Service to go Home."
                )
            }

            lower == "back" ||
                lower.contains("back karo") ||
                lower.contains("go back") ||
                lower.contains("piche jao") ||
                lower.contains("peeche jao") -> {
                val ok = a11y?.performGlobalSystemAction("BACK") == true
                return LocalRouteResult.Handled(
                    actionTag = "LOCAL:BACK",
                    paramsJson = "{}",
                    reply = if (ok) "Back kar diya Sir." else "Please enable JARVIS Accessibility Service first."
                )
            }

            lower.contains("recent apps") ||
                lower.contains("recents") ||
                lower.contains("recent kholo") -> {
                val ok = a11y?.performGlobalSystemAction("RECENTS") == true
                return LocalRouteResult.Handled(
                    actionTag = "LOCAL:RECENTS",
                    paramsJson = "{}",
                    reply = if (ok) "Recent apps khol diye hain Sir." else "Please enable JARVIS Accessibility Service first."
                )
            }

            lower.contains("scroll down") ||
                lower.contains("niche scroll") ||
                lower.contains("neeche scroll") ||
                lower.contains("niche karo") -> {
                val ok = a11y?.scrollDown() == true
                return LocalRouteResult.Handled(
                    actionTag = "LOCAL:SCROLL_DOWN",
                    paramsJson = "{}",
                    reply = if (ok) "Neeche scroll kar diya Sir." else "Accessibility Service active nahi hai Sir."
                )
            }

            lower.contains("scroll up") ||
                lower.contains("upar scroll") ||
                lower.contains("upar karo") -> {
                val ok = a11y?.scrollUp() == true
                return LocalRouteResult.Handled(
                    actionTag = "LOCAL:SCROLL_UP",
                    paramsJson = "{}",
                    reply = if (ok) "Upar scroll kar diya Sir." else "Accessibility Service active nahi hai Sir."
                )
            }

            (lower.contains("torch") || lower.contains("flashlight") || lower.contains("flash light")) &&
                (lower.contains("on") || lower.contains("chalu") || lower.contains("jala")) -> {
                val ok = offlineProcessor.toggleFlashlight(true)
                return LocalRouteResult.Handled(
                    actionTag = "LOCAL:FLASHLIGHT_ON",
                    paramsJson = "{}",
                    reply = if (ok) "Flashlight on kar di hai Sir." else "Flashlight available nahi hai Sir."
                )
            }

            (lower.contains("torch") || lower.contains("flashlight") || lower.contains("flash light")) &&
                (lower.contains("off") || lower.contains("band") || lower.contains("bujha")) -> {
                val ok = offlineProcessor.toggleFlashlight(false)
                return LocalRouteResult.Handled(
                    actionTag = "LOCAL:FLASHLIGHT_OFF",
                    paramsJson = "{}",
                    reply = if (ok) "Flashlight band kar di hai Sir." else "Flashlight available nahi hai Sir."
                )
            }

            lower.contains("volume up") ||
                lower.contains("increase volume") ||
                lower.contains("awaz badhao") ||
                lower.contains("aawaz badhao") ||
                lower.contains("volume badhao") -> {
                offlineProcessor.adjustVolume(AudioManager.ADJUST_RAISE)
                return LocalRouteResult.Handled(
                    actionTag = "LOCAL:VOLUME_UP",
                    paramsJson = "{}",
                    reply = "Volume badha diya hai Sir."
                )
            }

            lower.contains("volume down") ||
                lower.contains("decrease volume") ||
                lower.contains("lower volume") ||
                lower.contains("awaz kam") ||
                lower.contains("aawaz kam") ||
                lower.contains("volume kam") -> {
                offlineProcessor.adjustVolume(AudioManager.ADJUST_LOWER)
                return LocalRouteResult.Handled(
                    actionTag = "LOCAL:VOLUME_DOWN",
                    paramsJson = "{}",
                    reply = "Volume kam kar diya hai Sir."
                )
            }
        }

        return LocalRouteResult.NotHandled
    }

    fun isShortOrNoise(raw: String): Boolean {
        val stripped = raw.trim().replace(Regex("[^a-zA-Z0-9\\u0900-\\u097F]"), "")
        if (stripped.length < MIN_VALID_SPEECH_CHARS) return true
        val lower = raw.trim().lowercase(Locale.ROOT)
        return lower in FILLER_NOISE_WORDS
    }

    fun matchesEndCallIntent(lower: String): Boolean {
        return lower.contains("call cut") ||
            lower.contains("cut kar") ||
            lower.contains("cut the call") ||
            lower.contains("phone rakh") ||
            lower.contains("abhi rakh") ||
            lower.contains("rakh do") ||
            lower.contains("end call") ||
            lower.contains("hang up") ||
            lower.contains("call kaat") ||
            lower.contains("call band") ||
            lower.contains("bye jarvis")
    }

    fun parseRelativeCallDelay(
        original: String,
        lower: String,
        defaultPurpose: String
    ): RelativeDelayMatch? {
        val mentionsCall = lower.contains("call") || lower.contains("ring") || lower.contains("phone karna")
        if (!mentionsCall) return null

        // Special Hindi/English half-hour phrasing
        if (lower.contains("aadhe ghante") || lower.contains("adhe ghante") || lower.contains("half an hour") || lower.contains("half hour")) {
            return RelativeDelayMatch(
                delayMillis = 30L * 60L * 1000L,
                humanDelayLabel = "30 minute",
                purpose = buildPurposeFromRelative(original, defaultPurpose)
            )
        }

        // Normalize common spoken number words before regex matching
        val normalized = lower
            .replace(Regex("\\b(ek|one|a)\\s+(minute|min|ghante|ghanta|hour|hr)"), "1 $2")
            .replace(Regex("\\b(do|two)\\s+(minute|min|ghante|ghanta|hour|hr)"), "2 $2")
            .replace(Regex("\\b(teen|three)\\s+(minute|min|ghante|ghanta|hour|hr)"), "3 $2")
            .replace(Regex("\\b(paanch|panch|five)\\s+(minute|min|ghante|ghanta|hour|hr)"), "5 $2")
            .replace(Regex("\\b(das|ten)\\s+(minute|min|ghante|ghanta|hour|hr)"), "10 $2")
            .replace(Regex("\\b(pandrah|fifteen)\\s+(minute|min|ghante|ghanta|hour|hr)"), "15 $2")
            .replace(Regex("\\b(bees|twenty)\\s+(minute|min|ghante|ghanta|hour|hr)"), "20 $2")
            .replace(Regex("\\b(tees|thirty)\\s+(minute|min|ghante|ghanta|hour|hr)"), "30 $2")

        val match = RELATIVE_TIME_REGEX.find(normalized) ?: return null
        val amount = match.groupValues[1].toIntOrNull()?.coerceIn(1, 720) ?: return null
        val unit = match.groupValues[2].lowercase(Locale.ROOT)
        val isHour = unit.startsWith("h") || unit.startsWith("g")

        val delayMillis = if (isHour) {
            amount.toLong() * 60L * 60L * 1000L
        } else {
            amount.toLong() * 60L * 1000L
        }
        val label = if (isHour) {
            if (amount == 1) "1 ghante" else "$amount ghante"
        } else {
            "$amount minute"
        }

        return RelativeDelayMatch(
            delayMillis = delayMillis,
            humanDelayLabel = label,
            purpose = buildPurposeFromRelative(original, defaultPurpose)
        )
    }

    private fun buildPurposeFromRelative(original: String, fallback: String): String {
        val cleaned = original
            .replace(Regex("(?i)theek hai|abhi|cut kar do|cut karo|phone rakh do|rakh do|end call|hang up"), "")
            .trim(' ', ',', '.', '!')
        return if (cleaned.length >= 6) cleaned else fallback
    }

    private fun inferCallTypeFromText(lower: String, defaultType: String): String {
        return when {
            lower.contains("padh") || lower.contains("study") || lower.contains("utha") || lower.contains("wake") ->
                CALL_TYPE_WAKE_UP_STUDY
            lower.contains("view") || lower.contains("instagram") || lower.contains("youtube") || lower.contains("reel") ->
                CALL_TYPE_SMART_VIEWS_CHECK
            else -> defaultType
        }
    }

    companion object {
        const val MIN_VALID_SPEECH_CHARS = 3

        private val FILLER_NOISE_WORDS = setOf(
            "uh", "um", "hmm", "hm", "ah", "oh", "ok", "ha", "na", "no"
        )

        private val RELATIVE_TIME_REGEX = Regex(
            "(?:in\\s+|after\\s+)?(\\d{1,3})\\s*(minutes?|mins?|min|ghante|ghanta|hours?|hrs?|hr)(?:\\s*(?:baad|bad|later|me|mein))?",
            RegexOption.IGNORE_CASE
        )
    }
}
