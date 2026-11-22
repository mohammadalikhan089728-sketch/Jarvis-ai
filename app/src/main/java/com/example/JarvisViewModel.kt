package com.example

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
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
    val notificationListenerActive: Boolean = false,
    val overlayGranted: Boolean = false,
    val cameraGranted: Boolean = false,
    val phoneGranted: Boolean = false,
    val contactsGranted: Boolean = false
) {
    val activeCount: Int
        get() = listOf(
            microphoneGranted,
            accessibilityActive,
            notificationListenerActive,
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

/**
 * Coordinates UI state with JarvisActiveAssistantService (so continuous hands-free voice sessions
 * keep running seamlessly after going to Home screen or opening YouTube/WhatsApp/Settings),
 * Local Room DB, MicCoordinator, Scheduled Calls, and Diagnostics.
 */
class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val database = JarvisDatabase.getInstance(appContext)
    private val repository = JarvisRepository(database)
    private val memoryManager = MemoryManager(repository)
    private val aiClient = GeminiApiClient()
    private val previewVoiceEngine = VoiceEngineManager(appContext, aiClient)

    private val _localStatusBanner = MutableStateFlow("JARVIS Core online • Developed by Mozahid")
    private val _keyVerificationState = MutableStateFlow<KeyVerificationUiState>(KeyVerificationUiState.Idle)
    val keyVerificationState: StateFlow<KeyVerificationUiState> = _keyVerificationState.asStateFlow()

    private val _permissionsState = MutableStateFlow(PermissionsSnapshot())
    val permissionsState: StateFlow<PermissionsSnapshot> = _permissionsState.asStateFlow()

    // Live continuous session state mirrored directly from JarvisActiveAssistantService
    val isLiveSessionActive: StateFlow<Boolean> = JarvisActiveAssistantService.isSessionActive
    val phase: StateFlow<JarvisPhase> = JarvisActiveAssistantService.phase
    val voiceRmsDb: StateFlow<Float> = JarvisActiveAssistantService.voiceRmsDb
    val inactivityRemainingMs: StateFlow<Long> = JarvisActiveAssistantService.inactivityRemainingMs
    val isAutoSleepStandby: StateFlow<Boolean> = JarvisActiveAssistantService.isAutoSleepStandby
    val liveTranscript: StateFlow<String> = JarvisActiveAssistantService.liveTranscript
    val statusBanner: StateFlow<String> = _localStatusBanner.asStateFlow()

    val settings: StateFlow<SettingsEntity> = repository.settingsFlow
        .map { it ?: SettingsEntity() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsEntity())

    val chatHistory: StateFlow<List<ChatHistoryEntity>> = repository.historyFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val macros: StateFlow<List<CustomMacroEntity>> = repository.macrosFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val autoChatLogs: StateFlow<List<AutoChatLogsEntity>> = repository.autoChatLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val scheduledCalls: StateFlow<List<ScheduledCallEntity>> = repository.scheduledCallsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val scheduledTasks: StateFlow<List<ScheduledTaskEntity>> = repository.scheduledTasksFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingAlerts: StateFlow<List<PendingCallAlertEntity>> = repository.pendingAlertsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val userMemories: StateFlow<List<UserMemoryEntity>> = repository.memoriesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val screenSummary: StateFlow<String> = JarvisAccessibilityService.lastScreenSummary
    val isMultiStepRunning: StateFlow<Boolean> = JarvisAccessibilityService.isMultiStepRunning
    val multiStepStatus: StateFlow<String> = JarvisAccessibilityService.currentStepStatus
    val lastAutoReplyBanner: StateFlow<String> = JarvisNotificationListenerService.lastAutoReplyBanner
    val activeVoiceLabel: StateFlow<String> = previewVoiceEngine.activeVoiceLabel
    val lastVoiceStatus: StateFlow<String> = previewVoiceEngine.lastVoiceStatus
    val liveAudioConnected: StateFlow<Boolean> = PersistentLiveAudioSession.isSessionConnected
    val overlayRunning: StateFlow<Boolean> = FloatingOrbService.isRunning
    val wakeWordServiceRunning: StateFlow<Boolean> = JarvisWakeWordService.isRunning
    val networkMonitorRunning: StateFlow<Boolean> = NetworkMonitorService.isRunning

    init {
        viewModelScope.launch {
            val loaded = repository.ensureDefaultsInitialized()
            refreshPermissions()
            CallAlarmScheduler.rescheduleAllEnabledCalls(appContext)
            TaskSchedulerAndAutomationManager.rescheduleAllPendingTasks(appContext)
            if (loaded.floatingOverlayEnabled && Settings.canDrawOverlays(appContext)) {
                FloatingOrbService.start(appContext)
            }
            if (loaded.alwaysOnWakeWordEnabled && _permissionsState.value.microphoneGranted) {
                JarvisWakeWordService.start(appContext)
            }
            if (loaded.onlineAutoCallEnabled) {
                NetworkMonitorService.start(appContext)
            }
        }

        viewModelScope.launch {
            JarvisActiveAssistantService.statusBanner.collect { banner ->
                if (banner.isNotBlank()) {
                    _localStatusBanner.value = banner
                }
            }
        }

        viewModelScope.launch {
            JarvisAccessibilityService.isServiceRunning.collect {
                refreshPermissions()
            }
        }

        viewModelScope.launch {
            JarvisNotificationListenerService.isConnected.collect {
                refreshPermissions()
            }
        }

        viewModelScope.launch {
            JarvisNotificationListenerService.lastAutoReplyBanner.collect { msg ->
                if (msg.isNotBlank() && phase.value == JarvisPhase.IDLE) {
                    _localStatusBanner.value = msg
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
        val notificationListener = isNotificationListenerEnabled()

        _permissionsState.value = PermissionsSnapshot(
            microphoneGranted = mic,
            accessibilityActive = accessibility,
            notificationListenerActive = notificationListener,
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

    private fun isNotificationListenerEnabled(): Boolean {
        if (JarvisNotificationListenerService.isConnected.value) return true
        val enabled = Settings.Secure.getString(
            appContext.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabled.contains(appContext.packageName)
    }

    fun toggleListening() {
        if (isLiveSessionActive.value || phase.value == JarvisPhase.LISTENING || phase.value == JarvisPhase.SPEAKING) {
            stopLiveSessionManual()
        } else {
            startLiveSession()
        }
    }

    fun startLiveSession(initialCommand: String? = null) {
        refreshPermissions()
        if (!_permissionsState.value.microphoneGranted) {
            _localStatusBanner.value = "Microphone permission required. Grant it in Systems."
            return
        }
        previewVoiceEngine.stop()
        JarvisActiveAssistantService.startLiveSession(appContext, initialCommand)
    }

    fun stopLiveSessionManual() {
        previewVoiceEngine.stop()
        JarvisActiveAssistantService.stopLiveSession(appContext)
        _localStatusBanner.value = "STANDBY"
    }

    fun stopSpeaking() {
        previewVoiceEngine.stop()
        if (isLiveSessionActive.value) {
            JarvisActiveAssistantService.stopSpeech(appContext)
        }
    }

    fun stopMultiStepAgent() {
        JarvisAccessibilityService.requestStopMultiStep()
        FloatingOrbService.hideStopHud(appContext)
        _localStatusBanner.value = "Multi-step execution stopped"
    }

    fun submitCommand(commandText: String) {
        val clean = commandText.trim()
        if (clean.isEmpty()) return
        previewVoiceEngine.stop()
        JarvisActiveAssistantService.submitCommand(appContext, clean)
    }

    fun toggleAutoReplyMode(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateAutoReplyConfig(enabled = enabled)
            _localStatusBanner.value = if (enabled) {
                if (isNotificationListenerEnabled()) {
                    "AUTO-REPLY CHAT MODE ACTIVE"
                } else {
                    "AUTO-REPLY ON — Grant Notification Access in Systems"
                }
            } else {
                "AUTO-REPLY CHAT MODE OFF"
            }
        }
    }

    fun addScheduledCall(
        hour: Int,
        minute: Int,
        purpose: String,
        actionType: String,
        repeatDaysCsv: String
    ) {
        val cleanPurpose = purpose.trim().ifBlank { "Scheduled JARVIS Call" }
        viewModelScope.launch {
            val entity = ScheduledCallEntity(
                hour = hour.coerceIn(0, 23),
                minute = minute.coerceIn(0, 59),
                purpose = cleanPurpose,
                actionType = actionType,
                repeatDaysCsv = repeatDaysCsv,
                enabled = true
            )
            val id = repository.saveScheduledCall(entity)
            CallAlarmScheduler.scheduleCall(appContext, entity.copy(id = id))
            _localStatusBanner.value = "Scheduled JARVIS call at ${CallAlarmScheduler.formatTime12Hour(hour, minute)}"
        }
    }

    fun toggleScheduledCallEnabled(call: ScheduledCallEntity, enabled: Boolean) {
        viewModelScope.launch {
            val updated = call.copy(enabled = enabled)
            repository.saveScheduledCall(updated)
            if (enabled) {
                CallAlarmScheduler.scheduleCall(appContext, updated)
            } else {
                CallAlarmScheduler.cancelCall(appContext, updated.id)
            }
        }
    }

    fun deleteScheduledCall(call: ScheduledCallEntity) {
        viewModelScope.launch {
            CallAlarmScheduler.cancelCall(appContext, call.id)
            repository.deleteScheduledCall(call)
            _localStatusBanner.value = "Scheduled call removed"
        }
    }

    fun triggerTestIncomingCall() {
        CallAlarmReceiver.launchIncomingCall(
            context = appContext,
            purpose = "Test Call • Subah padhne aur views check briefing",
            actionType = CALL_TYPE_WAKE_UP_STUDY,
            alertSummary = "JARVIS incoming call test triggered from Config."
        )
    }

    fun addScheduledAutomationTask(
        taskType: String,
        target: String,
        messageText: String,
        hour: Int,
        minute: Int,
        repeatDaily: Boolean
    ) {
        val cleanTarget = target.trim()
        val cleanMsg = messageText.trim()
        if (cleanTarget.isEmpty() && taskType != TASK_TYPE_INSTAGRAM_REELS && taskType != TASK_TYPE_ALARM) return

        viewModelScope.launch {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                set(Calendar.MINUTE, minute.coerceIn(0, 59))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis() + 5_000L) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            val appPkg = when (taskType) {
                TASK_TYPE_WHATSAPP_MSG -> "com.whatsapp"
                TASK_TYPE_INSTAGRAM_DM, TASK_TYPE_INSTAGRAM_REELS -> "com.instagram.android"
                TASK_TYPE_YOUTUBE_PLAY -> "com.google.android.youtube"
                else -> ""
            }
            val entity = ScheduledTaskEntity(
                taskType = taskType,
                target = cleanTarget.ifBlank { taskType },
                messageText = cleanMsg,
                appPackage = appPkg,
                triggerAtMillis = cal.timeInMillis,
                repeatDaily = repeatDaily,
                status = TASK_STATUS_PENDING
            )
            TaskSchedulerAndAutomationManager.scheduleTask(appContext, repository, entity)
            val timeLabel = CallAlarmScheduler.formatTime12Hour(hour, minute)
            _localStatusBanner.value = "Scheduled $taskType for $timeLabel"
        }
    }

    fun deleteScheduledAutomationTask(task: ScheduledTaskEntity) {
        viewModelScope.launch {
            TaskSchedulerAndAutomationManager.cancelTask(appContext, task.id)
            repository.deleteScheduledTask(task)
            _localStatusBanner.value = "Scheduled automation task removed"
        }
    }

    fun runScheduledTaskNow(task: ScheduledTaskEntity) {
        viewModelScope.launch {
            _localStatusBanner.value = "Executing ${task.taskType} -> ${task.target}…"
            val resultMsg = TaskSchedulerAndAutomationManager.executeTaskNow(appContext, task)
            repository.updateScheduledTaskStatus(task.id, TASK_STATUS_EXECUTED)
            _localStatusBanner.value = resultMsg
        }
    }

    fun runDiagnosticTapTest(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val service = JarvisAccessibilityService.instance
            if (service == null) {
                _localStatusBanner.value = "Enable JARVIS Accessibility Service first"
                onResult(false)
                return@launch
            }
            val ok = service.performDiagnosticTestTap()
            _localStatusBanner.value = if (ok) {
                "Diagnostic tap succeeded — canPerformGestures ACTIVE"
            } else {
                "Diagnostic tap failed — re-enable JARVIS Accessibility Service"
            }
            onResult(ok)
        }
    }

    fun saveSettings(updated: SettingsEntity) {
        viewModelScope.launch {
            repository.saveSettings(updated)
            _localStatusBanner.value = "JARVIS configuration saved locally"

            if (updated.floatingOverlayEnabled && Settings.canDrawOverlays(appContext)) {
                FloatingOrbService.start(appContext)
            } else {
                FloatingOrbService.stop(appContext)
            }

            if (updated.alwaysOnWakeWordEnabled && _permissionsState.value.microphoneGranted) {
                JarvisWakeWordService.start(appContext)
            } else {
                JarvisWakeWordService.stop(appContext)
            }

            if (updated.onlineAutoCallEnabled) {
                NetworkMonitorService.start(appContext)
            } else {
                NetworkMonitorService.stop(appContext)
            }
        }
    }

    fun verifyAiCoreApiKey(apiKey: String) {
        viewModelScope.launch {
            _keyVerificationState.value = KeyVerificationUiState.Verifying
            when (val res = aiClient.verifyApiKey(apiKey, settings.value.coreEngine)) {
                is KeyVerificationResult.Valid -> {
                    _keyVerificationState.value = KeyVerificationUiState.Verified(res.latencyMs)
                }
                is KeyVerificationResult.Invalid -> {
                    _keyVerificationState.value = KeyVerificationUiState.Failed(res.message)
                }
            }
        }
    }

    fun addUserMemory(factText: String, category: String) {
        if (factText.isBlank()) return
        viewModelScope.launch {
            val saved = memoryManager.saveMemory(
                factText = factText.trim(),
                category = category.ifBlank { memoryManager.inferCategory(factText) }
            )
            if (saved) {
                _localStatusBanner.value = "Saved to JARVIS Memory Vault"
            }
        }
    }

    fun deleteUserMemory(memory: UserMemoryEntity) {
        viewModelScope.launch {
            repository.deleteMemory(memory)
            _localStatusBanner.value = "Memory removed from local vault"
        }
    }

    fun resetVerificationState() {
        _keyVerificationState.value = KeyVerificationUiState.Idle
    }

    fun testVoice(previewSettings: SettingsEntity) {
        previewVoiceEngine.testVoice(previewSettings)
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
            _localStatusBanner.value = "Local macro \"$triggerPhrase\" stored"
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
            _localStatusBanner.value = "Local telemetry logs cleared"
        }
    }

    fun scanCurrentScreenNow() {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            _localStatusBanner.value = "Enable JARVIS Accessibility Service to scan screens"
            return
        }
        val snapshot = service.readScreenHierarchy()
        _localStatusBanner.value = snapshot?.let {
            "Scanned ${it.nodes.size} UI nodes in ${it.packageName}"
        } ?: "No active window nodes returned"
    }

    fun runDirectGestureTest(gestureName: String) {
        viewModelScope.launch {
            val service = JarvisAccessibilityService.instance
            if (service == null) {
                _localStatusBanner.value = "Activate Accessibility Service to run gesture tests"
                return@launch
            }
            val ok = when (gestureName) {
                "SCROLL_DOWN" -> service.scrollDown()
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
            _localStatusBanner.value = if (ok) "Gesture $gestureName dispatched" else "Gesture $gestureName cancelled"
        }
    }

    override fun onCleared() {
        super.onCleared()
        previewVoiceEngine.shutdown()
    }
}
