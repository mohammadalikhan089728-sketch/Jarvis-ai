package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Resolves a system speech recognizer outside JARVIS's own package so JARVIS never recurses
 * into itself when selected as Android's Default Digital Assistant.
 */
object SpeechRecognizerFactory {

    fun resolveExternalRecognitionComponent(context: Context): ComponentName? {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val services = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentServices(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentServices(intent, 0)
            }
        } catch (_: Exception) {
            emptyList()
        }

        val candidates = services
            .mapNotNull { it.serviceInfo }
            .filter { it.packageName != context.packageName }

        // Prefer Google / Android system speech services when present
        val preferred = candidates.firstOrNull { info ->
            val pkg = info.packageName.lowercase()
            pkg.contains("google") || pkg.contains("speech") || pkg.contains("samsung")
        } ?: candidates.firstOrNull()

        return preferred?.let { ComponentName(it.packageName, it.name) }
    }

    fun createSafeRecognizer(context: Context): SpeechRecognizer {
        val external = resolveExternalRecognitionComponent(context)
        return if (external != null) {
            SpeechRecognizer.createSpeechRecognizer(context, external)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }
}

/**
 * System RecognitionService required for Android's Default Digital Assistant registration.
 * Delegates speech recognition safely to the device's system SpeechRecognizer backend.
 */
class JarvisRecognitionService : RecognitionService() {

    private var delegateRecognizer: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        if (listener == null) return
        cleanupDelegate()

        val externalComponent = SpeechRecognizerFactory.resolveExternalRecognitionComponent(this)
        if (externalComponent == null) {
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }

        runCatching {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(this, externalComponent)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    runCatching { listener.readyForSpeech(params ?: Bundle()) }
                }

                override fun onBeginningOfSpeech() {
                    runCatching { listener.beginningOfSpeech() }
                }

                override fun onRmsChanged(rmsdB: Float) {
                    runCatching { listener.rmsChanged(rmsdB) }
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    runCatching { listener.bufferReceived(buffer ?: ByteArray(0)) }
                }

                override fun onEndOfSpeech() {
                    runCatching { listener.endOfSpeech() }
                }

                override fun onError(error: Int) {
                    runCatching { listener.error(error) }
                    cleanupDelegate()
                }

                override fun onResults(results: Bundle?) {
                    runCatching { listener.results(results ?: Bundle()) }
                    cleanupDelegate()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    runCatching { listener.partialResults(partialResults ?: Bundle()) }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            delegateRecognizer = recognizer
            recognizer.startListening(recognizerIntent ?: Intent())
        }.onFailure {
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            cleanupDelegate()
        }
    }

    override fun onCancel(listener: Callback?) {
        cleanupDelegate()
    }

    override fun onStopListening(listener: Callback?) {
        runCatching { delegateRecognizer?.stopListening() }
    }

    override fun onDestroy() {
        cleanupDelegate()
        super.onDestroy()
    }

    private fun cleanupDelegate() {
        runCatching {
            delegateRecognizer?.stopListening()
            delegateRecognizer?.cancel()
            delegateRecognizer?.destroy()
        }
        delegateRecognizer = null
    }
}
