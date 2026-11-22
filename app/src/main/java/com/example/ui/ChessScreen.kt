package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.CHESS_DIFFICULTY_CASUAL
import com.example.CHESS_DIFFICULTY_MASTER
import com.example.CHESS_DIFFICULTY_TACTICAL
import com.example.ChessBoardState
import com.example.ChessColor
import com.example.ChessGameEntity
import com.example.ChessMove
import com.example.JarvisChessAiEngine
import com.example.PieceType
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.DeepHudNavy
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.SignalCrimson
import com.example.ui.theme.SignalGreen
import com.example.ui.theme.StarkAmber

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChessScreen(
    games: List<ChessGameEntity>,
    activeGame: ChessGameEntity?,
    isAiThinking: Boolean,
    statusMessage: String,
    onCreateNewGame: (difficulty: Int) -> Unit,
    onSelectGame: (ChessGameEntity) -> Unit,
    onDeleteGame: (ChessGameEntity) -> Unit,
    onMakeMove: (ChessMove) -> Unit,
    onMakeUciMove: (String) -> Unit,
    onResignGame: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSquare by remember(activeGame?.id, activeGame?.fen) { mutableIntStateOf(-1) }
    var flippedBoard by rememberSaveable { mutableStateOf(false) }
    var selectedDifficulty by rememberSaveable(activeGame?.difficulty) {
        mutableIntStateOf(activeGame?.difficulty ?: CHESS_DIFFICULTY_TACTICAL)
    }
    var moveCommandText by rememberSaveable { mutableStateOf("") }

    val board = remember(activeGame?.fen) {
        if (activeGame != null) ChessBoardState.fromFen(activeGame.fen) else ChessBoardState.fromFen(ChessBoardState.INITIAL_FEN)
    }
    val legalMovesForSelected = remember(board, selectedSquare) {
        if (selectedSquare in 0..63 && activeGame?.status == "IN_PROGRESS" && board.sideToMove == ChessColor.WHITE) {
            board.legalMovesFrom(selectedSquare)
        } else {
            emptyList()
        }
    }
    val legalTargets = remember(legalMovesForSelected) { legalMovesForSelected.map { it.to }.toSet() }
    val lastMoveUci = remember(activeGame?.movesHistoryCsv) {
        activeGame?.movesHistoryCsv?.split(',')?.lastOrNull()?.trim().orEmpty()
    }
    val lastFromSq = remember(lastMoveUci) {
        if (lastMoveUci.length >= 4) ChessBoardState.parseSquare(lastMoveUci.substring(0, 2)) ?: -1 else -1
    }
    val lastToSq = remember(lastMoveUci) {
        if (lastMoveUci.length >= 4) ChessBoardState.parseSquare(lastMoveUci.substring(2, 4)) ?: -1 else -1
    }
    val whiteInCheck = remember(board) { board.isKingInCheck(ChessColor.WHITE) }
    val blackInCheck = remember(board) { board.isKingInCheck(ChessColor.BLACK) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 620.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "J.A.R.V.I.S. TACTICAL CHESS",
                        style = MaterialTheme.typography.titleLarge,
                        color = ArcCyan
                    )
                    Text(
                        text = "Persistent AI Match Engine • Say \"e2 to e4\" or tap pieces",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { flippedBoard = !flippedBoard },
                    modifier = Modifier.testTag("chess_flip_board_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Flip,
                        contentDescription = "Flip chessboard orientation",
                        tint = ArcCyan
                    )
                }
            }

            // Difficulty + New Game controls
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val levels = listOf(
                    CHESS_DIFFICULTY_CASUAL to "Casual",
                    CHESS_DIFFICULTY_TACTICAL to "Tactical",
                    CHESS_DIFFICULTY_MASTER to "Master"
                )
                levels.forEach { (lvl, label) ->
                    FilterChip(
                        selected = selectedDifficulty == lvl,
                        onClick = { selectedDifficulty = lvl },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ArcCyan.copy(alpha = 0.22f),
                            selectedLabelColor = ArcCyan
                        ),
                        modifier = Modifier.testTag("chess_diff_$lvl")
                    )
                }
                Button(
                    onClick = { onCreateNewGame(selectedDifficulty) },
                    colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ObsidianVoid),
                    modifier = Modifier.testTag("chess_new_game_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("NEW MATCH")
                }
            }

            // Status Banner
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(
                    1.dp,
                    when {
                        isAiThinking -> StarkAmber
                        activeGame?.status?.startsWith("CHECKMATE") == true -> SignalGreen
                        whiteInCheck || blackInCheck -> SignalCrimson
                        else -> ArcCyan.copy(alpha = 0.45f)
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SmartToy,
                            contentDescription = null,
                            tint = if (isAiThinking) StarkAmber else ArcCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = activeGame?.title ?: "No active match — tap NEW MATCH",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = when {
                                    isAiThinking -> "JARVIS is calculating counter-move…"
                                    statusMessage.isNotBlank() -> statusMessage
                                    activeGame == null -> "Say \"play chess\" or tap NEW MATCH to start."
                                    activeGame.status != "IN_PROGRESS" -> "Match finished: ${activeGame.status.replace('_', ' ')}"
                                    whiteInCheck -> "CHECK on your King! Play a legal defense."
                                    else -> "Your turn (White) • Move #${board.fullMoveNumber}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (whiteInCheck) SignalCrimson else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (activeGame != null && activeGame.status == "IN_PROGRESS") {
                        OutlinedButton(
                            onClick = onResignGame,
                            border = BorderStroke(1.dp, SignalCrimson.copy(alpha = 0.6f)),
                            modifier = Modifier.testTag("chess_resign_button")
                        ) {
                            Icon(Icons.Filled.Flag, contentDescription = null, tint = SignalCrimson, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("RESIGN", color = SignalCrimson, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Interactive 8x8 Chessboard
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DeepHudNavy),
                border = BorderStroke(1.5.dp, ArcCyan.copy(alpha = 0.55f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("chess_board_container")
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    val rowIndices = if (flippedBoard) (7 downTo 0).toList() else (0..7).toList()
                    val colIndices = if (flippedBoard) (7 downTo 0).toList() else (0..7).toList()

                    rowIndices.forEach { r ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            colIndices.forEach { c ->
                                val sq = r * 8 + c
                                val piece = board.squares[sq]
                                val isLight = (r + c) % 2 == 0
                                val isSelected = sq == selectedSquare
                                val isLegalTarget = sq in legalTargets
                                val isLastMove = sq == lastFromSq || sq == lastToSq
                                val isKingCheck = piece?.type == PieceType.KING && (
                                    (piece.color == ChessColor.WHITE && whiteInCheck) ||
                                        (piece.color == ChessColor.BLACK && blackInCheck)
                                    )

                                val bgColor = when {
                                    isKingCheck -> SignalCrimson.copy(alpha = 0.68f)
                                    isSelected -> ArcCyan.copy(alpha = 0.55f)
                                    isLastMove -> StarkAmber.copy(alpha = 0.34f)
                                    isLight -> Color(0xFF22364A)
                                    else -> Color(0xFF101C2A)
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .background(bgColor)
                                        .then(
                                            if (isLegalTarget && piece != null) {
                                                Modifier.border(2.dp, SignalGreen)
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .clickable(enabled = activeGame?.status == "IN_PROGRESS" && !isAiThinking) {
                                            if (selectedSquare in 0..63 && sq in legalTargets) {
                                                val chosen = legalMovesForSelected.firstOrNull {
                                                    it.to == sq && (it.promotion == null || it.promotion == PieceType.QUEEN)
                                                } ?: legalMovesForSelected.first { it.to == sq }
                                                selectedSquare = -1
                                                onMakeMove(chosen)
                                            } else if (piece != null && piece.color == ChessColor.WHITE) {
                                                selectedSquare = if (selectedSquare == sq) -1 else sq
                                            } else {
                                                selectedSquare = -1
                                            }
                                        }
                                        .testTag("chess_sq_${ChessBoardState.squareName(sq)}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (piece != null) {
                                        Text(
                                            text = piece.unicodeSymbol(),
                                            fontSize = 26.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (piece.color == ChessColor.WHITE) Color(0xFFF3FAFF) else ArcCyan
                                        )
                                    } else if (isLegalTarget) {
                                        Box(
                                            modifier = Modifier
                                                .size(11.dp)
                                                .clip(CircleShape)
                                                .background(SignalGreen.copy(alpha = 0.85f))
                                        )
                                    }

                                    // Rank/File corner coordinates on edge squares
                                    if (c == colIndices.first()) {
                                        Text(
                                            text = "${8 - r}",
                                            fontSize = 8.sp,
                                            color = Color.White.copy(alpha = 0.45f),
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .padding(2.dp)
                                        )
                                    }
                                    if (r == rowIndices.last()) {
                                        Text(
                                            text = "${'a' + c}",
                                            fontSize = 8.sp,
                                            color = Color.White.copy(alpha = 0.45f),
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Text / Voice-style Move Input ("e2 to e4")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = moveCommandText,
                    onValueChange = { moveCommandText = it },
                    label = { Text("Enter Move (e.g. e2 to e4 or g1f3)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ArcCyan,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chess_move_input")
                )
                Button(
                    onClick = {
                        val uci = JarvisChessAiEngine.parseVoiceMove(moveCommandText) ?: moveCommandText.trim()
                        if (uci.isNotBlank()) {
                            onMakeUciMove(uci)
                            moveCommandText = ""
                        }
                    },
                    enabled = activeGame?.status == "IN_PROGRESS" && !isAiThinking && moveCommandText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ObsidianVoid),
                    modifier = Modifier.testTag("chess_move_submit_button")
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "Submit chess move", modifier = Modifier.size(18.dp))
                }
            }

            // Move History
            val moveList = remember(activeGame?.movesHistoryCsv) {
                activeGame?.movesHistoryCsv?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            }
            if (moveList.isNotEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "MOVE LOG (${moveList.size} plies)",
                            style = MaterialTheme.typography.labelSmall,
                            color = ArcCyan
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = moveList.chunked(2).mapIndexed { i, pair ->
                                "${i + 1}. ${pair.joinToString(" ")}"
                            }.joinToString("   "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Saved Games List (Persistent Room DB)
            if (games.isNotEmpty()) {
                Text(
                    text = "SAVED CHESS MATCHES (${games.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = ArcCyan
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(games, key = { it.id }) { game ->
                        val isCurrent = game.id == activeGame?.id
                        Surface(
                            onClick = { onSelectGame(game) },
                            shape = MaterialTheme.shapes.medium,
                            color = if (isCurrent) ArcCyan.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, if (isCurrent) ArcCyan else MaterialTheme.colorScheme.outline),
                            modifier = Modifier.testTag("saved_chess_game_${game.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.widthIn(max = 170.dp)) {
                                    Text(
                                        text = game.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isCurrent) ArcCyan else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${game.moveCount} moves • ${game.status.replace('_', ' ')}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteGame(game) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Delete saved chess game",
                                        tint = SignalCrimson,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}
