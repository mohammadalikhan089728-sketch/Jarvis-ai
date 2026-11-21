package com.example

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

enum class JarvisPhase {
    IDLE,
    LISTENING,
    THINKING,
    ACTING,
    SPEAKING
}

data class PermissionsSnapshot(
    val microphoneGranted: Boolean = false,
    val accessibilityActive: Boolean = false,
    val overlayGranted: Boolean = false,
    val cameraGranted: Boolean = false,
    val phoneGranted: Boolean = false,
    val contactsGranted: Boolean = false
) {
    val activeCount: Int
        get() = listOf(
            microphoneGranted,
            accessibilityActive,
            overlayGranted,
            cameraGranted,
            phoneGranted,
            contactsGranted
        ).count { it }
}

sealed interface KeyVerificationUiState {
    data object Idle : KeyVerificationUiState
    data object Verifying : KeyVerificationUiState
    data class Verified(val latencyMs: Long) : KeyVerificationUiState
    data class Failed(val reason: String) : KeyVerificationUiState
}

class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val database = JarvisDatabase.getInstance(appContext)
    private val repository = JarvisRepository(database)
    private val geminiApiClient = GeminiApiClient()
    private val offlineProcessor = OfflineCommandProcessor(appContext)
    private val agentEngine = JarvisAgentEngine(repository, geminiApiClient, offlineProcessor)
    val voiceEngine = VoiceEngineManager(appContext)

    private var speechRecognizer: SpeechRecognizer? = null
    private var passiveWakeMode = false
    @Volatile
    private var lastSpeechDetectedTimestamp: Long = 0L
    private var inactivityWatchdogJob: Job? = null
    private var restartListeningJob: Job? = null
    private var activeCommandJob: Job? = null

    private val _isLiveSessionActive = MutableStateFlow(false)
    val isLiveSessionActive: StateFlow<Boolean> = _isLiveSessionActive.asStateFlow()

    private val _inactivityRemainingMs = MutableStateFlow(INACTIVITY_TIMEOUT_MS)
    val inactivityRemainingMs: StateFlow<Long> = _inactivityRemainingMs.asStateFlow()

    private val _isAutoSleepStandby = MutableStateFlow(false)
    val isAutoSleepStandby: StateFlow<Boolean> = _isAutoSleepStandby.asStateFlow()

    private val _phase = MutableStateFlow(JarvisPhase.IDLE)
    val phase: StateFlow<JarvisPhase> = _phase.asStateFlow()

    private val _voiceRmsDb = MutableStateFlow(0f)
    val voiceRmsDb: StateFlow<Float> = _voiceRmsDb.asStateFlow()

    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

    private val _statusBanner = MutableStateFlow("Core online — Tap reactor for live hands-free mode")
    val statusBanner: StateFlow<String> = _statusBanner.asStateFlow()

    private val _keyVerificationState = MutableStateFlow<KeyVerificationUiState>(KeyVerificationUiState.Idle)
    val keyVerificationState: StateFlow<KeyVerificationUiState> = _keyVerificationState.asStateFlow()

    private val _permissionsState = MutableStateFlow(PermissionsSnapshot())
    val permissionsState: StateFlow<PermissionsSnapshot> = _permissionsState.asStateFlow()

    val settings: StateFlow<SettingsEntity> = repository.settingsFlow
        .map { it ?: SettingsEntity() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsEntity())

    val chatHistory: StateFlow<List<ChatHistoryEntity>> = repository.historyFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val macros: StateFlow<List<CustomMacroEntity>> = repository.macrosFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val screenSummary: StateFlow<String> = JarvisAccessibilityService.lastScreenSummary
    val activeVoiceLabel: StateFlow<String> = voiceEngine.activeVoiceLabel
    val lastVoiceStatus: StateFlow<String> = voiceEngine.lastVoiceStatus
    val overlayRunning: StateFlow<Boolean> = FloatingOrbService.isRunning

    val isReadyForAi: StateFlow<Boolean> = settings
        .map { it.geminiApiKey.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            val loaded = repository.ensureDefaultsInitialized()
            refreshPermissions()
            if (loaded.floatingOverlayEnabled && Settings.canDrawOverlays(appContext)) {
                FloatingOrbService.start(appContext)
            }
        }

        // Sync Accessibility Service live status into PermissionsSnapshot
        viewModelScope.launch {
            JarvisAccessibilityService.isServiceRunning.collect {
                refreshPermissions()
            }
        }

        // Sync VoiceEngine speaking state into JarvisPhase without interfering with live turn-taking
        viewModelScope.launch {
            voiceEngine.isSpeaking.collect { speaking ->
                if (speaking) {
                    _phase.value = JarvisPhase.SPEAKING
                } else if (_phase.value == JarvisPhase.SPEAKING && !_isLiveSessionActive.value) {
                    _phase.value = JarvisPhase.IDLE
                    maybeResumeWakeWordLoop()
                }
            }
        }
    }

    fun refreshPermissions() {
        val mic = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val cam = ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val phone = ContextCompat.checkSelfPermission(appContext, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val contacts = ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(appContext)
        val accessibility = isAccessibilityServiceEnabled()

        _permissionsState.value = PermissionsSnapshot(
            microphoneGranted = mic,
            accessibilityActive = accessibility,
            overlayGranted = overlay,
            cameraGranted = cam,
            phoneGranted = phone,
            contactsGranted = contacts
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (JarvisAccessibilityService.isServiceRunning.value) return true
        val enabledServices = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(appContext.packageName) &&
            enabledServices.contains(JarvisAccessibilityService::class.java.simpleName)
    }

    fun toggleListening() {
        if (_isLiveSessionActive.value || _phase.value == JarvisPhase.LISTENING || _phase.value == JarvisPhase.SPEAKING) {
            stopLiveSessionManual()
        } else {
            startLiveSession()
        }
    }

    fun startLiveSession() {
        refreshPermissions()
        if (!_permissionsState.value.microphoneGranted) {
            _statusBanner.value = "Microphone permission required. Grant it in Systems."
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            _statusBanner.value = "Speech recognition service unavailable on this device."
            return
        }

        restartListeningJob?.cancel()
        activeCommandJob?.cancel()
        voiceEngine.stop()

        _isAutoSleepStandby.value = false
        _isLiveSessionActive.value = true
        lastSpeechDetectedTimestamp = SystemClock.elapsedRealtime()
        _inactivityRemainingMs.value = INACTIVITY_TIMEOUT_MS
        _statusBanner.value = "LIVE CONVERSATION ACTIVE"
        _liveTranscript.value = "Listening continuously…"

        startInactivityWatchdog()
        beginRecognitionWindow(wakeWordOnly = false)
    }

    fun stopLiveSessionManual() {
        inactivityWatchdogJob?.cancel()
        inactivityWatchdogJob = null
        restartListeningJob?.cancel()
        restartListeningJob = null
        activeCommandJob?.cancel()
        activeCommandJob = null

        _isLiveSessionActive.value = false
        _isAutoSleepStandby.value = false
        _inactivityRemainingMs.value = 0L
        passiveWakeMode = false

        pauseSpeechRecognizer()
        voiceEngine.stop()
        _phase.value = JarvisPhase.IDLE
        _statusBanner.value = "STANDBY"
    }

    private fun startInactivityWatchdog() {
        inactivityWatchdogJob?.cancel()
        inactivityWatchdogJob = viewModelScope.launch {
            while (isActive && _isLiveSessionActive.value) {
                val elapsed = SystemClock.elapsedRealtime() - lastSpeechDetectedTimestamp
                val remaining = (INACTIVITY_TIMEOUT_MS - elapsed).coerceAtLeast(0L)
                _inactivityRemainingMs.value = remaining
                if (elapsed >= INACTIVITY_TIMEOUT_MS) {
                    triggerAutoSleep()
                    break
                }
                delay(250L)
            }
        }
    }

    private fun triggerAutoSleep() {
        inactivityWatchdogJob?.cancel()
        inactivityWatchdogJob = null
        restartListeningJob?.cancel()
        restartListeningJob = null
        activeCommandJob?.cancel()
        activeCommandJob = null

        pauseSpeechRecognizer()
        passiveWakeMode = false
        _isLiveSessionActive.value = false
        _isAutoSleepStandby.value = true
        _inactivityRemainingMs.value = 0L
        _liveTranscript.value = ""
        _phase.value = JarvisPhase.IDLE
        _statusBanner.value = "STANDBY (AUTO-SLEEP)"

        voiceEngine.speak(
            text = "No voice detected for 3 minutes. Entering standby mode.",
            settings = settings.value
        ) {
            _phase.value = JarvisPhase.IDLE
        }
    }

    private fun isInactivityExpired(): Boolean {
        return (SystemClock.elapsedRealtime() - lastSpeechDetectedTimestamp) >= INACTIVITY_TIMEOUT_MS
    }

    private fun markUserSpeechDetected() {
        lastSpeechDetectedTimestamp = SystemClock.elapsedRealtime()
        if (_isLiveSessionActive.value) {
            _inactivityRemainingMs.value = INACTIVITY_TIMEOUT_MS
        }
    }

    private fun scheduleLiveRestart(delayMs: Long) {
        restartListeningJob?.cancel()
        if (!_isLiveSessionActive.value) return
        if (isInactivityExpired()) {
            triggerAutoSleep()
            return
        }
        restartListeningJob = viewModelScope.launch {
            delay(delayMs)
            if (!_isLiveSessionActive.value) return@launch
            if (isInactivityExpired()) {
                triggerAutoSleep()
                return@launch
            }
            if (!voiceEngine.isSpeaking.value &&
                _phase.value != JarvisPhase.THINKING &&
                _phase.value != JarvisPhase.ACTING
            ) {
                beginRecognitionWindow(wakeWordOnly = false)
            }
        }
    }

    fun startVoiceListening(wakeWordOnly: Boolean = false) {
        if (!wakeWordOnly) {
            startLiveSession()
        } else {
            beginRecognitionWindow(wakeWordOnly = true)
        }
    }

    private fun beginRecognitionWindow(wakeWordOnly: Boolean) {
        refreshPermissions()
        if (!_permissionsState.value.microphoneGranted) {
            if (_isLiveSessionActive.value) stopLiveSessionManual()
            _statusBanner.value = "Microphone permission required. Grant it in Systems."
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            if (_isLiveSessionActive.value) stopLiveSessionManual()
            _statusBanner.value = "Speech recognition service unavailable on this device."
            return
        }

        pauseSpeechRecognizer()
        passiveWakeMode = wakeWordOnly
        if (wakeWordOnly && !_isLiveSessionActive.value) {
            _liveTranscript.value = "Awaiting wake phrase \"Hey Jarvis\"…"
            _statusBanner.value = "Wake-word standby active"
        } else if (_isLiveSessionActive.value) {
            _statusBanner.value = "LIVE • Listening hands-free"
        }
        _phase.value = JarvisPhase.LISTENING

        runCatching {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (_isLiveSessionActive.value || passiveWakeMode) {
                        _phase.value = JarvisPhase.LISTENING
                    }
                }

                override fun onBeginningOfSpeech() {
                    markUserSpeechDetected()
                    _statusBanner.value = "LIVE • Receiving voice input…"
                }

                override fun onRmsChanged(rmsdB: Float) {
                    _voiceRmsDb.value = rmsdB.coerceIn(0f, 12f)
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    _voiceRmsDb.value = 0f
                }

                override fun onError(error: Int) {
                    _voiceRmsDb.value = 0f
                    pauseSpeechRecognizer()

                    if (_isLiveSessionActive.value) {
                        if (isInactivityExpired()) {
                            triggerAutoSleep()
                            return
                        }
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                            SpeechRecognizer.ERROR_CLIENT -> {
                                // Silently and immediately restart without popups, errors, or beeps
                                _phase.value = JarvisPhase.LISTENING
                                scheduleLiveRestart(SILENT_RESTART_DELAY_MS)
                            }
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                stopLiveSessionManual()
                                _statusBanner.value = "Microphone permission denied."
                            }
                            else -> {
                                // Recover gracefully during continuous hands-free mode
                                _phase.value = JarvisPhase.LISTENING
                                scheduleLiveRestart(450L)
                            }
                        }
                        return
                    }

                    _phase.value = JarvisPhase.IDLE
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "Speech recognition network error."
                        else -> "Voice listener paused (code $error)."
                    }
                    _statusBanner.value = msg
                    if (passiveWakeMode && settings.value.wakeWordEnabled) {
                        viewModelScope.launch {
                            delay(900L)
                            if (settings.value.wakeWordEnabled && _phase.value == JarvisPhase.IDLE && !_isLiveSessionActive.value) {
                                beginRecognitionWindow(wakeWordOnly = true)
                            }
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    _voiceRmsDb.value = 0f
                    // Pause/tear down SpeechRecognizer immediately so JARVIS never hears its own TTS output
                    pauseSpeechRecognizer()

                    val spoken = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()

                    if (spoken.isEmpty()) {
                        if (_isLiveSessionActive.value) {
                            scheduleLiveRestart(SILENT_RESTART_DELAY_MS)
                        } else {
                            _phase.value = JarvisPhase.IDLE
                            maybeResumeWakeWordLoop()
                        }
                        return
                    }

                    markUserSpeechDetected()

                    if (passiveWakeMode && !_isLiveSessionActive.value) {
                        val lower = spoken.lowercase(Locale.ROOT)
                        val wakeIndex = when {
                            lower.contains("hey jarvis") -> lower.indexOf("hey jarvis") + "hey jarvis".length
                            lower.contains("jarvis") -> lower.indexOf("jarvis") + "jarvis".length
                            else -> -1
                        }
                        if (wakeIndex >= 0) {
                            val extracted = spoken.substring(wakeIndex).trim().trimStart(',', ':', ' ')
                            if (extracted.isNotEmpty()) {
                                _liveTranscript.value = extracted
                                submitCommand(extracted)
                            } else {
                                _isAutoSleepStandby.value = false
                                _isLiveSessionActive.value = true
                                lastSpeechDetectedTimestamp = SystemClock.elapsedRealtime()
                                _inactivityRemainingMs.value = INACTIVITY_TIMEOUT_MS
                                startInactivityWatchdog()
                                voiceEngine.speak("Yes, sir. Live mode engaged.", settings.value) {
                                    scheduleLiveRestart(POST_SPEECH_RESTART_DELAY_MS)
                                }
                            }
                        } else {
                            _phase.value = JarvisPhase.IDLE
                            maybeResumeWakeWordLoop()
                        }
                    } else {
                        _liveTranscript.value = spoken
                        submitCommand(spoken)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (partial.isNotEmpty()) {
                        markUserSpeechDetected()
                        _liveTranscript.value = partial
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer = recognizer
            recognizer.startListening(intent)
        }.onFailure { err ->
            if (_isLiveSessionActive.value) {
                scheduleLiveRestart(450L)
            } else {
                _phase.value = JarvisPhase.IDLE
                _statusBanner.value = err.localizedMessage ?: "Failed to open microphone stream."
            }
        }
    }

    private fun pauseSpeechRecognizer() {
        _voiceRmsDb.value = 0f
        runCatching {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
    }

    fun stopListening() {
        stopLiveSessionManual()
    }

    fun stopSpeaking() {
        if (_isLiveSessionActive.value) {
            stopLiveSessionManual()
        } else {
            voiceEngine.stop()
            if (_phase.value == JarvisPhase.SPEAKING) {
                _phase.value = JarvisPhase.IDLE
            }
        }
    }

    fun submitCommand(commandText: String) {
        val clean = commandText.trim()
        if (clean.isEmpty()) return

        // Temporarily pause recognizer while processing & speaking; preserve isLiveSessionActive for turn-taking
        restartListeningJob?.cancel()
        pauseSpeechRecognizer()
        _isAutoSleepStandby.value = false

        activeCommandJob?.cancel()
        activeCommandJob = viewModelScope.launch {
            val currentSettings = repository.getSettings()
            _liveTranscript.value = clean
            _statusBanner.value = "Processing command…"
            _phase.value = JarvisPhase.THINKING

            val result = agentEngine.processCommand(
                rawQuery = clean,
                settings = currentSettings,
                onPhaseUpdate = { updatedPhase -> _phase.value = updatedPhase }
            )

            _statusBanner.value = "${result.executedAction} • ${result.latencyMs}ms"
            _phase.value = JarvisPhase.SPEAKING

            voiceEngine.speak(result.reply, currentSettings) {
                if (_isLiveSessionActive.value) {
                    if (isInactivityExpired()) {
                        triggerAutoSleep()
                    } else {
                        scheduleLiveRestart(POST_SPEECH_RESTART_DELAY_MS)
                    }
                } else {
                    _phase.value = JarvisPhase.IDLE
                    maybeResumeWakeWordLoop()
                }
            }
        }
    }

    fun saveSettings(updated: SettingsEntity) {
        viewModelScope.launch {
            repository.saveSettings(updated)
            _statusBanner.value = "Configuration committed to local Room vault"

            // Sync Floating Overlay Orb service
            if (updated.floatingOverlayEnabled && Settings.canDrawOverlays(appContext)) {
                FloatingOrbService.start(appContext)
            } else {
                FloatingOrbService.stop(appContext)
            }

            // Sync Wake-Word Listening toggle
            if (updated.wakeWordEnabled && _permissionsState.value.microphoneGranted && _phase.value == JarvisPhase.IDLE) {
                startVoiceListening(wakeWordOnly = true)
            } else if (!updated.wakeWordEnabled && passiveWakeMode) {
                stopListening()
            }
        }
    }

    fun verifyGeminiApiKey(apiKey: String) {
        viewModelScope.launch {
            _keyVerificationState.value = KeyVerificationUiState.Verifying
            when (val res = geminiApiClient.verifyApiKey(apiKey)) {
                is KeyVerificationResult.Valid -> {
                    _keyVerificationState.value = KeyVerificationUiState.Verified(res.latencyMs)
                }
                is KeyVerificationResult.Invalid -> {
                    _keyVerificationState.value = KeyVerificationUiState.Failed(res.message)
                }
            }
        }
    }

    fun resetVerificationState() {
        _keyVerificationState.value = KeyVerificationUiState.Idle
    }

    fun testVoice(previewSettings: SettingsEntity) {
        voiceEngine.testVoice(previewSettings)
    }

    fun addCustomMacro(
        triggerPhrase: String,
        actionType: String,
        actionPayload: String,
        spokenReply: String
    ) {
        if (triggerPhrase.isBlank() || actionPayload.isBlank()) return
        viewModelScope.launch {
            repository.addMacro(
                CustomMacroEntity(
                    triggerPhrase = triggerPhrase.trim(),
                    actionType = actionType.trim().uppercase(Locale.ROOT),
                    actionPayload = actionPayload.trim(),
                    spokenReply = spokenReply.trim()
                )
            )
            _statusBanner.value = "Local macro \"$triggerPhrase\" stored"
        }
    }

    fun deleteCustomMacro(macro: CustomMacroEntity) {
        viewModelScope.launch {
            repository.deleteMacro(macro)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
            _statusBanner.value = "Local telemetry logs cleared"
        }
    }

    fun scanCurrentScreenNow() {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            _statusBanner.value = "Enable Jarvis Accessibility Service to scan screens"
            return
        }
        val snapshot = service.readScreenHierarchy()
        _statusBanner.value = snapshot?.let {
            "Scanned ${it.nodes.size} UI nodes in ${it.packageName}"
        } ?: "No active window nodes returned"
    }

    fun runDirectGestureTest(gestureName: String) {
        viewModelScope.launch {
            val service = JarvisAccessibilityService.instance
            if (service == null) {
                _statusBanner.value = "Activate Accessibility Service to run gesture tests"
                return@launch
            }
            _phase.value = JarvisPhase.ACTING
            val ok = when (gestureName) {
                "SCROLL_DOWN" -> service.performDirectionalScroll("SCROLL_DOWN")
                "DRAG_DIAGONAL" -> service.gestureEngine.dragByAngle(angleDegrees = 315f, distancePx = 320f)
                "PINCH_IN" -> service.gestureEngine.pinchZoomOut()
                "PINCH_OUT" -> service.gestureEngine.pinchZoomIn()
                "ROTATE" -> service.gestureEngine.twoFingerRotate(degrees = 45f)
                "MULTI_TAP" -> {
                    val b = service.gestureEngine.getScreenBounds()
                    service.gestureEngine.simultaneousMultiTap(
                        listOf(
                            (b.widthPx * 0.25f) to (b.heightPx * 0.65f),
                            (b.widthPx * 0.75f) to (b.heightPx * 0.65f)
                        )
                    )
                }
                else -> false
            }
            _phase.value = JarvisPhase.IDLE
            _statusBanner.value = if (ok) "Gesture $gestureName dispatched" else "Gesture $gestureName cancelled"
        }
    }

    private fun maybeResumeWakeWordLoop() {
        val current = settings.value
        if (current.wakeWordEnabled &&
            !_isLiveSessionActive.value &&
            _permissionsState.value.microphoneGranted &&
            _phase.value == JarvisPhase.IDLE
        ) {
            viewModelScope.launch {
                delay(700L)
                if (settings.value.wakeWordEnabled &&
                    !_isLiveSessionActive.value &&
                    _phase.value == JarvisPhase.IDLE
                ) {
                    beginRecognitionWindow(wakeWordOnly = true)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        inactivityWatchdogJob?.cancel()
        restartListeningJob?.cancel()
        activeCommandJob?.cancel()
        pauseSpeechRecognizer()
        voiceEngine.shutdown()
    }

    companion object {
        const val INACTIVITY_TIMEOUT_MS = 180_000L
        const val POST_SPEECH_RESTART_DELAY_MS = 250L
        const val SILENT_RESTART_DELAY_MS = 180L
    }
}
