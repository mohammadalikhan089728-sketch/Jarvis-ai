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
    val reply: String
)

data class AiAudioPayload(
    val audioBytes: ByteArray,
    val mimeType: String
)

sealed interface KeyVerificationResult {
    data class Valid(val latencyMs: Long) : KeyVerificationResult
    data class Invalid(val message: String) : KeyVerificationResult
}

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

    /**
     * Parses raw AI output that might be wrapped in markdown fences or JSON (`json { "reply": "..." }`),
     * extracts ONLY the human-readable `"reply"` value, and removes all stray brackets/symbols.
     */
    fun extractCleanReply(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        val unfenced = stripMarkdownFences(trimmed)
        val extracted = extractReplyFromPossibleJson(unfenced) ?: unfenced
        return sanitizePlainSpeechText(extracted)
    }

    /**
     * Final safety pass before UI rendering or TTS/AI Voice playback.
     */
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
            t.startsWith("json", ignoreCase = true) && (t.contains("{") || t.contains("reply"))
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

        // Fallback regex extraction if JSON was truncated or malformed
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

class GeminiApiClient {

    companion object {
        // Strictly locked internal neural engine identifiers (never exposed in UI strings)
        const val MODEL_NAME = "gemini-3.5-flash"
        const val LIVE_AUDIO_MODEL_NAME = "models/gemini-2.5-flash-native-audio-preview-12-2025"

        private const val GENERATE_ENDPOINT_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent"
        private const val STREAM_ENDPOINT_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:streamGenerateContent?alt=sse"
        private const val BIDI_LIVE_WS_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
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

    suspend fun verifyApiKey(rawApiKey: String): KeyVerificationResult = withContext(Dispatchers.IO) {
        val apiKey = rawApiKey.trim()
        if (apiKey.isEmpty()) {
            return@withContext KeyVerificationResult.Invalid("API key is empty. Enter a valid JARVIS AI Core key.")
        }

        val startTime = System.currentTimeMillis()
        try {
            val payload = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().put(JSONObject().put("text", "Reply with OK")))
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
                .url(GENERATE_ENDPOINT_URL)
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime
                val responseBody = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    KeyVerificationResult.Valid(latencyMs = latency)
                } else {
                    val apiError = sanitizeErrorText(extractApiErrorMessage(responseBody))
                    val friendly = when (response.code) {
                        400 -> "Invalid request (400): ${apiError.ifBlank { "Check your API key format." }}"
                        401, 403 -> "Authentication failed (${response.code}): API key rejected."
                        404 -> "JARVIS AI Core endpoint unavailable (404)."
                        429 -> "Rate limit reached (429): Quota temporarily exceeded."
                        else -> "Connection error (${response.code}): ${apiError.ifBlank { response.message }}"
                    }
                    KeyVerificationResult.Invalid(friendly)
                }
            }
        } catch (e: Exception) {
            KeyVerificationResult.Invalid("Network error: ${e.localizedMessage ?: "Unable to reach JARVIS AI Core."}")
        }
    }

    /**
     * Real-time Multimodal Live WebSocket audio streaming for "With AI Voice" mode.
     * Connects to BidiGenerateContent using models/gemini-2.5-flash-native-audio-preview-12-2025,
     * requests response_modalities: ["AUDIO"] with selectable deep male voice ("Charon" or "Fenrir"),
     * and streams incoming 24kHz 16-bit PCM chunks directly to [onPcmChunk] as they arrive (~400ms latency).
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

        val resolvedVoice = if (voiceName == AI_VOICE_FENRIR) AI_VOICE_FENRIR else AI_VOICE_CHARON
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

                // Handle setupComplete handshake -> send client text turn
                if (root.has("setupComplete") && promptSent.compareAndSet(false, true)) {
                    webSocket.send(buildClientTurnJson())
                    return
                }

                // Handle server error frame
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

        val request = Request.Builder()
            .url(wsUrl)
            .build()

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
                    // Multimodal Live WebSocket typically sends JSON envelopes as binary UTF-8 frames
                    handleServerJsonFrame(webSocket, bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    val total = chunkCount.get()
                    if (!completion.isCompleted) {
                        if (total > 0) {
                            completion.complete(Result.success(total))
                        } else {
                            completion.complete(Result.failure(IllegalStateException("Live audio stream closed ($code).")))
                        }
                    }
                    runCatching { webSocket.close(1000, null) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!completion.isCompleted) {
                        val total = chunkCount.get()
                        if (total > 0) {
                            completion.complete(Result.success(total))
                        } else {
                            completion.complete(
                                Result.failure(
                                    IllegalStateException(
                                        sanitizeErrorText(t.localizedMessage ?: "Live audio WebSocket unavailable.")
                                    )
                                )
                            )
                        }
                    }
                }
            }
        )
        activeVoiceWebSocket = ws

        val outcome = withTimeoutOrNull(22_000L) {
            completion.await()
        } ?: run {
            runCatching { ws.close(1000, "Timeout") }
            if (chunkCount.get() > 0) {
                Result.success(chunkCount.get())
            } else {
                Result.failure(IllegalStateException("Live AI Voice WebSocket timed out."))
            }
        }

        if (activeVoiceWebSocket === ws) {
            activeVoiceWebSocket = null
        }
        outcome
    }

    /**
     * Ultra-fast real-time SSE streaming conversational reply using gemini-3.5-flash.
     * Buffers any accidental JSON/markdown wrapper before emitting so TTS NEVER speaks "json", "{", or "reply".
     */
    suspend fun streamConversationalReply(
        rawApiKey: String,
        masterSystemPrompt: String,
        userPrompt: String,
        recentHistory: List<ChatHistoryEntity> = emptyList(),
        extraSystemGuidance: String = "",
        onSentenceReady: suspend (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = rawApiKey.trim()
        if (apiKey.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("JARVIS AI Core key is not configured. Open Config to enter your key.")
            )
        }

        try {
            val systemText = buildString {
                append(masterSystemPrompt.ifBlank { DEFAULT_MASTER_PROMPT })
                appendLine()
                appendLine("IDENTITY & VOICE OUTPUT RULES:")
                appendLine("- You are JARVIS, developed by Mozahid. Never mention any external AI brand or model name.")
                appendLine("- Respond ONLY in clean, natural spoken sentences. NEVER output JSON, brackets, markdown fences, asterisks, or bullet lists.")
                appendLine("- Keep sentences crisp and conversational so real-time voice synthesis sounds immediate.")
                if (extraSystemGuidance.isNotBlank()) {
                    appendLine(extraSystemGuidance)
                }
            }

            val contentsArray = JSONArray()
            for (entry in recentHistory.takeLast(4)) {
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
                                    JarvisResponseSanitizer.extractCleanReply(entry.jarvisReply)
                                )
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

            val payload = JSONObject().apply {
                put(
                    "systemInstruction",
                    JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", systemText)))
                    }
                )
                put("contents", contentsArray)
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("temperature", 0.45)
                        put("maxOutputTokens", 512)
                    }
                )
            }

            val request = Request.Builder()
                .url(STREAM_ENDPOINT_URL)
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
                    val err = sanitizeErrorText(extractApiErrorMessage(body))
                    return@withContext Result.failure(
                        IllegalStateException("JARVIS AI Core error ${response.code}: ${err.ifBlank { response.message }}")
                    )
                }

                val source = response.body?.source()
                    ?: return@withContext Result.failure(IllegalStateException("Empty response stream from JARVIS AI Core."))

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val jsonPayload = line.removePrefix("data:").trim()
                    if (jsonPayload.isEmpty() || jsonPayload == "[DONE]") continue

                    val chunkText = extractCandidateText(jsonPayload)
                    if (chunkText.isEmpty()) continue

                    fullText.append(chunkText)
                    sentenceBuffer.append(chunkText)

                    // Guard: if the model unexpectedly begins outputting ```json or { ... }, hold until complete to extract ONLY "reply"
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
                    return@withContext Result.success(cleanReply)
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
     * Autonomous multi-step phone automation decision call on gemini-3.5-flash.
     * Strictly parses JSON and sanitizes the "reply" field so brackets or JSON keys never reach TTS/UI.
     */
    suspend fun requestAgentAction(
        rawApiKey: String,
        masterSystemPrompt: String,
        userQuery: String,
        screenContextJson: String?,
        recentHistory: List<ChatHistoryEntity>,
        stepHistory: List<String> = emptyList(),
        stepIndex: Int = 1,
        maxSteps: Int = 8
    ): Result<JarvisAiAction> = withContext(Dispatchers.IO) {
        val apiKey = rawApiKey.trim()
        if (apiKey.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("JARVIS AI Core key is not configured. Open Config to enter your key.")
            )
        }

        try {
            val combinedSystemPrompt = buildSystemInstruction(masterSystemPrompt)
            val contentsArray = JSONArray()

            if (stepIndex == 1) {
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
                appendLine("USER GOAL:")
                appendLine(userQuery)
                appendLine("STEP: $stepIndex of $maxSteps")
                if (stepHistory.isNotEmpty()) {
                    appendLine("COMPLETED STEPS SO FAR:")
                    stepHistory.forEachIndexed { idx, s -> appendLine("${idx + 1}. $s") }
                }
                if (!screenContextJson.isNullOrBlank()) {
                    appendLine("CURRENT SCREEN UI JSON:")
                    appendLine(screenContextJson)
                } else {
                    appendLine("CURRENT SCREEN UI JSON: unavailable (Accessibility Service inactive).")
                }
            }

            contentsArray.put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", turnPrompt)))
                }
            )

            val payload = JSONObject().apply {
                put(
                    "systemInstruction",
                    JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", combinedSystemPrompt)))
                    }
                )
                put("contents", contentsArray)
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("temperature", 0.18)
                        put("responseMimeType", "application/json")
                        put("maxOutputTokens", 384)
                    }
                )
            }

            val rawModelText = executeStreamingCollect(apiKey, payload).getOrElse { err ->
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
        recentConversation: List<AutoChatLogsEntity>
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
                appendLine("- You are replying on the user's behalf in $appLabel to \"$senderName\".")
                appendLine("- Sound completely natural, warm, concise, and human-like (1-2 short casual sentences max).")
                appendLine("- Match the sender's language (Hindi, Hinglish, or English) automatically.")
                appendLine("- Output ONLY the exact message text to send. Never output JSON or mention any AI brand.")
            }

            val convoHistory = buildString {
                if (recentConversation.isNotEmpty()) {
                    appendLine("RECENT CHAT CONTEXT WITH $senderName:")
                    recentConversation.takeLast(8).forEach { log ->
                        val who = if (log.direction == AUTO_CHAT_DIR_INCOMING) senderName else "Me"
                        appendLine("$who: ${log.messageText}")
                    }
                    appendLine()
                }
                appendLine("LATEST MESSAGE FROM $senderName:")
                appendLine(incomingMessage)
            }

            val payload = JSONObject().apply {
                put(
                    "systemInstruction",
                    JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", systemText)))
                    }
                )
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().put(JSONObject().put("text", convoHistory)))
                        }
                    )
                )
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("temperature", 0.55)
                        put("maxOutputTokens", 140)
                    }
                )
            }

            executeStreamingCollect(apiKey, payload).map { text ->
                JarvisResponseSanitizer.extractCleanReply(text).take(400)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun executeStreamingCollect(apiKey: String, payload: JSONObject): Result<String> {
        val request = Request.Builder()
            .url(STREAM_ENDPOINT_URL)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val err = sanitizeErrorText(extractApiErrorMessage(body))
                return Result.failure(
                    IllegalStateException("JARVIS AI Core error ${response.code}: ${err.ifBlank { response.message }}")
                )
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

    private fun buildSystemInstruction(userMasterPrompt: String): String {
        return """
${userMasterPrompt.ifBlank { DEFAULT_MASTER_PROMPT }}

CRITICAL AUTONOMOUS MULTI-STEP AGENT PROTOCOL (STRICT JSON ONLY):
You are JARVIS, developed by Mozahid. Never mention any external AI brand.
You MUST respond with a single valid JSON object matching this exact schema:
{
  "action": "OPEN_APP" | "TAP_TEXT" | "TAP_XY" | "LONG_PRESS" | "TYPE_TEXT" | "SWIPE" | "SCROLL_DOWN" | "SCROLL_UP" | "SCROLL" | "DRAG" | "MULTI_TOUCH" | "BACK" | "HOME" | "RECENTS" | "GLOBAL" | "DONE" | "SPEAK",
  "params": { ... },
  "reply": "Short clean spoken sentence for the user with no markdown or symbols"
}

MULTI-STEP RULES:
- Execute ONE immediate next action toward the USER GOAL based on CURRENT SCREEN UI JSON (each node has id, text, desc, x, y, clickable, editable, scrollable).
- For multi-step goals (e.g., open YouTube -> tap search -> type song with submit:true -> tap first video; or open WhatsApp -> tap contact -> type message -> tap Send; or open Settings -> tap Network/Hotspot -> toggle on), execute the next logical step now. You will automatically receive the updated screen after 900ms!
- As soon as the user's goal is completely achieved, return "action": "DONE" with a crisp final spoken confirmation in "reply".
- If no screen action is needed, return "action": "SPEAK".

PARAMS SPECIFICATION BY ACTION:
- "OPEN_APP": {"appName": "YouTube", "packageName": "optional.package.name"}
- "TAP_TEXT": {"text": "visible label or button text"}
- "TAP_XY": {"x": 540, "y": 1200}
- "LONG_PRESS": {"x": 540, "y": 1200, "durationMs": 750}
- "TYPE_TEXT": {"text": "text to enter", "target": "optional field label", "submit": true}
- "SWIPE": {"x1": 540, "y1": 1600, "x2": 540, "y2": 500, "durationMs": 320}
- "SCROLL_DOWN": {}
- "SCROLL_UP": {}
- "SCROLL": {"direction": "SCROLL_UP" | "SCROLL_DOWN" | "SCROLL_LEFT" | "SCROLL_RIGHT"}
- "DRAG": {"startX": 300, "startY": 1000, "endX": 800, "endY": 600, "durationMs": 420} OR {"angleDegrees": 45, "distancePx": 400, "durationMs": 420}
- "MULTI_TOUCH": {"gesture": "PINCH_IN" | "PINCH_OUT" | "ROTATE" | "TWO_FINGER_SWIPE" | "MULTI_TAP", "direction": "UP|DOWN|LEFT|RIGHT", "degrees": 45, "spreadPx": 260, "points": [{"x": 200, "y": 900}, {"x": 880, "y": 900}]}
- "BACK": {}
- "HOME": {}
- "RECENTS": {}
- "GLOBAL": {"command": "HOME" | "BACK" | "RECENTS" | "NOTIFICATIONS" | "QUICK_SETTINGS" | "SCREENSHOT" | "LOCK_SCREEN" | "FLASHLIGHT_ON" | "FLASHLIGHT_OFF" | "VOLUME_UP" | "VOLUME_DOWN" | "WIFI" | "BLUETOOTH" | "DND" | "CLIPBOARD_READ" | "CLIPBOARD_COPY" | "CALL", "target": "optional text or phone/contact"}
- "DONE": {}
- "SPEAK": {}

Never include markdown fences or commentary outside the JSON object.
        """.trimIndent()
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
            JarvisAiAction(action = action, params = params, reply = cleanReply)
        } catch (_: Exception) {
            val cleanFallback = JarvisResponseSanitizer.extractCleanReply(rawText)
                .take(360)
                .ifBlank { "Command processed, sir." }
            JarvisAiAction(
                action = "SPEAK",
                params = JSONObject(),
                reply = cleanFallback
            )
        }
    }

    private fun extractApiErrorMessage(rawError: String): String {
        return try {
            JSONObject(rawError).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
}
