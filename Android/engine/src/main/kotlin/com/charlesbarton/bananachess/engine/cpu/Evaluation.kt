package com.charlesbarton.bananachess.engine.cpu

import com.charlesbarton.bananachess.engine.model.Board
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.Direction
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.GameStatus
import com.charlesbarton.bananachess.engine.model.Occupancy
import com.charlesbarton.bananachess.engine.model.Outcome
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.setup.GameSetup
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The static score the CPU search thinks with. Integer arithmetic only, and every
 * division truncates toward zero, so a position scores the same here as in the Swift
 * engine.
 */
internal object Evaluation {
    const val WIN_SCORE: Int = 1_000_000

    /**
     * Positive is good for [player]. Symmetric by construction: evaluating for the
     * opponent returns the negation, which is what makes minimax sound here.
     */
    fun score(
        state: GameState,
        player: PlayerId,
        weights: EvaluationWeights = EvaluationWeights(),
    ): Int {
        val status = state.status
        if (status is GameStatus.Ended) {
            return when (val outcome = status.outcome) {
                is Outcome.Win -> {
                    val margin = abs(state.scores.margin(player))
                    if (outcome.player == player) WIN_SCORE + margin else -WIN_SCORE - margin
                }

                Outcome.Draw -> {
                    0
                }
            }
        }

        val margin = state.scores.margin(player)
        var total = margin * weights.score
        total += positional(state, player, weights)
        total -= positional(state, player.opponent, weights)

        // How many moves of match are left, roughly: whichever side is nearer to empty.
        val clock = min(state.fruit(player).size, state.fruit(player.opponent).size)
        if (margin != 0) {
            total += (if (margin > 0) -clock else clock) * weights.clockPressure
        }
        if (state.ruleSet.usesWildcards) {
            total += wildcards(state, player, weights)
            total -= wildcards(state, player.opponent, weights)
        }
        return total
    }

    /**
     * Progress toward an outright win, priced by how close it is rather than by how
     * much of it is done. Both alternate victories are all-or-nothing, so what matters
     * is the number of moves still needed, and the last one is worth vastly more than
     * the first.
     */
    private fun wildcards(
        state: GameState,
        player: PlayerId,
        weights: EvaluationWeights,
    ): Int {
        var total = 0
        movesToBananaSplit(state, player)?.let { total += threatValue(it, weights) }
        movesToBlenderama(state, player)?.let { total += threatValue(it, weights) }

        total += counterReadiness(state, player, weights)
        total += state.wildcards.smoothie[player].collectedCount * weights.smoothieProgress
        if (state.goldenPineapple(player) != null) total += weights.goldenPineapple
        return total
    }

    /**
     * Credit for being *able* to answer the opponent's alternate win.
     *
     * A threat on its own is worth the same after every move that does not remove it,
     * so it cancels out of the comparison. This is the half that moves the CPU: getting
     * closer to the move that would kill the threat is worth a slice of the threat
     * itself, and the slice grows as the threat closes.
     *
     * The slice stays under the whole: it reaches at most `(SIZE - 1) / SIZE` of the
     * threat, so removing a threat always beats standing one move away from removing it.
     */
    private fun counterReadiness(
        state: GameState,
        player: PlayerId,
        weights: EvaluationWeights,
    ): Int {
        val attacker = player.opponent
        var total = 0

        // Blenderama is a shutout: it dies the moment this player scores at all, by
        // whatever route, so credit whichever route to the scoreboard is nearer.
        movesToBlenderama(state, attacker)?.let { steps ->
            // Floored at one so the slice stays strictly under the whole even if a
            // fruit is somehow already standing on the delivery row.
            val distance = max(1, movesToScore(state, player) ?: Board.SIZE)
            total += slice(threatValue(steps, weights), distance)
        }

        // A Banana Split has no such shutout and still has to be answered on the board:
        // blend the attacker's last banana before it gets across.
        movesToBananaSplit(state, attacker)?.let { steps ->
            val targets = state.fruit(attacker).filter { it.kind == PieceKind.Banana }.mapNotNull { it.position }
            if (targets.isNotEmpty()) {
                val distance = shortestBlenderApproach(player, targets, state) ?: Board.SIZE
                total += slice(threatValue(steps, weights), distance)
            }
        }
        return total
    }

    private fun slice(
        threat: Int,
        distance: Int,
    ): Int {
        val closeness = Board.SIZE - min(distance, Board.SIZE)
        return threat * closeness / Board.SIZE
    }

    private fun threatValue(
        stepsAway: Int,
        weights: EvaluationWeights,
    ): Int {
        val steps = max(1, stepsAway)
        return weights.alternateWinThreat / (steps * steps)
    }

    /**
     * An optimistic count of the moves still needed to blend every one of this player's
     * own fruit: one move per remaining fruit at the very least, plus the distance a
     * blender still has to travel to reach the nearest of them. Null once the opponent
     * has countered it, or once a fruit has left by any route but a self-blend.
     */
    private fun movesToBlenderama(
        state: GameState,
        player: PlayerId,
    ): Int? {
        if (state.wildcards.blenderamaCountered[player]) return null
        val blended = state.wildcards.ownFruitBlended[player]
        val remaining = state.fruit(player)
        if (blended == 0 && remaining.size != GameSetup.fruitCount) return null
        if (blended + remaining.size < GameSetup.fruitCount) return null
        if (remaining.isEmpty()) return null

        val approach = shortestBlenderApproach(player, remaining.mapNotNull { it.position }, state)
        return remaining.size + (approach ?: Board.SIZE)
    }

    /**
     * Likewise for a Banana Split: one delivery is worth a whole trip across the board,
     * so count the trips still needed and add how far the nearest banana has to go.
     */
    private fun movesToBananaSplit(
        state: GameState,
        player: PlayerId,
    ): Int? {
        val remaining = GameSetup.BANANA_DELIVERIES_TO_WIN - state.wildcards.bananasDelivered[player]
        if (remaining <= 0) return null
        val bananas = state.fruit(player).filter { it.kind == PieceKind.Banana }
        if (bananas.isEmpty()) return null

        val nearest =
            bananas.minOf { banana ->
                banana.position?.let { rowsToDelivery(it, player, state) } ?: (Board.SIZE - 1)
            }
        return (remaining - 1) * (Board.SIZE - 1) + nearest
    }

    /**
     * The fewest moves in which this player could put *any* points on the board: a
     * blender reaching one of the opponent's fruit, or one of their own fruit reaching
     * the delivery row, whichever is nearer. Null when they have neither route left.
     *
     * This is what answers a Blenderama under the shutout rule, and the cheap answer is
     * usually a delivery and not a blend at all.
     */
    private fun movesToScore(
        state: GameState,
        player: PlayerId,
    ): Int? {
        val approach =
            shortestBlenderApproach(player, state.fruit(player.opponent).mapNotNull { it.position }, state)
        val delivery =
            state
                .fruit(player)
                .mapNotNull { it.position }
                .minOfOrNull { rowsToDelivery(it, player, state) }
        return listOfNotNull(approach, delivery).minOrNull()
    }

    /**
     * Diagonal distance from this player's nearest on-board blender to the nearest of
     * the given squares. Null when they have no blender standing.
     */
    private fun shortestBlenderApproach(
        player: PlayerId,
        squares: List<Position>,
        state: GameState,
    ): Int? {
        val blenders = state.activeBlenders(player).mapNotNull { it.position }
        if (blenders.isEmpty() || squares.isEmpty()) return null
        return blenders.minOf { from -> squares.minOf { Board.blenderSteps(from, it) } }
    }

    private fun rowsToDelivery(
        square: Position,
        player: PlayerId,
        state: GameState,
    ): Int = Board.separation(square.row, player.deliveryRow, joined = state.boardWrap.wrapsRows)

    private fun positional(
        state: GameState,
        player: PlayerId,
        weights: EvaluationWeights,
    ): Int {
        var total = 0
        val occupancy = Occupancy(state)
        val enemyBlenderReach = blenderReach(player.opponent, state)
        val blenders = state.blenders(player)
        val ownHomes = blenders.mapNotNullTo(mutableSetOf()) { it.home }
        val destroyedSpawns = blenders.filter { it.isDestroyed }.mapNotNullTo(mutableSetOf()) { it.home }

        for (fruit in state.fruit(player)) {
            val value = fruit.kind.baseValue
            total += value * weights.fruitOnBoard
            val square = fruit.position ?: continue

            val progress = (Board.SIZE - 1) - rowsToDelivery(square, player, state)
            total += progress * value * weights.advancement

            if (square in enemyBlenderReach) total += value * weights.threatenedFruit
            if (square in ownHomes && square !in destroyedSpawns) total += weights.fruitOnOwnBlenderHome
        }

        for (blender in blenders) {
            val position = blender.position
            if (position == null) {
                // A smoothie victim is coming back for free; a combat loss only returns
                // if its owner spends a turn and three points on it.
                total += if (blender.needsBuyingBack) weights.blenderDestroyed else weights.blenderDestroyed / 4
                continue
            }
            // Camped on its own spawn: safe where it stands, dead the moment it steps
            // off. The reach is raw geometry, so it still flags this even though the
            // square itself can no longer be captured.
            if (RulesEngine.isOnASpawn(blender, state) && position in enemyBlenderReach) {
                total += weights.blenderOnCoveredSpawn
            }

            var mobility = 0
            for (step in Direction.diagonal) {
                val square = Board.step(position, step.row, step.column, BoardWrap.Flat) ?: continue
                val occupant = occupancy[square]?.let(state::piece)
                if (occupant == null) {
                    mobility += 1
                } else if (occupant.isFruit && occupant.owner != player) {
                    mobility += 1
                    total += occupant.kind.baseValue * weights.blenderThreat
                } else if (occupant.isBlender) {
                    mobility += 1
                }
                // Landing on your own fruit is legal but costs points, so it does not
                // count as somewhere this blender usefully wants to go.
            }
            total += mobility * weights.blenderMobility
        }

        return total
    }

    /**
     * Squares the player's blenders could land on next move, used for both threat
     * terms. A knocked-out blender reaches nothing.
     */
    private fun blenderReach(
        player: PlayerId,
        state: GameState,
    ): Set<Position> {
        val reach = mutableSetOf<Position>()
        for (blender in state.activeBlenders(player)) {
            val position = blender.position ?: continue
            for (step in Direction.diagonal) {
                Board.step(position, step.row, step.column, BoardWrap.Flat)?.let(reach::add)
            }
        }
        return reach
    }
}
