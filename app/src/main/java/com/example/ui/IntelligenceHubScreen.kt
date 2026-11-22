package com.example.ui

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PsychologyAlt
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ChessGameEntity
import com.example.ChessMove
import com.example.CustomMacroEntity
import com.example.ExecutionStrategyEntity
import com.example.KnowledgeEdgeEntity
import com.example.KnowledgeNodeEntity
import com.example.MissionRunEntity
import com.example.PersonalCommandEntity
import com.example.ProjectMemoryEntity
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.DeepHudNavy
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.SignalCrimson
import com.example.ui.theme.SignalGreen
import com.example.ui.theme.StarkAmber
import org.json.JSONArray
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

const val INTEL_TAB_PROJECTS = 0
const val INTEL_TAB_MISSION = 1
const val INTEL_TAB_DEV = 2
const val INTEL_TAB_WHAT_IF = 3
const val INTEL_TAB_GRAPH = 4
const val INTEL_TAB_IMPROVE = 5
const val INTEL_TAB_COMMANDS = 6
const val INTEL_TAB_CHESS = 7
const val INTEL_TAB_MACROS = 8

private data class IntelTabItem(val title: String, val icon: ImageVector)

private val INTEL_TABS = listOf(
    IntelTabItem("Projects", Icons.Filled.FolderSpecial),
    IntelTabItem("Mission", Icons.Filled.RocketLaunch),
    IntelTabItem("Dev", Icons.Filled.Code),
    IntelTabItem("What-If", Icons.Filled.PsychologyAlt),
    IntelTabItem("Graph", Icons.Filled.Hub),
    IntelTabItem("Improve", Icons.Filled.AutoGraph),
    IntelTabItem("Commands", Icons.Filled.RecordVoiceOver),
    IntelTabItem("Chess", Icons.Filled.SportsEsports),
    IntelTabItem("Macros", Icons.Filled.AutoFixHigh)
)

/**
 * JARVIS AI Intelligence Hub housing all 7 selected AI upgrades (strictly NO Predictive Intelligence),
 * plus Persistent AI Chess and existing Local Automation Macros.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IntelligenceHubScreen(
    initialTab: Int,
    projects: List<ProjectMemoryEntity>,
    missions: List<MissionRunEntity>,
    knowledgeNodes: List<KnowledgeNodeEntity>,
    knowledgeEdges: List<KnowledgeEdgeEntity>,
    strategies: List<ExecutionStrategyEntity>,
    personalCommands: List<PersonalCommandEntity>,
    chessGames: List<ChessGameEntity>,
    activeChessGame: ChessGameEntity?,
    chessAiThinking: Boolean,
    chessStatusMessage: String,
    macros: List<CustomMacroEntity>,
    accessibilityActive: Boolean,
    devModeOutput: String,
    whatIfOutput: String,
    isIntelBusy: Boolean,
    intelStatusMessage: String,
    onSaveProject: (title: String, desc: String, decisions: String, pendingTasks: String, progress: String) -> Unit,
    onResumeProject: (ProjectMemoryEntity) -> Unit,
    onDeleteProject: (ProjectMemoryEntity) -> Unit,
    onRunMission: (goal: String) -> Unit,
    onDeleteMission: (MissionRunEntity) -> Unit,
    onRunDevMode: (mode: String, prompt: String) -> Unit,
    onRunWhatIf: (scenario: String) -> Unit,
    onSyncKnowledgeGraph: () -> Unit,
    onAddKnowledgeLink: (fromLabel: String, fromType: String, toLabel: String, toType: String, relation: String) -> Unit,
    onDeleteKnowledgeNode: (KnowledgeNodeEntity) -> Unit,
    onToggleApproveStrategy: (ExecutionStrategyEntity, Boolean) -> Unit,
    onDeleteStrategy: (ExecutionStrategyEntity) -> Unit,
    onSavePersonalCommand: (trigger: String, steps: String, confirmation: String) -> Unit,
    onRunPersonalCommand: (PersonalCommandEntity) -> Unit,
    onDeletePersonalCommand: (PersonalCommandEntity) -> Unit,
    onCreateChessGame: (difficulty: Int) -> Unit,
    onSelectChessGame: (ChessGameEntity) -> Unit,
    onDeleteChessGame: (ChessGameEntity) -> Unit,
    onMakeChessMove: (ChessMove) -> Unit,
    onMakeChessUciMove: (String) -> Unit,
    onResignChessGame: () -> Unit,
    onAddMacro: (String, String, String, String) -> Unit,
    onDeleteMacro: (CustomMacroEntity) -> Unit,
    onRunMacro: (CustomMacroEntity) -> Unit,
    onRunGestureTest: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, INTEL_TABS.lastIndex)) }
    LaunchedEffect(initialTab) {
        if (initialTab in INTEL_TABS.indices) {
            selectedTab = initialTab
        }
    }

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
            INTEL_TABS.forEachIndexed { idx, tab ->
                Tab(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    text = { Text(tab.title, style = MaterialTheme.typography.labelMedium) },
                    icon = { Icon(tab.icon, contentDescription = tab.title, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("intel_tab_${tab.title.lowercase()}")
                )
            }
        }

        if (intelStatusMessage.isNotBlank() && selectedTab != INTEL_TAB_CHESS && selectedTab != INTEL_TAB_MACROS) {
            Surface(
                color = ArcCyan.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isIntelBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = ArcCyan
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = intelStatusMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = ArcCyan,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        when (selectedTab) {
            INTEL_TAB_CHESS -> ChessScreen(
                games = chessGames,
                activeGame = activeChessGame,
                isAiThinking = chessAiThinking,
                statusMessage = chessStatusMessage,
                onCreateNewGame = onCreateChessGame,
                onSelectGame = onSelectChessGame,
                onDeleteGame = onDeleteChessGame,
                onMakeMove = onMakeChessMove,
                onMakeUciMove = onMakeChessUciMove,
                onResignGame = onResignChessGame,
                modifier = Modifier.weight(1f)
            )

            INTEL_TAB_MACROS -> MacrosScreen(
                macros = macros,
                accessibilityActive = accessibilityActive,
                onAddMacro = onAddMacro,
                onDeleteMacro = onDeleteMacro,
                onRunMacro = onRunMacro,
                onRunGestureTest = onRunGestureTest,
                modifier = Modifier.weight(1f)
            )

            else -> {
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
                        when (selectedTab) {
                            INTEL_TAB_PROJECTS -> ProjectMemoryPane(projects, onSaveProject, onResumeProject, onDeleteProject)
                            INTEL_TAB_MISSION -> MissionModePane(missions, isIntelBusy, onRunMission, onDeleteMission)
                            INTEL_TAB_DEV -> AiDeveloperPane(devModeOutput, isIntelBusy, onRunDevMode)
                            INTEL_TAB_WHAT_IF -> WhatIfSimulatorPane(whatIfOutput, isIntelBusy, onRunWhatIf)
                            INTEL_TAB_GRAPH -> KnowledgeGraphPane(
                                knowledgeNodes,
                                knowledgeEdges,
                                onSyncKnowledgeGraph,
                                onAddKnowledgeLink,
                                onDeleteKnowledgeNode
                            )
                            INTEL_TAB_IMPROVE -> SelfImprovementPane(strategies, onToggleApproveStrategy, onDeleteStrategy)
                            INTEL_TAB_COMMANDS -> PersonalCommandPane(
                                personalCommands,
                                onSavePersonalCommand,
                                onRunPersonalCommand,
                                onDeletePersonalCommand
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectMemoryPane(
    projects: List<ProjectMemoryEntity>,
    onSaveProject: (String, String, String, String, String) -> Unit,
    onResumeProject: (ProjectMemoryEntity) -> Unit,
    onDeleteProject: (ProjectMemoryEntity) -> Unit
) {
    var title by rememberSaveable { mutableStateOf("") }
    var desc by rememberSaveable { mutableStateOf("") }
    var decisions by rememberSaveable { mutableStateOf("") }
    var pending by rememberSaveable { mutableStateOf("") }
    var progress by rememberSaveable { mutableStateOf("") }

    IntelSectionCard(title = "INFINITE PROJECT MEMORY", subtitle = "Persist long-running context, decisions, progress & pending tasks across sessions.") {
        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text("Project Name") }, placeholder = { Text("e.g. YouTube Automation Channel") },
            singleLine = true, colors = intelTextColors(), modifier = Modifier.fillMaxWidth().testTag("project_title_input")
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = desc, onValueChange = { desc = it },
            label = { Text("Goal / Scope") }, minLines = 2, maxLines = 3,
            colors = intelTextColors(), modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = decisions, onValueChange = { decisions = it },
            label = { Text("Key Decisions") }, singleLine = false, maxLines = 3,
            colors = intelTextColors(), modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = pending, onValueChange = { pending = it },
            label = { Text("Pending Tasks (comma-separated)") }, singleLine = true,
            colors = intelTextColors(), modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = progress, onValueChange = { progress = it },
            label = { Text("Current Progress Summary") }, singleLine = true,
            colors = intelTextColors(), modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = {
                if (title.isNotBlank()) {
                    onSaveProject(title, desc, decisions, pending, progress)
                    title = ""; desc = ""; decisions = ""; pending = ""; progress = ""
                }
            },
            enabled = title.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ObsidianVoid),
            modifier = Modifier.fillMaxWidth().testTag("save_project_button")
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("SAVE & ACTIVATE PROJECT")
        }
    }

    projects.forEach { proj ->
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, if (proj.isCurrentActive) SignalGreen else MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth().testTag("project_card_${proj.id}")
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(proj.title, style = MaterialTheme.typography.titleMedium, color = if (proj.isCurrentActive) SignalGreen else ArcCyan)
                        if (proj.isCurrentActive) {
                            Text("ACTIVE CONTEXT • Injected into JARVIS prompts", style = MaterialTheme.typography.labelSmall, color = SignalGreen)
                        }
                    }
                    Row {
                        if (!proj.isCurrentActive) {
                            OutlinedButton(onClick = { onResumeProject(proj) }) {
                                Text("RESUME", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        IconButton(onClick = { onDeleteProject(proj) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete project", tint = SignalCrimson)
                        }
                    }
                }
                if (proj.description.isNotBlank()) Text(proj.description, style = MaterialTheme.typography.bodySmall)
                if (proj.progressSummary.isNotBlank()) Text("Progress: ${proj.progressSummary}", style = MaterialTheme.typography.bodySmall, color = ArcCyan)
                if (proj.decisionsLog.isNotBlank()) Text("Decisions: ${proj.decisionsLog}", style = MaterialTheme.typography.bodySmall)
                if (proj.pendingTasksCsv.isNotBlank()) Text("Pending: ${proj.pendingTasksCsv}", style = MaterialTheme.typography.bodySmall, color = StarkAmber)
            }
        }
    }
}

@Composable
private fun MissionModePane(
    missions: List<MissionRunEntity>,
    isBusy: Boolean,
    onRunMission: (String) -> Unit,
    onDeleteMission: (MissionRunEntity) -> Unit
) {
    var goal by rememberSaveable { mutableStateOf("") }
    IntelSectionCard(title = "MISSION MODE", subtitle = "Break multi-step voice/text orders into ordered sub-tasks, execute sequentially & verify completion.") {
        OutlinedTextField(
            value = goal,
            onValueChange = { goal = it },
            label = { Text("Mission Command") },
            placeholder = { Text("e.g. Go to home screen, open YouTube, search MrBeast, open a video and play it.") },
            minLines = 2,
            maxLines = 4,
            colors = intelTextColors(),
            modifier = Modifier.fillMaxWidth().testTag("mission_goal_input")
        )
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = {
                if (goal.isNotBlank()) {
                    onRunMission(goal)
                    goal = ""
                }
            },
            enabled = goal.isNotBlank() && !isBusy,
            colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ObsidianVoid),
            modifier = Modifier.fillMaxWidth().testTag("run_mission_button")
        ) {
            Icon(Icons.Filled.RocketLaunch, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("EXECUTE SEQUENTIAL MISSION")
        }
    }

    missions.forEach { mission ->
        val steps = remember(mission.stepsJson) {
            runCatching {
                val arr = JSONArray(mission.stepsJson)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Triple(o.optString("title"), o.optString("status"), o.optString("verification"))
                }
            }.getOrDefault(emptyList())
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, if (mission.status == "COMPLETED") SignalGreen.copy(alpha = 0.6f) else StarkAmber.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(mission.goalCommand, style = MaterialTheme.typography.titleSmall, color = ArcCyan)
                        Text(
                            text = "${mission.status} • ${mission.completedSteps}/${mission.totalSteps} steps",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (mission.status == "COMPLETED") SignalGreen else StarkAmber
                        )
                    }
                    IconButton(onClick = { onDeleteMission(mission) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete mission", tint = SignalCrimson)
                    }
                }
                Text(mission.summary, style = MaterialTheme.typography.bodySmall)
                steps.forEachIndexed { i, (t, st, ver) ->
                    Text(
                        text = "${i + 1}. [$st] $t — $ver",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (st == "VERIFIED") SignalGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiDeveloperPane(
    output: String,
    isBusy: Boolean,
    onRunDevMode: (mode: String, prompt: String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var mode by rememberSaveable { mutableStateOf("WEBSITE") }
    var prompt by rememberSaveable { mutableStateOf("") }

    IntelSectionCard(title = "AI DEVELOPER MODE", subtitle = "Website creation, production code generation, debugging & project planning using your existing Gemini API.") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("WEBSITE", "CODE", "DEBUG", "PROJECT_PLAN").forEach { m ->
                FilterChip(
                    selected = mode == m,
                    onClick = { mode = m },
                    label = { Text(m.replace('_', ' ')) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ArcCyan.copy(alpha = 0.22f), selectedLabelColor = ArcCyan)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Describe website, code task, bug, or architecture") },
            minLines = 3,
            maxLines = 6,
            colors = intelTextColors(),
            modifier = Modifier.fillMaxWidth().testTag("dev_mode_prompt_input")
        )
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = { if (prompt.isNotBlank()) onRunDevMode(mode, prompt) },
            enabled = prompt.isNotBlank() && !isBusy,
            colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ObsidianVoid),
            modifier = Modifier.fillMaxWidth().testTag("run_dev_mode_button")
        ) {
            Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("GENERATE WITH JARVIS DEV CORE")
        }
    }

    if (output.isNotBlank()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = DeepHudNavy),
            border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("DEVELOPER OUTPUT", style = MaterialTheme.typography.labelMedium, color = ArcCyan)
                    IconButton(onClick = { clipboard.setText(AnnotatedString(output)) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy developer output", tint = ArcCyan)
                    }
                }
                Text(
                    text = output,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun WhatIfSimulatorPane(
    output: String,
    isBusy: Boolean,
    onRunWhatIf: (String) -> Unit
) {
    var scenario by rememberSaveable { mutableStateOf("") }
    IntelSectionCard(title = "WHAT-IF SIMULATOR", subtitle = "Analyze likely outcomes, risks, policy consequences & safer alternatives BEFORE acting.") {
        OutlinedTextField(
            value = scenario,
            onValueChange = { scenario = it },
            label = { Text("Proposed Action or Scenario") },
            placeholder = { Text("e.g. What if I change my YouTube niche from tech tutorials to daily vlogs?") },
            minLines = 3,
            maxLines = 5,
            colors = intelTextColors(),
            modifier = Modifier.fillMaxWidth().testTag("what_if_scenario_input")
        )
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = { if (scenario.isNotBlank()) onRunWhatIf(scenario) },
            enabled = scenario.isNotBlank() && !isBusy,
            colors = ButtonDefaults.buttonColors(containerColor = StarkAmber, contentColor = ObsidianVoid),
            modifier = Modifier.fillMaxWidth().testTag("run_what_if_button")
        ) {
            Icon(Icons.Filled.PsychologyAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("SIMULATE OUTCOMES & RISKS")
        }
    }

    if (output.isNotBlank()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, StarkAmber.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("SIMULATION REPORT", style = MaterialTheme.typography.labelMedium, color = StarkAmber)
                Text(output, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun KnowledgeGraphPane(
    nodes: List<KnowledgeNodeEntity>,
    edges: List<KnowledgeEdgeEntity>,
    onSync: () -> Unit,
    onAddLink: (String, String, String, String, String) -> Unit,
    onDeleteNode: (KnowledgeNodeEntity) -> Unit
) {
    var fromLabel by rememberSaveable { mutableStateOf("") }
    var toLabel by rememberSaveable { mutableStateOf("") }
    var relation by rememberSaveable { mutableStateOf("RELATES_TO") }

    IntelSectionCard(title = "KNOWLEDGE GRAPH", subtitle = "Connects memories, projects, tasks, monitored videos, chess matches & custom commands.") {
        OutlinedButton(
            onClick = onSync,
            border = BorderStroke(1.dp, ArcCyan),
            modifier = Modifier.fillMaxWidth().testTag("sync_knowledge_graph_button")
        ) {
            Icon(Icons.Filled.Sync, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("AUTO-SYNC FROM LOCAL VAULT (${nodes.size} nodes, ${edges.size} links)", color = ArcCyan)
        }

        if (nodes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            val previewNodes = nodes.take(12)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .background(DeepHudNavy, RoundedCornerShape(12.dp))
                    .padding(8.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val rx = size.width * 0.38f
                    val ry = size.height * 0.36f
                    val positions = previewNodes.mapIndexed { idx, n ->
                        val angle = (2.0 * PI * idx / previewNodes.size.coerceAtLeast(1))
                        n.id to Offset(cx + (rx * cos(angle)).toFloat(), cy + (ry * sin(angle)).toFloat())
                    }.toMap()

                    edges.forEach { e ->
                        val a = positions[e.fromNodeId]
                        val b = positions[e.toNodeId]
                        if (a != null && b != null) {
                            drawLine(color = ArcCyan.copy(alpha = 0.45f), start = a, end = b, strokeWidth = 2.5f)
                        }
                    }
                    previewNodes.forEach { n ->
                        val p = positions[n.id] ?: return@forEach
                        val nodeColor = when (n.nodeType) {
                            "PROJECT" -> SignalGreen
                            "VIDEO" -> StarkAmber
                            "TASK" -> ArcCyan
                            else -> Color(0xFFA78BFA)
                        }
                        drawCircle(color = nodeColor.copy(alpha = 0.25f), radius = 22f, center = p)
                        drawCircle(color = nodeColor, radius = 10f, center = p)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = fromLabel, onValueChange = { fromLabel = it },
                label = { Text("Node A") }, singleLine = true, colors = intelTextColors(), modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = toLabel, onValueChange = { toLabel = it },
                label = { Text("Node B") }, singleLine = true, colors = intelTextColors(), modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = relation, onValueChange = { relation = it },
                label = { Text("Relation") }, singleLine = true, colors = intelTextColors(), modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    if (fromLabel.isNotBlank() && toLabel.isNotBlank()) {
                        onAddLink(fromLabel, "MEMORY", toLabel, "PROJECT", relation.ifBlank { "RELATES_TO" })
                        fromLabel = ""; toLabel = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ObsidianVoid)
            ) {
                Text("LINK")
            }
        }
    }

    nodes.take(25).forEach { node ->
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.AccountTree, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("${node.label} [${node.nodeType}]", style = MaterialTheme.typography.labelMedium, color = ArcCyan)
                    if (node.details.isNotBlank()) {
                        Text(node.details, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { onDeleteNode(node) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete node", tint = SignalCrimson)
                }
            }
        }
    }
}

@Composable
private fun SelfImprovementPane(
    strategies: List<ExecutionStrategyEntity>,
    onToggleApprove: (ExecutionStrategyEntity, Boolean) -> Unit,
    onDelete: (ExecutionStrategyEntity) -> Unit
) {
    IntelSectionCard(
        title = "SELF-IMPROVEMENT ENGINE",
        subtitle = "Analyzes failed tasks and records improved recovery strategies. Critical setting changes are strictly locked until you approve."
    ) {
        Text(
            text = if (strategies.isEmpty()) "No task failures logged yet. When an automation step hits a popup or error, JARVIS saves the improved strategy here."
            else "${strategies.size} learned strategies stored locally.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    strategies.forEach { st ->
        val borderColor = when {
            st.requiresUserApproval && !st.approvedByUser -> StarkAmber
            else -> SignalGreen.copy(alpha = 0.55f)
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(st.taskPattern, style = MaterialTheme.typography.titleSmall, color = ArcCyan, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onDelete(st) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete strategy", tint = SignalCrimson)
                    }
                }
                Text("Failure Reason: ${st.failedReason}", style = MaterialTheme.typography.bodySmall, color = SignalCrimson)
                Text("Improved Strategy: ${st.improvedStrategy}", style = MaterialTheme.typography.bodySmall)
                if (st.requiresUserApproval) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            imageVector = if (st.approvedByUser) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                            contentDescription = null,
                            tint = if (st.approvedByUser) SignalGreen else StarkAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (st.approvedByUser) "Approved by user" else "Requires explicit permission before modifying critical settings",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (st.approvedByUser) SignalGreen else StarkAmber,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = { onToggleApprove(st, !st.approvedByUser) }) {
                            Text(if (st.approvedByUser) "REVOKE" else "APPROVE", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonalCommandPane(
    commands: List<PersonalCommandEntity>,
    onSaveCommand: (String, String, String) -> Unit,
    onRunCommand: (PersonalCommandEntity) -> Unit,
    onDeleteCommand: (PersonalCommandEntity) -> Unit
) {
    var trigger by rememberSaveable { mutableStateOf("") }
    var steps by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }

    IntelSectionCard(
        title = "PERSONAL COMMAND LANGUAGE",
        subtitle = "Define custom voice shortcuts that execute chained workflows separated by \"->\" or new lines."
    ) {
        OutlinedTextField(
            value = trigger, onValueChange = { trigger = it },
            label = { Text("Voice Trigger Phrase") }, placeholder = { Text("e.g. creator mode") },
            singleLine = true, colors = intelTextColors(), modifier = Modifier.fillMaxWidth().testTag("personal_cmd_trigger_input")
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = steps, onValueChange = { steps = it },
            label = { Text("Chained Workflow Steps (use -> between steps)") },
            placeholder = { Text("e.g. Go to home screen -> Open YouTube -> Search MrBeast") },
            minLines = 2, maxLines = 4, colors = intelTextColors(), modifier = Modifier.fillMaxWidth().testTag("personal_cmd_steps_input")
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = confirmation, onValueChange = { confirmation = it },
            label = { Text("Spoken Confirmation (Optional)") }, singleLine = true,
            colors = intelTextColors(), modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = {
                if (trigger.isNotBlank() && steps.isNotBlank()) {
                    onSaveCommand(trigger, steps, confirmation)
                    trigger = ""; steps = ""; confirmation = ""
                }
            },
            enabled = trigger.isNotBlank() && steps.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ObsidianVoid),
            modifier = Modifier.fillMaxWidth().testTag("save_personal_cmd_button")
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("SAVE PERSONAL COMMAND")
        }
    }

    commands.forEach { cmd ->
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("\"${cmd.triggerPhrase}\"  •  ${cmd.runCount} runs", style = MaterialTheme.typography.titleSmall, color = ArcCyan)
                    Text(cmd.workflowStepsText, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { onRunCommand(cmd) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Run personal command", tint = SignalGreen)
                }
                IconButton(onClick = { onDeleteCommand(cmd) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete personal command", tint = SignalCrimson)
                }
            }
        }
    }
}

@Composable
private fun IntelSectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = ArcCyan)
            Spacer(modifier = Modifier.height(3.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun intelTextColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = ArcCyan,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface
)
