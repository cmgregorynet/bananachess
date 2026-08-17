import Foundation
import Testing
@testable import BananaChessEngine

@Suite("Online payloads")
struct MatchPayloadTests {
    @Test("A payload from a newer schema is refused, not guessed at")
    func rejectsFutureSchema() throws {
        let payload = MatchPayload(
            schemaVersion: MatchPayload.currentSchemaVersion + 1,
            matchID: "m1",
            moveNumber: 3,
            state: GameSetup.newGame()
        )
        let data = try MatchCodec.encode(payload)

        #expect(throws: MatchCodecError.unsupportedSchema(
            found: MatchPayload.currentSchemaVersion + 1,
            supported: MatchPayload.currentSchemaVersion
        )) {
            try MatchCodec.decode(data)
        }
    }

    @Test("A payload from a newer rule set is refused")
    func rejectsFutureRules() throws {
        let payload = MatchPayload(
            rulesVersion: RulesEngine.rulesVersion + 1,
            matchID: "m1",
            moveNumber: 0,
            state: GameSetup.newGame()
        )

        #expect(throws: MatchCodecError.incompatibleRules(
            found: RulesEngine.rulesVersion + 1,
            supported: RulesEngine.rulesVersion
        )) {
            try MatchCodec.decode(try MatchCodec.encode(payload))
        }
    }

    /// RU-09
    @Test("A destroyed blender survives encoding and relaunch")
    func combatStateSurvivesRoundTrip() throws {
        var builder = BoardBuilder()
        let mine = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        let theirs = builder.blender(.two, at: position(2, 2), home: position(0, 4))
        _ = builder.fruit(.banana, .one, at: position(4, 0))
        _ = builder.fruit(.banana, .two, at: position(1, 5))
        let state = builder.state()

        let midKnockout = try RulesEngine.apply(state.move(mine, to: position(2, 2)), to: state).state
        let payload = MatchPayload(matchID: "m1", moveNumber: 1, state: midKnockout)

        let decoded = try MatchCodec.decode(try MatchCodec.encode(payload))

        #expect(decoded.state == midKnockout)
        #expect(!decoded.state.isOffBoard(mine), "the attacker survived")
        #expect(decoded.state.isOffBoard(theirs), "the defender did not")
        #expect(decoded.state.destroyedBlenders.count == 1)
        #expect(decoded.state.pieces == midKnockout.pieces)

        // And the restored match carries on identically.
        let fromDecoded = try advanceTurns(2, from: decoded.state)
        let fromOriginal = try advanceTurns(2, from: midKnockout)
        #expect(fromDecoded == fromOriginal)
    }

    @Test("A match started under the previous rules is refused, not silently continued")
    func previousRulesRefused() throws {
        let payload = MatchPayload(
            schemaVersion: MatchPayload.currentSchemaVersion,
            rulesVersion: RulesEngine.rulesVersion - 1,
            matchID: "m1",
            moveNumber: 4,
            state: GameSetup.newGame()
        )

        #expect(throws: MatchCodecError.incompatibleRules(
            found: RulesEngine.rulesVersion - 1,
            supported: RulesEngine.rulesVersion
        )) {
            try MatchCodec.decode(try MatchCodec.encode(payload))
        }
    }

    @Test("An older wire format is refused too")
    func olderSchemaRefused() throws {
        let payload = MatchPayload(
            schemaVersion: MatchPayload.currentSchemaVersion - 1,
            matchID: "m1",
            moveNumber: 4,
            state: GameSetup.newGame()
        )

        #expect(throws: MatchCodecError.outdatedSchema(
            found: MatchPayload.currentSchemaVersion - 1,
            supported: MatchPayload.currentSchemaVersion
        )) {
            try MatchCodec.decode(try MatchCodec.encode(payload))
        }
    }

    @Test("Garbage is rejected as malformed")
    func rejectsGarbage() {
        #expect(throws: MatchCodecError.malformed) {
            try MatchCodec.decode(Data("not a match".utf8))
        }
    }

    @Test("Last-move metadata survives the round trip so the opponent's move animates")
    func lastMoveRoundTrips() throws {
        let state = GameSetup.newGame()
        let move = RulesEngine.legalMoves(in: state)[0]
        let transition = try RulesEngine.apply(move, to: state)
        let payload = MatchPayload(
            matchID: "m1",
            moveNumber: 1,
            state: transition.state,
            lastMove: LastMove(action: .move(move), playedBy: .one, events: transition.events)
        )

        let decoded = try MatchCodec.decode(try MatchCodec.encode(payload))

        #expect(decoded.lastMove?.events == transition.events)
        #expect(decoded.lastMove?.move == move)
    }
}

@Suite("Match authority")
struct MatchAuthorityTests {
    private func authority(for player: PlayerID) -> MatchAuthority {
        MatchAuthority(payload: .newMatch(id: "m1"), localPlayer: player)
    }

    @Test("Only the participant whose turn it is may prepare a turn")
    func outOfTurnRejected() throws {
        let waiting = authority(for: .two)
        let move = RulesEngine.legalMoves(in: waiting.state)[0]

        #expect(!waiting.isLocalTurn)
        #expect(throws: MatchAuthorityError.notLocalTurn) { try waiting.prepareTurn(move) }
    }

    @Test("Preparing a turn does not change local authority")
    func prepareIsPure() throws {
        let active = authority(for: .one)
        let move = RulesEngine.legalMoves(in: active.state)[0]

        _ = try active.prepareTurn(move)

        #expect(active.moveNumber == 0)
        #expect(active.state == MatchPayload.newMatch(id: "m1").state)
    }

    @Test("A retried submission commits exactly once and never scores twice")
    func retryDoesNotDuplicate() throws {
        var active = authority(for: .one)
        // A move that scores, so a double commit would be visible in the score.
        var builder = BoardBuilder()
        let fruit = builder.fruit(.strawberry, .one, at: position(1, 2))
        _ = builder.fruit(.banana, .two, at: position(4, 4))
        let state = builder.state()
        active = MatchAuthority(
            payload: MatchPayload(matchID: "m1", moveNumber: 0, state: state),
            localPlayer: .one
        )

        let turn = try active.prepareTurn(state.move(fruit, to: position(0, 2)))
        try active.commit(turn)

        #expect(active.state.scores.one == 4)
        #expect(active.moveNumber == 1)
        // The same prepared turn arriving again — the retry path — is refused.
        #expect(throws: MatchAuthorityError.duplicateSubmission(moveNumber: 1)) {
            try active.commit(turn)
        }
        #expect(active.state.scores.one == 4)
    }

    @Test("Older remote state never overwrites newer local authority")
    func staleRemoteRejected() throws {
        var active = authority(for: .one)
        let turn = try active.prepareTurn(RulesEngine.legalMoves(in: active.state)[0])
        try active.commit(turn)

        let stale = MatchPayload.newMatch(id: "m1")

        #expect(throws: MatchAuthorityError.staleMoveNumber(expected: 1, received: 0)) {
            try active.adopt(stale)
        }
        #expect(active.moveNumber == 1)
    }

    @Test("A payload from another match is refused")
    func foreignPayloadRejected() {
        var active = authority(for: .one)
        #expect(throws: MatchAuthorityError.matchIDMismatch) {
            try active.adopt(.newMatch(id: "someone-elses-match"))
        }
    }
}

@Suite("Two simulated clients")
struct OnlineMatchServiceTests {
    private func pair() -> (store: InMemoryMatchStore, alice: InMemoryMatchService, bob: InMemoryMatchService) {
        let store = InMemoryMatchStore()
        let alice = OnlineLocalPlayer(id: "alice", displayName: "Alice")
        let bob = OnlineLocalPlayer(id: "bob", displayName: "Bob")
        return (
            store,
            InMemoryMatchService(store: store, localPlayer: alice, opponent: bob),
            InMemoryMatchService(store: store, localPlayer: bob, opponent: alice)
        )
    }

    @Test("Automatch seats the creator as player one and shows each side the right status")
    func seating() async throws {
        let (_, alice, bob) = pair()
        let created = try await alice.startAutomatch()
        let fromBob = try await bob.loadMatch(id: created.id)

        #expect(created.localPlayer == .one)
        #expect(created.status == .yourTurn)
        #expect(created.opponentName == "Bob")
        #expect(fromBob.localPlayer == .two)
        #expect(fromBob.status == .waitingForOpponent)
        #expect(fromBob.opponentName == "Alice")
    }

    @Test("Two clients alternate turns and converge on identical state after every move",
          .timeLimit(.minutes(2)))
    func clientsConverge() async throws {
        let (_, alice, bob) = pair()
        let created = try await alice.startAutomatch()
        let matchID = created.id

        var authorities: [PlayerID: MatchAuthority] = [
            .one: MatchAuthority(payload: created.payload!, localPlayer: .one),
            .two: MatchAuthority(payload: try await bob.loadMatch(id: matchID).payload!, localPlayer: .two),
        ]
        let cpus: [PlayerID: CPUPlayer] = [
            .one: CPUPlayer(difficulty: .medium, seed: 20),
            .two: CPUPlayer(difficulty: .medium, seed: 21),
        ]
        var turns = 0

        while !authorities[.one]!.state.status.isOver, turns < 600 {
            let seat = authorities[.one]!.state.activePlayer
            let service = seat == .one ? alice : bob
            var authority = authorities[seat]!

            // Actions, not just moves: a CPU that decides to buy a blender back would
            // otherwise stall this loop.
            guard let action = cpus[seat]!.selectAction(in: authority.state) else { break }
            let turn = try authority.prepareTurn(action)
            try await service.submit(
                turn.payload,
                expectedPredecessor: turn.expectedPredecessor,
                for: matchID
            )
            try authority.commit(turn)
            authorities[seat] = authority

            // The other device refreshes and adopts the authoritative payload.
            let otherSeat = seat.opponent
            let otherService = otherSeat == .one ? alice : bob
            var other = authorities[otherSeat]!
            try other.adopt(try await otherService.loadMatch(id: matchID).payload!)
            authorities[otherSeat] = other

            #expect(authorities[.one]!.state == authorities[.two]!.state)
            #expect(authorities[.one]!.moveNumber == authorities[.two]!.moveNumber)
            turns += 1
        }

        #expect(authorities[.one]!.state.status.isOver)
        #expect(authorities[.one]!.state == authorities[.two]!.state)
    }

    /// RU-10
    @Test("Two clients replay the same collision to identical state, scores, and timing")
    func clientsAgreeOnACollision() async throws {
        let (_, alice, bob) = pair()
        let created = try await alice.startAutomatch()
        let matchID = created.id

        // Seed both clients with a position one move away from a collision.
        var builder = BoardBuilder()
        let mine = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        let theirs = builder.blender(.two, at: position(2, 2), home: position(0, 4))
        _ = builder.fruit(.banana, .one, at: position(4, 0))
        _ = builder.fruit(.banana, .two, at: position(1, 5))
        let seeded = MatchPayload(matchID: matchID, moveNumber: 0, state: builder.state())

        var aliceAuthority = MatchAuthority(payload: seeded, localPlayer: .one)
        var bobAuthority = MatchAuthority(payload: seeded, localPlayer: .two)

        let turn = try aliceAuthority.prepareTurn(seeded.state.move(mine, to: position(2, 2)))
        try await alice.submit(turn.payload, expectedPredecessor: turn.expectedPredecessor, for: matchID)
        try aliceAuthority.commit(turn)

        let remote = try #require(try await bob.loadMatch(id: matchID).payload)
        try bobAuthority.adopt(remote)

        #expect(aliceAuthority.state == bobAuthority.state)
        #expect(aliceAuthority.state.scores == bobAuthority.state.scores)
        #expect(!bobAuthority.state.isOffBoard(mine))
        #expect(bobAuthority.state.isOffBoard(theirs))
        #expect(
            bobAuthority.state.destroyedBlenders == aliceAuthority.state.destroyedBlenders,
            "both devices must agree on which blenders are off the board"
        )

        // The events Alice sent reproduce Bob's board exactly — no animation timing and
        // no wall clock involved.
        let lastMove = try #require(remote.lastMove)
        var replayed = seeded.state
        for event in lastMove.events { replayed = replayed.applying(event) }
        #expect(replayed.pieces == bobAuthority.state.pieces)

        // Both then run the window down to the same respawn.
        let aliceLater = try advanceTurns(2, from: aliceAuthority.state)
        let bobLater = try advanceTurns(2, from: bobAuthority.state)
        #expect(aliceLater == bobLater)
    }

    @Test("A submission built on stale state is a conflict, not an overwrite")
    func conflictDetected() async throws {
        let (_, alice, bob) = pair()
        let created = try await alice.startAutomatch()
        let matchID = created.id

        var aliceAuthority = MatchAuthority(payload: created.payload!, localPlayer: .one)
        let turn = try aliceAuthority.prepareTurn(RulesEngine.legalMoves(in: aliceAuthority.state)[0])
        try await alice.submit(turn.payload, expectedPredecessor: turn.expectedPredecessor, for: matchID)
        try aliceAuthority.commit(turn)

        // Bob replays an ancient payload built on move 0.
        let ancient = MatchPayload.newMatch(id: matchID)
        await #expect(throws: OnlineMatchError.conflict(remoteMoveNumber: 1)) {
            try await bob.submit(ancient, expectedPredecessor: 0, for: matchID)
        }

        let remote = try await bob.loadMatch(id: matchID)
        #expect(remote.payload?.moveNumber == 1)
    }

    @Test("A retry after a transport failure submits exactly one turn")
    func retryAfterFailure() async throws {
        let (store, alice, bob) = pair()
        let created = try await alice.startAutomatch()
        let matchID = created.id
        await store.injectSubmissionFailures(1)

        var authority = MatchAuthority(payload: created.payload!, localPlayer: .one)
        let turn = try authority.prepareTurn(RulesEngine.legalMoves(in: authority.state)[0])

        await #expect(throws: OnlineMatchError.self) {
            try await alice.submit(turn.payload, expectedPredecessor: turn.expectedPredecessor, for: matchID)
        }
        #expect(authority.moveNumber == 0, "a failed write must not advance local authority")

        // Retry with the identical prepared payload.
        try await alice.submit(turn.payload, expectedPredecessor: turn.expectedPredecessor, for: matchID)
        try authority.commit(turn)

        let remote = try await bob.loadMatch(id: matchID)
        #expect(remote.payload?.moveNumber == 1)
        #expect(remote.payload?.state == authority.state)
        #expect(remote.payload?.state.history.count == 1)
    }

    @Test("Forfeiting ends the match in the opponent's favour and it stays viewable")
    func forfeit() async throws {
        let (_, alice, bob) = pair()
        let created = try await alice.startAutomatch()

        try await bob.forfeit(matchID: created.id)

        let fromAlice = try await alice.loadMatch(id: created.id)
        #expect(fromAlice.status == .ended)
        #expect(fromAlice.outcome == .win(.one))
        #expect(try await bob.loadMatches().count == 1)
    }

    @Test("Cancelled authentication leaves online unavailable without affecting anything else")
    func authenticationUnavailable() async {
        let store = InMemoryMatchStore()
        var service = InMemoryMatchService(
            store: store,
            localPlayer: OnlineLocalPlayer(id: "a", displayName: "A"),
            opponent: OnlineLocalPlayer(id: "b", displayName: "B")
        )
        service.isAuthenticationAvailable = false

        await #expect(throws: OnlineMatchError.notAuthenticated) {
            try await service.authenticateLocalPlayer()
        }
    }
}
