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
        private const val ENDPOINT_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
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
                .url(ENDPOINT_URL)
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

    suspend fun requestAgentAction(
        rawApiKey: String,
        masterSystemPrompt: String,
        userQuery: String,
        screenContextJson: String?,
        recentHistory: List<ChatHistoryEntity>
    ): Result<JarvisAiAction> = withContext(Dispatchers.IO) {
        val apiKey = rawApiKey.trim()
        if (apiKey.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("Gemini API key is not configured. Open Settings to enter your key.")
            )
        }

        try {
            val combinedSystemPrompt = buildSystemInstruction(masterSystemPrompt)
            val contentsArray = JSONArray()

            // Attach brief conversational continuity from local Room history
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

            val turnPrompt = buildString {
                appendLine("USER COMMAND:")
                appendLine(userQuery)
                if (!screenContextJson.isNullOrBlank()) {
                    appendLine()
                    appendLine("CURRENT ANDROID SCREEN HIERARCHY (JSON):")
                    appendLine(screenContextJson)
                } else {
                    appendLine()
                    appendLine("CURRENT ANDROID SCREEN HIERARCHY: unavailable (Accessibility Service inactive or disabled).")
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
                        put("temperature", 0.25)
                        put("responseMimeType", "application/json")
                        put("maxOutputTokens", 512)
                    }
                )
            }

            val request = Request.Builder()
                .url(ENDPOINT_URL)
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val err = extractApiErrorMessage(body)
                    return@withContext Result.failure(
                        IllegalStateException("Gemini ($MODEL_NAME) error ${response.code}: ${err.ifBlank { response.message }}")
                    )
                }

                val modelText = extractCandidateText(body)
                if (modelText.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("Empty response received from $MODEL_NAME.")
                    )
                }

                Result.success(parseActionJson(modelText))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildSystemInstruction(userMasterPrompt: String): String {
        return """
${userMasterPrompt.ifBlank { DEFAULT_MASTER_PROMPT }}

CRITICAL OUTPUT PROTOCOL (STRICT JSON ONLY):
You MUST respond with a single valid JSON object matching this exact schema:
{
  "action": "TAP_TEXT" | "TAP_XY" | "LONG_PRESS" | "SCROLL" | "DRAG" | "MULTI_TOUCH" | "TYPE_TEXT" | "OPEN_APP" | "GLOBAL" | "SPEAK",
  "params": { ... },
  "reply": "Short spoken confirmation or answer for the user"
}

PARAMS SPECIFICATION BY ACTION:
- "TAP_TEXT": {"text": "visible label or button text"}
- "TAP_XY": {"x": 540, "y": 1200}
- "LONG_PRESS": {"x": 540, "y": 1200, "durationMs": 750}
- "SCROLL": {"direction": "SCROLL_UP" | "SCROLL_DOWN" | "SCROLL_LEFT" | "SCROLL_RIGHT"}
- "DRAG": {"startX": 300, "startY": 1000, "endX": 800, "endY": 600, "durationMs": 420} OR {"angleDegrees": 45, "distancePx": 400, "durationMs": 420}
- "MULTI_TOUCH": {"gesture": "PINCH_IN" | "PINCH_OUT" | "ROTATE" | "TWO_FINGER_SWIPE" | "MULTI_TAP", "direction": "UP|DOWN|LEFT|RIGHT", "degrees": 45, "spreadPx": 260, "points": [{"x": 200, "y": 900}, {"x": 880, "y": 900}]}
- "TYPE_TEXT": {"text": "text to enter", "target": "optional field label"}
- "OPEN_APP": {"appName": "WhatsApp"}
- "GLOBAL": {"command": "HOME" | "BACK" | "RECENTS" | "NOTIFICATIONS" | "QUICK_SETTINGS" | "SCREENSHOT" | "LOCK_SCREEN" | "FLASHLIGHT_ON" | "FLASHLIGHT_OFF" | "VOLUME_UP" | "VOLUME_DOWN" | "CALL", "target": "optional phone or contact"}
- "SPEAK": {} (use when answering a question, summarizing the screen, or when no UI tap is required)

Never include markdown fences or commentary outside the JSON object.
        """.trimIndent()
    }

    private fun extractCandidateText(rawResponse: String): String {
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
        return sb.toString().trim()
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
