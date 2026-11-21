package com.example

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Provides active JARVIS VoiceInteractionSession instances whenever Android triggers the system assistant.
 */
class JarvisVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return JarvisVoiceInteractionSession(this)
    }
}
