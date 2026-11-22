package com.example.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.ChannelAnalyticsReport
import com.example.GeneratedSeoPackage
import com.example.MonitoredYouTubeVideoEntity
import com.example.PublicVideoAnalytics
import com.example.R
import com.example.UploadProgressState
import com.example.VideoClipEntity
import com.example.VideoClippingEngine
import com.example.YT_STATUS_FAILED
import com.example.YT_STATUS_PUBLISHED
import com.example.YT_STATUS_SCHEDULED
import com.example.YT_UPLOAD_TYPE_COMMUNITY
import com.example.YT_UPLOAD_TYPE_LONG
import com.example.YT_UPLOAD_TYPE_SHORT
import com.example.YouTubeAuthAndApiClient
import com.example.YouTubeCreatorConfig
import com.example.YouTubeUploadEntity
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.DeepHudNavy
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.SignalCrimson
import com.example.ui.theme.SignalGreen
import com.example.ui.theme.StarkAmber
import java.io.File
import java.util.Calendar

private data class YtTab(val label: String, val icon: ImageVector)

private val YT_TABS = listOf(
    YtTab("Upload", Icons.Filled.CloudUpload),
    YtTab("Analytics", Icons.Filled.Analytics),
    YtTab("Audit", Icons.Filled.VideoSettings),
    YtTab("Clips", Icons.Filled.ContentCut),
    YtTab("Monitor", Icons.Filled.NotificationsActive)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YouTubeCreatorScreen(
    config: YouTubeCreatorConfig,
    uploads: List<YouTubeUploadEntity>,
    monitoredVideos: List<MonitoredYouTubeVideoEntity>,
    clips: List<VideoClipEntity>,
    uploadProgress: UploadProgressState,
    generatedSeo: GeneratedSeoPackage?,
    publicVideoAnalytics: PublicVideoAnalytics?,
    channelAnalytics: ChannelAnalyticsReport?,
    videoAuditReport: String,
    youtubeStrategyReport: String,
    clippingStatus: String,
    isBusy: Boolean,
    statusMessage: String,
    onOpenSettings: () -> Unit,
    onGenerateSeo: (topic: String, isShort: Boolean) -> Unit,
    onStartUpload: (
        videoUri: Uri,
        thumbnailUri: Uri?,
        title: String,
        description: String,
        tagsCsv: String,
        privacy: String,
        scheduledPublishIsoUtc: String?,
        isShort: Boolean
    ) -> Unit,
    onRetryUpload: (YouTubeUploadEntity) -> Unit,
    onSaveCommunityDraft: (title: String, body: String) -> Unit,
    onGenerateCommunityCopy: (topic: String, onResult: (String) -> Unit) -> Unit,
    onFetchPublicVideo: (videoIdOrUrl: String) -> Unit,
    onFetchChannelAnalytics: () -> Unit,
    onAddMonitoredVideo: (videoIdOrUrl: String) -> Unit,
    onDeleteMonitoredVideo: (MonitoredYouTubeVideoEntity) -> Unit,
    onCheckMilestonesNow: () -> Unit,
    onTestMilestoneCall: () -> Unit,
    onRunVideoAudit: (videoUri: Uri, draftTitle: String, notesOrScript: String) -> Unit,
    onGenerateFourClips: (videoUri: Uri, vertical916: Boolean, burnCaptions: Boolean) -> Unit,
    onEditAndReExportClip: (clip: VideoClipEntity, startMs: Long, endMs: Long, caption: String, vertical916: Boolean) -> Unit,
    onExportClipToSaf: (clipFilePath: String, destUri: Uri) -> Unit,
    onDeleteClip: (VideoClipEntity) -> Unit,
    onGenerateStrategy: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var handoffShortVideoUri by remember { mutableStateOf<Uri?>(null) }
    var handoffShortTitle by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = DeepHudNavy,
            contentColor = ArcCyan,
            edgePadding = 8.dp
        ) {
            YT_TABS.forEachIndexed { i, tab ->
                Tab(
                    selected = selectedTab == i,
                    onClick = { selectedTab = i },
                    text = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                    icon = { Icon(tab.icon, contentDescription = tab.label, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("yt_tab_${tab.label.lowercase()}")
                )
            }
        }

        if (statusMessage.isNotBlank() || isBusy) {
            Surface(color = ArcCyan.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = ArcCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = statusMessage.ifBlank { "Processing YouTube creator request…" },
                        style = MaterialTheme.typography.labelSmall,
                        color = ArcCyan,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Verified Authenticated Channel Banner (Always Read-Only — NEVER manually editable!)
                VerifiedChannelBanner(config = config, onOpenSettings = onOpenSettings)

                when (selectedTab) {
                    0 -> UploadManagerPane(
                        config = config,
                        uploads = uploads,
                        uploadProgress = uploadProgress,
                        generatedSeo = generatedSeo,
                        initialShortUri = handoffShortVideoUri,
                        initialShortTitle = handoffShortTitle,
                        isBusy = isBusy,
                        onClearHandoff = { handoffShortVideoUri = null; handoffShortTitle = "" },
                        onGenerateSeo = onGenerateSeo,
                        onStartUpload = onStartUpload,
                        onRetryUpload = onRetryUpload,
                        onSaveCommunityDraft = onSaveCommunityDraft,
                        onGenerateCommunityCopy = onGenerateCommunityCopy
                    )
                    1 -> AnalyticsPane(
                        config = config,
                        publicVideo = publicVideoAnalytics,
                        channelAnalytics = channelAnalytics,
                        isBusy = isBusy,
                        onFetchPublicVideo = onFetchPublicVideo,
                        onFetchChannelAnalytics = onFetchChannelAnalytics,
                        onAddToMonitor = onAddMonitoredVideo
                    )
                    2 -> VideoAuditPane(
                        auditReport = videoAuditReport,
                        isBusy = isBusy,
                        onRunAudit = onRunVideoAudit
                    )
                    3 -> ClippingStudioPane(
                        config = config,
                        clips = clips,
                        clippingStatus = clippingStatus,
                        isBusy = isBusy,
                        onGenerateFourClips = onGenerateFourClips,
                        onEditClip = onEditAndReExportClip,
                        onExportClipToSaf = onExportClipToSaf,
                        onPublishClipAsShort = { clip ->
                            handoffShortVideoUri = Uri.fromFile(File(clip.clipFilePath))
                            handoffShortTitle = clip.title
                            selectedTab = 0
                        },
                        onDeleteClip = onDeleteClip
                    )
                    4 -> MonitorAndStrategyPane(
                        config = config,
                        monitoredVideos = monitoredVideos,
                        strategyReport = youtubeStrategyReport,
                        isBusy = isBusy,
                        onAddMonitoredVideo = onAddMonitoredVideo,
                        onDeleteMonitoredVideo = onDeleteMonitoredVideo,
                        onCheckNow = onCheckMilestonesNow,
                        onTestMilestoneCall = onTestMilestoneCall,
                        onGenerateStrategy = onGenerateStrategy
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun VerifiedChannelBanner(
    config: YouTubeCreatorConfig,
    onOpenSettings: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (config.isAccountAuthorized) SignalGreen.copy(alpha = 0.6f) else StarkAmber.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (config.channelThumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = config.channelThumbnailUrl,
                    contentDescription = stringResource(R.string.cd_channel_avatar),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(CircleShape)
                )
            } else {
                Surface(
                    shape = CircleShape,
                    color = ArcCyan.copy(alpha = 0.16f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Verified, contentDescription = null, tint = if (config.isAccountAuthorized) SignalGreen else StarkAmber)
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (config.isAccountAuthorized) config.channelName else "YouTube OAuth Not Connected",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (config.isAccountAuthorized) SignalGreen else StarkAmber
                )
                Text(
                    text = if (config.isAccountAuthorized) {
                        "${config.channelHandle.ifBlank { config.channelId }} • ${config.subscriberCount} subs • ${config.totalViews} views"
                    } else {
                        "Connect your own YouTube channel in Config via Google OAuth to unlock uploads & private analytics."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.testTag("yt_open_config_button")) {
                Text("CONFIG", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UploadManagerPane(
    config: YouTubeCreatorConfig,
    uploads: List<YouTubeUploadEntity>,
    uploadProgress: UploadProgressState,
    generatedSeo: GeneratedSeoPackage?,
    initialShortUri: Uri?,
    initialShortTitle: String,
    isBusy: Boolean,
    onClearHandoff: () -> Unit,
    onGenerateSeo: (String, Boolean) -> Unit,
    onStartUpload: (Uri, Uri?, String, String, String, String, String?, Boolean) -> Unit,
    onRetryUpload: (YouTubeUploadEntity) -> Unit,
    onSaveCommunityDraft: (String, String) -> Unit,
    onGenerateCommunityCopy: (String, (String) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var subMode by rememberSaveable { mutableStateOf(if (initialShortUri != null) YT_UPLOAD_TYPE_SHORT else YT_UPLOAD_TYPE_SHORT) }
    var selectedVideoUri by remember(initialShortUri) { mutableStateOf(initialShortUri) }
    var selectedThumbUri by remember { mutableStateOf<Uri?>(null) }
    var title by rememberSaveable(initialShortTitle) { mutableStateOf(initialShortTitle) }
    var description by rememberSaveable { mutableStateOf("") }
    var tagsCsv by rememberSaveable { mutableStateOf("") }
    var privacy by rememberSaveable { mutableStateOf(config.defaultPrivacy) }
    var scheduleEnabled by rememberSaveable { mutableStateOf(false) }
    var scheduleHoursAhead by rememberSaveable { mutableStateOf("24") }

    // Community post composer state
    var communityTopic by rememberSaveable { mutableStateOf("") }
    var communityBody by rememberSaveable { mutableStateOf("") }

    // Apply newly generated SEO metadata into fields when ready
    var lastAppliedSeoTitle by remember { mutableStateOf("") }
    if (generatedSeo != null && generatedSeo.title != lastAppliedSeoTitle) {
        lastAppliedSeoTitle = generatedSeo.title
        title = generatedSeo.title
        description = buildString {
            append(generatedSeo.description)
            if (generatedSeo.hashtags.isNotEmpty()) {
                append("\n\n").append(generatedSeo.hashtags.joinToString(" "))
            }
        }
        tagsCsv = generatedSeo.tags.joinToString(", ")
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            selectedVideoUri = uri
            onClearHandoff()
        }
    }
    val thumbPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) selectedThumbUri = uri
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            YT_UPLOAD_TYPE_SHORT to "YouTube Shorts",
            YT_UPLOAD_TYPE_LONG to "Long Videos",
            YT_UPLOAD_TYPE_COMMUNITY to "Community Posts"
        ).forEach { (key, label) ->
            FilterChip(
                selected = subMode == key,
                onClick = { subMode = key },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArcCyan.copy(alpha = 0.22f), selectedLabelColor = ArcCyan),
                modifier = Modifier.testTag("yt_upload_mode_$key")
            )
        }
    }

    if (subMode == YT_UPLOAD_TYPE_COMMUNITY) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, StarkAmber.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("COMMUNITY POSTS — OFFICIAL API LIMITATION", style = MaterialTheme.typography.titleSmall, color = StarkAmber)
                Text(
                    text = "YouTube Data API v3 does not provide a public endpoint to publish Community Posts. JARVIS never fakes publishing — compose high-engagement copy here with Gemini, copy it, and publish directly in YouTube Studio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = communityTopic, onValueChange = { communityTopic = it },
                    label = { Text("Post / Poll Topic") }, singleLine = true,
                    colors = ytTextColors(), modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { onGenerateCommunityCopy(communityTopic) { communityBody = it } },
                    enabled = communityTopic.isNotBlank() && !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ObsidianVoid),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("DRAFT POST WITH EXISTING GEMINI API")
                }
                OutlinedTextField(
                    value = communityBody, onValueChange = { communityBody = it },
                    label = { Text("Community Post Draft") }, minLines = 4, maxLines = 8,
                    colors = ytTextColors(), modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(communityBody))
                            if (communityBody.isNotBlank()) onSaveCommunityDraft(communityTopic.ifBlank { "Community Draft" }, communityBody)
                        },
                        enabled = communityBody.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("COPY & SAVE")
                    }
                    Button(
                        onClick = {
                            val studioIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://studio.youtube.com")).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching { context.startActivity(studioIntent) }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StarkAmber, contentColor = ObsidianVoid),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("YOUTUBE STUDIO")
                    }
                }
            }
        }
    } else {
        val isShort = subMode == YT_UPLOAD_TYPE_SHORT
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (isShort) "UPLOAD YOUTUBE SHORT (RESUMABLE MP4)" else "UPLOAD LONG YOUTUBE VIDEO (RESUMABLE MP4)",
                    style = MaterialTheme.typography.titleSmall,
                    color = ArcCyan
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { videoPicker.launch(arrayOf("video/*")) },
                        border = BorderStroke(1.dp, ArcCyan),
                        modifier = Modifier.weight(1f).testTag("yt_pick_video_button")
                    ) {
                        Icon(Icons.Filled.VideoFile, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (selectedVideoUri != null) "VIDEO SELECTED" else "PICK MP4 VIDEO", color = ArcCyan)
                    }
                    OutlinedButton(
                        onClick = { thumbPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (selectedThumbUri != null) "THUMBNAIL SET" else "THUMBNAIL (OPT)")
                    }
                }
                if (selectedVideoUri != null) {
                    Text(
                        text = "Selected file: ${selectedVideoUri.toString().takeLast(52)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SignalGreen
                    )
                }

                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Video Title / Topic") }, singleLine = true,
                    colors = ytTextColors(), modifier = Modifier.fillMaxWidth().testTag("yt_upload_title_input")
                )

                OutlinedButton(
                    onClick = { onGenerateSeo(title.ifBlank { "Trending Tech Video" }, isShort) },
                    enabled = !isBusy,
                    border = BorderStroke(1.dp, StarkAmber.copy(alpha = 0.7f)),
                    modifier = Modifier.fillMaxWidth().testTag("yt_generate_seo_button")
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = StarkAmber, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("GENERATE SEO (TITLE, DESC, TAGS) VIA GEMINI", color = StarkAmber)
                }

                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("Description & Hashtags") }, minLines = 3, maxLines = 6,
                    colors = ytTextColors(), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tagsCsv, onValueChange = { tagsCsv = it },
                    label = { Text("Tags (comma-separated)") }, singleLine = true,
                    colors = ytTextColors(), modifier = Modifier.fillMaxWidth()
                )

                // Privacy & Scheduled Publishing
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("private", "unlisted", "public").forEach { p ->
                        FilterChip(
                            selected = (if (scheduleEnabled) "private" else privacy) == p,
                            enabled = !scheduleEnabled || p == "private",
                            onClick = { privacy = p },
                            label = { Text(p.uppercase()) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Schedule Publish Time (UTC ISO-8601)", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "Enforces privacyStatus = \"private\" + publishAt per official YouTube API spec.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = scheduleEnabled,
                        onCheckedChange = { scheduleEnabled = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = ObsidianVoid, checkedTrackColor = ArcCyan)
                    )
                }

                if (scheduleEnabled) {
                    OutlinedTextField(
                        value = scheduleHoursAhead,
                        onValueChange = { scheduleHoursAhead = it.filter(Char::isDigit).take(4) },
                        label = { Text("Publish Hours From Now (e.g. 24)") },
                        singleLine = true,
                        colors = ytTextColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (uploadProgress.isUploading || uploadProgress.stageMessage.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = { (uploadProgress.progressPercent.coerceIn(0, 100)) / 100f },
                            color = ArcCyan,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${uploadProgress.progressPercent}% • ${uploadProgress.stageMessage}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (uploadProgress.lastError.isNotBlank()) SignalCrimson else ArcCyan
                        )
                    }
                }

                Button(
                    onClick = {
                        val vidUri = selectedVideoUri ?: return@Button
                        val schedIso = if (scheduleEnabled) {
                            val hrs = (scheduleHoursAhead.toLongOrNull() ?: 24L).coerceIn(1L, 2160L)
                            YouTubeAuthAndApiClient.toUtcIso8601(System.currentTimeMillis() + hrs * 3_600_000L)
                        } else null
                        onStartUpload(
                            vidUri,
                            selectedThumbUri,
                            title.ifBlank { "JARVIS Upload" },
                            description,
                            tagsCsv,
                            if (scheduleEnabled) "private" else privacy,
                            schedIso,
                            isShort
                        )
                    },
                    enabled = selectedVideoUri != null && config.isAccountAuthorized && !uploadProgress.isUploading,
                    colors = ButtonDefaults.buttonColors(containerColor = SignalGreen, contentColor = ObsidianVoid),
                    modifier = Modifier.fillMaxWidth().testTag("yt_start_upload_button")
                ) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (scheduleEnabled) "UPLOAD & SCHEDULE VIDEO" else "UPLOAD TO VERIFIED CHANNEL")
                }
            }
        }
    }

    // Upload History & Retry Recovery
    if (uploads.isNotEmpty()) {
        Text("UPLOAD HISTORY (${uploads.size})", style = MaterialTheme.typography.labelMedium, color = ArcCyan)
        uploads.take(10).forEach { item ->
            val stColor = when (item.status) {
                YT_STATUS_PUBLISHED, YT_STATUS_SCHEDULED -> SignalGreen
                YT_STATUS_FAILED -> SignalCrimson
                else -> StarkAmber
            }
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, stColor.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${item.uploadType} • ${item.title}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = buildString {
                                append(item.status)
                                if (item.youtubeVideoId.isNotBlank()) append(" • ID: ${item.youtubeVideoId}")
                                if (item.scheduledPublishIso.isNotBlank()) append(" • ${item.scheduledPublishIso}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = stColor
                        )
                        if (item.errorMessage.isNotBlank()) {
                            Text(item.errorMessage, style = MaterialTheme.typography.labelSmall, color = SignalCrimson)
                        }
                    }
                    if (item.status == YT_STATUS_FAILED && item.localVideoUri.isNotBlank()) {
                        OutlinedButton(onClick = { onRetryUpload(item) }) {
                            Text("RETRY", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsPane(
    config: YouTubeCreatorConfig,
    publicVideo: PublicVideoAnalytics?,
    channelAnalytics: ChannelAnalyticsReport?,
    isBusy: Boolean,
    onFetchPublicVideo: (String) -> Unit,
    onFetchChannelAnalytics: () -> Unit,
    onAddToMonitor: (String) -> Unit
) {
    var videoLookupInput by rememberSaveable { mutableStateOf("") }

    // Public Video Analyzer
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("PUBLIC VIDEO ANALYZER (YouTube Data API v3)", style = MaterialTheme.typography.titleSmall, color = ArcCyan)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = videoLookupInput,
                    onValueChange = { videoLookupInput = it },
                    label = { Text("YouTube Video URL or 11-char ID") },
                    singleLine = true,
                    colors = ytTextColors(),
                    modifier = Modifier.weight(1f).testTag("yt_public_video_input")
                )
                Button(
                    onClick = { onFetchPublicVideo(videoLookupInput) },
                    enabled = videoLookupInput.isNotBlank() && !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ObsidianVoid),
                    modifier = Modifier.testTag("yt_analyze_public_video_button")
                ) {
                    Icon(Icons.Filled.Search, contentDescription = "Analyze video", modifier = Modifier.size(18.dp))
                }
            }
            if (publicVideo != null) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = DeepHudNavy,
                    border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (publicVideo.thumbnailUrl.isNotBlank()) {
                            AsyncImage(
                                model = publicVideo.thumbnailUrl,
                                contentDescription = stringResource(R.string.cd_video_thumbnail),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                            )
                        }
                        Text(publicVideo.title, style = MaterialTheme.typography.titleSmall, color = ArcCyan)
                        Text("${publicVideo.channelTitle} • ${publicVideo.durationReadable}", style = MaterialTheme.typography.labelSmall)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            MetricPill("VIEWS", publicVideo.views.toString(), SignalGreen)
                            MetricPill("LIKES", publicVideo.likes.toString(), ArcCyan)
                            MetricPill("COMMENTS", publicVideo.comments.toString(), StarkAmber)
                        }
                        OutlinedButton(
                            onClick = { onAddToMonitor(publicVideo.videoId) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("ADD TO AUTOMATIC MILESTONE MONITOR")
                        }
                    }
                }
            }
        }
    }

    // Own Authorized Channel Analytics v2 + Compose Canvas Graphs
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("AUTHORIZED CHANNEL ANALYTICS (${config.analyticsWindowDays}D)", style = MaterialTheme.typography.titleSmall, color = ArcCyan)
                    Text("YouTube Analytics API v2 • channel==MINE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    onClick = onFetchChannelAnalytics,
                    enabled = config.isAccountAuthorized && !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ObsidianVoid),
                    modifier = Modifier.testTag("yt_load_channel_analytics_button")
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("LOAD")
                }
            }

            if (channelAnalytics != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricPill("VIEWS", channelAnalytics.totalViews.toString(), SignalGreen)
                    MetricPill("WATCH MIN", "%.0f".format(channelAnalytics.totalWatchMinutes), ArcCyan)
                    MetricPill("AVG DUR", "${"%.0f".format(channelAnalytics.averageViewDurationSec)}s", StarkAmber)
                    MetricPill("NET SUBS", "+${channelAnalytics.subscribersGained}/-${channelAnalytics.subscribersLost}", SignalGreen)
                }

                // Graph 1: Daily Views Line Graph
                if (channelAnalytics.dailyPoints.isNotEmpty()) {
                    Text("DAILY VIEWS TREND", style = MaterialTheme.typography.labelSmall, color = ArcCyan)
                    DailyLineGraph(
                        values = channelAnalytics.dailyPoints.map { it.views.toFloat() },
                        lineColor = ArcCyan
                    )

                    // Graph 2: Subscribers Gained vs Lost Dual Bar Chart
                    Text("SUBSCRIBERS GAINED (GREEN) VS LOST (RED)", style = MaterialTheme.typography.labelSmall, color = SignalGreen)
                    DualBarGraph(
                        positive = channelAnalytics.dailyPoints.takeLast(14).map { it.subscribersGained.toFloat() },
                        negative = channelAnalytics.dailyPoints.takeLast(14).map { it.subscribersLost.toFloat() }
                    )
                }

                // Graph 3: Video Performance Comparison Bars
                if (channelAnalytics.recentChannelVideos.isNotEmpty()) {
                    Text("VIDEO PERFORMANCE COMPARISON", style = MaterialTheme.typography.labelSmall, color = StarkAmber)
                    val maxViews = channelAnalytics.recentChannelVideos.maxOf { it.views }.coerceAtLeast(1L)
                    channelAnalytics.recentChannelVideos.take(6).forEach { v ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(v.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("${v.views} views", style = MaterialTheme.typography.labelSmall, color = ArcCyan)
                            }
                            LinearProgressIndicator(
                                progress = { (v.views.toFloat() / maxViews.toFloat()).coerceIn(0.03f, 1f) },
                                color = StarkAmber,
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp))
                            )
                        }
                    }
                }

                Text(
                    text = channelAnalytics.apiCoverageNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DailyLineGraph(values: List<Float>, lineColor: Color) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(DeepHudNavy, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        val maxVal = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
        val stepX = if (values.size > 1) size.width / (values.size - 1) else size.width
        val path = Path()
        values.forEachIndexed { idx, v ->
            val x = idx * stepX
            val y = size.height - ((v / maxVal) * size.height * 0.88f) - 4f
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            drawCircle(color = lineColor, radius = 4f, center = Offset(x, y))
        }
        drawPath(path = path, color = lineColor, style = Stroke(width = 3f))
    }
}

@Composable
private fun DualBarGraph(positive: List<Float>, negative: List<Float>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .background(DeepHudNavy, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        val count = positive.size.coerceAtLeast(1)
        val maxVal = (positive + negative).maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val groupW = size.width / count
        val barW = (groupW * 0.36f).coerceAtLeast(4f)
        for (i in 0 until count) {
            val baseX = i * groupW
            val posH = (positive.getOrElse(i) { 0f } / maxVal) * (size.height * 0.85f)
            val negH = (negative.getOrElse(i) { 0f } / maxVal) * (size.height * 0.85f)
            drawRect(color = SignalGreen, topLeft = Offset(baseX + 2f, size.height - posH), size = Size(barW, posH))
            drawRect(color = SignalCrimson, topLeft = Offset(baseX + barW + 4f, size.height - negH), size = Size(barW, negH))
        }
    }
}

@Composable
private fun VideoAuditPane(
    auditReport: String,
    isBusy: Boolean,
    onRunAudit: (Uri, String, String) -> Unit
) {
    val context = LocalContext.current
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var draftTitle by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            pickedUri = uri
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GEMINI PRE-UPLOAD VIDEO AUDIT", style = MaterialTheme.typography.titleSmall, color = ArcCyan)
            Text(
                text = "Audits local device video files (sampled keyframes + technical metadata + optional script) using your existing Gemini API across 9 dimensions. Never downloads YouTube URLs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { picker.launch(arrayOf("video/*")) },
                border = BorderStroke(1.dp, ArcCyan),
                modifier = Modifier.fillMaxWidth().testTag("yt_audit_pick_video_button")
            ) {
                Icon(Icons.Filled.VideoFile, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (pickedUri != null) "LOCAL VIDEO READY FOR AUDIT" else "SELECT LOCAL VIDEO FILE", color = ArcCyan)
            }
            OutlinedTextField(
                value = draftTitle, onValueChange = { draftTitle = it },
                label = { Text("Draft Video Title") }, singleLine = true,
                colors = ytTextColors(), modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = notes, onValueChange = { notes = it },
                label = { Text("Script / Captions / Audio Notes (Optional)") }, minLines = 3, maxLines = 5,
                colors = ytTextColors(), modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { pickedUri?.let { onRunAudit(it, draftTitle, notes) } },
                enabled = pickedUri != null && !isBusy,
                colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ObsidianVoid),
                modifier = Modifier.fillMaxWidth().testTag("yt_run_audit_button")
            ) {
                Icon(Icons.Filled.VideoSettings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("RUN 9-POINT GEMINI VIDEO AUDIT")
            }
        }
    }

    if (auditReport.isNotBlank()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = DeepHudNavy),
            border = BorderStroke(1.dp, SignalGreen.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("PRE-UPLOAD AUDIT REPORT", style = MaterialTheme.typography.labelMedium, color = SignalGreen)
                Text(auditReport, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClippingStudioPane(
    config: YouTubeCreatorConfig,
    clips: List<VideoClipEntity>,
    clippingStatus: String,
    isBusy: Boolean,
    onGenerateFourClips: (Uri, Boolean, Boolean) -> Unit,
    onEditClip: (VideoClipEntity, Long, Long, String, Boolean) -> Unit,
    onExportClipToSaf: (String, Uri) -> Unit,
    onPublishClipAsShort: (VideoClipEntity) -> Unit,
    onDeleteClip: (VideoClipEntity) -> Unit
) {
    val context = LocalContext.current
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var vertical916 by rememberSaveable { mutableStateOf(config.clipVertical916Default) }
    var burnCaptions by rememberSaveable { mutableStateOf(config.clipBurnCaptionsDefault) }
    var editingClip by remember { mutableStateOf<VideoClipEntity?>(null) }
    var pendingExportPath by remember { mutableStateOf<String?>(null) }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            sourceUri = uri
        }
    }
    val safExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { destUri ->
        val srcPath = pendingExportPath
        pendingExportPath = null
        if (destUri != null && !srcPath.isNullOrBlank()) {
            onExportClipToSaf(srcPath, destUri)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI CLIPPING STUDIO (4 REAL MP4 CLIPS PER BATCH)", style = MaterialTheme.typography.titleSmall, color = ArcCyan)
            Text(
                text = "Select a local long video -> JARVIS analyzes keyframes with Gemini -> muxes 4 real .mp4 clips on-device with preview cards, trim/edit controls, SAF export & Short handoff.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { videoPicker.launch(arrayOf("video/*")) },
                border = BorderStroke(1.dp, ArcCyan),
                modifier = Modifier.fillMaxWidth().testTag("yt_clip_pick_source_button")
            ) {
                Icon(Icons.Filled.VideoFile, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (sourceUri != null) "SOURCE VIDEO SELECTED" else "SELECT LOCAL LONG VIDEO", color = ArcCyan)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = vertical916, onClick = { vertical916 = !vertical916 }, label = { Text("9:16 Vertical Preview") })
                FilterChip(selected = burnCaptions, onClick = { burnCaptions = !burnCaptions }, label = { Text("Captions Overlay") })
            }
            if (clippingStatus.isNotBlank()) {
                Text(clippingStatus, style = MaterialTheme.typography.labelSmall, color = StarkAmber)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { sourceUri?.let { onGenerateFourClips(it, vertical916, burnCaptions) } },
                    enabled = sourceUri != null && !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ObsidianVoid),
                    modifier = Modifier.weight(1f).testTag("yt_generate_4_clips_button")
                ) {
                    Icon(Icons.Filled.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (clips.any { it.sourceUri == sourceUri?.toString() }) "GENERATE 4 MORE CLIPS" else "GENERATE 4 CLIPS")
                }
            }
        }
    }

    // Separate Clip Preview Cards
    clips.forEach { clip ->
        val durationSec = ((clip.endMs - clip.startMs) / 1000L).coerceAtLeast(1L)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth().testTag("video_clip_card_${clip.id}")
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    if (clip.thumbnailPath.isNotBlank() && File(clip.thumbnailPath).exists()) {
                        AsyncImage(
                            model = File(clip.thumbnailPath),
                            contentDescription = stringResource(R.string.cd_clip_preview),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(if (clip.isVertical916) 72.dp else 110.dp)
                                .height(if (clip.isVertical916) 120.dp else 72.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(clip.title, style = MaterialTheme.typography.titleSmall, color = ArcCyan)
                        Text(
                            text = "Batch #${clip.batchNumber} • ${VideoClippingEngine.formatMs(clip.startMs)} - ${VideoClippingEngine.formatMs(clip.endMs)} (${durationSec}s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = SignalGreen
                        )
                        if (clip.hookReason.isNotBlank()) {
                            Text(clip.hookReason, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (clip.captionText.isNotBlank()) {
                            Text("Caption: \"${clip.captionText}\"", style = MaterialTheme.typography.labelSmall, color = StarkAmber)
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { editingClip = clip },
                        modifier = Modifier.testTag("edit_clip_${clip.id}")
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("EDIT", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = {
                            pendingExportPath = clip.clipFilePath
                            safExportLauncher.launch("jarvis_clip_${clip.id}.mp4")
                        },
                        modifier = Modifier.testTag("export_clip_${clip.id}")
                    ) {
                        Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("EXPORT MP4", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = { onPublishClipAsShort(clip) },
                        colors = ButtonDefaults.buttonColors(containerColor = SignalGreen, contentColor = ObsidianVoid),
                        modifier = Modifier.testTag("publish_short_clip_${clip.id}")
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("USE AS SHORT", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { onDeleteClip(clip) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete clip", tint = SignalCrimson)
                    }
                }
            }
        }
    }

    editingClip?.let { clip ->
        ClipEditDialog(
            clip = clip,
            onDismiss = { editingClip = null },
            onSave = { sMs, eMs, cap, v916 ->
                editingClip = null
                onEditClip(clip, sMs, eMs, cap, v916)
            }
        )
    }
}

@Composable
private fun ClipEditDialog(
    clip: VideoClipEntity,
    onDismiss: () -> Unit,
    onSave: (startMs: Long, endMs: Long, caption: String, vertical916: Boolean) -> Unit
) {
    var startSec by remember { mutableStateOf((clip.startMs / 1000L).toString()) }
    var endSec by remember { mutableStateOf((clip.endMs / 1000L).toString()) }
    var caption by remember { mutableStateOf(clip.captionText) }
    var vertical916 by remember { mutableStateOf(clip.isVertical916) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text("Edit & Re-Export Clip", color = ArcCyan) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startSec, onValueChange = { startSec = it.filter(Char::isDigit) },
                        label = { Text("Start (sec)") }, singleLine = true, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = endSec, onValueChange = { endSec = it.filter(Char::isDigit) },
                        label = { Text("End (sec)") }, singleLine = true, modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = caption, onValueChange = { caption = it },
                    label = { Text("Caption Overlay") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("9:16 Vertical Framing")
                    Switch(checked = vertical916, onCheckedChange = { vertical916 = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val s = (startSec.toLongOrNull() ?: 0L) * 1000L
                val e = ((endSec.toLongOrNull() ?: ((s / 1000L) + 15L)) * 1000L).coerceAtLeast(s + 2_000L)
                onSave(s, e, caption, vertical916)
            }) {
                Text("RE-EXPORT MP4")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonitorAndStrategyPane(
    config: YouTubeCreatorConfig,
    monitoredVideos: List<MonitoredYouTubeVideoEntity>,
    strategyReport: String,
    isBusy: Boolean,
    onAddMonitoredVideo: (String) -> Unit,
    onDeleteMonitoredVideo: (MonitoredYouTubeVideoEntity) -> Unit,
    onCheckNow: () -> Unit,
    onTestMilestoneCall: () -> Unit,
    onGenerateStrategy: () -> Unit
) {
    var addVideoInput by rememberSaveable { mutableStateOf("") }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AUTOMATIC MILESTONE MONITOR & IN-APP CALLS", style = MaterialTheme.typography.titleSmall, color = ArcCyan)
            Text(
                text = "Thresholds: ${config.viewMilestonesCsv} • Interval: every ${config.monitoringIntervalMinutes}m • One in-app JARVIS call per crossed milestone (DND respected).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = addVideoInput, onValueChange = { addVideoInput = it },
                    label = { Text("Add Video ID / URL to Monitor") }, singleLine = true,
                    colors = ytTextColors(), modifier = Modifier.weight(1f).testTag("yt_monitor_add_input")
                )
                Button(
                    onClick = {
                        if (addVideoInput.isNotBlank()) {
                            onAddMonitoredVideo(addVideoInput)
                            addVideoInput = ""
                        }
                    },
                    enabled = addVideoInput.isNotBlank() && !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ObsidianVoid)
                ) {
                    Text("TRACK")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onCheckNow,
                    enabled = !isBusy,
                    border = BorderStroke(1.dp, ArcCyan),
                    modifier = Modifier.weight(1f).testTag("yt_check_milestones_now_button")
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("CHECK NOW", color = ArcCyan, style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = onTestMilestoneCall,
                    border = BorderStroke(1.dp, SignalGreen),
                    modifier = Modifier.weight(1f).testTag("yt_test_milestone_call_button")
                ) {
                    Icon(Icons.Filled.PhoneInTalk, contentDescription = null, tint = SignalGreen, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("TEST CALL", color = SignalGreen, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    monitoredVideos.forEach { mv ->
        val delta = (mv.currentViews - mv.previousViews).coerceAtLeast(0L)
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(mv.title, style = MaterialTheme.typography.titleSmall, color = ArcCyan, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = "Views: ${mv.currentViews} (+$delta recent) • Likes: ${mv.currentLikes} • Comments: ${mv.currentComments}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SignalGreen
                        )
                    }
                    IconButton(onClick = { onDeleteMonitoredVideo(mv) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Stop monitoring video", tint = SignalCrimson)
                    }
                }
                if (mv.reachedMilestonesCsv.isNotBlank()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        mv.reachedMilestonesCsv.split(',').filter { it.isNotBlank() }.forEach { m ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = StarkAmber.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, StarkAmber.copy(alpha = 0.55f))
                            ) {
                                Text(
                                    text = "$m VIEWS ✓",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StarkAmber,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // AI YouTube Strategy Assistant
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, StarkAmber.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI YOUTUBE STRATEGY ASSISTANT", style = MaterialTheme.typography.titleSmall, color = StarkAmber)
            Text(
                text = "Analyzes measured channel & video statistics using your existing Gemini API. Clearly separates [MEASURED STATISTICS] from [AI STRATEGY & ESTIMATES] — never promises viral views.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onGenerateStrategy,
                enabled = !isBusy,
                colors = ButtonDefaults.buttonColors(containerColor = StarkAmber, contentColor = ObsidianVoid),
                modifier = Modifier.fillMaxWidth().testTag("yt_generate_strategy_button")
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("GENERATE DATA-GROUNDED STRATEGY")
            }
            if (strategyReport.isNotBlank()) {
                Text(strategyReport, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MetricPill(label: String, value: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = accent.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = accent)
            Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ytTextColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = ArcCyan,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface
)
