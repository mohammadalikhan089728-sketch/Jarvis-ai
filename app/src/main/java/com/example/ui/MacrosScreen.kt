package com.example.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.CustomMacroEntity
import com.example.R
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.SignalGreen

private val MACRO_ACTION_TYPES = listOf(
    "OPEN_APP",
    "GLOBAL",
    "SCROLL",
    "DRAG",
    "MULTI_TOUCH",
    "TAP_TEXT",
    "AI_PROMPT"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MacrosScreen(
    macros: List<CustomMacroEntity>,
    accessibilityActive: Boolean,
    onAddMacro: (trigger: String, type: String, payload: String, reply: String) -> Unit,
    onDeleteMacro: (CustomMacroEntity) -> Unit,
    onRunMacro: (CustomMacroEntity) -> Unit,
    onRunGestureTest: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    if (showCreateDialog) {
        CreateMacroDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { trigger, type, payload, reply ->
                onAddMacro(trigger, type, payload, reply)
                showCreateDialog = false
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "MACROS & GESTURE LAB",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Local Room voice shortcuts & multi-touch tests",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.testTag("add_macro_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("NEW")
                }
            }

            // Gesture Engine Live Test Console
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.TouchApp,
                            contentDescription = null,
                            tint = ArcCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "GESTURE ENGINE DIAGNOSTICS",
                            style = MaterialTheme.typography.titleSmall,
                            color = ArcCyan
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (accessibilityActive) {
                            "Accessibility gesture dispatcher online. Test 360° drags and simultaneous multi-touch strokes:"
                        } else {
                            "Activate Jarvis Accessibility Service in Systems to dispatch physical touch gestures."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    val gestureTests = listOf(
                        "SCROLL_DOWN" to "Scroll Down",
                        "DRAG_DIAGONAL" to "360° Diagonal Drag",
                        "PINCH_OUT" to "Pinch Zoom In",
                        "PINCH_IN" to "Pinch Zoom Out",
                        "ROTATE" to "2-Finger Rotate",
                        "MULTI_TAP" to "Dual Multi-Tap"
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        gestureTests.forEach { (id, label) ->
                            AssistChip(
                                onClick = { onRunGestureTest(id) },
                                enabled = accessibilityActive,
                                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    labelColor = MaterialTheme.colorScheme.onSurface
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier.testTag("gesture_test_${id.lowercase()}")
                            )
                        }
                    }
                }
            }

            // Local Custom Voice Macros List
            Text(
                text = "CUSTOM VOICE SHORTCUTS (${macros.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (macros.isEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No Custom Macros Stored Yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Create voice triggers stored 100% locally in Room for instant app launches, multi-touch actions, or custom AI workflows.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onAddMacro(
                                        "zoom map",
                                        "MULTI_TOUCH",
                                        """{"gesture":"PINCH_OUT","spreadPx":280}""",
                                        "Zooming into the map, sir."
                                    )
                                },
                                modifier = Modifier.testTag("preset_macro_zoom")
                            ) {
                                Text("+ ZOOM MACRO")
                            }
                            OutlinedButton(
                                onClick = {
                                    onAddMacro(
                                        "take snap",
                                        "GLOBAL",
                                        """{"command":"SCREENSHOT"}""",
                                        "Capturing screenshot now."
                                    )
                                },
                                modifier = Modifier.testTag("preset_macro_screenshot")
                            ) {
                                Text("+ SCREENSHOT MACRO")
                            }
                        }
                    }
                }
            } else {
                macros.forEach { macro ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("macro_card_${macro.id}"),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "\"${macro.triggerPhrase}\"",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = ArcCyan
                                )
                                Text(
                                    text = "${macro.actionType} → ${macro.actionPayload}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (macro.spokenReply.isNotBlank()) {
                                    Text(
                                        text = "Reply: ${macro.spokenReply}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SignalGreen
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onRunMacro(macro) },
                                modifier = Modifier.testTag("run_macro_${macro.id}")
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(R.string.cd_run_macro),
                                    tint = SignalGreen
                                )
                            }
                            IconButton(
                                onClick = { onDeleteMacro(macro) },
                                modifier = Modifier.testTag("delete_macro_${macro.id}")
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.cd_delete_macro),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateMacroDialog(
    onDismiss: () -> Unit,
    onConfirm: (trigger: String, type: String, payload: String, reply: String) -> Unit
) {
    var trigger by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("OPEN_APP") }
    var payload by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }

    val payloadHint = when (selectedType) {
        "OPEN_APP" -> "App name (e.g. YouTube)"
        "GLOBAL" -> "HOME, BACK, SCREENSHOT, FLASHLIGHT_ON…"
        "SCROLL" -> "SCROLL_DOWN, SCROLL_UP, SCROLL_LEFT, SCROLL_RIGHT"
        "DRAG" -> """{"angleDegrees":45,"distancePx":350}"""
        "MULTI_TOUCH" -> """{"gesture":"PINCH_OUT","spreadPx":260}"""
        "TAP_TEXT" -> "Button label (e.g. Send)"
        else -> "Complex command for gemini-3.5-flash"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = {
            Text(
                text = "Create Local Voice Macro",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = trigger,
                    onValueChange = { trigger = it },
                    label = { Text("Voice Trigger Phrase") },
                    placeholder = { Text("e.g. battle mode") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("macro_trigger_input")
                )

                Text(
                    text = "Action Type",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MACRO_ACTION_TYPES.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            label = { Text(type, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                OutlinedTextField(
                    value = payload,
                    onValueChange = { payload = it },
                    label = { Text("Action Payload") },
                    placeholder = { Text(payloadHint) },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("macro_payload_input")
                )

                OutlinedTextField(
                    value = reply,
                    onValueChange = { reply = it },
                    label = { Text("Spoken Confirmation (Optional)") },
                    placeholder = { Text("Executing protocol, sir.") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("macro_reply_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(trigger, selectedType, payload, reply) },
                enabled = trigger.isNotBlank() && payload.isNotBlank(),
                modifier = Modifier.testTag("confirm_add_macro_button")
            ) {
                Text("SAVE MACRO")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL")
            }
        }
    )
}
