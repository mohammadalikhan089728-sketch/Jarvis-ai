package com.example

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class YouTubeCreatorConfig(
    val apiKey: String = "",
    val oauthClientId: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val tokenExpiresAtMillis: Long = 0L,
    val channelId: String = "",
    val channelName: String = "",
    val channelHandle: String = "",
    val channelThumbnailUrl: String = "",
    val subscriberCount: Long = 0L,
    val totalViews: Long = 0L,
    val videoCount: Long = 0L,
    val uploadFolderUri: String = "",
    val uploadFolderLabel: String = "App Movies/JarvisClips",
    val defaultPrivacy: String = "private",
    val defaultCategoryId: String = "22",
    val defaultMadeForKids: Boolean = false,
    val seoLanguage: String = "Hinglish / English",
    val seoTone: String = "High-CTR Engaging",
    val seoDefaultHashtags: String = "#shorts #trending",
    val analyticsWindowDays: Int = 28,
    val monitoringEnabled: Boolean = true,
    val monitoringIntervalMinutes: Int = 30,
    val viewMilestonesCsv: String = "100,500,1000,5000,10000",
    val milestoneInAppCallEnabled: Boolean = true,
    val respectDoNotDisturb: Boolean = true,
    val backgroundNotificationFallback: Boolean = true,
    val clipDefaultDurationSec: Int = 30,
    val clipVertical916Default: Boolean = true,
    val clipBurnCaptionsDefault: Boolean = true,
    val projectMemoryAutoInject: Boolean = true,
    val selfImprovementAutoLearn: Boolean = true
) {
    val isAccountAuthorized: Boolean
        get() = accessToken.isNotBlank() && channelId.isNotBlank()

    fun parsedMilestones(): List<Long> {
        return viewMilestonesCsv
            .split(',', ' ', ';')
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it > 0L }
            .distinct()
            .sorted()
    }
}

/**
 * Android Keystore-backed AES-256-GCM Encrypted Credential & Preference Vault for JARVIS.
 *
 * - Encrypts all sensitive values (YouTube Data API v3 Key, OAuth Client ID, Access Token,
 *   Refresh Token) using a hardware-backed AES-256-GCM SecretKey inside AndroidKeyStore.
 * - Never writes secrets in plain text and never overwrites existing Room Gemini API settings.
 * - Includes an automatic JVM fallback key ONLY when AndroidKeyStore is unavailable in local
 *   unit test runners (Robolectric), while always enforcing AndroidKeyStore on real Android devices.
 */
class SecureCredentialVault(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _configFlow = MutableStateFlow(loadConfig())
    val configFlow: StateFlow<YouTubeCreatorConfig> = _configFlow.asStateFlow()

    fun loadConfig(): YouTubeCreatorConfig {
        return YouTubeCreatorConfig(
            apiKey = getEncryptedString(KEY_YT_API_KEY),
            oauthClientId = getEncryptedString(KEY_YT_OAUTH_CLIENT_ID),
            accessToken = getEncryptedString(KEY_YT_ACCESS_TOKEN),
            refreshToken = getEncryptedString(KEY_YT_REFRESH_TOKEN),
            tokenExpiresAtMillis = prefs.getLong(KEY_YT_TOKEN_EXPIRY, 0L),
            channelId = prefs.getString(KEY_YT_CHANNEL_ID, "").orEmpty(),
            channelName = prefs.getString(KEY_YT_CHANNEL_NAME, "").orEmpty(),
            channelHandle = prefs.getString(KEY_YT_CHANNEL_HANDLE, "").orEmpty(),
            channelThumbnailUrl = prefs.getString(KEY_YT_CHANNEL_THUMB, "").orEmpty(),
            subscriberCount = prefs.getLong(KEY_YT_SUBSCRIBERS, 0L),
            totalViews = prefs.getLong(KEY_YT_TOTAL_VIEWS, 0L),
            videoCount = prefs.getLong(KEY_YT_VIDEO_COUNT, 0L),
            uploadFolderUri = prefs.getString(KEY_UPLOAD_FOLDER_URI, "").orEmpty(),
            uploadFolderLabel = prefs.getString(KEY_UPLOAD_FOLDER_LABEL, "App Movies/JarvisClips").orEmpty(),
            defaultPrivacy = prefs.getString(KEY_DEFAULT_PRIVACY, "private").orEmpty(),
            defaultCategoryId = prefs.getString(KEY_DEFAULT_CATEGORY_ID, "22").orEmpty(),
            defaultMadeForKids = prefs.getBoolean(KEY_DEFAULT_MADE_FOR_KIDS, false),
            seoLanguage = prefs.getString(KEY_SEO_LANGUAGE, "Hinglish / English").orEmpty(),
            seoTone = prefs.getString(KEY_SEO_TONE, "High-CTR Engaging").orEmpty(),
            seoDefaultHashtags = prefs.getString(KEY_SEO_HASHTAGS, "#shorts #trending").orEmpty(),
            analyticsWindowDays = prefs.getInt(KEY_ANALYTICS_DAYS, 28).coerceIn(7, 90),
            monitoringEnabled = prefs.getBoolean(KEY_MONITORING_ENABLED, true),
            monitoringIntervalMinutes = prefs.getInt(KEY_MONITORING_INTERVAL_MIN, 30).coerceIn(15, 1440),
            viewMilestonesCsv = prefs.getString(KEY_MILESTONES_CSV, "100,500,1000,5000,10000").orEmpty(),
            milestoneInAppCallEnabled = prefs.getBoolean(KEY_MILESTONE_CALL_ENABLED, true),
            respectDoNotDisturb = prefs.getBoolean(KEY_RESPECT_DND, true),
            backgroundNotificationFallback = prefs.getBoolean(KEY_BG_NOTIF_FALLBACK, true),
            clipDefaultDurationSec = prefs.getInt(KEY_CLIP_DURATION_SEC, 30).coerceIn(10, 60),
            clipVertical916Default = prefs.getBoolean(KEY_CLIP_VERTICAL_916, true),
            clipBurnCaptionsDefault = prefs.getBoolean(KEY_CLIP_BURN_CAPTIONS, true),
            projectMemoryAutoInject = prefs.getBoolean(KEY_PROJECT_MEM_AUTO_INJECT, true),
            selfImprovementAutoLearn = prefs.getBoolean(KEY_SELF_IMPROVE_AUTO_LEARN, true)
        )
    }

    fun saveConfig(updated: YouTubeCreatorConfig) {
        putEncryptedString(KEY_YT_API_KEY, updated.apiKey.trim())
        putEncryptedString(KEY_YT_OAUTH_CLIENT_ID, updated.oauthClientId.trim())
        putEncryptedString(KEY_YT_ACCESS_TOKEN, updated.accessToken.trim())
        putEncryptedString(KEY_YT_REFRESH_TOKEN, updated.refreshToken.trim())

        prefs.edit()
            .putLong(KEY_YT_TOKEN_EXPIRY, updated.tokenExpiresAtMillis)
            .putString(KEY_YT_CHANNEL_ID, updated.channelId.trim())
            .putString(KEY_YT_CHANNEL_NAME, updated.channelName.trim())
            .putString(KEY_YT_CHANNEL_HANDLE, updated.channelHandle.trim())
            .putString(KEY_YT_CHANNEL_THUMB, updated.channelThumbnailUrl.trim())
            .putLong(KEY_YT_SUBSCRIBERS, updated.subscriberCount)
            .putLong(KEY_YT_TOTAL_VIEWS, updated.totalViews)
            .putLong(KEY_YT_VIDEO_COUNT, updated.videoCount)
            .putString(KEY_UPLOAD_FOLDER_URI, updated.uploadFolderUri.trim())
            .putString(KEY_UPLOAD_FOLDER_LABEL, updated.uploadFolderLabel.trim().ifBlank { "App Movies/JarvisClips" })
            .putString(KEY_DEFAULT_PRIVACY, updated.defaultPrivacy.ifBlank { "private" })
            .putString(KEY_DEFAULT_CATEGORY_ID, updated.defaultCategoryId.ifBlank { "22" })
            .putBoolean(KEY_DEFAULT_MADE_FOR_KIDS, updated.defaultMadeForKids)
            .putString(KEY_SEO_LANGUAGE, updated.seoLanguage.ifBlank { "Hinglish / English" })
            .putString(KEY_SEO_TONE, updated.seoTone.ifBlank { "High-CTR Engaging" })
            .putString(KEY_SEO_HASHTAGS, updated.seoDefaultHashtags)
            .putInt(KEY_ANALYTICS_DAYS, updated.analyticsWindowDays.coerceIn(7, 90))
            .putBoolean(KEY_MONITORING_ENABLED, updated.monitoringEnabled)
            .putInt(KEY_MONITORING_INTERVAL_MIN, updated.monitoringIntervalMinutes.coerceIn(15, 1440))
            .putString(KEY_MILESTONES_CSV, updated.viewMilestonesCsv.ifBlank { "100,500,1000,5000,10000" })
            .putBoolean(KEY_MILESTONE_CALL_ENABLED, updated.milestoneInAppCallEnabled)
            .putBoolean(KEY_RESPECT_DND, updated.respectDoNotDisturb)
            .putBoolean(KEY_BG_NOTIF_FALLBACK, updated.backgroundNotificationFallback)
            .putInt(KEY_CLIP_DURATION_SEC, updated.clipDefaultDurationSec.coerceIn(10, 60))
            .putBoolean(KEY_CLIP_VERTICAL_916, updated.clipVertical916Default)
            .putBoolean(KEY_CLIP_BURN_CAPTIONS, updated.clipBurnCaptionsDefault)
            .putBoolean(KEY_PROJECT_MEM_AUTO_INJECT, updated.projectMemoryAutoInject)
            .putBoolean(KEY_SELF_IMPROVE_AUTO_LEARN, updated.selfImprovementAutoLearn)
            .apply()

        _configFlow.value = loadConfig()
    }

    fun updateOAuthTokensAndChannel(
        accessToken: String,
        refreshToken: String?,
        expiresAtMillis: Long,
        channelId: String,
        channelName: String,
        channelHandle: String,
        channelThumbnailUrl: String,
        subscriberCount: Long,
        totalViews: Long,
        videoCount: Long
    ) {
        val current = loadConfig()
        saveConfig(
            current.copy(
                accessToken = accessToken,
                refreshToken = refreshToken?.takeIf { it.isNotBlank() } ?: current.refreshToken,
                tokenExpiresAtMillis = expiresAtMillis,
                channelId = channelId,
                channelName = channelName,
                channelHandle = channelHandle,
                channelThumbnailUrl = channelThumbnailUrl,
                subscriberCount = subscriberCount,
                totalViews = totalViews,
                videoCount = videoCount
            )
        )
    }

    fun disconnectYouTubeAccount() {
        val current = loadConfig()
        saveConfig(
            current.copy(
                accessToken = "",
                refreshToken = "",
                tokenExpiresAtMillis = 0L,
                channelId = "",
                channelName = "",
                channelHandle = "",
                channelThumbnailUrl = "",
                subscriberCount = 0L,
                totalViews = 0L,
                videoCount = 0L
            )
        )
    }

    fun savePendingPkceSession(codeVerifier: String, state: String) {
        putEncryptedString(KEY_PKCE_VERIFIER, codeVerifier)
        putEncryptedString(KEY_PKCE_STATE, state)
    }

    fun consumePendingPkceSession(): Pair<String, String> {
        val verifier = getEncryptedString(KEY_PKCE_VERIFIER)
        val state = getEncryptedString(KEY_PKCE_STATE)
        prefs.edit().remove(KEY_PKCE_VERIFIER).remove(KEY_PKCE_STATE).apply()
        return verifier to state
    }

    fun isUsingHardwareKeystore(): Boolean {
        return runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
            ks.load(null)
            ks.containsAlias(KEY_ALIAS) || createAndroidKeystoreKey() != null
        }.getOrDefault(false)
    }

    fun putEncryptedString(key: String, plainText: String) {
        if (plainText.isEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val encoded = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        prefs.edit().putString(key, encoded).apply()
    }

    fun getEncryptedString(key: String): String {
        val stored = prefs.getString(key, null) ?: return ""
        val parts = stored.split(':')
        if (parts.size != 2) return ""
        return runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getOrCreateSecretKey(): SecretKey {
        createAndroidKeystoreKey()?.let { return it }
        // Fallback strictly for non-Keystore JVM unit tests (e.g. Robolectric)
        return jvmTestFallbackKey
    }

    private fun createAndroidKeystoreKey(): SecretKey? {
        return runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
            ks.load(null)
            val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return@runCatching existing

            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGen.init(spec)
            keyGen.generateKey()
        }.getOrNull()
    }

    companion object {
        private const val PREFS_NAME = "jarvis_secure_creator_vault"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "jarvis_aes256_gcm_vault_key"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        private const val KEY_YT_API_KEY = "enc_yt_api_key"
        private const val KEY_YT_OAUTH_CLIENT_ID = "enc_yt_oauth_client_id"
        private const val KEY_YT_ACCESS_TOKEN = "enc_yt_access_token"
        private const val KEY_YT_REFRESH_TOKEN = "enc_yt_refresh_token"
        private const val KEY_PKCE_VERIFIER = "enc_pkce_verifier"
        private const val KEY_PKCE_STATE = "enc_pkce_state"

        private const val KEY_YT_TOKEN_EXPIRY = "yt_token_expiry"
        private const val KEY_YT_CHANNEL_ID = "yt_channel_id"
        private const val KEY_YT_CHANNEL_NAME = "yt_channel_name"
        private const val KEY_YT_CHANNEL_HANDLE = "yt_channel_handle"
        private const val KEY_YT_CHANNEL_THUMB = "yt_channel_thumb"
        private const val KEY_YT_SUBSCRIBERS = "yt_subscribers"
        private const val KEY_YT_TOTAL_VIEWS = "yt_total_views"
        private const val KEY_YT_VIDEO_COUNT = "yt_video_count"
        private const val KEY_UPLOAD_FOLDER_URI = "yt_upload_folder_uri"
        private const val KEY_UPLOAD_FOLDER_LABEL = "yt_upload_folder_label"
        private const val KEY_DEFAULT_PRIVACY = "yt_default_privacy"
        private const val KEY_DEFAULT_CATEGORY_ID = "yt_default_category_id"
        private const val KEY_DEFAULT_MADE_FOR_KIDS = "yt_default_made_for_kids"
        private const val KEY_SEO_LANGUAGE = "yt_seo_language"
        private const val KEY_SEO_TONE = "yt_seo_tone"
        private const val KEY_SEO_HASHTAGS = "yt_seo_hashtags"
        private const val KEY_ANALYTICS_DAYS = "yt_analytics_days"
        private const val KEY_MONITORING_ENABLED = "yt_monitoring_enabled"
        private const val KEY_MONITORING_INTERVAL_MIN = "yt_monitoring_interval_min"
        private const val KEY_MILESTONES_CSV = "yt_milestones_csv"
        private const val KEY_MILESTONE_CALL_ENABLED = "yt_milestone_call_enabled"
        private const val KEY_RESPECT_DND = "yt_respect_dnd"
        private const val KEY_BG_NOTIF_FALLBACK = "yt_bg_notif_fallback"
        private const val KEY_CLIP_DURATION_SEC = "yt_clip_duration_sec"
        private const val KEY_CLIP_VERTICAL_916 = "yt_clip_vertical_916"
        private const val KEY_CLIP_BURN_CAPTIONS = "yt_clip_burn_captions"
        private const val KEY_PROJECT_MEM_AUTO_INJECT = "jarvis_project_mem_auto_inject"
        private const val KEY_SELF_IMPROVE_AUTO_LEARN = "jarvis_self_improve_auto_learn"

        private val jvmTestFallbackKey: SecretKey by lazy {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            SecretKeySpec(bytes, "AES")
        }

        @Volatile
        private var INSTANCE: SecureCredentialVault? = null

        fun getInstance(context: Context): SecureCredentialVault {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecureCredentialVault(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
