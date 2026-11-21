package com.example

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

sealed interface KeyVerificationResult {
    data class Valid(val latencyMs: Long, val model: String = GeminiApiClient.MODEL_NAME) : KeyVerificationResult
    data class Invalid(val message: String) : KeyVerificationResult
}

class GeminiApiClient {

    companion object {
        const val MODEL_NAME = "gemini-3.5-flash"
        private const val GENERATE_ENDPOINT_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent"
        private const val STREAM_ENDPOINT_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:streamGenerateContent?alt=sse"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun verifyApiKey(rawApiKey: String): KeyVerificationResult = withContext(Dispatchers.IO) {
        val apiKey = rawApiKey.trim()
        if (apiKey.isEmpty()) {
            return@withContext KeyVerificationResult.Invalid("API key is empty. Paste a valid Gemini key.")
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
                    val apiError = extractApiErrorMessage(responseBody)
                    val friendly = when (response.code) {
                        400 -> "Bad request (400): ${apiError.ifBlank { "Check API key format." }}"
                        401, 403 -> "Unauthorized (${response.code}): API key rejected or lacks access to $MODEL_NAME."
                        404 -> "Model $MODEL_NAME endpoint not found (404): $apiError"
                        429 -> "Rate limit reached (429): Quota exceeded for $MODEL_NAME."
                        else -> "HTTP ${response.code}: ${apiError.ifBlank { response.message }}"
                    }
                    KeyVerificationResult.Invalid(friendly)
                }
            }
        } catch (e: Exception) {
            KeyVerificationResult.Invalid("Network error: ${e.localizedMessage ?: "Unable to reach Gemini API."}")
        }
    }

    /**
     * Ultra-fast real-time SSE streaming conversational reply powered strictly by gemini-3.5-flash.
     * Flushes completed sentences immediately on '.', '?', '!', '।', or newline so TTS starts in ~400ms.
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
                IllegalStateException("Gemini API key is not configured. Open Config to enter your key.")
            )
        }

        try {
            val systemText = buildString {
                append(masterSystemPrompt.ifBlank { DEFAULT_MASTER_PROMPT })
                appendLine()
                appendLine("STREAMING VOICE OUTPUT RULES:")
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
                    val err = extractApiErrorMessage(body)
                    return@withContext Result.failure(
                        IllegalStateException("Gemini ($MODEL_NAME) error ${response.code}: ${err.ifBlank { response.message }}")
                    )
                }

                val source = response.body?.source()
                    ?: return@withContext Result.failure(IllegalStateException("Empty stream body from $MODEL_NAME."))

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val jsonPayload = line.removePrefix("data:").trim()
                    if (jsonPayload.isEmpty() || jsonPayload == "[DONE]") continue

                    val chunkText = extractCandidateText(jsonPayload)
                    if (chunkText.isEmpty()) continue

                    fullText.append(chunkText)
                    sentenceBuffer.append(chunkText)

                    // Flush any completed sentences immediately for ~400ms TTS start
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
                Result.failure(IllegalStateException("Empty streamed response from $MODEL_NAME."))
            } else {
                Result.success(combined)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Autonomous multi-step phone agent decision call using gemini-3.5-flash:streamGenerateContent.
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
                IllegalStateException("Gemini API key is not configured. Open Config to enter your key.")
            )
        }

        try {
            val combinedSystemPrompt = buildSystemInstruction(masterSystemPrompt)
            val contentsArray = JSONArray()

            // Attach brief conversational continuity only on step 1
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
                    appendLine("COMPACT SCREEN UI JSON:")
                    appendLine(screenContextJson)
                } else {
                    appendLine("SCREEN UI JSON: unavailable (Accessibility Service inactive).")
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
                        put("temperature", 0.2)
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
                    IllegalStateException("Empty response received from $MODEL_NAME.")
                )
            }
            Result.success(parseActionJson(rawModelText))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Generates a human-like background auto-reply for WhatsApp/Telegram/Instagram/SMS notifications
     * strictly powered by gemini-3.5-flash:streamGenerateContent.
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
            return@withContext Result.failure(IllegalStateException("Gemini API key missing."))
        }

        try {
            val systemText = buildString {
                append(masterSystemPrompt.ifBlank { DEFAULT_MASTER_PROMPT })
                appendLine()
                appendLine("AUTONOMOUS CHAT AUTO-REPLY MODE:")
                appendLine("- You are replying on the user's behalf in $appLabel to \"$senderName\".")
                appendLine("- Sound completely natural, warm, concise, and human-like (1-2 short casual sentences max).")
                appendLine("- Match the sender's language (Hindi, Hinglish, or English) automatically.")
                appendLine("- Output ONLY the exact message text to send. Do NOT add quotes, prefixes, or explanations.")
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
                val err = extractApiErrorMessage(body)
                return Result.failure(
                    IllegalStateException("Gemini ($MODEL_NAME) error ${response.code}: ${err.ifBlank { response.message }}")
                )
            }

            val source = response.body?.source()
                ?: return Result.failure(IllegalStateException("Empty SSE stream from $MODEL_NAME."))
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
                // Avoid splitting abbreviations like "J.A.R.V.I.S." or decimals like "3.5"
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

    private fun buildSystemInstruction(userMasterPrompt: String): String {
        return """
${userMasterPrompt.ifBlank { DEFAULT_MASTER_PROMPT }}

CRITICAL AUTONOMOUS MULTI-STEP AGENT PROTOCOL (STRICT JSON ONLY):
You MUST respond with a single valid JSON object matching this exact schema:
{
  "action": "OPEN_APP" | "TAP_TEXT" | "TAP_XY" | "LONG_PRESS" | "TYPE_TEXT" | "SCROLL" | "DRAG" | "MULTI_TOUCH" | "GLOBAL" | "DONE" | "SPEAK",
  "params": { ... },
  "reply": "Short spoken confirmation or status for the user"
}

MULTI-STEP RULES:
- Execute ONE immediate next action toward the USER GOAL based on the current COMPACT SCREEN UI JSON (keys: p=package, w=width, h=height, n=nodes with t=text, d=desc, x/y=center coords, c=clickable, e=editable, s=scrollable).
- For multi-step workflows (e.g., open app -> tap search -> type query -> tap result), do step 1 now; you will automatically be called again with the updated screen after 900ms!
- As soon as the user's goal is completely achieved, return "action": "DONE" with a crisp final spoken confirmation in "reply".
- If the request is purely a question or requires no screen action, return "action": "SPEAK" with the full spoken answer in "reply".

PARAMS SPECIFICATION BY ACTION:
- "OPEN_APP": {"appName": "YouTube"}
- "TAP_TEXT": {"text": "visible label or button text"}
- "TAP_XY": {"x": 540, "y": 1200}
- "LONG_PRESS": {"x": 540, "y": 1200, "durationMs": 750}
- "TYPE_TEXT": {"text": "text to enter", "target": "optional field label", "submit": true}
- "SCROLL": {"direction": "SCROLL_UP" | "SCROLL_DOWN" | "SCROLL_LEFT" | "SCROLL_RIGHT"}
- "DRAG": {"startX": 300, "startY": 1000, "endX": 800, "endY": 600, "durationMs": 420} OR {"angleDegrees": 45, "distancePx": 400, "durationMs": 420}
- "MULTI_TOUCH": {"gesture": "PINCH_IN" | "PINCH_OUT" | "ROTATE" | "TWO_FINGER_SWIPE" | "MULTI_TAP", "direction": "UP|DOWN|LEFT|RIGHT", "degrees": 45, "spreadPx": 260, "points": [{"x": 200, "y": 900}, {"x": 880, "y": 900}]}
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
