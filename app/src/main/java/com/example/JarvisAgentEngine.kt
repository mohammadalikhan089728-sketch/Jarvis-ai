package com.example

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AgentExecutionResult(
    val executedAction: String,
    val paramsJson: String,
    val reply: String,
    val isOffline: Boolean,
    val latencyMs: Long,
    val alreadyStreamSpoken: Boolean = false
)

class JarvisAgentEngine(
    private val appContext: Context,
    private val repository: JarvisRepository,
    private val aiClient: GeminiApiClient,
    private val offlineProcessor: OfflineCommandProcessor
) {

    private val memoryManager = MemoryManager(repository)
    private val localRouter = LocalCommandRouter(
        appContext = appContext,
        repository = repository,
        offlineProcessor = offlineProcessor,
        memoryManager = memoryManager
    )

    suspend fun processCommand(
        rawQuery: String,
        settings: SettingsEntity,
        onPhaseUpdate: (JarvisPhase) -> Unit,
        onStatusUpdate: (String) -> Unit = {},
        onStreamStart: () -> Unit = {},
        onStreamSentence: (String) -> Unit = {},
        onStreamEnd: () -> Unit = {}
    ): AgentExecutionResult {
        val startMs = System.currentTimeMillis()
        val query = rawQuery.trim()
        val normalizedLower = query.lowercase(Locale.ROOT)

        // 0. Zero-Token Local Instant Intent Router & <3-Char Noise Guard (0 API tokens)
        when (val localRoute = localRouter.tryRoute(query, inActiveCall = false)) {
            LocalRouteResult.IgnoredNoise -> {
                return AgentExecutionResult(
                    executedAction = "LOCAL:NOISE_IGNORED",
                    paramsJson = "{}",
                    reply = "",
                    isOffline = true,
                    latencyMs = 0L,
                    alreadyStreamSpoken = true
                )
            }
            is LocalRouteResult.Handled -> {
                onPhaseUpdate(JarvisPhase.ACTING)
                val res = AgentExecutionResult(
                    executedAction = localRoute.actionTag,
                    paramsJson = localRoute.paramsJson,
                    reply = localRoute.reply,
                    isOffline = true,
                    latencyMs = System.currentTimeMillis() - startMs
                )
                logAndReturn(query, res)
                return res
            }
            LocalRouteResult.NotHandled -> Unit
        }

        // Passively save personal facts/routines/goals shared naturally in conversation
        memoryManager.detectAndSaveImplicitMemory(query)

        // 1. Voice-created Scheduled JARVIS Calls ("Kal subah 6 baje padhne ke liye call karna")
        handleVoiceScheduleCallCommand(query, normalizedLower, startMs)?.let { result ->
            logAndReturn(query, result)
            return result
        }

        // 2. Voice-controlled Autonomous Auto-Reply Mode toggle & target contact setup
        handleAutoReplyControlCommand(query, normalizedLower, startMs)?.let { result ->
            logAndReturn(query, result)
            return result
        }

        // 3. Local Auto-Chat Logs query ("Kiska message aaya tha?", "Ashif se kya baat hui?")
        if (isAutoChatLogQuery(normalizedLower)) {
            val result = handleAutoChatLogQuery(
                query = query,
                lower = normalizedLower,
                settings = settings,
                startMs = startMs,
                onPhaseUpdate = onPhaseUpdate,
                onStreamStart = onStreamStart,
                onStreamSentence = onStreamSentence,
                onStreamEnd = onStreamEnd
            )
            logAndReturn(query, result)
            return result
        }

        // 4. Instant Screen Explainer / Hindi Translator ("Screen par kya likha hai?", "Isko Hindi me samjhao")
        if (isScreenExplainOrTranslateQuery(normalizedLower)) {
            val result = handleScreenExplainOrTranslate(
                query = query,
                lower = normalizedLower,
                settings = settings,
                startMs = startMs,
                onPhaseUpdate = onPhaseUpdate,
                onStreamStart = onStreamStart,
                onStreamSentence = onStreamSentence,
                onStreamEnd = onStreamEnd
            )
            logAndReturn(query, result)
            return result
        }

        // 5. Local Room Custom Macros
        val macros = repository.getAllMacros()
        val matchedMacro = macros.firstOrNull { macro ->
            val trigger = macro.triggerPhrase.trim().lowercase(Locale.ROOT)
            trigger.isNotEmpty() && (normalizedLower == trigger || normalizedLower.contains(trigger))
        }
        if (matchedMacro != null) {
            onPhaseUpdate(JarvisPhase.ACTING)
            val macroResult = executeMacro(matchedMacro, settings, onPhaseUpdate)
            val elapsed = System.currentTimeMillis() - startMs
            val finalRes = macroResult.copy(latencyMs = elapsed)
            logAndReturn(query, finalRes)
            return finalRes
        }

        // 6. Instant Offline Execution (<100ms) for single-step device actions
        val offlineMatch = offlineProcessor.tryExecute(query)
        if (offlineMatch != null && (settings.offlineFirstMode || settings.geminiApiKey.isBlank())) {
            onPhaseUpdate(JarvisPhase.ACTING)
            val elapsed = System.currentTimeMillis() - startMs
            val res = AgentExecutionResult(
                executedAction = offlineMatch.actionTag,
                paramsJson = offlineMatch.paramsJson,
                reply = offlineMatch.reply,
                isOffline = true,
                latencyMs = elapsed
            )
            logAndReturn(query, res)
            return res
        }

        // 7. Offline-only fallback when AI Core API key is not configured
        if (settings.geminiApiKey.isBlank()) {
            val screenSnapshot = JarvisAccessibilityService.instance?.readScreenHierarchy()
            val reply = if (normalizedLower.contains("read") && normalizedLower.contains("screen")) {
                screenSnapshot?.toHumanSummary()
                    ?: "Accessibility Service is inactive. Activate it in Systems so I can read the screen."
            } else {
                "Offline command not recognized and no JARVIS AI Core API key is saved. Open Config to enter your key."
            }
            val elapsed = System.currentTimeMillis() - startMs
            val res = AgentExecutionResult("OFFLINE:SPEAK", "{}", reply, true, elapsed)
            logAndReturn(query, res)
            return res
        }

        // 8. Pure Conversational Q&A:
        //    - When "With AI Voice" is active, route directly through the ONE persistent Live Audio WebSocket
        //      (zero REST generateContent loop, zero screen JSON, instant deep male audio + clean transcript).
        //    - Otherwise (or if Live Audio is unavailable/in cooldown), use ultra-fast streaming TTS.
        if (isPureConversationalQuery(normalizedLower)) {
            onPhaseUpdate(JarvisPhase.THINKING)
            val memoryContext = memoryManager.buildCompactMemoryContext(query)

            if (settings.voiceMode == VOICE_MODE_AI_VOICE && !PersistentLiveAudioSession.isInQuotaCooldown()) {
                onPhaseUpdate(JarvisPhase.SPEAKING)
                val liveResult = PersistentLiveAudioSession.sendConversationalTurn(
                    rawApiKey = settings.geminiApiKey,
                    userText = query,
                    voiceName = settings.aiVoiceName.ifBlank { AI_VOICE_CHARON },
                    masterSystemPrompt = settings.masterSystemPrompt,
                    memoryContext = memoryContext
                )
                if (liveResult.isSuccess) {
                    val turn = liveResult.getOrThrow()
                    val cleanTranscript = JarvisResponseSanitizer.extractCleanReply(turn.spokenTranscript)
                        .ifBlank { "Ji Sir, jawab de diya hai." }
                    val elapsed = System.currentTimeMillis() - startMs
                    val res = AgentExecutionResult(
                        executedAction = "JARVIS:LIVE_AUDIO",
                        paramsJson = JSONObject().put("chunks", turn.pcmChunksPlayed).toString(),
                        reply = cleanTranscript,
                        isOffline = false,
                        latencyMs = elapsed,
                        alreadyStreamSpoken = true
                    )
                    logAndReturn(query, res)
                    return res
                }
                // If quota cooldown was triggered during Live Audio attempt, never spam REST retries
                if (PersistentLiveAudioSession.isInQuotaCooldown()) {
                    val elapsed = System.currentTimeMillis() - startMs
                    val res = AgentExecutionResult(
                        executedAction = "JARVIS:QUOTA_COOLDOWN",
                        paramsJson = "{}",
                        reply = "Rate limit cooldown active hai Sir (${PersistentLiveAudioSession.remainingCooldownSeconds()}s). Offline commands aur device controls abhi bhi kaam kar rahe hain.",
                        isOffline = true,
                        latencyMs = elapsed,
                        alreadyStreamSpoken = false
                    )
                    logAndReturn(query, res)
                    return res
                }
                onPhaseUpdate(JarvisPhase.THINKING)
            }

            val recentHistory = repository.getRecentHistory(limit = 4)
            var startedSpeaking = false
            val streamResult = aiClient.streamConversationalReply(
                rawApiKey = settings.geminiApiKey,
                masterSystemPrompt = settings.masterSystemPrompt,
                userPrompt = query,
                recentHistory = recentHistory,
                selectedEngine = settings.coreEngine,
                memoryContext = memoryContext,
                onSentenceReady = { sentence ->
                    if (!startedSpeaking) {
                        startedSpeaking = true
                        onPhaseUpdate(JarvisPhase.SPEAKING)
                        onStreamStart()
                    }
                    onStreamSentence(sentence)
                }
            )
            if (startedSpeaking) {
                onStreamEnd()
            }

            val elapsed = System.currentTimeMillis() - startMs
            val res = streamResult.fold(
                onSuccess = { fullReply ->
                    AgentExecutionResult(
                        executedAction = "JARVIS:STREAM_SPEAK",
                        paramsJson = "{}",
                        reply = fullReply,
                        isOffline = false,
                        latencyMs = elapsed,
                        alreadyStreamSpoken = startedSpeaking
                    )
                },
                onFailure = { err ->
                    AgentExecutionResult(
                        executedAction = "ERROR:CORE",
                        paramsJson = "{}",
                        reply = err.localizedMessage ?: "Unable to reach JARVIS AI Core right now.",
                        isOffline = false,
                        latencyMs = elapsed,
                        alreadyStreamSpoken = false
                    )
                }
            )
            logAndReturn(query, res)
            return res
        }

        // 9. Autonomous Multi-Step Phone Control Loop (up to 8 sequential steps)
        val multiStepResult = executeAutonomousMultiStepLoop(
            goalQuery = query,
            settings = settings,
            startMs = startMs,
            onPhaseUpdate = onPhaseUpdate,
            onStatusUpdate = onStatusUpdate
        )
        logAndReturn(query, multiStepResult)
        return multiStepResult
    }

    private suspend fun handleVoiceScheduleCallCommand(
        query: String,
        lower: String,
        startMs: Long
    ): AgentExecutionResult? {
        val mentionsScheduleCall = (lower.contains("call karna") ||
            lower.contains("call kar dena") ||
            lower.contains("mujhe call") ||
            lower.contains("schedule a call") ||
            lower.contains("schedule call") ||
            lower.contains("call me at") ||
            lower.contains("uthana hai") ||
            lower.contains("utha dena")) &&
            (lower.contains("baje") || lower.contains("am") || lower.contains("pm") || lower.contains(":"))

        if (!mentionsScheduleCall) return null

        val timeRegex = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?|baje)?", RegexOption.IGNORE_CASE)
        val match = timeRegex.find(lower) ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull()?.coerceIn(0, 59) ?: 0
        val suffix = match.groupValues[3].lowercase(Locale.ROOT).replace(".", "")

        val isMorningHint = lower.contains("subah") || lower.contains("morning") || suffix == "am"
        val isEveningHint = lower.contains("shaam") || lower.contains("raat") || lower.contains("dopahar") ||
            lower.contains("afternoon") || lower.contains("evening") || lower.contains("night") || suffix == "pm"

        if (isEveningHint && hour in 1..11) {
            hour += 12
        } else if (isMorningHint && hour == 12) {
            hour = 0
        }
        hour = hour.coerceIn(0, 23)

        val actionType = when {
            lower.contains("padh") || lower.contains("study") || lower.contains("utha") || lower.contains("wake") ->
                CALL_TYPE_WAKE_UP_STUDY
            lower.contains("view") || lower.contains("instagram") || lower.contains("youtube") || lower.contains("reel") ->
                CALL_TYPE_SMART_VIEWS_CHECK
            else -> CALL_TYPE_CUSTOM_REMINDER
        }
        val repeatCsv = if (lower.contains("daily") || lower.contains("roz") || lower.contains("har roz")) {
            "DAILY"
        } else {
            "ONCE"
        }

        val callEntity = ScheduledCallEntity(
            hour = hour,
            minute = minute,
            purpose = query,
            actionType = actionType,
            repeatDaysCsv = repeatCsv,
            enabled = true
        )
        val savedId = repository.saveScheduledCall(callEntity)
        val scheduled = callEntity.copy(id = savedId)
        CallAlarmScheduler.scheduleCall(appContext, scheduled)

        val timeLabel = CallAlarmScheduler.formatTime12Hour(hour, minute)
        val elapsed = System.currentTimeMillis() - startMs
        return AgentExecutionResult(
            executedAction = "CALL_SCHEDULED",
            paramsJson = JSONObject()
                .put("time", timeLabel)
                .put("type", actionType)
                .put("repeat", repeatCsv)
                .toString(),
            reply = "Done Sir. JARVIS will call you at $timeLabel for: $query.",
            isOffline = true,
            latencyMs = elapsed
        )
    }

    private suspend fun executeAutonomousMultiStepLoop(
        goalQuery: String,
        settings: SettingsEntity,
        startMs: Long,
        onPhaseUpdate: (JarvisPhase) -> Unit,
        onStatusUpdate: (String) -> Unit
    ): AgentExecutionResult {
        val recentHistory = repository.getRecentHistory(limit = 3)
        val stepHistory = mutableListOf<String>()
        var lastActionTag = "JARVIS:DONE"
        var lastParamsJson = "{}"
        var finalReply = "Task completed, sir."
        var stepsExecuted = 0

        JarvisAccessibilityService.resetMultiStepSignal()
        JarvisAccessibilityService.updateMultiStepStatus(true, "Step 1/$MAX_AGENT_STEPS • Analyzing")
        FloatingOrbService.showStopHud(appContext, "Step 1/$MAX_AGENT_STEPS • Analyzing")

        try {
            for (step in 1..MAX_AGENT_STEPS) {
                if (JarvisAccessibilityService.isStopRequested()) {
                    finalReply = "Autonomous execution stopped after $stepsExecuted steps."
                    lastActionTag = "AGENT:STOPPED"
                    break
                }

                onPhaseUpdate(JarvisPhase.THINKING)
                val statusLabel = "Step $step/$MAX_AGENT_STEPS • Reading screen"
                JarvisAccessibilityService.updateMultiStepStatus(true, statusLabel)
                FloatingOrbService.showStopHud(appContext, statusLabel)
                onStatusUpdate(statusLabel)

                val screenContextJson = if (settings.autoReadScreenContext) {
                    JarvisAccessibilityService.instance?.readScreenHierarchy()?.toCompactJson()
                } else {
                    null
                }

                val memoryContext = memoryManager.buildCompactMemoryContext(goalQuery)
                val aiResult = aiClient.requestAgentAction(
                    rawApiKey = settings.geminiApiKey,
                    masterSystemPrompt = settings.masterSystemPrompt,
                    userQuery = goalQuery,
                    screenContextJson = screenContextJson,
                    recentHistory = recentHistory,
                    stepHistory = stepHistory,
                    stepIndex = step,
                    maxSteps = MAX_AGENT_STEPS,
                    selectedEngine = settings.coreEngine,
                    memoryContext = memoryContext
                )

                val aiAction = aiResult.getOrElse { err ->
                    val elapsed = System.currentTimeMillis() - startMs
                    return AgentExecutionResult(
                        executedAction = "ERROR:CORE",
                        paramsJson = "{}",
                        reply = err.localizedMessage ?: "Unable to reach JARVIS AI Core right now.",
                        isOffline = false,
                        latencyMs = elapsed
                    )
                }

                // Persist any long-term personal fact extracted by JARVIS AI Core
                aiAction.saveMemory?.takeIf { it.isNotBlank() }?.let { fact ->
                    memoryManager.saveMemory(fact, memoryManager.inferCategory(fact))
                }

                val actionUpper = aiAction.action.uppercase(Locale.ROOT)
                lastActionTag = if (stepsExecuted > 0) "AGENT(${stepsExecuted + 1}):$actionUpper" else "JARVIS:$actionUpper"
                lastParamsJson = aiAction.params.toString()
                finalReply = aiAction.reply

                if (actionUpper == "DONE" || actionUpper == "SPEAK") {
                    break
                }

                if (JarvisAccessibilityService.isStopRequested()) {
                    finalReply = "Autonomous execution stopped."
                    lastActionTag = "AGENT:STOPPED"
                    break
                }

                onPhaseUpdate(JarvisPhase.ACTING)
                val execLabel = "Step $step/$MAX_AGENT_STEPS • $actionUpper"
                JarvisAccessibilityService.updateMultiStepStatus(true, execLabel)
                FloatingOrbService.showStopHud(appContext, execLabel)
                onStatusUpdate(execLabel)

                val execNote = executeStructuredAction(actionUpper, aiAction.params)
                stepsExecuted++
                stepHistory.add("$actionUpper(${aiAction.params}) -> ${execNote ?: "OK"}")

                if (execNote != null && execNote.contains("Accessibility Service inactive", ignoreCase = true)) {
                    finalReply = "${aiAction.reply} ($execNote)"
                    break
                }

                // Mandatory post-action 750ms UI settle + fresh interactive window refresh so touches NEVER freeze after Home/app switch
                val settleTickMs = 100L
                var waited = 0L
                while (waited < STEP_SETTLE_DELAY_MS) {
                    if (JarvisAccessibilityService.isStopRequested()) break
                    delay(settleTickMs)
                    waited += settleTickMs
                }
                JarvisAccessibilityService.instance?.settleAndRefreshScreen(delayMs = 0L)

                if (step == 1 && !looksLikeMultiStepGoal(goalQuery)) {
                    if (execNote != null) {
                        finalReply = "${aiAction.reply} ($execNote)"
                    }
                    break
                }
            }
        } finally {
            JarvisAccessibilityService.updateMultiStepStatus(false)
            FloatingOrbService.hideStopHud(appContext)
        }

        val elapsed = System.currentTimeMillis() - startMs
        return AgentExecutionResult(
            executedAction = lastActionTag,
            paramsJson = lastParamsJson,
            reply = finalReply,
            isOffline = false,
            latencyMs = elapsed
        )
    }

    private suspend fun handleAutoReplyControlCommand(
        query: String,
        lower: String,
        startMs: Long
    ): AgentExecutionResult? {
        val mentionsAutoReply = lower.contains("auto reply") ||
            lower.contains("auto-reply") ||
            lower.contains("autoreply") ||
            lower.contains("auto chat") ||
            lower.contains("auto-chat")

        val replyToContactMatch = Regex(
            "(?:auto[ -]?reply\\s+to|reply\\s+to)\\s+([a-z0-9 ._-]+?)(?:\\s+automatically|\\s+on\\s+whatsapp|$)",
            RegexOption.IGNORE_CASE
        ).find(query)

        if (replyToContactMatch != null && (mentionsAutoReply || lower.contains("automatically"))) {
            val contact = replyToContactMatch.groupValues[1].trim()
            if (contact.isNotEmpty() && !contact.equals("all", ignoreCase = true) && !contact.equals("everyone", ignoreCase = true)) {
                repository.updateAutoReplyConfig(enabled = true, targetContact = contact)
                val listenerHint = if (!JarvisNotificationListenerService.isConnected.value) {
                    " Make sure Notification Access is enabled in Systems."
                } else ""
                val elapsed = System.currentTimeMillis() - startMs
                return AgentExecutionResult(
                    executedAction = "AUTO_REPLY:TARGET_ON",
                    paramsJson = JSONObject().put("enabled", true).put("contact", contact).toString(),
                    reply = "Auto-reply mode enabled for $contact.$listenerHint",
                    isOffline = true,
                    latencyMs = elapsed
                )
            }
        }

        if (mentionsAutoReply) {
            val turnOff = lower.contains("off") || lower.contains("disable") || lower.contains("stop") || lower.contains("band")
            val turnOn = lower.contains("on") || lower.contains("enable") || lower.contains("start") || lower.contains("chalu")
            if (turnOff || turnOn) {
                val enable = turnOn && !turnOff
                val clearFilter = lower.contains("all") || lower.contains("everyone")
                repository.updateAutoReplyConfig(
                    enabled = enable,
                    targetContact = if (clearFilter) "" else null
                )
                val listenerHint = if (enable && !JarvisNotificationListenerService.isConnected.value) {
                    " Enable Notification Access in Systems so I can intercept incoming chats."
                } else ""
                val elapsed = System.currentTimeMillis() - startMs
                return AgentExecutionResult(
                    executedAction = if (enable) "AUTO_REPLY:ON" else "AUTO_REPLY:OFF",
                    paramsJson = JSONObject().put("enabled", enable).toString(),
                    reply = if (enable) {
                        "Autonomous WhatsApp and notification auto-reply mode engaged.$listenerHint"
                    } else {
                        "Autonomous auto-reply mode powered down."
                    },
                    isOffline = true,
                    latencyMs = elapsed
                )
            }
        }
        return null
    }

    private fun isAutoChatLogQuery(lower: String): Boolean {
        return lower.contains("kiska message") ||
            lower.contains("whose message") ||
            lower.contains("who messaged") ||
            lower.contains("who texted") ||
            lower.contains("recent messages") ||
            lower.contains("auto reply log") ||
            lower.contains("se kya baat hui") ||
            lower.contains("kya baat hui") ||
            lower.contains("what did i talk") ||
            lower.contains("conversation with") ||
            (lower.contains("chat with") && (lower.contains("summary") || lower.contains("what") || lower.contains("kya")))
    }

    private suspend fun handleAutoChatLogQuery(
        query: String,
        lower: String,
        settings: SettingsEntity,
        startMs: Long,
        onPhaseUpdate: (JarvisPhase) -> Unit,
        onStreamStart: () -> Unit,
        onStreamSentence: (String) -> Unit,
        onStreamEnd: () -> Unit
    ): AgentExecutionResult {
        val hindiContactMatch = Regex("^([a-z0-9 ._-]+?)\\s+se\\s+kya\\s+baat", RegexOption.IGNORE_CASE).find(query)
        val englishContactMatch = Regex("(?:with|from)\\s+([a-z0-9 ._-]+?)[?.,!]*$", RegexOption.IGNORE_CASE).find(query)
        val specificSender = (hindiContactMatch?.groupValues?.getOrNull(1)
            ?: englishContactMatch?.groupValues?.getOrNull(1))
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("me", ignoreCase = true) }

        val logs = if (specificSender != null) {
            repository.getAutoChatLogsForSender(specificSender, limit = 16)
        } else {
            repository.getRecentAutoChatLogs(limit = 16).reversed()
        }

        if (logs.isEmpty()) {
            val reply = if (specificSender != null) {
                "No local auto-chat messages found with $specificSender yet."
            } else {
                "No incoming or auto-replied messages are logged in local storage yet."
            }
            return AgentExecutionResult(
                executedAction = "AUTO_CHAT:QUERY_EMPTY",
                paramsJson = JSONObject().put("sender", specificSender ?: "").toString(),
                reply = reply,
                isOffline = true,
                latencyMs = System.currentTimeMillis() - startMs
            )
        }

        val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        val formattedLogs = logs.joinToString("\n") { log ->
            val who = if (log.direction == AUTO_CHAT_DIR_INCOMING) log.senderName else "JARVIS (Me)"
            "[${timeFmt.format(Date(log.timestamp))} | ${log.appLabel}] $who: ${log.messageText}"
        }

        if (settings.geminiApiKey.isBlank()) {
            val latest = logs.last()
            val uniqueSenders = logs.map { it.senderName }.distinct().joinToString(", ")
            val offlineSummary = if (specificSender != null) {
                "Latest exchange with ${latest.senderName}: ${latest.messageText}"
            } else {
                "Recent messages came from $uniqueSenders. Latest from ${latest.senderName} on ${latest.appLabel}: ${latest.messageText}"
            }
            return AgentExecutionResult(
                executedAction = "AUTO_CHAT:OFFLINE_SUMMARY",
                paramsJson = JSONObject().put("count", logs.size).toString(),
                reply = offlineSummary,
                isOffline = true,
                latencyMs = System.currentTimeMillis() - startMs
            )
        }

        onPhaseUpdate(JarvisPhase.THINKING)
        var started = false
        val prompt = buildString {
            appendLine("USER QUESTION: $query")
            appendLine("LOCAL AUTO-CHAT MESSAGE LOGS:")
            appendLine(formattedLogs)
            appendLine()
            appendLine("Summarize the messages clearly and concisely in the user's language (Hindi/Hinglish/English).")
        }

        val streamRes = aiClient.streamConversationalReply(
            rawApiKey = settings.geminiApiKey,
            masterSystemPrompt = settings.masterSystemPrompt,
            userPrompt = prompt,
            selectedEngine = settings.coreEngine,
            onSentenceReady = { sentence ->
                if (!started) {
                    started = true
                    onPhaseUpdate(JarvisPhase.SPEAKING)
                    onStreamStart()
                }
                onStreamSentence(sentence)
            }
        )
        if (started) onStreamEnd()

        val elapsed = System.currentTimeMillis() - startMs
        return streamRes.fold(
            onSuccess = { summary ->
                AgentExecutionResult(
                    executedAction = "AUTO_CHAT:SUMMARY",
                    paramsJson = JSONObject().put("count", logs.size).toString(),
                    reply = summary,
                    isOffline = false,
                    latencyMs = elapsed,
                    alreadyStreamSpoken = started
                )
            },
            onFailure = { err ->
                AgentExecutionResult(
                    executedAction = "ERROR:AUTO_CHAT",
                    paramsJson = "{}",
                    reply = err.localizedMessage ?: "Unable to summarize local messages.",
                    isOffline = false,
                    latencyMs = elapsed
                )
            }
        )
    }

    private fun isScreenExplainOrTranslateQuery(lower: String): Boolean {
        return lower.contains("screen par kya") ||
            lower.contains("screen pe kya") ||
            lower.contains("what's on my screen") ||
            lower.contains("what is on my screen") ||
            lower.contains("read what's on screen") ||
            lower.contains("read screen") ||
            lower.contains("explain screen") ||
            lower.contains("explain this screen") ||
            lower.contains("translate screen") ||
            lower.contains("isko hindi me") ||
            lower.contains("hindi me samjhao") ||
            lower.contains("hindi mein samjhao") ||
            lower.contains("explain this in hindi") ||
            lower.contains("summarize screen") ||
            lower.contains("summarize this page")
    }

    private suspend fun handleScreenExplainOrTranslate(
        query: String,
        lower: String,
        settings: SettingsEntity,
        startMs: Long,
        onPhaseUpdate: (JarvisPhase) -> Unit,
        onStreamStart: () -> Unit,
        onStreamSentence: (String) -> Unit,
        onStreamEnd: () -> Unit
    ): AgentExecutionResult {
        val snapshot = JarvisAccessibilityService.instance?.readScreenHierarchy(maxNodes = 90)
        if (snapshot == null) {
            return AgentExecutionResult(
                executedAction = "SCREEN_EXPLAIN:NO_A11Y",
                paramsJson = "{}",
                reply = "Accessibility Service is inactive. Enable it in Systems so I can read and explain your screen.",
                isOffline = true,
                latencyMs = System.currentTimeMillis() - startMs
            )
        }

        if (settings.geminiApiKey.isBlank()) {
            return AgentExecutionResult(
                executedAction = "SCREEN_READ:OFFLINE",
                paramsJson = JSONObject().put("nodes", snapshot.nodes.size).toString(),
                reply = snapshot.toHumanSummary(),
                isOffline = true,
                latencyMs = System.currentTimeMillis() - startMs
            )
        }

        onPhaseUpdate(JarvisPhase.THINKING)
        val wantsHindi = lower.contains("hindi") || lower.contains("samjhao") || lower.contains("kya likha")
        val prompt = buildString {
            appendLine("USER REQUEST: $query")
            appendLine("VISIBLE SCREEN CONTENT:")
            appendLine(snapshot.toPlainReadableText())
            appendLine()
            if (wantsHindi) {
                appendLine("Explain or translate the key content on this screen clearly in natural spoken Hindi/Hinglish (2-4 short sentences).")
            } else {
                appendLine("Summarize and explain the key content currently displayed on this screen in 2-3 clear spoken sentences.")
            }
        }

        var started = false
        val streamRes = aiClient.streamConversationalReply(
            rawApiKey = settings.geminiApiKey,
            masterSystemPrompt = settings.masterSystemPrompt,
            userPrompt = prompt,
            selectedEngine = settings.coreEngine,
            onSentenceReady = { sentence ->
                if (!started) {
                    started = true
                    onPhaseUpdate(JarvisPhase.SPEAKING)
                    onStreamStart()
                }
                onStreamSentence(sentence)
            }
        )
        if (started) onStreamEnd()

        val elapsed = System.currentTimeMillis() - startMs
        return streamRes.fold(
            onSuccess = { explanation ->
                AgentExecutionResult(
                    executedAction = "JARVIS:SCREEN_EXPLAIN",
                    paramsJson = JSONObject().put("package", snapshot.packageName).toString(),
                    reply = explanation,
                    isOffline = false,
                    latencyMs = elapsed,
                    alreadyStreamSpoken = started
                )
            },
            onFailure = { err ->
                AgentExecutionResult(
                    executedAction = "ERROR:SCREEN_EXPLAIN",
                    paramsJson = "{}",
                    reply = err.localizedMessage ?: "Failed to explain screen content.",
                    isOffline = false,
                    latencyMs = elapsed
                )
            }
        )
    }

    private fun isPureConversationalQuery(lower: String): Boolean {
        val deviceActionKeywords = listOf(
            "open ", "launch ", "start ", "tap ", "click ", "press ", "type ", "enter ",
            "search ", "play ", "scroll ", "swipe ", "drag ", "zoom ", "pinch ", "rotate ",
            "turn on", "turn off", "hotspot", "settings", "youtube", "whatsapp", "instagram",
            "chrome", "maps", "spotify", "camera", "gallery", "message ", "send ", "call ",
            "dial ", "go back", "go home", "screenshot", "lock", "wifi", "wi-fi", "bluetooth",
            "clipboard", "volume", "brightness", "dnd", "do not disturb", "kholo", "khol ",
            "chalao", "bhejo", "dabao", "likho", "on karo", "off karo"
        )
        return deviceActionKeywords.none { lower.contains(it) }
    }

    private fun looksLikeMultiStepGoal(query: String): Boolean {
        val lower = query.lowercase(Locale.ROOT)
        return lower.contains(" and ") ||
            lower.contains(" then ") ||
            lower.contains(" aur ") ||
            lower.contains(" phir ") ||
            lower.contains(" kar ") ||
            lower.contains(",") ||
            lower.contains("search ") ||
            lower.contains("play ") ||
            lower.contains("chalao") ||
            lower.contains("hotspot") ||
            lower.contains("turn on") ||
            lower.contains("turn off") ||
            lower.contains("on karo") ||
            lower.contains("off karo") ||
            lower.contains("send ") ||
            lower.contains("bhejo") ||
            lower.contains("message ")
    }

    private suspend fun logAndReturn(query: String, result: AgentExecutionResult) {
        val cleanReply = JarvisResponseSanitizer.extractCleanReply(result.reply)
            .ifBlank { "Done, sir." }
        repository.logInteraction(
            ChatHistoryEntity(
                userTranscript = query,
                executedAction = result.executedAction,
                actionParamsJson = result.paramsJson,
                jarvisReply = cleanReply,
                isOffline = result.isOffline,
                latencyMs = result.latencyMs
            )
        )
    }

    private suspend fun executeMacro(
        macro: CustomMacroEntity,
        settings: SettingsEntity,
        onPhaseUpdate: (JarvisPhase) -> Unit
    ): AgentExecutionResult {
        if (macro.actionType.equals("AI_PROMPT", ignoreCase = true) && settings.geminiApiKey.isNotBlank()) {
            onPhaseUpdate(JarvisPhase.THINKING)
            val screenJson = if (settings.autoReadScreenContext) {
                JarvisAccessibilityService.instance?.readScreenHierarchy()?.toCompactJson()
            } else null
            val memoryContext = memoryManager.buildCompactMemoryContext(macro.actionPayload)
            val res = aiClient.requestAgentAction(
                rawApiKey = settings.geminiApiKey,
                masterSystemPrompt = settings.masterSystemPrompt,
                userQuery = macro.actionPayload,
                screenContextJson = screenJson,
                recentHistory = emptyList(),
                selectedEngine = settings.coreEngine,
                memoryContext = memoryContext
            )
            return res.fold(
                onSuccess = { action ->
                    action.saveMemory?.takeIf { it.isNotBlank() }?.let { fact ->
                        memoryManager.saveMemory(fact, memoryManager.inferCategory(fact))
                    }
                    onPhaseUpdate(JarvisPhase.ACTING)
                    executeStructuredAction(action.action, action.params)
                    AgentExecutionResult(
                        executedAction = "MACRO_AI:${action.action}",
                        paramsJson = action.params.toString(),
                        reply = macro.spokenReply.ifBlank { action.reply },
                        isOffline = false,
                        latencyMs = 0L
                    )
                },
                onFailure = { err ->
                    AgentExecutionResult(
                        executedAction = "MACRO_ERROR",
                        paramsJson = "{}",
                        reply = err.localizedMessage ?: "Macro AI call failed.",
                        isOffline = false,
                        latencyMs = 0L
                    )
                }
            )
        }

        val params = runCatching { JSONObject(macro.actionPayload) }.getOrElse {
            when (macro.actionType.uppercase(Locale.ROOT)) {
                "OPEN_APP" -> JSONObject().put("appName", macro.actionPayload)
                "TAP_TEXT" -> JSONObject().put("text", macro.actionPayload)
                "TYPE_TEXT" -> JSONObject().put("text", macro.actionPayload)
                "SCROLL" -> JSONObject().put("direction", macro.actionPayload)
                "GLOBAL" -> JSONObject().put("command", macro.actionPayload)
                else -> JSONObject().put("payload", macro.actionPayload)
            }
        }
        val note = executeStructuredAction(macro.actionType.uppercase(Locale.ROOT), params)
        val reply = macro.spokenReply.ifBlank { note ?: "Macro ${macro.triggerPhrase} executed." }
        return AgentExecutionResult(
            executedAction = "MACRO:${macro.actionType.uppercase(Locale.ROOT)}",
            paramsJson = params.toString(),
            reply = reply,
            isOffline = true,
            latencyMs = 0L
        )
    }

    suspend fun executeStructuredAction(action: String, params: JSONObject): String? {
        val service = JarvisAccessibilityService.instance
        return when (action.uppercase(Locale.ROOT)) {
            "SPEAK", "DONE" -> null

            "OPEN_APP" -> {
                val pkgName = params.optString("packageName", "").trim()
                if (pkgName.isNotEmpty()) {
                    val launchByPkg = appContext.packageManager.getLaunchIntentForPackage(pkgName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (launchByPkg != null && runCatching { appContext.startActivity(launchByPkg) }.isSuccess) {
                        return null
                    }
                }
                val appName = params.optString("appName", params.optString("app", pkgName)).trim()
                val launched = offlineProcessor.openInstalledApp(appName)
                if (launched == null) "App '$appName' not found" else null
            }

            "TAP_TEXT" -> {
                if (service == null) return "Accessibility Service inactive"
                val label = params.optString("text", "").trim()
                if (!service.tapByText(label)) "Could not locate '$label' on screen" else null
            }

            "TAP_XY" -> {
                if (service == null) return "Accessibility Service inactive"
                val x = params.optDouble("x", -1.0).toFloat()
                val y = params.optDouble("y", -1.0).toFloat()
                if (x < 0f || y < 0f) return "Invalid tap coordinates"
                if (!service.tapXY(x, y, durationMs = 80L)) "Tap gesture cancelled" else null
            }

            "LONG_PRESS", "LONG_PRESS_XY" -> {
                if (service == null) return "Accessibility Service inactive"
                val x = params.optDouble("x", -1.0).toFloat()
                val y = params.optDouble("y", -1.0).toFloat()
                val duration = params.optLong("durationMs", 750L)
                if (x < 0f || y < 0f) return "Invalid long-press coordinates"
                if (!service.longPress(x, y, duration)) "Long-press cancelled" else null
            }

            "SWIPE" -> {
                if (service == null) return "Accessibility Service inactive"
                val x1 = params.optDouble("x1", params.optDouble("startX", 500.0)).toFloat()
                val y1 = params.optDouble("y1", params.optDouble("startY", 1400.0)).toFloat()
                val x2 = params.optDouble("x2", params.optDouble("endX", 500.0)).toFloat()
                val y2 = params.optDouble("y2", params.optDouble("endY", 500.0)).toFloat()
                val duration = params.optLong("durationMs", 320L)
                if (!service.swipe(x1, y1, x2, y2, duration)) "Swipe gesture cancelled" else null
            }

            "SCROLL_DOWN" -> {
                if (service == null) return "Accessibility Service inactive"
                if (!service.scrollDown()) "Scroll down cancelled" else null
            }

            "SCROLL_UP" -> {
                if (service == null) return "Accessibility Service inactive"
                if (!service.scrollUp()) "Scroll up cancelled" else null
            }

            "SCROLL" -> {
                if (service == null) return "Accessibility Service inactive"
                val dir = params.optString("direction", "SCROLL_DOWN")
                if (!service.performDirectionalScroll(dir)) "Scroll gesture cancelled" else null
            }

            "BACK" -> {
                if (service == null) return "Accessibility Service inactive"
                if (!service.performGlobalSystemActionAndSettle("BACK")) "Back action failed" else null
            }

            "HOME" -> {
                if (service == null) return "Accessibility Service inactive"
                if (!service.performGlobalSystemActionAndSettle("HOME")) "Home action failed" else null
            }

            "RECENTS" -> {
                if (service == null) return "Accessibility Service inactive"
                if (!service.performGlobalSystemActionAndSettle("RECENTS")) "Recents action failed" else null
            }

            "DRAG" -> {
                if (service == null) return "Accessibility Service inactive"
                val duration = params.optLong("durationMs", 420L)
                val ok = if (params.has("angleDegrees")) {
                    val angle = params.optDouble("angleDegrees", 0.0).toFloat()
                    val dist = params.optDouble("distancePx", 380.0).toFloat()
                    val ox = if (params.has("originX")) params.optDouble("originX").toFloat() else null
                    val oy = if (params.has("originY")) params.optDouble("originY").toFloat() else null
                    service.gestureEngine.dragByAngle(ox, oy, angle, dist, duration)
                } else {
                    val sx = params.optDouble("startX", 500.0).toFloat()
                    val sy = params.optDouble("startY", 1000.0).toFloat()
                    val ex = params.optDouble("endX", 500.0).toFloat()
                    val ey = params.optDouble("endY", 400.0).toFloat()
                    service.gestureEngine.dragAnyDirection(sx, sy, ex, ey, duration)
                }
                if (!ok) "Drag gesture cancelled" else null
            }

            "MULTI_TOUCH" -> {
                if (service == null) return "Accessibility Service inactive"
                val gesture = params.optString("gesture", "PINCH_OUT").uppercase(Locale.ROOT)
                val ok = when (gesture) {
                    "PINCH_OUT", "ZOOM_IN" -> {
                        val spread = params.optDouble("spreadPx", 260.0).toFloat()
                        service.gestureEngine.pinchZoomIn(spreadPx = spread)
                    }
                    "PINCH_IN", "ZOOM_OUT" -> {
                        val spread = params.optDouble("spreadPx", 260.0).toFloat()
                        service.gestureEngine.pinchZoomOut(spreadPx = spread)
                    }
                    "ROTATE" -> {
                        val degrees = params.optDouble("degrees", 45.0).toFloat()
                        service.gestureEngine.twoFingerRotate(degrees = degrees)
                    }
                    "TWO_FINGER_SWIPE" -> {
                        val dirStr = params.optString("direction", "UP").uppercase(Locale.ROOT)
                        val dir = runCatching { SwipeDirection.valueOf(dirStr) }.getOrDefault(SwipeDirection.UP)
                        service.gestureEngine.twoFingerSwipe(dir)
                    }
                    "MULTI_TAP" -> {
                        val arr = params.optJSONArray("points")
                        val pts = mutableListOf<Pair<Float, Float>>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val obj = arr.optJSONObject(i) ?: continue
                                pts.add(obj.optDouble("x", 0.0).toFloat() to obj.optDouble("y", 0.0).toFloat())
                            }
                        }
                        service.gestureEngine.simultaneousMultiTap(pts)
                    }
                    else -> false
                }
                if (!ok) "Multi-touch gesture failed" else null
            }

            "TYPE_TEXT" -> {
                if (service == null) return "Accessibility Service inactive"
                val text = params.optString("text", "")
                val target = params.optString("target", "").takeIf(String::isNotBlank)
                val submit = params.optBoolean("submit", false)
                if (!service.typeText(text, target, submitAfterTyping = submit)) "No editable text field found" else null
            }

            "GLOBAL" -> {
                val cmd = params.optString("command", "").uppercase(Locale.ROOT)
                when (cmd) {
                    "FLASHLIGHT_ON" -> if (!offlineProcessor.toggleFlashlight(true)) "Flashlight unavailable" else null
                    "FLASHLIGHT_OFF" -> if (!offlineProcessor.toggleFlashlight(false)) "Flashlight unavailable" else null
                    "VOLUME_UP" -> {
                        offlineProcessor.adjustVolume(android.media.AudioManager.ADJUST_RAISE)
                        null
                    }
                    "VOLUME_DOWN" -> {
                        offlineProcessor.adjustVolume(android.media.AudioManager.ADJUST_LOWER)
                        null
                    }
                    "WIFI" -> {
                        offlineProcessor.openWifiQuickPanel()
                        null
                    }
                    "BLUETOOTH" -> {
                        offlineProcessor.openBluetoothSettings()
                        null
                    }
                    "MOBILE_DATA" -> {
                        offlineProcessor.openMobileDataSettings()
                        null
                    }
                    "HOTSPOT" -> {
                        offlineProcessor.openHotspotSettings()
                        null
                    }
                    "AIRPLANE_MODE" -> {
                        offlineProcessor.openAirplaneModeSettings()
                        null
                    }
                    "BATTERY_SAVER" -> {
                        offlineProcessor.openBatterySaverSettings()
                        null
                    }
                    "BRIGHTNESS" -> {
                        offlineProcessor.openDisplayBrightnessSettings()
                        null
                    }
                    "DND" -> {
                        offlineProcessor.toggleDoNotDisturb(null)
                        null
                    }
                    "CLIPBOARD_READ" -> {
                        offlineProcessor.readClipboardText() ?: "Clipboard empty"
                    }
                    "CLIPBOARD_COPY" -> {
                        val target = params.optString("target", params.optString("text", ""))
                        if (target.isNotBlank()) offlineProcessor.copyToClipboard(target)
                        null
                    }
                    "CALL" -> {
                        val target = params.optString("target", "")
                        offlineProcessor.initiateCall(target)
                        null
                    }
                    else -> {
                        if (service == null) "Accessibility Service inactive"
                        else if (!service.performGlobalSystemActionAndSettle(cmd)) "Global action $cmd unsupported" else null
                    }
                }
            }

            else -> null
        }
    }

    companion object {
        const val MAX_AGENT_STEPS = 12
        const val STEP_SETTLE_DELAY_MS = 750L
    }
}
