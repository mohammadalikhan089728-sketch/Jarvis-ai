package com.example

import org.json.JSONObject
import java.util.Locale

data class AgentExecutionResult(
    val executedAction: String,
    val paramsJson: String,
    val reply: String,
    val isOffline: Boolean,
    val latencyMs: Long
)

class JarvisAgentEngine(
    private val repository: JarvisRepository,
    private val geminiApiClient: GeminiApiClient,
    private val offlineProcessor: OfflineCommandProcessor
) {

    suspend fun processCommand(
        rawQuery: String,
        settings: SettingsEntity,
        onPhaseUpdate: (JarvisPhase) -> Unit
    ): AgentExecutionResult {
        val startMs = System.currentTimeMillis()
        val query = rawQuery.trim()
        val normalizedLower = query.lowercase(Locale.ROOT)

        // 1. Check local Room Custom Macros first
        val macros = repository.getAllMacros()
        val matchedMacro = macros.firstOrNull { macro ->
            val trigger = macro.triggerPhrase.trim().lowercase(Locale.ROOT)
            trigger.isNotEmpty() && (normalizedLower == trigger || normalizedLower.contains(trigger))
        }
        if (matchedMacro != null) {
            onPhaseUpdate(JarvisPhase.ACTING)
            val macroResult = executeMacro(matchedMacro, settings, onPhaseUpdate)
            val elapsed = System.currentTimeMillis() - startMs
            repository.logInteraction(
                ChatHistoryEntity(
                    userTranscript = query,
                    executedAction = macroResult.executedAction,
                    actionParamsJson = macroResult.paramsJson,
                    jarvisReply = macroResult.reply,
                    isOffline = macroResult.isOffline,
                    latencyMs = elapsed
                )
            )
            return macroResult.copy(latencyMs = elapsed)
        }

        // 2. Instant Offline Execution (<100ms) when Offline-First is active or direct device control matches
        val offlineMatch = offlineProcessor.tryExecute(query)
        if (offlineMatch != null && (settings.offlineFirstMode || settings.geminiApiKey.isBlank())) {
            onPhaseUpdate(JarvisPhase.ACTING)
            val elapsed = System.currentTimeMillis() - startMs
            repository.logInteraction(
                ChatHistoryEntity(
                    userTranscript = query,
                    executedAction = offlineMatch.actionTag,
                    actionParamsJson = offlineMatch.paramsJson,
                    jarvisReply = offlineMatch.reply,
                    isOffline = true,
                    latencyMs = elapsed
                )
            )
            return AgentExecutionResult(
                executedAction = offlineMatch.actionTag,
                paramsJson = offlineMatch.paramsJson,
                reply = offlineMatch.reply,
                isOffline = true,
                latencyMs = elapsed
            )
        }

        // 3. Offline-only fallback if no Gemini API key is configured
        if (settings.geminiApiKey.isBlank()) {
            val screenSnapshot = JarvisAccessibilityService.instance?.readScreenHierarchy()
            val reply = if (normalizedLower.contains("read") && normalizedLower.contains("screen")) {
                screenSnapshot?.toHumanSummary()
                    ?: "Accessibility Service is inactive. Activate it in Systems so I can read the screen."
            } else {
                "Offline command not recognized and no Gemini API key is saved. Open Config to add your key for gemini-3.5-flash."
            }
            val elapsed = System.currentTimeMillis() - startMs
            repository.logInteraction(
                ChatHistoryEntity(
                    userTranscript = query,
                    executedAction = "OFFLINE:SPEAK",
                    actionParamsJson = "{}",
                    jarvisReply = reply,
                    isOffline = true,
                    latencyMs = elapsed
                )
            )
            return AgentExecutionResult("OFFLINE:SPEAK", "{}", reply, true, elapsed)
        }

        // 4. Screen-Aware AI Agent Loop powered exclusively by gemini-3.5-flash
        onPhaseUpdate(JarvisPhase.THINKING)
        val screenContextJson = if (settings.autoReadScreenContext) {
            JarvisAccessibilityService.instance?.readScreenHierarchy()?.toCompactJson()
        } else {
            null
        }
        val recentHistory = repository.getRecentHistory(limit = 4)

        val aiResponse = geminiApiClient.requestAgentAction(
            rawApiKey = settings.geminiApiKey,
            masterSystemPrompt = settings.masterSystemPrompt,
            userQuery = query,
            screenContextJson = screenContextJson,
            recentHistory = recentHistory
        )

        val finalResult = aiResponse.fold(
            onSuccess = { aiAction ->
                onPhaseUpdate(JarvisPhase.ACTING)
                val execNote = executeStructuredAction(aiAction.action, aiAction.params)
                val finalReply = if (execNote != null) "${aiAction.reply} ($execNote)" else aiAction.reply
                val elapsed = System.currentTimeMillis() - startMs
                AgentExecutionResult(
                    executedAction = "GEMINI:${aiAction.action}",
                    paramsJson = aiAction.params.toString(),
                    reply = finalReply,
                    isOffline = false,
                    latencyMs = elapsed
                )
            },
            onFailure = { err ->
                val elapsed = System.currentTimeMillis() - startMs
                AgentExecutionResult(
                    executedAction = "ERROR:GEMINI",
                    paramsJson = "{}",
                    reply = err.localizedMessage ?: "Unable to reach gemini-3.5-flash right now.",
                    isOffline = false,
                    latencyMs = elapsed
                )
            }
        )

        repository.logInteraction(
            ChatHistoryEntity(
                userTranscript = query,
                executedAction = finalResult.executedAction,
                actionParamsJson = finalResult.paramsJson,
                jarvisReply = finalResult.reply,
                isOffline = finalResult.isOffline,
                latencyMs = finalResult.latencyMs
            )
        )
        return finalResult
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
            "SPEAK" -> null

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
                if (!service.typeText(text, target)) "No editable text field found" else null
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
}
