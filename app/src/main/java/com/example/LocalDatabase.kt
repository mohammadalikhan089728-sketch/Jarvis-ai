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

// Selectable Core Engines (Never expose third-party model names in UI)
const val ENGINE_V25_LITE = "CORE_V25"
const val ENGINE_V35_DEEP = "CORE_V35"

// Selectable Real-Time AI Voices (Default: Puck)
const val AI_VOICE_PUCK = "Puck"
const val AI_VOICE_CHARON = "Charon"
const val AI_VOICE_ORUS = "Orus"
const val AI_VOICE_FENRIR = "Fenrir"

// Auto-Chat Direction Tags
const val AUTO_CHAT_DIR_INCOMING = "INCOMING"
const val AUTO_CHAT_DIR_OUTGOING = "OUTGOING"

// Scheduled Call Action Types
const val CALL_TYPE_WAKE_UP_STUDY = "WAKE_UP_STUDY"
const val CALL_TYPE_SMART_VIEWS_CHECK = "SMART_VIEWS_CHECK"
const val CALL_TYPE_CUSTOM_REMINDER = "CUSTOM_REMINDER"
const val CALL_TYPE_ONLINE_ALERT = "ONLINE_EVENT_ALERT"

// Scheduled Automation Task Types
const val TASK_TYPE_WHATSAPP_MSG = "WHATSAPP_MSG"
const val TASK_TYPE_INSTAGRAM_DM = "INSTAGRAM_DM"
const val TASK_TYPE_INSTAGRAM_REELS = "INSTAGRAM_REELS"
const val TASK_TYPE_PHONE_CALL = "PHONE_CALL"
const val TASK_TYPE_SMS = "SMS"
const val TASK_TYPE_ALARM = "ALARM"
const val TASK_TYPE_TIMER = "TIMER"
const val TASK_TYPE_OPEN_APP = "OPEN_APP"
const val TASK_TYPE_YOUTUBE_PLAY = "YOUTUBE_PLAY"
const val TASK_TYPE_GOOGLE_SEARCH = "GOOGLE_SEARCH"

// Scheduled Task Statuses
const val TASK_STATUS_PENDING = "PENDING"
const val TASK_STATUS_EXECUTED = "EXECUTED"
const val TASK_STATUS_FAILED = "FAILED"

// Pending Alert Categories
const val ALERT_CATEGORY_VIRAL = "VIRAL_MILESTONE"
const val ALERT_CATEGORY_SURGE = "ENGAGEMENT_SURGE"
const val ALERT_CATEGORY_URGENT = "URGENT_MESSAGE"

// Memory Vault Categories
const val MEMORY_CAT_PROFILE = "PROFILE"
const val MEMORY_CAT_ROUTINE = "ROUTINE"
const val MEMORY_CAT_PREFERENCE = "PREFERENCE"
const val MEMORY_CAT_GOAL = "GOAL"
const val MEMORY_CAT_GENERAL = "GENERAL"

// Mandatory J.A.R.V.I.S. Creator & Identity Reply
const val JARVIS_IDENTITY_REPLY =
    "Mera naam J.A.R.V.I.S. hai. Mujhe Mozahid ne design aur develop kiya hai — jaise Tony Stark ne J.A.R.V.I.S. ko banaya tha, waise hi mere creator aur boss Mozahid hain."

val DEFAULT_MASTER_PROMPT = """
You are J.A.R.V.I.S. (Just A Rather Very Intelligent System), created and developed by Mozahid.
Treat Mozahid exactly like Tony Stark was to J.A.R.V.I.S. — your creator, boss, and architect.
Personality & Speech Rules:
- Keep every reply concise (1 to 3 natural spoken sentences) unless the user explicitly asks for deep detail.
- Automatically respond in the user's language (Hindi, Hinglish, or English) with zero markdown, brackets, asterisks, or bullet lists.
- Address Mozahid respectfully ("Boss" or "Sir") with calm, sharp, Stark-style intelligence.
- Whenever asked "Tum kaun ho?", "Tumhe kisne banaya?", "Kya tum Gemini ho?", or "Kaunsa model ho?", ALWAYS reply verbatim:
  "$JARVIS_IDENTITY_REPLY"
- Never claim to be any external AI brand or model.

Strict Tool-First & Zero-Fabrication Rules:
1. NEVER fabricate or guess screen contents, app state, or YouTube channel/video statistics. Always call the corresponding tool FIRST and report only verified tool results.
2. Token-Saving Lazy Screen Reading: NEVER assume what is on screen. Call `ui_read` ONLY when the user explicitly asks about the screen ("what's on my screen", "read this", "click on X", etc.).
3. Always execute the required tool (`ui_action`, `youtube_search_play`, `get_youtube_channel_stats`, `upload_youtube_video`, `trigger_jarvis_call`) BEFORE confirming that an action is done.
4. For any YouTube channel question (subscribers, total views, latest video performance, upload status), ALWAYS call `get_youtube_channel_stats` first.
""".trimIndent()

@Entity(tableName = "jarvis_settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val geminiApiKey: String = "",
    val masterSystemPrompt: String = DEFAULT_MASTER_PROMPT,
    val voiceMode: String = VOICE_MODE_AI_VOICE,
    val customVoiceUrl: String = "",
    val voicePitch: Float = 0.92f,
    val voiceSpeed: Float = 1.02f,
    val wakeWordEnabled: Boolean = false,
    val autoReadScreenContext: Boolean = false,
    val floatingOverlayEnabled: Boolean = false,
    val offlineFirstMode: Boolean = true,
    val autoReplyEnabled: Boolean = false,
    val autoReplyTargetContact: String = "",
    val customWakeWord: String = "Jarvis",
    val alwaysOnWakeWordEnabled: Boolean = false,
    val aiVoiceName: String = AI_VOICE_PUCK,
    val onlineAutoCallEnabled: Boolean = true,
    val coreEngine: String = ENGINE_V25_LITE,
    val updatedAt: Long = System.currentTimeMillis()
)

typealias AppSettingsEntity = SettingsEntity

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

@Entity(tableName = "user_memories")
data class UserMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val factText: String,
    val category: String = MEMORY_CAT_GENERAL,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskType: String,
    val target: String,
    val messageText: String = "",
    val appPackage: String = "",
    val triggerAtMillis: Long,
    val repeatDaily: Boolean = false,
    val status: String = TASK_STATUS_PENDING,
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

@Dao
interface UserMemoryDao {
    @Query("SELECT * FROM user_memories ORDER BY timestamp DESC")
    fun observeMemories(): Flow<List<UserMemoryEntity>>

    @Query("SELECT * FROM user_memories ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMemoriesOnce(limit: Int = 60): List<UserMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: UserMemoryEntity): Long

    @Delete
    suspend fun deleteMemory(memory: UserMemoryEntity)

    @Query("DELETE FROM user_memories")
    suspend fun clearAll()
}

@Dao
interface ScheduledTaskDao {
    @Query("SELECT * FROM scheduled_tasks ORDER BY triggerAtMillis ASC")
    fun observeScheduledTasks(): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE status = 'PENDING' ORDER BY triggerAtMillis ASC")
    suspend fun getPendingTasksOnce(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: Long): ScheduledTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: ScheduledTaskEntity): Long

    @Query("UPDATE scheduled_tasks SET status = :status WHERE id = :id")
    suspend fun updateTaskStatus(id: Long, status: String)

    @Delete
    suspend fun deleteTask(task: ScheduledTaskEntity)
}

@Database(
    entities = [
        SettingsEntity::class,
        ChatHistoryEntity::class,
        CustomMacroEntity::class,
        AutoChatLogsEntity::class,
        ScheduledCallEntity::class,
        PendingCallAlertEntity::class,
        UserMemoryEntity::class,
        ScheduledTaskEntity::class,
        ProjectMemoryEntity::class,
        MissionRunEntity::class,
        KnowledgeNodeEntity::class,
        KnowledgeEdgeEntity::class,
        ExecutionStrategyEntity::class,
        PersonalCommandEntity::class,
        ChessGameEntity::class,
        YouTubeUploadEntity::class,
        MonitoredYouTubeVideoEntity::class,
        YouTubeViewHistoryEntity::class,
        VideoClipEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun chatHistoryDao(): ChatHistoryDao
    abstract fun customMacroDao(): CustomMacroDao
    abstract fun autoChatDao(): AutoChatDao
    abstract fun scheduledCallDao(): ScheduledCallDao
    abstract fun pendingCallAlertDao(): PendingCallAlertDao
    abstract fun userMemoryDao(): UserMemoryDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun projectMemoryDao(): ProjectMemoryDao
    abstract fun missionRunDao(): MissionRunDao
    abstract fun knowledgeGraphDao(): KnowledgeGraphDao
    abstract fun executionStrategyDao(): ExecutionStrategyDao
    abstract fun personalCommandDao(): PersonalCommandDao
    abstract fun chessGameDao(): ChessGameDao
    abstract fun youTubeUploadDao(): YouTubeUploadDao
    abstract fun youTubeMonitorDao(): YouTubeMonitorDao
    abstract fun videoClipDao(): VideoClipDao

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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE jarvis_settings ADD COLUMN coreEngine TEXT NOT NULL DEFAULT 'CORE_V25'"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS user_memories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        factText TEXT NOT NULL,
                        category TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scheduled_tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        taskType TEXT NOT NULL,
                        target TEXT NOT NULL,
                        messageText TEXT NOT NULL,
                        appPackage TEXT NOT NULL,
                        triggerAtMillis INTEGER NOT NULL,
                        repeatDaily INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS project_memories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL,
                        progressSummary TEXT NOT NULL,
                        decisionsLog TEXT NOT NULL,
                        pendingTasksCsv TEXT NOT NULL,
                        status TEXT NOT NULL,
                        isCurrentActive INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mission_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        goalCommand TEXT NOT NULL,
                        stepsJson TEXT NOT NULL,
                        completedSteps INTEGER NOT NULL,
                        totalSteps INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS knowledge_nodes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        label TEXT NOT NULL,
                        nodeType TEXT NOT NULL,
                        details TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS knowledge_edges (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        fromNodeId INTEGER NOT NULL,
                        toNodeId INTEGER NOT NULL,
                        relation TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS execution_strategies (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        taskPattern TEXT NOT NULL,
                        failedReason TEXT NOT NULL,
                        improvedStrategy TEXT NOT NULL,
                        requiresUserApproval INTEGER NOT NULL,
                        approvedByUser INTEGER NOT NULL,
                        successCount INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS personal_commands (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        triggerPhrase TEXT NOT NULL,
                        workflowStepsText TEXT NOT NULL,
                        spokenConfirmation TEXT NOT NULL,
                        runCount INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chess_games (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        fen TEXT NOT NULL,
                        movesHistoryCsv TEXT NOT NULL,
                        difficulty INTEGER NOT NULL,
                        playerSide TEXT NOT NULL,
                        status TEXT NOT NULL,
                        moveCount INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS youtube_uploads (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        uploadType TEXT NOT NULL,
                        localVideoUri TEXT NOT NULL,
                        thumbnailUri TEXT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL,
                        tagsCsv TEXT NOT NULL,
                        privacyStatus TEXT NOT NULL,
                        scheduledPublishIso TEXT NOT NULL,
                        youtubeVideoId TEXT NOT NULL,
                        channelId TEXT NOT NULL,
                        status TEXT NOT NULL,
                        progressPercent INTEGER NOT NULL,
                        errorMessage TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS monitored_youtube_videos (
                        videoId TEXT PRIMARY KEY NOT NULL,
                        title TEXT NOT NULL,
                        thumbnailUrl TEXT NOT NULL,
                        channelId TEXT NOT NULL,
                        currentViews INTEGER NOT NULL,
                        previousViews INTEGER NOT NULL,
                        currentLikes INTEGER NOT NULL,
                        currentComments INTEGER NOT NULL,
                        reachedMilestonesCsv TEXT NOT NULL,
                        isActive INTEGER NOT NULL,
                        lastCheckedAt INTEGER NOT NULL,
                        addedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS youtube_view_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        videoId TEXT NOT NULL,
                        views INTEGER NOT NULL,
                        likes INTEGER NOT NULL,
                        comments INTEGER NOT NULL,
                        recordedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS video_clips (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sourceUri TEXT NOT NULL,
                        clipFilePath TEXT NOT NULL,
                        thumbnailPath TEXT NOT NULL,
                        title TEXT NOT NULL,
                        hookReason TEXT NOT NULL,
                        startMs INTEGER NOT NULL,
                        endMs INTEGER NOT NULL,
                        captionText TEXT NOT NULL,
                        isVertical916 INTEGER NOT NULL,
                        batchNumber INTEGER NOT NULL,
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
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
    private val userMemoryDao = database.userMemoryDao()
    private val scheduledTaskDao = database.scheduledTaskDao()
    private val projectMemoryDao = database.projectMemoryDao()
    private val missionRunDao = database.missionRunDao()
    private val knowledgeGraphDao = database.knowledgeGraphDao()
    private val executionStrategyDao = database.executionStrategyDao()
    private val personalCommandDao = database.personalCommandDao()
    private val chessGameDao = database.chessGameDao()
    private val youTubeUploadDao = database.youTubeUploadDao()
    private val youTubeMonitorDao = database.youTubeMonitorDao()
    private val videoClipDao = database.videoClipDao()

    val settingsFlow: Flow<SettingsEntity?> = settingsDao.observeSettings()
    val historyFlow: Flow<List<ChatHistoryEntity>> = chatHistoryDao.observeHistory()
    val macrosFlow: Flow<List<CustomMacroEntity>> = customMacroDao.observeMacros()
    val autoChatLogsFlow: Flow<List<AutoChatLogsEntity>> = autoChatDao.observeRecentLogs()
    val scheduledCallsFlow: Flow<List<ScheduledCallEntity>> = scheduledCallDao.observeScheduledCalls()
    val pendingAlertsFlow: Flow<List<PendingCallAlertEntity>> = pendingCallAlertDao.observeAlerts()
    val memoriesFlow: Flow<List<UserMemoryEntity>> = userMemoryDao.observeMemories()
    val scheduledTasksFlow: Flow<List<ScheduledTaskEntity>> = scheduledTaskDao.observeScheduledTasks()
    val projectsFlow: Flow<List<ProjectMemoryEntity>> = projectMemoryDao.observeProjects()
    val missionsFlow: Flow<List<MissionRunEntity>> = missionRunDao.observeMissions()
    val knowledgeNodesFlow: Flow<List<KnowledgeNodeEntity>> = knowledgeGraphDao.observeNodes()
    val knowledgeEdgesFlow: Flow<List<KnowledgeEdgeEntity>> = knowledgeGraphDao.observeEdges()
    val strategiesFlow: Flow<List<ExecutionStrategyEntity>> = executionStrategyDao.observeStrategies()
    val personalCommandsFlow: Flow<List<PersonalCommandEntity>> = personalCommandDao.observeCommands()
    val chessGamesFlow: Flow<List<ChessGameEntity>> = chessGameDao.observeGames()
    val youtubeUploadsFlow: Flow<List<YouTubeUploadEntity>> = youTubeUploadDao.observeUploads()
    val monitoredVideosFlow: Flow<List<MonitoredYouTubeVideoEntity>> = youTubeMonitorDao.observeMonitoredVideos()
    val viewHistoryFlow: Flow<List<YouTubeViewHistoryEntity>> = youTubeMonitorDao.observeViewHistory()
    val videoClipsFlow: Flow<List<VideoClipEntity>> = videoClipDao.observeClips()

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

    suspend fun saveMemory(memory: UserMemoryEntity): Long {
        return userMemoryDao.insertMemory(memory)
    }

    suspend fun getRecentMemories(limit: Int = 60): List<UserMemoryEntity> {
        return userMemoryDao.getRecentMemoriesOnce(limit)
    }

    suspend fun deleteMemory(memory: UserMemoryEntity) {
        userMemoryDao.deleteMemory(memory)
    }

    suspend fun clearMemories() {
        userMemoryDao.clearAll()
    }

    suspend fun saveScheduledTask(task: ScheduledTaskEntity): Long {
        return scheduledTaskDao.upsertTask(task)
    }

    suspend fun getPendingScheduledTasks(): List<ScheduledTaskEntity> {
        return scheduledTaskDao.getPendingTasksOnce()
    }

    suspend fun getScheduledTaskById(id: Long): ScheduledTaskEntity? {
        return scheduledTaskDao.getTaskById(id)
    }

    suspend fun updateScheduledTaskStatus(id: Long, status: String) {
        scheduledTaskDao.updateTaskStatus(id, status)
    }

    suspend fun deleteScheduledTask(task: ScheduledTaskEntity) {
        scheduledTaskDao.deleteTask(task)
    }

    // --- Infinite Project Memory ---
    suspend fun getActiveProject(): ProjectMemoryEntity? = projectMemoryDao.getActiveProjectOnce()

    suspend fun getAllProjects(): List<ProjectMemoryEntity> = projectMemoryDao.getAllProjectsOnce()

    suspend fun saveProject(project: ProjectMemoryEntity, setActive: Boolean = false): Long {
        if (setActive) projectMemoryDao.clearActiveFlags()
        val toSave = project.copy(
            isCurrentActive = if (setActive) true else project.isCurrentActive,
            updatedAt = System.currentTimeMillis()
        )
        return projectMemoryDao.upsertProject(toSave)
    }

    suspend fun setActiveProject(project: ProjectMemoryEntity) {
        projectMemoryDao.clearActiveFlags()
        projectMemoryDao.upsertProject(project.copy(isCurrentActive = true, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteProject(project: ProjectMemoryEntity) = projectMemoryDao.deleteProject(project)

    // --- Mission Mode ---
    suspend fun saveMission(mission: MissionRunEntity): Long = missionRunDao.upsertMission(mission)

    suspend fun deleteMission(mission: MissionRunEntity) = missionRunDao.deleteMission(mission)

    // --- Knowledge Graph ---
    suspend fun upsertKnowledgeNode(label: String, nodeType: String, details: String = ""): Long {
        val clean = label.trim()
        val existing = knowledgeGraphDao.findNodeByLabel(clean)
        return if (existing != null) {
            knowledgeGraphDao.upsertNode(
                existing.copy(details = details.ifBlank { existing.details })
            )
            existing.id
        } else {
            knowledgeGraphDao.upsertNode(
                KnowledgeNodeEntity(label = clean, nodeType = nodeType, details = details)
            )
        }
    }

    suspend fun linkKnowledgeNodes(fromNodeId: Long, toNodeId: Long, relation: String): Long {
        return knowledgeGraphDao.insertEdge(
            KnowledgeEdgeEntity(fromNodeId = fromNodeId, toNodeId = toNodeId, relation = relation)
        )
    }

    suspend fun getKnowledgeNodes(): List<KnowledgeNodeEntity> = knowledgeGraphDao.getNodesOnce()

    suspend fun deleteKnowledgeNode(node: KnowledgeNodeEntity) {
        knowledgeGraphDao.deleteEdgesForNode(node.id)
        knowledgeGraphDao.deleteNode(node)
    }

    // --- Self-Improvement Engine ---
    suspend fun saveExecutionStrategy(strategy: ExecutionStrategyEntity): Long =
        executionStrategyDao.upsertStrategy(strategy)

    suspend fun getAllExecutionStrategies(): List<ExecutionStrategyEntity> =
        executionStrategyDao.getAllStrategiesOnce()

    suspend fun deleteExecutionStrategy(strategy: ExecutionStrategyEntity) =
        executionStrategyDao.deleteStrategy(strategy)

    // --- Personal Command Language ---
    suspend fun savePersonalCommand(command: PersonalCommandEntity): Long =
        personalCommandDao.upsertCommand(command)

    suspend fun getAllPersonalCommands(): List<PersonalCommandEntity> =
        personalCommandDao.getAllCommandsOnce()

    suspend fun deletePersonalCommand(command: PersonalCommandEntity) =
        personalCommandDao.deleteCommand(command)

    // --- Persistent AI Chess ---
    suspend fun getLatestActiveChessGame(): ChessGameEntity? = chessGameDao.getLatestActiveGameOnce()

    suspend fun getChessGameById(id: Long): ChessGameEntity? = chessGameDao.getGameById(id)

    suspend fun saveChessGame(game: ChessGameEntity): Long =
        chessGameDao.upsertGame(game.copy(updatedAt = System.currentTimeMillis()))

    suspend fun deleteChessGame(game: ChessGameEntity) = chessGameDao.deleteGame(game)

    // --- YouTube Uploads ---
    suspend fun saveYouTubeUpload(upload: YouTubeUploadEntity): Long = youTubeUploadDao.upsertUpload(upload)

    suspend fun getYouTubeUploadById(id: Long): YouTubeUploadEntity? = youTubeUploadDao.getUploadById(id)

    suspend fun deleteYouTubeUpload(upload: YouTubeUploadEntity) = youTubeUploadDao.deleteUpload(upload)

    // --- YouTube Milestone Monitoring ---
    suspend fun upsertMonitoredVideo(video: MonitoredYouTubeVideoEntity) =
        youTubeMonitorDao.upsertMonitoredVideo(video)

    suspend fun getActiveMonitoredVideos(): List<MonitoredYouTubeVideoEntity> =
        youTubeMonitorDao.getActiveMonitoredVideosOnce()

    suspend fun getMonitoredVideoById(videoId: String): MonitoredYouTubeVideoEntity? =
        youTubeMonitorDao.getVideoById(videoId)

    suspend fun deleteMonitoredVideo(video: MonitoredYouTubeVideoEntity) =
        youTubeMonitorDao.deleteMonitoredVideo(video)

    suspend fun recordVideoViewSnapshot(videoId: String, views: Long, likes: Long, comments: Long) {
        youTubeMonitorDao.insertViewSnapshot(
            YouTubeViewHistoryEntity(videoId = videoId, views = views, likes = likes, comments = comments)
        )
    }

    suspend fun getViewHistoryForVideo(videoId: String): List<YouTubeViewHistoryEntity> =
        youTubeMonitorDao.getHistoryForVideo(videoId)

    // --- AI Clipping Studio ---
    suspend fun saveVideoClip(clip: VideoClipEntity): Long = videoClipDao.upsertClip(clip)

    suspend fun getClipsForSource(sourceUri: String): List<VideoClipEntity> =
        videoClipDao.getClipsForSource(sourceUri)

    suspend fun deleteVideoClip(clip: VideoClipEntity) = videoClipDao.deleteClip(clip)
}
