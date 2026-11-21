package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession

/**
 * Handles system assistant invocations (power button hold, corner swipe, lockscreen voice assist)
 * and launches JARVIS directly into live hands-free listening mode.
 */
class JarvisVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        launchJarvisListening()
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        launchJarvisListening()
    }

    private fun launchJarvisListening() {
        runCatching {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_ASSIST
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                putExtra(FloatingOrbService.EXTRA_START_LISTENING, true)
                putExtra(EXTRA_ASSIST_TRIGGERED, true)
            }
            context.startActivity(launchIntent)
        }
        hide()
    }

    companion object {
        const val EXTRA_ASSIST_TRIGGERED = "com.example.jarvis.EXTRA_ASSIST_TRIGGERED"
    }
}
