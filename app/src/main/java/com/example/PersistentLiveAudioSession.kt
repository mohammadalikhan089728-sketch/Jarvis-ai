package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

data class LiveAudioTurnResult(
    val spokenTranscript: String,
    val pcmChunksPlayed: Int,
    val toolsExecuted: List<String> = emptyList()
)

interface LiveAudioCallbacks {
    fun onUserTranscript(text: String) {}
    fun onJarvisTranscript(text: String, isTurnComplete: Boolean) {}
    fun onPhaseChanged(phase: JarvisPhase) {}
    fun onVoiceRmsChanged(rmsDb: Float) {}
    fun onToolExecuting(toolName: String) {}
    fun onAutoDisconnected(reason: String) {}
}

/**
 * High-Efficiency Real-Time Multimodal Live WebSocket Engine for J.A.R.V.I.S. (Developed by Mozahid).
 *
 * Exact Architecture:
 * - Single persistent Gemini Multimodal Live API WebSocket:
 *     Endpoint: wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent
 *     Auth Header: x-goog-api-key: <USER_GEMINI_API_KEY>
 *     Default Model: models/gemini-3.1-flash-live-preview
 *     Default Voice: "Puck" (native 24kHz PCM audio output, zero robotic TTS unless offline fallback)
 * - Full-Duplex Native Audio:
 *     16kHz 16-bit mono PCM AudioRecord input -> WebSocket `realtimeInput.mediaChunks`
 *     24kHz 16-bit mono PCM AudioTrack playback <- WebSocket `serverContent.modelTurn.parts[].inlineData`
 * - Echo Cancellation & Mic-Muting While JARVIS Speaks:
 *     Hardware AcousticEchoCanceler + NoiseSuppressor + strict software mic-gate while model audio plays
 *     (+ 280ms post-playback acoustic tail guard) so JARVIS never triggers itself in a loop.
 * - Token-Saving Lazy Screen Reading & Full Tool Calling:
 *     Registers `ui_read`, `ui_action`, `youtube_search_play`, `get_youtube_channel_stats`,
 *     `upload_youtube_video`, and `trigger_jarvis_call` in `setup.tools[].functionDeclarations`.
 *     Screen data is NEVER sent automatically; ONLY read when `ui_read` is invoked.
 * - 3-Minute (180,000 ms) Silence/Inactivity Auto-Disconnect to protect tokens and API quota.
 */
object PersistentLiveAudioSession {

    const val LIVE_MODEL_SHORT = "gemini-3.1-flash-live-preview"
    const val LIVE_MODEL_ID = "models/$LIVE_MODEL_SHORT"
    const val BIDI_LIVE_WS_BASE_URL =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val HEADER_API_KEY = "x-goog-api-key"

    const val INPUT_SAMPLE_RATE_HZ = 16_000
    const val OUTPUT_SAMPLE_RATE_HZ = 24_000
    const val INACTIVITY_DISCONNECT_MS = 180_000L

    private const val QUOTA_COOLDOWN_MS = 60_000L
    private const val SETUP_TIMEOUT_MS = 10_000L
    private const val TURN_TIMEOUT_MS = 35_000L
    private const val POST_SPEECH_MIC_GUARD_MS = 280L
    private const val SPEECH_RMS_ACTIVITY_THRESHOLD = 420.0

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val wsClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Long-lived persistent WebSocket
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val turnMutex = Mutex()
    private val trackLock = Any()

    @Volatile
    private var appContextRef: Context? = null
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
    private val activeInputTranscript = StringBuilder()
    private val activeTurnTools = mutableListOf<String>()
    private val activeTurnChunkCount = AtomicInteger(0)
    private val activeTurnInterrupted = AtomicBoolean(false)

    // Native 16kHz PCM duplex capture & echo-gate state
    private val isNativeMicStreaming = AtomicBoolean(false)
    private val isModelAudioPlaying = AtomicBoolean(false)
    private val micUnmuteAfterElapsedMs = AtomicLong(0L)
    private val lastActivityElapsedMs = AtomicLong(0L)
    @Volatile
    private var liveCallbacks: LiveAudioCallbacks? = null
    @Volatile
    private var micStreamJob: Job? = null
    @Volatile
    private var inactivityTimerJob: Job? = null

    private var audioTrack: AudioTrack? = null
    private var audioTrackSampleRate: Int = 0

    private val _isSessionConnected = MutableStateFlow(false)
    val isSessionConnected: StateFlow<Boolean> = _isSessionConnected.asStateFlow()

    private val _isFullDuplexMicActive = MutableStateFlow(false)
    val isFullDuplexMicActive: StateFlow<Boolean> = _isFullDuplexMicActive.asStateFlow()

    private val _inactivityRemainingMs = MutableStateFlow(INACTIVITY_DISCONNECT_MS)
    val inactivityRemainingMs: StateFlow<Long> = _inactivityRemainingMs.asStateFlow()

    private val _sessionStatus = MutableStateFlow("Live Link Standby")
    val sessionStatus: StateFlow<String> = _sessionStatus.asStateFlow()

    fun attachContext(context: Context) {
        appContextRef = context.applicationContext
    }

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

    fun markActivity() {
        lastActivityElapsedMs.set(SystemClock.elapsedRealtime())
        _inactivityRemainingMs.value = INACTIVITY_DISCONNECT_MS
    }

    /**
     * Ensures ONE persistent Gemini Multimodal Live WebSocket connection is open with:
     * - Endpoint: BidiGenerateContent
     * - Header: x-goog-api-key: <USER_GEMINI_API_KEY>
     * - Model: models/gemini-3.1-flash-live-preview
     * - Voice: "Puck" (default)
     * - Registered Tools: ui_read, ui_action, youtube_search_play, get_youtube_channel_stats,
     *   upload_youtube_video, trigger_jarvis_call
     */
    suspend fun ensureConnected(
        rawApiKey: String,
        voiceName: String = AI_VOICE_PUCK,
        masterSystemPrompt: String = DEFAULT_MASTER_PROMPT,
        memoryContext: String = "",
        context: Context? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (context != null) {
            appContextRef = context.applicationContext
        }
        val apiKey = rawApiKey.trim()
        if (apiKey.isEmpty() || isInQuotaCooldown()) {
            return@withContext false
        }

        val resolvedVoice = resolveVoiceName(voiceName)
        val keyHash = apiKey.hashCode()

        // Reuse existing live WebSocket session if already connected with same key & voice
        if (activeSocket != null &&
            isSetupReady &&
            connectedKeyHash == keyHash &&
            connectedVoiceName == resolvedVoice
        ) {
            markActivity()
            return@withContext true
        }

        closeSessionInternal("Reconfiguring live session")

        val setupSignal = CompletableDeferred<Boolean>()
        setupDeferred = setupSignal

        val ytChannelContext = appContextRef?.let { JarvisLiveToolRegistry.buildCompactYouTubeChannelContext(it) }.orEmpty()
        val setupPayload = buildSetupFrame(
            voiceName = resolvedVoice,
            masterSystemPrompt = masterSystemPrompt,
            memoryContext = memoryContext,
            youtubeChannelContext = ytChannelContext
        )

        val request = buildWebSocketRequest(apiKey)
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
        markActivity()
        startInactivityAutoDisconnectWatchdog()
        true
    }

    /**
     * Starts real-time native audio-to-audio streaming (16kHz PCM mic input -> Live WebSocket -> 24kHz PCM speaker output)
     * with hardware AcousticEchoCanceler + strict mic-muting while JARVIS is speaking + 3-minute inactivity disconnect.
     */
    suspend fun startNativeAudioStreaming(
        context: Context,
        rawApiKey: String,
        voiceName: String = AI_VOICE_PUCK,
        masterSystemPrompt: String = DEFAULT_MASTER_PROMPT,
        memoryContext: String = "",
        callbacks: LiveAudioCallbacks
    ): Boolean = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        if (ContextCompat.checkSelfPermission(appCtx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@withContext false
        }

        val connected = ensureConnected(
            rawApiKey = rawApiKey,
            voiceName = voiceName,
            masterSystemPrompt = masterSystemPrompt,
            memoryContext = memoryContext,
            context = appCtx
        )
        if (!connected || activeSocket == null || !isSetupReady) {
            return@withContext false
        }

        if (!MicCoordinator.tryAcquire(MicOwner.ACTIVE_ASSISTANT)) {
            return@withContext false
        }

        stopNativeAudioStreaming(releaseMicOwner = false)
        liveCallbacks = callbacks
        isNativeMicStreaming.set(true)
        _isFullDuplexMicActive.value = true
        markActivity()
        callbacks.onPhaseChanged(JarvisPhase.LISTENING)

        micStreamJob = sessionScope.launch {
            runPcm16kCaptureLoop(appCtx)
        }
        true
    }

    fun stopNativeAudioStreaming(releaseMicOwner: Boolean = true) {
        isNativeMicStreaming.set(false)
        _isFullDuplexMicActive.value = false
        micStreamJob?.cancel()
        micStreamJob = null
        liveCallbacks?.onVoiceRmsChanged(0f)
        liveCallbacks = null
        if (releaseMicOwner) {
            MicCoordinator.release(MicOwner.ACTIVE_ASSISTANT)
        }
    }

    private suspend fun runPcm16kCaptureLoop(appCtx: Context) {
        if (ContextCompat.checkSelfPermission(appCtx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            isNativeMicStreaming.set(false)
            _isFullDuplexMicActive.value = false
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        val chunkBytes = 3200 // 100ms of 16kHz 16-bit mono PCM (1600 samples * 2 bytes)
        var recorder: AudioRecord? = null
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null

        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, chunkBytes * 2)
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                return
            }

            // Enable hardware acoustic echo cancellation & noise suppression when available
            val sessionId = recorder.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                aec = runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }.getOrNull()
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }.getOrNull()
            }

            recorder.startRecording()
            val pcmBuffer = ByteArray(chunkBytes)

            while (sessionScope.isActive && isNativeMicStreaming.get() && isSetupReady) {
                val read = recorder.read(pcmBuffer, 0, pcmBuffer.size)
                if (read <= 0) {
                    delay(15L)
                    continue
                }

                // STRICT ECHO PREVENTION: Mute mic transmission completely while JARVIS is speaking
                // or during the 280ms post-playback acoustic tail guard!
                val nowElapsed = SystemClock.elapsedRealtime()
                val shouldMuteMic = isModelAudioPlaying.get() ||
                    MicCoordinator.isJarvisSpeaking.value ||
                    nowElapsed < micUnmuteAfterElapsedMs.get()

                if (shouldMuteMic) {
                    liveCallbacks?.onVoiceRmsChanged(0f)
                    continue
                }

                val rms = computePcm16Rms(pcmBuffer, read)
                val normalizedDb = ((rms / 350.0).coerceIn(0.0, 12.0)).toFloat()
                liveCallbacks?.onVoiceRmsChanged(normalizedDb)

                if (rms >= SPEECH_RMS_ACTIVITY_THRESHOLD) {
                    markActivity()
                }

                val ws = activeSocket ?: break
                val frame = buildRealtimeAudioFrame(pcmBuffer, read)
                val sent = runCatching { ws.send(frame) }.getOrDefault(false)
                if (!sent) break
            }
        } catch (_: SecurityException) {
            // Microphone permission revoked mid-session
        } catch (_: Exception) {
            // AudioRecord stopped cleanly
        } finally {
            runCatching { aec?.release() }
            runCatching { ns?.release() }
            runCatching {
                recorder?.stop()
                recorder?.release()
            }
            isNativeMicStreaming.set(false)
            _isFullDuplexMicActive.value = false
            liveCallbacks?.onVoiceRmsChanged(0f)
        }
    }

    /**
     * Executes a single text-to-native-audio turn directly over the persistent Live WebSocket!
     * Sends pure lightweight text (NO screen JSON!) — the model calls `ui_read`, `get_youtube_channel_stats`,
     * or `upload_youtube_video` lazily only when needed, and streams 24kHz PCM audio directly into AudioTrack.
     */
    suspend fun sendConversationalTurn(
        rawApiKey: String,
        userText: String,
        voiceName: String = AI_VOICE_PUCK,
        masterSystemPrompt: String = DEFAULT_MASTER_PROMPT,
        memoryContext: String = "",
        context: Context? = null
    ): Result<LiveAudioTurnResult> = turnMutex.withLock {
        withContext(Dispatchers.IO) {
            if (context != null) appContextRef = context.applicationContext
            if (isInQuotaCooldown()) {
                return@withContext Result.failure(
                    IllegalStateException("Rate-limit cooldown active (${remainingCooldownSeconds()}s).")
                )
            }
            val connected = ensureConnected(rawApiKey, voiceName, masterSystemPrompt, memoryContext, appContextRef)
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

            markActivity()
            val turnSignal = CompletableDeferred<Result<LiveAudioTurnResult>>()
            activeTurnTranscript.clear()
            activeTurnTools.clear()
            activeTurnChunkCount.set(0)
            activeTurnInterrupted.set(false)
            activeTurnDeferred = turnSignal

            setModelSpeaking(true)
            _sessionStatus.value = "Live Audio Active ($connectedVoiceName)"

            val promptText = if (memoryContext.isNotBlank()) {
                "[USER MEMORY]\n$memoryContext\n\n[USER]: $cleanInput"
            } else {
                cleanInput
            }

            val sent = runCatching { ws.send(buildClientContentTurn(promptText)) }.getOrDefault(false)
            if (!sent) {
                setModelSpeaking(false)
                closeSessionInternal("Send failed")
                return@withContext Result.failure(IllegalStateException("Failed to send live turn"))
            }

            val outcome = withTimeoutOrNull(TURN_TIMEOUT_MS) {
                turnSignal.await()
            } ?: run {
                val chunks = activeTurnChunkCount.get()
                val text = JarvisResponseSanitizer.extractCleanReply(activeTurnTranscript.toString())
                if (chunks > 0 || text.isNotEmpty()) {
                    Result.success(LiveAudioTurnResult(text, chunks, activeTurnTools.toList()))
                } else {
                    closeSessionInternal("Turn timeout")
                    Result.failure(IllegalStateException("Live Audio turn timed out."))
                }
            }

            drainAndPauseAudioTrack()
            activeTurnDeferred = null
            setModelSpeaking(false)
            outcome
        }
    }

    /**
     * Speaks an exact confirmation sentence aloud over the SAME persistent WebSocket (zero extra connections).
     */
    suspend fun speakVerbatim(
        rawApiKey: String,
        textToSpeak: String,
        voiceName: String = AI_VOICE_PUCK,
        context: Context? = null
    ): Result<Int> = turnMutex.withLock {
        withContext(Dispatchers.IO) {
            if (context != null) appContextRef = context.applicationContext
            val clean = JarvisResponseSanitizer.extractCleanReply(textToSpeak)
            if (clean.isEmpty() || isInQuotaCooldown()) {
                return@withContext Result.failure(IllegalStateException("Skipped verbatim live audio"))
            }
            val connected = ensureConnected(rawApiKey, voiceName, context = appContextRef)
            val ws = activeSocket
            if (!connected || ws == null || !isSetupReady) {
                return@withContext Result.failure(IllegalStateException("Persistent Live Audio link unavailable"))
            }

            markActivity()
            val turnSignal = CompletableDeferred<Result<LiveAudioTurnResult>>()
            activeTurnTranscript.clear()
            activeTurnTools.clear()
            activeTurnChunkCount.set(0)
            activeTurnInterrupted.set(false)
            activeTurnDeferred = turnSignal

            setModelSpeaking(true)
            val sent = runCatching {
                ws.send(
                    buildClientContentTurn(
                        "Speak this exact sentence aloud naturally without adding extra words or calling any tools: $clean"
                    )
                )
            }.getOrDefault(false)

            if (!sent) {
                setModelSpeaking(false)
                closeSessionInternal("Verbatim send failed")
                return@withContext Result.failure(IllegalStateException("Live send failed"))
            }

            val res = withTimeoutOrNull(20_000L) {
                turnSignal.await()
            } ?: Result.failure(IllegalStateException("Live audio playback timeout"))

            drainAndPauseAudioTrack()
            activeTurnDeferred = null
            setModelSpeaking(false)
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
                    pcmChunksPlayed = activeTurnChunkCount.get(),
                    toolsExecuted = activeTurnTools.toList()
                )
            )
        )
        activeTurnDeferred = null
        setModelSpeaking(false)
    }

    fun closeSession(reason: String = "Session ended") {
        closeSessionInternal(reason)
    }

    private fun startInactivityAutoDisconnectWatchdog() {
        inactivityTimerJob?.cancel()
        inactivityTimerJob = sessionScope.launch {
            while (isActive && activeSocket != null) {
                val elapsed = SystemClock.elapsedRealtime() - lastActivityElapsedMs.get()
                val remaining = (INACTIVITY_DISCONNECT_MS - elapsed).coerceAtLeast(0L)
                _inactivityRemainingMs.value = remaining
                if (elapsed >= INACTIVITY_DISCONNECT_MS && !isModelAudioPlaying.get()) {
                    val cb = liveCallbacks
                    closeSessionInternal("3-minute inactivity auto-disconnect")
                    cb?.onAutoDisconnected("No voice activity for 3 minutes. Live session disconnected to save tokens.")
                    break
                }
                delay(500L)
            }
        }
    }

    private fun setModelSpeaking(speaking: Boolean) {
        isModelAudioPlaying.set(speaking)
        MicCoordinator.setJarvisSpeaking(speaking)
        if (!speaking) {
            micUnmuteAfterElapsedMs.set(SystemClock.elapsedRealtime() + POST_SPEECH_MIC_GUARD_MS)
        }
    }

    private fun closeSessionInternal(reason: String) {
        inactivityTimerJob?.cancel()
        inactivityTimerJob = null
        stopNativeAudioStreaming(releaseMicOwner = true)

        val ws = activeSocket
        activeSocket = null
        isSetupReady = false
        _isSessionConnected.value = false
        _inactivityRemainingMs.value = 0L
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
        setModelSpeaking(false)
    }

    private fun handleSocketClosed(ws: WebSocket, reason: String) {
        if (activeSocket === ws) {
            inactivityTimerJob?.cancel()
            inactivityTimerJob = null
            stopNativeAudioStreaming(releaseMicOwner = true)
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
                            pcmChunksPlayed = chunks,
                            toolsExecuted = activeTurnTools.toList()
                        )
                    )
                )
            } else {
                turn.complete(Result.failure(IllegalStateException(reason)))
            }
        }
        activeTurnDeferred = null
        setModelSpeaking(false)
    }

    private fun handleIncomingFrame(ws: WebSocket, rawJson: String) {
        try {
            val root = JSONObject(rawJson)

            if (root.has("setupComplete")) {
                isSetupReady = true
                _isSessionConnected.value = true
                _sessionStatus.value = "Live Link Connected ($connectedVoiceName)"
                markActivity()
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

            // Handle official Gemini Live Function Calling (`toolCall.functionCalls`)!
            if (root.has("toolCall")) {
                val toolCall = root.optJSONObject("toolCall")
                val fnCalls = toolCall?.optJSONArray("functionCalls")
                if (fnCalls != null && fnCalls.length() > 0) {
                    markActivity()
                    dispatchLiveToolCalls(ws, fnCalls)
                }
                return
            }

            val serverContent = root.optJSONObject("serverContent") ?: return

            // Capture user's live speech transcription (when full-duplex 16kHz PCM mic is active)
            val inTranscript = serverContent.optJSONObject("inputTranscription")?.optString("text").orEmpty()
            if (inTranscript.isNotBlank()) {
                markActivity()
                activeInputTranscript.append(inTranscript)
                liveCallbacks?.onUserTranscript(activeInputTranscript.toString().trim())
            }

            // Capture model's output transcription
            val outTranscript = serverContent.optJSONObject("outputTranscription")?.optString("text").orEmpty()
            if (outTranscript.isNotBlank()) {
                markActivity()
                activeTurnTranscript.append(outTranscript)
                liveCallbacks?.onJarvisTranscript(
                    JarvisResponseSanitizer.extractCleanReply(activeTurnTranscript.toString()),
                    isTurnComplete = false
                )
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
                        if (!isModelAudioPlaying.get()) {
                            setModelSpeaking(true)
                            liveCallbacks?.onPhaseChanged(JarvisPhase.SPEAKING)
                        }
                        writePcmChunkToTrack(pcmBytes, sampleRate)
                        activeTurnChunkCount.incrementAndGet()
                    }
                }
            }

            if (serverContent.optBoolean("turnComplete", false)) {
                markActivity()
                val chunks = activeTurnChunkCount.get()
                val cleanReply = JarvisResponseSanitizer.extractCleanReply(activeTurnTranscript.toString())
                val toolsUsed = activeTurnTools.toList()

                if (isNativeMicStreaming.get()) {
                    drainAndPauseAudioTrack()
                    setModelSpeaking(false)
                    if (cleanReply.isNotEmpty()) {
                        liveCallbacks?.onJarvisTranscript(cleanReply, isTurnComplete = true)
                    }
                    activeTurnTranscript.clear()
                    activeInputTranscript.clear()
                    activeTurnTools.clear()
                    activeTurnChunkCount.set(0)
                    liveCallbacks?.onPhaseChanged(JarvisPhase.LISTENING)
                }

                activeTurnDeferred?.let { deferred ->
                    if (!deferred.isCompleted) {
                        if (chunks > 0 || cleanReply.isNotEmpty()) {
                            deferred.complete(Result.success(LiveAudioTurnResult(cleanReply, chunks, toolsUsed)))
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

    private fun dispatchLiveToolCalls(ws: WebSocket, fnCalls: JSONArray) {
        val ctx = appContextRef
        sessionScope.launch {
            val responsesArr = JSONArray()
            for (i in 0 until fnCalls.length()) {
                val call = fnCalls.optJSONObject(i) ?: continue
                val callId = call.optString("id", "call_$i")
                val name = call.optString("name", "")
                val args = call.optJSONObject("args") ?: JSONObject()

                activeTurnTools.add(name)
                liveCallbacks?.onToolExecuting(name)
                liveCallbacks?.onPhaseChanged(JarvisPhase.ACTING)
                _sessionStatus.value = "Executing tool: $name"

                val toolResult = if (ctx != null) {
                    JarvisLiveToolRegistry.executeToolCall(ctx, name, args)
                } else {
                    JSONObject().put("ok", false).put("error", "Android context unavailable for tool $name")
                }

                responsesArr.put(
                    JSONObject().apply {
                        put("id", callId)
                        put("name", name)
                        put("response", toolResult)
                    }
                )
            }
            val payload = buildToolResponseFrame(responsesArr)
            runCatching { ws.send(payload) }
            _sessionStatus.value = "Live Audio Active ($connectedVoiceName)"
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
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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

    internal fun resolveVoiceName(raw: String): String {
        return when (raw.trim()) {
            AI_VOICE_CHARON -> AI_VOICE_CHARON
            AI_VOICE_ORUS -> AI_VOICE_ORUS
            AI_VOICE_FENRIR -> AI_VOICE_FENRIR
            else -> AI_VOICE_PUCK
        }
    }

    private fun parsePcmSampleRate(mimeType: String): Int {
        val match = Regex("rate=(\\d+)").find(mimeType.lowercase())
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(8000, 48000) ?: OUTPUT_SAMPLE_RATE_HZ
    }

    private fun computePcm16Rms(pcmBytes: ByteArray, length: Int): Double {
        var sumSq = 0.0
        var count = 0
        var i = 0
        while (i + 1 < length) {
            val lo = pcmBytes[i].toInt() and 0xFF
            val hi = pcmBytes[i + 1].toInt()
            val sample = (hi shl 8) or lo
            sumSq += (sample * sample).toDouble()
            count++
            i += 2
        }
        return if (count > 0) sqrt(sumSq / count) else 0.0
    }

    internal fun buildWebSocketRequest(apiKey: String): Request {
        return Request.Builder()
            .url(BIDI_LIVE_WS_BASE_URL)
            .header(HEADER_API_KEY, apiKey.trim())
            .build()
    }

    internal fun buildSetupFrame(
        voiceName: String = AI_VOICE_PUCK,
        masterSystemPrompt: String = DEFAULT_MASTER_PROMPT,
        memoryContext: String = "",
        youtubeChannelContext: String = ""
    ): String {
        val resolvedVoice = resolveVoiceName(voiceName)
        val sysText = buildString {
            append(masterSystemPrompt.ifBlank { DEFAULT_MASTER_PROMPT })
            appendLine()
            appendLine("STRICT REAL-TIME LIVE ASSISTANT BEHAVIOR:")
            appendLine("- Keep replies concise (1 to 3 natural spoken sentences) unless the user asks for detail.")
            appendLine("- Automatically match the user's language (Hindi, Hinglish, or English). Zero markdown, zero brackets.")
            appendLine("- Execute tools BEFORE confirming any action.")
            appendLine("- NEVER fabricate screen contents or YouTube channel/video numbers — call `ui_read` or `get_youtube_channel_stats` first.")
            appendLine("- Token-Saving Rule: Call `ui_read` ONLY when the user explicitly asks about the screen or requests tapping an on-screen element.")
            appendLine("- If asked who you are, who made you, or what model you are, reply verbatim: \"$JARVIS_IDENTITY_REPLY\"")
            if (youtubeChannelContext.isNotBlank()) {
                appendLine()
                appendLine(youtubeChannelContext)
            }
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
                                                    put("voiceName", resolvedVoice)
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                    put("inputAudioTranscription", JSONObject())
                    put("outputAudioTranscription", JSONObject())
                    put("tools", JarvisLiveToolRegistry.buildLiveToolsJsonArray())
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

    internal fun buildRealtimeAudioFrame(pcm16kBytes: ByteArray, length: Int = pcm16kBytes.size): String {
        val slice = if (length == pcm16kBytes.size) pcm16kBytes else pcm16kBytes.copyOf(length)
        val b64 = Base64.encodeToString(slice, Base64.NO_WRAP)
        return JSONObject().apply {
            put(
                "realtimeInput",
                JSONObject().apply {
                    put(
                        "mediaChunks",
                        JSONArray().put(
                            JSONObject().apply {
                                put("mimeType", "audio/pcm;rate=$INPUT_SAMPLE_RATE_HZ")
                                put("data", b64)
                            }
                        )
                    )
                }
            )
        }.toString()
    }

    internal fun buildToolResponseFrame(functionResponses: JSONArray): String {
        return JSONObject().apply {
            put(
                "toolResponse",
                JSONObject().apply {
                    put("functionResponses", functionResponses)
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
