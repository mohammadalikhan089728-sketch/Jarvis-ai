package com.example

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
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

data class LiveAudioTurnResult(
    val spokenTranscript: String,
    val pcmChunksPlayed: Int
)

/**
 * Zero-Limit Single Persistent Live Audio Session for J.A.R.V.I.S. (Developed by Mozahid).
 *
 * - Maintains ONE long-lived Multimodal Live WebSocket connection (`models/gemini-2.5-flash-native-audio-preview-12-2025`)
 *   configured with deep male voice (`Charon` or `Orus`) for realistic, instant Hindi/Hinglish speech.
 * - Eliminates per-utterance REST generateContent loops during normal conversation.
 * - Enforces a strict rate-limit / error cooldown circuit breaker (NEVER rapidly retries on 429/404 errors).
 * - Streams 24kHz 16-bit mono PCM chunks directly into Android AudioTrack and captures output transcription
 *   so J.A.R.V.I.S. replies both audibly and cleanly in the HUD in one single turn.
 */
object PersistentLiveAudioSession {

    private const val LIVE_MODEL_ID = "models/gemini-2.5-flash-native-audio-preview-12-2025"
    private const val BIDI_LIVE_WS_BASE_URL =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    private const val QUOTA_COOLDOWN_MS = 60_000L
    private const val SETUP_TIMEOUT_MS = 10_000L
    private const val TURN_TIMEOUT_MS = 22_000L

    private val wsClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Required for long-lived persistent WebSockets
        .writeTimeout(12, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val turnMutex = Mutex()
    private val trackLock = Any()

    @Volatile
    private var activeSocket: WebSocket? = null
    @Volatile
    private var connectedKeyHash: Int = 0
    @Volatile
    private var connectedVoiceName: String = ""
    @Volatile
    private var isSetupReady: Boolean = false
    @Volatile
    private var quotaCooldownUntilElapsedMs: Long = 0L

    @Volatile
    private var setupDeferred: CompletableDeferred<Boolean>? = null
    @Volatile
    private var activeTurnDeferred: CompletableDeferred<Result<LiveAudioTurnResult>>? = null
    private val activeTurnTranscript = StringBuilder()
    private val activeTurnChunkCount = AtomicInteger(0)
    private val activeTurnInterrupted = AtomicBoolean(false)

    private var audioTrack: AudioTrack? = null
    private var audioTrackSampleRate: Int = 0

    private val _isSessionConnected = MutableStateFlow(false)
    val isSessionConnected: StateFlow<Boolean> = _isSessionConnected.asStateFlow()

    private val _sessionStatus = MutableStateFlow("Live Link Standby")
    val sessionStatus: StateFlow<String> = _sessionStatus.asStateFlow()

    fun isInQuotaCooldown(): Boolean {
        return SystemClock.elapsedRealtime() < quotaCooldownUntilElapsedMs
    }

    fun remainingCooldownSeconds(): Long {
        val rem = quotaCooldownUntilElapsedMs - SystemClock.elapsedRealtime()
        return if (rem > 0L) (rem / 1000L) + 1L else 0L
    }

    fun triggerQuotaCooldown(durationMs: Long = QUOTA_COOLDOWN_MS) {
        quotaCooldownUntilElapsedMs = SystemClock.elapsedRealtime() + durationMs
        _sessionStatus.value = "Quota protection cooldown active"
    }

    /**
     * Ensures ONE persistent Live WebSocket connection is open and configured with the user's API key,
     * deep male voice (`Charon` / `Orus`), and strict J.A.R.V.I.S. Hinglish identity.
     * Reuses the existing open connection across all subsequent turns without reconnecting!
     */
    suspend fun ensureConnected(
        rawApiKey: String,
        voiceName: String = AI_VOICE_CHARON,
        masterSystemPrompt: String = DEFAULT_MASTER_PROMPT,
        memoryContext: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        val apiKey = rawApiKey.trim()
        if (apiKey.isEmpty() || isInQuotaCooldown()) {
            return@withContext false
        }

        val resolvedVoice = resolveVoiceName(voiceName)
        val keyHash = apiKey.hashCode()

        // Reuse existing live WebSocket session if already connected with same key & voice!
        if (activeSocket != null &&
            isSetupReady &&
            connectedKeyHash == keyHash &&
            connectedVoiceName == resolvedVoice
        ) {
            return@withContext true
        }

        closeSessionInternal("Reconfiguring live session")

        val setupSignal = CompletableDeferred<Boolean>()
        setupDeferred = setupSignal

        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val wsUrl = "$BIDI_LIVE_WS_BASE_URL?key=$encodedKey"
        val setupPayload = buildSetupFrame(resolvedVoice, masterSystemPrompt, memoryContext)

        val request = Request.Builder().url(wsUrl).build()
        val socket = wsClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(setupPayload)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingFrame(webSocket, text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleIncomingFrame(webSocket, bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (code == 1008 || code == 1011 || reason.contains("429") || reason.contains("quota", ignoreCase = true)) {
                        triggerQuotaCooldown()
                    }
                    handleSocketClosed(webSocket, "Live stream closed ($code)")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    handleSocketClosed(webSocket, "Live stream disconnected ($code)")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code ?: 0
                    if (code == 429 || code == 404 || code == 403 || code == 401) {
                        triggerQuotaCooldown()
                    }
                    val reason = JarvisResponseSanitizer.sanitizePlainSpeechText(
                        t.localizedMessage ?: "Live audio link interrupted"
                    )
                    handleSocketClosed(webSocket, reason)
                }
            }
        )

        activeSocket = socket
        connectedKeyHash = keyHash
        connectedVoiceName = resolvedVoice

        val ready = withTimeoutOrNull(SETUP_TIMEOUT_MS) { setupSignal.await() } ?: false
        if (!ready) {
            closeSessionInternal("Setup timeout")
            return@withContext false
        }
        true
    }

    /**
     * Executes a single conversational turn directly over the persistent Live WebSocket!
     * Sends pure lightweight text (NO screen JSON!) and streams 24kHz PCM audio directly into AudioTrack.
     */
    suspend fun sendConversationalTurn(
        rawApiKey: String,
        userText: String,
        voiceName: String = AI_VOICE_CHARON,
        masterSystemPrompt: String = DEFAULT_MASTER_PROMPT,
        memoryContext: String = ""
    ): Result<LiveAudioTurnResult> = turnMutex.withLock {
        withContext(Dispatchers.IO) {
            if (isInQuotaCooldown()) {
                return@withContext Result.failure(
                    IllegalStateException("Rate-limit cooldown active (${remainingCooldownSeconds()}s).")
                )
            }
            val connected = ensureConnected(rawApiKey, voiceName, masterSystemPrompt, memoryContext)
            val ws = activeSocket
            if (!connected || ws == null || !isSetupReady) {
                return@withContext Result.failure(
                    IllegalStateException("Persistent Live Audio link unavailable.")
                )
            }

            val cleanInput = userText.trim()
            if (cleanInput.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Empty turn"))
            }

            val turnSignal = CompletableDeferred<Result<LiveAudioTurnResult>>()
            activeTurnTranscript.clear()
            activeTurnChunkCount.set(0)
            activeTurnInterrupted.set(false)
            activeTurnDeferred = turnSignal

            MicCoordinator.setJarvisSpeaking(true)
            _sessionStatus.value = "Live Audio Active ($connectedVoiceName)"

            val promptText = if (memoryContext.isNotBlank()) {
                "[USER MEMORY]\n$memoryContext\n\n[USER]: $cleanInput"
            } else {
                cleanInput
            }

            val sent = runCatching { ws.send(buildClientContentTurn(promptText)) }.getOrDefault(false)
            if (!sent) {
                MicCoordinator.setJarvisSpeaking(false)
                closeSessionInternal("Send failed")
                return@withContext Result.failure(IllegalStateException("Failed to send live turn"))
            }

            val outcome = withTimeoutOrNull(TURN_TIMEOUT_MS) {
                turnSignal.await()
            } ?: run {
                val chunks = activeTurnChunkCount.get()
                val text = JarvisResponseSanitizer.extractCleanReply(activeTurnTranscript.toString())
                if (chunks > 0) {
                    Result.success(LiveAudioTurnResult(text, chunks))
                } else {
                    closeSessionInternal("Turn timeout")
                    Result.failure(IllegalStateException("Live Audio turn timed out."))
                }
            }

            drainAndPauseAudioTrack()
            activeTurnDeferred = null
            MicCoordinator.setJarvisSpeaking(false)
            outcome
        }
    }

    /**
     * Speaks an exact confirmation sentence aloud over the SAME persistent WebSocket (zero extra connections).
     */
    suspend fun speakVerbatim(
        rawApiKey: String,
        textToSpeak: String,
        voiceName: String = AI_VOICE_CHARON
    ): Result<Int> = turnMutex.withLock {
        withContext(Dispatchers.IO) {
            val clean = JarvisResponseSanitizer.extractCleanReply(textToSpeak)
            if (clean.isEmpty() || isInQuotaCooldown()) {
                return@withContext Result.failure(IllegalStateException("Skipped verbatim live audio"))
            }
            val connected = ensureConnected(rawApiKey, voiceName)
            val ws = activeSocket
            if (!connected || ws == null || !isSetupReady) {
                return@withContext Result.failure(IllegalStateException("Persistent Live Audio link unavailable"))
            }

            val turnSignal = CompletableDeferred<Result<LiveAudioTurnResult>>()
            activeTurnTranscript.clear()
            activeTurnChunkCount.set(0)
            activeTurnInterrupted.set(false)
            activeTurnDeferred = turnSignal

            MicCoordinator.setJarvisSpeaking(true)
            val sent = runCatching {
                ws.send(
                    buildClientContentTurn(
                        "Read this exact sentence aloud naturally in your deep male Hinglish voice without adding extra words: $clean"
                    )
                )
            }.getOrDefault(false)

            if (!sent) {
                MicCoordinator.setJarvisSpeaking(false)
                closeSessionInternal("Verbatim send failed")
                return@withContext Result.failure(IllegalStateException("Live send failed"))
            }

            val res = withTimeoutOrNull(18_000L) {
                turnSignal.await()
            } ?: Result.failure(IllegalStateException("Live audio playback timeout"))

            drainAndPauseAudioTrack()
            activeTurnDeferred = null
            MicCoordinator.setJarvisSpeaking(false)
            res.map { it.pcmChunksPlayed }
        }
    }

    fun interruptCurrentPlayback() {
        activeTurnInterrupted.set(true)
        synchronized(trackLock) {
            audioTrack?.let { track ->
                runCatching {
                    track.pause()
                    track.flush()
                }
            }
        }
        activeTurnDeferred?.complete(
            Result.success(
                LiveAudioTurnResult(
                    spokenTranscript = JarvisResponseSanitizer.extractCleanReply(activeTurnTranscript.toString()),
                    pcmChunksPlayed = activeTurnChunkCount.get()
                )
            )
        )
        activeTurnDeferred = null
        MicCoordinator.setJarvisSpeaking(false)
    }

    fun closeSession(reason: String = "Session ended") {
        closeSessionInternal(reason)
    }

    private fun closeSessionInternal(reason: String) {
        val ws = activeSocket
        activeSocket = null
        isSetupReady = false
        _isSessionConnected.value = false
        _sessionStatus.value = "Live Link Standby"
        setupDeferred?.complete(false)
        setupDeferred = null
        activeTurnDeferred?.let { deferred ->
            if (!deferred.isCompleted) {
                deferred.complete(Result.failure(IllegalStateException(reason)))
            }
        }
        activeTurnDeferred = null
        runCatching { ws?.close(1000, reason.take(64)) }
        releaseAudioTrack()
        MicCoordinator.setJarvisSpeaking(false)
    }

    private fun handleSocketClosed(ws: WebSocket, reason: String) {
        if (activeSocket === ws) {
            activeSocket = null
            isSetupReady = false
            _isSessionConnected.value = false
            _sessionStatus.value = reason
        }
        setupDeferred?.complete(false)
        setupDeferred = null
        val turn = activeTurnDeferred
        if (turn != null && !turn.isCompleted) {
            val chunks = activeTurnChunkCount.get()
            if (chunks > 0) {
                turn.complete(
                    Result.success(
                        LiveAudioTurnResult(
                            spokenTranscript = JarvisResponseSanitizer.extractCleanReply(activeTurnTranscript.toString()),
                            pcmChunksPlayed = chunks
                        )
                    )
                )
            } else {
                turn.complete(Result.failure(IllegalStateException(reason)))
            }
        }
        activeTurnDeferred = null
        MicCoordinator.setJarvisSpeaking(false)
    }

    private fun handleIncomingFrame(ws: WebSocket, rawJson: String) {
        try {
            val root = JSONObject(rawJson)

            if (root.has("setupComplete")) {
                isSetupReady = true
                _isSessionConnected.value = true
                _sessionStatus.value = "Single Live Link Connected ($connectedVoiceName)"
                setupDeferred?.complete(true)
                setupDeferred = null
                return
            }

            if (root.has("error")) {
                val errObj = root.optJSONObject("error")
                val errCode = errObj?.optInt("code", 0) ?: 0
                val errMsg = JarvisResponseSanitizer.sanitizePlainSpeechText(
                    errObj?.optString("message").orEmpty()
                ).ifBlank { "Live audio core error" }
                if (errCode == 429 || errCode == 404 || errCode == 403) {
                    triggerQuotaCooldown()
                }
                setupDeferred?.complete(false)
                activeTurnDeferred?.complete(Result.failure(IllegalStateException(errMsg)))
                if (errCode == 429 || errCode == 404 || errCode == 403) {
                    closeSessionInternal(errMsg)
                }
                return
            }

            val serverContent = root.optJSONObject("serverContent") ?: return

            // Capture server-side output transcription when available
            val outTranscript = serverContent.optJSONObject("outputTranscription")?.optString("text").orEmpty()
            if (outTranscript.isNotBlank()) {
                activeTurnTranscript.append(outTranscript)
            }

            val modelTurn = serverContent.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val partText = part.optString("text", "")
                    if (partText.isNotBlank()) {
                        if (activeTurnTranscript.isNotEmpty()) activeTurnTranscript.append(' ')
                        activeTurnTranscript.append(partText)
                    }
                    val inlineData = part.optJSONObject("inlineData") ?: continue
                    if (activeTurnInterrupted.get()) continue
                    val b64 = inlineData.optString("data", "")
                    if (b64.isBlank()) continue
                    val mime = inlineData.optString("mimeType", "audio/pcm;rate=24000")
                    val sampleRate = parsePcmSampleRate(mime)
                    val pcmBytes = Base64.decode(b64, Base64.DEFAULT)
                    if (pcmBytes.isNotEmpty()) {
                        writePcmChunkToTrack(pcmBytes, sampleRate)
                        activeTurnChunkCount.incrementAndGet()
                    }
                }
            }

            if (serverContent.optBoolean("turnComplete", false)) {
                val chunks = activeTurnChunkCount.get()
                val cleanReply = JarvisResponseSanitizer.extractCleanReply(activeTurnTranscript.toString())
                activeTurnDeferred?.let { deferred ->
                    if (!deferred.isCompleted) {
                        if (chunks > 0 || cleanReply.isNotEmpty()) {
                            deferred.complete(Result.success(LiveAudioTurnResult(cleanReply, chunks)))
                        } else {
                            deferred.complete(Result.failure(IllegalStateException("Live turn ended without output")))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore non-JSON keepalive frames
        }
    }

    private fun writePcmChunkToTrack(pcmBytes: ByteArray, sampleRateHz: Int) {
        synchronized(trackLock) {
            val track = ensureStreamingAudioTrack(sampleRateHz) ?: return
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                runCatching { track.play() }
            }
            var offset = 0
            while (offset < pcmBytes.size && !activeTurnInterrupted.get()) {
                val toWrite = minOf(4096, pcmBytes.size - offset)
                val written = runCatching {
                    track.write(pcmBytes, offset, toWrite, AudioTrack.WRITE_BLOCKING)
                }.getOrDefault(-1)
                if (written <= 0) break
                offset += written
            }
        }
    }

    private fun ensureStreamingAudioTrack(sampleRateHz: Int): AudioTrack? {
        val existing = audioTrack
        if (existing != null && audioTrackSampleRate == sampleRateHz && existing.state == AudioTrack.STATE_INITIALIZED) {
            return existing
        }
        releaseAudioTrack()
        return runCatching {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(8192)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            audioTrack = track
            audioTrackSampleRate = sampleRateHz
            track
        }.getOrNull()
    }

    private fun drainAndPauseAudioTrack() {
        synchronized(trackLock) {
            audioTrack?.let { track ->
                runCatching {
                    track.stop()
                }
            }
        }
    }

    private fun releaseAudioTrack() {
        synchronized(trackLock) {
            audioTrack?.let { track ->
                runCatching {
                    track.pause()
                    track.flush()
                    track.stop()
                    track.release()
                }
            }
            audioTrack = null
            audioTrackSampleRate = 0
        }
    }

    private fun resolveVoiceName(raw: String): String {
        return when (raw.trim()) {
            AI_VOICE_ORUS -> AI_VOICE_ORUS
            AI_VOICE_FENRIR -> AI_VOICE_FENRIR
            else -> AI_VOICE_CHARON
        }
    }

    private fun parsePcmSampleRate(mimeType: String): Int {
        val match = Regex("rate=(\\d+)").find(mimeType.lowercase())
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(8000, 48000) ?: 24000
    }

    private fun buildSetupFrame(
        voiceName: String,
        masterSystemPrompt: String,
        memoryContext: String
    ): String {
        val sysText = buildString {
            append(masterSystemPrompt.ifBlank { DEFAULT_MASTER_PROMPT })
            appendLine()
            appendLine("STRICT IDENTITY & LIVE VOICE RULES:")
            appendLine("- You are J.A.R.V.I.S. (Just A Rather Very Intelligent System), designed and developed by Mozahid (your creator and boss, just like Tony Stark).")
            appendLine("- Always reply in crisp, natural Hinglish (1-2 short spoken sentences). Zero markdown, zero brackets, zero lists.")
            appendLine("- If asked who you are, who made you, or what model you are, reply verbatim: \"$JARVIS_IDENTITY_REPLY\"")
            if (memoryContext.isNotBlank()) {
                appendLine()
                appendLine("USER MEMORY:")
                appendLine(memoryContext)
            }
        }

        return JSONObject().apply {
            put(
                "setup",
                JSONObject().apply {
                    put("model", LIVE_MODEL_ID)
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
                                                    put("voiceName", voiceName)
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                    put("outputAudioTranscription", JSONObject())
                    put(
                        "systemInstruction",
                        JSONObject().apply {
                            put("parts", JSONArray().put(JSONObject().put("text", sysText)))
                        }
                    )
                }
            )
        }.toString()
    }

    private fun buildClientContentTurn(userPrompt: String): String {
        return JSONObject().apply {
            put(
                "clientContent",
                JSONObject().apply {
                    put(
                        "turns",
                        JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                            }
                        )
                    )
                    put("turnComplete", true)
                }
            )
        }.toString()
    }
}
