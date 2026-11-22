package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JarvisUpgradeVerificationTest {

    private lateinit var context: Context
    private lateinit var db: JarvisDatabase
    private lateinit var repo: JarvisRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, JarvisDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = JarvisRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -------------------------------------------------------------------------
    // 1. YOUTUBE VOICE COMMAND BUG FIX & ACTION PLANNER TESTS
    // -------------------------------------------------------------------------

    @Test
    fun `youtube multi-step voice command extracts ONLY MrBeast and never full sentence`() {
        val command = "Go to home screen, open YouTube, search MrBeast, open a video and play it."
        val plan = NaturalLanguageActionPlanner.planCommand(command)

        assertNotNull("Multi-step YouTube command must produce a structured workflow plan", plan)
        val p = plan!!
        assertEquals("MrBeast", p.extractedSearchKeyword)
        assertNull("Safe playback must NOT require external confirmation", p.pendingExternalConfirmationPrompt)
        assertEquals(
            listOf(
                PlannedActionKind.GO_HOME,
                PlannedActionKind.OPEN_APP,
                PlannedActionKind.YOUTUBE_SEARCH,
                PlannedActionKind.YOUTUBE_OPEN_FIRST_VIDEO,
                PlannedActionKind.YOUTUBE_VERIFY_PLAYBACK
            ),
            p.steps.map { it.kind }
        )
        val searchStep = p.steps.first { it.kind == PlannedActionKind.YOUTUBE_SEARCH }
        assertEquals("MrBeast", searchStep.primaryArg)
        assertFalse(
            "Search query must NEVER contain surrounding navigation words",
            searchStep.primaryArg.contains("open", ignoreCase = true) ||
                searchStep.primaryArg.contains("youtube", ignoreCase = true) ||
                searchStep.primaryArg.contains("play", ignoreCase = true)
        )
    }

    @Test
    fun `short youtube search command extracts clean keyword`() {
        val kw1 = NaturalLanguageActionPlanner.extractCleanYouTubeSearchKeyword(
            "Open YouTube and search MrBeast."
        )
        assertEquals("MrBeast", kw1)

        val kw2 = NaturalLanguageActionPlanner.extractCleanYouTubeSearchKeyword(
            "Go to home screen, open YouTube, search lo-fi hip hop beats, open first video and play it"
        )
        assertEquals("lo-fi hip hop beats", kw2)
    }

    @Test
    fun `external youtube actions like comment or subscribe require explicit user confirmation`() {
        val likePlan = NaturalLanguageActionPlanner.planCommand(
            "Open YouTube, search MrBeast, open a video and like the video"
        )
        assertNotNull(likePlan)
        assertNotNull(
            "Liking a video is an external action requiring confirmation",
            likePlan!!.pendingExternalConfirmationPrompt
        )
        assertTrue(likePlan.steps.any { it.requiresConfirmation })

        val subPlan = NaturalLanguageActionPlanner.planCommand(
            "Open YouTube and subscribe to the channel"
        )
        assertNotNull(subPlan)
        assertNotNull(
            "Subscribing is an external action requiring confirmation",
            subPlan!!.pendingExternalConfirmationPrompt
        )
        assertTrue(subPlan.steps.any { it.requiresConfirmation })
    }

    // -------------------------------------------------------------------------
    // 2. PERSISTENT AI CHESS ENGINE TESTS
    // -------------------------------------------------------------------------

    @Test
    fun `chess engine enforces legal opening moves FEN round-trip and checkmate detection`() {
        val start = ChessBoardState.fromFen(ChessBoardState.INITIAL_FEN)
        val legalOpeningMoves = start.generateLegalMoves()
        assertEquals("Standard chess position has exactly 20 legal opening moves", 20, legalOpeningMoves.size)
        assertEquals(ChessOutcome.IN_PROGRESS, start.evaluateOutcome())

        // Scholar's Mate: 1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7#
        val afterE4 = start.applyUciMove("e2e4")!!
        val fenRoundTrip = ChessBoardState.fromFen(afterE4.toFen())
        assertEquals(afterE4.toFen(), fenRoundTrip.toFen())

        val state2 = fenRoundTrip.applyUciMove("e7e5")!!
        val state3 = state2.applyUciMove("d1h5")!!
        val state4 = state3.applyUciMove("b8c6")!!
        val state5 = state4.applyUciMove("f1c4")!!
        val state6 = state5.applyUciMove("g8f6")!!
        val checkmateState = state6.applyUciMove("h5f7")!!

        assertEquals(ChessOutcome.CHECKMATE_WHITE_WINS, checkmateState.evaluateOutcome())
        assertTrue(checkmateState.isKingInCheck(ChessColor.BLACK))
        assertTrue(checkmateState.generateLegalMoves().isEmpty())
    }

    @Test
    fun `chess AI returns legal moves across all 3 difficulty levels and parses voice moves`() {
        val afterE4 = ChessBoardState.fromFen(ChessBoardState.INITIAL_FEN).applyUciMove("e2e4")!!
        val legalBlackUcis = afterE4.generateLegalMoves().map { it.toUci() }.toSet()

        val casualMove = JarvisChessAiEngine.chooseBestMove(afterE4, CHESS_DIFFICULTY_CASUAL)
        val tacticalMove = JarvisChessAiEngine.chooseBestMove(afterE4, CHESS_DIFFICULTY_TACTICAL)
        val masterMove = JarvisChessAiEngine.chooseBestMove(afterE4, CHESS_DIFFICULTY_MASTER)

        assertNotNull(casualMove)
        assertNotNull(tacticalMove)
        assertNotNull(masterMove)
        assertTrue(casualMove!!.toUci() in legalBlackUcis)
        assertTrue(tacticalMove!!.toUci() in legalBlackUcis)
        assertTrue(masterMove!!.toUci() in legalBlackUcis)

        assertEquals("e2e4", JarvisChessAiEngine.parseVoiceMove("e2 to e4"))
        assertEquals("g1f3", JarvisChessAiEngine.parseVoiceMove("move g1 to f3"))
        assertEquals("e7e8q", JarvisChessAiEngine.parseVoiceMove("e7 e8 queen"))
    }

    // -------------------------------------------------------------------------
    // 3. YOUTUBE URL PARSING, ISO DURATION, UTC TIME & MILESTONE DEDUP TESTS
    // -------------------------------------------------------------------------

    @Test
    fun `youtube video id parser handles standard shorts short-link and raw IDs`() {
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeAuthAndApiClient.extractYouTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
        assertEquals(
            "abc123XYZ_-",
            YouTubeAuthAndApiClient.extractYouTubeVideoId("https://youtube.com/shorts/abc123XYZ_-?feature=share")
        )
        assertEquals(
            "9bZkp7q19f0",
            YouTubeAuthAndApiClient.extractYouTubeVideoId("https://youtu.be/9bZkp7q19f0")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeAuthAndApiClient.extractYouTubeVideoId("dQw4w9WgXcQ")
        )
        assertNull(YouTubeAuthAndApiClient.extractYouTubeVideoId("not-a-valid-id"))

        assertEquals("12:05", YouTubeAuthAndApiClient.formatIsoDuration("PT12M5S"))
        assertEquals("1:02:09", YouTubeAuthAndApiClient.formatIsoDuration("PT1H2M9S"))
        assertTrue(
            YouTubeAuthAndApiClient.toUtcIso8601(1_760_000_000_000L)
                .matches(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$"))
        )
    }

    @Test
    fun `milestone monitor detects newly crossed thresholds and permanently blocks duplicates`() {
        val thresholds = listOf(100L, 500L, 1_000L, 5_000L, 10_000L)
        val reached = mutableSetOf<Long>()

        val firstPass = YouTubeMilestoneMonitorScheduler.detectNewCrossedMilestones(
            currentViews = 1_250L,
            configuredMilestones = thresholds,
            alreadyReached = reached
        )
        assertEquals(listOf(100L, 500L, 1_000L), firstPass)
        reached.addAll(firstPass)

        // Second check at 1,400 views must NOT trigger duplicate calls for 100 / 500 / 1,000
        val secondPass = YouTubeMilestoneMonitorScheduler.detectNewCrossedMilestones(
            currentViews = 1_400L,
            configuredMilestones = thresholds,
            alreadyReached = reached
        )
        assertTrue("Already-reached milestones must never re-trigger", secondPass.isEmpty())

        // Third check at 5,100 views triggers ONLY 5,000
        val thirdPass = YouTubeMilestoneMonitorScheduler.detectNewCrossedMilestones(
            currentViews = 5_100L,
            configuredMilestones = thresholds,
            alreadyReached = reached
        )
        assertEquals(listOf(5_000L), thirdPass)
    }

    // -------------------------------------------------------------------------
    // 4. AI CLIPPING STUDIO NON-OVERLAPPING 4-CLIP & "GENERATE 4 MORE" TESTS
    // -------------------------------------------------------------------------

    @Test
    fun `clipping studio selects 4 distinct clips on batch 1 and 4 fresh non-overlapping clips on batch 2`() {
        val engine = VideoClippingEngine(context)
        val totalDurationMs = 240_000L // 4-minute video
        val preferredLenMs = 20_000L

        val batch1 = engine.computeDeterministicNonOverlappingMoments(
            durationMs = totalDurationMs,
            preferredLenMs = preferredLenMs,
            excludedRanges = emptyList()
        )
        assertEquals("Batch 1 must produce exactly 4 clips", 4, batch1.size)
        for (i in batch1.indices) {
            for (j in i + 1 until batch1.size) {
                assertTrue(
                    "Batch 1 clips must not heavily overlap",
                    batch1[i].endMs <= batch1[j].startMs || batch1[j].endMs <= batch1[i].startMs
                )
            }
        }

        val excluded = batch1.map { it.startMs to it.endMs }
        val batch2 = engine.computeDeterministicNonOverlappingMoments(
            durationMs = totalDurationMs,
            preferredLenMs = preferredLenMs,
            excludedRanges = excluded
        )
        assertEquals("Generate 4 More Clips must produce 4 fresh clips", 4, batch2.size)
        for (c2 in batch2) {
            for ((s1, e1) in excluded) {
                assertTrue(
                    "Batch 2 clip [${c2.startMs},${c2.endMs}] must avoid Batch 1 [$s1,$e1]",
                    c2.endMs <= s1 || c2.startMs >= e1
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // 5. ENCRYPTED CREDENTIAL VAULT SECURITY TESTS
    // -------------------------------------------------------------------------

    @Test
    fun `secure credential vault encrypts secrets and wipes oauth tokens cleanly on disconnect`() {
        val vault = SecureCredentialVault.getInstance(context)
        val base = vault.loadConfig()
        vault.saveConfig(
            base.copy(
                apiKey = "AIzaSyTestKey1234567890",
                oauthClientId = "1234567890-testclient.apps.googleusercontent.com"
            )
        )
        vault.updateOAuthTokensAndChannel(
            accessToken = "ya29.test_access_token",
            refreshToken = "1//test_refresh_token",
            expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
            channelId = "UC_TEST_CHANNEL_01",
            channelName = "Mozahid Creator Lab",
            channelHandle = "@mozahidlab",
            channelThumbnailUrl = "https://example.com/avatar.jpg",
            subscriberCount = 12_500L,
            totalViews = 480_000L,
            videoCount = 64L
        )

        val loaded = vault.loadConfig()
        assertEquals("AIzaSyTestKey1234567890", loaded.apiKey)
        assertEquals("1234567890-testclient.apps.googleusercontent.com", loaded.oauthClientId)
        assertEquals("ya29.test_access_token", loaded.accessToken)
        assertEquals("1//test_refresh_token", loaded.refreshToken)
        assertEquals("UC_TEST_CHANNEL_01", loaded.channelId)
        assertTrue(loaded.isAccountAuthorized)

        // Disconnect must wipe all OAuth tokens & verified channel binding while keeping API key intact
        vault.disconnectYouTubeAccount()
        val afterDisconnect = vault.loadConfig()
        assertFalse(afterDisconnect.isAccountAuthorized)
        assertEquals("", afterDisconnect.accessToken)
        assertEquals("", afterDisconnect.refreshToken)
        assertEquals("", afterDisconnect.channelId)
        assertEquals("AIzaSyTestKey1234567890", afterDisconnect.apiKey)
    }

    // -------------------------------------------------------------------------
    // 6. ROOM DATABASE V6 PERSISTENCE TESTS (ALL NEW ENTITIES)
    // -------------------------------------------------------------------------

    @Test
    fun `room v6 database persists projects chess games monitored videos commands and strategies`() = runBlocking {
        // 1. Infinite Project Memory
        val projectId = repo.saveProject(
            ProjectMemoryEntity(
                title = "JARVIS v2 Launch",
                description = "Ship Creator Control Center",
                progressSummary = "Verified OAuth PKCE & Clipping Studio"
            ),
            setActive = true
        )
        assertTrue(projectId > 0L)
        val activeProject = repo.getActiveProject()
        assertNotNull(activeProject)
        assertEquals("JARVIS v2 Launch", activeProject!!.title)
        assertTrue(activeProject.progressSummary.contains("Verified OAuth PKCE"))

        // 2. Persistent Chess Game
        val gameId = repo.saveChessGame(
            ChessGameEntity(
                title = "Match vs JARVIS Master",
                difficulty = CHESS_DIFFICULTY_MASTER,
                fen = ChessBoardState.INITIAL_FEN,
                movesHistoryCsv = "e2e4,e7e5",
                moveCount = 2
            )
        )
        val loadedGame = repo.getLatestActiveChessGame()
        assertNotNull(loadedGame)
        assertEquals(gameId, loadedGame!!.id)
        assertEquals(2, loadedGame.moveCount)

        // 3. Monitored YouTube Video + Snapshot
        repo.upsertMonitoredVideo(
            MonitoredYouTubeVideoEntity(
                videoId = "dQw4w9WgXcQ",
                title = "Launch Trailer",
                currentViews = 1_050L,
                reachedMilestonesCsv = "100,500,1000"
            )
        )
        repo.recordVideoViewSnapshot("dQw4w9WgXcQ", 1_050L, 120L, 18L)
        val activeVideos = repo.getActiveMonitoredVideos()
        assertEquals(1, activeVideos.size)
        assertEquals("100,500,1000", activeVideos.first().reachedMilestonesCsv)
        assertEquals(1, repo.getViewHistoryForVideo("dQw4w9WgXcQ").size)

        // 4. Personal Command Language
        repo.savePersonalCommand(
            PersonalCommandEntity(
                triggerPhrase = "creator desk",
                workflowStepsText = "Open YouTube Studio -> Check Analytics"
            )
        )
        val commands = repo.getAllPersonalCommands()
        assertEquals(1, commands.size)
        assertEquals("creator desk", commands.first().triggerPhrase)

        // 5. Self-Improvement Engine (approval gate for critical settings preserved)
        repo.saveExecutionStrategy(
            ExecutionStrategyEntity(
                taskPattern = "youtube_search_play",
                failedReason = "Popup overlay intercepted tap",
                improvedStrategy = "Dismiss popup and retry first clickable video card",
                requiresUserApproval = true,
                approvedByUser = false,
                successCount = 1
            )
        )
        val strategies = repo.getAllExecutionStrategies()
        assertEquals(1, strategies.size)
        assertEquals(1, strategies.first().successCount)
        assertTrue(strategies.first().requiresUserApproval)
        assertFalse(strategies.first().approvedByUser)

        // 6. Knowledge Graph Nodes & Edges
        val n1 = repo.upsertKnowledgeNode("JARVIS Creator Hub", "PROJECT", "Core YouTube suite")
        val n2 = repo.upsertKnowledgeNode("Milestone Monitor", "TASK", "WorkManager view checker")
        val edgeId = repo.linkKnowledgeNodes(n1, n2, "PART_OF")
        assertTrue(edgeId > 0L)
        assertEquals(2, repo.getKnowledgeNodes().size)
    }
}
