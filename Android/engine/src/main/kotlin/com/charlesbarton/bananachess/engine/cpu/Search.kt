package com.charlesbarton.bananachess.engine.cpu

import com.charlesbarton.bananachess.engine.model.BattleResolution
import com.charlesbarton.bananachess.engine.model.Board
import com.charlesbarton.bananachess.engine.model.GameAction
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.RestoreBlender
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.rules.RulesError
import kotlin.math.max
import kotlin.math.min

/**
 * The position after [action] as the search sees it: battles roll the average and a
 * repetition may be considered. Null when the rules refuse the action, which the CPU
 * treats as "skip this move".
 */
internal fun GameState.searchedAfter(action: GameAction): GameState? =
    try {
        RulesEngine.apply(action, this, BattleResolution.Expected, enforcingRepetition = false).state
    } catch (_: RulesError) {
        null
    }

/** Highest rank first, and the higher action first among equals, so the order is total. */
private val byRankThenAction: Comparator<Ranked> =
    Comparator { a, b -> if (a.rank != b.rank) b.rank.compareTo(a.rank) else b.action.compareTo(a.action) }

private class Ranked(
    val action: GameAction,
    val rank: Int,
)

/** The receiver ordered by [rank] descending, then action descending. */
internal fun List<GameAction>.rankedBy(rank: (GameAction) -> Int): List<GameAction> {
    val ranked = map { Ranked(it, rank(it)) }
    return ranked.sortedWith(byRankThenAction).map { it.action }
}

/**
 * One alpha-beta search for one move. Holds the transposition table and the node
 * count, so it is single-use and not thread-safe.
 *
 * Time is read from [clock] in nanoseconds; the search gives up once [hardCapNanos]
 * have passed since [startNanos], or once [shouldStop] says so. Both are polled at
 * the same points.
 */
internal class Search(
    private val root: PlayerId,
    private val weights: EvaluationWeights,
    private val clock: () -> Long,
    private val startNanos: Long,
    private val hardCapNanos: Long,
    private val quiescenceDepth: Int,
    private val shouldStop: () -> Boolean = { false },
) {
    private val transpositions = HashMap<ULong, Entry>()
    private var timedOut = false

    var nodes: Int = 0
        private set

    private enum class Bound { Exact, Lower, Upper }

    private class Entry(
        val depth: Int,
        val value: Int,
        val bound: Bound,
        /**
         * The move that produced [value]. Kept even when the entry is too shallow to
         * answer the current query: it is still the first thing worth trying deeper.
         */
        val move: GameAction?,
    )

    class Result(
        val move: GameAction,
        val value: Int,
        /** Every root move's value, so the caller can tell a real preference from a tie. */
        val values: Map<GameAction, Int>,
    )

    /** Null when the clock ran out part way through: a partial iteration is untrustworthy. */
    fun bestMove(
        state: GameState,
        moves: List<GameAction>,
        depth: Int,
    ): Result? {
        var alpha = -Evaluation.WIN_SCORE * 2
        val beta = Evaluation.WIN_SCORE * 2
        var bestMove: GameAction? = null
        var bestValue = 0
        val values = HashMap<GameAction, Int>()

        for (move in moves) {
            val next = state.searchedAfter(move) ?: continue
            val value = minimax(next, depth - 1, alpha, beta)
            if (timedOut) return null
            values[move] = value
            if (bestMove == null || value > bestValue) {
                bestMove = move
                bestValue = value
                alpha = max(alpha, value)
            }
        }
        return bestMove?.let { Result(it, bestValue, values) }
    }

    /**
     * Minimax and not negamax: a player with no legal move is skipped, so the side to
     * move does not simply alternate and the sign cannot be flipped blindly.
     */
    private fun minimax(
        state: GameState,
        depth: Int,
        givenAlpha: Int,
        givenBeta: Int,
    ): Int {
        if (countNodeAndCheckClock()) return 0

        if (depth <= 0 || state.status.isOver) return quiesce(state, givenAlpha, givenBeta, quiescenceDepth)

        val key = state.positionKey
        var hint: GameAction? = null
        transpositions[key]?.let { entry ->
            hint = entry.move
            if (entry.depth >= depth) {
                when (entry.bound) {
                    Bound.Exact -> return entry.value
                    Bound.Lower -> if (entry.value >= givenBeta) return entry.value
                    Bound.Upper -> if (entry.value <= givenAlpha) return entry.value
                }
            }
        }

        // Unenforced inside the search: an enforced legality query plays out every
        // candidate to see whether it repeats, a whole extra ply of work per ply. The
        // move finally played still comes from a fully enforced list at the root.
        val moves = RulesEngine.legalActions(state, enforcingRepetition = false)
        if (moves.isEmpty()) return Evaluation.score(state, root, weights)

        val maximizing = state.activePlayer == root
        var alpha = givenAlpha
        var beta = givenBeta
        var best = if (maximizing) Int.MIN_VALUE else Int.MAX_VALUE
        var bestMove: GameAction? = null

        for (move in ordered(moves, state, hint)) {
            val next = state.searchedAfter(move) ?: continue
            val value = minimax(next, depth - 1, alpha, beta)
            if (timedOut) return if (bestMove == null) 0 else best

            if (maximizing) {
                if (bestMove == null || value > best) {
                    best = value
                    bestMove = move
                }
                alpha = max(alpha, best)
            } else {
                if (bestMove == null || value < best) {
                    best = value
                    bestMove = move
                }
                beta = min(beta, best)
            }
            if (alpha >= beta) break
        }

        // A stored bound has to be classified against the window this node was *given*,
        // not the one it narrowed to: by now alpha has been raised to `best`, so
        // comparing against it would file almost every bound as exact.
        val bound =
            when {
                best <= givenAlpha -> Bound.Upper
                best >= givenBeta -> Bound.Lower
                else -> Bound.Exact
            }
        transpositions[key] = Entry(depth, best, bound, bestMove)
        return best
    }

    /**
     * Keeps following *forcing* moves past the nominal depth so a line is never judged
     * half way through an exchange.
     *
     * Standing pat is the value of declining to continue. A player here must actually
     * move, so declining is not strictly available; it is the usual approximation.
     */
    private fun quiesce(
        state: GameState,
        givenAlpha: Int,
        givenBeta: Int,
        remaining: Int,
    ): Int {
        // Returned before the node is counted, so a level with quiescence off polls
        // the clock on the same nodes as it would if this did not exist.
        if (remaining <= 0 || state.status.isOver) return Evaluation.score(state, root, weights)

        if (countNodeAndCheckClock()) return 0

        val standPat = Evaluation.score(state, root, weights)
        val maximizing = state.activePlayer == root
        var alpha = givenAlpha
        var beta = givenBeta
        if (maximizing) {
            if (standPat >= beta) return standPat
            alpha = max(alpha, standPat)
        } else {
            if (standPat <= alpha) return standPat
            beta = min(beta, standPat)
        }

        val forcing = RulesEngine.legalActions(state, enforcingRepetition = false).filter { isForcing(it, state) }
        if (forcing.isEmpty()) return standPat

        var best = standPat
        for (move in ordered(forcing, state, hint = null)) {
            val next = state.searchedAfter(move) ?: continue
            val value = quiesce(next, alpha, beta, remaining - 1)
            if (timedOut) return best
            if (maximizing) {
                best = max(best, value)
                alpha = max(alpha, best)
            } else {
                best = min(best, value)
                beta = min(beta, best)
            }
            if (alpha >= beta) break
        }
        return best
    }

    /** Counts this node and reports whether the search has run out of time or been told to stop. */
    private fun countNodeAndCheckClock(): Boolean {
        nodes += 1
        if (nodes % TIME_CHECK_INTERVAL == 0 && (shouldStop() || clock() - startNanos >= hardCapNanos)) {
            timedOut = true
        }
        return timedOut
    }

    private fun ordered(
        moves: List<GameAction>,
        state: GameState,
        hint: GameAction?,
    ): List<GameAction> = moves.rankedBy { if (it == hint) Int.MAX_VALUE else staticRank(it, state) }

    companion object {
        /** The clock decides where the search actually stops; this ceiling is not what binds. */
        const val MAXIMUM_DEPTH: Int = 12

        /** Checking a clock is not free; do it every this-many nodes. */
        const val TIME_CHECK_INTERVAL: Int = 512

        /**
         * A move that settles something: a fruit reaching its delivery row, or a blender
         * arriving on an occupied square. Decided without applying the move.
         */
        fun isForcing(
            action: GameAction,
            state: GameState,
        ): Boolean {
            if (action !is Move) return false
            val piece = state.piece(action.pieceId) ?: return false
            if (piece.isFruit) return action.destination.row == piece.owner.deliveryRow
            return state.pieceAt(action.destination) != null
        }

        /**
         * Cheap ordering heuristic: no move is applied. Immediate points first, then
         * fruit getting closer to home, then everything else. Self-blends sort last.
         */
        fun staticRank(
            action: GameAction,
            state: GameState,
        ): Int =
            when (action) {
                is RestoreBlender -> -60
                is Move -> staticRank(action, state)
            }

        private fun staticRank(
            move: Move,
            state: GameState,
        ): Int {
            val piece = state.piece(move.pieceId) ?: return 0

            if (piece.isFruit) {
                val deliveryRow = piece.owner.deliveryRow
                if (move.destination.row == deliveryRow) return 1_000 + piece.kind.deliveryValue * 10
                val joined = state.boardWrap.wrapsRows
                val before = Board.separation(move.origin.row, deliveryRow, joined)
                val after = Board.separation(move.destination.row, deliveryRow, joined)
                return (before - after) * piece.kind.baseValue * 4
            }

            val occupant = state.pieceAt(move.destination) ?: return 1
            val isOwn = occupant.owner == piece.owner
            if (occupant.isFruit) {
                val value = 800 + occupant.kind.baseValue * 10
                return if (isOwn) -value else value
            }
            // Destroying their blender is the strongest thing a blender can do; wrecking
            // your own is the weakest.
            return if (isOwn) -900 else 900
        }
    }
}
