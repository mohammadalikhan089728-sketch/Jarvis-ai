package com.example

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class VoiceEngineManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private var customPlaybackJob: Job? = null

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
    private val customStreamBuffer = StringBuilder()
    private var activeStreamSettings: SettingsEntity? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _activeVoiceLabel = MutableStateFlow("Initializing Neural Voice…")
    val activeVoiceLabel: StateFlow<String> = _activeVoiceLabel.asStateFlow()

    private val _lastVoiceStatus = MutableStateFlow("Voice engine ready")
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
            _activeVoiceLabel.value = "System English (UK)"
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
            _activeVoiceLabel.value = "System English (UK)"
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
        customStreamBuffer.clear()
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

    /**
     * Begins a zero-lag sentence-by-sentence streaming TTS session.
     * Call [enqueueStreamSentence] for each sentence arriving from gemini-3.5-flash SSE,
     * then call [finishStreamingSpeech] when the stream closes.
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
        customStreamBuffer.clear()
    }

    /**
     * Feeds a completed sentence immediately into Android TTS using TextToSpeech.QUEUE_ADD
     * (after flushing on the very first sentence) so JARVIS speaks within ~400ms.
     */
    fun enqueueStreamSentence(sentence: String) {
        val clean = sentence.trim()
        val settings = activeStreamSettings ?: return
        if (clean.isEmpty() || !isStreamingSessionOpen) return

        if (settings.voiceMode == VOICE_MODE_CUSTOM_URL && settings.customVoiceUrl.isNotBlank()) {
            // Buffer for custom HTTP audio endpoint playback once stream finishes cleanly
            if (customStreamBuffer.isNotEmpty()) customStreamBuffer.append(' ')
            customStreamBuffer.append(clean)
            return
        }

        val engine = tts
        if (engine == null || !isTtsReady) return

        val isFirst = streamSentencesQueuedCount == 0
        if (isFirst) {
            engine.setPitch(settings.voicePitch.coerceIn(0.55f, 1.5f))
            engine.setSpeechRate(settings.voiceSpeed.coerceIn(0.6f, 1.75f))
            _lastVoiceStatus.value = "Streaming Neural Voice (${_activeVoiceLabel.value})"
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

    /**
     * Signals that no more sentences will arrive in the current stream.
     * Fires [onAllFinished] once the final queued sentence finishes speaking.
     */
    fun finishStreamingSpeech() {
        scope.launch {
            if (!isStreamingSessionOpen) return@launch
            val settings = activeStreamSettings

            if (settings != null &&
                settings.voiceMode == VOICE_MODE_CUSTOM_URL &&
                settings.customVoiceUrl.isNotBlank() &&
                customStreamBuffer.isNotEmpty()
            ) {
                val fullText = customStreamBuffer.toString().trim()
                val cb = onSpeechFinishedCallback
                isStreamingSessionOpen = false
                streamInputFinished = false
                customStreamBuffer.clear()
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

        if (settings.voiceMode == VOICE_MODE_CUSTOM_URL && settings.customVoiceUrl.isNotBlank()) {
            customPlaybackJob = scope.launch {
                val customSuccess = trySpeakCustomEndpoint(cleanText, settings)
                if (!customSuccess) {
                    _lastVoiceStatus.value = "Custom voice URL unreachable; fell back to Default Neural Voice."
                    speakWithSystemTts(cleanText, settings.voicePitch, settings.voiceSpeed)
                }
            }
        } else {
            _lastVoiceStatus.value = "Default Realistic Voice active (${_activeVoiceLabel.value})"
            speakWithSystemTts(cleanText, settings.voicePitch, settings.voiceSpeed)
        }
    }

    fun testVoice(settings: SettingsEntity) {
        val sample = "All systems operational, sir. Jarvis vocal matrix calibrated and standing by."
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

    private suspend fun trySpeakCustomEndpoint(text: String, settings: SettingsEntity): Boolean {
        val rawUrl = settings.customVoiceUrl.trim()
        val parsed = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            _lastVoiceStatus.value = "Invalid URL scheme. Only http:// or https:// endpoints are supported."
            return false
        }
        if (parsed.host.isNullOrBlank()) {
            _lastVoiceStatus.value = "Invalid custom voice URL host."
            return false
        }

        if (rawUrl.lowercase(Locale.ROOT).endsWith(".onnx")) {
            _lastVoiceStatus.value = "ONNX model link saved; point to a Piper/Sherpa HTTP TTS server for direct audio synthesis. Using Default Neural Voice."
            return false
        }

        val audioFile = withContext(Dispatchers.IO) {
            fetchCustomTtsAudio(rawUrl, text, settings.voiceSpeed, settings.voicePitch)
        } ?: return false

        return playAudioFile(audioFile)
    }

    private fun fetchCustomTtsAudio(
        endpointUrl: String,
        text: String,
        speed: Float,
        pitch: Float
    ): File? {
        val maxAudioBytes = 10 * 1024 * 1024
        return try {
            val request = if (endpointUrl.contains("{text}")) {
                val encoded = URLEncoder.encode(text, "UTF-8")
                val finalUrl = endpointUrl.replace("{text}", encoded)
                Request.Builder().url(finalUrl).get().build()
            } else {
                val payload = JSONObject().apply {
                    put("text", text)
                    put("input", text)
                    put("speed", speed.toDouble())
                    put("pitch", pitch.toDouble())
                    put("format", "wav")
                }
                Request.Builder()
                    .url(endpointUrl)
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("Accept", "audio/wav, audio/mpeg, audio/ogg, audio/*")
                    .build()
            }

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 405 && !endpointUrl.contains("{text}")) {
                        val separator = if (endpointUrl.contains("?")) "&" else "?"
                        val encoded = URLEncoder.encode(text, "UTF-8")
                        val getReq = Request.Builder()
                            .url("${endpointUrl}${separator}text=$encoded")
                            .get()
                            .build()
                        return httpClient.newCall(getReq).execute().use { getResp ->
                            saveValidatedAudioBody(getResp, maxAudioBytes)
                        }
                    }
                    return null
                }
                saveValidatedAudioBody(response, maxAudioBytes)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveValidatedAudioBody(response: okhttp3.Response, maxAudioBytes: Int): File? {
        if (!response.isSuccessful) return null
        val contentType = response.header("Content-Type")?.lowercase(Locale.ROOT).orEmpty()
        if (contentType.startsWith("text/html") || contentType.startsWith("application/json")) {
            return null
        }
        val body = response.body ?: return null
        val bytes = body.bytes()
        if (bytes.isEmpty() || bytes.size > maxAudioBytes) return null

        val outFile = File(context.cacheDir, "jarvis_custom_tts.audio")
        outFile.writeBytes(bytes)
        return outFile
    }

    private fun playAudioFile(file: File): Boolean {
        return try {
            releaseMediaPlayer()
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    releaseMediaPlayer()
                    dispatchSpeechFinished(expectedUtteranceId = null)
                }
                setOnErrorListener { _, _, _ ->
                    releaseMediaPlayer()
                    dispatchSpeechFinished(expectedUtteranceId = null)
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = player
            activeUtteranceId = null
            _isSpeaking.value = true
            _lastVoiceStatus.value = "Streaming custom open-source voice audio."
            true
        } catch (_: Exception) {
            releaseMediaPlayer()
            false
        }
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.let { player ->
            runCatching {
                if (player.isPlaying) player.stop()
                player.release()
            }
        }
        mediaPlayer = null
    }

    fun stop() {
        customPlaybackJob?.cancel()
        customPlaybackJob = null
        isStreamingSessionOpen = false
        streamInputFinished = false
        streamSentencesQueuedCount = 0
        streamLastUtteranceId = null
        activeStreamSettings = null
        customStreamBuffer.clear()
        activeUtteranceId = null
        onSpeechFinishedCallback = null
        tts?.stop()
        releaseMediaPlayer()
        _isSpeaking.value = false
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
    }
}
