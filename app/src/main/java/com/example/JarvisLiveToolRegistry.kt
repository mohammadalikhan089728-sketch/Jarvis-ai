package com.example

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Gemini Multimodal Live Function-Calling Tool Registry & Executor for J.A.R.V.I.S. (Developed by Mozahid).
 *
 * Implements:
 * 1. Token-Saving Lazy Screen Reading (`ui_read`): Screen hierarchy is NEVER sent automatically on normal
 *    conversation turns; it is read ONLY when the user explicitly asks about the screen or requests a screen tap.
 * 2. Full YouTube Data API v3 (OAuth 2.0) Tool Declarations:
 *    - `get_youtube_channel_stats`: Live subscribers, total views, video count, and latest video performance.
 *    - `upload_youtube_video`: Uploads a video via existing OAuth 2.0 resumable `videos.insert` (or opens Upload Manager).
 * 3. Accessibility Phone Control (`ui_action`), Clean YouTube Search/Play (`youtube_search_play`),
 *    and In-App JARVIS Call / Reminder Trigger (`trigger_jarvis_call`).
 */
object JarvisLiveToolRegistry {

    const val TOOL_UI_READ = "ui_read"
    const val TOOL_UI_ACTION = "ui_action"
    const val TOOL_YOUTUBE_SEARCH_PLAY = "youtube_search_play"
    const val TOOL_GET_YOUTUBE_CHANNEL_STATS = "get_youtube_channel_stats"
    const val TOOL_UPLOAD_YOUTUBE_VIDEO = "upload_youtube_video"
    const val TOOL_TRIGGER_JARVIS_CALL = "trigger_jarvis_call"

    /**
     * Builds the official Gemini Multimodal Live `tools` JSON array containing all `functionDeclarations`.
     */
    fun buildLiveToolsJsonArray(): JSONArray {
        val declarations = JSONArray()

        // 1. Lazy Token-Saving Screen Reader (`ui_read`)
        declarations.put(
            JSONObject().apply {
                put("name", TOOL_UI_READ)
                put(
                    "description",
                    "Lazily reads the current Android screen UI hierarchy via Accessibility Service. " +
                        "Call this ONLY when the user explicitly asks what is on their screen, asks to read/translate/explain " +
                        "the screen, or asks to click/tap a visible on-screen element. NEVER call during normal conversation."
                )
                put(
                    "parameters",
                    JSONObject().apply {
                        put("type", "OBJECT")
                        put(
                            "properties",
                            JSONObject().apply {
                                put(
                                    "max_nodes",
                                    JSONObject()
                                        .put("type", "INTEGER")
                                        .put("description", "Maximum interactive UI nodes to inspect (default 60).")
                                )
                            }
                        )
                    }
                )
            }
        )

        // 2. Android UI & Accessibility Control (`ui_action`)
        declarations.put(
            JSONObject().apply {
                put("name", TOOL_UI_ACTION)
                put(
                    "description",
                    "Executes a real Android phone control action via Accessibility Service or OS intents: " +
                        "OPEN_APP, TAP_TEXT, TAP_XY, TYPE_TEXT, SCROLL_DOWN, SCROLL_UP, HOME, BACK, RECENTS, " +
                        "FLASHLIGHT_ON, FLASHLIGHT_OFF, VOLUME_UP, VOLUME_DOWN, WIFI, BLUETOOTH."
                )
                put(
                    "parameters",
                    JSONObject().apply {
                        put("type", "OBJECT")
                        put(
                            "properties",
                            JSONObject().apply {
                                put(
                                    "action",
                                    JSONObject()
                                        .put("type", "STRING")
                                        .put(
                                            "description",
                                            "Action name: OPEN_APP, TAP_TEXT, TAP_XY, TYPE_TEXT, SCROLL_DOWN, SCROLL_UP, HOME, BACK, RECENTS, FLASHLIGHT_ON, FLASHLIGHT_OFF, VOLUME_UP, VOLUME_DOWN, WIFI, BLUETOOTH"
                                        )
                                )
                                put(
                                    "target",
                                    JSONObject()
                                        .put("type", "STRING")
                                        .put("description", "App name, button text to tap, or text to type.")
                                )
                                put(
                                    "x",
                                    JSONObject().put("type", "NUMBER").put("description", "Optional X coordinate for TAP_XY.")
                                )
                                put(
                                    "y",
                                    JSONObject().put("type", "NUMBER").put("description", "Optional Y coordinate for TAP_XY.")
                                )
                                put(
                                    "submit",
                                    JSONObject().put("type", "BOOLEAN").put("description", "Whether to press Enter/Submit after TYPE_TEXT.")
                                )
                            }
                        )
                        put("required", JSONArray().put("action"))
                    }
                )
            }
        )

        // 3. Clean YouTube Search & Play (`youtube_search_play`)
        declarations.put(
            JSONObject().apply {
                put("name", TOOL_YOUTUBE_SEARCH_PLAY)
                put(
                    "description",
                    "Opens YouTube, searches ONLY the clean search keyword (never full sentences), opens the first video, and verifies playback."
                )
                put(
                    "parameters",
                    JSONObject().apply {
                        put("type", "OBJECT")
                        put(
                            "properties",
                            JSONObject().apply {
                                put(
                                    "query",
                                    JSONObject()
                                        .put("type", "STRING")
                                        .put("description", "Clean YouTube search keyword only, e.g. 'MrBeast'.")
                                )
                                put(
                                    "go_home_first",
                                    JSONObject()
                                        .put("type", "BOOLEAN")
                                        .put("description", "Whether to go to home screen before opening YouTube.")
                                )
                            }
                        )
                        put("required", JSONArray().put("query"))
                    }
                )
            }
        )

        // 4. YouTube Data API v3 Real-Time Channel & Latest Video Stats (`get_youtube_channel_stats`)
        declarations.put(
            JSONObject().apply {
                put("name", TOOL_GET_YOUTUBE_CHANNEL_STATS)
                put(
                    "description",
                    "Fetches real-time connected YouTube channel statistics (subscriber count, total channel views, video count) " +
                        "and latest uploaded videos' views, likes, and comments using the existing OAuth 2.0 YouTube Data API v3 credentials. " +
                        "Always call this tool before answering any question about YouTube subscribers, views, latest video performance, or recent upload status."
                )
                put(
                    "parameters",
                    JSONObject().apply {
                        put("type", "OBJECT")
                        put(
                            "properties",
                            JSONObject().apply {
                                put(
                                    "video_id_or_url",
                                    JSONObject()
                                        .put("type", "STRING")
                                        .put("description", "Optional specific YouTube video ID or URL to inspect in addition to channel stats.")
                                )
                                put(
                                    "max_recent_videos",
                                    JSONObject()
                                        .put("type", "INTEGER")
                                        .put("description", "How many latest channel videos to include (1-10, default 5).")
                                )
                            }
                        )
                    }
                )
            }
        )

        // 5. YouTube Data API v3 OAuth Video Upload (`upload_youtube_video`)
        declarations.put(
            JSONObject().apply {
                put("name", TOOL_UPLOAD_YOUTUBE_VIDEO)
                put(
                    "description",
                    "Uploads a local video to the user's connected YouTube channel using existing OAuth 2.0 resumable videos.insert credentials. " +
                        "Can upload a provided file/content URI, the latest generated AI Clipping Studio clip (`use_latest_clip=true`), " +
                        "or open the YouTube Upload Manager if no video file is selected yet."
                )
                put(
                    "parameters",
                    JSONObject().apply {
                        put("type", "OBJECT")
                        put(
                            "properties",
                            JSONObject().apply {
                                put("title", JSONObject().put("type", "STRING").put("description", "Video title (max 100 chars)."))
                                put("description", JSONObject().put("type", "STRING").put("description", "Video description."))
                                put("tags_csv", JSONObject().put("type", "STRING").put("description", "Comma-separated tags."))
                                put(
                                    "privacy_status",
                                    JSONObject()
                                        .put("type", "STRING")
                                        .put("description", "public, unlisted, or private (defaults to user's configured privacy).")
                                )
                                put("is_short", JSONObject().put("type", "BOOLEAN").put("description", "True for YouTube Shorts."))
                                put(
                                    "video_uri",
                                    JSONObject().put("type", "STRING").put("description", "Optional content:// or file:// URI of local MP4.")
                                )
                                put(
                                    "use_latest_clip",
                                    JSONObject()
                                        .put("type", "BOOLEAN")
                                        .put("description", "Set true to upload the most recently exported clip from JARVIS AI Clipping Studio.")
                                )
                                put(
                                    "scheduled_publish_iso_utc",
                                    JSONObject()
                                        .put("type", "STRING")
                                        .put("description", "Optional ISO-8601 UTC publish timestamp; forces privacyStatus='private'.")
                                )
                            }
                        )
                        put("required", JSONArray().put("title"))
                    }
                )
            }
        )

        // 6. Existing In-App JARVIS Call Alert Trigger (`trigger_jarvis_call`)
        declarations.put(
            JSONObject().apply {
                put("name", TOOL_TRIGGER_JARVIS_CALL)
                put(
                    "description",
                    "Triggers the app's existing JARVIS incoming voice call alert screen immediately or reports scheduled call status."
                )
                put(
                    "parameters",
                    JSONObject().apply {
                        put("type", "OBJECT")
                        put(
                            "properties",
                            JSONObject().apply {
                                put("purpose", JSONObject().put("type", "STRING").put("description", "Reason/title for the JARVIS call."))
                                put("summary", JSONObject().put("type", "STRING").put("description", "Brief voice message for the call."))
                            }
                        )
                        put("required", JSONArray().put("purpose"))
                    }
                )
            }
        )

        return JSONArray().put(
            JSONObject().put("functionDeclarations", declarations)
        )
    }

    /**
     * Compact, token-light (~25 tokens) YouTube channel identity summary injected into the Live session setup
     * so JARVIS knows your connected channel without fetching stats until asked.
     */
    fun buildCompactYouTubeChannelContext(context: Context): String {
        val cfg = SecureCredentialVault.getInstance(context.applicationContext).loadConfig()
        return if (cfg.isAccountAuthorized) {
            val handlePart = if (cfg.channelHandle.isNotBlank()) " (${cfg.channelHandle})" else ""
            "CONNECTED YOUTUBE CHANNEL (OAuth 2.0 Verified): ${cfg.channelName}$handlePart [channelId=${cfg.channelId}]. " +
                "Call `get_youtube_channel_stats` for live subscribers/views/latest video stats, and `upload_youtube_video` to upload."
        } else {
            "YOUTUBE CHANNEL STATUS: Not connected via OAuth yet. If user asks for channel stats or upload, call `get_youtube_channel_stats` or advise authorizing in YouTube/Config tab."
        }
    }

    /**
     * Executes a Gemini Live `functionCall` and returns a structured JSON response payload
     * ready to be sent back inside `toolResponse.functionResponses`.
     */
    suspend fun executeToolCall(
        context: Context,
        toolName: String,
        args: JSONObject
    ): JSONObject = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        return@withContext when (toolName) {
            TOOL_UI_READ -> executeUiRead(args)
            TOOL_UI_ACTION -> executeUiAction(appContext, args)
            TOOL_YOUTUBE_SEARCH_PLAY -> executeYouTubeSearchPlay(appContext, args)
            TOOL_GET_YOUTUBE_CHANNEL_STATS -> executeGetYouTubeChannelStats(appContext, args)
            TOOL_UPLOAD_YOUTUBE_VIDEO -> executeUploadYouTubeVideo(appContext, args)
            TOOL_TRIGGER_JARVIS_CALL -> executeTriggerJarvisCall(appContext, args)
            else -> JSONObject()
                .put("ok", false)
                .put("error", "Unknown tool '$toolName'")
        }
    }

    private fun executeUiRead(args: JSONObject): JSONObject {
        val service = JarvisAccessibilityService.instance
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "Accessibility Service is inactive. Ask the user to enable JARVIS Screen & Touch Control in Systems.")
        val maxNodes = args.optInt("max_nodes", 60).coerceIn(15, 100)
        val snapshot = service.readScreenHierarchy(maxNodes = maxNodes)
            ?: return JSONObject().put("ok", false).put("error", "No active screen content detected.")
        return JSONObject()
            .put("ok", true)
            .put("packageName", snapshot.packageName)
            .put("nodeCount", snapshot.nodes.size)
            .put("readableSummary", snapshot.toPlainReadableText().take(2400))
            .put("compactUiJson", snapshot.toCompactJson().take(3200))
    }

    private suspend fun executeUiAction(context: Context, args: JSONObject): JSONObject {
        val action = args.optString("action", "").trim().uppercase(Locale.ROOT)
        val target = args.optString("target", "").trim()
        val offline = OfflineCommandProcessor(context)
        val service = JarvisAccessibilityService.instance

        val errorNote: String? = when (action) {
            "OPEN_APP" -> {
                if (offline.openInstalledApp(target) != null) null else "App '$target' not found on device."
            }
            "TAP_TEXT" -> {
                if (service == null) "Accessibility Service inactive."
                else if (service.tapByText(target)) null else "Could not find '$target' on screen."
            }
            "TAP_XY" -> {
                if (service == null) "Accessibility Service inactive."
                else {
                    val x = args.optDouble("x", -1.0).toFloat()
                    val y = args.optDouble("y", -1.0).toFloat()
                    if (x < 0f || y < 0f) "Invalid x/y coordinates."
                    else if (service.tapXY(x, y, 80L)) null else "Tap gesture failed."
                }
            }
            "TYPE_TEXT" -> {
                if (service == null) "Accessibility Service inactive."
                else {
                    val submit = args.optBoolean("submit", false)
                    if (service.typeText(target, null, submitAfterTyping = submit)) null
                    else "No editable input field found on screen."
                }
            }
            "SCROLL_DOWN" -> if (service?.scrollDown() == true) null else "Scroll down failed or Accessibility inactive."
            "SCROLL_UP" -> if (service?.scrollUp() == true) null else "Scroll up failed or Accessibility inactive."
            "HOME" -> if (service?.performGlobalSystemActionAndSettle("HOME") == true) null else "Home action failed."
            "BACK" -> if (service?.performGlobalSystemActionAndSettle("BACK") == true) null else "Back action failed."
            "RECENTS" -> if (service?.performGlobalSystemActionAndSettle("RECENTS") == true) null else "Recents action failed."
            "FLASHLIGHT_ON" -> if (offline.toggleFlashlight(true)) null else "Flashlight unavailable."
            "FLASHLIGHT_OFF" -> if (offline.toggleFlashlight(false)) null else "Flashlight unavailable."
            "VOLUME_UP" -> {
                offline.adjustVolume(AudioManager.ADJUST_RAISE)
                null
            }
            "VOLUME_DOWN" -> {
                offline.adjustVolume(AudioManager.ADJUST_LOWER)
                null
            }
            "WIFI" -> {
                offline.openWifiQuickPanel()
                null
            }
            "BLUETOOTH" -> {
                offline.openBluetoothSettings()
                null
            }
            else -> "Unsupported ui_action '$action'."
        }

        return JSONObject()
            .put("ok", errorNote == null)
            .put("action", action)
            .put("target", target)
            .put("detail", errorNote ?: "Action executed successfully.")
    }

    private suspend fun executeYouTubeSearchPlay(context: Context, args: JSONObject): JSONObject {
        val rawQuery = args.optString("query", "").trim()
        val cleanKeyword = NaturalLanguageActionPlanner.extractCleanYouTubeSearchKeyword(rawQuery)
            .ifBlank { rawQuery }
        val goHomeFirst = args.optBoolean("go_home_first", false)
        val syntheticCmd = buildString {
            if (goHomeFirst) append("Go to home screen, ")
            append("open YouTube, search ")
            append(cleanKeyword)
            append(", open a video and play it")
        }
        val plan = NaturalLanguageActionPlanner.planCommand(syntheticCmd)
            ?: return JSONObject().put("ok", false).put("error", "Could not build YouTube playback plan.")
        val repo = JarvisRepository(JarvisDatabase.getInstance(context))
        val outcome = NaturalLanguageActionPlanner.executeWorkflow(
            context = context,
            workflow = plan,
            offlineProcessor = OfflineCommandProcessor(context),
            repository = repo
        )
        return JSONObject()
            .put("ok", outcome.succeeded)
            .put("searchedKeyword", plan.extractedSearchKeyword)
            .put("completedSteps", outcome.completedSteps)
            .put("totalSteps", outcome.totalSteps)
            .put("summary", outcome.spokenReply)
    }

    private suspend fun executeGetYouTubeChannelStats(context: Context, args: JSONObject): JSONObject {
        val ytClient = YouTubeAuthAndApiClient(context)
        val repo = JarvisRepository(JarvisDatabase.getInstance(context))
        val vault = SecureCredentialVault.getInstance(context)
        val cfg = vault.loadConfig()
        val maxVideos = args.optInt("max_recent_videos", 5).coerceIn(1, 10)
        val specificVideo = args.optString("video_id_or_url", "").trim()

        val result = JSONObject()

        if (specificVideo.isNotBlank()) {
            val videoRes = ytClient.fetchPublicVideoStats(specificVideo)
            videoRes.fold(
                onSuccess = { v ->
                    result.put(
                        "requestedVideo",
                        JSONObject()
                            .put("videoId", v.videoId)
                            .put("title", v.title)
                            .put("channelTitle", v.channelTitle)
                            .put("views", v.views)
                            .put("likes", v.likes)
                            .put("comments", v.comments)
                            .put("publishedAt", v.publishedAt)
                            .put("duration", v.durationReadable)
                    )
                },
                onFailure = { err ->
                    result.put("requestedVideoError", err.localizedMessage ?: "Failed to fetch video stats")
                }
            )
        }

        if (!cfg.isAccountAuthorized) {
            return result
                .put("ok", result.has("requestedVideo"))
                .put("channelAuthorized", false)
                .put("message", "No YouTube channel is connected via OAuth 2.0 yet. Ask the user to connect their channel in the YouTube or Config tab.")
        }

        val liveSnap = ytClient.fetchLiveChannelSnapshotWithLatestVideos(maxVideos)
        return liveSnap.fold(
            onSuccess = { (channel, latestVideos) ->
                val videosArr = JSONArray()
                latestVideos.forEach { v ->
                    videosArr.put(
                        JSONObject()
                            .put("videoId", v.videoId)
                            .put("title", v.title)
                            .put("views", v.views)
                            .put("likes", v.likes)
                            .put("comments", v.comments)
                            .put("publishedAt", v.publishedAt)
                            .put("duration", v.durationReadable)
                    )
                }

                // Also include recent upload queue status from local Room DB
                val recentUploads = repo.getActiveMonitoredVideos().take(3)
                val monitoredArr = JSONArray()
                recentUploads.forEach { m ->
                    monitoredArr.put(
                        JSONObject()
                            .put("videoId", m.videoId)
                            .put("title", m.title)
                            .put("trackedViews", m.currentViews)
                            .put("reachedMilestones", m.reachedMilestonesCsv)
                    )
                }

                result
                    .put("ok", true)
                    .put("channelAuthorized", true)
                    .put("channelId", channel.channelId)
                    .put("channelName", channel.title)
                    .put("channelHandle", channel.handle)
                    .put("subscriberCount", channel.subscriberCount)
                    .put("totalChannelViews", channel.totalViews)
                    .put("totalVideoCount", channel.videoCount)
                    .put("latestVideos", videosArr)
                    .put("monitoredVideos", monitoredArr)
            },
            onFailure = { err ->
                result
                    .put("ok", false)
                    .put("channelAuthorized", true)
                    .put("cachedChannelName", cfg.channelName)
                    .put("cachedSubscribers", cfg.subscriberCount)
                    .put("cachedTotalViews", cfg.totalViews)
                    .put("error", err.localizedMessage ?: "Failed to fetch live YouTube channel stats")
            }
        )
    }

    private suspend fun executeUploadYouTubeVideo(context: Context, args: JSONObject): JSONObject {
        val vault = SecureCredentialVault.getInstance(context)
        val cfg = vault.loadConfig()
        if (!cfg.isAccountAuthorized) {
            JarvisNavigationEvents.requestNavigate(ROUTE_YOUTUBE)
            return JSONObject()
                .put("ok", false)
                .put("error", "YouTube OAuth 2.0 account is not connected yet. Opened the YouTube tab so the user can sign in first.")
        }

        val repo = JarvisRepository(JarvisDatabase.getInstance(context))
        val title = args.optString("title", "").trim().ifBlank { "JARVIS Creator Upload" }
        val description = args.optString("description", "").trim()
        val tags = args.optString("tags_csv", "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val isShort = args.optBoolean("is_short", false)
        val privacy = args.optString("privacy_status", cfg.defaultPrivacy).trim().ifBlank { cfg.defaultPrivacy }
        val scheduleIso = args.optString("scheduled_publish_iso_utc", "").trim().takeIf { it.isNotBlank() }
        val explicitUriStr = args.optString("video_uri", "").trim()
        val useLatestClip = args.optBoolean("use_latest_clip", false)

        val resolvedUri: Uri? = when {
            explicitUriStr.isNotBlank() -> runCatching { Uri.parse(explicitUriStr) }.getOrNull()
            useLatestClip -> {
                val clipFile = findLatestGeneratedClipFile(context)
                clipFile?.let { Uri.fromFile(it) }
            }
            else -> null
        }

        if (resolvedUri == null) {
            JarvisNavigationEvents.requestNavigate(ROUTE_YOUTUBE)
            return JSONObject()
                .put("ok", false)
                .put("needsFileSelection", true)
                .put(
                    "message",
                    "Opened the YouTube Upload Manager for channel ${cfg.channelName}. " +
                        "Please pick the local video file to upload with title \"$title\" (or say upload my latest clip)."
                )
        }

        val uploadId = repo.saveYouTubeUpload(
            YouTubeUploadEntity(
                uploadType = if (isShort) YT_UPLOAD_TYPE_SHORT else YT_UPLOAD_TYPE_LONG,
                localVideoUri = resolvedUri.toString(),
                title = title,
                description = description,
                tagsCsv = tags.joinToString(", "),
                privacyStatus = if (scheduleIso != null) "private" else privacy,
                scheduledPublishIso = scheduleIso.orEmpty(),
                channelId = cfg.channelId,
                status = YT_STATUS_UPLOADING,
                progressPercent = 10
            )
        )

        val ytClient = YouTubeAuthAndApiClient(context)
        val uploadRes = ytClient.uploadVideoResumable(
            videoUri = resolvedUri,
            thumbnailUri = null,
            title = title,
            description = description,
            tags = tags,
            privacyStatus = privacy,
            scheduledPublishIsoUtc = scheduleIso,
            isShort = isShort,
            categoryId = cfg.defaultCategoryId,
            madeForKids = cfg.defaultMadeForKids,
            onProgress = { _, _ -> }
        )

        return uploadRes.fold(
            onSuccess = { videoId ->
                repo.saveYouTubeUpload(
                    YouTubeUploadEntity(
                        id = uploadId,
                        uploadType = if (isShort) YT_UPLOAD_TYPE_SHORT else YT_UPLOAD_TYPE_LONG,
                        localVideoUri = resolvedUri.toString(),
                        title = title,
                        description = description,
                        tagsCsv = tags.joinToString(", "),
                        privacyStatus = if (scheduleIso != null) "private" else privacy,
                        scheduledPublishIso = scheduleIso.orEmpty(),
                        youtubeVideoId = videoId,
                        channelId = cfg.channelId,
                        status = if (scheduleIso != null) YT_STATUS_SCHEDULED else YT_STATUS_PUBLISHED,
                        progressPercent = 100
                    )
                )
                repo.upsertMonitoredVideo(
                    MonitoredYouTubeVideoEntity(
                        videoId = videoId,
                        title = title,
                        channelId = cfg.channelId,
                        currentViews = 0L,
                        previousViews = 0L
                    )
                )
                YouTubeMilestoneMonitorScheduler.syncPeriodicSchedule(context)
                JSONObject()
                    .put("ok", true)
                    .put("videoId", videoId)
                    .put("title", title)
                    .put("channelName", cfg.channelName)
                    .put("status", if (scheduleIso != null) "SCHEDULED" else "PUBLISHED")
                    .put("milestoneMonitoringRegistered", true)
            },
            onFailure = { err ->
                repo.saveYouTubeUpload(
                    YouTubeUploadEntity(
                        id = uploadId,
                        uploadType = if (isShort) YT_UPLOAD_TYPE_SHORT else YT_UPLOAD_TYPE_LONG,
                        localVideoUri = resolvedUri.toString(),
                        title = title,
                        description = description,
                        tagsCsv = tags.joinToString(", "),
                        privacyStatus = privacy,
                        channelId = cfg.channelId,
                        status = YT_STATUS_FAILED,
                        errorMessage = err.localizedMessage ?: "Upload failed"
                    )
                )
                JSONObject()
                    .put("ok", false)
                    .put("error", err.localizedMessage ?: "YouTube video upload failed.")
            }
        )
    }

    private fun executeTriggerJarvisCall(context: Context, args: JSONObject): JSONObject {
        val purpose = args.optString("purpose", "JARVIS Voice Check-In").trim()
        val summary = args.optString("summary", purpose).trim()
        CallAlarmReceiver.launchIncomingCall(
            context = context,
            purpose = purpose,
            actionType = CALL_TYPE_CUSTOM_REMINDER,
            alertSummary = summary
        )
        return JSONObject()
            .put("ok", true)
            .put("message", "Triggered JARVIS incoming call alert for: $purpose")
    }

    private fun findLatestGeneratedClipFile(context: Context): File? {
        val base = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val dir = File(base, "JarvisClips")
        if (!dir.exists()) return null
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4", ignoreCase = true) && f.length() > 0L }
            ?.maxByOrNull { it.lastModified() }
    }
}
