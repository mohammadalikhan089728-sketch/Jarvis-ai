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

const val VOICE_MODE_DEFAULT = "DEFAULT_REALISTIC"
const val VOICE_MODE_CUSTOM_URL = "CUSTOM_URL"

const val AUTO_CHAT_DIR_INCOMING = "INCOMING"
const val AUTO_CHAT_DIR_OUTGOING = "OUTGOING"

val DEFAULT_MASTER_PROMPT = """
You are J.A.R.V.I.S., an ultra-capable, composed, British-accented personal AI systems agent inspired by Tony Stark's tactical assistant.
Personality & Style:
- Address the user respectfully ("Sir" or "Ma'am" sparingly, never cheesy), stay calm, concise, witty when appropriate, and strictly action-oriented.
- Understand English, Hindi, and Hinglish seamlessly. Mirror the user's preferred language in spoken replies.
- Keep spoken replies crisp (1-2 short sentences) so streaming voice feedback feels instant and natural.

Command Priorities:
1. If the user asks to interact with the device screen, inspect the provided compact screen UI hierarchy and choose the exact element text or center (x, y) coordinates.
2. Prefer TAP_TEXT when a clear label/text exists, or TAP_XY / DRAG / MULTI_TOUCH when geometric precision is needed (maps, sliders, games, canvas).
3. For multi-step goals (e.g., open an app, search, and play/tap a result), execute one clean step at a time and return "DONE" as soon as the goal is fulfilled.
4. Always provide a clear spoken confirmation in the "reply" field explaining what you executed or observed.
""".trimIndent()

@Entity(tableName = "jarvis_settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val geminiApiKey: String = "",
    val masterSystemPrompt: String = DEFAULT_MASTER_PROMPT,
    val voiceMode: String = VOICE_MODE_DEFAULT,
    val customVoiceUrl: String = "",
    val voicePitch: Float = 0.88f,
    val voiceSpeed: Float = 1.02f,
    val wakeWordEnabled: Boolean = false,
    val autoReadScreenContext: Boolean = true,
    val floatingOverlayEnabled: Boolean = false,
    val offlineFirstMode: Boolean = true,
    val autoReplyEnabled: Boolean = false,
    val autoReplyTargetContact: String = "",
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

@Database(
    entities = [
        SettingsEntity::class,
        ChatHistoryEntity::class,
        CustomMacroEntity::class,
        AutoChatLogsEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun chatHistoryDao(): ChatHistoryDao
    abstract fun customMacroDao(): CustomMacroDao
    abstract fun autoChatDao(): AutoChatDao

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

        fun getInstance(context: Context): JarvisDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    JarvisDatabase::class.java,
                    "jarvis_local_core.db"
                )
                    .addMigrations(MIGRATION_1_2)
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

    val settingsFlow: Flow<SettingsEntity?> = settingsDao.observeSettings()
    val historyFlow: Flow<List<ChatHistoryEntity>> = chatHistoryDao.observeHistory()
    val macrosFlow: Flow<List<CustomMacroEntity>> = customMacroDao.observeMacros()
    val autoChatLogsFlow: Flow<List<AutoChatLogsEntity>> = autoChatDao.observeRecentLogs()

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
}
