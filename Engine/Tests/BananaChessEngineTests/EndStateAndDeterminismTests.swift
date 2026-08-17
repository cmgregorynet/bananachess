import Testing
@testable import BananaChessEngine

@Suite("End state")
struct EndStateTests {
    @Test("Resolving the last fruit ends the match and locks the board")
    func lastFruitEndsMatch() throws {
        var builder = BoardBuilder()
        let fruit = builder.fruit(.strawberry, .one, at: position(1, 2))
        _ = builder.blender(.two, at: position(3, 3), home: position(0, 4))
        let state = builder.state()

        let result = try RulesEngine.apply(state.move(fruit, to: position(0, 2)), to: state)

        #expect(result.state.status == .ended(.win(.one)))
        #expect(result.state.remainingFruit.isEmpty)
        #expect(RulesEngine.legalMoves(in: result.state).isEmpty)
        #expect(result.events.contains(.gameEnded(outcome: .win(.one))))
    }

    @Test("Equal scores are a draw")
    func drawOnEqualScores() throws {
        var builder = BoardBuilder()
        let fruit = builder.fruit(.banana, .one, at: position(1, 2))
        _ = builder.blender(.two, at: position(3, 3), home: position(0, 4))
        let state = builder.state(scores: Scores(one: 0, two: 2))

        let result = try RulesEngine.apply(state.move(fruit, to: position(0, 2)), to: state)

        #expect(result.state.status == .ended(.draw))
    }

    @Test("A finished match refuses further moves")
    func finishedMatchRefusesMoves() throws {
        var builder = BoardBuilder()
        let fruit = builder.fruit(.banana, .one, at: position(1, 2))
        let blender = builder.blender(.two, at: position(3, 3), home: position(0, 4))
        let state = builder.state()

        let result = try RulesEngine.apply(state.move(fruit, to: position(0, 2)), to: state)
        let afterwards = Move(pieceID: blender, origin: position(3, 3), destination: position(2, 2))

        #expect(throws: RulesError.gameOver) { try RulesEngine.apply(afterwards, to: result.state) }
    }

    @Test("A player with no legal move is skipped rather than deadlocking")
    func playerWithNoMovesIsSkipped() throws {
        // A blender can always move somewhere now that it may land on anything, so the
        // only player who can run out of moves is one whose blenders are both destroyed
        // — and whose spawns are blocked, so neither can be bought back — and whose last
        // fruit is boxed in, since fruit never captures out.
        var builder = BoardBuilder()
        let mover = builder.fruit(.banana, .one, at: position(3, 3))
        _ = builder.destroyedBlender(.two, home: position(0, 1))
        _ = builder.destroyedBlender(.two, home: position(0, 4))
        _ = builder.fruit(.strawberry, .two, at: position(1, 1))
        // These also sit on player two's blender spawns, so nothing can be bought back.
        for square in [position(0, 1), position(1, 0), position(1, 2), position(2, 1), position(0, 4)] {
            _ = builder.fruit(.banana, .one, at: square)
        }
        let state = builder.state()

        let result = try RulesEngine.apply(state.move(mover, to: position(2, 3)), to: state)

        #expect(result.state.activePlayer == .one)
        #expect(result.events.contains(.turnPassed(skipped: .two)))
    }
}

@Suite("Determinism")
struct DeterminismTests {
    @Test("Replaying a move from the same state produces identical state and events")
    func replayIsIdentical() throws {
        var builder = BoardBuilder()
        let mine = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        _ = builder.blender(.two, at: position(2, 2), home: position(0, 4))
        _ = builder.blender(.two, at: position(5, 1), home: position(0, 1))
        _ = builder.fruit(.pineapple, .one, at: position(0, 1))
        _ = builder.fruit(.banana, .two, at: position(4, 4))
        let state = builder.state()
        let move = state.move(mine, to: position(2, 2))

        let first = try RulesEngine.apply(move, to: state)
        let second = try RulesEngine.apply(move, to: state)

        #expect(first.state == second.state)
        #expect(first.events == second.events)
        #expect(first.state.positionKey == second.state.positionKey)
    }

    @Test("Legal move lists come back in a stable order")
    func legalMovesAreOrdered() {
        let state = GameSetup.newGame()
        #expect(RulesEngine.legalMoves(in: state) == RulesEngine.legalMoves(in: state).sorted())
    }

    @Test("A full state survives an encode/decode round trip")
    func codableRoundTrip() throws {
        var state = GameSetup.newGame()
        for move in RulesEngine.legalMoves(in: state).prefix(1) {
            state = try RulesEngine.apply(move, to: state).state
        }

        let payload = MatchPayload(matchID: "m1", moveNumber: 1, state: state)
        let decoded = try MatchCodec.decode(try MatchCodec.encode(payload))

        #expect(decoded == payload)
        #expect(decoded.state.pieces == state.pieces)
        #expect(decoded.state.positionKey == state.positionKey)
    }

    @Test("Encoding is byte-stable, so payload comparison means something")
    func encodingIsStable() throws {
        let payload = MatchPayload.newMatch(id: "m1")
        #expect(try MatchCodec.encode(payload) == (try MatchCodec.encode(payload)))
    }

    @Test("Replaying a transition's events reproduces the state the engine returned")
    func eventReplayMatchesEngine() throws {
        // A move that destroys a blender and earns a Mega turn.
        var builder = BoardBuilder()
        let mine = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        _ = builder.blender(.two, at: position(2, 2), home: position(0, 4))
        _ = builder.blender(.two, at: position(5, 1), home: position(0, 1))
        _ = builder.fruit(.pineapple, .one, at: position(0, 1))
        _ = builder.fruit(.banana, .two, at: position(4, 4))
        let state = builder.state()

        let result = try RulesEngine.apply(state.move(mine, to: position(2, 2)), to: state)
        var replayed = state
        for event in result.events {
            replayed = replayed.applying(event)
        }

        #expect(replayed.pieces == result.state.pieces)
        #expect(replayed.scores == result.state.scores)
        #expect(replayed.activePlayer == result.state.activePlayer)
        #expect(replayed.status == result.state.status)
    }

    @Test("Event replay holds for a whole match, not just one clever fixture",
          .timeLimit(.minutes(2)))
    func eventReplayHoldsAcrossAMatch() {
        var state = GameSetup.newGame()
        let cpu = CPUPlayer(difficulty: .medium, seed: 31)
        var mismatches = 0

        while !state.status.isOver, state.turnNumber < 400 {
            guard let action = cpu.selectAction(in: state),
                  let result = try? RulesEngine.apply(action, to: state) else { break }
            var replayed = state
            for event in result.events { replayed = replayed.applying(event) }
            if replayed.pieces != result.state.pieces || replayed.scores != result.state.scores {
                mismatches += 1
            }
            state = result.state
        }

        #expect(mismatches == 0)
    }

    @Test("The position key ignores nothing that matters")
    func positionKeySensitivity() throws {
        let state = GameSetup.newGame()
        var moved = state
        moved.activePlayer = .two
        #expect(state.positionKey != moved.positionKey)

        var scored = state
        scored.scores.one += 1
        #expect(state.positionKey != scored.positionKey)
    }
}
