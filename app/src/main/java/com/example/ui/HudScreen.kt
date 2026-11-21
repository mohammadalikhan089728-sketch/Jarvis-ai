package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ChatHistoryEntity
import com.example.JarvisPhase
import com.example.PermissionsSnapshot
import com.example.R
import com.example.SettingsEntity
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.SignalCrimson
import com.example.ui.theme.SignalGreen
import com.example.ui.theme.StarkAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val QUICK_COMMANDS = listOf(
    "Explain screen in Hindi",
    "Kiska message aaya tha?",
    "Read clipboard",
    "What time is it?",
    "Battery status",
    "Flashlight on",
    "Scroll down"
)

@Composable
fun HudScreen(
    phase: JarvisPhase,
    voiceRmsDb: Float,
    isLiveSessionActive: Boolean,
    inactivityRemainingMs: Long,
    isAutoSleepStandby: Boolean,
    isMultiStepRunning: Boolean,
    multiStepStatus: String,
    liveTranscript: String,
    statusBanner: String,
    settings: SettingsEntity,
    permissions: PermissionsSnapshot,
    history: List<ChatHistoryEntity>,
    onToggleVoice: () -> Unit,
    onStopSpeech: () -> Unit,
    onStopMultiStep: () -> Unit,
    onToggleAutoReply: (Boolean) -> Unit,
    onSubmitCommand: (String) -> Unit,
    onClearHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    var manualInput by rememberSaveable { mutableStateOf("") }

    val sendManualCommand = {
        val trimmed = manualInput.trim()
        if (trimmed.isNotEmpty()) {
            onSubmitCommand(trimmed)
            manualInput = ""
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        // Subtle generated HUD backdrop art layered behind content
        Image(
            painter = painterResource(id = R.drawable.img_hud_backdrop_1795296876436),
            contentDescription = stringResource(R.string.cd_hud_backdrop),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.24f)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            ObsidianVoid.copy(alpha = 0.72f),
                            Color.Transparent,
                            ObsidianVoid.copy(alpha = 0.92f)
                        )
                    )
                )
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            val isExpanded = maxWidth >= 720.dp

            if (isExpanded) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.45f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        HudTopTelemetryHeader(
                            settings = settings,
                            permissions = permissions,
                            onToggleAutoReply = onToggleAutoReply,
                            onOpenSettings = onOpenSettings,
                            onOpenPermissions = onOpenPermissions
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        MultiStepStopBanner(
                            visible = isMultiStepRunning,
                            stepStatus = multiStepStatus,
                            onStop = onStopMultiStep
                        )
                        LiveSessionStatusPill(
                            isLiveSessionActive = isLiveSessionActive,
                            inactivityRemainingMs = inactivityRemainingMs,
                            isAutoSleepStandby = isAutoSleepStandby
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        ArcReactorCore(
                            phase = phase,
                            voiceRmsDb = voiceRmsDb,
                            isLiveSessionActive = isLiveSessionActive,
                            isAutoSleepStandby = isAutoSleepStandby,
                            onTapCore = onToggleVoice,
                            size = 220.dp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LiveTelemetryStrip(
                            liveTranscript = liveTranscript,
                            statusBanner = statusBanner
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        QuickCommandChips(onQuickCommand = onSubmitCommand)
                    }

                    Column(
                        modifier = Modifier
                            .weight(0.55f)
                            .fillMaxHeight()
                    ) {
                        ActionLogHeader(
                            count = history.size,
                            onClearHistory = onClearHistory
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ActionLogList(
                            history = history,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        CommandInputDock(
                            inputText = manualInput,
                            onInputChange = { manualInput = it },
                            phase = phase,
                            isLiveSessionActive = isLiveSessionActive,
                            onSend = sendManualCommand,
                            onToggleVoice = onToggleVoice,
                            onStopSpeech = onStopSpeech
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    HudTopTelemetryHeader(
                        settings = settings,
                        permissions = permissions,
                        onToggleAutoReply = onToggleAutoReply,
                        onOpenSettings = onOpenSettings,
                        onOpenPermissions = onOpenPermissions
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    MultiStepStopBanner(
                        visible = isMultiStepRunning,
                        stepStatus = multiStepStatus,
                        onStop = onStopMultiStep
                    )

                    LiveSessionStatusPill(
                        isLiveSessionActive = isLiveSessionActive,
                        inactivityRemainingMs = inactivityRemainingMs,
                        isAutoSleepStandby = isAutoSleepStandby
                    )

                    ArcReactorCore(
                        phase = phase,
                        voiceRmsDb = voiceRmsDb,
                        isLiveSessionActive = isLiveSessionActive,
                        isAutoSleepStandby = isAutoSleepStandby,
                        onTapCore = onToggleVoice,
                        size = 180.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    LiveTelemetryStrip(
                        liveTranscript = liveTranscript,
                        statusBanner = statusBanner
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    QuickCommandChips(onQuickCommand = onSubmitCommand)
                    Spacer(modifier = Modifier.height(10.dp))

                    ActionLogHeader(
                        count = history.size,
                        onClearHistory = onClearHistory
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    ActionLogList(
                        history = history,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    CommandInputDock(
                        inputText = manualInput,
                        onInputChange = { manualInput = it },
                        phase = phase,
                        isLiveSessionActive = isLiveSessionActive,
                        onSend = sendManualCommand,
                        onToggleVoice = onToggleVoice,
                        onStopSpeech = onStopSpeech
                    )
                }
            }
        }
    }
}

@Composable
private fun HudTopTelemetryHeader(
    settings: SettingsEntity,
    permissions: PermissionsSnapshot,
    onToggleAutoReply: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPermissions: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.hud_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.developer_credit),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TelemetryBadge(
                    label = if (settings.geminiApiKey.isNotBlank()) "JARVIS CORE" else "CORE KEY NEEDED",
                    color = if (settings.geminiApiKey.isNotBlank()) SignalGreen else StarkAmber
                )
                TelemetryBadge(
                    label = if (permissions.accessibilityActive) "A11Y LIVE" else "A11Y OFF",
                    color = if (permissions.accessibilityActive) ArcCyan else SignalCrimson
                )
                Surface(
                    onClick = { onToggleAutoReply(!settings.autoReplyEnabled) },
                    shape = RoundedCornerShape(6.dp),
                    color = (if (settings.autoReplyEnabled) SignalGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                        .copy(alpha = 0.14f),
                    border = BorderStroke(
                        1.dp,
                        (if (settings.autoReplyEnabled) SignalGreen else MaterialTheme.colorScheme.outline)
                            .copy(alpha = 0.65f)
                    ),
                    modifier = Modifier.testTag("hud_auto_reply_toggle")
                ) {
                    Text(
                        text = if (settings.autoReplyEnabled) "AUTO-CHAT ON" else "AUTO-CHAT OFF",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (settings.autoReplyEnabled) SignalGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(
                onClick = onOpenPermissions,
                modifier = Modifier.testTag("hud_permissions_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = stringResource(R.string.cd_open_permissions),
                    tint = if (permissions.activeCount >= 3) SignalGreen else StarkAmber
                )
            }
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag("hud_settings_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.cd_open_settings),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun MultiStepStopBanner(
    visible: Boolean,
    stepStatus: String,
    onStop: () -> Unit
) {
    AnimatedVisibility(visible = visible) {
        Surface(
            onClick = onStop,
            shape = RoundedCornerShape(999.dp),
            color = SignalCrimson.copy(alpha = 0.18f),
            border = BorderStroke(1.dp, SignalCrimson.copy(alpha = 0.85f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .testTag("hud_stop_multi_step_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.StopCircle,
                        contentDescription = stringResource(R.string.cd_stop_multi_step),
                        tint = SignalCrimson,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stepStatus.ifBlank { "Autonomous Agent Running…" },
                        style = MaterialTheme.typography.labelMedium,
                        color = SignalCrimson,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "TAP TO STOP",
                    style = MaterialTheme.typography.labelSmall,
                    color = SignalCrimson
                )
            }
        }
    }
}

@Composable
private fun LiveSessionStatusPill(
    isLiveSessionActive: Boolean,
    inactivityRemainingMs: Long,
    isAutoSleepStandby: Boolean
) {
    AnimatedVisibility(visible = isLiveSessionActive || isAutoSleepStandby) {
        if (isLiveSessionActive) {
            val totalSeconds = ((inactivityRemainingMs + 999L) / 1000L).coerceAtLeast(0L)
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            val formattedCountdown = String.format(Locale.US, "%02d:%02d", minutes, seconds)
            val badgeColor = if (totalSeconds <= 30L) StarkAmber else SignalGreen

            val pulseTransition = rememberInfiniteTransition(label = "live_dot_pulse")
            val dotAlpha by pulseTransition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 650),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_alpha"
            )

            Surface(
                shape = RoundedCornerShape(999.dp),
                color = badgeColor.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.6f)),
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .testTag("live_countdown_badge")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .alpha(dotAlpha)
                            .background(badgeColor, CircleShape)
                    )
                    Text(
                        text = "LIVE • Auto-sleep in $formattedCountdown",
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeColor
                    )
                }
            }
        } else if (isAutoSleepStandby) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = StarkAmber.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, StarkAmber.copy(alpha = 0.55f)),
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .testTag("auto_sleep_badge")
            ) {
                Text(
                    text = "STANDBY (AUTO-SLEEP)",
                    style = MaterialTheme.typography.labelMedium,
                    color = StarkAmber,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun TelemetryBadge(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun LiveTelemetryStrip(
    liveTranscript: String,
    statusBanner: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = statusBanner.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (liveTranscript.isNotBlank()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "\"$liveTranscript\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun QuickCommandChips(onQuickCommand: (String) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(QUICK_COMMANDS) { cmd ->
            AssistChip(
                onClick = { onQuickCommand(cmd) },
                label = {
                    Text(
                        text = cmd,
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    leadingIconContentColor = MaterialTheme.colorScheme.primary
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.testTag("quick_cmd_${cmd.lowercase(Locale.ROOT).replace(' ', '_')}")
            )
        }
    }
}

@Composable
private fun ActionLogHeader(
    count: Int,
    onClearHistory: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Radar,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "LIVE ACTION LOG ($count)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (count > 0) {
            IconButton(
                onClick = onClearHistory,
                modifier = Modifier.testTag("clear_history_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteSweep,
                    contentDescription = stringResource(R.string.cd_clear_history),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ActionLogList(
    history: List<ChatHistoryEntity>,
    modifier: Modifier = Modifier
) {
    if (history.isEmpty()) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.Radar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                    modifier = Modifier.size(34.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Awaiting Tactical Directives",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Tap the Arc Reactor to speak, or try an instant offline command chip above. All logs stay 100% local in Room.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 4.dp)
        ) {
            items(history, key = { it.id }) { item ->
                HistoryLogCard(entry = item)
            }
        }
    }
}

@Composable
private fun HistoryLogCard(entry: ChatHistoryEntity) {
    val timeLabel = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
    val accent = if (entry.isOffline) SignalGreen else ArcCyan

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("history_card_${entry.id}"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TelemetryBadge(
                    label = "${entry.executedAction} • ${entry.latencyMs}ms",
                    color = accent
                )
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "YOU: ${entry.userTranscript}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = entry.jarvisReply,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun CommandInputDock(
    inputText: String,
    onInputChange: (String) -> Unit,
    phase: JarvisPhase,
    isLiveSessionActive: Boolean,
    onSend: () -> Unit,
    onToggleVoice: () -> Unit,
    onStopSpeech: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            placeholder = {
                Text(
                    text = stringResource(R.string.command_placeholder),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
            ),
            modifier = Modifier
                .weight(1f)
                .testTag("manual_command_input")
        )

        if (inputText.isNotBlank()) {
            FilledIconButton(
                onClick = onSend,
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = ObsidianVoid
                ),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("send_command_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.cd_send_command)
                )
            }
        } else if (isLiveSessionActive || phase == JarvisPhase.LISTENING) {
            FilledIconButton(
                onClick = onToggleVoice,
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = SignalCrimson,
                    contentColor = ObsidianVoid
                ),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("mic_toggle_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.MicOff,
                    contentDescription = stringResource(R.string.cd_mic_toggle)
                )
            }
        } else if (phase == JarvisPhase.SPEAKING) {
            FilledIconButton(
                onClick = onStopSpeech,
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = SignalCrimson,
                    contentColor = ObsidianVoid
                ),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("stop_speech_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.StopCircle,
                    contentDescription = stringResource(R.string.cd_stop_speech)
                )
            }
        } else {
            FilledIconButton(
                onClick = onToggleVoice,
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = ObsidianVoid
                ),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("mic_toggle_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = stringResource(R.string.cd_mic_toggle)
                )
            }
        }
    }
}
