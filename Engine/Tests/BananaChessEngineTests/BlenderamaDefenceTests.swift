import Testing
@testable import BananaChessEngine

/// Blenderama is a shutout: it dies the moment the defender scores at all. The CPU's
/// counter has to be priced on *that* — on the nearest route to the scoreboard — and not
/// on walking a blender toward the attacker's fruit, which was the answer under the
/// rules as they stood before the shutout came in.
@Suite("Blenderama defence")
struct BlenderamaDefenceTests {
    /// The attacker is two self-blends from an outright win, which is far enough ahead to
    /// sit beyond any search horizon a phone will reach — so the evaluation, not the
    /// search, has to point at the answer.
    ///
    /// From here the defender has two plausible moves: walk its fruit a row nearer home,
    /// or walk its blender a step nearer the attacker's fruit. Only the first one leads
    /// anywhere: it is two moves from a delivery, while the blender is still four from a
    /// blend. The fruit move therefore has to evaluate higher.
    private func fixture() -> (state: GameState, fruit: PieceID, blender: PieceID) {
        var builder = BoardBuilder()
        // Defender: one fruit three rows from its delivery row, one distant blender.
        let fruit = builder.fruit(.banana, .one, at: position(3, 0))
        let blender = builder.blender(.one, at: position(5, 0), home: position(5, 0))
        // Attacker: two fruit left and a blender already touching one of them.
        _ = builder.fruit(.strawberry, .two, at: position(1, 4))
        _ = builder.fruit(.strawberry, .two, at: position(2, 5))
        _ = builder.blender(.two, at: position(0, 3), home: position(0, 3))

        var state = builder.state(active: .one, ruleSet: .wildcard)
        // Four of the attacker's six already blended: two more and it is over.
        state.wildcards.ownFruitBlended[.two] = GameSetup.fruitCount - 2
        return (state, fruit, blender)
    }

    @Test("the nearest route to the scoreboard is what answers a Blenderama")
    func scoringRouteOutranksBlenderApproach() throws {
        let (state, fruit, blender) = fixture()
        let weights = EvaluationWeights()
        let actions = RulesEngine.legalActions(in: state)

        func resulting(_ id: PieceID, to destination: Position) throws -> Int {
            let action = try #require(
                actions.first {
                    guard case .move(let move) = $0 else { return false }
                    return move.pieceID == id && move.destination == destination
                }
            )
            let after = try RulesEngine.apply(action, to: state).state
            return Evaluation.score(after, for: .one, weights: weights)
        }

        // The attacker's Blenderama is live, so there is something to answer.
        #expect(state.wildcards.blenderamaCountered[.two] == false)

        let advanceFruit = try resulting(fruit, to: position(2, 0))
        let advanceBlender = try resulting(blender, to: position(4, 1))

        #expect(
            advanceFruit > advanceBlender,
            Comment(
                rawValue: "a Blenderama is two moves off: closing on a delivery "
                    + "(\(advanceFruit)) must beat marching a blender (\(advanceBlender))"
            )
        )
    }

    /// The counter credit must stay strictly below the value of the threat it answers,
    /// or the CPU hovers one move from scoring forever rather than scoring.
    @Test("actually scoring beats standing next to a score")
    func scoringBeatsHovering() throws {
        var builder = BoardBuilder()
        let fruit = builder.fruit(.banana, .one, at: position(1, 0))
        _ = builder.blender(.one, at: position(5, 0), home: position(5, 0))
        _ = builder.fruit(.strawberry, .two, at: position(2, 5))
        _ = builder.fruit(.strawberry, .two, at: position(3, 4))
        _ = builder.blender(.two, at: position(0, 3), home: position(0, 3))
        var state = builder.state(active: .one, ruleSet: .wildcard)
        state.wildcards.ownFruitBlended[.two] = GameSetup.fruitCount - 2

        let actions = RulesEngine.legalActions(in: state)
        let delivery = try #require(
            actions.first {
                guard case .move(let move) = $0 else { return false }
                return move.pieceID == fruit && move.destination.row == PlayerID.one.deliveryRow
            }
        )
        let after = try RulesEngine.apply(delivery, to: state).state
        // Scoring is what shuts the run out, permanently.
        #expect(after.wildcards.blenderamaCountered[.two])

        let sideways = actions.first {
            guard case .move(let move) = $0 else { return false }
            return move.pieceID == fruit && move.destination.row != PlayerID.one.deliveryRow
        }
        if let sideways {
            let hovering = try RulesEngine.apply(sideways, to: state).state
            #expect(
                Evaluation.score(after, for: .one, weights: EvaluationWeights())
                    > Evaluation.score(hovering, for: .one, weights: EvaluationWeights())
            )
        }
    }
}
