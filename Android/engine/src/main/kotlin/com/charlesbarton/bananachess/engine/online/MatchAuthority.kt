package com.charlesbarton.bananachess.engine.online

import com.charlesbarton.bananachess.engine.model.GameAction
import com.charlesbarton.bananachess.engine.model.GameEvent
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.rules.RulesEngine

/** Why a turn or an update was refused. [message] is written for the player. */
sealed class MatchAuthorityError(
    override val message: String,
) : Exception(message) {
    data object NotLocalTurn : MatchAuthorityError("It is not your turn.")

    data object MatchIdMismatch : MatchAuthorityError("That update belongs to a different match.")

    data class StaleMoveNumber(
        val expected: Int,
        val received: Int,
    ) : MatchAuthorityError("That update is older than the state already stored.")

    data class DuplicateSubmission(
        val moveNumber: Int,
    ) : MatchAuthorityError("That turn has already been submitted.")

    data object MatchFinished : MatchAuthorityError("This match has finished.")
}

/**
 * One device's copy of a shared match, with the ordering rules that keep two devices
 * from disagreeing. Preparing a turn is pure: it produces the payload to send without
 * changing anything, so a failed send can be retried with the identical payload and can
 * never score twice.
 *
 * Immutable: [commit] and [adopt] return the authority that results.
 */
data class MatchAuthority(
    val payload: MatchPayload,
    val localPlayer: PlayerId,
) {
    val state: GameState get() = payload.state
    val moveNumber: Int get() = payload.moveNumber

    val isLocalTurn: Boolean get() = !payload.state.status.isOver && payload.state.activePlayer == localPlayer

    data class PreparedTurn(
        val payload: MatchPayload,
        val events: List<GameEvent>,
        /**
         * The move number this turn expects to already be stored. A mismatch means
         * someone else wrote in between and the turn must be rebuilt.
         */
        val expectedPredecessor: Int,
    )

    /**
     * Builds, but does not commit, the payload for one local action.
     *
     * @throws MatchAuthorityError when the match is over or it is not the local player's turn.
     * @throws com.charlesbarton.bananachess.engine.rules.RulesError when the rules refuse the action.
     */
    fun prepareTurn(action: GameAction): PreparedTurn {
        if (payload.state.status.isOver) throw MatchAuthorityError.MatchFinished
        if (!isLocalTurn) throw MatchAuthorityError.NotLocalTurn

        val transition = RulesEngine.apply(action, payload.state)
        val next =
            MatchPayload(
                matchId = payload.matchId,
                moveNumber = payload.moveNumber + 1,
                state = transition.state,
                lastMove = LastMove(action, playedBy = localPlayer, events = transition.events),
            )
        return PreparedTurn(next, transition.events, expectedPredecessor = payload.moveNumber)
    }

    /**
     * Called once the turn has gone out. Refuses a second commit of the same turn, which
     * is what a retry after an ambiguous failure looks like.
     *
     * @throws MatchAuthorityError
     */
    fun commit(turn: PreparedTurn): MatchAuthority {
        if (turn.payload.matchId != payload.matchId) throw MatchAuthorityError.MatchIdMismatch
        if (turn.expectedPredecessor != payload.moveNumber) {
            throw MatchAuthorityError.DuplicateSubmission(turn.payload.moveNumber)
        }
        return copy(payload = turn.payload)
    }

    /**
     * Takes authoritative remote state. Never moves backwards: a cached or replayed
     * older payload is refused rather than overwriting newer truth.
     *
     * @throws MatchAuthorityError
     */
    fun adopt(remote: MatchPayload): MatchAuthority {
        if (remote.matchId != payload.matchId) throw MatchAuthorityError.MatchIdMismatch
        if (remote.moveNumber < payload.moveNumber) {
            throw MatchAuthorityError.StaleMoveNumber(expected = payload.moveNumber, received = remote.moveNumber)
        }
        return copy(payload = remote)
    }
}
