package com.example

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * Persistent Foreground Voice Assistant Service for JARVIS (Developed by Mozahid).
 * Keeps the continuous live voice conversation loop running seamlessly even after JARVIS executes
 * GLOBAL_ACTION_HOME or launches YouTube, WhatsApp, Instagram, or Settings.
 *
 * Displays a compact, draggable Floating Arc Reactor Mini-Bubble (SYSTEM_ALERT_WINDOW) with live
 * phase status ("Listening...", "Thinking...", "Executing...", "Speaking...") and a Stop button.
 * Coordinates microphone access exclusively through MicCoordinator.
 */
class JarvisActiveAssistantService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var repository: JarvisRepository
    private val aiClient = GeminiApiClient()
    private lateinit var offlineProcessor: OfflineCommandProcessor
    private lateinit var agentEngine: JarvisAgentEngine
    private lateinit var voiceEngine: VoiceEngineManager

    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecognizerListening = false
    private var lastSpeechElapsedMs: Long = 0L

    private var inactivityWatchdogJob: Job? = null
    private var restartListeningJob: Job? = null
    private var activeCommandJob: Job? = null

    // Floating Arc Reactor Mini-Bubble overlay components
    private var windowManager: WindowManager? = null
    private var miniBubbleRoot: View? = null
    private var miniReactorIcon: MiniArcReactorView? = null
    private var miniStatusText: TextView? = null

    private val speechPauseListener: () -> Unit = {
        stopRecognizerWindowOnly()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        repository = JarvisRepository(JarvisDatabase.getInstance(applicationContext))
        offlineProcessor = OfflineCommandProcessor(applicationContext)
        agentEngine = JarvisAgentEngine(applicationContext, repository, aiClient, offlineProcessor)
        voiceEngine = VoiceEngineManager(applicationContext, aiClient)

        MicCoordinator.registerSpeechPauseListener(speechPauseListener)

        serviceScope.launch {
            voiceEngine.activeVoiceLabel.collect { _activeVoiceLabel.value = it }
        }
        serviceScope.launch {
            voiceEngine.lastVoiceStatus.collect { _lastVoiceStatus.value = it }
        }
        serviceScope.launch {
            _phase.collect { phase ->
                updateMiniBubbleUi(phase)
                updateForegroundNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SESSION -> {
                stopSessionAndSelf(speakGoodbye = false)
                return START_NOT_STICKY
            }
            ACTION_STOP_SPEECH -> {
                voiceEngine.stop()
                if (_isSessionActive.value) {
                    scheduleLiveRestart(MicCoordinator.POST_SPEECH_RESTART_DELAY_MS)
                } else {
                    _phase.value = JarvisPhase.IDLE
                }
                return START_STICKY
            }
            ACTION_TEST_VOICE -> {
                serviceScope.launch {
                    val currentSettings = repository.getSettings()
                    voiceEngine.testVoice(currentSettings)
                }
                return START_NOT_STICKY
            }
            ACTION_SUBMIT_COMMAND -> {
                val cmd = intent.getStringExtra(EXTRA_COMMAND_TEXT)?.trim().orEmpty()
                if (cmd.isNotEmpty()) {
                    if (_isSessionActive.value) {
                        ensureForegroundStarted()
                        submitCommandInternal(cmd)
                    } else {
                        // One-shot command execution (doesn't require continuous loop unless already active)
                        submitCommandInternal(cmd)
                    }
                }
                return if (_isSessionActive.value) START_STICKY else START_NOT_STICKY
            }
            else -> {
                val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (!hasMic) {
                    _statusBanner.value = "Microphone permission required. Grant it in Systems."
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!ensureForegroundStarted()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val initialCmd = intent?.getStringExtra(EXTRA_COMMAND_TEXT)?.trim().orEmpty()
                startContinuousSession(initialCommand = initialCmd.takeIf { it.isNotEmpty() })
                return START_STICKY
            }
        }
    }

    private fun ensureForegroundStarted(): Boolean {
        val notification = buildForegroundNotification()
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        }.getOrDefault(false)
    }

    private fun startContinuousSession(initialCommand: String? = null) {
        restartListeningJob?.cancel()
        activeCommandJob?.cancel()
        voiceEngine.stop()

        _isAutoSleepStandby.value = false
        _isSessionActive.value = true
        MicCoordinator.setActiveAssistantRunning(true)
        lastSpeechElapsedMs = SystemClock.elapsedRealtime()
        _inactivityRemainingMs.value = INACTIVITY_TIMEOUT_MS
        _statusBanner.value = "LIVE CONVERSATION ACTIVE"
        _liveTranscript.value = "Listening continuously…"

        attachFloatingMiniBubbleIfPermitted()
        startInactivityWatchdog()

        if (!initialCommand.isNullOrBlank()) {
            submitCommandInternal(initialCommand)
        } else {
            beginRecognitionWindow()
        }
    }

    private fun stopSessionAndSelf(speakGoodbye: Boolean) {
        inactivityWatchdogJob?.cancel()
        inactivityWatchdogJob = null
        restartListeningJob?.cancel()
        restartListeningJob = null
        activeCommandJob?.cancel()
        activeCommandJob = null
        JarvisAccessibilityService.requestStopMultiStep()

        _isSessionActive.value = false
        _inactivityRemainingMs.value = 0L
        stopRecognizerWindowOnly()
        destroySpeechRecognizer()
        removeFloatingMiniBubble()
        MicCoordinator.setActiveAssistantRunning(false)
        MicCoordinator.release(MicOwner.ACTIVE_ASSISTANT)

        if (speakGoodbye) {
            serviceScope.launch {
                val settings = repository.getSettings()
                _phase.value = JarvisPhase.SPEAKING
                _statusBanner.value = "STANDBY"
                voiceEngine.speak("Standing by, sir.", settings) {
                    _phase.value = JarvisPhase.IDLE
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        } else {
            voiceEngine.stop()
            _phase.value = JarvisPhase.IDLE
            _statusBanner.value = "STANDBY"
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun triggerAutoSleep() {
        inactivityWatchdogJob?.cancel()
        inactivityWatchdogJob = null
        restartListeningJob?.cancel()
        restartListeningJob = null
        activeCommandJob?.cancel()
        activeCommandJob = null

        stopRecognizerWindowOnly()
        destroySpeechRecognizer()
        _isSessionActive.value = false
        _isAutoSleepStandby.value = true
        _inactivityRemainingMs.value = 0L
        _liveTranscript.value = ""
        _phase.value = JarvisPhase.IDLE
        _statusBanner.value = "STANDBY (AUTO-SLEEP)"
        removeFloatingMiniBubble()
        MicCoordinator.setActiveAssistantRunning(false)
        MicCoordinator.release(MicOwner.ACTIVE_ASSISTANT)

        serviceScope.launch {
            val settings = repository.getSettings()
            voiceEngine.speak(
                text = "No voice detected for 3 minutes. JARVIS entering standby mode.",
                settings = settings
            ) {
                _phase.value = JarvisPhase.IDLE
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun startInactivityWatchdog() {
        inactivityWatchdogJob?.cancel()
        inactivityWatchdogJob = serviceScope.launch {
            while (isActive && _isSessionActive.value) {
                val elapsed = SystemClock.elapsedRealtime() - lastSpeechElapsedMs
                val remaining = (INACTIVITY_TIMEOUT_MS - elapsed).coerceAtLeast(0L)
                _inactivityRemainingMs.value = remaining
                if (elapsed >= INACTIVITY_TIMEOUT_MS && !MicCoordinator.isJarvisSpeaking.value) {
                    triggerAutoSleep()
                    break
                }
                delay(250L)
            }
        }
    }

    private fun isInactivityExpired(): Boolean {
        return (SystemClock.elapsedRealtime() - lastSpeechElapsedMs) >= INACTIVITY_TIMEOUT_MS
    }

    private fun markSpeechActivity() {
        lastSpeechElapsedMs = SystemClock.elapsedRealtime()
        if (_isSessionActive.value) {
            _inactivityRemainingMs.value = INACTIVITY_TIMEOUT_MS
        }
    }

    private fun scheduleLiveRestart(delayMs: Long) {
        restartListeningJob?.cancel()
        if (!_isSessionActive.value) return
        if (isInactivityExpired()) {
            triggerAutoSleep()
            return
        }
        restartListeningJob = serviceScope.launch {
            delay(delayMs)
            if (!_isSessionActive.value) return@launch
            if (isInactivityExpired()) {
                triggerAutoSleep()
                return@launch
            }
            if (!MicCoordinator.isJarvisSpeaking.value &&
                !MicCoordinator.isIncomingCallActive.value &&
                _phase.value != JarvisPhase.THINKING &&
                _phase.value != JarvisPhase.ACTING
            ) {
                beginRecognitionWindow()
            } else {
                scheduleLiveRestart(MicCoordinator.ERROR_RESTART_DEBOUNCE_MS)
            }
        }
    }

    private fun beginRecognitionWindow() {
        if (!_isSessionActive.value) return
        if (MicCoordinator.isJarvisSpeaking.value || MicCoordinator.isIncomingCallActive.value) {
            scheduleLiveRestart(MicCoordinator.ERROR_RESTART_DEBOUNCE_MS)
            return
        }

        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasMic || !SpeechRecognizer.isRecognitionAvailable(this)) {
            _statusBanner.value = "Speech recognition unavailable."
            stopSessionAndSelf(speakGoodbye = false)
            return
        }

        stopRecognizerWindowOnly()
        _phase.value = JarvisPhase.LISTENING
        _statusBanner.value = "LIVE • Listening hands-free"

        runCatching {
            val recognizer = speechRecognizer ?: SpeechRecognizerFactory.createSafeRecognizer(this).also {
                it.setRecognitionListener(recognitionListener)
                speechRecognizer = it
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MS)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    POSSIBLY_COMPLETE_SILENCE_MS
                )
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
            }

            val started = MicCoordinator.startListeningSilently(
                context = this,
                owner = MicOwner.ACTIVE_ASSISTANT,
                recognizer = recognizer,
                intent = intent
            )
            isRecognizerListening = started
            if (!started) {
                scheduleLiveRestart(MicCoordinator.ERROR_RESTART_DEBOUNCE_MS)
            }
        }.onFailure {
            isRecognizerListening = false
            destroySpeechRecognizer()
            scheduleLiveRestart(500L)
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (_isSessionActive.value) {
                _phase.value = JarvisPhase.LISTENING
            }
        }

        override fun onBeginningOfSpeech() {
            markSpeechActivity()
            _statusBanner.value = "LIVE • Hearing you…"
        }

        override fun onRmsChanged(rmsdB: Float) {
            _voiceRmsDb.value = rmsdB.coerceIn(0f, 12f)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            _voiceRmsDb.value = 0f
            isRecognizerListening = false
        }

        override fun onError(error: Int) {
            _voiceRmsDb.value = 0f
            isRecognizerListening = false
            MicCoordinator.release(MicOwner.ACTIVE_ASSISTANT)

            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                destroySpeechRecognizer()
            } else {
                stopRecognizerWindowOnly()
            }

            if (!_isSessionActive.value) return
            if (isInactivityExpired()) {
                triggerAutoSleep()
                return
            }

            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    _phase.value = JarvisPhase.LISTENING
                    scheduleLiveRestart(MicCoordinator.ERROR_RESTART_DEBOUNCE_MS)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    _statusBanner.value = "Microphone permission denied."
                    stopSessionAndSelf(speakGoodbye = false)
                }
                else -> {
                    _phase.value = JarvisPhase.LISTENING
                    scheduleLiveRestart(600L)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            _voiceRmsDb.value = 0f
            isRecognizerListening = false
            stopRecognizerWindowOnly()
            MicCoordinator.release(MicOwner.ACTIVE_ASSISTANT)

            val spoken = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()

            if (spoken.isEmpty()) {
                if (_isSessionActive.value) {
                    scheduleLiveRestart(MicCoordinator.ERROR_RESTART_DEBOUNCE_MS)
                }
                return
            }

            markSpeechActivity()
            _liveTranscript.value = spoken

            if (isStopListeningCommand(spoken)) {
                stopSessionAndSelf(speakGoodbye = true)
                return
            }

            submitCommandInternal(spoken)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (partial.isNotEmpty()) {
                markSpeechActivity()
                _liveTranscript.value = partial
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun isStopListeningCommand(spoken: String): Boolean {
        val lower = spoken.lowercase(Locale.ROOT)
        return lower == "stop" ||
            lower.contains("stop listening") ||
            lower.contains("sunna band") ||
            lower.contains("band karo") ||
            lower.contains("bas karo") ||
            lower.contains("go to sleep") ||
            lower.contains("jarvis sleep") ||
            lower.contains("jarvis stop")
    }

    private fun submitCommandInternal(commandText: String) {
        val clean = commandText.trim()
        if (clean.isEmpty()) return

        restartListeningJob?.cancel()
        stopRecognizerWindowOnly()
        MicCoordinator.release(MicOwner.ACTIVE_ASSISTANT)
        _isAutoSleepStandby.value = false

        activeCommandJob?.cancel()
        activeCommandJob = serviceScope.launch {
            val currentSettings = repository.getSettings()
            _liveTranscript.value = clean
            _statusBanner.value = "Thinking…"
            _phase.value = JarvisPhase.THINKING

            val result = agentEngine.processCommand(
                rawQuery = clean,
                settings = currentSettings,
                onPhaseUpdate = { updatedPhase -> _phase.value = updatedPhase },
                onStatusUpdate = { status -> _statusBanner.value = status },
                onStreamStart = {
                    voiceEngine.beginStreamingSpeech(
                        settings = currentSettings,
                        onAllFinished = { handlePostSpeechTurnTaking() }
                    )
                },
                onStreamSentence = { sentence ->
                    voiceEngine.enqueueStreamSentence(sentence)
                },
                onStreamEnd = {
                    voiceEngine.finishStreamingSpeech()
                }
            )

            val cleanReply = JarvisResponseSanitizer.extractCleanReply(result.reply)
            _statusBanner.value = "${result.executedAction} • ${result.latencyMs}ms"

            if (!result.alreadyStreamSpoken) {
                _phase.value = JarvisPhase.SPEAKING
                voiceEngine.speak(cleanReply, currentSettings) {
                    handlePostSpeechTurnTaking()
                }
            }
        }
    }

    private fun handlePostSpeechTurnTaking() {
        if (_isSessionActive.value) {
            markSpeechActivity()
            scheduleLiveRestart(MicCoordinator.POST_SPEECH_RESTART_DELAY_MS)
        } else {
            _phase.value = JarvisPhase.IDLE
        }
    }

    // ============================================================================================
    // FLOATING ARC REACTOR MINI-BUBBLE OVERLAY (PERSISTS ACROSS HOME SCREEN & ALL OTHER APPS)
    // ============================================================================================

    private fun attachFloatingMiniBubbleIfPermitted() {
        if (miniBubbleRoot != null) {
            updateMiniBubbleUi(_phase.value)
            return
        }
        if (!Settings.canDrawOverlays(this)) return

        val wm = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also {
            windowManager = it
        }
        val density = resources.displayMetrics.density

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (10 * density).toInt(),
                (7 * density).toInt(),
                (10 * density).toInt(),
                (7 * density).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f * density
                setColor(Color.parseColor("#EC07111F"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#00E5FF"))
            }
            elevation = 12f * density
        }

        val reactor = MiniArcReactorView(this).apply {
            val sz = (32 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz)
        }
        miniReactorIcon = reactor
        container.addView(reactor)

        val statusTv = TextView(this).apply {
            text = "Listening…"
            setTextColor(Color.parseColor("#E6FAFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            maxLines = 1
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (8 * density).toInt()
                marginEnd = (10 * density).toInt()
            }
            layoutParams = lp
        }
        miniStatusText = statusTv
        container.addView(statusTv)

        val stopBtn = TextView(this).apply {
            text = "STOP"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(
                (10 * density).toInt(),
                (5 * density).toInt(),
                (10 * density).toInt(),
                (5 * density).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f * density
                setColor(Color.parseColor("#D92649"))
            }
            setOnClickListener {
                stopSessionAndSelf(speakGoodbye = false)
            }
        }
        container.addView(stopBtn)

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).toInt()
            y = (110 * density).toInt()
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    runCatching { wm.updateViewLayout(container, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.rawX - touchX)
                    val dy = abs(event.rawY - touchY)
                    if (dx < 14f && dy < 14f) {
                        val openHud = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        runCatching { startActivity(openHud) }
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            wm.addView(container, params)
            miniBubbleRoot = container
            updateMiniBubbleUi(_phase.value)
        }
    }

    private fun updateMiniBubbleUi(phase: JarvisPhase) {
        mainHandler.post {
            val label = when (phase) {
                JarvisPhase.LISTENING -> "Listening…"
                JarvisPhase.THINKING -> "Thinking…"
                JarvisPhase.ACTING -> "Executing…"
                JarvisPhase.SPEAKING -> "Speaking…"
                JarvisPhase.IDLE -> "JARVIS Live"
            }
            val colorInt = when (phase) {
                JarvisPhase.LISTENING -> Color.parseColor("#00E5FF")
                JarvisPhase.THINKING -> Color.parseColor("#FFB300")
                JarvisPhase.ACTING -> Color.parseColor("#38BDF8")
                JarvisPhase.SPEAKING -> Color.parseColor("#22C55E")
                JarvisPhase.IDLE -> Color.parseColor("#00E5FF")
            }
            miniStatusText?.text = label
            miniReactorIcon?.setRingColor(colorInt)
        }
    }

    private fun removeFloatingMiniBubble() {
        mainHandler.post {
            miniBubbleRoot?.let { v -> runCatching { windowManager?.removeView(v) } }
            miniBubbleRoot = null
            miniReactorIcon = null
            miniStatusText = null
        }
    }

    private class MiniArcReactorView(context: Context) : View(context) {
        private var ringColor: Int = Color.parseColor("#00E5FF")
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = ringColor
        }
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        fun setRingColor(color: Int) {
            ringColor = color
            ringPaint.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = (minOf(width, height) / 2f) - 3f
            corePaint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(Color.WHITE, ringColor, Color.parseColor("#07111F")),
                floatArrayOf(0.15f, 0.55f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r, corePaint)
            canvas.drawCircle(cx, cy, r * 0.85f, ringPaint)
        }
    }

    // ============================================================================================
    // RECOGNIZER & NOTIFICATION LIFECYCLE
    // ============================================================================================

    private fun stopRecognizerWindowOnly() {
        _voiceRmsDb.value = 0f
        if (isRecognizerListening) {
            runCatching {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            }
            isRecognizerListening = false
        }
    }

    private fun destroySpeechRecognizer() {
        _voiceRmsDb.value = 0f
        isRecognizerListening = false
        runCatching {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Live Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps JARVIS hands-free voice assistant active across all apps."
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateForegroundNotification() {
        if (!_isSessionActive.value) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildForegroundNotification())
    }

    private fun buildForegroundNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPi = PendingIntent.getActivity(
            this,
            9201,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, JarvisActiveAssistantService::class.java).apply {
            action = ACTION_STOP_SESSION
        }
        val stopPi = PendingIntent.getService(
            this,
            9202,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val phaseLabel = when (_phase.value) {
            JarvisPhase.LISTENING -> "Listening hands-free across apps…"
            JarvisPhase.THINKING -> "Thinking…"
            JarvisPhase.ACTING -> "Executing phone action…"
            JarvisPhase.SPEAKING -> "Speaking…"
            JarvisPhase.IDLE -> "Ready"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("JARVIS Live Assistant")
            .setContentText("$phaseLabel • Developed by Mozahid")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPi)
            .addAction(0, "Stop Listening", stopPi)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        MicCoordinator.unregisterSpeechPauseListener(speechPauseListener)
        inactivityWatchdogJob?.cancel()
        restartListeningJob?.cancel()
        activeCommandJob?.cancel()
        destroySpeechRecognizer()
        removeFloatingMiniBubble()
        voiceEngine.shutdown()
        _isSessionActive.value = false
        _phase.value = JarvisPhase.IDLE
        MicCoordinator.setActiveAssistantRunning(false)
        MicCoordinator.release(MicOwner.ACTIVE_ASSISTANT)
        serviceScope.cancel()
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_active_assistant_channel"
        private const val NOTIFICATION_ID = 4201

        const val ACTION_START_SESSION = "com.example.jarvis.ACTION_START_ACTIVE_SESSION"
        const val ACTION_STOP_SESSION = "com.example.jarvis.ACTION_STOP_ACTIVE_SESSION"
        const val ACTION_STOP_SPEECH = "com.example.jarvis.ACTION_STOP_ACTIVE_SPEECH"
        const val ACTION_SUBMIT_COMMAND = "com.example.jarvis.ACTION_SUBMIT_ACTIVE_COMMAND"
        const val ACTION_TEST_VOICE = "com.example.jarvis.ACTION_TEST_ACTIVE_VOICE"
        const val EXTRA_COMMAND_TEXT = "com.example.jarvis.EXTRA_COMMAND_TEXT"

        const val INACTIVITY_TIMEOUT_MS = 180_000L
        const val COMPLETE_SILENCE_MS = 700L
        const val POSSIBLY_COMPLETE_SILENCE_MS = 500L

        private val _isSessionActive = MutableStateFlow(false)
        val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

        private val _phase = MutableStateFlow(JarvisPhase.IDLE)
        val phase: StateFlow<JarvisPhase> = _phase.asStateFlow()

        private val _voiceRmsDb = MutableStateFlow(0f)
        val voiceRmsDb: StateFlow<Float> = _voiceRmsDb.asStateFlow()

        private val _inactivityRemainingMs = MutableStateFlow(INACTIVITY_TIMEOUT_MS)
        val inactivityRemainingMs: StateFlow<Long> = _inactivityRemainingMs.asStateFlow()

        private val _isAutoSleepStandby = MutableStateFlow(false)
        val isAutoSleepStandby: StateFlow<Boolean> = _isAutoSleepStandby.asStateFlow()

        private val _liveTranscript = MutableStateFlow("")
        val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

        private val _statusBanner = MutableStateFlow("JARVIS Core online • Developed by Mozahid")
        val statusBanner: StateFlow<String> = _statusBanner.asStateFlow()

        private val _activeVoiceLabel = MutableStateFlow("Neural Voice Ready")
        val activeVoiceLabel: StateFlow<String> = _activeVoiceLabel.asStateFlow()

        private val _lastVoiceStatus = MutableStateFlow("Normal Voice ready")
        val lastVoiceStatus: StateFlow<String> = _lastVoiceStatus.asStateFlow()

        fun startLiveSession(context: Context, initialCommand: String? = null) {
            val intent = Intent(context, JarvisActiveAssistantService::class.java).apply {
                action = ACTION_START_SESSION
                if (!initialCommand.isNullOrBlank()) {
                    putExtra(EXTRA_COMMAND_TEXT, initialCommand)
                }
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stopLiveSession(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, JarvisActiveAssistantService::class.java).apply {
                        action = ACTION_STOP_SESSION
                    }
                )
            }
        }

        fun stopSpeech(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, JarvisActiveAssistantService::class.java).apply {
                        action = ACTION_STOP_SPEECH
                    }
                )
            }
        }

        fun submitCommand(context: Context, command: String) {
            runCatching {
                context.startService(
                    Intent(context, JarvisActiveAssistantService::class.java).apply {
                        action = ACTION_SUBMIT_COMMAND
                        putExtra(EXTRA_COMMAND_TEXT, command)
                    }
                )
            }
        }

        fun testVoice(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, JarvisActiveAssistantService::class.java).apply {
                        action = ACTION_TEST_VOICE
                    }
                )
            }
        }
    }
}
