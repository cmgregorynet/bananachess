package com.charlesbarton.bananachess.engine.online

import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.GameStatus
import com.charlesbarton.bananachess.engine.model.Move
import com.charlesbarton.bananachess.engine.model.Outcome
import com.charlesbarton.bananachess.engine.model.Piece
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.model.RestoreBlender
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import com.charlesbarton.bananachess.engine.rules.RulesError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ported from the "Match authority" suite in OnlineTests.swift. */
class MatchAuthorityTest {
    private fun authority(player: PlayerId): MatchAuthority = MatchAuthority(MatchPayload.newMatch(id = "m1"), player)

    private fun firstMove(authority: MatchAuthority): Move = RulesEngine.legalMoves(authority.state).first()

    @Test
    fun `only the participant whose turn it is may prepare a turn`() {
        val waiting = authority(PlayerId.Two)

        assertFalse(waiting.isLocalTurn)
        val error = assertFailsWith<MatchAuthorityError.NotLocalTurn> { waiting.prepareTurn(firstMove(waiting)) }
        assertEquals("It is not your turn.", error.message)
    }

    @Test
    fun `preparing a turn does not change local authority`() {
        val active = authority(PlayerId.One)
        val before = active.copy()

        val turn = active.prepareTurn(firstMove(active))

        assertEquals(0, active.moveNumber)
        assertEquals(before, active)
        assertEquals(MatchPayload.newMatch(id = "m1").state, active.state)
        assertEquals(0, turn.expectedPredecessor)
        assertEquals(1, turn.payload.moveNumber)
        assertEquals("m1", turn.payload.matchId)
        assertEquals(turn.events, turn.payload.lastMove?.events)
        assertEquals(PlayerId.One, turn.payload.lastMove?.playedBy)
        assertEquals(firstMove(active), turn.payload.lastMove?.move)
        assertEquals(turn, active.prepareTurn(firstMove(active)), "the same action prepares the same turn")
    }

    @Test
    fun `a retried submission commits exactly once and never scores twice`() {
        // A move that scores, so a double commit would be visible in the score.
        val fruit = Piece(PieceId("one.fruit.0"), PlayerId.One, PieceKind.Strawberry, Position(1, 2))
        val other = Piece(PieceId("two.fruit.0"), PlayerId.Two, PieceKind.Banana, Position(4, 4))
        val state = GameState(pieces = listOf(fruit, other))
        val active = MatchAuthority(MatchPayload(matchId = "m1", moveNumber = 0, state = state), PlayerId.One)

        val turn = active.prepareTurn(Move(fruit.id, Position(1, 2), Position(0, 2)))
        val committed = active.commit(turn)

        assertEquals(4, committed.state.scores.one)
        assertEquals(1, committed.moveNumber)
        assertEquals(0, active.moveNumber, "the authority it came from is untouched")
        // The same prepared turn arriving again, which is the retry path, is refused.
        val error = assertFailsWith<MatchAuthorityError.DuplicateSubmission> { committed.commit(turn) }
        assertEquals(MatchAuthorityError.DuplicateSubmission(moveNumber = 1), error)
        assertEquals("That turn has already been submitted.", error.message)
    }

    @Test
    fun `older remote state never overwrites newer local authority`() {
        val start = authority(PlayerId.One)
        val active = start.commit(start.prepareTurn(firstMove(start)))

        val error =
            assertFailsWith<MatchAuthorityError.StaleMoveNumber> { active.adopt(MatchPayload.newMatch(id = "m1")) }

        assertEquals(MatchAuthorityError.StaleMoveNumber(expected = 1, received = 0), error)
        assertEquals("That update is older than the state already stored.", error.message)
        assertEquals(1, active.moveNumber)
    }

    @Test
    fun `a payload from another match is refused`() {
        val active = authority(PlayerId.One)

        val error =
            assertFailsWith<MatchAuthorityError.MatchIdMismatch> {
                active.adopt(MatchPayload.newMatch(id = "someone-elses-match"))
            }

        assertEquals("That update belongs to a different match.", error.message)
        val foreign = MatchAuthority(MatchPayload.newMatch(id = "other"), PlayerId.One)
        assertFailsWith<MatchAuthorityError.MatchIdMismatch> { active.commit(foreign.prepareTurn(firstMove(foreign))) }
    }

    @Test
    fun `the other device adopts a turn and the two agree`() {
        val one = authority(PlayerId.One)
        val two = authority(PlayerId.Two)

        val turn = one.prepareTurn(firstMove(one))
        val sent = MatchLink.payload(MatchLink.url(turn.payload))
        val oneAfter = one.commit(turn)
        val twoAfter = two.adopt(sent)

        assertEquals(oneAfter.payload, twoAfter.payload)
        assertTrue(twoAfter.isLocalTurn)
        assertFalse(oneAfter.isLocalTurn)
        assertEquals(twoAfter, twoAfter.adopt(sent), "adopting the same payload again changes nothing")
    }

    @Test
    fun `a finished match takes no more turns`() {
        val finished =
            MatchPayload.newMatch(id = "m1").let {
                it.copy(state = it.state.updated(status = GameStatus.Ended(Outcome.Draw)))
            }
        val active = MatchAuthority(finished, PlayerId.One)

        assertFalse(active.isLocalTurn)
        val error =
            assertFailsWith<MatchAuthorityError.MatchFinished> {
                active.prepareTurn(
                    firstMove(authority(PlayerId.One)),
                )
            }
        assertEquals("This match has finished.", error.message)
    }

    @Test
    fun `an action the rules refuse is refused with the rules' own error`() {
        val active = authority(PlayerId.One)

        assertFailsWith<RulesError> { active.prepareTurn(RestoreBlender(PieceId("one.blender.0"), Position(5, 1))) }
        assertEquals(0, active.moveNumber)
    }
}
