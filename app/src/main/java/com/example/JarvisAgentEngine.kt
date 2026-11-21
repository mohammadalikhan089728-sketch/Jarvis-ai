package com.example

import android.content.Context
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
    private val geminiApiClient: GeminiApiClient,
    private val offlineProcessor: OfflineCommandProcessor
) {

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

        // 1. Voice-controlled Autonomous Auto-Reply Mode toggle & target contact setup
        handleAutoReplyControlCommand(query, normalizedLower, startMs)?.let { result ->
            logAndReturn(query, result)
            return result
        }

        // 2. Local Auto-Chat Logs query ("Kiska message aaya tha?", "Ashif se kya baat hui?")
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

        // 3. Instant Screen Explainer / Hindi Translator ("Screen par kya likha hai?", "Isko Hindi me samjhao")
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

        // 4. Local Room Custom Macros
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

        // 5. Instant Offline Execution (<100ms) for single-step device actions
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

        // 6. Offline-only fallback when Gemini API key is not configured
        if (settings.geminiApiKey.isBlank()) {
            val screenSnapshot = JarvisAccessibilityService.instance?.readScreenHierarchy()
            val reply = if (normalizedLower.contains("read") && normalizedLower.contains("screen")) {
                screenSnapshot?.toHumanSummary()
                    ?: "Accessibility Service is inactive. Activate it in Systems so I can read the screen."
            } else {
                "Offline command not recognized and no Gemini API key is saved. Open Config to add your key for gemini-3.5-flash."
            }
            val elapsed = System.currentTimeMillis() - startMs
            val res = AgentExecutionResult("OFFLINE:SPEAK", "{}", reply, true, elapsed)
            logAndReturn(query, res)
            return res
        }

        // 7. Ultra-Fast Streaming Conversational Q&A (~400ms first-sentence TTS) when no device/UI action is requested
        if (isPureConversationalQuery(normalizedLower)) {
            onPhaseUpdate(JarvisPhase.THINKING)
            val recentHistory = repository.getRecentHistory(limit = 4)
            var startedSpeaking = false
            val streamResult = geminiApiClient.streamConversationalReply(
                rawApiKey = settings.geminiApiKey,
                masterSystemPrompt = settings.masterSystemPrompt,
                userPrompt = query,
                recentHistory = recentHistory,
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
                        executedAction = "GEMINI:STREAM_SPEAK",
                        paramsJson = "{}",
                        reply = fullReply,
                        isOffline = false,
                        latencyMs = elapsed,
                        alreadyStreamSpoken = startedSpeaking
                    )
                },
                onFailure = { err ->
                    AgentExecutionResult(
                        executedAction = "ERROR:GEMINI",
                        paramsJson = "{}",
                        reply = err.localizedMessage ?: "Unable to reach gemini-3.5-flash right now.",
                        isOffline = false,
                        latencyMs = elapsed,
                        alreadyStreamSpoken = false
                    )
                }
            )
            logAndReturn(query, res)
            return res
        }

        // 8. Autonomous Multi-Step Phone Execution Loop (up to 8 sequential steps) via gemini-3.5-flash
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

    private suspend fun executeAutonomousMultiStepLoop(
        goalQuery: String,
        settings: SettingsEntity,
        startMs: Long,
        onPhaseUpdate: (JarvisPhase) -> Unit,
        onStatusUpdate: (String) -> Unit
    ): AgentExecutionResult {
        val recentHistory = repository.getRecentHistory(limit = 3)
        val stepHistory = mutableListOf<String>()
        var lastActionTag = "GEMINI:DONE"
        var lastParamsJson = "{}"
        var finalReply = "Task completed, sir."
        var stepsExecuted = 0

        JarvisAccessibilityService.resetMultiStepSignal()
        JarvisAccessibilityService.updateMultiStepStatus(true, "Step 1/$MAX_AGENT_STEPS • Analyzing")
        FloatingOrbService.showStopHud(appContext, "Step 1/$MAX_AGENT_STEPS • Analyzing")

        try {
            for (step in 1..MAX_AGENT_STEPS) {
                if (JarvisAccessibilityService.isStopRequested()) {
                    finalReply = "Autonomous execution stopped by user after $stepsExecuted steps."
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

                val aiResult = geminiApiClient.requestAgentAction(
                    rawApiKey = settings.geminiApiKey,
                    masterSystemPrompt = settings.masterSystemPrompt,
                    userQuery = goalQuery,
                    screenContextJson = screenContextJson,
                    recentHistory = recentHistory,
                    stepHistory = stepHistory,
                    stepIndex = step,
                    maxSteps = MAX_AGENT_STEPS
                )

                val aiAction = aiResult.getOrElse { err ->
                    val elapsed = System.currentTimeMillis() - startMs
                    return AgentExecutionResult(
                        executedAction = "ERROR:GEMINI",
                        paramsJson = "{}",
                        reply = err.localizedMessage ?: "Unable to reach gemini-3.5-flash right now.",
                        isOffline = false,
                        latencyMs = elapsed
                    )
                }

                val actionUpper = aiAction.action.uppercase(Locale.ROOT)
                lastActionTag = if (stepsExecuted > 0) "AGENT(${stepsExecuted + 1}):$actionUpper" else "GEMINI:$actionUpper"
                lastParamsJson = aiAction.params.toString()
                finalReply = aiAction.reply

                if (actionUpper == "DONE" || actionUpper == "SPEAK") {
                    break
                }

                if (JarvisAccessibilityService.isStopRequested()) {
                    finalReply = "Autonomous execution stopped by user."
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
                stepHistory.add(
                    "$actionUpper(${aiAction.params}) -> ${execNote ?: "OK"}"
                )

                // If accessibility is inactive and action needed it, stop immediately instead of spinning
                if (execNote != null && execNote.contains("Accessibility Service inactive", ignoreCase = true)) {
                    finalReply = "${aiAction.reply} ($execNote)"
                    break
                }

                // Single-step goals (not clearly multi-step) complete cleanly after 1 action
                if (step == 1 && !looksLikeMultiStepGoal(goalQuery)) {
                    if (execNote != null) {
                        finalReply = "${aiAction.reply} ($execNote)"
                    }
                    break
                }

                // Interruptible 900ms UI settle window before capturing updated screen hierarchy
                val settleStepMs = 100L
                var waited = 0L
                while (waited < STEP_SETTLE_DELAY_MS) {
                    if (JarvisAccessibilityService.isStopRequested()) break
                    delay(settleStepMs)
                    waited += settleStepMs
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

        // e.g. "Reply to Ashif automatically" / "Auto reply to Rahul"
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
            lower.contains("chat with") && (lower.contains("summary") || lower.contains("what") || lower.contains("kya"))
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
        // Extract contact name if asking about a specific sender (e.g. "Ashif se kya baat hui?")
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
            val who = if (log.direction == AUTO_CHAT_DIR_INCOMING) log.senderName else "Jarvis (Me)"
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

        val streamRes = geminiApiClient.streamConversationalReply(
            rawApiKey = settings.geminiApiKey,
            masterSystemPrompt = settings.masterSystemPrompt,
            userPrompt = prompt,
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
        val streamRes = geminiApiClient.streamConversationalReply(
            rawApiKey = settings.geminiApiKey,
            masterSystemPrompt = settings.masterSystemPrompt,
            userPrompt = prompt,
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
                    executedAction = "GEMINI:SCREEN_EXPLAIN",
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
            "clipboard", "volume", "brightness", "dnd", "do not disturb", "kholo", "chalao",
            "bhejo", "dabao", "likho"
        )
        return deviceActionKeywords.none { lower.contains(it) }
    }

    private fun looksLikeMultiStepGoal(query: String): Boolean {
        val lower = query.lowercase(Locale.ROOT)
        return lower.contains(" and ") ||
            lower.contains(" then ") ||
            lower.contains(" aur ") ||
            lower.contains(" phir ") ||
            lower.contains(",") ||
            lower.contains("search ") ||
            lower.contains("play ") ||
            lower.contains("hotspot") ||
            lower.contains("turn on") ||
            lower.contains("turn off") ||
            lower.contains("send ") ||
            lower.contains("message ")
    }

    private suspend fun logAndReturn(query: String, result: AgentExecutionResult) {
        repository.logInteraction(
            ChatHistoryEntity(
                userTranscript = query,
                executedAction = result.executedAction,
                actionParamsJson = result.paramsJson,
                jarvisReply = result.reply,
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
            val res = geminiApiClient.requestAgentAction(
                rawApiKey = settings.geminiApiKey,
                masterSystemPrompt = settings.masterSystemPrompt,
                userQuery = macro.actionPayload,
                screenContextJson = screenJson,
                recentHistory = emptyList()
            )
            return res.fold(
                onSuccess = { action ->
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
                val appName = params.optString("appName", params.optString("app", "")).trim()
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
                if (!service.gestureEngine.tap(x, y)) "Tap gesture cancelled" else null
            }

            "LONG_PRESS" -> {
                if (service == null) return "Accessibility Service inactive"
                val x = params.optDouble("x", -1.0).toFloat()
                val y = params.optDouble("y", -1.0).toFloat()
                val duration = params.optLong("durationMs", 750L)
                if (x < 0f || y < 0f) return "Invalid long-press coordinates"
                if (!service.gestureEngine.longPress(x, y, duration)) "Long-press cancelled" else null
            }

            "SCROLL" -> {
                if (service == null) return "Accessibility Service inactive"
                val dir = params.optString("direction", "SCROLL_DOWN")
                if (!service.performDirectionalScroll(dir)) "Scroll gesture cancelled" else null
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
                        else if (!service.performGlobalSystemAction(cmd)) "Global action $cmd unsupported" else null
                    }
                }
            }

            else -> null
        }
    }

    companion object {
        const val MAX_AGENT_STEPS = 8
        const val STEP_SETTLE_DELAY_MS = 900L
    }
}
