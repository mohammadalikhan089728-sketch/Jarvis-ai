package com.example.ui

import android.os.Build
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
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.PermissionsSnapshot
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.SignalCrimson
import com.example.ui.theme.SignalGreen
import com.example.ui.theme.StarkAmber

@Composable
fun PermissionsScreen(
    permissions: PermissionsSnapshot,
    screenSummary: String,
    onRequestMic: () -> Unit,
    onOpenDefaultAssistantSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onTestScreenTap: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onOpenAppInfoSettings: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestCamera: () -> Unit,
    onRequestPhoneAndContacts: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onScanScreenNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showRestrictedGuide by remember { mutableStateOf(false) }

    if (showRestrictedGuide) {
        AlertDialog(
            onDismissRequest = { showRestrictedGuide = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = {
                Text(
                    text = "Android 13–15 Restricted Settings Guide",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            text = {
                Text(
                    text = "On Android 13, 14, and 15, sideloaded APKs show a \"Restricted setting\" lock when activating Accessibility Services:\n\n" +
                        "1. Tap \"Open App Info\" below.\n" +
                        "2. Tap the three-dot menu (⋮) in the top-right corner.\n" +
                        "3. Select \"Allow restricted settings\" and confirm biometrics/PIN.\n" +
                        "4. Return here and tap \"Enable\" on Jarvis Accessibility Service.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestrictedGuide = false
                        onOpenAppInfoSettings()
                    },
                    modifier = Modifier.testTag("dialog_open_app_info_button")
                ) {
                    Text("OPEN APP INFO")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestrictedGuide = false }) {
                    Text("CLOSE")
                }
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SYSTEMS & PERMISSIONS",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Developed by Mozahid • ${permissions.activeCount}/7 online • API ${Build.VERSION.SDK_INT}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onRefreshPermissions,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.testTag("refresh_permissions_button")
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("SYNC")
                }
            }

            // 0. Default Digital Assistant App Registration
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Assistant,
                                contentDescription = null,
                                tint = ArcCyan,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Default Digital Assistant App",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "System Settings > Default Apps",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ArcCyan
                                )
                            }
                        }
                        Button(
                            onClick = onOpenDefaultAssistantSettings,
                            modifier = Modifier.testTag("perm_default_assistant_button")
                        ) {
                            Text("SET DEFAULT")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Select JARVIS in Android's Digital Assistant menu so system assistant triggers launch JARVIS directly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 1. Microphone
            PermissionChecklistCard(
                icon = Icons.Filled.Mic,
                title = "Microphone Voice Uplink",
                description = "Required for Push-to-Talk, live conversation, and Always-On background wake-word detection.",
                isActive = permissions.microphoneGranted,
                actionLabel = if (permissions.microphoneGranted) "GRANTED" else "ENABLE",
                onAction = onRequestMic,
                tag = "perm_microphone_button"
            )

            // 2. Accessibility Service + Android 13-15 Restricted Settings Helper
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(
                    1.dp,
                    if (permissions.accessibilityActive) SignalGreen.copy(alpha = 0.5f) else StarkAmber.copy(alpha = 0.6f)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AccessibilityNew,
                                contentDescription = null,
                                tint = if (permissions.accessibilityActive) SignalGreen else StarkAmber,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Accessibility & Gesture Engine",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                StatusBadgePill(isActive = permissions.accessibilityActive)
                            }
                        }

                        Button(
                            onClick = onOpenAccessibilitySettings,
                            modifier = Modifier.testTag("perm_accessibility_button")
                        ) {
                            Text(if (permissions.accessibilityActive) "MANAGE" else "ENABLE")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Enables full multi-window screen hierarchy reading, reliable physical taps, 360° drags, multi-touch pinch/rotate, text typing, and global controls.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onTestScreenTap,
                        border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.65f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("perm_test_screen_tap_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.TouchApp,
                            contentDescription = null,
                            tint = ArcCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("TEST SCREEN TAP", color = ArcCyan)
                    }

                    if (!permissions.accessibilityActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = StarkAmber.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, StarkAmber.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.Info,
                                        contentDescription = null,
                                        tint = StarkAmber,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Android 13–15: Unlock \"Allow Restricted Settings\" if blocked.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StarkAmber
                                    )
                                }
                                TextButton(
                                    onClick = { showRestrictedGuide = true },
                                    modifier = Modifier.testTag("restricted_settings_helper_button")
                                ) {
                                    Text("GUIDE")
                                }
                            }
                        }
                    }
                }
            }

            // 3. Notification Listener (WhatsApp / Telegram / Instagram / SMS Auto-Reply Chat)
            PermissionChecklistCard(
                icon = Icons.Filled.NotificationsActive,
                title = "Notification Access (Auto-Reply Chat)",
                description = "Allows Jarvis to read incoming WhatsApp, Telegram, Instagram, and SMS notifications and send background AI replies via RemoteInput.",
                isActive = permissions.notificationListenerActive,
                actionLabel = if (permissions.notificationListenerActive) "ACTIVE" else "ENABLE",
                onAction = onOpenNotificationListenerSettings,
                tag = "perm_notification_listener_button"
            )

            // 4. Floating Overlay Permission
            PermissionChecklistCard(
                icon = Icons.Filled.Layers,
                title = "Floating Overlay Orb & Stop HUD",
                description = "Displays the draggable Arc Reactor orb and the floating emergency STOP button during autonomous multi-step execution.",
                isActive = permissions.overlayGranted,
                actionLabel = if (permissions.overlayGranted) "ACTIVE" else "ENABLE",
                onAction = onRequestOverlay,
                tag = "perm_overlay_button"
            )

            // 4. Camera / Flashlight
            PermissionChecklistCard(
                icon = Icons.Filled.FlashlightOn,
                title = "Camera Hardware (Flashlight)",
                description = "Allows instant offline voice control of the device rear torch/flashlight.",
                isActive = permissions.cameraGranted,
                actionLabel = if (permissions.cameraGranted) "GRANTED" else "ENABLE",
                onAction = onRequestCamera,
                tag = "perm_camera_button"
            )

            // 5. Phone & Contacts
            val phoneContactsReady = permissions.phoneGranted && permissions.contactsGranted
            PermissionChecklistCard(
                icon = Icons.Filled.Call,
                title = "Phone Calls & Contacts",
                description = "Allows Jarvis to resolve spoken contact names and place direct calls (falls back to dialer if denied).",
                isActive = phoneContactsReady,
                actionLabel = if (phoneContactsReady) "GRANTED" else "ENABLE",
                onAction = onRequestPhoneAndContacts,
                tag = "perm_phone_contacts_button"
            )

            // Live Screen Reader Diagnostic Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Visibility,
                                contentDescription = null,
                                tint = ArcCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SCREEN HIERARCHY MONITOR",
                                style = MaterialTheme.typography.titleSmall,
                                color = ArcCyan
                            )
                        }
                        OutlinedButton(
                            onClick = onScanScreenNow,
                            border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.6f)),
                            modifier = Modifier.testTag("scan_screen_now_button")
                        ) {
                            Text("SCAN")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = screenSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PermissionChecklistCard(
    icon: ImageVector,
    title: String,
    description: String,
    isActive: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    tag: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(
            1.dp,
            if (isActive) SignalGreen.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isActive) SignalGreen else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        StatusBadgePill(isActive = isActive)
                    }
                }
                Button(
                    onClick = onAction,
                    enabled = !isActive,
                    modifier = Modifier.testTag(tag)
                ) {
                    Text(actionLabel)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusBadgePill(isActive: Boolean) {
    val color = if (isActive) SignalGreen else SignalCrimson
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        modifier = Modifier.padding(top = 3.dp)
    ) {
        Text(
            text = if (isActive) "ACTIVE" else "MISSING",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
