package com.example

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// Voice Modes
const val VOICE_MODE_NORMAL = "NORMAL_VOICE"
const val VOICE_MODE_AI_VOICE = "AI_VOICE"
const val VOICE_MODE_DEFAULT = VOICE_MODE_NORMAL
const val VOICE_MODE_CUSTOM_URL = "CUSTOM_URL"

// Selectable Male AI Voices
const val AI_VOICE_CHARON = "Charon"
const val AI_VOICE_FENRIR = "Fenrir"

// Auto-Chat Direction Tags
const val AUTO_CHAT_DIR_INCOMING = "INCOMING"
const val AUTO_CHAT_DIR_OUTGOING = "OUTGOING"

// Scheduled Call Action Types
const val CALL_TYPE_WAKE_UP_STUDY = "WAKE_UP_STUDY"
const val CALL_TYPE_SMART_VIEWS_CHECK = "SMART_VIEWS_CHECK"
const val CALL_TYPE_CUSTOM_REMINDER = "CUSTOM_REMINDER"
const val CALL_TYPE_ONLINE_ALERT = "ONLINE_EVENT_ALERT"

// Pending Alert Categories
const val ALERT_CATEGORY_VIRAL = "VIRAL_MILESTONE"
const val ALERT_CATEGORY_SURGE = "ENGAGEMENT_SURGE"
const val ALERT_CATEGORY_URGENT = "URGENT_MESSAGE"

val DEFAULT_MASTER_PROMPT = """
You are J.A.R.V.I.S. (Developed by Mozahid), an ultra-capable, composed, intelligent personal AI systems agent.
Personality & Style:
- Address the user respectfully ("Sir" or "Ma'am" sparingly), stay calm, concise, witty when appropriate, and strictly action-oriented.
- Understand English, Hindi, and Hinglish seamlessly. Mirror the user's preferred language in spoken replies.
- Keep spoken replies crisp (1-2 short sentences) so voice feedback feels immediate and natural.
- Never claim to be any third-party brand; you are strictly JARVIS, developed by Mozahid.

Command Priorities:
1. If the user asks to control the phone, inspect the compact screen UI JSON and select exact element text or center (x, y) coordinates.
2. Prefer TAP_TEXT when a clear label exists, or TAP_XY / SWIPE / DRAG / MULTI_TOUCH when geometric precision is needed.
3. For multi-step workflows (e.g., open YouTube, search, play video; open WhatsApp and send a message; open Settings and enable hotspot), execute one step at a time and return "DONE" as soon as the goal is fulfilled.
4. Always provide a clear spoken confirmation in the "reply" field explaining what you executed or observed.
""".trimIndent()

@Entity(tableName = "jarvis_settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val geminiApiKey: String = "",
    val masterSystemPrompt: String = DEFAULT_MASTER_PROMPT,
    val voiceMode: String = VOICE_MODE_NORMAL,
    val customVoiceUrl: String = "",
    val voicePitch: Float = 0.92f,
    val voiceSpeed: Float = 1.02f,
    val wakeWordEnabled: Boolean = false,
    val autoReadScreenContext: Boolean = true,
    val floatingOverlayEnabled: Boolean = false,
    val offlineFirstMode: Boolean = true,
    val autoReplyEnabled: Boolean = false,
    val autoReplyTargetContact: String = "",
    val customWakeWord: String = "Jarvis",
    val alwaysOnWakeWordEnabled: Boolean = false,
    val aiVoiceName: String = AI_VOICE_CHARON,
    val onlineAutoCallEnabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_history")
data class ChatHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userTranscript: String,
    val executedAction: String,
    val actionParamsJson: String,
    val jarvisReply: String,
    val isOffline: Boolean,
    val latencyMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "custom_macros")
data class CustomMacroEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggerPhrase: String,
    val actionType: String,
    val actionPayload: String,
    val spokenReply: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "auto_chat_logs")
data class AutoChatLogsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val senderName: String,
    val appPackage: String,
    val appLabel: String,
    val direction: String,
    val messageText: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "scheduled_calls")
data class ScheduledCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val purpose: String,
    val actionType: String = CALL_TYPE_CUSTOM_REMINDER,
    val repeatDaysCsv: String = "DAILY", // "ONCE", "DAILY", or comma-separated Calendar.DAY_OF_WEEK ints (1=Sun..7=Sat)
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "pending_call_alerts")
data class PendingCallAlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val summary: String,
    val sourceApp: String,
    val alertCategory: String = ALERT_CATEGORY_VIRAL,
    val handled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface SettingsDao {
    @Query("SELECT * FROM jarvis_settings WHERE id = 1 LIMIT 1")
    fun observeSettings(): Flow<SettingsEntity?>

    @Query("SELECT * FROM jarvis_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettingsOnce(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: SettingsEntity)
}

@Dao
interface ChatHistoryDao {
    @Query("SELECT * FROM chat_history ORDER BY timestamp DESC LIMIT 100")
    fun observeHistory(): Flow<List<ChatHistoryEntity>>

    @Query("SELECT * FROM chat_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentHistory(limit: Int): List<ChatHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: ChatHistoryEntity): Long

    @Query("DELETE FROM chat_history")
    suspend fun clearAll()
}

@Dao
interface CustomMacroDao {
    @Query("SELECT * FROM custom_macros ORDER BY createdAt DESC")
    fun observeMacros(): Flow<List<CustomMacroEntity>>

    @Query("SELECT * FROM custom_macros")
    suspend fun getAllMacrosOnce(): List<CustomMacroEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMacro(macro: CustomMacroEntity): Long

    @Delete
    suspend fun deleteMacro(macro: CustomMacroEntity)
}

@Dao
interface AutoChatDao {
    @Query("SELECT * FROM auto_chat_logs ORDER BY timestamp DESC LIMIT 100")
    fun observeRecentLogs(): Flow<List<AutoChatLogsEntity>>

    @Query("SELECT * FROM auto_chat_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLogsOnce(limit: Int): List<AutoChatLogsEntity>

    @Query(
        "SELECT * FROM auto_chat_logs " +
            "WHERE LOWER(senderName) LIKE '%' || LOWER(:senderQuery) || '%' " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun getLogsForSender(senderQuery: String, limit: Int): List<AutoChatLogsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(entry: AutoChatLogsEntity): Long

    @Query("DELETE FROM auto_chat_logs")
    suspend fun clearAll()
}

@Dao
interface ScheduledCallDao {
    @Query("SELECT * FROM scheduled_calls ORDER BY hour ASC, minute ASC")
    fun observeScheduledCalls(): Flow<List<ScheduledCallEntity>>

    @Query("SELECT * FROM scheduled_calls WHERE enabled = 1 ORDER BY hour ASC, minute ASC")
    suspend fun getEnabledCallsOnce(): List<ScheduledCallEntity>

    @Query("SELECT * FROM scheduled_calls WHERE id = :id LIMIT 1")
    suspend fun getCallById(id: Long): ScheduledCallEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCall(call: ScheduledCallEntity): Long

    @Query("UPDATE scheduled_calls SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Delete
    suspend fun deleteCall(call: ScheduledCallEntity)
}

@Dao
interface PendingCallAlertDao {
    @Query("SELECT * FROM pending_call_alerts ORDER BY createdAt DESC LIMIT 50")
    fun observeAlerts(): Flow<List<PendingCallAlertEntity>>

    @Query("SELECT * FROM pending_call_alerts WHERE handled = 0 ORDER BY createdAt DESC LIMIT 20")
    suspend fun getUnhandledAlertsOnce(): List<PendingCallAlertEntity>

    @Query("SELECT * FROM pending_call_alerts ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentAlertsOnce(limit: Int): List<PendingCallAlertEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlert(alert: PendingCallAlertEntity): Long

    @Query("UPDATE pending_call_alerts SET handled = 1 WHERE handled = 0")
    suspend fun markAllHandled()

    @Query("DELETE FROM pending_call_alerts")
    suspend fun clearAll()
}

@Database(
    entities = [
        SettingsEntity::class,
        ChatHistoryEntity::class,
        CustomMacroEntity::class,
        AutoChatLogsEntity::class,
        ScheduledCallEntity::class,
        PendingCallAlertEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun chatHistoryDao(): ChatHistoryDao
    abstract fun customMacroDao(): CustomMacroDao
    abstract fun autoChatDao(): AutoChatDao
    abstract fun scheduledCallDao(): ScheduledCallDao
    abstract fun pendingCallAlertDao(): PendingCallAlertDao

    companion object {
        @Volatile
        private var INSTANCE: JarvisDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE jarvis_settings ADD COLUMN autoReplyEnabled INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE jarvis_settings ADD COLUMN autoReplyTargetContact TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS auto_chat_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        senderName TEXT NOT NULL,
                        appPackage TEXT NOT NULL,
                        appLabel TEXT NOT NULL,
                        direction TEXT NOT NULL,
                        messageText TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE jarvis_settings ADD COLUMN customWakeWord TEXT NOT NULL DEFAULT 'Jarvis'"
                )
                db.execSQL(
                    "ALTER TABLE jarvis_settings ADD COLUMN alwaysOnWakeWordEnabled INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE jarvis_settings ADD COLUMN aiVoiceName TEXT NOT NULL DEFAULT 'Charon'"
                )
                db.execSQL(
                    "ALTER TABLE jarvis_settings ADD COLUMN onlineAutoCallEnabled INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scheduled_calls (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        hour INTEGER NOT NULL,
                        minute INTEGER NOT NULL,
                        purpose TEXT NOT NULL,
                        actionType TEXT NOT NULL,
                        repeatDaysCsv TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_call_alerts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        sourceApp TEXT NOT NULL,
                        alertCategory TEXT NOT NULL,
                        handled INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): JarvisDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    JarvisDatabase::class.java,
                    "jarvis_local_core.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

class JarvisRepository(private val database: JarvisDatabase) {
    private val settingsDao = database.settingsDao()
    private val chatHistoryDao = database.chatHistoryDao()
    private val customMacroDao = database.customMacroDao()
    private val autoChatDao = database.autoChatDao()
    private val scheduledCallDao = database.scheduledCallDao()
    private val pendingCallAlertDao = database.pendingCallAlertDao()

    val settingsFlow: Flow<SettingsEntity?> = settingsDao.observeSettings()
    val historyFlow: Flow<List<ChatHistoryEntity>> = chatHistoryDao.observeHistory()
    val macrosFlow: Flow<List<CustomMacroEntity>> = customMacroDao.observeMacros()
    val autoChatLogsFlow: Flow<List<AutoChatLogsEntity>> = autoChatDao.observeRecentLogs()
    val scheduledCallsFlow: Flow<List<ScheduledCallEntity>> = scheduledCallDao.observeScheduledCalls()
    val pendingAlertsFlow: Flow<List<PendingCallAlertEntity>> = pendingCallAlertDao.observeAlerts()

    suspend fun ensureDefaultsInitialized(): SettingsEntity {
        val existing = settingsDao.getSettingsOnce()
        if (existing != null) return existing
        val defaults = SettingsEntity()
        settingsDao.upsertSettings(defaults)
        return defaults
    }

    suspend fun getSettings(): SettingsEntity {
        return settingsDao.getSettingsOnce() ?: ensureDefaultsInitialized()
    }

    suspend fun saveSettings(settings: SettingsEntity) {
        settingsDao.upsertSettings(settings.copy(id = 1, updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateAutoReplyConfig(enabled: Boolean, targetContact: String? = null): SettingsEntity {
        val current = getSettings()
        val updated = current.copy(
            autoReplyEnabled = enabled,
            autoReplyTargetContact = targetContact?.trim() ?: current.autoReplyTargetContact,
            updatedAt = System.currentTimeMillis()
        )
        settingsDao.upsertSettings(updated)
        return updated
    }

    suspend fun logInteraction(entry: ChatHistoryEntity) {
        chatHistoryDao.insertEntry(entry)
    }

    suspend fun getRecentHistory(limit: Int = 6): List<ChatHistoryEntity> {
        return chatHistoryDao.getRecentHistory(limit).reversed()
    }

    suspend fun clearHistory() {
        chatHistoryDao.clearAll()
    }

    suspend fun getAllMacros(): List<CustomMacroEntity> {
        return customMacroDao.getAllMacrosOnce()
    }

    suspend fun addMacro(macro: CustomMacroEntity) {
        customMacroDao.insertMacro(macro)
    }

    suspend fun deleteMacro(macro: CustomMacroEntity) {
        customMacroDao.deleteMacro(macro)
    }

    suspend fun logAutoChat(entry: AutoChatLogsEntity) {
        autoChatDao.insertLog(entry)
    }

    suspend fun getRecentAutoChatLogs(limit: Int = 20): List<AutoChatLogsEntity> {
        return autoChatDao.getRecentLogsOnce(limit)
    }

    suspend fun getAutoChatLogsForSender(senderQuery: String, limit: Int = 12): List<AutoChatLogsEntity> {
        return autoChatDao.getLogsForSender(senderQuery.trim(), limit).reversed()
    }

    suspend fun clearAutoChatLogs() {
        autoChatDao.clearAll()
    }

    suspend fun saveScheduledCall(call: ScheduledCallEntity): Long {
        return scheduledCallDao.upsertCall(call)
    }

    suspend fun getEnabledScheduledCalls(): List<ScheduledCallEntity> {
        return scheduledCallDao.getEnabledCallsOnce()
    }

    suspend fun getScheduledCallById(id: Long): ScheduledCallEntity? {
        return scheduledCallDao.getCallById(id)
    }

    suspend fun setScheduledCallEnabled(id: Long, enabled: Boolean) {
        scheduledCallDao.setEnabled(id, enabled)
    }

    suspend fun deleteScheduledCall(call: ScheduledCallEntity) {
        scheduledCallDao.deleteCall(call)
    }

    suspend fun addPendingCallAlert(alert: PendingCallAlertEntity): Long {
        return pendingCallAlertDao.insertAlert(alert)
    }

    suspend fun getUnhandledCallAlerts(): List<PendingCallAlertEntity> {
        return pendingCallAlertDao.getUnhandledAlertsOnce()
    }

    suspend fun getRecentCallAlerts(limit: Int = 10): List<PendingCallAlertEntity> {
        return pendingCallAlertDao.getRecentAlertsOnce(limit)
    }

    suspend fun markAllAlertsHandled() {
        pendingCallAlertDao.markAllHandled()
    }
}
