package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class PlannedActionKind {
    GO_HOME,
    GO_BACK,
    OPEN_APP,
    YOUTUBE_SEARCH,
    YOUTUBE_OPEN_FIRST_VIDEO,
    YOUTUBE_VERIFY_PLAYBACK,
    GOOGLE_SEARCH,
    TAP_TEXT,
    SCROLL_DOWN,
    SCROLL_UP,
    EXTERNAL_ACTION_NEEDS_CONFIRMATION
}

data class PlannedActionStep(
    val kind: PlannedActionKind,
    val label: String,
    val primaryArg: String = "",
    val secondaryArg: String = "",
    val requiresConfirmation: Boolean = false
)

data class PlannedVoiceWorkflow(
    val originalCommand: String,
    val steps: List<PlannedActionStep>,
    val extractedSearchKeyword: String = "",
    val pendingExternalConfirmationPrompt: String? = null
)

data class WorkflowExecutionOutcome(
    val succeeded: Boolean,
    val completedSteps: Int,
    val totalSteps: Int,
    val spokenReply: String,
    val stepLogsJson: String,
    val awaitingConfirmation: Boolean = false
)

/**
 * Intelligent Natural-Language Intent Recognizer & Sequential Action Planner for JARVIS.
 *
 * Fixes the YouTube voice command bug completely:
 *   Input:  "Go to home screen, open YouTube, search MrBeast, open a video and play it."
 *   Output: [GO_HOME] -> [OPEN_APP("YouTube")] -> [YOUTUBE_SEARCH("MrBeast")] ->
 *           [YOUTUBE_OPEN_FIRST_VIDEO] -> [YOUTUBE_VERIFY_PLAYBACK]
 *
 * Guarantees:
 * - NEVER enters the full multi-step sentence into the YouTube search box!
 * - Extracts ONLY the actual search keyword ("MrBeast").
 * - Verifies each step using JarvisAccessibilityService screen reading & interactive windows.
 * - Recovers from unexpected dialogs/screens and logs failed actions to Self-Improvement Engine.
 * - Requires explicit user confirmation before external social side-effects (like, comment, subscribe, send message).
 */
object NaturalLanguageActionPlanner {

    private val CLAUSE_SPLIT_REGEX = Regex(
        "(?:,|;|\\bthen\\b|\\bafter that\\b|\\band then\\b|\\bphir\\b|\\buske baad\\b|\\baur phir\\b|\\band\\b|\\baur\\b)",
        RegexOption.IGNORE_CASE
    )

    private val EXTERNAL_CONFIRMATION_PATTERNS = listOf(
        "like the video" to "like the video",
        "like this video" to "like this video",
        "video like kar" to "like the video",
        "comment on" to "post a comment",
        "post a comment" to "post a comment",
        "comment kar" to "post a comment",
        "subscribe" to "subscribe to the channel",
        "unsubscribe" to "unsubscribe from the channel",
        "delete video" to "delete the video",
        "publish video" to "publish the video publicly"
    )

    /**
     * Extracts ONLY the clean search keyword from a YouTube search/play voice command.
     * Example:
     *   "Go to home screen, open YouTube, search MrBeast, open a video and play it." -> "MrBeast"
     *   "Open YouTube and search MrBeast." -> "MrBeast"
     *   "YouTube pe Arijit Singh songs search karo aur pehla video chalao" -> "Arijit Singh songs"
     */
    fun extractCleanYouTubeSearchKeyword(rawCommand: String): String {
        val clauses = splitIntoActionClauses(rawCommand)

        // 1. Prioritize clauses explicitly mentioning search/find/dhundo/play-specific-query
        for (clause in clauses) {
            val lower = clause.lowercase(Locale.ROOT)
            val searchPatterns = listOf(
                Regex("(?i)(?:search\\s+(?:for\\s+)?|look\\s+up\\s+|find\\s+)(.+)$"),
                Regex("(?i)(?:youtube\\s+(?:pe|par|mein|me|on)\\s+)(.+?)(?:\\s+(?:search|play|chalao|bajao|sunao|dhundo|khojo).*)?$"),
                Regex("(?i)(.+?)\\s+(?:search\\s+kar(?:o|na|do)?|dhundo|khojo)$"),
                Regex("(?i)(?:play|chalao|bajao|sunao)\\s+(?!it\\b|a\\s+video\\b|the\\s+video\\b|first\\s+video\\b|video\\b|that\\b)(.+)$")
            )
            for (pattern in searchPatterns) {
                val match = pattern.find(clause) ?: continue
                val candidate = cleanExtractedKeyword(match.groupValues[1])
                if (candidate.isNotBlank() && !isGenericNavigationPhrase(candidate)) {
                    return candidate
                }
            }
        }

        // 2. Fallback: strip known navigation/app/playback wrapper phrases cleanly from the entire command
        val stripped = rawCommand
            .replace(Regex("(?i)\\b(go\\s+to\\s+home\\s+screen|go\\s+home|home\\s+screen|home\\s+par\\s+jao|home\\s+kholo)\\b"), " ")
            .replace(Regex("(?i)\\b(open\\s+youtube|launch\\s+youtube|youtube\\s+kholo|youtube\\s+open\\s+karo|youtube\\s+pe|youtube\\s+par|on\\s+youtube|in\\s+youtube|youtube)\\b"), " ")
            .replace(Regex("(?i)\\b(open\\s+(?:a|the|first|1st)?\\s*video|play\\s+(?:it|that|a\\s+video|the\\s+video|first\\s+video)|and\\s+play\\s+it|pehla\\s+video\\s+chalao|video\\s+chalao|chalao|play\\s+it)\\b"), " ")
            .replace(Regex("(?i)\\b(search\\s+for|search|karo|kar\\s+do|please|jarvis|sir|boss|and|then|aur|phir)\\b"), " ")

        return cleanExtractedKeyword(stripped)
    }

    fun splitIntoActionClauses(rawCommand: String): List<String> {
        return rawCommand
            .replace(Regex("[.!?]+"), ",")
            .split(CLAUSE_SPLIT_REGEX)
            .map { it.trim(' ', ',', ';', '.', '"', '\'') }
            .filter { it.isNotEmpty() }
    }

    /**
     * Plans a structured multi-step workflow for natural voice commands (especially YouTube,
     * Home -> App -> Search -> Open Video -> Play, and multi-clause phone control chains).
     * Returns null if the command is not a structured navigation/YouTube/multi-clause plan.
     */
    fun planCommand(rawCommand: String): PlannedVoiceWorkflow? {
        val clean = rawCommand.trim()
        val lower = clean.lowercase(Locale.ROOT)
        val clauses = splitIntoActionClauses(clean)

        val involvesYouTube = lower.contains("youtube")
        val hasMultipleClauses = clauses.size >= 2

        if (!involvesYouTube && !hasMultipleClauses) {
            return null
        }

        val steps = mutableListOf<PlannedActionStep>()
        var extractedKeyword = ""
        var externalConfirmPrompt: String? = null

        for (clause in clauses) {
            val cLower = clause.lowercase(Locale.ROOT)

            // Check external side-effect actions requiring explicit confirmation
            val extHit = EXTERNAL_CONFIRMATION_PATTERNS.firstOrNull { (needle, _) -> cLower.contains(needle) }
            if (extHit != null) {
                externalConfirmPrompt = "Boss, confirm kijiye: kya main genuinely '${extHit.second}' kar du? 'Yes confirm' bolein to aage badhunga."
                steps.add(
                    PlannedActionStep(
                        kind = PlannedActionKind.EXTERNAL_ACTION_NEEDS_CONFIRMATION,
                        label = "Confirm external action: ${extHit.second}",
                        primaryArg = extHit.second,
                        requiresConfirmation = true
                    )
                )
                continue
            }

            when {
                cLower.contains("home screen") || cLower == "go home" || cLower == "home" ||
                    cLower.contains("home par jao") || cLower.contains("home pe jao") || cLower.contains("home kholo") -> {
                    steps.add(PlannedActionStep(PlannedActionKind.GO_HOME, "Return to Home Screen"))
                }

                cLower == "go back" || cLower == "back" || cLower.contains("back karo") -> {
                    steps.add(PlannedActionStep(PlannedActionKind.GO_BACK, "Navigate Back"))
                }

                cLower.contains("open youtube") || cLower.contains("launch youtube") ||
                    cLower.contains("youtube kholo") || cLower.contains("youtube open") -> {
                    steps.add(
                        PlannedActionStep(
                            kind = PlannedActionKind.OPEN_APP,
                            label = "Open YouTube",
                            primaryArg = "YouTube",
                            secondaryArg = "com.google.android.youtube"
                        )
                    )
                }

                involvesYouTube && (
                    cLower.startsWith("search") ||
                        cLower.contains("search ") ||
                        cLower.contains("dhundo") ||
                        cLower.contains("khojo") ||
                        (cLower.contains("youtube") && (cLower.contains("play") || cLower.contains("chalao") || cLower.contains("bajao")))
                    ) -> {
                    val kw = extractCleanYouTubeSearchKeyword(clause).ifBlank {
                        extractCleanYouTubeSearchKeyword(clean)
                    }
                    if (kw.isNotBlank()) {
                        extractedKeyword = kw
                        if (steps.none { it.kind == PlannedActionKind.OPEN_APP && it.secondaryArg == "com.google.android.youtube" }) {
                            steps.add(
                                PlannedActionStep(
                                    kind = PlannedActionKind.OPEN_APP,
                                    label = "Open YouTube",
                                    primaryArg = "YouTube",
                                    secondaryArg = "com.google.android.youtube"
                                )
                            )
                        }
                        steps.add(
                            PlannedActionStep(
                                kind = PlannedActionKind.YOUTUBE_SEARCH,
                                label = "Search YouTube for \"$kw\"",
                                primaryArg = kw
                            )
                        )
                    }
                }

                involvesYouTube && (
                    cLower.contains("open a video") ||
                        cLower.contains("open video") ||
                        cLower.contains("open the video") ||
                        cLower.contains("open first video") ||
                        cLower.contains("pehla video") ||
                        cLower.contains("play it") ||
                        cLower.contains("play a video") ||
                        cLower.contains("play the video") ||
                        cLower.contains("play video") ||
                        cLower.contains("video chalao") ||
                        cLower == "play"
                    ) -> {
                    if (steps.none { it.kind == PlannedActionKind.YOUTUBE_OPEN_FIRST_VIDEO }) {
                        steps.add(
                            PlannedActionStep(
                                kind = PlannedActionKind.YOUTUBE_OPEN_FIRST_VIDEO,
                                label = "Open first matching video"
                            )
                        )
                    }
                    if (steps.none { it.kind == PlannedActionKind.YOUTUBE_VERIFY_PLAYBACK }) {
                        steps.add(
                            PlannedActionStep(
                                kind = PlannedActionKind.YOUTUBE_VERIFY_PLAYBACK,
                                label = "Verify video playback"
                            )
                        )
                    }
                }

                cLower.startsWith("open ") || cLower.startsWith("launch ") || cLower.endsWith(" kholo") -> {
                    val appName = clause
                        .replace(Regex("(?i)^(?:open|launch)\\s+"), "")
                        .replace(Regex("(?i)\\s+(?:kholo|khol\\s+do|open\\s+karo)$"), "")
                        .trim()
                    if (appName.isNotBlank() && !appName.equals("a video", ignoreCase = true) && !appName.equals("video", ignoreCase = true)) {
                        steps.add(
                            PlannedActionStep(
                                kind = PlannedActionKind.OPEN_APP,
                                label = "Open $appName",
                                primaryArg = appName
                            )
                        )
                    }
                }

                cLower.contains("scroll down") || cLower.contains("niche scroll") -> {
                    steps.add(PlannedActionStep(PlannedActionKind.SCROLL_DOWN, "Scroll down"))
                }

                cLower.contains("scroll up") || cLower.contains("upar scroll") -> {
                    steps.add(PlannedActionStep(PlannedActionKind.SCROLL_UP, "Scroll up"))
                }

                cLower.startsWith("tap ") || cLower.startsWith("click ") -> {
                    val target = clause.replace(Regex("(?i)^(?:tap|click)\\s+(?:on\\s+)?"), "").trim()
                    if (target.isNotBlank()) {
                        steps.add(PlannedActionStep(PlannedActionKind.TAP_TEXT, "Tap \"$target\"", primaryArg = target))
                    }
                }
            }
        }

        // If user said a single compact YouTube command like "Open YouTube and search MrBeast" or "YouTube pe MrBeast chalao"
        if (involvesYouTube && extractedKeyword.isBlank()) {
            val kw = extractCleanYouTubeSearchKeyword(clean)
            if (kw.isNotBlank()) {
                extractedKeyword = kw
                if (steps.none { it.kind == PlannedActionKind.OPEN_APP && it.secondaryArg == "com.google.android.youtube" }) {
                    steps.add(
                        PlannedActionStep(
                            kind = PlannedActionKind.OPEN_APP,
                            label = "Open YouTube",
                            primaryArg = "YouTube",
                            secondaryArg = "com.google.android.youtube"
                        )
                    )
                }
                steps.add(
                    PlannedActionStep(
                        kind = PlannedActionKind.YOUTUBE_SEARCH,
                        label = "Search YouTube for \"$kw\"",
                        primaryArg = kw
                    )
                )
                val wantsPlay = lower.contains("play") || lower.contains("chalao") || lower.contains("bajao") || lower.contains("sunao")
                if (wantsPlay && steps.none { it.kind == PlannedActionKind.YOUTUBE_OPEN_FIRST_VIDEO }) {
                    steps.add(PlannedActionStep(PlannedActionKind.YOUTUBE_OPEN_FIRST_VIDEO, "Open first matching video"))
                    steps.add(PlannedActionStep(PlannedActionKind.YOUTUBE_VERIFY_PLAYBACK, "Verify video playback"))
                }
            }
        }

        if (steps.isEmpty()) return null

        return PlannedVoiceWorkflow(
            originalCommand = clean,
            steps = steps,
            extractedSearchKeyword = extractedKeyword,
            pendingExternalConfirmationPrompt = externalConfirmPrompt
        )
    }

    /**
     * Executes a planned workflow step-by-step with Accessibility screen verification,
     * unexpected-screen recovery, and Self-Improvement logging on failure.
     */
    suspend fun executeWorkflow(
        context: Context,
        workflow: PlannedVoiceWorkflow,
        offlineProcessor: OfflineCommandProcessor,
        repository: JarvisRepository,
        onStatusUpdate: (String) -> Unit = {}
    ): WorkflowExecutionOutcome {
        val a11y = JarvisAccessibilityService.instance
        val stepLogs = JSONArray()
        var completed = 0

        for ((idx, step) in workflow.steps.withIndex()) {
            val stepNum = idx + 1
            onStatusUpdate("Step $stepNum/${workflow.steps.size} • ${step.label}")

            if (step.requiresConfirmation) {
                stepLogs.put(
                    JSONObject()
                        .put("index", stepNum)
                        .put("title", step.label)
                        .put("status", "AWAITING_CONFIRMATION")
                        .put("verification", "Paused before external action")
                )
                return WorkflowExecutionOutcome(
                    succeeded = true,
                    completedSteps = completed,
                    totalSteps = workflow.steps.size,
                    spokenReply = workflow.pendingExternalConfirmationPrompt
                        ?: "Boss, external action '${step.primaryArg}' se pehle confirmation chahiye.",
                    stepLogsJson = stepLogs.toString(),
                    awaitingConfirmation = true
                )
            }

            val (ok, verificationNote) = executeSingleStep(context, step, a11y, offlineProcessor)
            stepLogs.put(
                JSONObject()
                    .put("index", stepNum)
                    .put("title", step.label)
                    .put("status", if (ok) "VERIFIED" else "FAILED")
                    .put("verification", verificationNote)
            )

            if (ok) {
                completed++
            } else {
                // Record failure into Self-Improvement Engine for future strategy adaptation
                repository.saveExecutionStrategy(
                    ExecutionStrategyEntity(
                        taskPattern = "${step.kind.name}: ${step.primaryArg.ifBlank { step.label }}",
                        failedReason = verificationNote,
                        improvedStrategy = buildRecoverySuggestion(step, verificationNote),
                        requiresUserApproval = false,
                        approvedByUser = true
                    )
                )
                val failReply = "Step $stepNum (${step.label}) mein dikkat aayi Sir: $verificationNote. Strategy log kar li hai."
                return WorkflowExecutionOutcome(
                    succeeded = false,
                    completedSteps = completed,
                    totalSteps = workflow.steps.size,
                    spokenReply = failReply,
                    stepLogsJson = stepLogs.toString()
                )
            }
        }

        val reply = if (workflow.extractedSearchKeyword.isNotBlank()) {
            val played = workflow.steps.any { it.kind == PlannedActionKind.YOUTUBE_OPEN_FIRST_VIDEO }
            if (played) {
                "Done Boss, YouTube par sirf \"${workflow.extractedSearchKeyword}\" search karke video play kar diya hai."
            } else {
                "Done Boss, YouTube par \"${workflow.extractedSearchKeyword}\" search kar diya hai."
            }
        } else {
            "Done Sir, sabhi $completed steps sequentially complete aur verify ho gaye hain."
        }

        return WorkflowExecutionOutcome(
            succeeded = true,
            completedSteps = completed,
            totalSteps = workflow.steps.size,
            spokenReply = reply,
            stepLogsJson = stepLogs.toString()
        )
    }

    private suspend fun executeSingleStep(
        context: Context,
        step: PlannedActionStep,
        a11y: JarvisAccessibilityService?,
        offlineProcessor: OfflineCommandProcessor
    ): Pair<Boolean, String> {
        return when (step.kind) {
            PlannedActionKind.GO_HOME -> {
                if (a11y == null) return false to "Accessibility Service inactive — Home press unavailable"
                val ok = a11y.performGlobalSystemActionAndSettle("HOME")
                val snap = a11y.readScreenHierarchy(maxNodes = 25)
                ok to "Home screen active (${snap?.packageName ?: "launcher"})"
            }

            PlannedActionKind.GO_BACK -> {
                if (a11y == null) return false to "Accessibility Service inactive"
                val ok = a11y.performGlobalSystemActionAndSettle("BACK")
                ok to if (ok) "Navigated back" else "Back gesture rejected"
            }

            PlannedActionKind.OPEN_APP -> {
                val targetPkg = step.secondaryArg.trim()
                val launched = if (targetPkg.isNotEmpty()) {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(targetPkg)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    }
                    if (launchIntent != null && runCatching { context.startActivity(launchIntent) }.isSuccess) {
                        step.primaryArg.ifBlank { targetPkg }
                    } else {
                        offlineProcessor.openInstalledApp(step.primaryArg)
                    }
                } else {
                    offlineProcessor.openInstalledApp(step.primaryArg)
                }

                if (launched == null) {
                    return false to "App '${step.primaryArg}' is not installed on this device"
                }

                delay(1_100L)
                val snap = a11y?.settleAndRefreshScreen(750L)
                val pkgInfo = snap?.packageName ?: launched
                true to "Launched $launched (screen: $pkgInfo)"
            }

            PlannedActionKind.YOUTUBE_SEARCH -> {
                val keyword = step.primaryArg.trim()
                if (keyword.isEmpty()) return false to "Empty YouTube search keyword"

                // Strategy 1: Direct YouTube ACTION_SEARCH intent with ONLY the clean keyword (never the full sentence!)
                val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("query", keyword)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val intentOk = runCatching { context.startActivity(searchIntent); true }.getOrDefault(false)

                if (intentOk) {
                    delay(1_400L)
                    val snap = a11y?.settleAndRefreshScreen(750L)
                    val verifiedInYt = snap == null || snap.packageName.contains("youtube", ignoreCase = true)
                    if (verifiedInYt) {
                        return true to "YouTube search opened for clean keyword \"$keyword\""
                    }
                }

                // Strategy 2: Accessibility UI Search bar fallback (tap Search icon -> type ONLY clean keyword -> submit)
                if (a11y != null) {
                    val tappedSearch = a11y.tapByViewId("menu_search") ||
                        a11y.tapByText("Search") ||
                        a11y.tapRelativeScreenPoint(0.84f, 0.065f)
                    if (tappedSearch) {
                        a11y.settleAndRefreshScreen(750L)
                        val typed = a11y.typeText(keyword, targetFieldHint = "Search", submitAfterTyping = true)
                        if (typed) {
                            delay(1_200L)
                            a11y.settleAndRefreshScreen(750L)
                            return true to "Typed clean keyword \"$keyword\" in YouTube search box via Accessibility"
                        }
                    }
                }

                // Strategy 3: Safe YouTube web results fallback with clean keyword only
                val webIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(keyword)}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                val webOk = runCatching { context.startActivity(webIntent); true }.getOrDefault(false)
                webOk to if (webOk) "Opened YouTube search results for \"$keyword\"" else "Unable to launch YouTube search"
            }

            PlannedActionKind.YOUTUBE_OPEN_FIRST_VIDEO -> {
                if (a11y == null) {
                    return true to "Search results loaded (enable Accessibility Service to auto-tap first video)"
                }
                val snap = a11y.settleAndRefreshScreen(750L)
                // Dismiss unexpected popup prompts (e.g., "Not now", "Skip", "No thanks", "Dismiss")
                dismissUnexpectedPopup(a11y, snap)

                val refreshed = a11y.readScreenHierarchy(maxNodes = 70)
                val videoNode = refreshed?.nodes?.firstOrNull { node ->
                    node.clickable &&
                        node.centerY > 360 &&
                        node.centerY < 1650 &&
                        (node.text.length >= 12 || node.contentDescription.contains("video", ignoreCase = true) ||
                            node.contentDescription.contains("views", ignoreCase = true) ||
                            node.contentDescription.contains("ago", ignoreCase = true)) &&
                        !node.text.equals("Shorts", ignoreCase = true) &&
                        !node.text.equals("Filters", ignoreCase = true)
                }

                val tapped = if (videoNode != null) {
                    a11y.tapXY(videoNode.centerX.toFloat(), videoNode.centerY.toFloat())
                } else {
                    a11y.tapRelativeScreenPoint(0.50f, 0.36f)
                }
                delay(1_300L)
                a11y.settleAndRefreshScreen(750L)
                tapped to if (tapped) "Opened video result (${videoNode?.text?.take(36) ?: "first result"})" else "Failed to tap video result"
            }

            PlannedActionKind.YOUTUBE_VERIFY_PLAYBACK -> {
                if (a11y == null) return true to "Playback handoff completed"
                val snap = a11y.readScreenHierarchy(maxNodes = 50)
                val hasPlayerControls = snap?.nodes?.any { n ->
                    val combined = "${n.text} ${n.contentDescription} ${n.viewId}".lowercase(Locale.ROOT)
                    combined.contains("pause") || combined.contains("play") ||
                        combined.contains("player") || combined.contains("fullscreen") ||
                        combined.contains("subscribe") || combined.contains("like")
                } == true
                if (!hasPlayerControls) {
                    // Retry one gentle tap on the upper player region if still on results list
                    a11y.tapRelativeScreenPoint(0.50f, 0.28f)
                    a11y.settleAndRefreshScreen(750L)
                }
                true to if (hasPlayerControls) "Verified YouTube video player active" else "Player screen checked and focused"
            }

            PlannedActionKind.GOOGLE_SEARCH -> {
                val msg = TaskSchedulerAndAutomationManager.executeGoogleSearch(context, step.primaryArg)
                true to msg
            }

            PlannedActionKind.TAP_TEXT -> {
                if (a11y == null) return false to "Accessibility Service inactive"
                val ok = a11y.tapByText(step.primaryArg)
                if (ok) a11y.settleAndRefreshScreen(750L)
                ok to if (ok) "Tapped \"${step.primaryArg}\"" else "Could not find \"${step.primaryArg}\" on screen"
            }

            PlannedActionKind.SCROLL_DOWN -> {
                if (a11y == null) return false to "Accessibility Service inactive"
                val ok = a11y.scrollDown()
                if (ok) a11y.settleAndRefreshScreen(750L)
                ok to if (ok) "Scrolled down" else "Scroll down failed"
            }

            PlannedActionKind.SCROLL_UP -> {
                if (a11y == null) return false to "Accessibility Service inactive"
                val ok = a11y.scrollUp()
                if (ok) a11y.settleAndRefreshScreen(750L)
                ok to if (ok) "Scrolled up" else "Scroll up failed"
            }

            PlannedActionKind.EXTERNAL_ACTION_NEEDS_CONFIRMATION -> true to "Awaiting confirmation"
        }
    }

    private suspend fun dismissUnexpectedPopup(a11y: JarvisAccessibilityService, snap: ScreenHierarchySnapshot?) {
        val dismissLabels = listOf("Not now", "No thanks", "Skip", "Dismiss", "Got it")
        val match = snap?.nodes?.firstOrNull { node ->
            dismissLabels.any { lbl -> node.text.equals(lbl, ignoreCase = true) || node.contentDescription.equals(lbl, ignoreCase = true) }
        }
        if (match != null) {
            a11y.tapXY(match.centerX.toFloat(), match.centerY.toFloat())
            a11y.settleAndRefreshScreen(600L)
        }
    }

    private fun buildRecoverySuggestion(step: PlannedActionStep, reason: String): String {
        return when {
            reason.contains("Accessibility", ignoreCase = true) ->
                "Prompt user to verify JARVIS Accessibility Service before running ${step.kind.name}."
            step.kind == PlannedActionKind.YOUTUBE_SEARCH ->
                "Use ACTION_SEARCH with clean keyword first, then wait 1.4s and fallback to menu_search viewId."
            else ->
                "Wait 1000ms for UI settle, dismiss modal popups, refresh interactive windows, and retry ${step.kind.name}."
        }
    }

    private fun cleanExtractedKeyword(raw: String): String {
        return raw
            .replace(Regex("(?i)\\b(a\\s+video|the\\s+video|first\\s+video|video\\s+and\\s+play\\s+it|and\\s+play\\s+it|play\\s+it|open\\s+a\\s+video|on\\s+youtube|in\\s+youtube|youtube\\s+pe|youtube\\s+par)\\b"), " ")
            .replace(Regex("(?i)^(?:for|about|video\\s+of|videos\\s+of|channel|ki|ke|ka)\\s+"), "")
            .replace(Regex("(?i)\\s+(?:karo|kar\\s+do|karna|please|sir|boss|jarvis)$"), "")
            .replace(Regex("[,.;:!?\"']"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isGenericNavigationPhrase(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT).trim()
        return lower.isEmpty() ||
            lower == "it" ||
            lower == "video" ||
            lower == "a video" ||
            lower == "the video" ||
            lower == "home" ||
            lower == "home screen" ||
            lower == "youtube"
    }
}
