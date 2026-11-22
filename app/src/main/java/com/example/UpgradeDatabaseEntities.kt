package com.example

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// New Call Type for YouTube View Milestone In-App Calls (reuses existing JarvisIncomingCallActivity!)
const val CALL_TYPE_YOUTUBE_MILESTONE = "YOUTUBE_MILESTONE_ALERT"

// Chess Difficulty Levels
const val CHESS_DIFFICULTY_CASUAL = 1
const val CHESS_DIFFICULTY_TACTICAL = 2
const val CHESS_DIFFICULTY_MASTER = 3

// YouTube Upload Types & Statuses
const val YT_UPLOAD_TYPE_SHORT = "SHORT"
const val YT_UPLOAD_TYPE_LONG = "LONG"
const val YT_UPLOAD_TYPE_COMMUNITY = "COMMUNITY_DRAFT"
const val YT_STATUS_DRAFT = "DRAFT"
const val YT_STATUS_UPLOADING = "UPLOADING"
const val YT_STATUS_PUBLISHED = "PUBLISHED"
const val YT_STATUS_SCHEDULED = "SCHEDULED"
const val YT_STATUS_FAILED = "FAILED"

// 1. Infinite Project Memory
@Entity(tableName = "project_memories")
data class ProjectMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    val progressSummary: String = "",
    val decisionsLog: String = "",
    val pendingTasksCsv: String = "",
    val status: String = "ACTIVE", // ACTIVE, PAUSED, COMPLETED
    val isCurrentActive: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

// 2. Mission Mode Runs
@Entity(tableName = "mission_runs")
data class MissionRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalCommand: String,
    val stepsJson: String, // JSONArray of {index, title, status, verification}
    val completedSteps: Int = 0,
    val totalSteps: Int = 0,
    val status: String = "RUNNING", // RUNNING, COMPLETED, FAILED, AWAITING_CONFIRMATION
    val summary: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

// 3. Knowledge Graph Nodes & Edges
@Entity(tableName = "knowledge_nodes")
data class KnowledgeNodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val nodeType: String, // MEMORY, PROJECT, TASK, VIDEO, CHESS, COMMAND
    val details: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "knowledge_edges")
data class KnowledgeEdgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromNodeId: Long,
    val toNodeId: Long,
    val relation: String, // RELATES_TO, PART_OF, DEPENDS_ON, IMPROVES, CREATED_FOR
    val createdAt: Long = System.currentTimeMillis()
)

// 4. Self-Improvement Engine Execution Strategies
@Entity(tableName = "execution_strategies")
data class ExecutionStrategyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskPattern: String,
    val failedReason: String,
    val improvedStrategy: String,
    val requiresUserApproval: Boolean = false,
    val approvedByUser: Boolean = true,
    val successCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

// 5. Personal Command Language (Custom Multi-Step Voice Shortcuts)
@Entity(tableName = "personal_commands")
data class PersonalCommandEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggerPhrase: String,
    val workflowStepsText: String, // Newline or "->" separated natural commands
    val spokenConfirmation: String = "",
    val runCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

// 6. Persistent AI Chess Games
@Entity(tableName = "chess_games")
data class ChessGameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val fen: String,
    val movesHistoryCsv: String = "", // UCI moves e.g. "e2e4,e7e5,g1f3"
    val difficulty: Int = CHESS_DIFFICULTY_TACTICAL,
    val playerSide: String = "WHITE", // WHITE or BLACK
    val status: String = "IN_PROGRESS", // IN_PROGRESS, CHECKMATE_USER, CHECKMATE_JARVIS, STALEMATE, DRAW, RESIGNED
    val moveCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

// 7. YouTube Upload History & Retry Recovery
@Entity(tableName = "youtube_uploads")
data class YouTubeUploadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uploadType: String, // SHORT, LONG, COMMUNITY_DRAFT
    val localVideoUri: String = "",
    val thumbnailUri: String = "",
    val title: String,
    val description: String = "",
    val tagsCsv: String = "",
    val privacyStatus: String = "private",
    val scheduledPublishIso: String = "",
    val youtubeVideoId: String = "",
    val channelId: String = "",
    val status: String = YT_STATUS_DRAFT,
    val progressPercent: Int = 0,
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

// 8. Automatic YouTube Milestone Monitoring
@Entity(tableName = "monitored_youtube_videos")
data class MonitoredYouTubeVideoEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val thumbnailUrl: String = "",
    val channelId: String = "",
    val currentViews: Long = 0L,
    val previousViews: Long = 0L,
    val currentLikes: Long = 0L,
    val currentComments: Long = 0L,
    val reachedMilestonesCsv: String = "", // Prevents duplicate milestone alerts permanently
    val isActive: Boolean = true,
    val lastCheckedAt: Long = System.currentTimeMillis(),
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "youtube_view_history")
data class YouTubeViewHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val views: Long,
    val likes: Long,
    val comments: Long,
    val recordedAt: Long = System.currentTimeMillis()
)

// 9. AI Clipping Studio Generated MP4 Clips
@Entity(tableName = "video_clips")
data class VideoClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceUri: String,
    val clipFilePath: String,
    val thumbnailPath: String = "",
    val title: String,
    val hookReason: String = "",
    val startMs: Long,
    val endMs: Long,
    val captionText: String = "",
    val isVertical916: Boolean = true,
    val batchNumber: Int = 1,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ProjectMemoryDao {
    @Query("SELECT * FROM project_memories ORDER BY isCurrentActive DESC, updatedAt DESC")
    fun observeProjects(): Flow<List<ProjectMemoryEntity>>

    @Query("SELECT * FROM project_memories WHERE isCurrentActive = 1 LIMIT 1")
    suspend fun getActiveProjectOnce(): ProjectMemoryEntity?

    @Query("SELECT * FROM project_memories ORDER BY updatedAt DESC")
    suspend fun getAllProjectsOnce(): List<ProjectMemoryEntity>

    @Query("UPDATE project_memories SET isCurrentActive = 0")
    suspend fun clearActiveFlags()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(project: ProjectMemoryEntity): Long

    @Delete
    suspend fun deleteProject(project: ProjectMemoryEntity)
}

@Dao
interface MissionRunDao {
    @Query("SELECT * FROM mission_runs ORDER BY createdAt DESC LIMIT 30")
    fun observeMissions(): Flow<List<MissionRunEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMission(mission: MissionRunEntity): Long

    @Delete
    suspend fun deleteMission(mission: MissionRunEntity)
}

@Dao
interface KnowledgeGraphDao {
    @Query("SELECT * FROM knowledge_nodes ORDER BY createdAt DESC LIMIT 120")
    fun observeNodes(): Flow<List<KnowledgeNodeEntity>>

    @Query("SELECT * FROM knowledge_edges ORDER BY createdAt DESC LIMIT 240")
    fun observeEdges(): Flow<List<KnowledgeEdgeEntity>>

    @Query("SELECT * FROM knowledge_nodes ORDER BY createdAt DESC LIMIT 120")
    suspend fun getNodesOnce(): List<KnowledgeNodeEntity>

    @Query("SELECT * FROM knowledge_nodes WHERE LOWER(label) = LOWER(:label) LIMIT 1")
    suspend fun findNodeByLabel(label: String): KnowledgeNodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNode(node: KnowledgeNodeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdge(edge: KnowledgeEdgeEntity): Long

    @Delete
    suspend fun deleteNode(node: KnowledgeNodeEntity)

    @Query("DELETE FROM knowledge_edges WHERE fromNodeId = :nodeId OR toNodeId = :nodeId")
    suspend fun deleteEdgesForNode(nodeId: Long)
}

@Dao
interface ExecutionStrategyDao {
    @Query("SELECT * FROM execution_strategies ORDER BY createdAt DESC LIMIT 60")
    fun observeStrategies(): Flow<List<ExecutionStrategyEntity>>

    @Query("SELECT * FROM execution_strategies ORDER BY createdAt DESC LIMIT 40")
    suspend fun getAllStrategiesOnce(): List<ExecutionStrategyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStrategy(strategy: ExecutionStrategyEntity): Long

    @Delete
    suspend fun deleteStrategy(strategy: ExecutionStrategyEntity)
}

@Dao
interface PersonalCommandDao {
    @Query("SELECT * FROM personal_commands ORDER BY createdAt DESC")
    fun observeCommands(): Flow<List<PersonalCommandEntity>>

    @Query("SELECT * FROM personal_commands")
    suspend fun getAllCommandsOnce(): List<PersonalCommandEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCommand(command: PersonalCommandEntity): Long

    @Delete
    suspend fun deleteCommand(command: PersonalCommandEntity)
}

@Dao
interface ChessGameDao {
    @Query("SELECT * FROM chess_games ORDER BY updatedAt DESC")
    fun observeGames(): Flow<List<ChessGameEntity>>

    @Query("SELECT * FROM chess_games WHERE status = 'IN_PROGRESS' ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestActiveGameOnce(): ChessGameEntity?

    @Query("SELECT * FROM chess_games WHERE id = :id LIMIT 1")
    suspend fun getGameById(id: Long): ChessGameEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGame(game: ChessGameEntity): Long

    @Delete
    suspend fun deleteGame(game: ChessGameEntity)
}

@Dao
interface YouTubeUploadDao {
    @Query("SELECT * FROM youtube_uploads ORDER BY createdAt DESC LIMIT 60")
    fun observeUploads(): Flow<List<YouTubeUploadEntity>>

    @Query("SELECT * FROM youtube_uploads WHERE id = :id LIMIT 1")
    suspend fun getUploadById(id: Long): YouTubeUploadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUpload(upload: YouTubeUploadEntity): Long

    @Delete
    suspend fun deleteUpload(upload: YouTubeUploadEntity)
}

@Dao
interface YouTubeMonitorDao {
    @Query("SELECT * FROM monitored_youtube_videos ORDER BY addedAt DESC")
    fun observeMonitoredVideos(): Flow<List<MonitoredYouTubeVideoEntity>>

    @Query("SELECT * FROM monitored_youtube_videos WHERE isActive = 1")
    suspend fun getActiveMonitoredVideosOnce(): List<MonitoredYouTubeVideoEntity>

    @Query("SELECT * FROM monitored_youtube_videos WHERE videoId = :videoId LIMIT 1")
    suspend fun getVideoById(videoId: String): MonitoredYouTubeVideoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMonitoredVideo(video: MonitoredYouTubeVideoEntity)

    @Delete
    suspend fun deleteMonitoredVideo(video: MonitoredYouTubeVideoEntity)

    @Query("SELECT * FROM youtube_view_history ORDER BY recordedAt DESC LIMIT 200")
    fun observeViewHistory(): Flow<List<YouTubeViewHistoryEntity>>

    @Query("SELECT * FROM youtube_view_history WHERE videoId = :videoId ORDER BY recordedAt ASC LIMIT 60")
    suspend fun getHistoryForVideo(videoId: String): List<YouTubeViewHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertViewSnapshot(snapshot: YouTubeViewHistoryEntity): Long
}

@Dao
interface VideoClipDao {
    @Query("SELECT * FROM video_clips ORDER BY createdAt DESC LIMIT 40")
    fun observeClips(): Flow<List<VideoClipEntity>>

    @Query("SELECT * FROM video_clips WHERE sourceUri = :sourceUri ORDER BY startMs ASC")
    suspend fun getClipsForSource(sourceUri: String): List<VideoClipEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertClip(clip: VideoClipEntity): Long

    @Delete
    suspend fun deleteClip(clip: VideoClipEntity)

    @Query("DELETE FROM video_clips WHERE sourceUri = :sourceUri")
    suspend fun deleteClipsForSource(sourceUri: String)
}
