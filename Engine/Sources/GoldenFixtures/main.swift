// Regenerate (from the repository root):
//
//   export DEVELOPER_DIR=/Applications/Xcode-26.5.0.app/Contents/Developer
//   (cd Engine && swift run GoldenFixtures /tmp/golden.json)
//   gzip -n -9 -c /tmp/golden.json > Android/engine/src/test/resources/golden.json.gz
//
// `-n` keeps the name and timestamp out of the gzip header, so the same JSON always
// gives the same committed bytes.

import Foundation

// `Evaluation` is internal to the engine. This target only ever builds in debug, through
// `swift run`, which is where a testable import is available.
@testable import BananaChessEngine

/// Emits the three things `ConformanceFixtures` leaves unpinned, for the Kotlin port.
///
/// The conformance fixture says where every piece ends up, which is enough to port the
/// rules. It does not say what a match looks like as bytes, and a link sent from an iPhone
/// has to open on Android: so the payload JSON and the link are recorded exactly as this
/// engine writes them. Nor does it state the two position hashes or the CPU's evaluation,
/// which a port can get subtly wrong while every board still matches.
struct Fixture: Encodable {
    let rulesVersion: Int
    let schemaVersion: Int
    let games: [Game]
}

struct Game: Encodable {
    let seed: UInt64
    let ruleSet: String
    let boardSetup: String
    let boardWrap: String
    let blenderBattles: Bool
    let startingPlayer: String
    /// Every action of the game in order, so a port can replay to any sampled step.
    let actions: [String]
    let samples: [Sample]
}

struct Sample: Encodable {
    /// How many actions have been played. Step 0 is the opening.
    let step: Int
    /// The exact string `MatchCodec.encode` produced, not a nested object: the point is
    /// the bytes, and re-encoding it inside this file would lose them.
    let payloadJSON: String
    let link: String
    /// Decimal strings, because both run past what a JSON reader can be trusted to keep
    /// in a double.
    let boardKey: String
    let positionKey: String
    let evaluationOne: Int
    let evaluationTwo: Int
}

struct FixtureError: Error, CustomStringConvertible {
    let description: String
}

let sampledSteps: Set<Int> = [0, 1, 2, 5, 10, 20, 40]
let stepLimit = 80
let weights = EvaluationWeights()

/// The same selection rule as the `wander` driver in `ConformanceFixtures`: a pure
/// function of the seed and the step, so a port needs no chooser of its own.
func wander(_ actions: [GameAction], step: Int, seed: UInt64) -> GameAction? {
    guard !actions.isEmpty else { return nil }
    var generator = SeededGenerator(seed: seed &* 0x9E37_79B9_7F4A_7C15 &+ UInt64(step))
    return actions[Int(generator.next() % UInt64(actions.count))]
}

func sample(_ state: GameState, lastMove: LastMove?, seed: UInt64) throws -> Sample {
    let payload = MatchPayload(
        matchID: "golden-\(seed)",
        moveNumber: state.history.count,
        state: state,
        lastMove: lastMove
    )
    guard let json = String(data: try MatchCodec.encode(payload), encoding: .utf8) else {
        throw FixtureError(description: "payload for seed \(seed) is not UTF-8")
    }
    return Sample(
        step: state.history.count,
        payloadJSON: json,
        link: try MatchLink.url(for: payload).absoluteString,
        boardKey: String(state.boardKey),
        positionKey: String(state.positionKey),
        evaluationOne: Evaluation.score(state, for: .one, weights: weights),
        evaluationTwo: Evaluation.score(state, for: .two, weights: weights)
    )
}

var games: [Game] = []
var seed: UInt64 = 1001

for ruleSet in RuleSet.allCases {
    for setup in BoardSetup.allCases {
        for wrap in BoardWrap.allCases {
            // On for the 1st, 3rd, 5th… game.
            let battles = games.count.isMultiple(of: 2)
            let starting: PlayerID = seed.isMultiple(of: 2) ? .one : .two
            var state = GameSetup.newGame(
                startingPlayer: starting,
                ruleSet: ruleSet,
                boardSetup: setup,
                boardWrap: wrap,
                blenderBattles: battles,
                seed: seed
            )
            var actions: [String] = []
            var samples = [try sample(state, lastMove: nil, seed: seed)]
            var sampledBattle = false

            while !state.status.isOver, actions.count < stepLimit {
                let legal = RulesEngine.legalActions(in: state).sorted()
                guard let action = wander(legal, step: actions.count, seed: seed) else { break }
                let mover = state.activePlayer
                let transition = try RulesEngine.apply(action, to: state)
                state = transition.state
                actions.append(action.description)

                // A battle is the one event that carries dice, and wandering never lands
                // one on a fixed step. So the first in each game is sampled wherever it
                // falls; otherwise no payload here would show what one looks like.
                let battle = !sampledBattle && transition.events.contains {
                    if case .blenderBattle = $0 { true } else { false }
                }
                if battle { sampledBattle = true }

                let finished = state.status.isOver || actions.count == stepLimit
                if sampledSteps.contains(actions.count) || finished || battle {
                    let lastMove = LastMove(
                        action: action, playedBy: mover, events: transition.events
                    )
                    samples.append(try sample(state, lastMove: lastMove, seed: seed))
                }
            }

            games.append(
                Game(
                    seed: seed,
                    ruleSet: ruleSet.rawValue,
                    boardSetup: setup.rawValue,
                    boardWrap: wrap.rawValue,
                    blenderBattles: battles,
                    startingPlayer: starting.rawValue,
                    actions: actions,
                    samples: samples
                )
            )
            seed += 1
        }
    }
}

let fixture = Fixture(
    rulesVersion: RulesEngine.rulesVersion,
    schemaVersion: MatchPayload.currentSchemaVersion,
    games: games
)

let encoder = JSONEncoder()
encoder.outputFormatting = [.sortedKeys]
let data = try encoder.encode(fixture)

let destination = CommandLine.arguments.count > 1
    ? URL(fileURLWithPath: CommandLine.arguments[1])
    : URL(fileURLWithPath: "golden.json")
try FileManager.default.createDirectory(
    at: destination.deletingLastPathComponent(), withIntermediateDirectories: true
)
try data.write(to: destination)

let totalSamples = games.reduce(0) { $0 + $1.samples.count }
print("wrote \(games.count) games, \(totalSamples) samples")
print("  rules \(RulesEngine.rulesVersion), schema \(MatchPayload.currentSchemaVersion)")
print("  \(data.count) bytes → \(destination.path)")
