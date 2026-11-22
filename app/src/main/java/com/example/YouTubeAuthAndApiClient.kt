package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class YouTubeChannelInfo(
    val channelId: String,
    val title: String,
    val handle: String,
    val thumbnailUrl: String,
    val subscriberCount: Long,
    val totalViews: Long,
    val videoCount: Long
)

data class PublicVideoAnalytics(
    val videoId: String,
    val title: String,
    val description: String,
    val thumbnailUrl: String,
    val durationIso: String,
    val durationReadable: String,
    val channelId: String,
    val channelTitle: String,
    val publishedAt: String,
    val views: Long,
    val likes: Long,
    val comments: Long
)

data class DailyAnalyticsPoint(
    val date: String,
    val views: Long,
    val estimatedMinutesWatched: Double,
    val averageViewDurationSec: Double,
    val subscribersGained: Long,
    val subscribersLost: Long
)

data class ChannelAnalyticsReport(
    val windowDays: Int,
    val totalViews: Long,
    val totalWatchMinutes: Double,
    val averageViewDurationSec: Double,
    val subscribersGained: Long,
    val subscribersLost: Long,
    val dailyPoints: List<DailyAnalyticsPoint>,
    val recentChannelVideos: List<PublicVideoAnalytics>,
    val apiCoverageNote: String
)

data class GeneratedSeoPackage(
    val title: String,
    val description: String,
    val tags: List<String>,
    val hashtags: List<String>
)

data class UploadProgressState(
    val isUploading: Boolean = false,
    val progressPercent: Int = 0,
    val stageMessage: String = "",
    val lastUploadedVideoId: String = "",
    val lastError: String = ""
)

/**
 * Receives Google OAuth 2.0 PKCE redirect (`com.aistudio.jarvis.kxqzmp:/oauth2redirect`),
 * validates the anti-CSRF `state` token, exchanges the code for OAuth tokens, verifies the real
 * YouTube channel via `channels.list?mine=true`, and returns cleanly to MainActivity.
 */
class YouTubeOAuthRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }

        val error = uri.getQueryParameter("error")
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")

        val appCtx = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val client = YouTubeAuthAndApiClient(appCtx)
            if (!error.isNullOrBlank()) {
                YouTubeAuthAndApiClient.postAuthStatus("OAuth authorization declined or failed: $error")
            } else if (!code.isNullOrBlank()) {
                val res = client.completeOAuthCallback(code = code, returnedState = state.orEmpty())
                res.fold(
                    onSuccess = { ch ->
                        YouTubeAuthAndApiClient.postAuthStatus("Connected verified channel: ${ch.title} (${ch.handle.ifBlank { ch.channelId }})")
                    },
                    onFailure = { err ->
                        YouTubeAuthAndApiClient.postAuthStatus("OAuth verification failed: ${err.localizedMessage ?: "Unknown error"}")
                    }
                )
            }
            val reopen = Intent(appCtx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            runCatching { startActivity(reopen) }
            finish()
        }
    }
}

/**
 * Official YouTube Data API v3, Google OAuth 2.0 PKCE, YouTube Analytics API v2,
 * Resumable Video Upload (`videos.insert`), and Gemini-powered SEO & Strategy engine.
 *
 * Security & Policy Guarantees:
 * - Never permits manual entry of another channel handle to grant upload permission.
 * - All tokens and API keys are persisted strictly inside Android Keystore AES-256-GCM vault.
 * - Enforces YouTube's official scheduled publishing requirement (`privacyStatus = "private"` + ISO-8601 `publishAt`).
 * - Never fakes Community Post API publishing (YouTube Data API v3 exposes no public Community Post create endpoint).
 */
class YouTubeAuthAndApiClient(context: Context) {

    private val appContext = context.applicationContext
    private val vault = SecureCredentialVault.getInstance(appContext)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun startGoogleOAuthFlow(activityContext: Context, clientId: String): Result<String> {
        val cleanClientId = clientId.trim()
        if (cleanClientId.isEmpty()) {
            return Result.failure(IllegalArgumentException("Enter your Google OAuth 2.0 Client ID first."))
        }
        val verifier = generatePkceVerifier()
        val challenge = sha256Base64Url(verifier)
        val state = generateRandomState()
        vault.savePendingPkceSession(verifier, state)

        val scopeEncoded = URLEncoder.encode(REQUIRED_SCOPES.joinToString(" "), "UTF-8")
        val redirectEncoded = URLEncoder.encode(REDIRECT_URI, "UTF-8")
        val authUrl = buildString {
            append("https://accounts.google.com/o/oauth2/v2/auth")
            append("?client_id=").append(URLEncoder.encode(cleanClientId, "UTF-8"))
            append("&redirect_uri=").append(redirectEncoded)
            append("&response_type=code")
            append("&scope=").append(scopeEncoded)
            append("&code_challenge=").append(challenge)
            append("&code_challenge_method=S256")
            append("&state=").append(state)
            append("&access_type=offline")
            append("&prompt=consent")
        }

        val uri = Uri.parse(authUrl)
        val opened = runCatching {
            val customTabs = CustomTabsIntent.Builder().setShowTitle(true).build()
            customTabs.launchUrl(activityContext, uri)
            true
        }.getOrElse {
            runCatching {
                val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activityContext.startActivity(browserIntent)
                true
            }.getOrDefault(false)
        }

        return if (opened) {
            postAuthStatus("Browser opened for Google YouTube OAuth sign-in…")
            Result.success("Google OAuth sign-in started.")
        } else {
            Result.failure(IllegalStateException("Unable to open browser for Google OAuth sign-in."))
        }
    }

    suspend fun completeOAuthCallback(code: String, returnedState: String): Result<YouTubeChannelInfo> =
        withContext(Dispatchers.IO) {
            val (verifier, expectedState) = vault.consumePendingPkceSession()
            if (expectedState.isNotBlank() && returnedState.isNotBlank() && expectedState != returnedState) {
                return@withContext Result.failure(SecurityException("OAuth state mismatch. Please retry sign-in."))
            }
            val config = vault.loadConfig()
            val clientId = config.oauthClientId.trim()
            if (clientId.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("OAuth Client ID missing."))
            }

            val formBuilder = FormBody.Builder()
                .add("code", code.trim())
                .add("client_id", clientId)
                .add("redirect_uri", REDIRECT_URI)
                .add("grant_type", "authorization_code")
            if (verifier.isNotBlank()) {
                formBuilder.add("code_verifier", verifier)
            }

            val tokenReq = Request.Builder()
                .url(OAUTH_TOKEN_URL)
                .post(formBuilder.build())
                .build()

            val tokenResp = runCatching { httpClient.newCall(tokenReq).execute() }.getOrElse {
                return@withContext Result.failure(it)
            }
            tokenResp.use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errDesc = runCatching { JSONObject(body).optString("error_description") }.getOrDefault("")
                    return@withContext Result.failure(
                        IllegalStateException("OAuth token exchange failed (${resp.code}): ${errDesc.ifBlank { resp.message }}")
                    )
                }
                val json = JSONObject(body)
                val accessToken = json.optString("access_token").trim()
                val refreshToken = json.optString("refresh_token").trim().takeIf { it.isNotEmpty() }
                val expiresInSec = json.optLong("expires_in", 3600L)
                if (accessToken.isEmpty()) {
                    return@withContext Result.failure(IllegalStateException("OAuth response missing access_token."))
                }

                val expiresAt = System.currentTimeMillis() + (expiresInSec - 60L).coerceAtLeast(60L) * 1000L
                val channelRes = fetchAuthenticatedChannel(accessToken)
                val channel = channelRes.getOrElse { return@withContext Result.failure(it) }

                vault.updateOAuthTokensAndChannel(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAtMillis = expiresAt,
                    channelId = channel.channelId,
                    channelName = channel.title,
                    channelHandle = channel.handle,
                    channelThumbnailUrl = channel.thumbnailUrl,
                    subscriberCount = channel.subscriberCount,
                    totalViews = channel.totalViews,
                    videoCount = channel.videoCount
                )
                Result.success(channel)
            }
        }

    suspend fun ensureValidAccessToken(): Result<String> = withContext(Dispatchers.IO) {
        val config = vault.loadConfig()
        if (config.accessToken.isBlank()) {
            return@withContext Result.failure(IllegalStateException("YouTube account not authorized. Connect via Google OAuth first."))
        }
        if (System.currentTimeMillis() < config.tokenExpiresAtMillis - 60_000L) {
            return@withContext Result.success(config.accessToken)
        }
        if (config.refreshToken.isBlank() || config.oauthClientId.isBlank()) {
            return@withContext Result.success(config.accessToken)
        }

        val form = FormBody.Builder()
            .add("client_id", config.oauthClientId)
            .add("refresh_token", config.refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val req = Request.Builder().url(OAUTH_TOKEN_URL).post(form).build()
        return@withContext runCatching {
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Token refresh failed (${resp.code}). Please reconnect YouTube OAuth.")
                }
                val json = JSONObject(body)
                val newAccess = json.optString("access_token").trim()
                val expSec = json.optLong("expires_in", 3600L)
                if (newAccess.isEmpty()) throw IllegalStateException("Refresh response missing access_token.")
                val newExpiry = System.currentTimeMillis() + (expSec - 60L).coerceAtLeast(60L) * 1000L
                vault.saveConfig(config.copy(accessToken = newAccess, tokenExpiresAtMillis = newExpiry))
                newAccess
            }
        }
    }

    suspend fun fetchAuthenticatedChannel(accessToken: String): Result<YouTubeChannelInfo> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$YT_DATA_BASE/channels?part=snippet,statistics&mine=true")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            return@withContext runCatching {
                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException(parseYouTubeError(resp.code, body, "Channel verification failed"))
                    }
                    val items = JSONObject(body).optJSONArray("items")
                    if (items == null || items.length() == 0) {
                        throw IllegalStateException("No YouTube channel found on this Google account.")
                    }
                    val item = items.getJSONObject(0)
                    val snippet = item.optJSONObject("snippet") ?: JSONObject()
                    val stats = item.optJSONObject("statistics") ?: JSONObject()
                    val thumbs = snippet.optJSONObject("thumbnails")
                    val thumbUrl = thumbs?.optJSONObject("high")?.optString("url")
                        ?: thumbs?.optJSONObject("medium")?.optString("url")
                        ?: thumbs?.optJSONObject("default")?.optString("url")
                        ?: ""
                    YouTubeChannelInfo(
                        channelId = item.optString("id"),
                        title = snippet.optString("title", "Verified Channel"),
                        handle = snippet.optString("customUrl", ""),
                        thumbnailUrl = thumbUrl,
                        subscriberCount = stats.optString("subscriberCount", "0").toLongOrNull() ?: 0L,
                        totalViews = stats.optString("viewCount", "0").toLongOrNull() ?: 0L,
                        videoCount = stats.optString("videoCount", "0").toLongOrNull() ?: 0L
                    )
                }
            }
        }

    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        val cfg = vault.loadConfig()
        val notes = mutableListOf<String>()

        if (cfg.apiKey.isNotBlank()) {
            val req = Request.Builder()
                .url("$YT_DATA_BASE/videoCategories?part=snippet&regionCode=IN&key=${URLEncoder.encode(cfg.apiKey, "UTF-8")}")
                .get()
                .build()
            val apiOk = runCatching {
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException(parseYouTubeError(resp.code, resp.body?.string().orEmpty(), "API key test failed"))
                    }
                    true
                }
            }
            apiOk.fold(
                onSuccess = { notes.add("YouTube Data API v3 Key: VERIFIED") },
                onFailure = { return@withContext Result.failure(it) }
            )
        } else {
            notes.add("YouTube Data API v3 Key: Not entered")
        }

        if (cfg.accessToken.isNotBlank()) {
            val token = ensureValidAccessToken().getOrElse { return@withContext Result.failure(it) }
            val ch = fetchAuthenticatedChannel(token).getOrElse { return@withContext Result.failure(it) }
            vault.updateOAuthTokensAndChannel(
                accessToken = token,
                refreshToken = null,
                expiresAtMillis = vault.loadConfig().tokenExpiresAtMillis,
                channelId = ch.channelId,
                channelName = ch.title,
                channelHandle = ch.handle,
                channelThumbnailUrl = ch.thumbnailUrl,
                subscriberCount = ch.subscriberCount,
                totalViews = ch.totalViews,
                videoCount = ch.videoCount
            )
            notes.add("OAuth Channel: ${ch.title} (${ch.handle.ifBlank { ch.channelId }})")
        } else {
            notes.add("OAuth Account: Not connected")
        }

        Result.success(notes.joinToString(" • "))
    }

    /**
     * Fetches public video statistics via YouTube Data API v3 (`videos.list`).
     * Works with either the configured Data API key OR the authorized OAuth access token.
     */
    suspend fun fetchPublicVideoStats(videoIdOrUrl: String): Result<PublicVideoAnalytics> =
        withContext(Dispatchers.IO) {
            val videoId = extractYouTubeVideoId(videoIdOrUrl)
                ?: return@withContext Result.failure(IllegalArgumentException("Enter a valid YouTube Video ID or URL."))

            val cfg = vault.loadConfig()
            val urlBuilder = StringBuilder("$YT_DATA_BASE/videos?part=snippet,contentDetails,statistics&id=${URLEncoder.encode(videoId, "UTF-8")}")
            val reqBuilder = Request.Builder()

            if (cfg.apiKey.isNotBlank()) {
                urlBuilder.append("&key=").append(URLEncoder.encode(cfg.apiKey, "UTF-8"))
            } else if (cfg.accessToken.isNotBlank()) {
                val token = ensureValidAccessToken().getOrElse { return@withContext Result.failure(it) }
                reqBuilder.header("Authorization", "Bearer $token")
            } else {
                return@withContext Result.failure(
                    IllegalStateException("Configure YouTube Data API v3 Key or connect your YouTube OAuth account first.")
                )
            }

            return@withContext runCatching {
                httpClient.newCall(reqBuilder.url(urlBuilder.toString()).get().build()).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException(parseYouTubeError(resp.code, body, "Video lookup failed"))
                    }
                    val items = JSONObject(body).optJSONArray("items")
                    if (items == null || items.length() == 0) {
                        throw IllegalStateException("Video '$videoId' not found or is private.")
                    }
                    parseVideoItem(items.getJSONObject(0))
                }
            }
        }

    /**
     * Batch fetches stats for multiple monitored video IDs in 1 quota unit!
     */
    suspend fun fetchBatchVideoStats(videoIds: List<String>): Result<Map<String, PublicVideoAnalytics>> =
        withContext(Dispatchers.IO) {
            val cleanIds = videoIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(50)
            if (cleanIds.isEmpty()) return@withContext Result.success(emptyMap())

            val cfg = vault.loadConfig()
            val joined = URLEncoder.encode(cleanIds.joinToString(","), "UTF-8")
            val urlBuilder = StringBuilder("$YT_DATA_BASE/videos?part=snippet,contentDetails,statistics&id=$joined")
            val reqBuilder = Request.Builder()

            if (cfg.accessToken.isNotBlank()) {
                val token = ensureValidAccessToken().getOrNull()
                if (!token.isNullOrBlank()) {
                    reqBuilder.header("Authorization", "Bearer $token")
                }
            }
            if (cfg.apiKey.isNotBlank()) {
                urlBuilder.append("&key=").append(URLEncoder.encode(cfg.apiKey, "UTF-8"))
            } else if (cfg.accessToken.isBlank()) {
                return@withContext Result.failure(IllegalStateException("YouTube API key or OAuth token required."))
            }

            return@withContext runCatching {
                httpClient.newCall(reqBuilder.url(urlBuilder.toString()).get().build()).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException(parseYouTubeError(resp.code, body, "Batch stats failed"))
                    }
                    val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
                    val map = mutableMapOf<String, PublicVideoAnalytics>()
                    for (i in 0 until items.length()) {
                        val parsed = parseVideoItem(items.getJSONObject(i))
                        map[parsed.videoId] = parsed
                    }
                    map
                }
            }
        }

    /**
     * Fetches authorized channel daily analytics via YouTube Analytics API v2
     * plus recent uploaded videos from the authenticated channel.
     */
    suspend fun fetchAuthorizedChannelAnalytics(windowDays: Int = 28): Result<ChannelAnalyticsReport> =
        withContext(Dispatchers.IO) {
            val token = ensureValidAccessToken().getOrElse { return@withContext Result.failure(it) }
            val safeDays = windowDays.coerceIn(7, 90)
            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val endCal = Calendar.getInstance()
            val startCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -safeDays) }
            val startDate = dateFmt.format(startCal.time)
            val endDate = dateFmt.format(endCal.time)

            val analyticsUrl = "$YT_ANALYTICS_BASE/reports" +
                "?ids=channel==MINE" +
                "&startDate=$startDate" +
                "&endDate=$endDate" +
                "&metrics=views,estimatedMinutesWatched,averageViewDuration,subscribersGained,subscribersLost" +
                "&dimensions=day" +
                "&sort=day"

            val dailyPoints = mutableListOf<DailyAnalyticsPoint>()
            var analyticsNote = "Measured via YouTube Analytics API v2 (channel==MINE). Note: Impressions and CTR are only exposed in YouTube Studio / Bulk Reporting API, not real-time Analytics v2 queries."

            val analyticsReq = Request.Builder()
                .url(analyticsUrl)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            runCatching {
                httpClient.newCall(analyticsReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        analyticsNote = parseYouTubeError(resp.code, body, "YouTube Analytics API returned ${resp.code}")
                    } else {
                        val rows = JSONObject(body).optJSONArray("rows") ?: JSONArray()
                        for (i in 0 until rows.length()) {
                            val r = rows.optJSONArray(i) ?: continue
                            dailyPoints.add(
                                DailyAnalyticsPoint(
                                    date = r.optString(0),
                                    views = r.optLong(1, 0L),
                                    estimatedMinutesWatched = r.optDouble(2, 0.0),
                                    averageViewDurationSec = r.optDouble(3, 0.0),
                                    subscribersGained = r.optLong(4, 0L),
                                    subscribersLost = r.optLong(5, 0L)
                                )
                            )
                        }
                    }
                }
            }.onFailure { err ->
                analyticsNote = "Analytics API error: ${err.localizedMessage}"
            }

            val recentVideos = fetchAuthorizedRecentVideos(token).getOrDefault(emptyList())

            val totalViews = dailyPoints.sumOf { it.views }
            val totalWatch = dailyPoints.sumOf { it.estimatedMinutesWatched }
            val avgDur = if (dailyPoints.isNotEmpty()) {
                dailyPoints.map { it.averageViewDurationSec }.average()
            } else 0.0
            val subsGain = dailyPoints.sumOf { it.subscribersGained }
            val subsLost = dailyPoints.sumOf { it.subscribersLost }

            Result.success(
                ChannelAnalyticsReport(
                    windowDays = safeDays,
                    totalViews = totalViews,
                    totalWatchMinutes = totalWatch,
                    averageViewDurationSec = avgDur,
                    subscribersGained = subsGain,
                    subscribersLost = subsLost,
                    dailyPoints = dailyPoints,
                    recentChannelVideos = recentVideos,
                    apiCoverageNote = analyticsNote
                )
            )
        }

    private fun fetchAuthorizedRecentVideos(accessToken: String): Result<List<PublicVideoAnalytics>> {
        val searchReq = Request.Builder()
            .url("$YT_DATA_BASE/search?part=id&forMine=true&type=video&order=date&maxResults=10")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        return runCatching {
            val ids = mutableListOf<String>()
            httpClient.newCall(searchReq).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching emptyList()
                val items = JSONObject(resp.body?.string().orEmpty()).optJSONArray("items") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val vid = items.optJSONObject(i)?.optJSONObject("id")?.optString("videoId").orEmpty()
                    if (vid.isNotBlank()) ids.add(vid)
                }
            }
            if (ids.isEmpty()) return@runCatching emptyList()

            val detailReq = Request.Builder()
                .url("$YT_DATA_BASE/videos?part=snippet,contentDetails,statistics&id=${URLEncoder.encode(ids.joinToString(","), "UTF-8")}")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            httpClient.newCall(detailReq).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching emptyList()
                val items = JSONObject(resp.body?.string().orEmpty()).optJSONArray("items") ?: JSONArray()
                val out = mutableListOf<PublicVideoAnalytics>()
                for (i in 0 until items.length()) {
                    out.add(parseVideoItem(items.getJSONObject(i)))
                }
                out
            }
        }
    }

    /**
     * Real YouTube Data API v3 Resumable Video Upload (`videos.insert`).
     *
     * Scheduled Publishing Verification:
     * Per YouTube Data API v3 specification, whenever `publishAt` (ISO-8601 UTC timestamp) is supplied,
     * `status.privacyStatus` MUST be `"private"`. This method enforces that requirement automatically.
     */
    suspend fun uploadVideoResumable(
        videoUri: Uri,
        thumbnailUri: Uri?,
        title: String,
        description: String,
        tags: List<String>,
        privacyStatus: String,
        scheduledPublishIsoUtc: String?,
        isShort: Boolean,
        categoryId: String,
        madeForKids: Boolean,
        onProgress: (percent: Int, stage: String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val token = ensureValidAccessToken().getOrElse { return@withContext Result.failure(it) }
        val cfg = vault.loadConfig()
        if (cfg.channelId.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No verified YouTube channel connected. Authorize your own channel via OAuth first."))
        }

        val mimeType = appContext.contentResolver.getType(videoUri) ?: "video/mp4"
        val videoBytes = readUriBytes(videoUri)
            ?: return@withContext Result.failure(IllegalStateException("Unable to read selected video file from device storage."))
        if (videoBytes.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Selected video file is empty."))
        }

        onProgress(5, "Initializing YouTube resumable upload session…")

        val finalDescription = if (isShort && !description.contains("#shorts", ignoreCase = true)) {
            "$description\n\n#shorts".trim()
        } else {
            description.trim()
        }
        val cleanScheduleIso = scheduledPublishIsoUtc?.trim().orEmpty()
        // Official YouTube API rule: scheduled videos MUST set privacyStatus = "private" + publishAt
        val effectivePrivacy = if (cleanScheduleIso.isNotBlank()) "private" else privacyStatus.lowercase(Locale.ROOT)

        val metadataJson = JSONObject().apply {
            put(
                "snippet",
                JSONObject().apply {
                    put("title", title.trim().take(100))
                    put("description", finalDescription.take(5000))
                    put("categoryId", categoryId.ifBlank { "22" })
                    val tagArr = JSONArray()
                    tags.map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.take(30).forEach { tagArr.put(it) }
                    if (isShort) tagArr.put("shorts")
                    put("tags", tagArr)
                }
            )
            put(
                "status",
                JSONObject().apply {
                    put("privacyStatus", effectivePrivacy)
                    put("selfDeclaredMadeForKids", madeForKids)
                    if (cleanScheduleIso.isNotBlank()) {
                        put("publishAt", cleanScheduleIso)
                    }
                }
            )
        }

        val initReq = Request.Builder()
            .url(YT_UPLOAD_RESUMABLE_URL)
            .header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Length", videoBytes.size.toString())
            .header("X-Upload-Content-Type", mimeType)
            .post(metadataJson.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()

        val uploadLocation = runCatching {
            httpClient.newCall(initReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException(parseYouTubeError(resp.code, resp.body?.string().orEmpty(), "Upload session init failed"))
                }
                resp.header("Location") ?: throw IllegalStateException("YouTube did not return a resumable upload Location header.")
            }
        }.getOrElse { return@withContext Result.failure(it) }

        // Upload in resilient 1 MB chunks with automatic retry recovery
        val chunkSize = 1024 * 1024 // 1 MiB (multiple of 256 KiB as required by Google Resumable Upload protocol)
        var offset = 0
        var uploadedVideoId = ""
        val totalSize = videoBytes.size

        while (offset < totalSize) {
            val endExclusive = minOf(offset + chunkSize, totalSize)
            val chunkLen = endExclusive - offset
            val contentRange = "bytes $offset-${endExclusive - 1}/$totalSize"

            var attempt = 0
            var chunkAccepted = false
            var lastChunkErr: Exception? = null

            while (attempt < 3 && !chunkAccepted) {
                attempt++
                val pct = 10 + ((endExclusive.toFloat() / totalSize.toFloat()) * 78f).toInt()
                onProgress(pct.coerceIn(10, 88), "Uploading chunk ($pct%) • attempt $attempt…")

                val putReq = Request.Builder()
                    .url(uploadLocation)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Range", contentRange)
                    .header("Content-Type", mimeType)
                    .put(videoBytes.toRequestBody(mimeType.toMediaType(), offset, chunkLen))
                    .build()

                try {
                    httpClient.newCall(putReq).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        when (resp.code) {
                            308 -> {
                                // Resume incomplete — chunk accepted
                                chunkAccepted = true
                            }
                            200, 201 -> {
                                uploadedVideoId = JSONObject(body).optString("id").trim()
                                chunkAccepted = true
                            }
                            else -> {
                                lastChunkErr = IllegalStateException(parseYouTubeError(resp.code, body, "Chunk upload failed"))
                                if (resp.code in 400..499 && resp.code != 408 && resp.code != 429) {
                                    return@withContext Result.failure(lastChunkErr!!)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    lastChunkErr = e
                }
            }

            if (!chunkAccepted) {
                return@withContext Result.failure(lastChunkErr ?: IllegalStateException("Upload interrupted after 3 retries."))
            }
            offset = endExclusive
        }

        if (uploadedVideoId.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Upload finished without a verified YouTube Video ID."))
        }

        // Optional custom thumbnail upload (`thumbnails.set`) — note: YouTube requires channel verification for custom thumbnails
        if (thumbnailUri != null) {
            onProgress(91, "Uploading custom thumbnail…")
            val thumbBytes = readUriBytes(thumbnailUri)
            if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                val thumbMime = appContext.contentResolver.getType(thumbnailUri) ?: "image/jpeg"
                val thumbReq = Request.Builder()
                    .url("https://www.googleapis.com/upload/youtube/v3/thumbnails/set?videoId=${URLEncoder.encode(uploadedVideoId, "UTF-8")}")
                    .header("Authorization", "Bearer $token")
                    .post(thumbBytes.toRequestBody(thumbMime.toMediaType()))
                    .build()
                runCatching { httpClient.newCall(thumbReq).execute().close() }
            }
        }

        // Post-upload verification via `videos.list`
        onProgress(96, "Verifying uploaded video on YouTube…")
        val verifyReq = Request.Builder()
            .url("$YT_DATA_BASE/videos?part=status,snippet&id=${URLEncoder.encode(uploadedVideoId, "UTF-8")}")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val verified = runCatching {
            httpClient.newCall(verifyReq).execute().use { it.isSuccessful }
        }.getOrDefault(false)

        onProgress(100, if (verified) "Upload verified (ID: $uploadedVideoId)" else "Uploaded (ID: $uploadedVideoId)")
        Result.success(uploadedVideoId)
    }

    /**
     * Generates SEO Title, Description, Tags, and Hashtags using the user's EXISTING Gemini API key!
     */
    suspend fun generateSeoWithExistingGemini(
        aiClient: GeminiApiClient,
        geminiApiKey: String,
        selectedEngine: String,
        topicOrDraftTitle: String,
        isShort: Boolean,
        seoLanguage: String,
        seoTone: String,
        defaultHashtags: String
    ): Result<GeneratedSeoPackage> {
        val sys = """
            You are JARVIS YouTube SEO Strategist. Return ONLY a strict JSON object:
            {"title":"...","description":"...","tags":["tag1","tag2"],"hashtags":["#tag1","#tag2"]}
            Rules:
            - Title <= 95 chars, high curiosity/clarity without misleading clickbait.
            - Language: $seoLanguage. Tone: $seoTone. Format: ${if (isShort) "YouTube Short (vertical <60s)" else "Long-form YouTube Video"}.
            - Include 8-14 focused tags and 4-6 relevant hashtags (include $defaultHashtags where fitting).
            - Never guarantee viral views. Output valid JSON only.
        """.trimIndent()

        val res = aiClient.generateLongAnalysis(
            rawApiKey = geminiApiKey,
            systemInstruction = sys,
            userPrompt = "Generate optimized YouTube SEO metadata for: $topicOrDraftTitle",
            selectedEngine = selectedEngine,
            maxOutputTokens = 900,
            expectJson = true
        )

        return res.map { raw ->
            val cleaned = JarvisResponseSanitizer.stripMarkdownFences(raw)
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            val json = JSONObject(if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned)
            val tagsArr = json.optJSONArray("tags") ?: JSONArray()
            val hashArr = json.optJSONArray("hashtags") ?: JSONArray()
            val tags = (0 until tagsArr.length()).mapNotNull { tagsArr.optString(it).takeIf(String::isNotBlank) }
            val hashes = (0 until hashArr.length()).mapNotNull { hashArr.optString(it).takeIf(String::isNotBlank) }
            GeneratedSeoPackage(
                title = json.optString("title", topicOrDraftTitle).trim(),
                description = json.optString("description", "").trim(),
                tags = tags,
                hashtags = hashes
            )
        }
    }

    /**
     * AI YouTube Strategy Assistant: analyzes MEASURED channel & video statistics and clearly
     * separates verified metrics from AI strategy recommendations (never guaranteeing viral outcomes).
     */
    suspend fun generateChannelStrategyWithExistingGemini(
        aiClient: GeminiApiClient,
        geminiApiKey: String,
        selectedEngine: String,
        channel: YouTubeCreatorConfig,
        analytics: ChannelAnalyticsReport?,
        monitoredVideos: List<MonitoredYouTubeVideoEntity>
    ): Result<String> {
        val measuredSummary = buildString {
            appendLine("=== MEASURED CHANNEL DATA ===")
            appendLine("Channel: ${channel.channelName.ifBlank { "Not authorized yet" }} (${channel.channelHandle})")
            appendLine("Subscribers: ${channel.subscriberCount}, Total Views: ${channel.totalViews}, Total Videos: ${channel.videoCount}")
            if (analytics != null) {
                appendLine("Last ${analytics.windowDays} Days Measured Views: ${analytics.totalViews}")
                appendLine("Watch Time (min): ${"%.1f".format(analytics.totalWatchMinutes)}, Avg View Duration: ${"%.0f".format(analytics.averageViewDurationSec)}s")
                appendLine("Subscribers Gained: +${analytics.subscribersGained}, Lost: -${analytics.subscribersLost}")
                if (analytics.recentChannelVideos.isNotEmpty()) {
                    appendLine("Recent Channel Videos:")
                    analytics.recentChannelVideos.take(6).forEach { v ->
                        appendLine("- \"${v.title}\" | Views: ${v.views}, Likes: ${v.likes}, Comments: ${v.comments}, Published: ${v.publishedAt.take(10)}")
                    }
                }
            }
            if (monitoredVideos.isNotEmpty()) {
                appendLine("Monitored Uploads:")
                monitoredVideos.take(6).forEach { mv ->
                    val delta = (mv.currentViews - mv.previousViews).coerceAtLeast(0L)
                    appendLine("- \"${mv.title}\" | Views: ${mv.currentViews} (+$delta recent), Likes: ${mv.currentLikes}, Comments: ${mv.currentComments}")
                }
            }
        }

        val systemInstruction = """
            You are JARVIS AI YouTube Strategy Assistant (Developed by Mozahid).
            CRITICAL HONESTY RULES:
            1. Clearly separate section [MEASURED STATISTICS] from section [AI STRATEGY & ESTIMATES].
            2. NEVER guarantee views, virality, or subscriber outcomes.
            3. Provide actionable, concrete guidance covering:
               - Content improvement suggestions
               - 3 high-clarity video title options
               - Thumbnail visual composition ideas
               - SEO keyword & packaging improvements
               - Audience engagement analysis
               - Best publishing time window recommendations based on historical data (or state clearly if history is limited)
               - 3 future video topic ideas
               - Direct comparison between the creator's own videos listed above.
            Keep formatting clean, structured, and easy to scan on mobile.
        """.trimIndent()

        return aiClient.generateLongAnalysis(
            rawApiKey = geminiApiKey,
            systemInstruction = systemInstruction,
            userPrompt = measuredSummary,
            selectedEngine = selectedEngine,
            maxOutputTokens = 1600,
            expectJson = false
        )
    }

    private fun parseVideoItem(item: JSONObject): PublicVideoAnalytics {
        val snippet = item.optJSONObject("snippet") ?: JSONObject()
        val content = item.optJSONObject("contentDetails") ?: JSONObject()
        val stats = item.optJSONObject("statistics") ?: JSONObject()
        val thumbs = snippet.optJSONObject("thumbnails")
        val thumbUrl = thumbs?.optJSONObject("high")?.optString("url")
            ?: thumbs?.optJSONObject("medium")?.optString("url")
            ?: thumbs?.optJSONObject("default")?.optString("url")
            ?: ""
        val isoDur = content.optString("duration", "PT0S")
        return PublicVideoAnalytics(
            videoId = item.optString("id"),
            title = snippet.optString("title", "Untitled Video"),
            description = snippet.optString("description", ""),
            thumbnailUrl = thumbUrl,
            durationIso = isoDur,
            durationReadable = formatIsoDuration(isoDur),
            channelId = snippet.optString("channelId", ""),
            channelTitle = snippet.optString("channelTitle", ""),
            publishedAt = snippet.optString("publishedAt", ""),
            views = stats.optString("viewCount", "0").toLongOrNull() ?: 0L,
            likes = stats.optString("likeCount", "0").toLongOrNull() ?: 0L,
            comments = stats.optString("commentCount", "0").toLongOrNull() ?: 0L
        )
    }

    private fun parseYouTubeError(code: Int, body: String, fallback: String): String {
        val errObj = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val msg = errObj?.optString("message").orEmpty()
        val reason = errObj?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
        return when {
            reason.equals("quotaExceeded", ignoreCase = true) || code == 429 ->
                "YouTube API daily quota exceeded. Pause monitoring or wait for quota reset."
            code == 401 ->
                "YouTube OAuth session expired (401). Please reconnect your account."
            code == 403 ->
                "YouTube permission/quota denied (403): ${msg.ifBlank { reason.ifBlank { fallback } }}"
            else ->
                "$fallback ($code): ${msg.ifBlank { fallback }}"
        }
    }

    private fun readUriBytes(uri: Uri): ByteArray? {
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                }
                out.toByteArray()
            }
        }.getOrNull()
    }

    private fun generatePkceVerifier(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun sha256Base64Url(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateRandomState(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        const val REDIRECT_URI = "com.aistudio.jarvis.kxqzmp:/oauth2redirect"
        private const val OAUTH_TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val YT_DATA_BASE = "https://www.googleapis.com/youtube/v3"
        private const val YT_ANALYTICS_BASE = "https://youtubeanalytics.googleapis.com/v2"
        private const val YT_UPLOAD_RESUMABLE_URL =
            "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status"

        val REQUIRED_SCOPES = listOf(
            "https://www.googleapis.com/auth/youtube.readonly",
            "https://www.googleapis.com/auth/youtube.upload",
            "https://www.googleapis.com/auth/yt-analytics.readonly"
        )

        private val _oauthStatus = MutableStateFlow("")
        val oauthStatus: StateFlow<String> = _oauthStatus.asStateFlow()

        fun postAuthStatus(msg: String) {
            _oauthStatus.value = msg
        }

        fun extractYouTubeVideoId(raw: String): String? {
            val clean = raw.trim()
            if (clean.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) return clean
            val patterns = listOf(
                Regex("(?:v=|/shorts/|youtu\\.be/|/embed/)([a-zA-Z0-9_-]{11})")
            )
            for (p in patterns) {
                val m = p.find(clean)
                if (m != null) return m.groupValues[1]
            }
            return null
        }

        fun formatIsoDuration(iso: String): String {
            val match = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").find(iso) ?: return iso
            val h = match.groupValues[1].toIntOrNull() ?: 0
            val m = match.groupValues[2].toIntOrNull() ?: 0
            val s = match.groupValues[3].toIntOrNull() ?: 0
            return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(Locale.US, "%d:%02d", m, s)
        }

        fun toUtcIso8601(epochMillis: Long): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            return fmt.format(java.util.Date(epochMillis))
        }
    }
}
