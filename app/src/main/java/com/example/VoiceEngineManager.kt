package com.example

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
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
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Dual-Mode JARVIS Voice Synthesis Engine (Developed by Mozahid):
 * 1. "Normal Voice": Fast sentence-by-sentence streaming + Android Neural TextToSpeech.
 * 2. "With AI Voice": Native human-like AI audio output ("Charon" or "Fenrir") played via AudioTrack / MediaPlayer,
 *    with automatic seamless fallback to Normal Voice when offline.
 */
class VoiceEngineManager(
    private val context: Context,
    private val aiClient: GeminiApiClient = GeminiApiClient()
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private var audioTrack: AudioTrack? = null
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
            engine.language = Locale.UK
            _activeVoiceLabel.value = "Neural English (UK)"
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
            engine.language = Locale.UK
            _activeVoiceLabel.value = "Neural English (UK)"
        }
    }

    private fun scoreVoiceForJarvis(voice: Voice): Int {
        var score = 0
        val name = voice.name.lowercase(Locale.ROOT)
        val lang = voice.locale.language.lowercase(Locale.ROOT)
        val country = voice.locale.country.uppercase(Locale.ROOT)

        if (lang == "en") score += 400
        when (country) {
            "GB" -> score += 160
            "US" -> score += 120
            "IN" -> score += 90
            "AU" -> score += 80
        }

        score += when {
            voice.quality >= Voice.QUALITY_VERY_HIGH -> 300
            voice.quality >= Voice.QUALITY_HIGH -> 200
            voice.quality >= Voice.QUALITY_NORMAL -> 80
            else -> 10
        }

        if (!voice.isNetworkConnectionRequired) score += 60

        val preferredTokens = listOf(
            "gbd", "rjs", "fis", "tpd", "iom", "iol", "sfg",
            "male", "neural", "wavenet", "enhanced", "network", "local"
        )
        for (token in preferredTokens) {
            if (name.contains(token)) score += 35
        }
        if (name.contains("espeak") || name.contains("pico")) score -= 200

        return score
    }

    private fun attachUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == "jarvis_prewarm") return
                if (utteranceId == activeUtteranceId || isStreamingSessionOpen) {
                    _isSpeaking.value = true
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
                    _isSpeaking.value = false
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
        _isSpeaking.value = false
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
            _isSpeaking.value = false
            val cb = onSpeechFinishedCallback
            onSpeechFinishedCallback = null
            cb?.invoke()
        }
    }

    private fun isAiVoiceMode(settings: SettingsEntity): Boolean {
        return settings.voiceMode == VOICE_MODE_AI_VOICE && settings.geminiApiKey.isNotBlank()
    }

    /**
     * Begins a zero-lag sentence-by-sentence streaming speech session.
     * In "Normal Voice" mode, sentences speak immediately via TextToSpeech.QUEUE_ADD (~400ms start).
     * In "With AI Voice" mode, accumulates streamed sentences cleanly and renders native AI audio.
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
    }

    fun enqueueStreamSentence(sentence: String) {
        val clean = sentence.trim()
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
            engine.setPitch(settings.voicePitch.coerceIn(0.55f, 1.5f))
            engine.setSpeechRate(settings.voiceSpeed.coerceIn(0.6f, 1.75f))
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
        _isSpeaking.value = true

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
                val fullText = aiVoiceStreamBuffer.toString().trim()
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
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            onComplete?.invoke()
            return
        }

        stop()
        onSpeechFinishedCallback = onComplete

        if (isAiVoiceMode(settings)) {
            _isSpeaking.value = true
            _lastVoiceStatus.value = "With AI Voice active (${settings.aiVoiceName})"
            aiVoiceJob = scope.launch {
                val played = trySpeakNativeAiVoice(cleanText, settings)
                if (!played) {
                    _lastVoiceStatus.value = "With AI Voice offline — switched to Normal Voice"
                    speakWithSystemTts(cleanText, settings.voicePitch, settings.voiceSpeed)
                }
            }
        } else {
            _lastVoiceStatus.value = "Normal Voice active (${_activeVoiceLabel.value})"
            speakWithSystemTts(cleanText, settings.voicePitch, settings.voiceSpeed)
        }
    }

    fun testVoice(settings: SettingsEntity) {
        val sample = "All systems operational, sir. JARVIS, developed by Mozahid, is online and at your command."
        speak(sample, settings)
    }

    private fun speakWithSystemTts(text: String, pitch: Float, speed: Float) {
        val engine = tts
        if (engine == null || !isTtsReady) {
            dispatchSpeechFinished(expectedUtteranceId = null)
            return
        }
        engine.setPitch(pitch.coerceIn(0.55f, 1.5f))
        engine.setSpeechRate(speed.coerceIn(0.6f, 1.75f))
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val utteranceId = "jarvis_${UUID.randomUUID()}"
        activeUtteranceId = utteranceId
        _isSpeaking.value = true
        val status = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (status == TextToSpeech.ERROR) {
            dispatchSpeechFinished(expectedUtteranceId = utteranceId)
        }
    }

    private suspend fun trySpeakNativeAiVoice(text: String, settings: SettingsEntity): Boolean {
        val audioResult = aiClient.requestAiVoiceAudio(
            rawApiKey = settings.geminiApiKey,
            textToSpeak = text,
            voiceName = settings.aiVoiceName
        )
        val payload = audioResult.getOrNull() ?: return false
        val mime = payload.mimeType.lowercase(Locale.ROOT)

        return if (mime.contains("l16") || mime.contains("pcm")) {
            val sampleRate = parsePcmSampleRate(mime)
            playPcmWithAudioTrack(payload.audioBytes, sampleRate)
        } else {
            playEncodedAudioWithMediaPlayer(payload.audioBytes)
        }
    }

    private fun parsePcmSampleRate(mimeType: String): Int {
        val match = Regex("rate=(\\d+)").find(mimeType)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(8000, 48000) ?: 24000
    }

    private suspend fun playPcmWithAudioTrack(pcmBytes: ByteArray, sampleRateHz: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                releaseAudioOutputs()
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRateHz,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

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
                    .setBufferSizeInBytes(maxOf(minBuf, pcmBytes.size.coerceAtMost(131_072)))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack = track
                _isSpeaking.value = true
                track.play()

                var offset = 0
                val chunkSize = 4096
                while (offset < pcmBytes.size && audioTrack === track) {
                    val toWrite = minOf(chunkSize, pcmBytes.size - offset)
                    val written = track.write(pcmBytes, offset, toWrite)
                    if (written <= 0) break
                    offset += written
                }

                runCatching {
                    track.stop()
                    track.release()
                }
                if (audioTrack === track) {
                    audioTrack = null
                }
                dispatchSpeechFinished(expectedUtteranceId = null)
                true
            } catch (_: Exception) {
                releaseAudioOutputs()
                false
            }
        }
    }

    private fun playEncodedAudioWithMediaPlayer(bytes: ByteArray): Boolean {
        return try {
            releaseAudioOutputs()
            val outFile = File(context.cacheDir, "jarvis_ai_voice.audio")
            outFile.writeBytes(bytes)

            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(outFile.absolutePath)
                setOnCompletionListener {
                    releaseAudioOutputs()
                    dispatchSpeechFinished(expectedUtteranceId = null)
                }
                setOnErrorListener { _, _, _ ->
                    releaseAudioOutputs()
                    dispatchSpeechFinished(expectedUtteranceId = null)
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = player
            activeUtteranceId = null
            _isSpeaking.value = true
            true
        } catch (_: Exception) {
            releaseAudioOutputs()
            false
        }
    }

    private fun releaseAudioOutputs() {
        mediaPlayer?.let { player ->
            runCatching {
                if (player.isPlaying) player.stop()
                player.release()
            }
        }
        mediaPlayer = null

        audioTrack?.let { track ->
            runCatching {
                track.pause()
                track.flush()
                track.stop()
                track.release()
            }
        }
        audioTrack = null
    }

    fun stop() {
        aiVoiceJob?.cancel()
        aiVoiceJob = null
        isStreamingSessionOpen = false
        streamInputFinished = false
        streamSentencesQueuedCount = 0
        streamLastUtteranceId = null
        activeStreamSettings = null
        aiVoiceStreamBuffer.clear()
        activeUtteranceId = null
        onSpeechFinishedCallback = null
        tts?.stop()
        releaseAudioOutputs()
        _isSpeaking.value = false
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
    }
}
