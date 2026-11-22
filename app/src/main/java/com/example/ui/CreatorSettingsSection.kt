package com.example.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.YouTubeAuthAndApiClient
import com.example.YouTubeCreatorConfig
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.SignalCrimson
import com.example.ui.theme.SignalGreen
import com.example.ui.theme.StarkAmber

/**
 * Independent YouTube Creator Control Center & AI Intelligence Settings Sections embedded inside
 * the existing JARVIS SettingsScreen — backed by Android Keystore AES-256-GCM SecureCredentialVault.
 * Never overwrites existing Gemini API credentials or Room settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreatorSettingsSection(
    config: YouTubeCreatorConfig,
    oauthStatus: String,
    youtubeTestStatus: String,
    onSaveConfig: (YouTubeCreatorConfig) -> Unit,
    onStartYouTubeOAuth: (clientId: String) -> Unit,
    onTestYouTubeConnection: () -> Unit,
    onDisconnectYouTube: () -> Unit,
    onTestMilestoneCall: () -> Unit,
    onOpenYouTubeControlCenter: () -> Unit
) {
    val context = LocalContext.current

    var ytApiKey by rememberSaveable { mutableStateOf(config.apiKey) }
    var showYtApiKey by rememberSaveable { mutableStateOf(false) }
    var oauthClientId by rememberSaveable { mutableStateOf(config.oauthClientId) }
    var defaultPrivacy by rememberSaveable { mutableStateOf(config.defaultPrivacy) }
    var defaultCategoryId by rememberSaveable { mutableStateOf(config.defaultCategoryId) }
    var defaultMadeForKids by rememberSaveable { mutableStateOf(config.defaultMadeForKids) }
    var seoLanguage by rememberSaveable { mutableStateOf(config.seoLanguage) }
    var seoTone by rememberSaveable { mutableStateOf(config.seoTone) }
    var seoHashtags by rememberSaveable { mutableStateOf(config.seoDefaultHashtags) }
    var analyticsWindowDays by rememberSaveable { mutableStateOf(config.analyticsWindowDays) }
    var monitoringEnabled by rememberSaveable { mutableStateOf(config.monitoringEnabled) }
    var monitoringInterval by rememberSaveable { mutableStateOf(config.monitoringIntervalMinutes.toString()) }
    var milestonesCsv by rememberSaveable { mutableStateOf(config.viewMilestonesCsv) }
    var milestoneCallEnabled by rememberSaveable { mutableStateOf(config.milestoneInAppCallEnabled) }
    var respectDnd by rememberSaveable { mutableStateOf(config.respectDoNotDisturb) }
    var bgNotifFallback by rememberSaveable { mutableStateOf(config.backgroundNotificationFallback) }
    var clipDurationSec by rememberSaveable { mutableStateOf(config.clipDefaultDurationSec.toString()) }
    var clipVertical916 by rememberSaveable { mutableStateOf(config.clipVertical916Default) }
    var clipBurnCaptions by rememberSaveable { mutableStateOf(config.clipBurnCaptionsDefault) }
    var projectMemoryAutoInject by rememberSaveable { mutableStateOf(config.projectMemoryAutoInject) }
    var selfImproveAutoLearn by rememberSaveable { mutableStateOf(config.selfImprovementAutoLearn) }

    LaunchedEffect(config) {
        if (ytApiKey.isEmpty() && config.apiKey.isNotEmpty()) ytApiKey = config.apiKey
        if (oauthClientId.isEmpty() && config.oauthClientId.isNotEmpty()) oauthClientId = config.oauthClientId
        defaultPrivacy = config.defaultPrivacy
        defaultCategoryId = config.defaultCategoryId
        defaultMadeForKids = config.defaultMadeForKids
        seoLanguage = config.seoLanguage
        seoTone = config.seoTone
        seoHashtags = config.seoDefaultHashtags
        analyticsWindowDays = config.analyticsWindowDays
        monitoringEnabled = config.monitoringEnabled
        monitoringInterval = config.monitoringIntervalMinutes.toString()
        milestonesCsv = config.viewMilestonesCsv
        milestoneCallEnabled = config.milestoneInAppCallEnabled
        respectDnd = config.respectDoNotDisturb
        bgNotifFallback = config.backgroundNotificationFallback
        clipDurationSec = config.clipDefaultDurationSec.toString()
        clipVertical916 = config.clipVertical916Default
        clipBurnCaptions = config.clipBurnCaptionsDefault
        projectMemoryAutoInject = config.projectMemoryAutoInject
        selfImproveAutoLearn = config.selfImprovementAutoLearn
    }

    fun buildUpdatedConfig(folderUri: String = config.uploadFolderUri, folderLabel: String = config.uploadFolderLabel): YouTubeCreatorConfig {
        return config.copy(
            apiKey = ytApiKey.trim(),
            oauthClientId = oauthClientId.trim(),
            uploadFolderUri = folderUri,
            uploadFolderLabel = folderLabel,
            defaultPrivacy = defaultPrivacy,
            defaultCategoryId = defaultCategoryId.trim().ifBlank { "22" },
            defaultMadeForKids = defaultMadeForKids,
            seoLanguage = seoLanguage.trim().ifBlank { "Hinglish / English" },
            seoTone = seoTone.trim().ifBlank { "High-CTR Engaging" },
            seoDefaultHashtags = seoHashtags.trim(),
            analyticsWindowDays = analyticsWindowDays,
            monitoringEnabled = monitoringEnabled,
            monitoringIntervalMinutes = (monitoringInterval.toIntOrNull() ?: 30).coerceIn(15, 1440),
            viewMilestonesCsv = milestonesCsv.trim().ifBlank { "100,500,1000,5000,10000" },
            milestoneInAppCallEnabled = milestoneCallEnabled,
            respectDoNotDisturb = respectDnd,
            backgroundNotificationFallback = bgNotifFallback,
            clipDefaultDurationSec = (clipDurationSec.toIntOrNull() ?: 30).coerceIn(8, 58),
            clipVertical916Default = clipVertical916,
            clipBurnCaptionsDefault = clipBurnCaptions,
            projectMemoryAutoInject = projectMemoryAutoInject,
            selfImprovementAutoLearn = selfImproveAutoLearn
        )
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val label = uri.lastPathSegment?.substringAfterLast(':')?.ifBlank { uri.toString() } ?: uri.toString()
            onSaveConfig(buildUpdatedConfig(folderUri = uri.toString(), folderLabel = label))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        // Quick Launch Card for Full YouTube Creator Control Center
        Button(
            onClick = onOpenYouTubeControlCenter,
            colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ObsidianVoid),
            modifier = Modifier.fillMaxWidth().testTag("settings_open_youtube_studio_button")
        ) {
            Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("OPEN YOUTUBE CREATOR CONTROL CENTER")
        }

        // 1. YouTube API Configuration (Encrypted in Android Keystore AES-256-GCM)
        CreatorSectionCard(title = "YOUTUBE API CONFIGURATION (KEYSTORE ENCRYPTED)", icon = Icons.Filled.Key) {
            Text(
                text = "Credentials are encrypted with Android Keystore AES-256-GCM. Existing Gemini API configuration is preserved untouched.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = ytApiKey,
                onValueChange = { ytApiKey = it },
                label = { Text("YouTube Data API v3 Key") },
                singleLine = true,
                visualTransformation = if (showYtApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showYtApiKey = !showYtApiKey }) {
                        Icon(if (showYtApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "Toggle YouTube API key visibility")
                    }
                },
                colors = creatorFieldColors(),
                modifier = Modifier.fillMaxWidth().testTag("yt_api_key_input")
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = oauthClientId,
                onValueChange = { oauthClientId = it },
                label = { Text("Google OAuth 2.0 Client ID (Android / Installed App)") },
                placeholder = { Text("xxxx.apps.googleusercontent.com") },
                singleLine = true,
                colors = creatorFieldColors(),
                modifier = Modifier.fillMaxWidth().testTag("yt_oauth_client_id_input")
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "OAuth Redirect URI: ${YouTubeAuthAndApiClient.REDIRECT_URI}",
                style = MaterialTheme.typography.labelSmall,
                color = ArcCyan
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onSaveConfig(buildUpdatedConfig()) },
                    colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ObsidianVoid),
                    modifier = Modifier.weight(1f).testTag("yt_save_credentials_button")
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("SAVE VAULT")
                }
                OutlinedButton(
                    onClick = {
                        onSaveConfig(buildUpdatedConfig())
                        onTestYouTubeConnection()
                    },
                    border = BorderStroke(1.dp, SignalGreen),
                    modifier = Modifier.weight(1f).testTag("yt_test_connection_button")
                ) {
                    Icon(Icons.Filled.Security, contentDescription = null, tint = SignalGreen, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("TEST LINK", color = SignalGreen)
                }
            }
            if (youtubeTestStatus.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(youtubeTestStatus, style = MaterialTheme.typography.labelSmall, color = ArcCyan)
            }
        }

        // 2. YouTube Account Connection & Strictly Read-Only Channel Information
        CreatorSectionCard(title = "YOUTUBE ACCOUNT CONNECTION & CHANNEL INFO", icon = Icons.Filled.AccountCircle) {
            if (config.isAccountAuthorized) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, SignalGreen.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (config.channelThumbnailUrl.isNotBlank()) {
                            AsyncImage(
                                model = config.channelThumbnailUrl,
                                contentDescription = stringResource(R.string.cd_channel_avatar),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(44.dp).clip(CircleShape)
                            )
                        } else {
                            Icon(Icons.Filled.Verified, contentDescription = null, tint = SignalGreen, modifier = Modifier.size(32.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(config.channelName, style = MaterialTheme.typography.titleSmall, color = SignalGreen)
                            Text("Handle: ${config.channelHandle.ifBlank { "Verified" }}", style = MaterialTheme.typography.labelSmall)
                            Text("Channel ID: ${config.channelId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Security Lock: Channel identity is strictly verified from Google OAuth (channels.list?mine=true). Manual handle entry is disabled to prevent unauthorized channel access.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDisconnectYouTube,
                    border = BorderStroke(1.dp, SignalCrimson),
                    modifier = Modifier.fillMaxWidth().testTag("yt_disconnect_oauth_button")
                ) {
                    Icon(Icons.Filled.LinkOff, contentDescription = null, tint = SignalCrimson, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("DISCONNECT YOUTUBE ACCOUNT", color = SignalCrimson)
                }
            } else {
                Text(
                    text = "Authorize your own Google YouTube account using OAuth 2.0 PKCE. JARVIS will automatically retrieve and verify your Channel Name, Handle, ID, and Thumbnail.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        onSaveConfig(buildUpdatedConfig())
                        onStartYouTubeOAuth(oauthClientId)
                    },
                    enabled = oauthClientId.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = SignalGreen, contentColor = ObsidianVoid),
                    modifier = Modifier.fillMaxWidth().testTag("yt_connect_oauth_button")
                ) {
                    Icon(Icons.Filled.Verified, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("CONNECT YOUTUBE VIA GOOGLE OAUTH")
                }
            }
            if (oauthStatus.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(oauthStatus, style = MaterialTheme.typography.labelSmall, color = StarkAmber)
            }
        }

        // 3. Upload Folder & Upload Preferences
        CreatorSectionCard(title = "UPLOAD FOLDER & PREFERENCES", icon = Icons.Filled.FolderOpen) {
            Text("Active Export/Upload Folder: ${config.uploadFolderLabel}", style = MaterialTheme.typography.bodySmall, color = ArcCyan)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = { folderPicker.launch(null) },
                border = BorderStroke(1.dp, ArcCyan),
                modifier = Modifier.fillMaxWidth().testTag("yt_select_upload_folder_button")
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("CHOOSE UPLOAD / EXPORT FOLDER (SAF)", color = ArcCyan)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Default Privacy", style = MaterialTheme.typography.labelSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("private", "unlisted", "public").forEach { p ->
                    FilterChip(selected = defaultPrivacy == p, onClick = { defaultPrivacy = p }, label = { Text(p.uppercase()) })
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = defaultCategoryId, onValueChange = { defaultCategoryId = it.filter(Char::isDigit).take(3) },
                label = { Text("Default YouTube Category ID (22=People, 28=Tech, 20=Gaming)") },
                singleLine = true, colors = creatorFieldColors(), modifier = Modifier.fillMaxWidth()
            )
            CreatorSwitchRow("Default Made For Kids", "Self-declared COPPA setting for uploads", defaultMadeForKids) { defaultMadeForKids = it }
        }

        // 4. SEO Preferences & Analytics Settings
        CreatorSectionCard(title = "SEO & ANALYTICS SETTINGS", icon = Icons.Filled.Analytics) {
            OutlinedTextField(value = seoLanguage, onValueChange = { seoLanguage = it }, label = { Text("SEO Language") }, singleLine = true, colors = creatorFieldColors(), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(value = seoTone, onValueChange = { seoTone = it }, label = { Text("SEO Tone / Style") }, singleLine = true, colors = creatorFieldColors(), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(value = seoHashtags, onValueChange = { seoHashtags = it }, label = { Text("Default Hashtags") }, singleLine = true, colors = creatorFieldColors(), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            Text("Channel Analytics Window", style = MaterialTheme.typography.labelSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 28, 90).forEach { days ->
                    FilterChip(selected = analyticsWindowDays == days, onClick = { analyticsWindowDays = days }, label = { Text("$days Days") })
                }
            }
        }

        // 5. View Milestone Settings & In-App Call Preferences
        CreatorSectionCard(title = "VIEW MILESTONES & IN-APP CALL PREFERENCES", icon = Icons.Filled.NotificationsActive) {
            CreatorSwitchRow("Automatic YouTube Performance Monitoring", "Background WorkManager view checks on tracked uploads", monitoringEnabled) { monitoringEnabled = it }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = milestonesCsv, onValueChange = { milestonesCsv = it },
                label = { Text("View Milestone Thresholds (comma-separated, customizable)") },
                placeholder = { Text("100,500,1000,5000,10000") },
                singleLine = true, colors = creatorFieldColors(), modifier = Modifier.fillMaxWidth().testTag("yt_milestones_input")
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = monitoringInterval, onValueChange = { monitoringInterval = it.filter(Char::isDigit).take(4) },
                label = { Text("Check Interval (minutes, minimum 15)") },
                singleLine = true, colors = creatorFieldColors(), modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(6.dp))
            CreatorSwitchRow("Automatic In-App JARVIS Milestone Call", "Reuses JARVIS Incoming Call screen (one call per milestone)", milestoneCallEnabled) { milestoneCallEnabled = it }
            CreatorSwitchRow("Respect Do Not Disturb (DND)", "Downgrades call alert to quiet notification when DND is active", respectDnd) { respectDnd = it }
            CreatorSwitchRow("Background Notification Fallback", "Respects Android background activity launch restrictions", bgNotifFallback) { bgNotifFallback = it }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = onTestMilestoneCall,
                border = BorderStroke(1.dp, SignalGreen),
                modifier = Modifier.fillMaxWidth().testTag("settings_test_milestone_call_button")
            ) {
                Icon(Icons.Filled.PhoneInTalk, contentDescription = null, tint = SignalGreen, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("TEST YOUTUBE MILESTONE IN-APP CALL", color = SignalGreen)
            }
        }

        // 6. Clipping Studio & Project Memory Settings
        CreatorSectionCard(title = "CLIPPING STUDIO & AI INTELLIGENCE SETTINGS", icon = Icons.Filled.ContentCut) {
            OutlinedTextField(
                value = clipDurationSec, onValueChange = { clipDurationSec = it.filter(Char::isDigit).take(2) },
                label = { Text("Default Clip Length (seconds, 8–58)") },
                singleLine = true, colors = creatorFieldColors(), modifier = Modifier.fillMaxWidth()
            )
            CreatorSwitchRow("Default 9:16 Vertical Framing", "Apply 9:16 framing preview on generated clips", clipVertical916) { clipVertical916 = it }
            CreatorSwitchRow("Default Captions Overlay", "Generate visual captions on clip cards", clipBurnCaptions) { clipBurnCaptions = it }
            CreatorSwitchRow("Infinite Project Memory Auto-Inject", "Include active project context in JARVIS replies", projectMemoryAutoInject) { projectMemoryAutoInject = it }
            CreatorSwitchRow("Self-Improvement Failure Learning", "Save improved strategies after failed tasks (critical changes locked)", selfImproveAutoLearn) { selfImproveAutoLearn = it }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { onSaveConfig(buildUpdatedConfig()) },
                colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ObsidianVoid),
                modifier = Modifier.fillMaxWidth().testTag("save_all_creator_settings_button")
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("SAVE ALL CREATOR & INTELLIGENCE SETTINGS")
            }
        }
    }
}

@Composable
private fun CreatorSectionCard(
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
                Icon(icon, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, color = ArcCyan)
            }
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun CreatorSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = ObsidianVoid, checkedTrackColor = ArcCyan)
        )
    }
}

@Composable
private fun creatorFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = ArcCyan,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface
)
