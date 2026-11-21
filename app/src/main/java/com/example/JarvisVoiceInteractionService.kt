package com.example

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

/**
 * Registers JARVIS (Developed by Mozahid) with Android's System Default Digital Assistant registry.
 * Enables launching JARVIS via power-button long press, corner swipe, or assistant key.
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TRIGGER_ASSIST_SESSION) {
            runCatching {
                val args = Bundle().apply {
                    putBoolean(FloatingOrbService.EXTRA_START_LISTENING, true)
                }
                showSession(
                    args,
                    VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
                )
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    companion object {
        const val ACTION_TRIGGER_ASSIST_SESSION = "com.example.jarvis.ACTION_TRIGGER_ASSIST_SESSION"
    }
}
