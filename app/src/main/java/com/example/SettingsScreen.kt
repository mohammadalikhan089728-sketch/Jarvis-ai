package com.example

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import java.util.Locale

@Composable
fun SettingsScreen(
    settings: SettingsEntity,
    keyVerificationState: KeyVerificationUiState,
    activeVoiceLabel: String,
    lastVoiceStatus: String,
    overlayPermissionGranted: Boolean,
    onSaveSettings: (SettingsEntity) -> Unit,
    onVerifyApiKey: (String) -> Unit,
    onResetVerification: () -> Unit,
    onTestVoice: (SettingsEntity) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    var apiKey by rememberSaveable { mutableStateOf(settings.geminiApiKey) }
    var showApiKey by rememberSaveable { mutableStateOf(false) }
    var masterPrompt by rememberSaveable { mutableStateOf(settings.masterSystemPrompt) }
    var voiceMode by rememberSaveable { mutableStateOf(settings.voiceMode) }
    var customVoiceUrl by rememberSaveable { mutableStateOf(settings.customVoiceUrl) }
    var voicePitch by rememberSaveable { mutableFloatStateOf(settings.voicePitch) }
    var voiceSpeed by rememberSaveable { mutableFloatStateOf(settings.voiceSpeed) }
    var wakeWordEnabled by rememberSaveable { mutableStateOf(settings.wakeWordEnabled) }
    var autoReadScreen by rememberSaveable { mutableStateOf(settings.autoReadScreenContext) }
    var floatingOverlay by rememberSaveable { mutableStateOf(settings.floatingOverlayEnabled) }
    var offlineFirst by rememberSaveable { mutableStateOf(settings.offlineFirstMode) }
    var savedBanner by remember { mutableStateOf<String?>(null) }

    // Synchronize local form fields when Room initially emits saved values
    LaunchedEffect(settings.updatedAt) {
        apiKey = settings.geminiApiKey
        masterPrompt = settings.masterSystemPrompt
        voiceMode = settings.voiceMode
        customVoiceUrl = settings.customVoiceUrl
        voicePitch = settings.voicePitch
        voiceSpeed = settings.voiceSpeed
        wakeWordEnabled = settings.wakeWordEnabled
        autoReadScreen = settings.autoReadScreenContext
        floatingOverlay = settings.floatingOverlayEnabled
        offlineFirst = settings.offlineFirstMode
    }

    fun buildCurrentEntity(): SettingsEntity {
        return SettingsEntity(
            id = 1,
            geminiApiKey = apiKey.trim(),
            masterSystemPrompt = masterPrompt.trim().ifBlank { DEFAULT_MASTER_PROMPT },
            voiceMode = voiceMode,
            customVoiceUrl = customVoiceUrl.trim(),
            voicePitch = voicePitch,
            voiceSpeed = voiceSpeed,
            wakeWordEnabled = wakeWordEnabled,
            autoReadScreenContext = autoReadScreen,
            floatingOverlayEnabled = floatingOverlay,
            offlineFirstMode = offlineFirst
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
                Column {
                    Text(
                        text = "SYSTEM CONFIGURATION",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "100% Local On-Device Room Vault",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = {
                        val entity = buildCurrentEntity()
                        onSaveSettings(entity)
                        savedBanner = "All parameters saved locally."
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

            // 1. Gemini API Key Section (Locked strictly to gemini-3.5-flash)
            SettingsSectionCard(
                title = "GEMINI NEURAL CORE",
                icon = Icons.Filled.Key
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ArcCyan.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = "MODEL LOCK: ${GeminiApiClient.MODEL_NAME}",
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
                    label = { Text("Gemini API Key") },
                    placeholder = { Text("Paste your AI Studio API key") },
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
                        .testTag("gemini_api_key_input")
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
                            savedBanner = "API key cleared from local storage."
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
                            message = "Verified on ${GeminiApiClient.MODEL_NAME} (${keyVerificationState.latencyMs}ms)"
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

            // 2. Editable Master System Prompt
            SettingsSectionCard(
                title = "MASTER SYSTEM PROMPT",
                icon = Icons.Filled.Psychology
            ) {
                Text(
                    text = "Injected dynamically as systemInstruction into every ${GeminiApiClient.MODEL_NAME} request. Define personality, Hindi/Hinglish/English style, and command priorities.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = masterPrompt,
                    onValueChange = { masterPrompt = it },
                    label = { Text("JARVIS Core Directive") },
                    minLines = 7,
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
                            savedBanner = "Master System Prompt reset to Stark defaults."
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

            // 3. Realistic Voice Engine Switcher + Sliders + Open-Source Voice URL
            SettingsSectionCard(
                title = "VOCAL SYNTHESIS MATRIX",
                icon = Icons.Filled.GraphicEq
            ) {
                VoiceOptionRow(
                    title = "Default Realistic Neural Voice",
                    subtitle = "Active: $activeVoiceLabel",
                    selected = voiceMode == VOICE_MODE_DEFAULT,
                    onSelect = { voiceMode = VOICE_MODE_DEFAULT },
                    tag = "voice_mode_default_radio"
                )
                Spacer(modifier = Modifier.height(6.dp))
                VoiceOptionRow(
                    title = "Custom Open-Source Voice URL",
                    subtitle = "Piper TTS / Sherpa-ONNX / HTTP WAV or MP3 endpoint (auto-falls back offline)",
                    selected = voiceMode == VOICE_MODE_CUSTOM_URL,
                    onSelect = { voiceMode = VOICE_MODE_CUSTOM_URL },
                    tag = "voice_mode_custom_radio"
                )

                if (voiceMode == VOICE_MODE_CUSTOM_URL) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = customVoiceUrl,
                        onValueChange = { customVoiceUrl = it },
                        label = { Text("Open-Source TTS Endpoint / Model URL") },
                        placeholder = { Text("http://192.168.1.10:5000/api/tts or https://…") },
                        singleLine = true,
                        colors = hudTextFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_voice_url_input")
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = String.format(Locale.US, "VOICE PITCH: %.2fx (Default 0.88x)", voicePitch),
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
                            voicePitch = 0.88f
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

            // 4. Behavior Toggles
            SettingsSectionCard(
                title = "AGENT BEHAVIOR PROTOCOLS",
                icon = Icons.Filled.Tune
            ) {
                BehaviorToggleRow(
                    title = "Continuous Wake-Word Listening",
                    subtitle = "Passively listen for \"Hey Jarvis\" to trigger voice actions hands-free",
                    checked = wakeWordEnabled,
                    onCheckedChange = {
                        wakeWordEnabled = it
                        onSaveSettings(buildCurrentEntity())
                    },
                    tag = "toggle_wake_word"
                )
                BehaviorToggleRow(
                    title = "Auto-Read Screen Context",
                    subtitle = "Attach Accessibility UI tree coordinates to every gemini-3.5-flash query",
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
                        "Show draggable Arc Reactor orb over other apps"
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
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
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
private fun VoiceOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    tag: String
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
private fun hudTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface
)
