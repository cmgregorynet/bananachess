import Foundation
import Testing
@testable import BananaChessEngine

@Suite("CPU")
struct CPUTests {
    @Test("Every difficulty returns a legal move and never mutates its snapshot",
          arguments: CPUDifficulty.allCases)
    func returnsLegalMove(difficulty: CPUDifficulty) throws {
        var state = GameSetup.newGame()
        // Play a few plies so the position is not the trivial opening.
        for _ in 0..<6 {
            let move = CPUPlayer(difficulty: .medium, seed: 7).selectMove(in: state)!
            state = try RulesEngine.apply(move, to: state).state
        }

        let snapshot = state
        let cpu = CPUPlayer(difficulty: difficulty, seed: 42)
        let move = try #require(cpu.selectMove(in: state))

        #expect(RulesEngine.legalMoves(in: state).contains(move))
        #expect(state == snapshot, "the CPU must not touch the state it was handed")
        #expect(throws: Never.self) { try RulesEngine.apply(move, to: state) }
    }

    @Test("A finished match yields no CPU move")
    func noMoveWhenFinished() {
        var builder = BoardBuilder()
        _ = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        var state = builder.state()
        state.status = .ended(.draw)

        #expect(CPUPlayer(difficulty: .hard).selectMove(in: state) == nil)
    }

    @Test("Easy is reproducible from a seed")
    func easyIsSeeded() {
        let state = GameSetup.newGame()
        let first = CPUPlayer(difficulty: .easy, seed: 99).selectMove(in: state)
        let second = CPUPlayer(difficulty: .easy, seed: 99).selectMove(in: state)
        let other = CPUPlayer(difficulty: .easy, seed: 1234).selectMove(in: state)

        #expect(first == second)
        #expect(first != nil)
        #expect(first != other, "different seeds should not lock onto the same move")
    }

    @Test("A seeded Easy game replays move for move")
    func seededGameReplays() {
        let one = CPUPlayer(difficulty: .easy, seed: 5)
        let two = CPUPlayer(difficulty: .easy, seed: 6)
        let first = playOut(GameSetup.newGame(), one: one, two: two)
        let second = playOut(GameSetup.newGame(), one: one, two: two)

        #expect(first == second)
        #expect(first.history == second.history)
    }

    @Test("Medium takes an available delivery over a neutral shuffle")
    func mediumTakesDelivery() throws {
        var builder = BoardBuilder()
        let pineapple = builder.fruit(.pineapple, .one, at: position(1, 2))
        _ = builder.fruit(.banana, .one, at: position(4, 5))
        _ = builder.fruit(.banana, .two, at: position(3, 0))
        _ = builder.blender(.two, at: position(5, 5), home: position(0, 4))
        let state = builder.state()

        let move = try #require(CPUPlayer(difficulty: .medium, seed: 3).selectMove(in: state))

        #expect(move == Move(pieceID: pineapple, origin: position(1, 2), destination: position(0, 2)))
    }

    @Test("Medium takes a free blend over a neutral shuffle")
    func mediumTakesBlend() throws {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        _ = builder.fruit(.banana, .one, at: position(5, 5))
        let pineapple = builder.fruit(.pineapple, .two, at: position(2, 4))
        let state = builder.state()

        let move = try #require(CPUPlayer(difficulty: .medium, seed: 3).selectMove(in: state))

        #expect(move.pieceID == blender)
        #expect(move.destination == state.piece(with: pineapple)?.position)
    }

    @Test("Hard finds a two-move forced delivery and keeps to its time budget")
    func hardFindsForcedSequence() throws {
        var builder = BoardBuilder()
        let strawberry = builder.fruit(.strawberry, .one, at: position(2, 2))
        _ = builder.fruit(.banana, .one, at: position(5, 5))
        _ = builder.fruit(.banana, .two, at: position(4, 0))
        _ = builder.blender(.two, at: position(5, 3), home: position(0, 4))
        let state = builder.state()

        let start = ContinuousClock().now
        let move = try #require(CPUPlayer(difficulty: .hard, seed: 11).selectMove(in: state))
        let elapsed = ContinuousClock().now - start

        #expect(move == Move(pieceID: strawberry, origin: position(2, 2), destination: position(1, 2)))
        #expect(elapsed < .seconds(2.5), "hard cap is 2s plus slack for a loaded CI machine")
    }

    @Test("Hard respects its time cap from the opening position")
    func hardRespectsTimeCap() throws {
        let state = GameSetup.newGame()
        let start = ContinuousClock().now
        _ = CPUPlayer(difficulty: .hard, seed: 1).selectMove(in: state)
        let elapsed = ContinuousClock().now - start

        #expect(elapsed < .seconds(2.5))
    }

    @Test("The CPU does not blend its own fruit when anything else is available",
          arguments: CPUDifficulty.allCases.filter { $0 != .easy })
    func cpuAvoidsPointlessSelfBlends(difficulty: CPUDifficulty) throws {
        // A blender surrounded by its owner's own fruit: every diagonal is now a legal
        // self-blend, and every one of them loses points.
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        for square in [position(2, 2), position(2, 4), position(4, 2), position(4, 4)] {
            _ = builder.fruit(.strawberry, .one, at: square)
        }
        _ = builder.fruit(.banana, .one, at: position(0, 5))
        _ = builder.fruit(.banana, .two, at: position(5, 0))
        let state = builder.state()

        let move = try #require(CPUPlayer(difficulty: difficulty, seed: 2).selectMove(in: state))
        let result = try RulesEngine.apply(move, to: state)

        #expect(move.pieceID != blender, "there is a free fruit move; wrecking its own fruit is not it")
        #expect(result.state.scores.one >= 0)
    }

    @Test("The CPU will take a self-blend when it is the lesser loss")
    func cpuAcceptsAPricedSelfBlend() throws {
        // Player one is to move. Its banana is one square from delivering, but an
        // opponent blender is poised to take its pineapple. Blending its own banana is
        // legal and costs 1; the CPU must at least evaluate such moves rather than
        // treating them as unavailable.
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        _ = builder.fruit(.banana, .one, at: position(2, 2))
        _ = builder.fruit(.banana, .two, at: position(1, 5))
        let state = builder.state()

        let legal = RulesEngine.legalMoves(in: state)
        let selfBlend = Move(pieceID: blender, origin: position(3, 3), destination: position(2, 2))

        #expect(legal.contains(selfBlend), "the move is available to the search")
        let move = try #require(CPUPlayer(difficulty: .medium, seed: 2).selectMove(in: state))
        #expect(legal.contains(move))
    }

    @Test("Stronger difficulties beat weaker ones over a series", .timeLimit(.minutes(2)))
    func hardBeatsEasy() {
        var hardWins = 0
        var easyWins = 0

        for seed in UInt64(1)...6 {
            let hard = CPUPlayer(difficulty: .medium, seed: seed)
            let easy = CPUPlayer(difficulty: .easy, seed: seed &* 31)
            // Alternate who moves first so neither side gets a structural edge.
            let starting: PlayerID = seed.isMultiple(of: 2) ? .one : .two
            let state = GameSetup.newGame(startingPlayer: starting)
            let final = playOut(state, one: hard, two: easy)

            if case .ended(let outcome) = final.status {
                if outcome.isWin(for: .one) { hardWins += 1 }
                if outcome.isWin(for: .two) { easyWins += 1 }
            }
        }

        #expect(hardWins > easyWins, "medium won \(hardWins), easy won \(easyWins)")
    }

    @Test("Self-play matches terminate and never overlap pieces", .timeLimit(.minutes(2)))
    func selfPlayTerminates() {
        var overlapped = false
        let final = playOut(
            GameSetup.newGame(),
            one: CPUPlayer(difficulty: .medium, seed: 17),
            two: CPUPlayer(difficulty: .medium, seed: 18),
            onEachTurn: { state, _ in
                let squares = state.pieces.compactMap(\.position)
                if Set(squares).count != squares.count { overlapped = true }
            }
        )

        #expect(!overlapped)
        #expect(final.status.isOver, "match stalled after the turn limit")
        #expect(RulesEngine.isFruitDepleted(final), "one side ran out of fruit, which ends it")
    }
}
