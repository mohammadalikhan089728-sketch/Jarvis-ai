package com.example

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ScheduleSend
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.SignalCrimson
import com.example.ui.theme.SignalGreen
import com.example.ui.theme.StarkAmber
import java.util.Calendar
import java.util.Locale

private val WEEKDAY_OPTIONS = listOf(
    Calendar.SUNDAY to "Sun",
    Calendar.MONDAY to "Mon",
    Calendar.TUESDAY to "Tue",
    Calendar.WEDNESDAY to "Wed",
    Calendar.THURSDAY to "Thu",
    Calendar.FRIDAY to "Fri",
    Calendar.SATURDAY to "Sat"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: SettingsEntity,
    keyVerificationState: KeyVerificationUiState,
    activeVoiceLabel: String,
    lastVoiceStatus: String,
    overlayPermissionGranted: Boolean,
    notificationListenerGranted: Boolean,
    wakeWordServiceRunning: Boolean,
    scheduledCalls: List<ScheduledCallEntity>,
    scheduledTasks: List<ScheduledTaskEntity>,
    userMemories: List<UserMemoryEntity>,
    onSaveSettings: (SettingsEntity) -> Unit,
    onVerifyApiKey: (String) -> Unit,
    onResetVerification: () -> Unit,
    onTestVoice: (SettingsEntity) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onOpenDefaultAssistantSettings: () -> Unit,
    onRequestPostNotificationsPermission: () -> Unit,
    onAddScheduledCall: (hour: Int, minute: Int, purpose: String, actionType: String, repeatDaysCsv: String) -> Unit,
    onToggleScheduledCall: (ScheduledCallEntity, Boolean) -> Unit,
    onDeleteScheduledCall: (ScheduledCallEntity) -> Unit,
    onAddScheduledTask: (taskType: String, target: String, messageText: String, hour: Int, minute: Int, repeatDaily: Boolean) -> Unit,
    onDeleteScheduledTask: (ScheduledTaskEntity) -> Unit,
    onRunScheduledTaskNow: (ScheduledTaskEntity) -> Unit,
    onAddUserMemory: (factText: String, category: String) -> Unit,
    onDeleteUserMemory: (UserMemoryEntity) -> Unit,
    onTestIncomingCall: () -> Unit,
    onTestScreenTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val normalizedInitialMode = if (settings.voiceMode == VOICE_MODE_AI_VOICE) {
        VOICE_MODE_AI_VOICE
    } else {
        VOICE_MODE_NORMAL
    }
    val normalizedInitialEngine = if (settings.coreEngine == ENGINE_V35_DEEP) {
        ENGINE_V35_DEEP
    } else {
        ENGINE_V25_LITE
    }

    var apiKey by rememberSaveable { mutableStateOf(settings.geminiApiKey) }
    var showApiKey by rememberSaveable { mutableStateOf(false) }
    var coreEngine by rememberSaveable { mutableStateOf(normalizedInitialEngine) }
    var masterPrompt by rememberSaveable { mutableStateOf(settings.masterSystemPrompt) }
    var voiceMode by rememberSaveable { mutableStateOf(normalizedInitialMode) }
    var aiVoiceName by rememberSaveable { mutableStateOf(settings.aiVoiceName.ifBlank { AI_VOICE_CHARON }) }
    var voicePitch by rememberSaveable { mutableFloatStateOf(settings.voicePitch) }
    var voiceSpeed by rememberSaveable { mutableFloatStateOf(settings.voiceSpeed) }
    var wakeWordEnabled by rememberSaveable { mutableStateOf(settings.wakeWordEnabled) }
    var customWakeWord by rememberSaveable { mutableStateOf(settings.customWakeWord.ifBlank { "Jarvis" }) }
    var alwaysOnWakeWord by rememberSaveable { mutableStateOf(settings.alwaysOnWakeWordEnabled) }
    var onlineAutoCallEnabled by rememberSaveable { mutableStateOf(settings.onlineAutoCallEnabled) }
    var autoReadScreen by rememberSaveable { mutableStateOf(settings.autoReadScreenContext) }
    var floatingOverlay by rememberSaveable { mutableStateOf(settings.floatingOverlayEnabled) }
    var offlineFirst by rememberSaveable { mutableStateOf(settings.offlineFirstMode) }
    var autoReplyEnabled by rememberSaveable { mutableStateOf(settings.autoReplyEnabled) }
    var autoReplyTargetContact by rememberSaveable { mutableStateOf(settings.autoReplyTargetContact) }
    var savedBanner by remember { mutableStateOf<String?>(null) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    var showScheduleTaskDialog by remember { mutableStateOf(false) }
    var showAddMemoryDialog by remember { mutableStateOf(false) }

    // Synchronize local form fields when Room emits saved updates
    LaunchedEffect(settings.updatedAt) {
        apiKey = settings.geminiApiKey
        coreEngine = if (settings.coreEngine == ENGINE_V35_DEEP) ENGINE_V35_DEEP else ENGINE_V25_LITE
        masterPrompt = settings.masterSystemPrompt
        voiceMode = if (settings.voiceMode == VOICE_MODE_AI_VOICE) VOICE_MODE_AI_VOICE else VOICE_MODE_NORMAL
        aiVoiceName = settings.aiVoiceName.ifBlank { AI_VOICE_CHARON }
        voicePitch = settings.voicePitch
        voiceSpeed = settings.voiceSpeed
        wakeWordEnabled = settings.wakeWordEnabled
        customWakeWord = settings.customWakeWord.ifBlank { "Jarvis" }
        alwaysOnWakeWord = settings.alwaysOnWakeWordEnabled
        onlineAutoCallEnabled = settings.onlineAutoCallEnabled
        autoReadScreen = settings.autoReadScreenContext
        floatingOverlay = settings.floatingOverlayEnabled
        offlineFirst = settings.offlineFirstMode
        autoReplyEnabled = settings.autoReplyEnabled
        autoReplyTargetContact = settings.autoReplyTargetContact
    }

    fun buildCurrentEntity(): SettingsEntity {
        return SettingsEntity(
            id = 1,
            geminiApiKey = apiKey.trim(),
            masterSystemPrompt = masterPrompt.trim().ifBlank { DEFAULT_MASTER_PROMPT },
            voiceMode = voiceMode,
            customVoiceUrl = settings.customVoiceUrl,
            voicePitch = voicePitch,
            voiceSpeed = voiceSpeed,
            wakeWordEnabled = wakeWordEnabled,
            autoReadScreenContext = autoReadScreen,
            floatingOverlayEnabled = floatingOverlay,
            offlineFirstMode = offlineFirst,
            autoReplyEnabled = autoReplyEnabled,
            autoReplyTargetContact = autoReplyTargetContact.trim(),
            customWakeWord = customWakeWord.trim().ifBlank { "Jarvis" },
            alwaysOnWakeWordEnabled = alwaysOnWakeWord,
            aiVoiceName = aiVoiceName.ifBlank { AI_VOICE_CHARON },
            onlineAutoCallEnabled = onlineAutoCallEnabled,
            coreEngine = coreEngine
        )
    }

    if (showScheduleDialog) {
        ScheduleCallDialog(
            onDismiss = { showScheduleDialog = false },
            onConfirm = { hour, minute, purpose, actionType, repeatCsv ->
                onRequestPostNotificationsPermission()
                onAddScheduledCall(hour, minute, purpose, actionType, repeatCsv)
                showScheduleDialog = false
                savedBanner = "Scheduled JARVIS call for ${CallAlarmScheduler.formatTime12Hour(hour, minute)}."
            }
        )
    }

    if (showAddMemoryDialog) {
        AddMemoryDialog(
            onDismiss = { showAddMemoryDialog = false },
            onConfirm = { fact, category ->
                onAddUserMemory(fact, category)
                showAddMemoryDialog = false
                savedBanner = "Memory stored permanently in local Room Vault."
            }
        )
    }

    if (showScheduleTaskDialog) {
        AddScheduledTaskDialog(
            onDismiss = { showScheduleTaskDialog = false },
            onConfirm = { taskType, target, messageText, hour, minute, repeatDaily ->
                onRequestPostNotificationsPermission()
                onAddScheduledTask(taskType, target, messageText, hour, minute, repeatDaily)
                showScheduleTaskDialog = false
                savedBanner = "Scheduled automation task for ${CallAlarmScheduler.formatTime12Hour(hour, minute)}."
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "JARVIS CONFIGURATION",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Developed by Mozahid • 100% Local Room Vault",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = {
                        val entity = buildCurrentEntity()
                        onSaveSettings(entity)
                        savedBanner = "All JARVIS parameters saved locally."
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = ObsidianVoid
                    ),
                    modifier = Modifier.testTag("settings_save_all_button")
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("SAVE")
                }
            }

            if (savedBanner != null) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = SignalGreen.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, SignalGreen.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = savedBanner.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = SignalGreen,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // 1. Default Digital Assistant & Screen Touch Control Diagnostics
            SettingsSectionCard(
                title = "SYSTEM ASSISTANT & TOUCH ENGINE",
                icon = Icons.Filled.Assistant
            ) {
                Text(
                    text = "Register JARVIS as Android's official Default Digital Assistant so power-button hold or corner swipe launches JARVIS directly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onOpenDefaultAssistantSettings,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ArcCyan,
                        contentColor = ObsidianVoid
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("set_default_digital_assistant_button")
                ) {
                    Icon(Icons.Filled.Assistant, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("SET JARVIS AS DEFAULT DIGITAL ASSISTANT")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onTestScreenTap,
                    border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_test_screen_tap_button")
                ) {
                    Icon(
                        Icons.Filled.TouchApp,
                        contentDescription = null,
                        tint = ArcCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("TEST SCREEN TAP (GESTURE CHECK)", color = ArcCyan)
                }
            }

            // 2. Always-On Background Wake Word Detection
            SettingsSectionCard(
                title = "ALWAYS-ON BACKGROUND WAKE WORD",
                icon = Icons.Filled.Hearing
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = (if (wakeWordServiceRunning) SignalGreen else StarkAmber).copy(alpha = 0.14f),
                        border = BorderStroke(
                            1.dp,
                            (if (wakeWordServiceRunning) SignalGreen else StarkAmber).copy(alpha = 0.55f)
                        )
                    ) {
                        Text(
                            text = if (wakeWordServiceRunning) {
                                "BACKGROUND ENGINE: ACTIVE"
                            } else {
                                "BACKGROUND ENGINE: STANDBY"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (wakeWordServiceRunning) SignalGreen else StarkAmber,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                BehaviorToggleRow(
                    title = "Always-On Wake Word (Works When App is Closed)",
                    subtitle = "Runs a sticky foreground microphone service that wakes JARVIS even when killed from recents or screen is locked",
                    checked = alwaysOnWakeWord,
                    onCheckedChange = { enable ->
                        if (enable) onRequestPostNotificationsPermission()
                        alwaysOnWakeWord = enable
                        onSaveSettings(buildCurrentEntity())
                        savedBanner = if (enable) {
                            "Always-On background wake word enabled for \"${customWakeWord.ifBlank { "Jarvis" }}\"."
                        } else {
                            "Always-On background wake word turned off."
                        }
                    },
                    tag = "toggle_always_on_wake_word"
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = customWakeWord,
                    onValueChange = { customWakeWord = it },
                    label = { Text("Custom Wake Word") },
                    placeholder = { Text("Jarvis, Hey Jarvis, Babua, Sun Jarvis…") },
                    singleLine = true,
                    colors = hudTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_wake_word_input")
                )

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        onSaveSettings(buildCurrentEntity())
                        savedBanner = "Custom wake word set to \"${customWakeWord.trim().ifBlank { "Jarvis" }}\"."
                    },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_wake_word_button")
                ) {
                    Text("APPLY WAKE WORD")
                }
            }

            // 3. Two Clean Voice Modes: Normal Voice vs With AI Voice
            SettingsSectionCard(
                title = "JARVIS VOICE MODES",
                icon = Icons.Filled.GraphicEq
            ) {
                VoiceOptionRow(
                    title = "Normal Voice (Zero-Audio-Token Neural TTS)",
                    subtitle = "Default • Fast streaming + Neural Male Speech ($activeVoiceLabel, 0 audio tokens) • 10x daily quota saver",
                    selected = voiceMode == VOICE_MODE_NORMAL,
                    onSelect = {
                        voiceMode = VOICE_MODE_NORMAL
                        onSaveSettings(buildCurrentEntity())
                    },
                    tag = "voice_mode_normal_radio"
                )
                Spacer(modifier = Modifier.height(8.dp))
                VoiceOptionRow(
                    title = "With AI Voice (Single Persistent Live Session)",
                    subtitle = "ONE live audio link (Charon / Orus deep male Hindi/Hinglish voice) • Zero per-turn REST loop • Auto-cooldown & offline fallback",
                    badgeText = "LIVE AUDIO",
                    badgeColor = ArcCyan,
                    selected = voiceMode == VOICE_MODE_AI_VOICE,
                    onSelect = {
                        voiceMode = VOICE_MODE_AI_VOICE
                        onSaveSettings(buildCurrentEntity())
                    },
                    tag = "voice_mode_ai_radio"
                )

                if (voiceMode == VOICE_MODE_AI_VOICE) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "SELECT AI MALE VOICE PROFILE",
                        style = MaterialTheme.typography.labelMedium,
                        color = ArcCyan
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = aiVoiceName == AI_VOICE_CHARON,
                            onClick = {
                                aiVoiceName = AI_VOICE_CHARON
                                onSaveSettings(buildCurrentEntity())
                            },
                            label = { Text("Charon") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ArcCyan.copy(alpha = 0.22f),
                                selectedLabelColor = ArcCyan
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("ai_voice_charon_chip")
                        )
                        FilterChip(
                            selected = aiVoiceName == AI_VOICE_ORUS,
                            onClick = {
                                aiVoiceName = AI_VOICE_ORUS
                                onSaveSettings(buildCurrentEntity())
                            },
                            label = { Text("Orus") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ArcCyan.copy(alpha = 0.22f),
                                selectedLabelColor = ArcCyan
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("ai_voice_orus_chip")
                        )
                        FilterChip(
                            selected = aiVoiceName == AI_VOICE_FENRIR,
                            onClick = {
                                aiVoiceName = AI_VOICE_FENRIR
                                onSaveSettings(buildCurrentEntity())
                            },
                            label = { Text("Fenrir") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ArcCyan.copy(alpha = 0.22f),
                                selectedLabelColor = ArcCyan
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("ai_voice_fenrir_chip")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = String.format(Locale.US, "VOICE PITCH: %.2fx (Default 0.92x)", voicePitch),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Slider(
                    value = voicePitch,
                    onValueChange = { voicePitch = it },
                    valueRange = 0.60f..1.40f,
                    colors = SliderDefaults.colors(
                        thumbColor = ArcCyan,
                        activeTrackColor = ArcCyan
                    ),
                    modifier = Modifier.testTag("voice_pitch_slider")
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = String.format(Locale.US, "SPEECH SPEED: %.2fx (Default 1.02x)", voiceSpeed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Slider(
                    value = voiceSpeed,
                    onValueChange = { voiceSpeed = it },
                    valueRange = 0.70f..1.50f,
                    colors = SliderDefaults.colors(
                        thumbColor = ArcCyan,
                        activeTrackColor = ArcCyan
                    ),
                    modifier = Modifier.testTag("voice_speed_slider")
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            val preview = buildCurrentEntity()
                            onSaveSettings(preview)
                            onTestVoice(preview)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("test_voice_button")
                    ) {
                        Icon(Icons.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("TEST AUDIO")
                    }
                    OutlinedButton(
                        onClick = {
                            voicePitch = 0.92f
                            voiceSpeed = 1.02f
                        },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Text("JARVIS TONE")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "STATUS: $lastVoiceStatus",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 4. JARVIS Incoming Call System ("JARVIS Calls You")
            SettingsSectionCard(
                title = "JARVIS CALLS YOU (SCHEDULED & AUTO-CALLS)",
                icon = Icons.Filled.PhoneInTalk
            ) {
                Text(
                    text = "Schedule unlimited lock-screen calls from JARVIS for study wake-ups, YouTube/Instagram views check, or custom reminders. You can also create calls by voice (e.g., \"Kal subah 6 baje padhne ke liye call karna\").",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                BehaviorToggleRow(
                    title = "Auto-Call When Online / Viral Alert",
                    subtitle = "Detects viral milestones, notification surges (15+ in 5 min), or urgent messages via Notification Access and calls you automatically when online",
                    checked = onlineAutoCallEnabled,
                    onCheckedChange = { enable ->
                        if (enable) onRequestPostNotificationsPermission()
                        onlineAutoCallEnabled = enable
                        onSaveSettings(buildCurrentEntity())
                    },
                    tag = "toggle_online_auto_call"
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showScheduleDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ArcCyan,
                            contentColor = ObsidianVoid
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("add_scheduled_call_button")
                    ) {
                        Icon(Icons.Filled.AddAlert, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("SCHEDULE CALL")
                    }
                    OutlinedButton(
                        onClick = {
                            onRequestPostNotificationsPermission()
                            onTestIncomingCall()
                        },
                        border = BorderStroke(1.dp, SignalGreen.copy(alpha = 0.75f)),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("test_incoming_call_button")
                    ) {
                        Icon(
                            Icons.Filled.Call,
                            contentDescription = null,
                            tint = SignalGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("TEST CALL", color = SignalGreen)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (scheduledCalls.isEmpty()) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "No scheduled calls yet. Tap \"SCHEDULE CALL\" above or tell JARVIS by voice.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        scheduledCalls.forEach { call ->
                            ScheduledCallRowCard(
                                call = call,
                                onToggle = { enabled -> onToggleScheduledCall(call, enabled) },
                                onDelete = { onDeleteScheduledCall(call) }
                            )
                        }
                    }
                }
            }

            // 4A. Scheduled Autonomous Tasks (WhatsApp, Instagram DM, SMS, Direct Calls, Alarms)
            SettingsSectionCard(
                title = "SCHEDULED AUTOMATION TASKS (${scheduledTasks.size})",
                icon = Icons.Filled.ScheduleSend
            ) {
                Text(
                    text = "Automate WhatsApp messages, Instagram DMs, SMS, calls, or alarms at exact times using AlarmManager + WorkManager + Lock-Screen Wake. Or command by voice: \"Shaam 6 baje Rahul ko WhatsApp par Hello bhej dena\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { showScheduleTaskDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ArcCyan,
                        contentColor = ObsidianVoid
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_scheduled_task_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ADD AUTOMATION TASK")
                }
                Spacer(modifier = Modifier.height(10.dp))

                if (scheduledTasks.isEmpty()) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "No scheduled automation tasks yet. Tap ADD AUTOMATION TASK or schedule by voice.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        scheduledTasks.take(20).forEach { task ->
                            ScheduledTaskRowCard(
                                task = task,
                                onRunNow = { onRunScheduledTaskNow(task) },
                                onDelete = { onDeleteScheduledTask(task) }
                            )
                        }
                    }
                }
            }

            // 4B. JARVIS Core Engine Selector (2 Selectable Engines + Silent Automatic Failover)
            SettingsSectionCard(
                title = "JARVIS CORE ENGINE",
                icon = Icons.Filled.Memory
            ) {
                Text(
                    text = "Select your active JARVIS intelligence core. Silent automatic failover switches instantly if one tier hits a rate limit or network hiccup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                VoiceOptionRow(
                    title = "Core Engine v2.5 (Ultra-Fast & Max Daily Limit — Default)",
                    subtitle = "Optimized for 1,000+ daily requests, zero-thinking latency, and instant voice replies",
                    badgeText = "10X QUOTA",
                    badgeColor = SignalGreen,
                    selected = coreEngine == ENGINE_V25_LITE,
                    onSelect = {
                        coreEngine = ENGINE_V25_LITE
                        onSaveSettings(buildCurrentEntity())
                        savedBanner = "Active engine switched to JARVIS Core Engine v2.5."
                    },
                    tag = "core_engine_v25_radio"
                )
                Spacer(modifier = Modifier.height(8.dp))
                VoiceOptionRow(
                    title = "Core Engine v3.5 (Deep Reasoning Backup)",
                    subtitle = "High-precision reasoning tier with automatic fallback to v2.5 if rate-limited",
                    badgeText = "DEEP BACKUP",
                    badgeColor = ArcCyan,
                    selected = coreEngine == ENGINE_V35_DEEP,
                    onSelect = {
                        coreEngine = ENGINE_V35_DEEP
                        onSaveSettings(buildCurrentEntity())
                        savedBanner = "Active engine switched to JARVIS Core Engine v3.5."
                    },
                    tag = "core_engine_v35_radio"
                )
            }

            // 4C. JARVIS Long-Term Memory Vault (100% Local Room DB)
            SettingsSectionCard(
                title = "JARVIS MEMORY VAULT (${userMemories.size})",
                icon = Icons.Filled.Storage
            ) {
                Text(
                    text = "JARVIS remembers your personal facts, routines, and goals 100% locally. Say \"Yaad rakhna...\" or \"Remember...\" anytime — only the top 6 relevant facts are injected to keep token usage ultra-low.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { showAddMemoryDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ArcCyan,
                        contentColor = ObsidianVoid
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_memory_vault_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ADD MEMORY FACT")
                }
                Spacer(modifier = Modifier.height(10.dp))

                if (userMemories.isEmpty()) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "No long-term memories stored yet. Say \"Yaad rakhna mera naam...\" or tap ADD MEMORY FACT above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        userMemories.take(25).forEach { memory ->
                            MemoryVaultRowCard(
                                memory = memory,
                                onDelete = { onDeleteUserMemory(memory) }
                            )
                        }
                    }
                }
            }

            // 5. JARVIS Neural Core Key Section (Strictly zero third-party model branding)
            SettingsSectionCard(
                title = "JARVIS NEURAL CORE",
                icon = Icons.Filled.Key
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ArcCyan.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = if (coreEngine == ENGINE_V35_DEEP) {
                            "ACTIVE CORE: JARVIS ENGINE v3.5"
                        } else {
                            "ACTIVE CORE: JARVIS ENGINE v2.5"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = ArcCyan,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        onResetVerification()
                    },
                    label = { Text("JARVIS AI Core API Key") },
                    placeholder = { Text("Paste your neural access key") },
                    singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(
                            onClick = { showApiKey = !showApiKey },
                            modifier = Modifier.testTag("toggle_api_key_visibility")
                        ) {
                            Icon(
                                imageVector = if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(R.string.cd_toggle_key_visibility)
                            )
                        }
                    },
                    colors = hudTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("jarvis_api_key_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onSaveSettings(buildCurrentEntity())
                            onVerifyApiKey(apiKey)
                        },
                        enabled = keyVerificationState !is KeyVerificationUiState.Verifying,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("verify_api_key_button")
                    ) {
                        if (keyVerificationState is KeyVerificationUiState.Verifying) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = ObsidianVoid,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("VERIFYING…")
                        } else {
                            Text("VERIFY KEY")
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            apiKey = ""
                            onResetVerification()
                            onSaveSettings(buildCurrentEntity())
                            savedBanner = "Neural access key cleared from local vault."
                        },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.testTag("clear_api_key_button")
                    ) {
                        Text("CLEAR")
                    }
                }

                when (keyVerificationState) {
                    is KeyVerificationUiState.Verified -> {
                        Spacer(modifier = Modifier.height(10.dp))
                        VerificationBanner(
                            icon = Icons.Filled.CheckCircle,
                            color = SignalGreen,
                            message = "JARVIS AI Core verified (${keyVerificationState.latencyMs}ms)"
                        )
                    }
                    is KeyVerificationUiState.Failed -> {
                        Spacer(modifier = Modifier.height(10.dp))
                        VerificationBanner(
                            icon = Icons.Filled.ErrorOutline,
                            color = SignalCrimson,
                            message = keyVerificationState.reason
                        )
                    }
                    else -> Unit
                }
            }

            // 6. Editable Master System Prompt
            SettingsSectionCard(
                title = "MASTER SYSTEM PROMPT",
                icon = Icons.Filled.Psychology
            ) {
                Text(
                    text = "Injected dynamically into every JARVIS AI Core request. Customize personality, Hindi/Hinglish/English style, and phone automation priorities.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = masterPrompt,
                    onValueChange = { masterPrompt = it },
                    label = { Text("JARVIS Core Directive") },
                    minLines = 6,
                    maxLines = 14,
                    colors = hudTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("master_prompt_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onSaveSettings(buildCurrentEntity())
                            savedBanner = "Master System Prompt updated."
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_master_prompt_button")
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("SAVE PROMPT")
                    }
                    OutlinedButton(
                        onClick = {
                            masterPrompt = DEFAULT_MASTER_PROMPT
                            onSaveSettings(buildCurrentEntity())
                            savedBanner = "Master System Prompt reset to JARVIS defaults."
                        },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.testTag("reset_master_prompt_button")
                    ) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("RESET")
                    }
                }
            }

            // 7. Agent Behavior Protocols
            SettingsSectionCard(
                title = "AGENT BEHAVIOR PROTOCOLS",
                icon = Icons.Filled.Tune
            ) {
                BehaviorToggleRow(
                    title = "In-App Passive Wake Phrase",
                    subtitle = "Listen for \"${customWakeWord.ifBlank { "Jarvis" }}\" while JARVIS HUD is open in foreground",
                    checked = wakeWordEnabled,
                    onCheckedChange = {
                        wakeWordEnabled = it
                        onSaveSettings(buildCurrentEntity())
                    },
                    tag = "toggle_wake_word"
                )
                BehaviorToggleRow(
                    title = "Auto-Read Screen Context",
                    subtitle = "Attach live Accessibility UI coordinates to every JARVIS AI Core automation step",
                    checked = autoReadScreen,
                    onCheckedChange = {
                        autoReadScreen = it
                        onSaveSettings(buildCurrentEntity())
                    },
                    tag = "toggle_auto_read_screen"
                )
                BehaviorToggleRow(
                    title = "Floating Overlay Orb",
                    subtitle = if (overlayPermissionGranted) {
                        "Show draggable JARVIS Arc Reactor orb over other apps"
                    } else {
                        "Requires Display Over Other Apps permission (tap to grant)"
                    },
                    checked = floatingOverlay,
                    onCheckedChange = { enable ->
                        if (enable && !overlayPermissionGranted) {
                            onRequestOverlayPermission()
                        } else {
                            floatingOverlay = enable
                            onSaveSettings(buildCurrentEntity())
                        }
                    },
                    tag = "toggle_floating_orb"
                )
                BehaviorToggleRow(
                    title = "Offline-First Mode (<100ms)",
                    subtitle = "Execute device controls, apps, scrolls, drags, and zooms locally before calling AI",
                    checked = offlineFirst,
                    onCheckedChange = {
                        offlineFirst = it
                        onSaveSettings(buildCurrentEntity())
                    },
                    tag = "toggle_offline_first"
                )
                BehaviorToggleRow(
                    title = "Auto-Reply / Chat Mode (WhatsApp, Telegram, SMS)",
                    subtitle = if (notificationListenerGranted) {
                        "Automatically reply in background via JARVIS AI Core and log chats locally"
                    } else {
                        "Notification Access required — tap to grant or toggle on"
                    },
                    checked = autoReplyEnabled,
                    onCheckedChange = { enable ->
                        autoReplyEnabled = enable
                        onSaveSettings(buildCurrentEntity())
                        if (enable && !notificationListenerGranted) {
                            onOpenNotificationListenerSettings()
                        }
                    },
                    tag = "toggle_auto_reply_settings"
                )

                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = autoReplyTargetContact,
                    onValueChange = { autoReplyTargetContact = it },
                    label = { Text("Auto-Reply Contact Filter (Optional)") },
                    placeholder = { Text("Leave blank for all chats, or enter e.g. Ashif") },
                    singleLine = true,
                    colors = hudTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("auto_reply_contact_filter_input")
                )

                if (!notificationListenerGranted) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onOpenNotificationListenerSettings,
                        border = BorderStroke(1.dp, StarkAmber.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_open_notification_access_button")
                    ) {
                        Text(
                            text = "ENABLE NOTIFICATION ACCESS FOR AUTO-REPLY & ALERTS",
                            color = StarkAmber
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ScheduledCallRowCard(
    call: ScheduledCallEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val typeLabel = when (call.actionType) {
        CALL_TYPE_WAKE_UP_STUDY -> "WAKE-UP / STUDY"
        CALL_TYPE_SMART_VIEWS_CHECK -> "SMART VIEWS CHECK"
        else -> "CUSTOM REMINDER"
    }
    val typeColor = when (call.actionType) {
        CALL_TYPE_WAKE_UP_STUDY -> StarkAmber
        CALL_TYPE_SMART_VIEWS_CHECK -> SignalGreen
        else -> ArcCyan
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (call.enabled) typeColor.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("scheduled_call_card_${call.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = CallAlarmScheduler.formatTime12Hour(call.hour, call.minute),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (call.enabled) ArcCyan else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = typeColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, typeColor.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = typeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = typeColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = call.purpose,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Repeat: ${CallAlarmScheduler.formatRepeatLabel(call.repeatDaysCsv)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = call.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = ObsidianVoid,
                    checkedTrackColor = ArcCyan
                ),
                modifier = Modifier.testTag("toggle_scheduled_call_${call.id}")
            )
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_scheduled_call_${call.id}")
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete scheduled call",
                    tint = SignalCrimson
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScheduleCallDialog(
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int, purpose: String, actionType: String, repeatCsv: String) -> Unit
) {
    val now = remember { Calendar.getInstance() }
    val timeState = rememberTimePickerState(
        initialHour = now.get(Calendar.HOUR_OF_DAY),
        initialMinute = (now.get(Calendar.MINUTE) + 5) % 60,
        is24Hour = false
    )
    var purpose by remember { mutableStateOf("") }
    var actionType by remember { mutableStateOf(CALL_TYPE_WAKE_UP_STUDY) }
    var repeatMode by remember { mutableStateOf("DAILY") } // "ONCE", "DAILY", "CUSTOM"
    val selectedDays = remember {
        mutableStateListOf(
            Calendar.MONDAY,
            Calendar.TUESDAY,
            Calendar.WEDNESDAY,
            Calendar.THURSDAY,
            Calendar.FRIDAY
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = {
            Text(
                text = "Schedule JARVIS Incoming Call",
                style = MaterialTheme.typography.titleMedium,
                color = ArcCyan
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TimeInput(state = timeState)

                OutlinedTextField(
                    value = purpose,
                    onValueChange = { purpose = it },
                    label = { Text("Call Purpose") },
                    placeholder = { Text("e.g. Subah padhne ke liye uthana hai") },
                    singleLine = false,
                    maxLines = 3,
                    colors = hudTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("schedule_call_purpose_input")
                )

                Text(
                    text = "Call Mode",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = actionType == CALL_TYPE_WAKE_UP_STUDY,
                        onClick = { actionType = CALL_TYPE_WAKE_UP_STUDY },
                        label = { Text("Wake-Up / Study") }
                    )
                    FilterChip(
                        selected = actionType == CALL_TYPE_SMART_VIEWS_CHECK,
                        onClick = { actionType = CALL_TYPE_SMART_VIEWS_CHECK },
                        label = { Text("Views Check") }
                    )
                    FilterChip(
                        selected = actionType == CALL_TYPE_CUSTOM_REMINDER,
                        onClick = { actionType = CALL_TYPE_CUSTOM_REMINDER },
                        label = { Text("Reminder") }
                    )
                }

                Text(
                    text = "Repeat Schedule",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = repeatMode == "ONCE",
                        onClick = { repeatMode = "ONCE" },
                        label = { Text("Once") }
                    )
                    FilterChip(
                        selected = repeatMode == "DAILY",
                        onClick = { repeatMode = "DAILY" },
                        label = { Text("Daily") }
                    )
                    FilterChip(
                        selected = repeatMode == "CUSTOM",
                        onClick = { repeatMode = "CUSTOM" },
                        label = { Text("Custom Days") }
                    )
                }

                if (repeatMode == "CUSTOM") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        WEEKDAY_OPTIONS.forEach { (dayInt, label) ->
                            val isSelected = selectedDays.contains(dayInt)
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (isSelected && selectedDays.size > 1) {
                                        selectedDays.remove(dayInt)
                                    } else if (!isSelected) {
                                        selectedDays.add(dayInt)
                                    }
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val repeatCsv = when (repeatMode) {
                        "ONCE" -> "ONCE"
                        "CUSTOM" -> selectedDays.sorted().joinToString(",")
                        else -> "DAILY"
                    }
                    val finalPurpose = purpose.trim().ifBlank {
                        when (actionType) {
                            CALL_TYPE_WAKE_UP_STUDY -> "Subah padhne ke liye wake-up call"
                            CALL_TYPE_SMART_VIEWS_CHECK -> "YouTube / Instagram views check briefing"
                            else -> "Scheduled JARVIS reminder call"
                        }
                    }
                    onConfirm(timeState.hour, timeState.minute, finalPurpose, actionType, repeatCsv)
                },
                modifier = Modifier.testTag("confirm_schedule_call_button")
            ) {
                Text("SAVE CALL")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL")
            }
        }
    )
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun MemoryVaultRowCard(
    memory: UserMemoryEntity,
    onDelete: () -> Unit
) {
    val badgeColor = when (memory.category) {
        MEMORY_CAT_PROFILE -> ArcCyan
        MEMORY_CAT_ROUTINE -> SignalGreen
        MEMORY_CAT_GOAL -> StarkAmber
        MEMORY_CAT_PREFERENCE -> Color(0xFFA78BFA)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("memory_vault_card_${memory.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = badgeColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = memory.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = memory.factText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_memory_${memory.id}")
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete memory",
                    tint = SignalCrimson
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddMemoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (factText: String, category: String) -> Unit
) {
    var factText by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(MEMORY_CAT_PROFILE) }
    val categories = listOf(
        MEMORY_CAT_PROFILE,
        MEMORY_CAT_ROUTINE,
        MEMORY_CAT_GOAL,
        MEMORY_CAT_PREFERENCE,
        MEMORY_CAT_GENERAL
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = {
            Text(
                text = "Add to JARVIS Memory Vault",
                style = MaterialTheme.typography.titleMedium,
                color = ArcCyan
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = factText,
                    onValueChange = { factText = it },
                    label = { Text("Personal Fact / Routine / Preference") },
                    placeholder = { Text("e.g. Subah 6 baje Maths padhna hai") },
                    minLines = 2,
                    maxLines = 4,
                    colors = hudTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_memory_fact_input")
                )
                Text(
                    text = "Category",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (factText.trim().length >= 4) {
                        onConfirm(factText.trim(), category)
                    }
                },
                enabled = factText.trim().length >= 4,
                modifier = Modifier.testTag("confirm_add_memory_button")
            ) {
                Text("SAVE MEMORY")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL")
            }
        }
    )
}

@Composable
private fun VoiceOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    tag: String,
    badgeText: String? = null,
    badgeColor: Color = StarkAmber
) {
    Surface(
        onClick = onSelect,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) ArcCyan.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) ArcCyan else MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(selectedColor = ArcCyan)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!badgeText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeColor.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.55f))
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BehaviorToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ObsidianVoid,
                checkedTrackColor = ArcCyan
            ),
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
private fun VerificationBanner(
    icon: ImageVector,
    color: Color,
    message: String
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = color
            )
        }
    }
}

@Composable
private fun ScheduledTaskRowCard(
    task: ScheduledTaskEntity,
    onRunNow: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColor = when (task.status) {
        TASK_STATUS_EXECUTED -> SignalGreen
        TASK_STATUS_FAILED -> SignalCrimson
        else -> ArcCyan
    }
    val timeLabel = remember(task.triggerAtMillis) {
        java.text.SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault())
            .format(java.util.Date(task.triggerAtMillis))
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("scheduled_task_row_${task.id}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = statusColor.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.55f))
                    ) {
                        Text(
                            text = task.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = task.taskType.replace('_', ' '),
                        style = MaterialTheme.typography.labelMedium,
                        color = ArcCyan
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (task.messageText.isNotBlank()) {
                        "${task.target} • \"${task.messageText}\""
                    } else {
                        task.target
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$timeLabel${if (task.repeatDaily) " • Daily" else " • Once"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onRunNow,
                modifier = Modifier.testTag("run_scheduled_task_${task.id}")
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Run automation task now",
                    tint = SignalGreen
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_scheduled_task_${task.id}")
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete scheduled task",
                    tint = SignalCrimson
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddScheduledTaskDialog(
    onDismiss: () -> Unit,
    onConfirm: (taskType: String, target: String, messageText: String, hour: Int, minute: Int, repeatDaily: Boolean) -> Unit
) {
    val nowCal = remember { Calendar.getInstance().apply { add(Calendar.MINUTE, 5) } }
    val timeState = rememberTimePickerState(
        initialHour = nowCal.get(Calendar.HOUR_OF_DAY),
        initialMinute = nowCal.get(Calendar.MINUTE),
        is24Hour = false
    )
    var selectedType by remember { mutableStateOf(TASK_TYPE_WHATSAPP_MSG) }
    var target by remember { mutableStateOf("") }
    var messageText by remember { mutableStateOf("") }
    var repeatDaily by remember { mutableStateOf(false) }

    val typeOptions = listOf(
        TASK_TYPE_WHATSAPP_MSG to "WhatsApp",
        TASK_TYPE_INSTAGRAM_DM to "Insta DM",
        TASK_TYPE_PHONE_CALL to "Phone Call",
        TASK_TYPE_SMS to "SMS",
        TASK_TYPE_ALARM to "Alarm",
        TASK_TYPE_OPEN_APP to "Open App"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = {
            Text(
                text = "SCHEDULE AUTOMATION TASK",
                style = MaterialTheme.typography.titleMedium,
                color = ArcCyan
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    typeOptions.forEach { (key, label) ->
                        FilterChip(
                            selected = selectedType == key,
                            onClick = { selectedType = key },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ArcCyan.copy(alpha = 0.22f),
                                selectedLabelColor = ArcCyan
                            )
                        )
                    }
                }

                TimeInput(state = timeState)

                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = {
                        Text(
                            when (selectedType) {
                                TASK_TYPE_OPEN_APP -> "App Name (e.g., YouTube, Spotify)"
                                TASK_TYPE_ALARM -> "Alarm Label"
                                else -> "Contact Name / Phone / Handle"
                            }
                        )
                    },
                    singleLine = true,
                    colors = hudTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("scheduled_task_target_input")
                )

                if (selectedType == TASK_TYPE_WHATSAPP_MSG ||
                    selectedType == TASK_TYPE_INSTAGRAM_DM ||
                    selectedType == TASK_TYPE_SMS
                ) {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        label = { Text("Message Text") },
                        minLines = 2,
                        maxLines = 4,
                        colors = hudTextFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("scheduled_task_message_input")
                    )
                }

                BehaviorToggleRow(
                    title = "Repeat Daily",
                    subtitle = "Re-arm automatically every 24 hours after execution",
                    checked = repeatDaily,
                    onCheckedChange = { repeatDaily = it },
                    tag = "scheduled_task_repeat_daily_toggle"
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val effectiveTarget = target.trim().ifBlank {
                        if (selectedType == TASK_TYPE_ALARM) "J.A.R.V.I.S. Alarm" else ""
                    }
                    if (effectiveTarget.isNotEmpty()) {
                        onConfirm(
                            selectedType,
                            effectiveTarget,
                            messageText.trim(),
                            timeState.hour,
                            timeState.minute,
                            repeatDaily
                        )
                    }
                },
                enabled = target.trim().isNotEmpty() || selectedType == TASK_TYPE_ALARM,
                modifier = Modifier.testTag("confirm_add_scheduled_task_button")
            ) {
                Text("SCHEDULE")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL")
            }
        }
    )
}

@Composable
private fun hudTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface
)
