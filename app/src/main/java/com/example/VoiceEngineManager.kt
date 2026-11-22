package com.example

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

/**
 * Dual-Mode JARVIS Voice Engine (Developed by Mozahid):
 * 1. "Normal Voice": Fast sentence-by-sentence streaming + best available Neural Android TTS
 *    (prioritizing en-IN / hi-IN / en-GB deep male pitch 0.92f).
 * 2. "With AI Voice": Real-time Multimodal Live WebSocket streaming directly into Android AudioTrack
 *    (24kHz PCM_16BIT MONO MODE_STREAM, selectable deep male voice "Charon" or "Fenrir") with
 *    ~400ms start latency and automatic fallback to Normal Voice when offline or unavailable.
 *
 * Strictly sanitizes all text via JarvisResponseSanitizer before playback and coordinates with
 * MicCoordinator so SpeechRecognizer NEVER listens while JARVIS is speaking.
 */
class VoiceEngineManager(
    private val context: Context,
    private val aiClient: GeminiApiClient = GeminiApiClient()
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var audioTrack: AudioTrack? = null
    private var audioTrackSampleRate: Int = 0
    private var aiVoiceJob: Job? = null

    @Volatile
    private var activeUtteranceId: String? = null
    private var onSpeechFinishedCallback: (() -> Unit)? = null

    // Sentence-by-sentence streaming speech queue state
    @Volatile
    private var isStreamingSessionOpen = false
    @Volatile
    private var streamInputFinished = false
    @Volatile
    private var streamSentencesQueuedCount = 0
    @Volatile
    private var streamLastUtteranceId: String? = null
    private val aiVoiceStreamBuffer = StringBuilder()
    private var activeStreamSettings: SettingsEntity? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _activeVoiceLabel = MutableStateFlow("Initializing Neural Voice…")
    val activeVoiceLabel: StateFlow<String> = _activeVoiceLabel.asStateFlow()

    private val _lastVoiceStatus = MutableStateFlow("Normal Voice ready")
    val lastVoiceStatus: StateFlow<String> = _lastVoiceStatus.asStateFlow()

    init {
        initializeSystemTts()
    }

    private fun setSpeakingState(speaking: Boolean) {
        _isSpeaking.value = speaking
        MicCoordinator.setJarvisSpeaking(speaking)
    }

    private fun initializeSystemTts() {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
                configureAssistantAudioAttributes()
                selectBestJarvisVoice()
                attachUtteranceListener()
                prewarmTtsPipeline()
            } else {
                isTtsReady = false
                _activeVoiceLabel.value = "System TTS unavailable"
                _lastVoiceStatus.value = "Failed to initialize Android TTS engine."
            }
        }
    }

    private fun prewarmTtsPipeline() {
        runCatching {
            tts?.playSilentUtterance(1L, TextToSpeech.QUEUE_FLUSH, "jarvis_prewarm")
        }
    }

    private fun configureAssistantAudioAttributes() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        tts?.setAudioAttributes(attrs)
    }

    private fun selectBestJarvisVoice() {
        val engine = tts ?: return
        val voices = try {
            engine.voices?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        if (voices.isEmpty()) {
            engine.language = Locale("en", "IN")
            _activeVoiceLabel.value = "Neural English (India)"
            return
        }

        val ranked = voices
            .filter { !it.isNetworkConnectionRequired || it.quality >= Voice.QUALITY_HIGH }
            .sortedByDescending { scoreVoiceForJarvis(it) }

        val best = ranked.firstOrNull()
        if (best != null) {
            engine.voice = best
            val qualityTag = when {
                best.quality >= Voice.QUALITY_VERY_HIGH -> "Ultra Neural"
                best.quality >= Voice.QUALITY_HIGH -> "Enhanced"
                else -> "Standard"
            }
            _activeVoiceLabel.value = "${best.locale.displayName} ($qualityTag)"
        } else {
            engine.language = Locale("en", "IN")
            _activeVoiceLabel.value = "Neural English (India)"
        }
    }

    private fun scoreVoiceForJarvis(voice: Voice): Int {
        var score = 0
        val name = voice.name.lowercase(Locale.ROOT)
        val lang = voice.locale.language.lowercase(Locale.ROOT)
        val country = voice.locale.country.uppercase(Locale.ROOT)

        // Prioritize en-IN & hi-IN neural voices for natural Hinglish/English clarity, followed by en-GB / en-US
        if (lang == "en" && country == "IN") score += 520
        else if (lang == "hi" && country == "IN") score += 480
        else if (lang == "en" && country == "GB") score += 460
        else if (lang == "en" && country == "US") score += 430
        else if (lang == "en") score += 380

        score += when {
            voice.quality >= Voice.QUALITY_VERY_HIGH -> 320
            voice.quality >= Voice.QUALITY_HIGH -> 220
            voice.quality >= Voice.QUALITY_NORMAL -> 80
            else -> 10
        }

        if (!voice.isNetworkConnectionRequired) score += 70

        val preferredMaleNeuralTokens = listOf(
            "ene", "end", "hid", "gbd", "rjs", "fis", "tpd", "iom", "iol", "sfg",
            "male", "neural", "wavenet", "enhanced", "network", "local"
        )
        for (token in preferredMaleNeuralTokens) {
            if (name.contains(token)) score += 35
        }
        if (name.contains("espeak") || name.contains("pico")) score -= 220

        return score
    }

    private fun attachUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == "jarvis_prewarm") return
                if (utteranceId == activeUtteranceId || isStreamingSessionOpen) {
                    setSpeakingState(true)
                }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == "jarvis_prewarm") return
                handleUtteranceCompleted(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == "jarvis_prewarm") return
                handleUtteranceCompleted(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == "jarvis_prewarm") return
                handleUtteranceCompleted(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == activeUtteranceId) {
                    setSpeakingState(false)
                }
            }
        })
    }

    private fun handleUtteranceCompleted(utteranceId: String?) {
        scope.launch {
            if (isStreamingSessionOpen) {
                if (streamInputFinished && utteranceId != null && utteranceId == streamLastUtteranceId) {
                    completeStreamingSessionNow()
                }
                return@launch
            }
            dispatchSpeechFinished(expectedUtteranceId = utteranceId)
        }
    }

    private fun completeStreamingSessionNow() {
        isStreamingSessionOpen = false
        streamInputFinished = false
        streamSentencesQueuedCount = 0
        streamLastUtteranceId = null
        activeUtteranceId = null
        activeStreamSettings = null
        aiVoiceStreamBuffer.clear()
        setSpeakingState(false)
        val cb = onSpeechFinishedCallback
        onSpeechFinishedCallback = null
        cb?.invoke()
    }

    private fun dispatchSpeechFinished(expectedUtteranceId: String?) {
        scope.launch {
            if (expectedUtteranceId != null && expectedUtteranceId != activeUtteranceId) {
                return@launch
            }
            activeUtteranceId = null
            setSpeakingState(false)
            val cb = onSpeechFinishedCallback
            onSpeechFinishedCallback = null
            cb?.invoke()
        }
    }

    private fun isAiVoiceMode(settings: SettingsEntity): Boolean {
        return settings.voiceMode == VOICE_MODE_AI_VOICE && settings.geminiApiKey.isNotBlank()
    }

    private fun effectiveNormalPitch(settings: SettingsEntity): Float {
        // Default deep male JARVIS pitch 0.92f if unchanged from legacy default
        val raw = settings.voicePitch
        return if (raw <= 0.0f) DEFAULT_DEEP_MALE_PITCH else raw.coerceIn(0.60f, 1.40f)
    }

    /**
     * Begins a zero-lag sentence-by-sentence streaming speech session.
     * In "Normal Voice" mode, sanitized sentences speak immediately via TextToSpeech.QUEUE_ADD (~400ms start).
     * In "With AI Voice" mode, buffers the clean response and streams 24kHz PCM via Live WebSocket.
     */
    fun beginStreamingSpeech(
        settings: SettingsEntity,
        onAllFinished: (() -> Unit)? = null
    ) {
        stop()
        onSpeechFinishedCallback = onAllFinished
        activeStreamSettings = settings
        isStreamingSessionOpen = true
        streamInputFinished = false
        streamSentencesQueuedCount = 0
        streamLastUtteranceId = null
        aiVoiceStreamBuffer.clear()
        setSpeakingState(true)
    }

    fun enqueueStreamSentence(sentence: String) {
        val clean = JarvisResponseSanitizer.sanitizePlainSpeechText(sentence)
        val settings = activeStreamSettings ?: return
        if (clean.isEmpty() || !isStreamingSessionOpen) return

        if (isAiVoiceMode(settings)) {
            if (aiVoiceStreamBuffer.isNotEmpty()) aiVoiceStreamBuffer.append(' ')
            aiVoiceStreamBuffer.append(clean)
            return
        }

        val engine = tts
        if (engine == null || !isTtsReady) return

        val isFirst = streamSentencesQueuedCount == 0
        if (isFirst) {
            engine.setPitch(effectiveNormalPitch(settings))
            engine.setSpeechRate(settings.voiceSpeed.coerceIn(0.65f, 1.75f))
            _lastVoiceStatus.value = "Normal Voice active (${_activeVoiceLabel.value})"
        }

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val utteranceId = "jarvis_stream_${UUID.randomUUID()}"
        val queueMode = if (isFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        streamSentencesQueuedCount++
        streamLastUtteranceId = utteranceId
        activeUtteranceId = utteranceId
        setSpeakingState(true)

        val status = engine.speak(clean, queueMode, params, utteranceId)
        if (status == TextToSpeech.ERROR) {
            streamSentencesQueuedCount = (streamSentencesQueuedCount - 1).coerceAtLeast(0)
        }
    }

    fun finishStreamingSpeech() {
        scope.launch {
            if (!isStreamingSessionOpen) return@launch
            val settings = activeStreamSettings

            if (settings != null && isAiVoiceMode(settings) && aiVoiceStreamBuffer.isNotEmpty()) {
                val fullText = JarvisResponseSanitizer.extractCleanReply(aiVoiceStreamBuffer.toString())
                val cb = onSpeechFinishedCallback
                isStreamingSessionOpen = false
                streamInputFinished = false
                aiVoiceStreamBuffer.clear()
                activeStreamSettings = null
                speak(fullText, settings, cb)
                return@launch
            }

            streamInputFinished = true
            val engine = tts
            val nothingQueuedOrPlaying = streamSentencesQueuedCount == 0 ||
                engine == null ||
                !isTtsReady ||
                (!engine.isSpeaking && !_isSpeaking.value)
            if (nothingQueuedOrPlaying) {
                completeStreamingSessionNow()
            }
        }
    }

    fun speak(
        text: String,
        settings: SettingsEntity,
        onComplete: (() -> Unit)? = null
    ) {
        val cleanText = JarvisResponseSanitizer.extractCleanReply(text)
        if (cleanText.isEmpty()) {
            setSpeakingState(false)
            onComplete?.invoke()
            return
        }

        stop()
        onSpeechFinishedCallback = onComplete

        if (isAiVoiceMode(settings) && !PersistentLiveAudioSession.isInQuotaCooldown()) {
            setSpeakingState(true)
            val chosenVoice = settings.aiVoiceName.ifBlank { AI_VOICE_CHARON }
            _lastVoiceStatus.value = "Single Live AI Voice ($chosenVoice)"
            aiVoiceJob = scope.launch {
                val res = PersistentLiveAudioSession.speakVerbatim(
                    rawApiKey = settings.geminiApiKey,
                    textToSpeak = cleanText,
                    voiceName = chosenVoice
                )
                if (res.isSuccess) {
                    dispatchSpeechFinished(expectedUtteranceId = null)
                } else {
                    _lastVoiceStatus.value = "Live Audio paused — using Neural Normal Voice"
                    speakWithSystemTts(cleanText, effectiveNormalPitch(settings), settings.voiceSpeed)
                }
            }
        } else {
            _lastVoiceStatus.value = "Normal Voice active (${_activeVoiceLabel.value})"
            speakWithSystemTts(cleanText, effectiveNormalPitch(settings), settings.voiceSpeed)
        }
    }

    fun testVoice(settings: SettingsEntity) {
        val sample = "All systems operational, sir. JARVIS, developed by Mozahid, is online and ready for your command."
        speak(sample, settings)
    }

    private fun speakWithSystemTts(text: String, pitch: Float, speed: Float) {
        val sanitized = JarvisResponseSanitizer.sanitizePlainSpeechText(text)
        val engine = tts
        if (sanitized.isEmpty() || engine == null || !isTtsReady) {
            dispatchSpeechFinished(expectedUtteranceId = null)
            return
        }
        engine.setPitch(pitch.coerceIn(0.60f, 1.40f))
        engine.setSpeechRate(speed.coerceIn(0.65f, 1.75f))
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val utteranceId = "jarvis_${UUID.randomUUID()}"
        activeUtteranceId = utteranceId
        setSpeakingState(true)
        val status = engine.speak(sanitized, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (status == TextToSpeech.ERROR) {
            dispatchSpeechFinished(expectedUtteranceId = utteranceId)
        }
    }

    /**
     * Streams 24kHz 16-bit mono PCM chunks from Multimodal Live WebSocket directly into AudioTrack
     * as each chunk arrives for ultra-low-latency (~400ms) realistic male AI speech.
     */
    private suspend fun streamLiveAiVoiceToAudioTrack(
        cleanText: String,
        settings: SettingsEntity
    ): Boolean = withContext(Dispatchers.IO) {
        releaseAudioTrack()
        var wroteAnyAudio = false
        val trackLock = Any()

        val result = aiClient.streamLiveAiVoice(
            rawApiKey = settings.geminiApiKey,
            textToSpeak = cleanText,
            voiceName = settings.aiVoiceName.ifBlank { AI_VOICE_CHARON },
            onPcmChunk = { pcmBytes, sampleRateHz ->
                synchronized(trackLock) {
                    val track = ensureStreamingAudioTrack(sampleRateHz) ?: return@synchronized
                    var offset = 0
                    while (offset < pcmBytes.size && audioTrack === track) {
                        val toWrite = minOf(4096, pcmBytes.size - offset)
                        val written = runCatching {
                            track.write(pcmBytes, offset, toWrite, AudioTrack.WRITE_BLOCKING)
                        }.getOrDefault(-1)
                        if (written <= 0) break
                        offset += written
                        wroteAnyAudio = true
                    }
                }
            }
        )

        synchronized(trackLock) {
            audioTrack?.let { track ->
                runCatching {
                    // Drain remaining buffered audio before releasing
                    track.stop()
                    track.release()
                }
                audioTrack = null
                audioTrackSampleRate = 0
            }
        }

        if (result.isSuccess && wroteAnyAudio) {
            dispatchSpeechFinished(expectedUtteranceId = null)
            true
        } else {
            false
        }
    }

    private fun ensureStreamingAudioTrack(sampleRateHz: Int): AudioTrack? {
        val existing = audioTrack
        if (existing != null && audioTrackSampleRate == sampleRateHz) {
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

    private fun releaseAudioTrack() {
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

    fun stop() {
        aiVoiceJob?.cancel()
        aiVoiceJob = null
        aiClient.cancelActiveVoiceStream()
        PersistentLiveAudioSession.interruptCurrentPlayback()
        isStreamingSessionOpen = false
        streamInputFinished = false
        streamSentencesQueuedCount = 0
        streamLastUtteranceId = null
        activeStreamSettings = null
        aiVoiceStreamBuffer.clear()
        activeUtteranceId = null
        onSpeechFinishedCallback = null
        tts?.stop()
        releaseAudioTrack()
        setSpeakingState(false)
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
    }

    companion object {
        const val DEFAULT_DEEP_MALE_PITCH = 0.92f
    }
}
