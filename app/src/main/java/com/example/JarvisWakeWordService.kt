package com.example

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Always-On Background Wake Word Foreground Service for JARVIS (Developed by Mozahid).
 * Stays alive via START_STICKY, foregroundServiceType="microphone", onTaskRemoved self-restart,
 * and BootReceiver even when the user swipes JARVIS away from recent tasks.
 */
class JarvisWakeWordService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isCycleListening = false
    @Volatile
    private var activeWakeWord: String = "Jarvis"
    @Volatile
    private var wakeEnabledInDb: Boolean = true
    private var lastTriggerElapsedMs: Long = 0L

    private val restartCycleRunnable = Runnable {
        startNextWakeCycle()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        observeSettingsInBackground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            wakeEnabledInDb = false
            stopSelf()
            return START_NOT_STICKY
        }

        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildForegroundNotification(activeWakeWord)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        _isRunning.value = true
        scheduleNextCycle(350L)
        return START_STICKY
    }

    private fun observeSettingsInBackground() {
        serviceScope.launch {
            val repo = JarvisRepository(JarvisDatabase.getInstance(applicationContext))
            repo.settingsFlow.collect { settings ->
                if (settings != null) {
                    val word = settings.customWakeWord.trim().ifBlank { "Jarvis" }
                    activeWakeWord = word
                    wakeEnabledInDb = settings.alwaysOnWakeWordEnabled
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, buildForegroundNotification(word))
                    if (!settings.alwaysOnWakeWordEnabled) {
                        mainHandler.post { stopSelf() }
                    }
                }
            }
        }
    }

    private fun scheduleNextCycle(delayMs: Long) {
        mainHandler.removeCallbacks(restartCycleRunnable)
        mainHandler.postDelayed(restartCycleRunnable, delayMs)
    }

    private fun startNextWakeCycle() {
        if (!wakeEnabledInDb) return

        // Yield microphone whenever MainActivity live listening or an incoming JARVIS call is active
        if (isMainAppListening.value || JarvisIncomingCallActivity.isCallActive.value) {
            stopRecognizerCleanly()
            scheduleNextCycle(1_500L)
            return
        }

        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasMic || !SpeechRecognizer.isRecognitionAvailable(this)) {
            scheduleNextCycle(5_000L)
            return
        }

        if (isCycleListening) return

        runCatching {
            val recognizer = speechRecognizer ?: SpeechRecognizerFactory.createSafeRecognizer(this).also {
                it.setRecognitionListener(wakeRecognitionListener)
                speechRecognizer = it
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            }
            isCycleListening = true
            recognizer.startListening(intent)
        }.onFailure {
            destroyRecognizerCleanly()
            scheduleNextCycle(2_000L)
        }
    }

    private val wakeRecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            isCycleListening = false
        }

        override fun onError(error: Int) {
            isCycleListening = false
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                destroyRecognizerCleanly()
            } else {
                stopRecognizerCleanly()
            }
            val backoff = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 700L
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 10_000L
                else -> 1_600L
            }
            scheduleNextCycle(backoff)
        }

        override fun onResults(results: Bundle?) {
            isCycleListening = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isNotEmpty() && matchesWakeWord(text)) {
                handleWakeWordTriggered(text)
            } else {
                scheduleNextCycle(700L)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (partial.isNotEmpty() && matchesWakeWord(partial)) {
                handleWakeWordTriggered(partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun matchesWakeWord(spoken: String): Boolean {
        val target = activeWakeWord.trim().lowercase(Locale.ROOT)
        if (target.isEmpty()) return false
        return spoken.lowercase(Locale.ROOT).contains(target)
    }

    private fun handleWakeWordTriggered(transcript: String) {
        val now = SystemClock.elapsedRealtime()
        if ((now - lastTriggerElapsedMs) < 3_000L) return
        lastTriggerElapsedMs = now

        stopRecognizerCleanly()
        playActivationTone()

        // Extract optional command spoken right after the wake word
        val target = activeWakeWord.trim().lowercase(Locale.ROOT)
        val idx = transcript.lowercase(Locale.ROOT).indexOf(target)
        val trailingCommand = if (idx >= 0) {
            transcript.substring(idx + target.length).trim().trimStart(',', ':', ' ')
        } else ""

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            putExtra(FloatingOrbService.EXTRA_START_LISTENING, true)
            putExtra(EXTRA_WAKE_TRIGGERED, true)
            if (trailingCommand.length >= 3) {
                putExtra(EXTRA_INITIAL_COMMAND, trailingCommand)
            }
        }
        runCatching { startActivity(launchIntent) }

        // Wait before resuming background wake cycles while MainActivity handles live conversation
        scheduleNextCycle(3_000L)
    }

    private fun playActivationTone() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 160)
            mainHandler.postDelayed({ runCatching { tg.release() } }, 300L)
        }
    }

    private fun stopRecognizerCleanly() {
        isCycleListening = false
        runCatching {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        }
    }

    private fun destroyRecognizerCleanly() {
        isCycleListening = false
        runCatching {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!wakeEnabledInDb) return
        // Schedule automatic service restart if user swipes the app away from recents
        runCatching {
            val restartIntent = Intent(applicationContext, JarvisWakeWordService::class.java)
            val pi = PendingIntent.getService(
                applicationContext,
                9101,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 2_000L,
                pi
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(restartCycleRunnable)
        destroyRecognizerCleanly()
        serviceScope.cancel()
        _isRunning.value = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Always-On Wake Word",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps JARVIS (Developed by Mozahid) listening for your custom wake word."
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(wakeWord: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            9102,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("JARVIS — Always-On Wake Word")
            .setContentText("Listening for \"$wakeWord\" • Developed by Mozahid")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_wake_word_channel"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_STOP_SERVICE = "com.example.jarvis.ACTION_STOP_WAKE_SERVICE"
        const val EXTRA_WAKE_TRIGGERED = "com.example.jarvis.EXTRA_WAKE_TRIGGERED"
        const val EXTRA_INITIAL_COMMAND = "com.example.jarvis.EXTRA_INITIAL_COMMAND"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        // Updated by JarvisViewModel so WakeWordService yields mic cleanly during active live sessions
        val isMainAppListening = MutableStateFlow(false)

        fun start(context: Context) {
            val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (!hasMic) return
            val intent = Intent(context, JarvisWakeWordService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, JarvisWakeWordService::class.java))
            }
        }
    }
}
