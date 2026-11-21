package com.example

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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

class GeminiApiClient {

    companion object {
        // Strictly locked internal neural engine identifier (never exposed in UI strings)
        const val MODEL_NAME = "gemini-3.5-flash"
        private const val GENERATE_ENDPOINT_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent"
        private const val STREAM_ENDPOINT_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:streamGenerateContent?alt=sse"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

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
     * Generates native human-like AI audio bytes (PCM / WAV) via responseModalities: ["AUDIO"]
     * with selectable male voice ("Charon" or "Fenrir") for "With AI Voice" mode.
     */
    suspend fun requestAiVoiceAudio(
        rawApiKey: String,
        textToSpeak: String,
        voiceName: String = AI_VOICE_CHARON
    ): Result<AiAudioPayload> = withContext(Dispatchers.IO) {
        val apiKey = rawApiKey.trim()
        val cleanText = textToSpeak.trim()
        if (apiKey.isEmpty() || cleanText.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("AI Voice requires an active API key and text."))
        }

        val resolvedVoice = if (voiceName == AI_VOICE_FENRIR) AI_VOICE_FENRIR else AI_VOICE_CHARON
        try {
            val payload = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().put(
                                        "text",
                                        "Read the following JARVIS response aloud naturally, clearly, and authoritatively without adding extra words:\n$cleanText"
                                    )
                                )
                            )
                        }
                    )
                )
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
            }

            val request = Request.Builder()
                .url(GENERATE_ENDPOINT_URL)
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("AI Voice stream unavailable (${response.code}).")
                    )
                }

                val root = JSONObject(body)
                val candidates = root.optJSONArray("candidates") ?: return@withContext Result.failure(
                    IllegalStateException("No audio candidate returned.")
                )
                for (c in 0 until candidates.length()) {
                    val parts = candidates.optJSONObject(c)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts") ?: continue
                    for (p in 0 until parts.length()) {
                        val inlineData = parts.optJSONObject(p)?.optJSONObject("inlineData") ?: continue
                        val b64 = inlineData.optString("data", "")
                        val mime = inlineData.optString("mimeType", "audio/L16;codec=pcm;rate=24000")
                        if (b64.isNotBlank()) {
                            val decoded = Base64.decode(b64, Base64.DEFAULT)
                            if (decoded.isNotEmpty()) {
                                return@withContext Result.success(
                                    AiAudioPayload(audioBytes = decoded, mimeType = mime)
                                )
                            }
                        }
                    }
                }
                Result.failure(IllegalStateException("No inline audio payload present in AI Voice response."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Ultra-fast real-time SSE streaming conversational reply.
     * Flushes completed sentences immediately on '.', '?', '!', '।', or newline so Normal Voice starts in ~400ms.
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
                appendLine("- Respond in clean, natural spoken sentences (no markdown bullets, no asterisks, no JSON).")
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
                        put("parts", JSONArray().put(JSONObject().put("text", entry.jarvisReply)))
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

                    while (true) {
                        val cutIndex = findSentenceBoundary(sentenceBuffer)
                        if (cutIndex < 0) break
                        val sentence = sanitizeSpokenSentence(sentenceBuffer.substring(0, cutIndex + 1))
                        sentenceBuffer.delete(0, cutIndex + 1)
                        if (sentence.isNotEmpty()) {
                            onSentenceReady(sentence)
                        }
                    }
                }
            }

            val trailing = sanitizeSpokenSentence(sentenceBuffer.toString())
            if (trailing.isNotEmpty()) {
                onSentenceReady(trailing)
            }

            val combined = sanitizeSpokenSentence(fullText.toString())
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
     * Autonomous multi-step phone automation decision call.
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
                                            put("reply", entry.jarvisReply)
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
                appendLine("- Output ONLY the exact message text to send. Never mention any AI brand.")
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
                text.trim().trim('"', '\'').take(400)
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

    private fun sanitizeSpokenSentence(raw: String): String {
        return raw
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .replace(Regex("\\s+"), " ")
            .trim()
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
  "reply": "Short spoken confirmation or answer for the user"
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
        val cleaned = rawText
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

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
            val reply = obj.optString("reply", "").trim().ifBlank { "Done, sir." }
            JarvisAiAction(action = action, params = params, reply = reply)
        } catch (_: Exception) {
            JarvisAiAction(
                action = "SPEAK",
                params = JSONObject(),
                reply = cleaned.take(360).ifBlank { "Command processed." }
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
