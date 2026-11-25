package com.charlesbarton.bananachess.engine.rules

import com.charlesbarton.bananachess.engine.model.BattleReport
import com.charlesbarton.bananachess.engine.model.BattleResolution
import com.charlesbarton.bananachess.engine.model.Board
import com.charlesbarton.bananachess.engine.model.Direction
import com.charlesbarton.bananachess.engine.model.GameAction
import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.GameStatus
import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.model.Occupancy
import com.charlesbarton.bananachess.engine.model.Outcome
import com.charlesbarton.bananachess.engine.model.Piece
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.model.RestoreBlender
import com.charlesbarton.bananachess.engine.model.Scores
import com.charlesbarton.bananachess.engine.random.SplitMix64

/**
 * The single deterministic transition path shared by local play, the CPU, and the
 * network. Stateless by design: every function is a pure function of the state given.
 *
 * A port of `Engine/Sources/BananaChessEngine/RulesEngine.swift`, which is the
 * specification. The wildcard rules live in [WildcardRules].
 */
object RulesEngine {
    /**
     * Bumped whenever a rules change would make two clients disagree. Encoded into every
     * online payload. The history of each bump is in the Swift source.
     */
    const val RULES_VERSION: Int = 18

    /**
     * Points it costs to put a destroyed blender back on its spawn, which also takes the
     * whole turn. Scores may go negative paying it: there is no affordability check,
     * deliberately.
     */
    const val BLENDER_RESTORE_COST: Int = 3

    /**
     * How many times one arrangement may stand on the board. A move that would make an
     * arrangement happen for the third time is simply not available to that player.
     * This is what finally bounds a match.
     */
    const val REPETITION_LIMIT: Int = 3

    /**
     * What a blender adds to its side of a battle for being the one going in. One, and
     * paired with ties going to the defender.
     */
    const val BATTLE_ATTACKER_BONUS: Int = 1

    /** What completing a Tropical Smoothie pays its maker, once per match. */
    const val SMOOTHIE_AWARD: Int = 3

    /**
     * How long the opponent went without blenders when a smoothie blew them up.
     *
     * **Retained, unused.** Smoothies pay points now. This and the pour are kept intact
     * and tested so the blowout can be switched back on.
     */
    const val SMOOTHIE_BLACKOUT_TURNS: Int = 2

    /** The new state and the ordered events one action produced. */
    data class Transition(
        val state: GameState,
        val events: List<GameEvent>,
    )

    // Legal moves

    /**
     * Whether this action would put the board into an arrangement that has already stood
     * twice, the third occurrence being the one the rule forbids.
     *
     * Answered by playing the action out, because a turn does more than move one piece:
     * pieces come back and the turn changes hands, all of which are part of the
     * arrangement. The simulation runs with the rule switched off, which is what stops
     * this from asking itself the same question forever.
     */
    internal fun wouldRepeat(
        action: GameAction,
        state: GameState,
    ): Boolean {
        val next =
            try {
                apply(action, state, enforcingRepetition = false)
            } catch (_: RulesError) {
                return false
            }
        return state.timesSeen(next.state.boardKey) >= REPETITION_LIMIT - 1
    }

    /** The active player's moves, in canonical order. */
    fun legalMoves(state: GameState): List<Move> = legalMoves(state.activePlayer, state)

    fun legalMoves(
        player: PlayerId,
        state: GameState,
        enforcingRepetition: Boolean = true,
    ): List<Move> {
        if (state.status.isOver) return emptyList()
        val occupancy = Occupancy(state)
        return state
            .pieces(player)
            .flatMap { legalMoves(it, state, occupancy) }
            .filter { !enforcingRepetition || !wouldRepeat(it, state) }
            .sorted()
    }

    fun legalMoves(
        pieceId: PieceId,
        state: GameState,
        enforcingRepetition: Boolean = true,
    ): List<Move> {
        if (state.status.isOver) return emptyList()
        val piece = state.piece(pieceId) ?: return emptyList()
        return legalMoves(piece, state, Occupancy(state))
            .filter { !enforcingRepetition || !wouldRepeat(it, state) }
            .sorted()
    }

    /** Where [piece] may step, before the repetition rule. Whose turn it is plays no part. */
    internal fun legalMoves(
        piece: Piece,
        state: GameState,
        occupancy: Occupancy,
    ): List<Move> {
        // A piece off the board has no moves.
        val origin = piece.position ?: return emptyList()
        val steps = if (piece.isBlender) Direction.diagonal else Direction.orthogonal
        val wrap = state.edgesFor(piece)
        return steps.mapNotNull { step ->
            val destination = Board.step(origin, step.row, step.column, wrap) ?: return@mapNotNull null
            if (canLand(piece, destination, state, occupancy)) Move(piece.id, origin, destination) else null
        }
    }

    private fun canLand(
        piece: Piece,
        destination: Position,
        state: GameState,
        occupancy: Occupancy,
    ): Boolean {
        val occupant = occupancy[destination]?.let(state::piece) ?: return true

        // Fruit never captures, including onto a blocked delivery square.
        if (!piece.isBlender) return false

        // A blender standing on one of its owner's spawn squares cannot be taken, by
        // either side. Home is safe: the square a blender is bought back onto is not a
        // square it can be knocked straight off again.
        // Otherwise a blender may land on anything: any fruit, whoever grew it, and any
        // other blender, its owner's included.
        return !(occupant.isBlender && isOnASpawn(occupant, state))
    }

    /**
     * Whether a blender is standing on one of *its owner's* spawn squares. Either of
     * them: a destroyed blender may be bought back onto either, so both are home.
     */
    internal fun isOnASpawn(
        blender: Piece,
        state: GameState,
    ): Boolean {
        if (!blender.isBlender) return false
        val square = blender.position ?: return false
        return state.pieces.any { it.isBlender && it.owner == blender.owner && it.home == square }
    }

    // Validation

    /**
     * Throws the [RulesError] that says why [action] is not available, or returns.
     * Does not apply the repetition rule; [apply] does.
     */
    fun validate(
        action: GameAction,
        state: GameState,
    ) {
        if (state.status.isOver) throw RulesError.GameOver
        when (action) {
            is RestoreBlender -> validateRestore(action, state)
            is Move -> validateMove(action, state)
        }
    }

    private fun validateRestore(
        action: RestoreBlender,
        state: GameState,
    ) {
        val id = action.pieceId
        val blender = state.piece(id) ?: throw RulesError.UnknownPiece(id)
        if (blender.owner != state.activePlayer) throw RulesError.NotActivePlayersPiece(id)
        if (!blender.isDestroyed) throw RulesError.BlenderNotDestroyed(id)
        if (!blender.needsBuyingBack) throw RulesError.BlenderReturningOnItsOwn(id)
        if (action.at !in spawnSquares(blender.owner, state)) throw RulesError.NotASpawnSquare(action.at)
        if (state.pieceAt(action.at) != null) throw RulesError.SpawnOccupied(id)
    }

    private fun validateMove(
        move: Move,
        state: GameState,
    ) {
        val piece = state.piece(move.pieceId) ?: throw RulesError.UnknownPiece(move.pieceId)
        if (piece.owner != state.activePlayer) throw RulesError.NotActivePlayersPiece(move.pieceId)
        val origin = piece.position ?: throw RulesError.PieceIsOffBoard(move.pieceId)
        if (origin != move.origin) throw RulesError.OriginMismatch(expected = origin, given = move.origin)
        if (move !in legalMoves(piece, state, Occupancy(state))) {
            throw RulesError.IllegalDestination(move.destination)
        }
    }

    // Legal actions

    /**
     * Every action the active player may take, in canonical order: moves, plus any
     * blender of theirs that can be put back onto a clear spawn.
     */
    fun legalActions(
        state: GameState,
        enforcingRepetition: Boolean = true,
    ): List<GameAction> {
        if (state.status.isOver) return emptyList()

        val actions = mutableListOf<GameAction>()
        actions += legalMoves(state.activePlayer, state, enforcingRepetition)
        val spawns = spawnSquares(state.activePlayer, state)
        for (blender in state.destroyedBlenders(state.activePlayer)) {
            if (!blender.needsBuyingBack) continue
            for (spawn in spawns) {
                if (state.pieceAt(spawn) != null) continue
                val restore = RestoreBlender(blender.id, spawn)
                if (enforcingRepetition && wouldRepeat(restore, state)) continue
                actions += restore
            }
        }
        return actions.sorted()
    }

    /** Actions available to a player who is not necessarily the active one. Used by the skip check. */
    private fun legalActions(
        player: PlayerId,
        state: GameState,
        enforcingRepetition: Boolean,
    ): List<GameAction> = legalActions(state.updated(activePlayer = player), enforcingRepetition)

    // Apply

    /**
     * Applies one action and returns the resulting state plus the ordered events it
     * produced. [state] itself is never changed.
     *
     * Within a move: the piece moves, then combat or a blend at its destination
     * resolves, then delivery. The turn then advances, and anything due back returns
     * before the next player can pick a piece.
     *
     * @throws RulesError when the action is not available.
     */
    fun apply(
        action: GameAction,
        state: GameState,
        battles: BattleResolution = BattleResolution.Dice,
        enforcingRepetition: Boolean = true,
    ): Transition {
        validate(action, state)
        if (enforcingRepetition && wouldRepeat(action, state)) throw RulesError.WouldRepeatPosition

        val turn = TurnInProgress(state)
        when (action) {
            is RestoreBlender -> restore(action, turn)
            is Move -> move(action, battles, turn)
        }

        turn.state = turn.state.updated(history = turn.state.history + action)
        WildcardRules.announceGoldenPineapples(previous = state, turn = turn)
        WildcardRules.recordScoringTurn(state.activePlayer, state.scores, turn)
        finishTurn(turn, enforcingRepetition)
        // Recorded last, once the turn has actually changed hands: the arrangement
        // includes whose move it is, so counting it any earlier would file it under
        // the wrong player and the rule would never match anything.
        return Transition(turn.state.recordingArrangement(), turn.events)
    }

    private fun move(
        move: Move,
        battles: BattleResolution,
        turn: TurnInProgress,
    ) {
        // Validation has already proved the piece exists and belongs to the active player.
        val piece = turn.state.piece(move.pieceId) ?: throw RulesError.UnknownPiece(move.pieceId)
        val occupant = turn.state.pieceAt(move.destination)

        turn.emit(GameEvent.Moved(piece.id, move.origin, move.destination))

        if (!piece.isBlender) {
            turn.setPosition(move.destination, piece.id)
            if (move.destination.row == piece.owner.deliveryRow) deliver(piece, move.destination, turn)
            return
        }
        if (occupant != null && occupant.isBlender) {
            resolveCollision(attacker = piece, defender = occupant, square = move.destination, battles, turn)
            return
        }
        if (occupant != null) blend(occupant, move.destination, blendedBy = piece.owner, turn)
        turn.setPosition(move.destination, piece.id)
    }

    /**
     * Settles a blender running into another: a battle if the match is playing them,
     * otherwise the plain kill, where the defender is destroyed and the attacker takes
     * the square.
     *
     * A battle is only ever between two *players*, so running into one of your own
     * blenders still destroys it outright: there is nobody to roll against.
     */
    private fun resolveCollision(
        attacker: Piece,
        defender: Piece,
        square: Position,
        battles: BattleResolution,
        turn: TurnInProgress,
    ) {
        if (!turn.state.blenderBattles || defender.owner == attacker.owner) {
            destroy(victim = defender, by = attacker, square, turn)
            turn.setPosition(square, attacker.id)
            return
        }

        // Nothing has moved yet, so this is still the position the move was made from.
        val report = battleReport(attacker, defender, square, battles, turn.state)
        turn.emit(GameEvent.BlenderBattle(report, square))

        if (report.attackerWins) {
            destroy(victim = defender, by = attacker, square, turn)
            turn.setPosition(square, attacker.id)
        } else {
            // The attack failed: the attacker is the one that leaves, and the defender
            // holds the square it was already standing on.
            destroy(victim = attacker, by = defender, square, turn)
        }
    }

    /**
     * Both dice come from the position itself, so two devices given the same move
     * compute the same battle. Salted differently per side so the two rolls are not the
     * same number, and salted with the square so two battles in one position differ.
     */
    private fun battleReport(
        attacker: Piece,
        defender: Piece,
        square: Position,
        battles: BattleResolution,
        state: GameState,
    ): BattleReport {
        val attackerRoll: Int
        val defenderRoll: Int
        when (battles) {
            BattleResolution.Dice -> {
                val base = state.positionKey + Occupancy.index(square).toULong()
                attackerRoll = die(base + ATTACKER_DIE_SALT)
                defenderRoll = die(base + DEFENDER_DIE_SALT)
            }

            BattleResolution.Expected -> {
                // Any equal pair does: being the same on both sides it cancels. Four is
                // the rounded mean of a die, so the totals the CPU reasons about are at
                // least the right size.
                attackerRoll = EXPECTED_ROLL
                defenderRoll = EXPECTED_ROLL
            }
        }

        return BattleReport(
            attacker = attacker.owner,
            attackerFruit = state.battleStrength(attacker.owner),
            attackerBonus = BATTLE_ATTACKER_BONUS,
            attackerRoll = attackerRoll,
            defenderFruit = state.battleStrength(defender.owner),
            defenderRoll = defenderRoll,
        )
    }

    private const val ATTACKER_DIE_SALT: ULong = 0x9E3779B9uL
    private const val DEFENDER_DIE_SALT: ULong = 0x85EBCA6BuL
    private const val DIE_SIDES: ULong = 6uL
    private const val EXPECTED_ROLL: Int = 4

    /** The remainder is taken unsigned, as the Swift engine takes it. */
    private fun die(seed: ULong): Int = (SplitMix64(seed).next() % DIE_SIDES).toInt() + 1

    private fun destroy(
        victim: Piece,
        by: Piece,
        square: Position,
        turn: TurnInProgress,
    ) {
        turn.setPosition(null, victim.id)
        turn.emit(GameEvent.BlenderDestroyed(victim.id, victim.owner, by = by.id, at = square))
    }

    /**
     * Every square this player's blenders spawn on, in order. A destroyed blender may be
     * bought back onto either of them, not only the one it started on.
     */
    fun spawnSquares(
        player: PlayerId,
        state: GameState,
    ): List<Position> = state.blenders(player).mapNotNull { it.home }.sorted()

    /**
     * Buying a destroyed blender back: the whole turn, and three points off the score,
     * which is allowed to go negative.
     */
    private fun restore(
        action: RestoreBlender,
        turn: TurnInProgress,
    ) {
        val blender = turn.state.piece(action.pieceId) ?: return
        turn.setPosition(action.at, blender.id)
        turn.addPoints(blender.owner, -BLENDER_RESTORE_COST)
        turn.emit(GameEvent.BlenderRestored(blender.id, blender.owner, action.at, BLENDER_RESTORE_COST))
        turn.emitScore(blender.owner)
    }

    // Resolution

    /**
     * Signed blending: taking the other player's fruit pays its base value, taking your
     * own costs it. Blending never uses the delivery multiplier, and scores are
     * deliberately allowed below zero: clamping would hide the price of clearing your
     * own path.
     */
    private fun blend(
        fruit: Piece,
        square: Position,
        blendedBy: PlayerId,
        turn: TurnInProgress,
    ) {
        val magnitude = WildcardRules.blendValue(fruit, turn.state)
        val points = if (fruit.owner == blendedBy) -magnitude else magnitude

        turn.remove(fruit.id)
        turn.addPoints(blendedBy, points)
        turn.emit(GameEvent.Blended(fruit.id, fruit.kind, fruit.owner, square, awardedTo = blendedBy, points))
        turn.emitScore(blendedBy)

        WildcardRules.recordBlend(fruit, blendedBy, turn)
    }

    private fun deliver(
        fruit: Piece,
        square: Position,
        turn: TurnInProgress,
    ) {
        val points = WildcardRules.deliveryValue(fruit, turn.state)

        if (WildcardRules.recyclesOnDelivery(fruit, turn)) {
            turn.setPosition(null, fruit.id)
        } else {
            turn.remove(fruit.id)
        }
        turn.addPoints(fruit.owner, points)
        turn.emit(GameEvent.Delivered(fruit.id, fruit.kind, fruit.owner, square, points))
        turn.emitScore(fruit.owner)
    }

    // Turn and end state

    /**
     * [enforcingRepetition] is threaded all the way down to the skip check, and that is
     * the whole reason it exists. Deciding whether the next player has a move is itself
     * a legality question, so a simulated turn that asked it *with* the rule on would
     * simulate another turn to answer it, for ever. Inside a simulation the skip check
     * therefore ignores repetition.
     */
    private fun finishTurn(
        turn: TurnInProgress,
        enforcingRepetition: Boolean,
    ) {
        // Alternate victories win outright, so they are checked before anything else
        // gets a chance to happen.
        WildcardRules.win(turn.state)?.let { return end(turn, it) }

        turn.state = turn.state.updated(turnNumber = turn.state.turnNumber + 1)
        // Anything due back returns before the next player picks anything up.
        WildcardRules.returnWaitingBananas(turn)
        WildcardRules.returnScheduledBlenders(turn)

        WildcardRules.win(turn.state)?.let { return end(turn, it) }

        if (isFruitDepleted(turn.state)) return end(turn, outcome(turn.state.scores))

        val current = turn.state.activePlayer
        val opponent = current.opponent
        if (legalActions(opponent, turn.state, enforcingRepetition).isNotEmpty()) {
            turn.state = turn.state.updated(activePlayer = opponent)
            turn.emit(GameEvent.TurnChanged(opponent))
        } else if (legalActions(current, turn.state, enforcingRepetition).isNotEmpty()) {
            // A player with nothing legal to do is skipped rather than deadlocking the match.
            turn.emit(GameEvent.TurnPassed(skipped = opponent))
            turn.emit(GameEvent.TurnChanged(current))
        } else {
            end(turn, outcome(turn.state.scores))
        }
    }

    /**
     * The match ends the moment *either* player runs out of fruit, not when the board is
     * empty. Fruit still standing has never scored and never will, so whoever is ahead
     * at that instant wins. A banana in transit between the far edge and its spawn still
     * counts as being in play.
     */
    fun isFruitDepleted(state: GameState): Boolean = PlayerId.entries.any { state.fruit(it).isEmpty() }

    private fun end(
        turn: TurnInProgress,
        outcome: Outcome,
    ) {
        turn.state = turn.state.updated(status = GameStatus.Ended(outcome))
        turn.emit(GameEvent.GameEnded(outcome))
    }

    /** Who wins on points alone. */
    fun outcome(scores: Scores): Outcome =
        when {
            scores.one > scores.two -> Outcome.Win(PlayerId.One)
            scores.two > scores.one -> Outcome.Win(PlayerId.Two)
            else -> Outcome.Draw
        }
}
