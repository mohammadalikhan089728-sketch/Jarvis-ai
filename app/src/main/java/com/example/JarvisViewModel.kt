package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    private val offlineProcessor = OfflineCommandProcessor(appContext)
    private val previewVoiceEngine = VoiceEngineManager(appContext, aiClient)
    private val vault = SecureCredentialVault.getInstance(appContext)
    private val intelligenceModules = JarvisIntelligenceModules(appContext, repository, aiClient, offlineProcessor)
    private val youtubeClient = YouTubeAuthAndApiClient(appContext)
    private val clippingEngine = VideoClippingEngine(appContext)

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

    // --- New Upgrade StateFlows (Intelligence Modules, Chess, YouTube Creator Control Center) ---
    val creatorConfig: StateFlow<YouTubeCreatorConfig> = vault.configFlow
    val oauthStatus: StateFlow<String> = YouTubeAuthAndApiClient.oauthStatus

    val projects: StateFlow<List<ProjectMemoryEntity>> = repository.projectsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val missions: StateFlow<List<MissionRunEntity>> = repository.missionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val knowledgeNodes: StateFlow<List<KnowledgeNodeEntity>> = repository.knowledgeNodesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val knowledgeEdges: StateFlow<List<KnowledgeEdgeEntity>> = repository.knowledgeEdgesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val executionStrategies: StateFlow<List<ExecutionStrategyEntity>> = repository.strategiesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val personalCommands: StateFlow<List<PersonalCommandEntity>> = repository.personalCommandsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val chessGames: StateFlow<List<ChessGameEntity>> = repository.chessGamesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val youtubeUploads: StateFlow<List<YouTubeUploadEntity>> = repository.youtubeUploadsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val monitoredVideos: StateFlow<List<MonitoredYouTubeVideoEntity>> = repository.monitoredVideosFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val videoClips: StateFlow<List<VideoClipEntity>> = repository.videoClipsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedChessGameId = MutableStateFlow<Long?>(null)
    val activeChessGame: StateFlow<ChessGameEntity?> = combine(chessGames, _selectedChessGameId) { list, selId ->
        list.firstOrNull { it.id == selId } ?: list.firstOrNull { it.status == "IN_PROGRESS" } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _chessAiThinking = MutableStateFlow(false)
    val chessAiThinking: StateFlow<Boolean> = _chessAiThinking.asStateFlow()
    private val _chessStatusMessage = MutableStateFlow("")
    val chessStatusMessage: StateFlow<String> = _chessStatusMessage.asStateFlow()

    private val _devModeOutput = MutableStateFlow("")
    val devModeOutput: StateFlow<String> = _devModeOutput.asStateFlow()
    private val _whatIfOutput = MutableStateFlow("")
    val whatIfOutput: StateFlow<String> = _whatIfOutput.asStateFlow()
    private val _isIntelBusy = MutableStateFlow(false)
    val isIntelBusy: StateFlow<Boolean> = _isIntelBusy.asStateFlow()
    private val _intelStatusMessage = MutableStateFlow("")
    val intelStatusMessage: StateFlow<String> = _intelStatusMessage.asStateFlow()

    private val _uploadProgress = MutableStateFlow(UploadProgressState())
    val uploadProgress: StateFlow<UploadProgressState> = _uploadProgress.asStateFlow()
    private val _generatedSeo = MutableStateFlow<GeneratedSeoPackage?>(null)
    val generatedSeo: StateFlow<GeneratedSeoPackage?> = _generatedSeo.asStateFlow()
    private val _publicVideoAnalytics = MutableStateFlow<PublicVideoAnalytics?>(null)
    val publicVideoAnalytics: StateFlow<PublicVideoAnalytics?> = _publicVideoAnalytics.asStateFlow()
    private val _channelAnalytics = MutableStateFlow<ChannelAnalyticsReport?>(null)
    val channelAnalytics: StateFlow<ChannelAnalyticsReport?> = _channelAnalytics.asStateFlow()
    private val _videoAuditReport = MutableStateFlow("")
    val videoAuditReport: StateFlow<String> = _videoAuditReport.asStateFlow()
    private val _youtubeStrategyReport = MutableStateFlow("")
    val youtubeStrategyReport: StateFlow<String> = _youtubeStrategyReport.asStateFlow()
    private val _clippingStatus = MutableStateFlow("")
    val clippingStatus: StateFlow<String> = _clippingStatus.asStateFlow()
    private val _isYouTubeBusy = MutableStateFlow(false)
    val isYouTubeBusy: StateFlow<Boolean> = _isYouTubeBusy.asStateFlow()
    private val _youtubeStatusMessage = MutableStateFlow("")
    val youtubeStatusMessage: StateFlow<String> = _youtubeStatusMessage.asStateFlow()
    private val _youtubeTestStatus = MutableStateFlow("")
    val youtubeTestStatus: StateFlow<String> = _youtubeTestStatus.asStateFlow()

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
            YouTubeMilestoneMonitorScheduler.syncPeriodicSchedule(appContext)
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

    // ============================================================================================
    // UPGRADE MODULE ACTIONS: 7 AI FEATURES, CHESS & YOUTUBE CREATOR CONTROL CENTER
    // ============================================================================================

    fun saveProjectMemory(title: String, desc: String, decisions: String, pendingTasks: String, progress: String) {
        viewModelScope.launch {
            val saved = intelligenceModules.createOrResumeProject(title, desc, decisions, pendingTasks, progress)
            _intelStatusMessage.value = "Project \"${saved.title}\" saved & set active."
        }
    }

    fun resumeProjectMemory(project: ProjectMemoryEntity) {
        viewModelScope.launch {
            repository.setActiveProject(project)
            _intelStatusMessage.value = "Resumed project \"${project.title}\"."
        }
    }

    fun deleteProjectMemory(project: ProjectMemoryEntity) {
        viewModelScope.launch {
            repository.deleteProject(project)
            _intelStatusMessage.value = "Removed project \"${project.title}\"."
        }
    }

    fun runMissionGoal(goal: String) {
        viewModelScope.launch {
            _isIntelBusy.value = true
            _intelStatusMessage.value = "Planning sequential mission…"
            val run = intelligenceModules.executeMissionCommand(goal, repository.getSettings()) { status ->
                _intelStatusMessage.value = status
            }
            _intelStatusMessage.value = run.summary
            _isIntelBusy.value = false
        }
    }

    fun deleteMissionRun(mission: MissionRunEntity) {
        viewModelScope.launch { repository.deleteMission(mission) }
    }

    fun runDeveloperMode(mode: String, prompt: String) {
        viewModelScope.launch {
            _isIntelBusy.value = true
            _intelStatusMessage.value = "Generating $mode output via existing Gemini API…"
            val res = intelligenceModules.runDeveloperAssistant(mode, prompt, repository.getSettings())
            res.fold(
                onSuccess = {
                    _devModeOutput.value = it
                    _intelStatusMessage.value = "Developer Mode ($mode) completed."
                },
                onFailure = {
                    _intelStatusMessage.value = it.localizedMessage ?: "Developer Mode failed — check Gemini API key in Config."
                }
            )
            _isIntelBusy.value = false
        }
    }

    fun runWhatIfSimulation(scenario: String) {
        viewModelScope.launch {
            _isIntelBusy.value = true
            _intelStatusMessage.value = "Simulating outcomes & risks…"
            val res = intelligenceModules.runWhatIfSimulation(scenario, repository.getSettings())
            res.fold(
                onSuccess = {
                    _whatIfOutput.value = it
                    _intelStatusMessage.value = "What-If simulation complete."
                },
                onFailure = {
                    _intelStatusMessage.value = it.localizedMessage ?: "What-If failed — check Gemini API key in Config."
                }
            )
            _isIntelBusy.value = false
        }
    }

    fun syncKnowledgeGraph() {
        viewModelScope.launch {
            val links = intelligenceModules.syncKnowledgeGraphFromLocalVault()
            _intelStatusMessage.value = "Knowledge Graph synced ($links connections updated)."
        }
    }

    fun addKnowledgeLink(fromLabel: String, fromType: String, toLabel: String, toType: String, relation: String) {
        viewModelScope.launch {
            val a = repository.upsertKnowledgeNode(fromLabel, fromType)
            val b = repository.upsertKnowledgeNode(toLabel, toType)
            repository.linkKnowledgeNodes(a, b, relation)
            _intelStatusMessage.value = "Linked $fromLabel -> $toLabel ($relation)."
        }
    }

    fun deleteKnowledgeNode(node: KnowledgeNodeEntity) {
        viewModelScope.launch { repository.deleteKnowledgeNode(node) }
    }

    fun toggleApproveStrategy(strategy: ExecutionStrategyEntity, approved: Boolean) {
        viewModelScope.launch {
            repository.saveExecutionStrategy(strategy.copy(approvedByUser = approved))
            _intelStatusMessage.value = if (approved) "Strategy approved." else "Strategy approval revoked."
        }
    }

    fun deleteExecutionStrategy(strategy: ExecutionStrategyEntity) {
        viewModelScope.launch { repository.deleteExecutionStrategy(strategy) }
    }

    fun savePersonalCommand(trigger: String, steps: String, confirmation: String) {
        viewModelScope.launch {
            repository.savePersonalCommand(
                PersonalCommandEntity(
                    triggerPhrase = trigger.trim(),
                    workflowStepsText = steps.trim(),
                    spokenConfirmation = confirmation.trim()
                )
            )
            _intelStatusMessage.value = "Personal command \"$trigger\" saved."
        }
    }

    fun runPersonalCommand(cmd: PersonalCommandEntity) {
        viewModelScope.launch {
            _isIntelBusy.value = true
            val res = intelligenceModules.matchAndExecutePersonalCommand(cmd.triggerPhrase, repository.getSettings()) { st ->
                _intelStatusMessage.value = st
            }
            _intelStatusMessage.value = res?.second ?: "Personal command completed."
            _isIntelBusy.value = false
        }
    }

    fun deletePersonalCommand(cmd: PersonalCommandEntity) {
        viewModelScope.launch { repository.deletePersonalCommand(cmd) }
    }

    // --- Persistent AI Chess ---
    fun createNewChessGame(difficulty: Int) {
        viewModelScope.launch {
            val game = ChessGameEntity(
                title = "JARVIS Match #${(System.currentTimeMillis() % 900) + 100}",
                fen = ChessBoardState.INITIAL_FEN,
                difficulty = difficulty.coerceIn(1, 3),
                status = "IN_PROGRESS"
            )
            val id = repository.saveChessGame(game)
            _selectedChessGameId.value = id
            _chessStatusMessage.value = "New match created. You play White."
        }
    }

    fun selectChessGame(game: ChessGameEntity) {
        _selectedChessGameId.value = game.id
        _chessStatusMessage.value = "Loaded ${game.title}."
    }

    fun deleteChessGame(game: ChessGameEntity) {
        viewModelScope.launch {
            repository.deleteChessGame(game)
            if (_selectedChessGameId.value == game.id) _selectedChessGameId.value = null
        }
    }

    fun makeChessMove(move: ChessMove) = makeChessUciMove(move.toUci())

    fun makeChessUciMove(uci: String) {
        val current = activeChessGame.value ?: return
        if (current.status != "IN_PROGRESS" || _chessAiThinking.value) return

        viewModelScope.launch {
            val board = ChessBoardState.fromFen(current.fen)
            val afterUser = board.applyUciMove(uci)
            if (afterUser == null) {
                _chessStatusMessage.value = "Illegal move: $uci"
                return@launch
            }
            var updatedHistory = listOf(current.movesHistoryCsv, uci).filter { it.isNotBlank() }.joinToString(",")
            var moveCount = current.moveCount + 1
            val afterUserOutcome = afterUser.evaluateOutcome()
            val afterUserStatus = outcomeToStatusString(afterUserOutcome)

            val savedUserTurn = current.copy(
                fen = afterUser.toFen(),
                movesHistoryCsv = updatedHistory,
                moveCount = moveCount,
                status = afterUserStatus,
                updatedAt = System.currentTimeMillis()
            )
            repository.saveChessGame(savedUserTurn)

            if (afterUserOutcome != ChessOutcome.IN_PROGRESS) {
                _chessStatusMessage.value = "Game over: ${afterUserStatus.replace('_', ' ')}"
                return@launch
            }

            _chessAiThinking.value = true
            _chessStatusMessage.value = "You played $uci • JARVIS thinking…"
            val aiMove = withContext(Dispatchers.Default) {
                JarvisChessAiEngine.chooseBestMove(afterUser, current.difficulty)
            }
            _chessAiThinking.value = false

            if (aiMove != null) {
                val afterAi = afterUser.applyMove(aiMove)
                if (afterAi != null) {
                    val aiUci = aiMove.toUci()
                    updatedHistory = "$updatedHistory,$aiUci"
                    moveCount++
                    val finalOutcome = afterAi.evaluateOutcome()
                    val finalStatus = outcomeToStatusString(finalOutcome)
                    repository.saveChessGame(
                        savedUserTurn.copy(
                            fen = afterAi.toFen(),
                            movesHistoryCsv = updatedHistory,
                            moveCount = moveCount,
                            status = finalStatus,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    _chessStatusMessage.value = if (finalOutcome == ChessOutcome.IN_PROGRESS) {
                        "You: $uci • JARVIS: $aiUci. Your move."
                    } else {
                        "JARVIS played $aiUci • ${finalStatus.replace('_', ' ')}"
                    }
                }
            }
        }
    }

    fun resignActiveChessGame() {
        val current = activeChessGame.value ?: return
        viewModelScope.launch {
            repository.saveChessGame(current.copy(status = "RESIGNED", updatedAt = System.currentTimeMillis()))
            _chessStatusMessage.value = "You resigned ${current.title}."
        }
    }

    private fun outcomeToStatusString(outcome: ChessOutcome): String = when (outcome) {
        ChessOutcome.IN_PROGRESS -> "IN_PROGRESS"
        ChessOutcome.CHECKMATE_WHITE_WINS -> "CHECKMATE_USER"
        ChessOutcome.CHECKMATE_BLACK_WINS -> "CHECKMATE_JARVIS"
        ChessOutcome.STALEMATE -> "STALEMATE"
        ChessOutcome.DRAW_FIFTY_MOVE, ChessOutcome.DRAW_INSUFFICIENT_MATERIAL -> "DRAW"
    }

    // --- YouTube Creator Control Center & Encrypted Settings ---
    fun saveCreatorConfig(updated: YouTubeCreatorConfig) {
        vault.saveConfig(updated)
        YouTubeMilestoneMonitorScheduler.syncPeriodicSchedule(appContext)
        _localStatusBanner.value = "YouTube & Creator settings saved in Keystore Vault"
    }

    fun startYouTubeOAuth(activityContext: Context, clientId: String) {
        val res = youtubeClient.startGoogleOAuthFlow(activityContext, clientId)
        res.onFailure { _youtubeTestStatus.value = it.localizedMessage ?: "OAuth launch failed" }
    }

    fun testYouTubeConnection() {
        viewModelScope.launch {
            _youtubeTestStatus.value = "Testing YouTube API & verified channel…"
            val res = youtubeClient.testConnection()
            _youtubeTestStatus.value = res.fold(
                onSuccess = { it },
                onFailure = { "Connection failed: ${it.localizedMessage}" }
            )
        }
    }

    fun disconnectYouTubeAccount() {
        vault.disconnectYouTubeAccount()
        YouTubeAuthAndApiClient.postAuthStatus("YouTube account disconnected and tokens cleared.")
        _channelAnalytics.value = null
        _youtubeTestStatus.value = "Disconnected."
    }

    fun generateYouTubeSeo(topic: String, isShort: Boolean) {
        viewModelScope.launch {
            _isYouTubeBusy.value = true
            _youtubeStatusMessage.value = "Generating SEO metadata with existing Gemini API…"
            val s = repository.getSettings()
            val cfg = vault.loadConfig()
            val res = youtubeClient.generateSeoWithExistingGemini(
                aiClient = aiClient,
                geminiApiKey = s.geminiApiKey,
                selectedEngine = s.coreEngine,
                topicOrDraftTitle = topic,
                isShort = isShort,
                seoLanguage = cfg.seoLanguage,
                seoTone = cfg.seoTone,
                defaultHashtags = cfg.seoDefaultHashtags
            )
            res.fold(
                onSuccess = {
                    _generatedSeo.value = it
                    _youtubeStatusMessage.value = "SEO Title, Description, Tags & Hashtags ready."
                },
                onFailure = {
                    _youtubeStatusMessage.value = it.localizedMessage ?: "SEO generation failed."
                }
            )
            _isYouTubeBusy.value = false
        }
    }

    fun startYouTubeVideoUpload(
        videoUri: Uri,
        thumbnailUri: Uri?,
        title: String,
        description: String,
        tagsCsv: String,
        privacy: String,
        scheduledPublishIsoUtc: String?,
        isShort: Boolean
    ) {
        viewModelScope.launch {
            val cfg = vault.loadConfig()
            val entity = YouTubeUploadEntity(
                uploadType = if (isShort) YT_UPLOAD_TYPE_SHORT else YT_UPLOAD_TYPE_LONG,
                localVideoUri = videoUri.toString(),
                thumbnailUri = thumbnailUri?.toString().orEmpty(),
                title = title.trim(),
                description = description.trim(),
                tagsCsv = tagsCsv.trim(),
                privacyStatus = if (!scheduledPublishIsoUtc.isNullOrBlank()) "private" else privacy,
                scheduledPublishIso = scheduledPublishIsoUtc.orEmpty(),
                channelId = cfg.channelId,
                status = YT_STATUS_UPLOADING,
                progressPercent = 2
            )
            val rowId = repository.saveYouTubeUpload(entity)
            _uploadProgress.value = UploadProgressState(isUploading = true, progressPercent = 2, stageMessage = "Starting upload…")

            val res = youtubeClient.uploadVideoResumable(
                videoUri = videoUri,
                thumbnailUri = thumbnailUri,
                title = title,
                description = description,
                tags = tagsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                privacyStatus = privacy,
                scheduledPublishIsoUtc = scheduledPublishIsoUtc,
                isShort = isShort,
                categoryId = cfg.defaultCategoryId,
                madeForKids = cfg.defaultMadeForKids,
                onProgress = { pct, stage ->
                    _uploadProgress.value = UploadProgressState(isUploading = pct < 100, progressPercent = pct, stageMessage = stage)
                }
            )

            res.fold(
                onSuccess = { videoId ->
                    val finalStatus = if (!scheduledPublishIsoUtc.isNullOrBlank()) YT_STATUS_SCHEDULED else YT_STATUS_PUBLISHED
                    repository.saveYouTubeUpload(
                        entity.copy(id = rowId, youtubeVideoId = videoId, status = finalStatus, progressPercent = 100)
                    )
                    // Auto-register uploaded video in Automatic Milestone Monitoring DB!
                    repository.upsertMonitoredVideo(
                        MonitoredYouTubeVideoEntity(
                            videoId = videoId,
                            title = title.trim(),
                            channelId = cfg.channelId
                        )
                    )
                    YouTubeMilestoneMonitorScheduler.syncPeriodicSchedule(appContext)
                    _uploadProgress.value = UploadProgressState(
                        isUploading = false,
                        progressPercent = 100,
                        stageMessage = "Uploaded & verified! Auto-registered for view milestone monitoring.",
                        lastUploadedVideoId = videoId
                    )
                    _youtubeStatusMessage.value = "Video uploaded (ID: $videoId) and added to Milestone Monitor."
                },
                onFailure = { err ->
                    val msg = err.localizedMessage ?: "Upload failed"
                    repository.saveYouTubeUpload(entity.copy(id = rowId, status = YT_STATUS_FAILED, errorMessage = msg))
                    _uploadProgress.value = UploadProgressState(isUploading = false, progressPercent = 0, stageMessage = msg, lastError = msg)
                    _youtubeStatusMessage.value = "Upload failed: $msg"
                }
            )
        }
    }

    fun retryFailedYouTubeUpload(upload: YouTubeUploadEntity) {
        if (upload.localVideoUri.isBlank()) return
        startYouTubeVideoUpload(
            videoUri = Uri.parse(upload.localVideoUri),
            thumbnailUri = upload.thumbnailUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) },
            title = upload.title,
            description = upload.description,
            tagsCsv = upload.tagsCsv,
            privacy = upload.privacyStatus,
            scheduledPublishIsoUtc = upload.scheduledPublishIso.takeIf { it.isNotBlank() },
            isShort = upload.uploadType == YT_UPLOAD_TYPE_SHORT
        )
    }

    fun saveCommunityPostDraft(title: String, body: String) {
        viewModelScope.launch {
            repository.saveYouTubeUpload(
                YouTubeUploadEntity(
                    uploadType = YT_UPLOAD_TYPE_COMMUNITY,
                    title = title,
                    description = body,
                    status = YT_STATUS_DRAFT
                )
            )
            _youtubeStatusMessage.value = "Community Post draft copied & saved locally. Open YouTube Studio to publish."
        }
    }

    fun generateCommunityPostCopy(topic: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            _isYouTubeBusy.value = true
            _youtubeStatusMessage.value = "Drafting Community Post with existing Gemini API…"
            val s = repository.getSettings()
            val res = aiClient.generateLongAnalysis(
                rawApiKey = s.geminiApiKey,
                systemInstruction = "Draft an engaging YouTube Community Post (hook + short body + optional 4-option poll idea) ready to paste into YouTube Studio.",
                userPrompt = "Topic: $topic",
                selectedEngine = s.coreEngine,
                maxOutputTokens = 500
            )
            res.fold(
                onSuccess = {
                    onResult(it)
                    _youtubeStatusMessage.value = "Community Post draft ready."
                },
                onFailure = { _youtubeStatusMessage.value = it.localizedMessage ?: "Draft generation failed." }
            )
            _isYouTubeBusy.value = false
        }
    }

    fun fetchPublicVideoAnalytics(videoIdOrUrl: String) {
        viewModelScope.launch {
            _isYouTubeBusy.value = true
            _youtubeStatusMessage.value = "Fetching public YouTube video statistics…"
            val res = youtubeClient.fetchPublicVideoStats(videoIdOrUrl)
            res.fold(
                onSuccess = {
                    _publicVideoAnalytics.value = it
                    _youtubeStatusMessage.value = "Loaded stats for \"${it.title}\"."
                },
                onFailure = { _youtubeStatusMessage.value = it.localizedMessage ?: "Video lookup failed." }
            )
            _isYouTubeBusy.value = false
        }
    }

    fun fetchAuthorizedChannelAnalytics() {
        viewModelScope.launch {
            _isYouTubeBusy.value = true
            _youtubeStatusMessage.value = "Loading YouTube Analytics API v2 report…"
            val res = youtubeClient.fetchAuthorizedChannelAnalytics(vault.loadConfig().analyticsWindowDays)
            res.fold(
                onSuccess = {
                    _channelAnalytics.value = it
                    _youtubeStatusMessage.value = "Loaded ${it.windowDays}-day channel analytics (${it.totalViews} views)."
                },
                onFailure = { _youtubeStatusMessage.value = it.localizedMessage ?: "Channel analytics failed." }
            )
            _isYouTubeBusy.value = false
        }
    }

    fun addMonitoredYouTubeVideo(videoIdOrUrl: String) {
        viewModelScope.launch {
            val id = YouTubeAuthAndApiClient.extractYouTubeVideoId(videoIdOrUrl)
            if (id == null) {
                _youtubeStatusMessage.value = "Enter a valid YouTube URL or 11-character Video ID."
                return@launch
            }
            _isYouTubeBusy.value = true
            val stats = youtubeClient.fetchPublicVideoStats(id).getOrNull()
            repository.upsertMonitoredVideo(
                MonitoredYouTubeVideoEntity(
                    videoId = id,
                    title = stats?.title ?: "Tracked Video ($id)",
                    thumbnailUrl = stats?.thumbnailUrl.orEmpty(),
                    channelId = stats?.channelId.orEmpty(),
                    currentViews = stats?.views ?: 0L,
                    previousViews = stats?.views ?: 0L,
                    currentLikes = stats?.likes ?: 0L,
                    currentComments = stats?.comments ?: 0L
                )
            )
            YouTubeMilestoneMonitorScheduler.syncPeriodicSchedule(appContext)
            _youtubeStatusMessage.value = "Now monitoring \"${stats?.title ?: id}\" for configured view milestones."
            _isYouTubeBusy.value = false
        }
    }

    fun deleteMonitoredYouTubeVideo(video: MonitoredYouTubeVideoEntity) {
        viewModelScope.launch { repository.deleteMonitoredVideo(video) }
    }

    fun checkYouTubeMilestonesNow() {
        viewModelScope.launch {
            _isYouTubeBusy.value = true
            _youtubeStatusMessage.value = "Checking tracked videos against view milestones…"
            val res = YouTubeMilestoneMonitorScheduler.runSingleCheckNow(appContext)
            _youtubeStatusMessage.value = res.fold(
                onSuccess = { it.statusMessage },
                onFailure = { "Milestone check error: ${it.message ?: "Unknown error"}" }
            )
            _isYouTubeBusy.value = false
        }
    }

    fun triggerTestYouTubeMilestoneCall() {
        CallAlarmReceiver.launchIncomingCall(
            context = appContext,
            purpose = "YouTube Milestone: 1,000 Views Reached!",
            actionType = CALL_TYPE_YOUTUBE_MILESTONE,
            alertSummary = "Congratulations Sir! Your video 'JARVIS Creator Showcase' just crossed the 1,000 views milestone with 1,042 total views (+185 recent growth). Would you like to discuss our next YouTube strategy?"
        )
    }

    fun runGeminiPreUploadVideoAudit(videoUri: Uri, draftTitle: String, notesOrScript: String) {
        viewModelScope.launch {
            _isYouTubeBusy.value = true
            _youtubeStatusMessage.value = "Sampling video keyframes & running 9-point Gemini Audit…"
            val s = repository.getSettings()
            val res = clippingEngine.runPreUploadVideoAudit(
                videoUri = videoUri,
                videoTitleDraft = draftTitle,
                creatorNotesOrScript = notesOrScript,
                aiClient = aiClient,
                geminiApiKey = s.geminiApiKey,
                selectedEngine = s.coreEngine
            )
            res.fold(
                onSuccess = {
                    _videoAuditReport.value = it
                    _youtubeStatusMessage.value = "Pre-upload Gemini Video Audit complete."
                },
                onFailure = { _youtubeStatusMessage.value = it.localizedMessage ?: "Video audit failed." }
            )
            _isYouTubeBusy.value = false
        }
    }

    fun generateFourVideoClips(videoUri: Uri, vertical916: Boolean, burnCaptions: Boolean) {
        viewModelScope.launch {
            _isYouTubeBusy.value = true
            val existing = repository.getClipsForSource(videoUri.toString())
            val s = repository.getSettings()
            val cfg = vault.loadConfig()
            val res = clippingEngine.generateFourClips(
                videoUri = videoUri,
                existingClipsForSource = existing,
                defaultClipDurationSec = cfg.clipDefaultDurationSec,
                vertical916 = vertical916,
                burnCaptions = burnCaptions,
                aiClient = aiClient,
                geminiApiKey = s.geminiApiKey,
                selectedEngine = s.coreEngine,
                onStatus = { st -> _clippingStatus.value = st }
            )
            res.fold(
                onSuccess = { newClips ->
                    newClips.forEach { repository.saveVideoClip(it) }
                    _youtubeStatusMessage.value = "Generated ${newClips.size} playable MP4 clips."
                },
                onFailure = { err ->
                    val msg = err.localizedMessage ?: "Clipping failed"
                    _clippingStatus.value = msg
                    _youtubeStatusMessage.value = msg
                }
            )
            _isYouTubeBusy.value = false
        }
    }

    fun editAndReExportVideoClip(
        clip: VideoClipEntity,
        startMs: Long,
        endMs: Long,
        caption: String,
        vertical916: Boolean
    ) {
        viewModelScope.launch {
            _isYouTubeBusy.value = true
            _clippingStatus.value = "Re-exporting edited MP4 clip…"
            val res = clippingEngine.reExportEditedClip(clip, startMs, endMs, caption, vertical916)
            res.fold(
                onSuccess = { updated ->
                    repository.saveVideoClip(updated)
                    _clippingStatus.value = "Updated clip \"${updated.title}\" exported."
                    _youtubeStatusMessage.value = "Clip updated & saved as MP4."
                },
                onFailure = { err ->
                    _clippingStatus.value = err.localizedMessage ?: "Re-export failed."
                }
            )
            _isYouTubeBusy.value = false
        }
    }

    fun exportVideoClipToSaf(clipFilePath: String, destUri: Uri) {
        viewModelScope.launch {
            val res = clippingEngine.exportClipToDestinationUri(clipFilePath, destUri)
            _youtubeStatusMessage.value = res.fold(
                onSuccess = { bytes -> "Exported MP4 clip (${bytes / 1024} KB) to selected folder." },
                onFailure = { "Export failed: ${it.localizedMessage}" }
            )
        }
    }

    fun deleteVideoClip(clip: VideoClipEntity) {
        viewModelScope.launch {
            runCatching { File(clip.clipFilePath).delete() }
            runCatching { if (clip.thumbnailPath.isNotBlank()) File(clip.thumbnailPath).delete() }
            repository.deleteVideoClip(clip)
        }
    }

    fun generateYouTubeChannelStrategy() {
        viewModelScope.launch {
            _isYouTubeBusy.value = true
            _youtubeStatusMessage.value = "Analyzing measured YouTube data with existing Gemini API…"
            val s = repository.getSettings()
            val cfg = vault.loadConfig()
            val tracked = repository.getActiveMonitoredVideos()
            val res = youtubeClient.generateChannelStrategyWithExistingGemini(
                aiClient = aiClient,
                geminiApiKey = s.geminiApiKey,
                selectedEngine = s.coreEngine,
                channel = cfg,
                analytics = _channelAnalytics.value,
                monitoredVideos = tracked
            )
            res.fold(
                onSuccess = {
                    _youtubeStrategyReport.value = it
                    _youtubeStatusMessage.value = "Data-grounded YouTube strategy ready."
                },
                onFailure = { _youtubeStatusMessage.value = it.localizedMessage ?: "Strategy generation failed." }
            )
            _isYouTubeBusy.value = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        previewVoiceEngine.shutdown()
    }
}
