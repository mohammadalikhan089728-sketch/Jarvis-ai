package com.example

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

enum class MicOwner(val priority: Int) {
    NONE(0),
    WAKE_WORD(1),
    MAIN_APP(2),
    ACTIVE_ASSISTANT(3),
    INCOMING_CALL(4)
}

/**
 * Single Microphone Arbiter & Silent SpeechRecognizer Helper for JARVIS (Developed by Mozahid).
 * Guarantees:
 * 1. Only ONE component holds the Android microphone at any instant.
 * 2. SpeechRecognizer is strictly paused while JARVIS is speaking (isSpeaking == true).
 * 3. JarvisWakeWordService is automatically paused whenever MainActivity, JarvisActiveAssistantService,
 *    or JarvisIncomingCallActivity is active.
 * 4. Briefly mutes STREAM_NOTIFICATION & STREAM_SYSTEM for 250ms around startListening()
 *    to suppress Android's annoying start/stop recognition beep during continuous loops.
 */
object MicCoordinator {

    const val SILENT_MUTE_DURATION_MS = 250L
    const val ERROR_RESTART_DEBOUNCE_MS = 400L
    const val POST_SPEECH_RESTART_DELAY_MS = 320L

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val muteDepth = AtomicInteger(0)
    private var unmuteRunnable: Runnable? = null

    private val _activeOwner = MutableStateFlow(MicOwner.NONE)
    val activeOwner: StateFlow<MicOwner> = _activeOwner.asStateFlow()

    private val _isJarvisSpeaking = MutableStateFlow(false)
    val isJarvisSpeaking: StateFlow<Boolean> = _isJarvisSpeaking.asStateFlow()

    private val _isMainActivityResumed = MutableStateFlow(false)
    val isMainActivityResumed: StateFlow<Boolean> = _isMainActivityResumed.asStateFlow()

    private val _isActiveAssistantRunning = MutableStateFlow(false)
    val isActiveAssistantRunning: StateFlow<Boolean> = _isActiveAssistantRunning.asStateFlow()

    private val _isIncomingCallActive = MutableStateFlow(false)
    val isIncomingCallActive: StateFlow<Boolean> = _isIncomingCallActive.asStateFlow()

    private val pauseWakeWordListeners = CopyOnWriteArraySet<() -> Unit>()
    private val pauseAllRecognizersListeners = CopyOnWriteArraySet<() -> Unit>()

    fun registerWakeWordPauseListener(listener: () -> Unit) {
        pauseWakeWordListeners.add(listener)
    }

    fun unregisterWakeWordPauseListener(listener: () -> Unit) {
        pauseWakeWordListeners.remove(listener)
    }

    fun registerSpeechPauseListener(listener: () -> Unit) {
        pauseAllRecognizersListeners.add(listener)
    }

    fun unregisterSpeechPauseListener(listener: () -> Unit) {
        pauseAllRecognizersListeners.remove(listener)
    }

    fun setMainActivityActive(active: Boolean) {
        _isMainActivityResumed.value = active
        if (active) {
            notifyWakeWordToPause()
        }
    }

    fun setActiveAssistantRunning(running: Boolean) {
        _isActiveAssistantRunning.value = running
        if (running) {
            notifyWakeWordToPause()
        } else {
            release(MicOwner.ACTIVE_ASSISTANT)
        }
    }

    fun setIncomingCallActive(active: Boolean) {
        _isIncomingCallActive.value = active
        if (active) {
            notifyWakeWordToPause()
        } else {
            release(MicOwner.INCOMING_CALL)
        }
    }

    fun setJarvisSpeaking(speaking: Boolean) {
        val changed = _isJarvisSpeaking.value != speaking
        _isJarvisSpeaking.value = speaking
        if (speaking && changed) {
            mainHandler.post {
                pauseAllRecognizersListeners.forEach { listener ->
                    runCatching { listener.invoke() }
                }
            }
        }
    }

    /**
     * Returns true if JarvisWakeWordService should yield the mic right now.
     */
    fun shouldPauseWakeWord(): Boolean {
        return _isJarvisSpeaking.value ||
            _isIncomingCallActive.value ||
            _isActiveAssistantRunning.value ||
            _isMainActivityResumed.value ||
            _activeOwner.value.priority > MicOwner.WAKE_WORD.priority
    }

    /**
     * Attempts to claim exclusive microphone ownership for [owner].
     */
    fun tryAcquire(owner: MicOwner): Boolean {
        synchronized(lock) {
            if (_isJarvisSpeaking.value) return false
            if (owner == MicOwner.WAKE_WORD && shouldPauseWakeWord()) return false

            val current = _activeOwner.value
            if (current == owner) return true
            if (current == MicOwner.NONE || owner.priority >= current.priority) {
                if (current == MicOwner.WAKE_WORD && owner != MicOwner.WAKE_WORD) {
                    notifyWakeWordToPause()
                }
                _activeOwner.value = owner
                return true
            }
            return false
        }
    }

    fun canListen(owner: MicOwner): Boolean {
        if (_isJarvisSpeaking.value) return false
        if (owner == MicOwner.WAKE_WORD && shouldPauseWakeWord()) return false
        val current = _activeOwner.value
        return current == owner || current == MicOwner.NONE || owner.priority >= current.priority
    }

    fun release(owner: MicOwner) {
        synchronized(lock) {
            if (_activeOwner.value == owner) {
                _activeOwner.value = MicOwner.NONE
            }
        }
    }

    /**
     * Starts Android SpeechRecognizer while briefly muting STREAM_NOTIFICATION & STREAM_SYSTEM
     * for 250ms to suppress Android's repetitive recognition start beep during continuous loops.
     */
    fun startListeningSilently(
        context: Context,
        owner: MicOwner,
        recognizer: SpeechRecognizer,
        intent: Intent
    ): Boolean {
        if (!tryAcquire(owner)) return false
        muteSystemBeepTemporarily(context.applicationContext)
        return runCatching {
            recognizer.startListening(intent)
            true
        }.getOrElse {
            release(owner)
            false
        }
    }

    private fun notifyWakeWordToPause() {
        mainHandler.post {
            pauseWakeWordListeners.forEach { listener ->
                runCatching { listener.invoke() }
            }
        }
    }

    private fun muteSystemBeepTemporarily(appContext: Context) {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        mainHandler.post {
            unmuteRunnable?.let { mainHandler.removeCallbacks(it) }
            if (muteDepth.getAndIncrement() == 0) {
                setStreamsMuted(audioManager, muted = true)
            }
            val restoreTask = Runnable {
                muteDepth.set(0)
                setStreamsMuted(audioManager, muted = false)
            }
            unmuteRunnable = restoreTask
            mainHandler.postDelayed(restoreTask, SILENT_MUTE_DURATION_MS)
        }
    }

    private fun setStreamsMuted(audioManager: AudioManager, muted: Boolean) {
        val streams = intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)
        for (stream in streams) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val direction = if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
                    audioManager.adjustStreamVolume(stream, direction, 0)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.setStreamMute(stream, muted)
                }
            }
        }
    }
}
