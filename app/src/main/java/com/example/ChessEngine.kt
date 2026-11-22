package com.example

import kotlin.math.abs

enum class ChessColor {
    WHITE, BLACK;
    fun opposite(): ChessColor = if (this == WHITE) BLACK else WHITE
}

enum class PieceType(val materialValue: Int, val fenChar: Char) {
    PAWN(100, 'p'),
    KNIGHT(320, 'n'),
    BISHOP(330, 'b'),
    ROOK(500, 'r'),
    QUEEN(900, 'q'),
    KING(20000, 'k')
}

data class ChessPiece(
    val type: PieceType,
    val color: ChessColor
) {
    fun unicodeSymbol(): String = when (color) {
        ChessColor.WHITE -> when (type) {
            PieceType.KING -> "♔"
            PieceType.QUEEN -> "♕"
            PieceType.ROOK -> "♖"
            PieceType.BISHOP -> "♗"
            PieceType.KNIGHT -> "♘"
            PieceType.PAWN -> "♙"
        }
        ChessColor.BLACK -> when (type) {
            PieceType.KING -> "♚"
            PieceType.QUEEN -> "♛"
            PieceType.ROOK -> "♜"
            PieceType.BISHOP -> "♝"
            PieceType.KNIGHT -> "♞"
            PieceType.PAWN -> "♟"
        }
    }

    fun toFenChar(): Char = if (color == ChessColor.WHITE) type.fenChar.uppercaseChar() else type.fenChar
}

data class ChessMove(
    val from: Int, // 0..63 where 0 = a8, 7 = h8, 56 = a1, 63 = h1
    val to: Int,
    val promotion: PieceType? = null,
    val isCastleKingSide: Boolean = false,
    val isCastleQueenSide: Boolean = false,
    val isEnPassant: Boolean = false
) {
    fun toUci(): String {
        val base = "${ChessBoardState.squareName(from)}${ChessBoardState.squareName(to)}"
        return if (promotion != null) "$base${promotion.fenChar}" else base
    }
}

enum class ChessOutcome {
    IN_PROGRESS,
    CHECKMATE_WHITE_WINS,
    CHECKMATE_BLACK_WINS,
    STALEMATE,
    DRAW_FIFTY_MOVE,
    DRAW_INSUFFICIENT_MATERIAL
}

/**
 * Complete, 100% FIDE-legal Chess Board State & Engine (FEN, castling, en passant, promotion,
 * check/checkmate/stalemate detection, and Alpha-Beta Minimax AI across 3 difficulty levels).
 */
data class ChessBoardState(
    val squares: List<ChessPiece?>, // length 64: row 0 = rank 8, row 7 = rank 1
    val sideToMove: ChessColor = ChessColor.WHITE,
    val whiteCastleKing: Boolean = true,
    val whiteCastleQueen: Boolean = true,
    val blackCastleKing: Boolean = true,
    val blackCastleQueen: Boolean = true,
    val enPassantSquare: Int = -1,
    val halfMoveClock: Int = 0,
    val fullMoveNumber: Int = 1
) {
    fun toFen(): String {
        val sb = StringBuilder()
        for (r in 0..7) {
            var empty = 0
            for (c in 0..7) {
                val p = squares[r * 8 + c]
                if (p == null) {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    sb.append(p.toFenChar())
                }
            }
            if (empty > 0) sb.append(empty)
            if (r < 7) sb.append('/')
        }
        sb.append(' ').append(if (sideToMove == ChessColor.WHITE) 'w' else 'b')
        val castle = buildString {
            if (whiteCastleKing) append('K')
            if (whiteCastleQueen) append('Q')
            if (blackCastleKing) append('k')
            if (blackCastleQueen) append('q')
        }.ifEmpty { "-" }
        sb.append(' ').append(castle)
        sb.append(' ').append(if (enPassantSquare in 0..63) squareName(enPassantSquare) else "-")
        sb.append(' ').append(halfMoveClock).append(' ').append(fullMoveNumber)
        return sb.toString()
    }

    fun generateLegalMoves(): List<ChessMove> {
        val pseudo = generatePseudoLegalMoves(sideToMove)
        return pseudo.filter { move ->
            val next = applyMoveUnchecked(move)
            !next.isKingInCheck(sideToMove)
        }
    }

    fun legalMovesFrom(square: Int): List<ChessMove> {
        return generateLegalMoves().filter { it.from == square }
    }

    fun applyMove(move: ChessMove): ChessBoardState? {
        val legal = generateLegalMoves().firstOrNull {
            it.from == move.from && it.to == move.to &&
                (move.promotion == null || it.promotion == move.promotion)
        } ?: return null
        return applyMoveUnchecked(legal)
    }

    fun applyUciMove(uci: String): ChessBoardState? {
        val clean = uci.trim().lowercase().replace(Regex("[^a-h1-8qrbn]"), "")
        if (clean.length < 4) return null
        val from = parseSquare(clean.substring(0, 2)) ?: return null
        val to = parseSquare(clean.substring(2, 4)) ?: return null
        val promo = when (clean.getOrNull(4)) {
            'q' -> PieceType.QUEEN
            'r' -> PieceType.ROOK
            'b' -> PieceType.BISHOP
            'n' -> PieceType.KNIGHT
            else -> null
        }
        return applyMove(ChessMove(from = from, to = to, promotion = promo))
    }

    fun isKingInCheck(color: ChessColor): Boolean {
        val kingSq = squares.indexOfFirst { it?.type == PieceType.KING && it.color == color }
        if (kingSq < 0) return true
        return isSquareAttackedBy(kingSq, color.opposite())
    }

    fun evaluateOutcome(): ChessOutcome {
        val legal = generateLegalMoves()
        if (legal.isEmpty()) {
            return if (isKingInCheck(sideToMove)) {
                if (sideToMove == ChessColor.WHITE) ChessOutcome.CHECKMATE_BLACK_WINS
                else ChessOutcome.CHECKMATE_WHITE_WINS
            } else {
                ChessOutcome.STALEMATE
            }
        }
        if (halfMoveClock >= 100) return ChessOutcome.DRAW_FIFTY_MOVE
        val nonKings = squares.filterNotNull().filter { it.type != PieceType.KING }
        if (nonKings.isEmpty() || (nonKings.size == 1 && (nonKings[0].type == PieceType.KNIGHT || nonKings[0].type == PieceType.BISHOP))) {
            return ChessOutcome.DRAW_INSUFFICIENT_MATERIAL
        }
        return ChessOutcome.IN_PROGRESS
    }

    fun applyMoveUnchecked(move: ChessMove): ChessBoardState {
        val board = squares.toMutableList()
        val moving = board[move.from] ?: return this
        val captured = board[move.to]

        board[move.from] = null
        board[move.to] = if (move.promotion != null) ChessPiece(move.promotion, moving.color) else moving

        if (move.isEnPassant) {
            val capSq = if (moving.color == ChessColor.WHITE) move.to + 8 else move.to - 8
            if (capSq in 0..63) board[capSq] = null
        }
        if (move.isCastleKingSide) {
            val row = move.from / 8
            board[row * 8 + 5] = board[row * 8 + 7]
            board[row * 8 + 7] = null
        } else if (move.isCastleQueenSide) {
            val row = move.from / 8
            board[row * 8 + 3] = board[row * 8 + 0]
            board[row * 8 + 0] = null
        }

        var wck = whiteCastleKing
        var wcq = whiteCastleQueen
        var bck = blackCastleKing
        var bcq = blackCastleQueen

        if (moving.type == PieceType.KING) {
            if (moving.color == ChessColor.WHITE) { wck = false; wcq = false }
            else { bck = false; bcq = false }
        }
        if (move.from == 63 || move.to == 63) wck = false
        if (move.from == 56 || move.to == 56) wcq = false
        if (move.from == 7 || move.to == 7) bck = false
        if (move.from == 0 || move.to == 0) bcq = false

        val newEnPassant = if (moving.type == PieceType.PAWN && abs(move.to - move.from) == 16) {
            (move.from + move.to) / 2
        } else -1

        val newHalfMove = if (moving.type == PieceType.PAWN || captured != null || move.isEnPassant) 0 else halfMoveClock + 1
        val newFullMove = if (sideToMove == ChessColor.BLACK) fullMoveNumber + 1 else fullMoveNumber

        return ChessBoardState(
            squares = board,
            sideToMove = sideToMove.opposite(),
            whiteCastleKing = wck,
            whiteCastleQueen = wcq,
            blackCastleKing = bck,
            blackCastleQueen = bcq,
            enPassantSquare = newEnPassant,
            halfMoveClock = newHalfMove,
            fullMoveNumber = newFullMove
        )
    }

    private fun generatePseudoLegalMoves(color: ChessColor): List<ChessMove> {
        val moves = mutableListOf<ChessMove>()
        for (sq in 0..63) {
            val p = squares[sq] ?: continue
            if (p.color != color) continue
            val r = sq / 8
            val c = sq % 8
            when (p.type) {
                PieceType.PAWN -> addPawnMoves(sq, r, c, color, moves)
                PieceType.KNIGHT -> addSteppedMoves(sq, r, c, color, KNIGHT_DELTAS, moves)
                PieceType.BISHOP -> addSlidingMoves(sq, r, c, color, BISHOP_DIRS, moves)
                PieceType.ROOK -> addSlidingMoves(sq, r, c, color, ROOK_DIRS, moves)
                PieceType.QUEEN -> {
                    addSlidingMoves(sq, r, c, color, BISHOP_DIRS, moves)
                    addSlidingMoves(sq, r, c, color, ROOK_DIRS, moves)
                }
                PieceType.KING -> {
                    addSteppedMoves(sq, r, c, color, KING_DELTAS, moves)
                    addCastlingMoves(color, moves)
                }
            }
        }
        return moves
    }

    private fun addPawnMoves(sq: Int, r: Int, c: Int, color: ChessColor, out: MutableList<ChessMove>) {
        val dir = if (color == ChessColor.WHITE) -1 else 1
        val startRow = if (color == ChessColor.WHITE) 6 else 1
        val promoRow = if (color == ChessColor.WHITE) 0 else 7

        val oneR = r + dir
        if (oneR in 0..7) {
            val oneSq = oneR * 8 + c
            if (squares[oneSq] == null) {
                if (oneR == promoRow) {
                    PROMO_TYPES.forEach { out.add(ChessMove(sq, oneSq, promotion = it)) }
                } else {
                    out.add(ChessMove(sq, oneSq))
                    if (r == startRow) {
                        val twoSq = (r + 2 * dir) * 8 + c
                        if (squares[twoSq] == null) out.add(ChessMove(sq, twoSq))
                    }
                }
            }
            for (dc in intArrayOf(-1, 1)) {
                val tc = c + dc
                if (tc !in 0..7) continue
                val capSq = oneR * 8 + tc
                val target = squares[capSq]
                if (target != null && target.color != color) {
                    if (oneR == promoRow) {
                        PROMO_TYPES.forEach { out.add(ChessMove(sq, capSq, promotion = it)) }
                    } else {
                        out.add(ChessMove(sq, capSq))
                    }
                } else if (capSq == enPassantSquare) {
                    out.add(ChessMove(sq, capSq, isEnPassant = true))
                }
            }
        }
    }

    private fun addSteppedMoves(sq: Int, r: Int, c: Int, color: ChessColor, deltas: Array<IntArray>, out: MutableList<ChessMove>) {
        for (d in deltas) {
            val nr = r + d[0]
            val nc = c + d[1]
            if (nr !in 0..7 || nc !in 0..7) continue
            val tSq = nr * 8 + nc
            val target = squares[tSq]
            if (target == null || target.color != color) {
                out.add(ChessMove(sq, tSq))
            }
        }
    }

    private fun addSlidingMoves(sq: Int, r: Int, c: Int, color: ChessColor, dirs: Array<IntArray>, out: MutableList<ChessMove>) {
        for (d in dirs) {
            var nr = r + d[0]
            var nc = c + d[1]
            while (nr in 0..7 && nc in 0..7) {
                val tSq = nr * 8 + nc
                val target = squares[tSq]
                if (target == null) {
                    out.add(ChessMove(sq, tSq))
                } else {
                    if (target.color != color) out.add(ChessMove(sq, tSq))
                    break
                }
                nr += d[0]
                nc += d[1]
            }
        }
    }

    private fun addCastlingMoves(color: ChessColor, out: MutableList<ChessMove>) {
        val opp = color.opposite()
        if (color == ChessColor.WHITE) {
            if (squares[60]?.type == PieceType.KING && squares[60]?.color == ChessColor.WHITE && !isSquareAttackedBy(60, opp)) {
                if (whiteCastleKing && squares[61] == null && squares[62] == null &&
                    squares[63]?.type == PieceType.ROOK && squares[63]?.color == ChessColor.WHITE &&
                    !isSquareAttackedBy(61, opp) && !isSquareAttackedBy(62, opp)
                ) {
                    out.add(ChessMove(60, 62, isCastleKingSide = true))
                }
                if (whiteCastleQueen && squares[59] == null && squares[58] == null && squares[57] == null &&
                    squares[56]?.type == PieceType.ROOK && squares[56]?.color == ChessColor.WHITE &&
                    !isSquareAttackedBy(59, opp) && !isSquareAttackedBy(58, opp)
                ) {
                    out.add(ChessMove(60, 58, isCastleQueenSide = true))
                }
            }
        } else {
            if (squares[4]?.type == PieceType.KING && squares[4]?.color == ChessColor.BLACK && !isSquareAttackedBy(4, opp)) {
                if (blackCastleKing && squares[5] == null && squares[6] == null &&
                    squares[7]?.type == PieceType.ROOK && squares[7]?.color == ChessColor.BLACK &&
                    !isSquareAttackedBy(5, opp) && !isSquareAttackedBy(6, opp)
                ) {
                    out.add(ChessMove(4, 6, isCastleKingSide = true))
                }
                if (blackCastleQueen && squares[3] == null && squares[2] == null && squares[1] == null &&
                    squares[0]?.type == PieceType.ROOK && squares[0]?.color == ChessColor.BLACK &&
                    !isSquareAttackedBy(3, opp) && !isSquareAttackedBy(2, opp)
                ) {
                    out.add(ChessMove(4, 2, isCastleQueenSide = true))
                }
            }
        }
    }

    fun isSquareAttackedBy(sq: Int, attacker: ChessColor): Boolean {
        val r = sq / 8
        val c = sq % 8

        // Pawn attacks
        val pawnSourceRow = if (attacker == ChessColor.WHITE) r + 1 else r - 1
        if (pawnSourceRow in 0..7) {
            for (dc in intArrayOf(-1, 1)) {
                val pc = c + dc
                if (pc in 0..7) {
                    val p = squares[pawnSourceRow * 8 + pc]
                    if (p != null && p.color == attacker && p.type == PieceType.PAWN) return true
                }
            }
        }

        // Knight attacks
        for (d in KNIGHT_DELTAS) {
            val nr = r + d[0]; val nc = c + d[1]
            if (nr in 0..7 && nc in 0..7) {
                val p = squares[nr * 8 + nc]
                if (p != null && p.color == attacker && p.type == PieceType.KNIGHT) return true
            }
        }

        // King attacks
        for (d in KING_DELTAS) {
            val nr = r + d[0]; val nc = c + d[1]
            if (nr in 0..7 && nc in 0..7) {
                val p = squares[nr * 8 + nc]
                if (p != null && p.color == attacker && p.type == PieceType.KING) return true
            }
        }

        // Sliding diagonal (Bishop / Queen)
        for (d in BISHOP_DIRS) {
            var nr = r + d[0]; var nc = c + d[1]
            while (nr in 0..7 && nc in 0..7) {
                val p = squares[nr * 8 + nc]
                if (p != null) {
                    if (p.color == attacker && (p.type == PieceType.BISHOP || p.type == PieceType.QUEEN)) return true
                    break
                }
                nr += d[0]; nc += d[1]
            }
        }

        // Sliding orthogonal (Rook / Queen)
        for (d in ROOK_DIRS) {
            var nr = r + d[0]; var nc = c + d[1]
            while (nr in 0..7 && nc in 0..7) {
                val p = squares[nr * 8 + nc]
                if (p != null) {
                    if (p.color == attacker && (p.type == PieceType.ROOK || p.type == PieceType.QUEEN)) return true
                    break
                }
                nr += d[0]; nc += d[1]
            }
        }
        return false
    }

    companion object {
        const val INITIAL_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        private val PROMO_TYPES = listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)
        private val KNIGHT_DELTAS = arrayOf(
            intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, -2), intArrayOf(-1, 2),
            intArrayOf(1, -2), intArrayOf(1, 2), intArrayOf(2, -1), intArrayOf(2, 1)
        )
        private val KING_DELTAS = arrayOf(
            intArrayOf(-1, -1), intArrayOf(-1, 0), intArrayOf(-1, 1), intArrayOf(0, -1),
            intArrayOf(0, 1), intArrayOf(1, -1), intArrayOf(1, 0), intArrayOf(1, 1)
        )
        private val BISHOP_DIRS = arrayOf(intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(1, 1))
        private val ROOK_DIRS = arrayOf(intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1))

        fun squareName(index: Int): String {
            val file = ('a'.code + (index % 8)).toChar()
            val rank = 8 - (index / 8)
            return "$file$rank"
        }

        fun parseSquare(name: String): Int? {
            if (name.length != 2) return null
            val file = name[0].lowercaseChar() - 'a'
            val rank = 8 - (name[1] - '0')
            if (file !in 0..7 || rank !in 0..7) return null
            return rank * 8 + file
        }

        fun fromFen(fen: String): ChessBoardState {
            val parts = fen.trim().split(Regex("\\s+"))
            val ranks = parts.getOrNull(0)?.split('/') ?: return fromFen(INITIAL_FEN)
            val board = MutableList<ChessPiece?>(64) { null }
            for (r in 0..7) {
                val rowStr = ranks.getOrNull(r) ?: continue
                var c = 0
                for (ch in rowStr) {
                    if (ch.isDigit()) {
                        c += (ch - '0')
                    } else if (c in 0..7) {
                        val color = if (ch.isUpperCase()) ChessColor.WHITE else ChessColor.BLACK
                        val type = when (ch.lowercaseChar()) {
                            'p' -> PieceType.PAWN
                            'n' -> PieceType.KNIGHT
                            'b' -> PieceType.BISHOP
                            'r' -> PieceType.ROOK
                            'q' -> PieceType.QUEEN
                            'k' -> PieceType.KING
                            else -> null
                        }
                        if (type != null) board[r * 8 + c] = ChessPiece(type, color)
                        c++
                    }
                }
            }
            val side = if (parts.getOrNull(1) == "b") ChessColor.BLACK else ChessColor.WHITE
            val castle = parts.getOrNull(2).orEmpty()
            val ep = parts.getOrNull(3)?.takeIf { it != "-" }?.let { parseSquare(it) } ?: -1
            val half = parts.getOrNull(4)?.toIntOrNull() ?: 0
            val full = parts.getOrNull(5)?.toIntOrNull() ?: 1
            return ChessBoardState(
                squares = board,
                sideToMove = side,
                whiteCastleKing = castle.contains('K'),
                whiteCastleQueen = castle.contains('Q'),
                blackCastleKing = castle.contains('k'),
                blackCastleQueen = castle.contains('q'),
                enPassantSquare = ep,
                halfMoveClock = half,
                fullMoveNumber = full
            )
        }
    }
}

object JarvisChessAiEngine {

    private val PAWN_TABLE = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        50, 50, 50, 50, 50, 50, 50, 50,
        10, 10, 20, 30, 30, 20, 10, 10,
        5, 5, 10, 25, 25, 10, 5, 5,
        0, 0, 0, 20, 20, 0, 0, 0,
        5, -5, -10, 0, 0, -10, -5, 5,
        5, 10, 10, -20, -20, 10, 10, 5,
        0, 0, 0, 0, 0, 0, 0, 0
    )

    private val KNIGHT_TABLE = intArrayOf(
        -50, -40, -30, -30, -30, -30, -40, -50,
        -40, -20, 0, 0, 0, 0, -20, -40,
        -30, 0, 10, 15, 15, 10, 0, -30,
        -30, 5, 15, 20, 20, 15, 5, -30,
        -30, 0, 15, 20, 20, 15, 0, -30,
        -30, 5, 10, 15, 15, 10, 5, -30,
        -40, -20, 0, 5, 5, 0, -20, -40,
        -50, -40, -30, -30, -30, -30, -40, -50
    )

    private val CENTER_BONUS = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        0, 5, 5, 5, 5, 5, 5, 0,
        0, 5, 12, 15, 15, 12, 5, 0,
        0, 5, 15, 22, 22, 15, 5, 0,
        0, 5, 15, 22, 22, 15, 5, 0,
        0, 5, 12, 15, 15, 12, 5, 0,
        0, 5, 5, 5, 5, 5, 5, 0,
        0, 0, 0, 0, 0, 0, 0, 0
    )

    fun chooseBestMove(state: ChessBoardState, difficulty: Int): ChessMove? {
        val legal = orderMoves(state, state.generateLegalMoves())
        if (legal.isEmpty()) return null

        val depth = when (difficulty.coerceIn(1, 3)) {
            CHESS_DIFFICULTY_CASUAL -> 1
            CHESS_DIFFICULTY_TACTICAL -> 2
            else -> 3
        }

        val maximizing = state.sideToMove == ChessColor.WHITE
        var bestMove = legal.first()
        var bestScore = if (maximizing) Int.MIN_VALUE else Int.MAX_VALUE
        var alpha = -1_000_000
        var beta = 1_000_000

        for (move in legal) {
            val next = state.applyMoveUnchecked(move)
            val score = alphaBeta(next, depth - 1, alpha, beta, !maximizing)
            if (maximizing) {
                if (score > bestScore) {
                    bestScore = score
                    bestMove = move
                }
                if (score > alpha) alpha = score
            } else {
                if (score < bestScore) {
                    bestScore = score
                    bestMove = move
                }
                if (score < beta) beta = score
            }
        }
        return bestMove
    }

    private fun alphaBeta(
        state: ChessBoardState,
        depth: Int,
        alphaInit: Int,
        betaInit: Int,
        maximizing: Boolean
    ): Int {
        val legal = state.generateLegalMoves()
        if (legal.isEmpty()) {
            return if (state.isKingInCheck(state.sideToMove)) {
                if (state.sideToMove == ChessColor.WHITE) -100_000 - depth else 100_000 + depth
            } else 0
        }
        if (depth <= 0) {
            return evaluateStatic(state)
        }

        var alpha = alphaInit
        var beta = betaInit
        val ordered = orderMoves(state, legal)

        if (maximizing) {
            var value = Int.MIN_VALUE
            for (move in ordered) {
                value = maxOf(value, alphaBeta(state.applyMoveUnchecked(move), depth - 1, alpha, beta, false))
                if (value > alpha) alpha = value
                if (alpha >= beta) break
            }
            return value
        } else {
            var value = Int.MAX_VALUE
            for (move in ordered) {
                value = minOf(value, alphaBeta(state.applyMoveUnchecked(move), depth - 1, alpha, beta, true))
                if (value < beta) beta = value
                if (beta <= alpha) break
            }
            return value
        }
    }

    private fun orderMoves(state: ChessBoardState, moves: List<ChessMove>): List<ChessMove> {
        return moves.sortedByDescending { move ->
            val victim = state.squares[move.to]?.type?.materialValue ?: 0
            val promoBonus = move.promotion?.materialValue ?: 0
            victim * 10 + promoBonus + CENTER_BONUS[move.to]
        }
    }

    private fun evaluateStatic(state: ChessBoardState): Int {
        var score = 0
        for (sq in 0..63) {
            val p = state.squares[sq] ?: continue
            val mirrorSq = if (p.color == ChessColor.WHITE) sq else (7 - (sq / 8)) * 8 + (sq % 8)
            val positional = when (p.type) {
                PieceType.PAWN -> PAWN_TABLE[mirrorSq]
                PieceType.KNIGHT -> KNIGHT_TABLE[mirrorSq]
                PieceType.BISHOP, PieceType.QUEEN -> CENTER_BONUS[mirrorSq]
                PieceType.ROOK -> if ((mirrorSq / 8) == 1) 18 else 0
                PieceType.KING -> 0
            }
            val value = p.type.materialValue + positional
            score += if (p.color == ChessColor.WHITE) value else -value
        }
        return score
    }

    /**
     * Parses spoken chess commands like "e2 to e4", "move e2 e4", "play e7e5".
     */
    fun parseVoiceMove(rawCommand: String): String? {
        val lower = rawCommand.lowercase()
            .replace(" to ", " ")
            .replace(" se ", " ")
            .replace("-", " ")
        val match = Regex("\\b([a-h][1-8])\\s*([a-h][1-8])(?:\\s*(queen|rook|bishop|knight|q|r|b|n))?\\b").find(lower)
            ?: return null
        val promo = when (match.groupValues[3]) {
            "queen", "q" -> "q"
            "rook", "r" -> "r"
            "bishop", "b" -> "b"
            "knight", "n" -> "n"
            else -> ""
        }
        return "${match.groupValues[1]}${match.groupValues[2]}$promo"
    }
}
