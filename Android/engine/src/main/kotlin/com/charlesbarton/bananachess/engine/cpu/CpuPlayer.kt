package com.charlesbarton.bananachess.engine.cpu

import com.charlesbarton.bananachess.engine.model.GameAction
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.random.SplitMix64
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Picks one legal action for a difficulty from a snapshot. It never applies its own
 * move: the caller pushes the result back through [RulesEngine.apply], so the CPU has
 * no private path into the game state.
 *
 * [selectAction] is plain synchronous code that can run for as long as the plan's hard
 * time cap, so the caller chooses the thread, and can end it sooner through `shouldStop`.
 * A player holds no state between calls and may be shared.
 */
class CpuPlayer(
    val difficulty: CpuDifficulty,
    val weights: EvaluationWeights = EvaluationWeights(),
    /** Set for reproducible play in tests; null uses the system generator. */
    val seed: ULong? = null,
    /** Overridable so two levels can be put head to head with other budgets. */
    val plan: SearchPlan = difficulty.plan,
    /** Monotonic time in nanoseconds. Replaced in tests so nothing waits on a real clock. */
    private val clock: () -> Long = System::nanoTime,
) {
    /** What a pick was based on, for tests and measurements. */
    internal data class Analysis(
        val action: GameAction?,
        /** The deepest iteration that finished; one for a one-ply pick and zero for a random one. */
        val completedDepth: Int,
        val nodes: Int,
    )

    /** Convenience for callers that only care about moves. */
    fun selectMove(
        state: GameState,
        shouldStop: () -> Boolean = { false },
    ): Move? = selectAction(state, shouldStop) as? Move

    /**
     * @param shouldStop polled wherever the search checks its clock, from the thread running
     *   the search. Once it returns true the search ends as if out of time, and what it
     *   returns is only a best guess, meant to be thrown away by a caller that has stopped
     *   wanting it.
     */
    fun selectAction(
        state: GameState,
        shouldStop: () -> Boolean = { false },
    ): GameAction? = analyse(state, shouldStop).action

    internal fun analyse(
        state: GameState,
        shouldStop: () -> Boolean = { false },
    ): Analysis {
        val moves = RulesEngine.legalActions(state)
        return when {
            moves.isEmpty() -> Analysis(null, completedDepth = 0, nodes = 0)
            plan.candidateFraction >= 1 -> Analysis(pickAtRandom(moves, state), completedDepth = 0, nodes = 0)
            plan.depth <= 1 -> Analysis(pickShallow(moves, state), completedDepth = 1, nodes = 0)
            else -> pickBestSearch(moves, state, shouldStop)
        }
    }

    /** One ply, then a pick from the better end of what it saw. */
    private fun pickShallow(
        moves: List<GameAction>,
        state: GameState,
    ): GameAction {
        val player = state.activePlayer
        val values = HashMap<GameAction, Int>()
        for (move in moves) {
            val next = state.searchedAfter(move) ?: continue
            values[move] = Evaluation.score(next, player, weights)
        }
        if (values.isEmpty()) return moves[0]

        val ranked = values.keys.toList().rankedBy { values.getValue(it) }
        if (plan.candidateFraction <= 0) {
            val best = values.getValue(ranked[0])
            return breakTie(ranked.filter { values.getValue(it) == best }, state)
        }
        val pool = max(1, ceil(ranked.size * plan.candidateFraction).toInt())
        return breakTie(ranked.take(pool), state)
    }

    private fun pickBestSearch(
        moves: List<GameAction>,
        state: GameState,
        shouldStop: () -> Boolean,
    ): Analysis {
        val start = clock()
        val softBudget = plan.softTimeBudget.inWholeNanoseconds
        val search =
            Search(
                root = state.activePlayer,
                weights = weights,
                clock = clock,
                startNanos = start,
                hardCapNanos = plan.hardTimeCap.inWholeNanoseconds,
                quiescenceDepth = plan.quiescenceDepth,
                shouldStop = shouldStop,
            )

        var ordered = moves.rankedBy { Search.staticRank(it, state) }
        var best = ordered[0]
        var values: Map<GameAction, Int> = emptyMap()
        var completedDepth = 0
        var depth = 2

        while (depth <= min(plan.depth, Search.MAXIMUM_DEPTH)) {
            if (shouldStop() || clock() - start >= softBudget) break
            // Out of time mid-depth: the last fully completed depth stands.
            val result = search.bestMove(state, ordered, depth) ?: break
            best = result.move
            values = result.values
            completedDepth = depth
            // Hand the next iteration this one's ranking: searching the previous best
            // first is what lets the next depth establish a tight window early.
            val ranking = values
            ordered = ordered.rankedBy { ranking[it] ?: Int.MIN_VALUE }
            depth += 1
            // A forced win is found, not improved on.
            if (abs(result.value) >= Evaluation.WIN_SCORE) break
        }
        return Analysis(preferringTempo(best, values, state), completedDepth, search.nodes)
    }

    /**
     * Breaks a near-tie toward banking the points now. Deep search has a horizon, and
     * a delivery that cannot be interfered with scores the same taken this move or
     * next; a player watching a computer shuffle while sitting on free points sees a bug.
     */
    private fun preferringTempo(
        best: GameAction,
        values: Map<GameAction, Int>,
        state: GameState,
    ): GameAction {
        if (plan.tempoMargin <= 0) return best
        val bestValue = values[best] ?: return best
        val contenders = values.filterValues { it >= bestValue - plan.tempoMargin }.keys
        if (contenders.size <= 1) return best
        // Safe against alpha-beta's fail-low values, which are only upper bounds: high
        // ranks are searched first, so anything this can promote was searched before
        // the winner and has an exact value.
        val front = contenders.toList().rankedBy { Search.staticRank(it, state) }[0]
        // Only overrides when the alternative actually banks something.
        return if (Search.staticRank(front, state) > Search.staticRank(best, state)) front else best
    }

    private fun breakTie(
        moves: List<GameAction>,
        state: GameState,
    ): GameAction = if (moves.size > 1) pickAtRandom(moves, state) else moves[0]

    /**
     * Reproducible for a given game when seeded, but deliberately not a pure function
     * of the position: shuffling a fruit back and forth returns to the same position,
     * and a position-only seed would answer it with the same move forever.
     */
    private fun pickAtRandom(
        moves: List<GameAction>,
        state: GameState,
    ): GameAction {
        val seed = seed ?: return moves.random()
        val generator = SplitMix64(seed xor state.positionKey xor state.turnNumber.toLong().toULong())
        return moves[generator.nextBelow(moves.size.toULong()).toInt()]
    }
}
