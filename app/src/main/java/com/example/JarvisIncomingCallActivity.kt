package com.example

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.JarvisTheme
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.SignalCrimson
import com.example.ui.theme.SignalGreen
import com.example.ui.theme.StarkAmber
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

private enum class CallStage {
    RINGING,
    ACTIVE_CONVERSATION,
    ENDED
}

/**
 * Full-Screen Futuristic Incoming Call & Live Two-Way Conversation Activity for JARVIS (Developed by Mozahid).
 * Coordinates microphone access exclusively through MicCoordinator, sanitizes every AI reply so raw
 * JSON/brackets are never shown or spoken, and supports both Normal Voice and Multimodal Live AI Voice.
 */
class JarvisIncomingCallActivity : ComponentActivity() {

    private lateinit var repository: JarvisRepository
    private val aiClient = GeminiApiClient()
    private lateinit var offlineProcessor: OfflineCommandProcessor
    private lateinit var agentEngine: JarvisAgentEngine
    private lateinit var voiceEngine: VoiceEngineManager
    private lateinit var memoryManager: MemoryManager
    private lateinit var localRouter: LocalCommandRouter

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var callTimerJob: Job? = null
    private var restartListenJob: Job? = null
    private var turnJob: Job? = null
    private var lastSpeechElapsedMs: Long = 0L

    private var callPurpose: String = "JARVIS Tactical Check-In"
    private var callActionType: String = CALL_TYPE_CUSTOM_REMINDER
    private var callAlertSummary: String = ""
    private var viewsScreenCheckDone: Boolean = false
    private val callDialogueLog = mutableListOf<ChatHistoryEntity>()

    private val _stage = MutableStateFlow(CallStage.RINGING)
    private val _elapsedSeconds = MutableStateFlow(0L)
    private val _isMuted = MutableStateFlow(false)
    private val _jarvisSpeechText = MutableStateFlow("Incoming encrypted voice call…")
    private val _userTranscript = MutableStateFlow("")
    private val _statusLine = MutableStateFlow("INCOMING CALL")

    private val pauseOnSpeechListener: () -> Unit = {
        stopRecognizerCleanly()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockScreenWake()
        enableEdgeToEdge()

        _isCallActive.value = true
        MicCoordinator.setIncomingCallActive(true)
        MicCoordinator.registerSpeechPauseListener(pauseOnSpeechListener)
        CallAlarmReceiver.dismissCallNotification(this)

        repository = JarvisRepository(JarvisDatabase.getInstance(applicationContext))
        offlineProcessor = OfflineCommandProcessor(applicationContext)
        agentEngine = JarvisAgentEngine(applicationContext, repository, aiClient, offlineProcessor)
        voiceEngine = VoiceEngineManager(applicationContext, aiClient)
        memoryManager = MemoryManager(repository)
        localRouter = LocalCommandRouter(applicationContext, repository, offlineProcessor, memoryManager)

        extractCallExtras(intent)
        startRingingAndVibrating()

        setContent {
            JarvisTheme {
                val stage by _stage.collectAsStateWithLifecycle()
                val elapsed by _elapsedSeconds.collectAsStateWithLifecycle()
                val isMuted by _isMuted.collectAsStateWithLifecycle()
                val jarvisLine by _jarvisSpeechText.collectAsStateWithLifecycle()
                val userLine by _userTranscript.collectAsStateWithLifecycle()
                val statusLine by _statusLine.collectAsStateWithLifecycle()

                JarvisCallScreenUi(
                    stage = stage,
                    purpose = callPurpose,
                    actionType = callActionType,
                    elapsedSeconds = elapsed,
                    isMuted = isMuted,
                    jarvisSpeechText = jarvisLine,
                    userTranscript = userLine,
                    statusLine = statusLine,
                    onAccept = ::acceptCall,
                    onDecline = ::declineOrEndCall,
                    onToggleMute = ::toggleMute
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (_stage.value == CallStage.RINGING) {
            extractCallExtras(intent)
        }
    }

    private fun extractCallExtras(intent: Intent?) {
        callPurpose = intent?.getStringExtra(CallAlarmReceiver.EXTRA_CALL_PURPOSE)
            ?.takeIf { it.isNotBlank() } ?: "Scheduled JARVIS Check-In"
        callActionType = intent?.getStringExtra(CallAlarmReceiver.EXTRA_CALL_ACTION_TYPE)
            ?.takeIf { it.isNotBlank() } ?: CALL_TYPE_CUSTOM_REMINDER
        callAlertSummary = intent?.getStringExtra(CallAlarmReceiver.EXTRA_ALERT_SUMMARY).orEmpty()
    }

    private fun configureLockScreenWake() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startRingingAndVibrating() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val r = RingtoneManager.getRingtone(this, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                r.isLooping = true
            }
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            r.play()
            ringtone = r
        }

        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator = vib
            val pattern = longArrayOf(0L, 650L, 450L, 650L, 900L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pattern, 0)
            }
        }
    }

    private fun stopRingingAndVibrating() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        CallAlarmReceiver.dismissCallNotification(this)
    }

    private fun acceptCall() {
        if (_stage.value != CallStage.RINGING) return
        stopRingingAndVibrating()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            runCatching { km.requestDismissKeyguard(this, null) }
        }

        _stage.value = CallStage.ACTIVE_CONVERSATION
        _statusLine.value = "CONNECTED • LIVE VOICE LINK"
        lastSpeechElapsedMs = SystemClock.elapsedRealtime()
        startCallDurationTimer()

        turnJob = lifecycleScope.launch {
            val settings = repository.getSettings()
            val recentAlerts = repository.getRecentCallAlerts(limit = 5)
            val openingPrompt = buildOpeningCallPrompt(recentAlerts)
            speakCallTurn(openingPrompt, settings, isOpeningTurn = true)
        }
    }

    private fun buildOpeningCallPrompt(recentAlerts: List<PendingCallAlertEntity>): String {
        val alertsText = if (callAlertSummary.isNotBlank()) {
            callAlertSummary
        } else if (recentAlerts.isNotEmpty()) {
            recentAlerts.joinToString(" | ") { "${it.sourceApp}: ${it.title} (${it.summary})" }
        } else {
            "No new viral or milestone notification alerts recorded yet."
        }

        return when (callActionType) {
            CALL_TYPE_WAKE_UP_STUDY -> """
                You just called the user on a scheduled Wake-Up / Study Call.
                Scheduled Purpose: "$callPurpose".
                Greet them warmly and energetically in natural Hinglish/Hindi.
                Ask an interactive wake-up check question like: "Good morning Sir! Aankh khuli Sir? Aaj kaunsa subject padhna hai?"
                Keep it crisp (2 short sentences). Respond in plain spoken sentences only (no JSON).
            """.trimIndent()

            CALL_TYPE_SMART_VIEWS_CHECK -> """
                You just called the user for a Smart Views Check Call.
                Scheduled Purpose: "$callPurpose".
                Local Notification Milestones Observed: $alertsText.
                First briefly report any milestones observed (or mention no big alert came yet), then politely ask permission:
                "Sir, kya main abhi ek baar Instagram ya YouTube khol kar fresh views check kar du?"
                Keep it concise (2 short sentences). Respond in plain spoken sentences only (no JSON).
            """.trimIndent()

            CALL_TYPE_ONLINE_ALERT -> """
                You just called the user because the phone came online with important/viral alerts!
                Alert Details: $alertsText.
                Brief the user clearly and enthusiastically in natural Hinglish/English and ask if they want to take action.
                Keep it concise (2 short sentences). Respond in plain spoken sentences only (no JSON).
            """.trimIndent()

            CALL_TYPE_YOUTUBE_MILESTONE -> """
                You just placed an in-app JARVIS milestone call to celebrate a YouTube video view milestone!
                Milestone Summary: $alertsText.
                Purpose: "$callPurpose".
                Congratulate the user warmly in natural Hinglish/English, clearly announce the video title, crossed milestone, current views, and recent growth, then offer to discuss YouTube strategy, pinned comments, Shorts clips, or next video ideas.
                Never guarantee future virality. Keep it energetic and concise (2-3 short spoken sentences, no JSON).
            """.trimIndent()

            else -> """
                You just called the user for a scheduled JARVIS call.
                Scheduled Purpose: "$callPurpose".
                Greet them respectfully as JARVIS and remind them clearly about the purpose, then ask how they would like to proceed.
                Keep it concise (2 short sentences). Respond in plain spoken sentences only (no JSON).
            """.trimIndent()
        }
    }

    private suspend fun speakCallTurn(
        promptText: String,
        settings: SettingsEntity,
        isOpeningTurn: Boolean = false
    ) {
        if (_stage.value != CallStage.ACTIVE_CONVERSATION) return
        stopRecognizerCleanly()
        MicCoordinator.release(MicOwner.INCOMING_CALL)
        _statusLine.value = "JARVIS THINKING…"

        if (settings.geminiApiKey.isBlank()) {
            val offlineFallback = when (callActionType) {
                CALL_TYPE_WAKE_UP_STUDY ->
                    "Good morning Sir! Aankh khuli? $callPurpose ka time ho gaya hai. Aaj kaunsa subject padhna hai?"
                CALL_TYPE_SMART_VIEWS_CHECK ->
                    "Sir, $callPurpose ka time ho gaya hai. ${callAlertSummary.ifBlank { "Abhi tak koi bada milestone alert nahi aaya." }} Kya main ek baar Instagram ya YouTube khol kar views check kar du?"
                CALL_TYPE_ONLINE_ALERT ->
                    "Sir, urgent update! $callPurpose. $callAlertSummary"
                CALL_TYPE_YOUTUBE_MILESTONE ->
                    "Congratulations Sir! ${callAlertSummary.ifBlank { callPurpose }} Would you like to discuss our next YouTube strategy?"
                else ->
                    "Hello Sir, JARVIS calling for: $callPurpose."
            }
            val cleanOffline = JarvisResponseSanitizer.extractCleanReply(offlineFallback)
            _jarvisSpeechText.value = cleanOffline
            _statusLine.value = "JARVIS SPEAKING…"
            voiceEngine.speak(cleanOffline, settings) {
                scheduleCallListeningRestart(MicCoordinator.POST_SPEECH_RESTART_DELAY_MS)
            }
            return
        }

        val callGuidance = when (callActionType) {
            CALL_TYPE_WAKE_UP_STUDY ->
                "ACTIVE WAKE-UP / STUDY CALL: Keep motivating the user gently and asking short interactive questions until they confirm they are fully awake and ready to study."
            CALL_TYPE_SMART_VIEWS_CHECK ->
                "ACTIVE SMART VIEWS CHECK CALL: Be helpful and non-spammy. Only open Instagram/YouTube if the user explicitly says yes."
            CALL_TYPE_YOUTUBE_MILESTONE ->
                "ACTIVE YOUTUBE MILESTONE CALL: Discuss practical YouTube creator growth strategy (hook, title/thumbnail iteration, Shorts spin-offs, audience retention) based on the milestone alert: $callAlertSummary. Never guarantee views or virality. Keep replies concise (1-2 spoken sentences)."
            else ->
                "ACTIVE LIVE VOICE CALL: Keep responses warm, concise (1-2 sentences), and conversational."
        }

        val useNormalStreaming = settings.voiceMode != VOICE_MODE_AI_VOICE
        var streamingStarted = false
        val collectedReply = StringBuilder()

        val memoryContext = memoryManager.buildCompactMemoryContext(promptText)
        val result = aiClient.streamConversationalReply(
            rawApiKey = settings.geminiApiKey,
            masterSystemPrompt = settings.masterSystemPrompt,
            userPrompt = promptText,
            recentHistory = callDialogueLog.takeLast(6),
            extraSystemGuidance = callGuidance,
            selectedEngine = settings.coreEngine,
            memoryContext = memoryContext,
            onSentenceReady = { sentence ->
                val cleanSentence = JarvisResponseSanitizer.sanitizePlainSpeechText(sentence)
                if (cleanSentence.isEmpty()) return@streamConversationalReply
                if (collectedReply.isNotEmpty()) collectedReply.append(' ')
                collectedReply.append(cleanSentence)
                _jarvisSpeechText.value = collectedReply.toString()
                if (useNormalStreaming) {
                    if (!streamingStarted) {
                        streamingStarted = true
                        _statusLine.value = "JARVIS SPEAKING…"
                        voiceEngine.beginStreamingSpeech(settings) {
                            scheduleCallListeningRestart(MicCoordinator.POST_SPEECH_RESTART_DELAY_MS)
                        }
                    }
                    voiceEngine.enqueueStreamSentence(cleanSentence)
                }
            }
        )

        if (streamingStarted) {
            voiceEngine.finishStreamingSpeech()
        }

        val finalSpoken = JarvisResponseSanitizer.extractCleanReply(
            result.getOrElse { "Sir, I am on the line regarding $callPurpose." }
        ).ifBlank { "Sir, I am on the line regarding $callPurpose." }
        _jarvisSpeechText.value = finalSpoken

        if (!isOpeningTurn) {
            callDialogueLog.add(
                ChatHistoryEntity(
                    userTranscript = _userTranscript.value,
                    executedAction = "CALL:$callActionType",
                    actionParamsJson = "{}",
                    jarvisReply = finalSpoken,
                    isOffline = false,
                    latencyMs = 0L
                )
            )
        }

        if (!streamingStarted) {
            _statusLine.value = "JARVIS SPEAKING…"
            voiceEngine.speak(finalSpoken, settings) {
                scheduleCallListeningRestart(MicCoordinator.POST_SPEECH_RESTART_DELAY_MS)
            }
        }
    }

    private fun handleUserCallUtterance(spoken: String) {
        val lower = spoken.lowercase(Locale.ROOT)
        _userTranscript.value = spoken
        lastSpeechElapsedMs = SystemClock.elapsedRealtime()

        turnJob?.cancel()
        turnJob = lifecycleScope.launch {
            val settings = repository.getSettings()

            // 1. Zero-Token In-Call Local Router:
            //    - Combined hangup + relative call scheduling ("Theek hai abhi cut kar do, 10 minute baad call karna")
            //    - Standalone relative call scheduling ("1 ghante baad call karna")
            //    - Instant call hangup ("Call cut kar do", "Phone rakh do", "End call")
            //    - Explicit long-term memory save ("Yaad rakhna...")
            when (
                val route = localRouter.tryRoute(
                    rawQuery = spoken,
                    inActiveCall = true,
                    defaultCallPurpose = callPurpose,
                    defaultCallActionType = callActionType
                )
            ) {
                LocalRouteResult.IgnoredNoise -> {
                    scheduleCallListeningRestart(MicCoordinator.ERROR_RESTART_DEBOUNCE_MS)
                    return@launch
                }
                is LocalRouteResult.Handled -> {
                    val cleanReply = JarvisResponseSanitizer.extractCleanReply(route.reply)
                    _jarvisSpeechText.value = cleanReply
                    _statusLine.value = if (route.shouldEndCall) {
                        "CONFIRMING & DISCONNECTING…"
                    } else {
                        "JARVIS SPEAKING…"
                    }
                    voiceEngine.speak(cleanReply, settings) {
                        if (route.shouldEndCall) {
                            declineOrEndCall()
                        } else {
                            scheduleCallListeningRestart(MicCoordinator.POST_SPEECH_RESTART_DELAY_MS)
                        }
                    }
                    return@launch
                }
                LocalRouteResult.NotHandled -> Unit
            }

            // Passively store personal routines/goals shared during the call
            memoryManager.detectAndSaveImplicitMemory(spoken)

            // 2. Smart Views Check Call: polite permission-based ONE-TIME screen check only when user says YES
            if (callActionType == CALL_TYPE_SMART_VIEWS_CHECK &&
                !viewsScreenCheckDone &&
                isAffirmativeViewsCheck(lower)
            ) {
                viewsScreenCheckDone = true
                val targetApp = if (lower.contains("youtube")) "YouTube" else "Instagram"
                _statusLine.value = "OPENING $targetApp FOR ONE-TIME CHECK…"
                _jarvisSpeechText.value = "Checking $targetApp once now, Sir."
                voiceEngine.speak("Opening $targetApp once to check your views, Sir.", settings)

                offlineProcessor.openInstalledApp(targetApp)
                delay(2_200L)
                val screenText = JarvisAccessibilityService.instance
                    ?.readScreenHierarchy(maxNodes = 70)
                    ?.toPlainReadableText()
                    ?: "Accessibility screen reader is inactive, so I opened $targetApp for you to view directly."

                val summaryPrompt = "The user said yes to checking $targetApp views once. Summarize any visible view/like/subscriber numbers from this screen text clearly in 2 short spoken sentences:\n$screenText"
                speakCallTurn(summaryPrompt, settings)
                return@launch
            }

            speakCallTurn(spoken, settings)
        }
    }

    private fun isAffirmativeViewsCheck(lower: String): Boolean {
        return lower.contains("yes") ||
            lower.contains("haan") ||
            lower.contains("han") ||
            lower.contains("check") ||
            lower.contains("khol") ||
            lower.contains("kar do") ||
            lower.contains("kar de") ||
            lower.contains("open instagram") ||
            lower.contains("open youtube")
    }

    private fun scheduleCallListeningRestart(delayMs: Long) {
        restartListenJob?.cancel()
        if (_stage.value != CallStage.ACTIVE_CONVERSATION || _isMuted.value) return
        restartListenJob = lifecycleScope.launch {
            delay(delayMs)
            if (_stage.value != CallStage.ACTIVE_CONVERSATION || _isMuted.value) return@launch
            if ((SystemClock.elapsedRealtime() - lastSpeechElapsedMs) >= CALL_INACTIVITY_LIMIT_MS) {
                declineOrEndCall()
                return@launch
            }
            if (MicCoordinator.isJarvisSpeaking.value) {
                scheduleCallListeningRestart(MicCoordinator.ERROR_RESTART_DEBOUNCE_MS)
                return@launch
            }
            startCallListeningWindow()
        }
    }

    private fun startCallListeningWindow() {
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasMic || !SpeechRecognizer.isRecognitionAvailable(this)) {
            _statusLine.value = "MICROPHONE PERMISSION NEEDED"
            return
        }
        if (MicCoordinator.isJarvisSpeaking.value) {
            scheduleCallListeningRestart(MicCoordinator.ERROR_RESTART_DEBOUNCE_MS)
            return
        }

        stopRecognizerCleanly()
        _statusLine.value = "LISTENING • SPEAK NATURALLY"

        runCatching {
            val recognizer = speechRecognizer ?: SpeechRecognizerFactory.createSafeRecognizer(this).also {
                it.setRecognitionListener(callRecognitionListener)
                speechRecognizer = it
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
            }
            val started = MicCoordinator.startListeningSilently(
                context = this,
                owner = MicOwner.INCOMING_CALL,
                recognizer = recognizer,
                intent = intent
            )
            if (!started) {
                scheduleCallListeningRestart(MicCoordinator.ERROR_RESTART_DEBOUNCE_MS)
            }
        }.onFailure {
            destroyRecognizerCleanly()
            MicCoordinator.release(MicOwner.INCOMING_CALL)
            scheduleCallListeningRestart(500L)
        }
    }

    private val callRecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() {
            lastSpeechElapsedMs = SystemClock.elapsedRealtime()
            _statusLine.value = "HEARING YOU…"
        }

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            MicCoordinator.release(MicOwner.INCOMING_CALL)
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                destroyRecognizerCleanly()
            } else {
                stopRecognizerCleanly()
            }
            scheduleCallListeningRestart(MicCoordinator.ERROR_RESTART_DEBOUNCE_MS)
        }

        override fun onResults(results: Bundle?) {
            stopRecognizerCleanly()
            MicCoordinator.release(MicOwner.INCOMING_CALL)
            val spoken = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (spoken.isBlank() || localRouter.isShortOrNoise(spoken)) {
                scheduleCallListeningRestart(MicCoordinator.ERROR_RESTART_DEBOUNCE_MS)
            } else {
                handleUserCallUtterance(spoken)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (partial.isNotBlank()) {
                lastSpeechElapsedMs = SystemClock.elapsedRealtime()
                _userTranscript.value = partial
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun startCallDurationTimer() {
        callTimerJob?.cancel()
        callTimerJob = lifecycleScope.launch {
            while (isActive && _stage.value == CallStage.ACTIVE_CONVERSATION) {
                delay(1_000L)
                _elapsedSeconds.value = _elapsedSeconds.value + 1L
                if ((SystemClock.elapsedRealtime() - lastSpeechElapsedMs) >= CALL_INACTIVITY_LIMIT_MS &&
                    !voiceEngine.isSpeaking.value
                ) {
                    declineOrEndCall()
                    break
                }
            }
        }
    }

    private fun toggleMute() {
        val next = !_isMuted.value
        _isMuted.value = next
        if (next) {
            stopRecognizerCleanly()
            MicCoordinator.release(MicOwner.INCOMING_CALL)
            _statusLine.value = "MICROPHONE MUTED"
        } else {
            scheduleCallListeningRestart(200L)
        }
    }

    private fun declineOrEndCall() {
        _stage.value = CallStage.ENDED
        stopRingingAndVibrating()
        callTimerJob?.cancel()
        restartListenJob?.cancel()
        turnJob?.cancel()
        destroyRecognizerCleanly()
        voiceEngine.stop()
        _isCallActive.value = false
        MicCoordinator.setIncomingCallActive(false)
        MicCoordinator.release(MicOwner.INCOMING_CALL)
        runCatching { finishAndRemoveTask() }.onFailure { finish() }
    }

    private fun stopRecognizerCleanly() {
        runCatching {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        }
    }

    private fun destroyRecognizerCleanly() {
        runCatching {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
    }

    override fun onDestroy() {
        MicCoordinator.unregisterSpeechPauseListener(pauseOnSpeechListener)
        stopRingingAndVibrating()
        callTimerJob?.cancel()
        restartListenJob?.cancel()
        turnJob?.cancel()
        destroyRecognizerCleanly()
        voiceEngine.shutdown()
        _isCallActive.value = false
        MicCoordinator.setIncomingCallActive(false)
        MicCoordinator.release(MicOwner.INCOMING_CALL)
        super.onDestroy()
    }

    companion object {
        private const val CALL_INACTIVITY_LIMIT_MS = 180_000L

        private val _isCallActive = MutableStateFlow(false)
        val isCallActive: StateFlow<Boolean> = _isCallActive.asStateFlow()
    }
}

@Composable
private fun JarvisCallScreenUi(
    stage: CallStage,
    purpose: String,
    actionType: String,
    elapsedSeconds: Long,
    isMuted: Boolean,
    jarvisSpeechText: String,
    userTranscript: String,
    statusLine: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onToggleMute: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "call_pulse")
    val ringScale by pulse.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(950),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ring_scale"
    )

    val formattedTimer = String.format(
        Locale.US,
        "%02d:%02d",
        elapsedSeconds / 60L,
        elapsedSeconds % 60L
    )

    val typeBadge = when (actionType) {
        CALL_TYPE_WAKE_UP_STUDY -> "WAKE-UP & STUDY CALL"
        CALL_TYPE_SMART_VIEWS_CHECK -> "SMART VIEWS CHECK"
        CALL_TYPE_ONLINE_ALERT -> "VIRAL / URGENT ALERT"
        CALL_TYPE_YOUTUBE_MILESTONE -> "YOUTUBE MILESTONE CALL"
        else -> "SCHEDULED JARVIS CALL"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF02060F), ObsidianVoid, Color(0xFF041322))
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = ArcCyan.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.55f))
                ) {
                    Text(
                        text = typeBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = ArcCyan,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "J.A.R.V.I.S.",
                    style = MaterialTheme.typography.displaySmall,
                    color = ArcCyan
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.developer_credit),
                    style = MaterialTheme.typography.labelMedium,
                    color = StarkAmber
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (stage == CallStage.ACTIVE_CONVERSATION) "$statusLine  •  $formattedTimer" else statusLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (stage == CallStage.ACTIVE_CONVERSATION) SignalGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.size(188.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val baseR = size.minDimension * 0.36f
                        drawCircle(
                            color = ArcCyan.copy(alpha = 0.14f),
                            radius = baseR * ringScale * 1.25f,
                            center = Offset(cx, cy)
                        )
                        drawCircle(
                            color = ArcCyan.copy(alpha = 0.7f),
                            radius = baseR * ringScale,
                            center = Offset(cx, cy),
                            style = Stroke(width = 4f)
                        )
                        drawCircle(
                            color = if (stage == CallStage.ACTIVE_CONVERSATION) SignalGreen else ArcCyan,
                            radius = baseR * 0.62f,
                            center = Offset(cx, cy),
                            style = Stroke(width = 6f)
                        )
                    }
                    Text(
                        text = "J.A.R.V.I.S.",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = purpose,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = jarvisSpeechText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ArcCyan,
                            textAlign = TextAlign.Center,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (userTranscript.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "YOU: \"$userTranscript\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            if (stage == CallStage.RINGING) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = onDecline,
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = SignalCrimson,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .size(72.dp)
                                .testTag("incoming_call_decline_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CallEnd,
                                contentDescription = stringResource(R.string.cd_decline_call),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("DECLINE", style = MaterialTheme.typography.labelMedium, color = SignalCrimson)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = onAccept,
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = SignalGreen,
                                contentColor = ObsidianVoid
                            ),
                            modifier = Modifier
                                .size(72.dp)
                                .testTag("incoming_call_accept_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Call,
                                contentDescription = stringResource(R.string.cd_accept_call),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("ACCEPT", style = MaterialTheme.typography.labelMedium, color = SignalGreen)
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = onToggleMute,
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (isMuted) StarkAmber else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isMuted) ObsidianVoid else ArcCyan
                            ),
                            modifier = Modifier
                                .size(64.dp)
                                .testTag("active_call_mute_button")
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                                contentDescription = stringResource(R.string.cd_toggle_mute),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isMuted) "MUTED" else "MIC ON",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = onDecline,
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = SignalCrimson,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .size(72.dp)
                                .testTag("active_call_end_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CallEnd,
                                contentDescription = stringResource(R.string.cd_end_call),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("END CALL", style = MaterialTheme.typography.labelMedium, color = SignalCrimson)
                    }
                }
            }
        }
    }
}
