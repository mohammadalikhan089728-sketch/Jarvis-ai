package com.example

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class JarvisAiAction(
    val action: String,
    val params: JSONObject,
    val reply: String,
    val saveMemory: String? = null
)

data class AiAudioPayload(
    val audioBytes: ByteArray,
    val mimeType: String
)

sealed interface KeyVerificationResult {
    data class Valid(val latencyMs: Long) : KeyVerificationResult
    data class Invalid(val message: String) : KeyVerificationResult
}

private data class EngineModelSpec(
    val modelId: String,
    val maxOutputTokens: Int,
    val thinkingBudget: Int = 0
)

/**
 * Strict text sanitizer guaranteeing JARVIS NEVER displays or speaks raw markdown fences,
 * JSON wrappers ("json { reply: ... }"), brackets, asterisks, or third-party AI brand names.
 */
object JarvisResponseSanitizer {

    private val FENCE_PREFIX_REGEX = Regex("^```(?:json|JSON)?\\s*")
    private val FENCE_SUFFIX_REGEX = Regex("\\s*```$")
    private val LEADING_JSON_WORD_REGEX = Regex("^(?:json|JSON)\\s*(?=\\{)")
    private val REPLY_FIELD_REGEX = Regex(""""reply"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val TEXT_FIELD_REGEX = Regex(""""(?:text|message|response)"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val FORBIDDEN_CHARS_REGEX = Regex("""[\{\}\[\]"\*`#]""")
    private val BRAND_LEAK_REGEX = Regex("(?i)gemini[\\w.-]*")
    private val MULTI_SPACE_REGEX = Regex("\\s+")

    fun extractCleanReply(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        val unfenced = stripMarkdownFences(trimmed)
        val extracted = extractReplyFromPossibleJson(unfenced) ?: unfenced
        return sanitizePlainSpeechText(extracted)
    }

    fun sanitizePlainSpeechText(raw: String): String {
        if (raw.isBlank()) return ""
        var text = stripMarkdownFences(raw)
        if (looksLikeJsonEnvelope(text)) {
            extractReplyFromPossibleJson(text)?.let { text = it }
        }
        return text
            .replace("\\n", " ")
            .replace("\\\"", "")
            .replace(Regex("(?i)^\\s*json\\s*:?\\s*"), "")
            .replace(Regex("(?i)^\\s*reply\\s*:\\s*"), "")
            .replace("**", "")
            .replace("__", "")
            .replace(FORBIDDEN_CHARS_REGEX, " ")
            .replace(BRAND_LEAK_REGEX, "JARVIS Core")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

    fun looksLikeJsonEnvelope(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("{") ||
            t.startsWith("```") ||
            (t.startsWith("json", ignoreCase = true) && (t.contains("{") || t.contains("reply")))
    }

    fun stripMarkdownFences(raw: String): String {
        var cleaned = raw.trim()
        cleaned = FENCE_PREFIX_REGEX.replace(cleaned, "")
        cleaned = FENCE_SUFFIX_REGEX.replace(cleaned, "")
        cleaned = LEADING_JSON_WORD_REGEX.replace(cleaned, "")
        return cleaned.trim()
    }

    private fun extractReplyFromPossibleJson(candidate: String): String? {
        val start = candidate.indexOf('{')
        val end = candidate.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val jsonSlice = candidate.substring(start, end + 1)
            runCatching {
                val obj = JSONObject(jsonSlice)
                val reply = obj.optString("reply", "").trim()
                if (reply.isNotEmpty()) return reply
                val text = obj.optString("text", "").trim()
                if (text.isNotEmpty()) return text
                val message = obj.optString("message", "").trim()
                if (message.isNotEmpty()) return message
            }
        }

        val replyMatch = REPLY_FIELD_REGEX.find(candidate)
            ?: TEXT_FIELD_REGEX.find(candidate)
        if (replyMatch != null) {
            return unescapeJsonString(replyMatch.groupValues[1])
        }
        return null
    }

    private fun unescapeJsonString(escaped: String): String {
        return escaped
            .replace("\\n", " ")
            .replace("\\r", " ")
            .replace("\\t", " ")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .trim()
    }
}

/**
 * JARVIS Neural Core Network Client (Developed by Mozahid).
 * - Supports 2 selectable engines with silent automatic fallback on HTTP 404/429/503:
 *     1. Core Engine v2.5 (Default): gemini-2.5-flash-lite -> gemini-2.5-flash -> gemini-3.5-flash (maxOutputTokens=180, thinkingBudget=0)
 *     2. Core Engine v3.5 (Backup):  gemini-3.5-flash -> gemini-2.5-flash-lite -> gemini-2.5-flash (maxOutputTokens=220, thinkingBudget=0)
 * - Supports compact top-6 long-term memory injection (<= 100 tokens) and optional "save_memory" JSON field.
 * - Supports real-time Multimodal Live WebSocket audio streaming (BidiGenerateContent) for "With AI Voice".
 */
class GeminiApiClient {

    companion object {
        // Strictly internal model IDs (NEVER exposed in user-visible UI strings)
        const val MODEL_V25_LITE = "gemini-2.5-flash-lite"
        const val MODEL_V25_FLASH = "gemini-2.5-flash"
        const val MODEL_V35_FLASH = "gemini-3.5-flash"
        const val LIVE_AUDIO_MODEL_NAME = "models/gemini-2.5-flash-native-audio-preview-12-2025"

        private const val API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val BIDI_LIVE_WS_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeVoiceWebSocket: WebSocket? = null

    fun cancelActiveVoiceStream() {
        val ws = activeVoiceWebSocket
        activeVoiceWebSocket = null
        runCatching { ws?.close(1000, "Cancelled") }
    }

    private fun resolveEngineChain(selectedEngine: String): List<EngineModelSpec> {
        return if (selectedEngine == ENGINE_V35_DEEP) {
            listOf(
                EngineModelSpec(MODEL_V35_FLASH, maxOutputTokens = 220, thinkingBudget = 0),
                EngineModelSpec(MODEL_V25_LITE, maxOutputTokens = 180, thinkingBudget = 0),
                EngineModelSpec(MODEL_V25_FLASH, maxOutputTokens = 180, thinkingBudget = 0)
            )
        } else {
            listOf(
                EngineModelSpec(MODEL_V25_LITE, maxOutputTokens = 180, thinkingBudget = 0),
                EngineModelSpec(MODEL_V25_FLASH, maxOutputTokens = 180, thinkingBudget = 0),
                EngineModelSpec(MODEL_V35_FLASH, maxOutputTokens = 220, thinkingBudget = 0)
            )
        }
    }

    private fun generateUrl(modelId: String) = "$API_BASE_URL/$modelId:generateContent"
    private fun streamUrl(modelId: String) = "$API_BASE_URL/$modelId:streamGenerateContent?alt=sse"

    /**
     * NEVER rapidly auto-retry on 429 or 404 (prevents RPM/RPD quota lockouts).
     * Only trigger silent single-hop failover on transient 500/503 server hiccups.
     */
    private fun shouldFailoverHttpCode(code: Int): Boolean {
        if (code == 429 || code == 404) {
            PersistentLiveAudioSession.triggerQuotaCooldown()
            return false
        }
        return code == 500 || code == 503
    }

    suspend fun verifyApiKey(
        rawApiKey: String,
        selectedEngine: String = ENGINE_V25_LITE
    ): KeyVerificationResult = withContext(Dispatchers.IO) {
        val apiKey = rawApiKey.trim()
        if (apiKey.isEmpty()) {
            return@withContext KeyVerificationResult.Invalid("API key is empty. Enter a valid JARVIS AI Core key.")
        }

        val chain = resolveEngineChain(selectedEngine)
        var lastError = "Unable to verify JARVIS AI Core key."

        for (spec in chain) {
            val startTime = System.currentTimeMillis()
            try {
                val payload = JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put("parts", JSONArray().put(JSONObject().put("text", "Reply OK")))
                            }
                        )
                    )
                    put(
                        "generationConfig",
                        JSONObject().apply {
                            put("maxOutputTokens", 8)
                            put("temperature", 0.0)
                        }
                    )
                }

                val request = Request.Builder()
                    .url(generateUrl(spec.modelId))
                    .header("x-goog-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val latency = System.currentTimeMillis() - startTime
                    val responseBody = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        return@withContext KeyVerificationResult.Valid(latencyMs = latency)
                    }
                    val apiError = sanitizeErrorText(extractApiErrorMessage(responseBody))
                    lastError = when (response.code) {
                        400 -> "Invalid request (400): ${apiError.ifBlank { "Check your API key format." }}"
                        401, 403 -> "Authentication failed (${response.code}): API key rejected."
                        404 -> "Engine endpoint unavailable (404)."
                        429 -> "Rate limit reached (429): Quota temporarily exceeded."
                        else -> "Connection error (${response.code}): ${apiError.ifBlank { response.message }}"
                    }
                    if (!shouldFailoverHttpCode(response.code)) {
                        return@withContext KeyVerificationResult.Invalid(lastError)
                    }
                }
            } catch (e: Exception) {
                lastError = "Network error: ${sanitizeErrorText(e.localizedMessage ?: "Unable to reach JARVIS AI Core.")}"
                return@withContext KeyVerificationResult.Invalid(lastError)
            }
        }
        KeyVerificationResult.Invalid(lastError)
    }

    /**
     * Real-time Multimodal Live WebSocket audio streaming for "With AI Voice" mode.
     * Streams incoming 24kHz 16-bit PCM chunks directly into AudioTrack (~400ms latency).
     */
    suspend fun streamLiveAiVoice(
        rawApiKey: String,
        textToSpeak: String,
        voiceName: String = AI_VOICE_CHARON,
        onPcmChunk: (pcmBytes: ByteArray, sampleRateHz: Int) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        val apiKey = rawApiKey.trim()
        val cleanText = JarvisResponseSanitizer.extractCleanReply(textToSpeak)
        if (apiKey.isEmpty() || cleanText.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("AI Voice requires an active key and clean text."))
        }

        val resolvedVoice = when (voiceName.trim()) {
            AI_VOICE_ORUS -> AI_VOICE_ORUS
            AI_VOICE_FENRIR -> AI_VOICE_FENRIR
            else -> AI_VOICE_CHARON
        }
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val wsUrl = "$BIDI_LIVE_WS_BASE_URL?key=$encodedKey"

        val completion = CompletableDeferred<Result<Int>>()
        val setupSent = AtomicBoolean(false)
        val promptSent = AtomicBoolean(false)
        val chunkCount = AtomicInteger(0)

        cancelActiveVoiceStream()

        fun buildSetupJson(): String {
            return JSONObject().apply {
                put(
                    "setup",
                    JSONObject().apply {
                        put("model", LIVE_AUDIO_MODEL_NAME)
                        put(
                            "generationConfig",
                            JSONObject().apply {
                                put("responseModalities", JSONArray().put("AUDIO"))
                                put(
                                    "speechConfig",
                                    JSONObject().apply {
                                        put(
                                            "voiceConfig",
                                            JSONObject().apply {
                                                put(
                                                    "prebuiltVoiceConfig",
                                                    JSONObject().apply {
                                                        put("voiceName", resolvedVoice)
                                                    }
                                                )
                                            }
                                        )
                                    }
                                )
                            }
                        )
                        put(
                            "systemInstruction",
                            JSONObject().apply {
                                put(
                                    "parts",
                                    JSONArray().put(
                                        JSONObject().put(
                                            "text",
                                            "You are JARVIS, developed by Mozahid. Speak the exact text provided by the user naturally, calmly, and clearly in a deep male voice. Do not add extra words, labels, or commentary."
                                        )
                                    )
                                )
                            }
                        )
                    }
                )
            }.toString()
        }

        fun buildClientTurnJson(): String {
            return JSONObject().apply {
                put(
                    "clientContent",
                    JSONObject().apply {
                        put(
                            "turns",
                            JSONArray().put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put(
                                        "parts",
                                        JSONArray().put(
                                            JSONObject().put(
                                                "text",
                                                "Say this aloud verbatim with natural human intonation:\n$cleanText"
                                            )
                                        )
                                    )
                                }
                            )
                        )
                        put("turnComplete", true)
                    }
                )
            }.toString()
        }

        fun handleServerJsonFrame(webSocket: WebSocket, rawJson: String) {
            try {
                val root = JSONObject(rawJson)

                if (root.has("setupComplete") && promptSent.compareAndSet(false, true)) {
                    webSocket.send(buildClientTurnJson())
                    return
                }

                if (root.has("error")) {
                    val errMsg = sanitizeErrorText(root.optJSONObject("error")?.optString("message").orEmpty())
                    completion.complete(Result.failure(IllegalStateException(errMsg.ifBlank { "Live audio error" })))
                    runCatching { webSocket.close(1000, "Error") }
                    return
                }

                val serverContent = root.optJSONObject("serverContent") ?: return
                val modelTurn = serverContent.optJSONObject("modelTurn")
                val parts = modelTurn?.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val inlineData = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                        val b64 = inlineData.optString("data", "")
                        if (b64.isBlank()) continue
                        val mime = inlineData.optString("mimeType", "audio/pcm;rate=24000")
                        val sampleRate = parsePcmSampleRate(mime)
                        val decoded = Base64.decode(b64, Base64.DEFAULT)
                        if (decoded.isNotEmpty()) {
                            chunkCount.incrementAndGet()
                            onPcmChunk(decoded, sampleRate)
                        }
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    val total = chunkCount.get()
                    if (total > 0) {
                        completion.complete(Result.success(total))
                    } else {
                        completion.complete(Result.failure(IllegalStateException("Live audio turn completed without PCM chunks.")))
                    }
                    runCatching { webSocket.close(1000, "Turn complete") }
                }
            } catch (_: Exception) {
                // Ignore non-JSON keepalive frames
            }
        }

        val request = Request.Builder().url(wsUrl).build()
        val ws = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (setupSent.compareAndSet(false, true)) {
                        webSocket.send(buildSetupJson())
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleServerJsonFrame(webSocket, text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleServerJsonFrame(webSocket, bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    val total = chunkCount.get()
                    if (!completion.isCompleted) {
                        if (total > 0) completion.complete(Result.success(total))
                        else completion.complete(Result.failure(IllegalStateException("Live audio stream closed ($code).")))
                    }
                    runCatching { webSocket.close(1000, null) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!completion.isCompleted) {
                        val total = chunkCount.get()
                        if (total > 0) completion.complete(Result.success(total))
                        else completion.complete(
                            Result.failure(
                                IllegalStateException(sanitizeErrorText(t.localizedMessage ?: "Live audio unavailable."))
                            )
                        )
                    }
                }
            }
        )
        activeVoiceWebSocket = ws

        val outcome = withTimeoutOrNull(20_000L) {
            completion.await()
        } ?: run {
            runCatching { ws.close(1000, "Timeout") }
            if (chunkCount.get() > 0) Result.success(chunkCount.get())
            else Result.failure(IllegalStateException("Live AI Voice WebSocket timed out."))
        }

        if (activeVoiceWebSocket === ws) {
            activeVoiceWebSocket = null
        }
        outcome
    }

    /**
     * Ultra-fast token-optimized SSE streaming conversational reply with automatic Core Engine failover,
     * thinkingBudget = 0, strict maxOutputTokens cap, and compact top-6 long-term memory injection.
     */
    suspend fun streamConversationalReply(
        rawApiKey: String,
        masterSystemPrompt: String,
        userPrompt: String,
        recentHistory: List<ChatHistoryEntity> = emptyList(),
        extraSystemGuidance: String = "",
        selectedEngine: String = ENGINE_V25_LITE,
        memoryContext: String = "",
        onSentenceReady: suspend (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = rawApiKey.trim()
        if (apiKey.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("JARVIS AI Core key is not configured. Open Config to enter your key.")
            )
        }

        if (PersistentLiveAudioSession.isInQuotaCooldown()) {
            val sec = PersistentLiveAudioSession.remainingCooldownSeconds()
            return@withContext Result.failure(
                IllegalStateException("Sir, rate-limit protection active hai (${sec}s). Offline controls abhi bhi kaam kar rahe hain.")
            )
        }

        val systemText = buildString {
            append(masterSystemPrompt.ifBlank { DEFAULT_MASTER_PROMPT })
            appendLine()
            appendLine("IDENTITY & VOICE OUTPUT RULES:")
            appendLine("- You are J.A.R.V.I.S. (Just A Rather Very Intelligent System), created and developed by Mozahid (your creator and boss, like Tony Stark).")
            appendLine("- If asked who you are, who created you, or what model you are, reply verbatim: \"$JARVIS_IDENTITY_REPLY\"")
            appendLine("- Always respond ONLY in crisp, natural Hinglish (1-2 short spoken sentences). NEVER output JSON, brackets, markdown fences, asterisks, or bullet lists.")
            if (memoryContext.isNotBlank()) {
                appendLine()
                appendLine("USER LONG-TERM MEMORY:")
                appendLine(memoryContext)
            }
            if (extraSystemGuidance.isNotBlank()) {
                appendLine(extraSystemGuidance)
            }
        }

        val contentsArray = JSONArray()
        for (entry in recentHistory.takeLast(3)) {
            contentsArray.put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", entry.userTranscript)))
                }
            )
            contentsArray.put(
                JSONObject().apply {
                    put("role", "model")
                    put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put("text", JarvisResponseSanitizer.extractCleanReply(entry.jarvisReply))
                        )
                    )
                }
            )
        }
        contentsArray.put(
            JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
            }
        )

        val chain = resolveEngineChain(selectedEngine)
        var lastError: Throwable = IllegalStateException("Unable to reach JARVIS AI Core.")

        for (spec in chain) {
            val attempt = executeConversationalStreamOnce(
                apiKey = apiKey,
                spec = spec,
                systemText = systemText,
                contentsArray = contentsArray,
                includeThinkingConfig = true,
                onSentenceReady = onSentenceReady
            )

            if (attempt.isSuccess) {
                return@withContext Result.success(attempt.getOrThrow())
            }
            val err = attempt.exceptionOrNull()
            if (err is ThinkingConfigUnsupportedException) {
                val retryNoThinking = executeConversationalStreamOnce(
                    apiKey = apiKey,
                    spec = spec,
                    systemText = systemText,
                    contentsArray = contentsArray,
                    includeThinkingConfig = false,
                    onSentenceReady = onSentenceReady
                )
                if (retryNoThinking.isSuccess) {
                    return@withContext Result.success(retryNoThinking.getOrThrow())
                }
                lastError = retryNoThinking.exceptionOrNull() ?: lastError
                if (lastError !is FailoverHttpException) break
            } else if (err is FailoverHttpException) {
                lastError = err
                continue
            } else {
                return@withContext Result.failure(err ?: lastError)
            }
        }

        Result.failure(lastError)
    }

    private suspend fun executeConversationalStreamOnce(
        apiKey: String,
        spec: EngineModelSpec,
        systemText: String,
        contentsArray: JSONArray,
        includeThinkingConfig: Boolean,
        onSentenceReady: suspend (String) -> Unit
    ): Result<String> {
        return try {
            val genConfig = JSONObject().apply {
                put("temperature", 0.4)
                put("maxOutputTokens", spec.maxOutputTokens)
                if (includeThinkingConfig) {
                    put("thinkingConfig", JSONObject().put("thinkingBudget", spec.thinkingBudget))
                }
            }
            val payload = JSONObject().apply {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemText))))
                put("contents", contentsArray)
                put("generationConfig", genConfig)
            }

            val request = Request.Builder()
                .url(streamUrl(spec.modelId))
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val fullText = StringBuilder()
            val sentenceBuffer = StringBuilder()
            var detectedJsonStream = false
            var emittedAnySentence = false

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val apiMsg = sanitizeErrorText(extractApiErrorMessage(body))
                    if (response.code == 400 && includeThinkingConfig && body.contains("thinking", ignoreCase = true)) {
                        return Result.failure(ThinkingConfigUnsupportedException())
                    }
                    val message = "JARVIS AI Core error ${response.code}: ${apiMsg.ifBlank { response.message }}"
                    if (shouldFailoverHttpCode(response.code)) {
                        return Result.failure(FailoverHttpException(response.code, message))
                    }
                    return Result.failure(IllegalStateException(message))
                }

                val source = response.body?.source()
                    ?: return Result.failure(IllegalStateException("Empty response stream from JARVIS AI Core."))

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val jsonPayload = line.removePrefix("data:").trim()
                    if (jsonPayload.isEmpty() || jsonPayload == "[DONE]") continue

                    val chunkText = extractCandidateText(jsonPayload)
                    if (chunkText.isEmpty()) continue

                    fullText.append(chunkText)
                    sentenceBuffer.append(chunkText)

                    val trimmedStart = fullText.toString().trimStart()
                    if (!emittedAnySentence && (
                            trimmedStart.startsWith("{") ||
                                trimmedStart.startsWith("```") ||
                                trimmedStart.startsWith("json", ignoreCase = true)
                            )
                    ) {
                        detectedJsonStream = true
                        continue
                    }

                    if (!detectedJsonStream) {
                        while (true) {
                            val cutIndex = findSentenceBoundary(sentenceBuffer)
                            if (cutIndex < 0) break
                            val sentence = JarvisResponseSanitizer.sanitizePlainSpeechText(
                                sentenceBuffer.substring(0, cutIndex + 1)
                            )
                            sentenceBuffer.delete(0, cutIndex + 1)
                            if (sentence.isNotEmpty()) {
                                emittedAnySentence = true
                                onSentenceReady(sentence)
                            }
                        }
                    }
                }
            }

            if (detectedJsonStream) {
                val cleanReply = JarvisResponseSanitizer.extractCleanReply(fullText.toString())
                if (cleanReply.isNotEmpty()) {
                    onSentenceReady(cleanReply)
                    return Result.success(cleanReply)
                }
            } else {
                val trailing = JarvisResponseSanitizer.sanitizePlainSpeechText(sentenceBuffer.toString())
                if (trailing.isNotEmpty()) {
                    onSentenceReady(trailing)
                }
            }

            val combined = JarvisResponseSanitizer.extractCleanReply(fullText.toString())
            if (combined.isBlank()) {
                Result.failure(IllegalStateException("Empty response from JARVIS AI Core."))
            } else {
                Result.success(combined)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Autonomous multi-step phone automation decision call with Engine v2.5 / v3.5 selection,
     * automatic failover, thinkingBudget=0, compact 25-node UI JSON, top-6 memories, and optional "save_memory".
     */
    suspend fun requestAgentAction(
        rawApiKey: String,
        masterSystemPrompt: String,
        userQuery: String,
        screenContextJson: String?,
        recentHistory: List<ChatHistoryEntity>,
        stepHistory: List<String> = emptyList(),
        stepIndex: Int = 1,
        maxSteps: Int = 8,
        selectedEngine: String = ENGINE_V25_LITE,
        memoryContext: String = ""
    ): Result<JarvisAiAction> = withContext(Dispatchers.IO) {
        val apiKey = rawApiKey.trim()
        if (apiKey.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("JARVIS AI Core key is not configured. Open Config to enter your key.")
            )
        }
        if (PersistentLiveAudioSession.isInQuotaCooldown()) {
            val sec = PersistentLiveAudioSession.remainingCooldownSeconds()
            return@withContext Result.failure(
                IllegalStateException("Rate-limit cooldown active (${sec}s) to protect your daily quota.")
            )
        }

        try {
            val combinedSystemPrompt = buildSystemInstruction(masterSystemPrompt, memoryContext)
            val contentsArray = JSONArray()

            if (stepIndex == 1) {
                for (entry in recentHistory.takeLast(2)) {
                    contentsArray.put(
                        JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().put(JSONObject().put("text", entry.userTranscript)))
                        }
                    )
                    contentsArray.put(
                        JSONObject().apply {
                            put("role", "model")
                            put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().put(
                                        "text",
                                        JSONObject().apply {
                                            put("action", entry.executedAction)
                                            put("params", JSONObject())
                                            put("reply", JarvisResponseSanitizer.extractCleanReply(entry.jarvisReply))
                                        }.toString()
                                    )
                                )
                            )
                        }
                    )
                }
            }

            val turnPrompt = buildString {
                appendLine("GOAL: $userQuery")
                appendLine("STEP: $stepIndex/$maxSteps")
                if (stepHistory.isNotEmpty()) {
                    appendLine("DONE: ${stepHistory.joinToString(" | ")}")
                }
                if (!screenContextJson.isNullOrBlank()) {
                    appendLine("UI: $screenContextJson")
                }
            }

            contentsArray.put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", turnPrompt)))
                }
            )

            val rawModelText = executeCollectWithEngineFailover(
                apiKey = apiKey,
                selectedEngine = selectedEngine,
                systemText = combinedSystemPrompt,
                contentsArray = contentsArray,
                temperature = 0.15,
                jsonOutput = true
            ).getOrElse { err ->
                return@withContext Result.failure(err)
            }

            if (rawModelText.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Empty decision response from JARVIS AI Core.")
                )
            }
            Result.success(parseActionJson(rawModelText))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Generates a natural human-like background auto-reply for WhatsApp/Telegram/Instagram/SMS notifications.
     */
    suspend fun generateAutoChatReply(
        rawApiKey: String,
        masterSystemPrompt: String,
        senderName: String,
        appLabel: String,
        incomingMessage: String,
        recentConversation: List<AutoChatLogsEntity>,
        selectedEngine: String = ENGINE_V25_LITE
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = rawApiKey.trim()
        if (apiKey.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("JARVIS AI Core key missing."))
        }

        try {
            val systemText = buildString {
                append(masterSystemPrompt.ifBlank { DEFAULT_MASTER_PROMPT })
                appendLine()
                appendLine("AUTONOMOUS CHAT AUTO-REPLY MODE:")
                appendLine("- Reply on the user's behalf in $appLabel to \"$senderName\".")
                appendLine("- Natural, warm, concise (1-2 short sentences), matching the sender's language.")
                appendLine("- Output ONLY the exact message text to send. Never output JSON or external AI brands.")
            }

            val convoHistory = buildString {
                if (recentConversation.isNotEmpty()) {
                    appendLine("RECENT CHAT:")
                    recentConversation.takeLast(6).forEach { log ->
                        val who = if (log.direction == AUTO_CHAT_DIR_INCOMING) senderName else "Me"
                        appendLine("$who: ${log.messageText}")
                    }
                }
                appendLine("LATEST FROM $senderName: $incomingMessage")
            }

            val contentsArray = JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", convoHistory)))
                }
            )

            executeCollectWithEngineFailover(
                apiKey = apiKey,
                selectedEngine = selectedEngine,
                systemText = systemText,
                contentsArray = contentsArray,
                temperature = 0.5,
                jsonOutput = false
            ).map { text ->
                JarvisResponseSanitizer.extractCleanReply(text).take(400)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun executeCollectWithEngineFailover(
        apiKey: String,
        selectedEngine: String,
        systemText: String,
        contentsArray: JSONArray,
        temperature: Double,
        jsonOutput: Boolean
    ): Result<String> {
        val chain = resolveEngineChain(selectedEngine)
        var lastError: Throwable = IllegalStateException("Unable to reach JARVIS AI Core.")

        for (spec in chain) {
            val attempt = executeStreamingCollectOnce(
                apiKey = apiKey,
                spec = spec,
                systemText = systemText,
                contentsArray = contentsArray,
                temperature = temperature,
                jsonOutput = jsonOutput,
                includeThinkingConfig = true
            )
            if (attempt.isSuccess) return attempt
            val err = attempt.exceptionOrNull()
            if (err is ThinkingConfigUnsupportedException) {
                val retry = executeStreamingCollectOnce(
                    apiKey = apiKey,
                    spec = spec,
                    systemText = systemText,
                    contentsArray = contentsArray,
                    temperature = temperature,
                    jsonOutput = jsonOutput,
                    includeThinkingConfig = false
                )
                if (retry.isSuccess) return retry
                lastError = retry.exceptionOrNull() ?: lastError
                if (lastError !is FailoverHttpException) break
            } else if (err is FailoverHttpException) {
                lastError = err
                continue
            } else {
                return Result.failure(err ?: lastError)
            }
        }
        return Result.failure(lastError)
    }

    private fun executeStreamingCollectOnce(
        apiKey: String,
        spec: EngineModelSpec,
        systemText: String,
        contentsArray: JSONArray,
        temperature: Double,
        jsonOutput: Boolean,
        includeThinkingConfig: Boolean
    ): Result<String> {
        val genConfig = JSONObject().apply {
            put("temperature", temperature)
            put("maxOutputTokens", spec.maxOutputTokens)
            if (jsonOutput) {
                put("responseMimeType", "application/json")
            }
            if (includeThinkingConfig) {
                put("thinkingConfig", JSONObject().put("thinkingBudget", spec.thinkingBudget))
            }
        }
        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemText))))
            put("contents", contentsArray)
            put("generationConfig", genConfig)
        }

        val request = Request.Builder()
            .url(streamUrl(spec.modelId))
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                if (response.code == 400 && includeThinkingConfig && body.contains("thinking", ignoreCase = true)) {
                    return Result.failure(ThinkingConfigUnsupportedException())
                }
                val err = sanitizeErrorText(extractApiErrorMessage(body))
                val msg = "JARVIS AI Core error ${response.code}: ${err.ifBlank { response.message }}"
                if (shouldFailoverHttpCode(response.code)) {
                    return Result.failure(FailoverHttpException(response.code, msg))
                }
                return Result.failure(IllegalStateException(msg))
            }

            val source = response.body?.source()
                ?: return Result.failure(IllegalStateException("Empty response stream from JARVIS AI Core."))
            val sb = StringBuilder()

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val jsonPayload = line.removePrefix("data:").trim()
                if (jsonPayload.isEmpty() || jsonPayload == "[DONE]") continue
                val chunk = extractCandidateText(jsonPayload)
                if (chunk.isNotEmpty()) sb.append(chunk)
            }

            return Result.success(sb.toString().trim())
        }
    }

    private fun findSentenceBoundary(buffer: StringBuilder): Int {
        for (i in 0 until buffer.length) {
            val ch = buffer[i]
            if (ch == '?' || ch == '!' || ch == '।' || ch == '\n') {
                return i
            }
            if (ch == '.') {
                val prevIsDigit = i > 0 && buffer[i - 1].isDigit()
                val nextIsDigit = i + 1 < buffer.length && buffer[i + 1].isDigit()
                val nextIsSpaceOrEnd = (i + 1 == buffer.length) || buffer[i + 1].isWhitespace()
                if (!(prevIsDigit && nextIsDigit) && nextIsSpaceOrEnd && i >= 6) {
                    return i
                }
            }
        }
        return -1
    }

    private fun parsePcmSampleRate(mimeType: String): Int {
        val match = Regex("rate=(\\d+)").find(mimeType.lowercase())
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(8000, 48000) ?: 24000
    }

    private fun sanitizeErrorText(raw: String): String {
        return raw.replace(Regex("(?i)gemini[\\w.-]*"), "JARVIS Core").trim()
    }

    private fun buildSystemInstruction(userMasterPrompt: String, memoryContext: String): String {
        return buildString {
            append(userMasterPrompt.ifBlank { DEFAULT_MASTER_PROMPT })
            if (memoryContext.isNotBlank()) {
                appendLine()
                appendLine("USER MEMORY:")
                appendLine(memoryContext)
            }
            appendLine()
            append(
                """
COMPACT AGENT PROTOCOL (STRICT SINGLE JSON OBJECT ONLY):
You are J.A.R.V.I.S., created and developed by Mozahid. Reply in crisp natural Hinglish (1 short sentence). Never mention any external AI brand.
Schema:
{"action":"OPEN_APP|TAP_TEXT|TAP_XY|LONG_PRESS_XY|LONG_PRESS|TYPE_TEXT|SWIPE|SCROLL_DOWN|SCROLL_UP|SCROLL|DRAG|MULTI_TOUCH|BACK|HOME|RECENTS|GLOBAL|DONE|SPEAK","params":{},"reply":"Short clean Hinglish spoken sentence","save_memory":"optional short user fact to remember or empty"}
Rules:
- Execute ONE next action using UI nodes (id,text,x,y). Return DONE once goal completes, SPEAK if no action needed.
- Params: OPEN_APP{"appName"}, TAP_TEXT{"text"}, TAP_XY{"x","y"}, LONG_PRESS_XY{"x","y"}, TYPE_TEXT{"text","submit":true}, SWIPE{"x1","y1","x2","y2"}, SCROLL{"direction"}, DRAG{"startX","startY","endX","endY"}, MULTI_TOUCH{"gesture"}, GLOBAL{"command","target"}.
- Only fill "save_memory" when user shares a new personal fact/preference/routine worth remembering permanently.
- No markdown fences or text outside JSON.
                """.trimIndent()
            )
        }
    }

    private fun extractCandidateText(rawResponse: String): String {
        return try {
            val json = JSONObject(rawResponse)
            val candidates = json.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val t = parts.optJSONObject(i)?.optString("text").orEmpty()
                if (t.isNotEmpty()) sb.append(t)
            }
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseActionJson(rawText: String): JarvisAiAction {
        val cleaned = JarvisResponseSanitizer.stripMarkdownFences(rawText)
        val jsonStart = cleaned.indexOf('{')
        val jsonEnd = cleaned.lastIndexOf('}')
        val candidateJson = if (jsonStart >= 0 && jsonEnd > jsonStart) {
            cleaned.substring(jsonStart, jsonEnd + 1)
        } else {
            cleaned
        }

        return try {
            val obj = JSONObject(candidateJson)
            val action = obj.optString("action", "SPEAK").trim().uppercase().ifBlank { "SPEAK" }
            val params = obj.optJSONObject("params") ?: JSONObject()
            val cleanReply = JarvisResponseSanitizer.sanitizePlainSpeechText(
                obj.optString("reply", "")
            ).ifBlank { "Done, sir." }
            val saveMem = obj.optString("save_memory", "")
                .trim()
                .takeIf { it.length >= 4 && !it.equals("null", ignoreCase = true) }
            JarvisAiAction(
                action = action,
                params = params,
                reply = cleanReply,
                saveMemory = saveMem
            )
        } catch (_: Exception) {
            val cleanFallback = JarvisResponseSanitizer.extractCleanReply(rawText)
                .take(320)
                .ifBlank { "Command processed, sir." }
            JarvisAiAction(
                action = "SPEAK",
                params = JSONObject(),
                reply = cleanFallback,
                saveMemory = null
            )
        }
    }

    /**
     * Rich structured/long-form AI generation reusing the user's EXISTING Gemini API key and engine chain.
     * Used by AI Developer Mode, What-If Simulator, Mission Mode planning, Self-Improvement analysis,
     * YouTube SEO generation, and AI YouTube Strategy Assistant (higher token budget than short voice turns).
     */
    suspend fun generateLongAnalysis(
        rawApiKey: String,
        systemInstruction: String,
        userPrompt: String,
        selectedEngine: String = ENGINE_V25_LITE,
        maxOutputTokens: Int = 1400,
        expectJson: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = rawApiKey.trim()
        if (apiKey.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("JARVIS AI Core key missing. Configure your key once in Config.")
            )
        }
        if (PersistentLiveAudioSession.isInQuotaCooldown()) {
            return@withContext Result.failure(
                IllegalStateException("Quota protection cooldown active (${PersistentLiveAudioSession.remainingCooldownSeconds()}s).")
            )
        }

        val chain = resolveEngineChain(selectedEngine)
        var lastErr: Throwable = IllegalStateException("Unable to reach JARVIS AI Core.")

        for (spec in chain) {
            try {
                val payload = JSONObject().apply {
                    put(
                        "systemInstruction",
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                    )
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                            }
                        )
                    )
                    put(
                        "generationConfig",
                        JSONObject().apply {
                            put("temperature", if (expectJson) 0.25 else 0.55)
                            put("maxOutputTokens", maxOutputTokens.coerceIn(256, 4096))
                            if (expectJson) {
                                put("responseMimeType", "application/json")
                            }
                        }
                    )
                }

                val request = Request.Builder()
                    .url(generateUrl(spec.modelId))
                    .header("x-goog-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val text = extractCandidateText(body).trim()
                        if (text.isNotEmpty()) {
                            return@withContext Result.success(text)
                        }
                        lastErr = IllegalStateException("Empty analysis response.")
                    } else {
                        val apiErr = sanitizeErrorText(extractApiErrorMessage(body))
                        val msg = "JARVIS AI Core error ${response.code}: ${apiErr.ifBlank { response.message }}"
                        lastErr = IllegalStateException(msg)
                        if (!shouldFailoverHttpCode(response.code)) {
                            return@withContext Result.failure(lastErr)
                        }
                    }
                }
            } catch (e: Exception) {
                lastErr = e
                return@withContext Result.failure(e)
            }
        }
        Result.failure(lastErr)
    }

    /**
     * Multimodal video keyframe + metadata analysis reusing the user's EXISTING Gemini API key.
     * Accepts sampled JPEG frames (base64 encoded) plus video duration/audio/transcript metadata for
     * Pre-Upload Video Audits and AI Clipping Studio 4-moment extraction.
     */
    suspend fun generateMultimodalVideoAnalysis(
        rawApiKey: String,
        systemInstruction: String,
        promptText: String,
        jpegFramesBase64: List<String>,
        selectedEngine: String = ENGINE_V25_LITE,
        maxOutputTokens: Int = 1600,
        expectJson: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = rawApiKey.trim()
        if (apiKey.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("JARVIS AI Core key missing. Configure your key once in Config.")
            )
        }
        if (PersistentLiveAudioSession.isInQuotaCooldown()) {
            return@withContext Result.failure(
                IllegalStateException("Quota protection cooldown active (${PersistentLiveAudioSession.remainingCooldownSeconds()}s).")
            )
        }

        val partsArray = JSONArray()
        partsArray.put(JSONObject().put("text", promptText))
        jpegFramesBase64.take(8).forEach { b64 ->
            if (b64.isNotBlank()) {
                partsArray.put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject()
                            .put("mimeType", "image/jpeg")
                            .put("data", b64)
                    )
                )
            }
        }

        val chain = resolveEngineChain(selectedEngine)
        var lastErr: Throwable = IllegalStateException("Multimodal analysis failed.")

        for (spec in chain) {
            try {
                val payload = JSONObject().apply {
                    put(
                        "systemInstruction",
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                    )
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put("parts", partsArray)
                            }
                        )
                    )
                    put(
                        "generationConfig",
                        JSONObject().apply {
                            put("temperature", if (expectJson) 0.25 else 0.45)
                            put("maxOutputTokens", maxOutputTokens.coerceIn(512, 4096))
                            if (expectJson) {
                                put("responseMimeType", "application/json")
                            }
                        }
                    )
                }

                val request = Request.Builder()
                    .url(generateUrl(spec.modelId))
                    .header("x-goog-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val text = extractCandidateText(body).trim()
                        if (text.isNotEmpty()) {
                            return@withContext Result.success(text)
                        }
                        lastErr = IllegalStateException("Empty multimodal response.")
                    } else {
                        val apiErr = sanitizeErrorText(extractApiErrorMessage(body))
                        val msg = "JARVIS AI Core error ${response.code}: ${apiErr.ifBlank { response.message }}"
                        lastErr = IllegalStateException(msg)
                        if (!shouldFailoverHttpCode(response.code)) {
                            return@withContext Result.failure(lastErr)
                        }
                    }
                }
            } catch (e: Exception) {
                lastErr = e
                return@withContext Result.failure(e)
            }
        }
        Result.failure(lastErr)
    }

    private fun extractApiErrorMessage(rawError: String): String {
        return try {
            JSONObject(rawError).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private class ThinkingConfigUnsupportedException : Exception()
    private class FailoverHttpException(val code: Int, message: String) : Exception(message)
}
