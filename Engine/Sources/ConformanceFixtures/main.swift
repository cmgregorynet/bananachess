import BananaChessEngine
import Foundation

/// Emits the behaviour of the Swift engine as data, so a port can be held to it.
///
/// The rules here have changed eighteen times. A second implementation written from the
/// same prose would drift from this one within a week, and the drift would show up as a
/// board that disagrees with itself across devices. So the Swift engine is the
/// specification and this writes it down: every opening, and a long scripted game per
/// configuration, move by move, with the resulting position and every event.
///
/// The games are driven by scripted choosers rather than by the CPU: deterministic
/// without porting any search, and reproducible from a seed.
///
/// `first` — always the lowest action in canonical order — was the only driver at first,
/// and it turned out to be a poor explorer. It picks `one.blender.0` over and over, so
/// across eighteen wildcard games it never once reached a Banana Split or a Blenderama,
/// never bought a blender back, and never let one respawn. Those rules were ported but
/// unproven, which is worse than unported. The seeded driver wanders instead, and the
/// coverage report at the end of this file says what actually got exercised.
struct Fixture: Encodable {
    let rulesVersion: Int
    let schemaVersion: Int
    let games: [Game]
}

struct Game: Encodable {
    let driver: String
    let ruleSet: String
    let boardSetup: String
    let boardWrap: String
    let blenderBattles: Bool
    let startingPlayer: String
    let seed: UInt64
    let opening: Position
    let steps: [Step]
    let outcome: String?
}

struct Position: Encodable {
    let pieces: [PieceSnapshot]
    let activePlayer: String
    let scores: [Int]
    let turnNumber: Int
}

struct PieceSnapshot: Encodable {
    let id: String
    let owner: String
    let kind: String
    let row: Int?
    let column: Int?
    let homeRow: Int?
    let homeColumn: Int?
    let respawnTurn: Int?
}

struct Step: Encodable {
    let action: String
    let events: [String]
    /// Compact board after the move: `id@row,column`, or `id@-` for a piece off the
    /// board. Compact because the full objects came to 32 MB; readable because a failing
    /// conformance test should show which piece is in the wrong square, not which byte.
    let after: String
    let activePlayer: String
    let scores: [Int]
    let turnNumber: Int
    /// Only on the opening moves of each game. Checking move *generation* everywhere
    /// quadrupled the file for very little: if a port generates the wrong moves it shows
    /// up within a handful of turns, and the position check catches it thereafter.
    let legalBefore: [String]?
}

func places(_ state: GameState) -> String {
    state.pieces.map { piece in
        guard let square = piece.position else { return "\(piece.id.rawValue)@-" }
        return "\(piece.id.rawValue)@\(square.row),\(square.column)"
    }.joined(separator: " ")
}

func snapshot(_ state: GameState) -> Position {
    Position(
        pieces: state.pieces.map {
            PieceSnapshot(
                id: $0.id.rawValue,
                owner: $0.owner.rawValue,
                kind: $0.kind.rawValue,
                row: $0.position?.row,
                column: $0.position?.column,
                homeRow: $0.home?.row,
                homeColumn: $0.home?.column,
                respawnTurn: $0.respawnTurn
            )
        },
        activePlayer: state.activePlayer.rawValue,
        scores: [state.scores.one, state.scores.two],
        turnNumber: state.turnNumber
    )
}

func describe(_ event: GameEvent) -> String {
    switch event {
    case .moved(let id, let from, let to): "moved \(id) \(from) \(to)"
    case .blenderBattle(let report, let at):
        "battle \(report.attacker.rawValue) \(report.attackerFruit)+\(report.attackerBonus)"
            + "+\(report.attackerRoll) v \(report.defenderFruit)+\(report.defenderRoll) at \(at)"
    case .blenderDestroyed(let id, let owner, let by, let at):
        "blenderDestroyed \(id) \(owner.rawValue) by \(by) at \(at)"
    case .blenderRestored(let id, let owner, let at, let cost):
        "blenderRestored \(id) \(owner.rawValue) at \(at) cost \(cost)"
    case .blenderRespawned(let id, let owner, let at):
        "blenderRespawned \(id) \(owner.rawValue) at \(at)"
    case .bananaReturned(let id, let to): "bananaReturned \(id) \(to)"
    case .blended(let id, let kind, let owner, let at, let to, let points):
        "blended \(id) \(kind.rawValue) of \(owner.rawValue) at \(at) to \(to.rawValue) \(points)"
    case .delivered(let id, let kind, let owner, let at, let points):
        "delivered \(id) \(kind.rawValue) \(owner.rawValue) at \(at) \(points)"
    case .scoreChanged(let player, let score): "score \(player.rawValue) \(score)"
    case .smoothiePoured(let player, let points): "smoothie \(player.rawValue) \(points)"
    case .smoothie(let player, let ids, let turn):
        "smoothieBlast \(player.rawValue) \(ids.map(\.rawValue).joined(separator: ",")) \(turn)"
    case .pineappleTurnedGolden(let id, let owner): "golden \(id) \(owner.rawValue)"
    case .turnPassed(let skipped): "turnPassed \(skipped.rawValue)"
    case .turnChanged(let active): "turnChanged \(active.rawValue)"
    case .gameEnded(let outcome): "ended \(describe(outcome))"
    }
}

func describe(_ outcome: Outcome) -> String {
    switch outcome {
    case .draw: "draw"
    case .win(let player, let reason): "\(player.rawValue) by \(reason.rawValue)"
    }
}

/// Picks one action from the legal list. Both are pure functions of the position and the
/// move number, so a port reproduces them without needing the chooser itself.
enum Driver: String, CaseIterable {
    case first
    case wander
    /// Chases a Blenderama: always blends its own fruit when it can.
    ///
    /// Needed because the shutout rule turned Blenderama into a perfect game — measured
    /// at zero wins in twenty-four CPU matches — so no amount of wandering will ever
    /// stumble into one, and the most intricate rule in the game would go untested.
    case selfBlender

    func choose(
        _ actions: [GameAction], step: Int, seed: UInt64, in currentState: GameState
    ) -> GameAction? {
        guard !actions.isEmpty else { return nil }
        switch self {
        case .first:
            return actions[0]
        case .wander:
            var generator = SeededGenerator(seed: seed &* 0x9E37_79B9_7F4A_7C15 &+ UInt64(step))
            return actions[Int(generator.next() % UInt64(actions.count))]
        case .selfBlender:
            return actions.first { action in
                guard case .move(let move) = action,
                      let piece = selfBlendTarget(of: move, in: currentState) else { return false }
                return piece
            } ?? actions[0]
        }
    }
}

/// Whether this move puts a blender onto its own owner's fruit.
func selfBlendTarget(of move: Move, in state: GameState) -> Bool? {
    guard let mover = state.piece(with: move.pieceID), mover.kind == .blender else { return nil }
    guard let occupant = state.piece(at: move.destination) else { return nil }
    return occupant.owner == mover.owner && occupant.kind != .blender ? true : nil
}

var games: [Game] = []
var seed: UInt64 = 1

for ruleSet in RuleSet.allCases {
    for setup in BoardSetup.allCases {
        for wrap in BoardWrap.allCases {
            for battles in [false, true] {
              for driver in Driver.allCases {
                let starting: PlayerID = seed.isMultiple(of: 2) ? .one : .two
                var state = GameSetup.newGame(
                    startingPlayer: starting,
                    ruleSet: ruleSet,
                    boardSetup: setup,
                    boardWrap: wrap,
                    blenderBattles: battles,
                    seed: seed
                )
                let opening = snapshot(state)
                var steps: [Step] = []

                while !state.status.isOver, steps.count < 150 {
                    let legal = RulesEngine.legalActions(in: state).sorted()
                    guard let action = driver.choose(
                        legal, step: steps.count, seed: seed, in: state
                    ) else { break }
                    guard let transition = try? RulesEngine.apply(action, to: state) else { break }
                    steps.append(
                        Step(
                            action: action.description,
                            events: transition.events.map(describe),
                            after: places(transition.state),
                            activePlayer: transition.state.activePlayer.rawValue,
                            scores: [transition.state.scores.one, transition.state.scores.two],
                            turnNumber: transition.state.turnNumber,
                            legalBefore: steps.count < 8 ? legal.map(\.description) : nil
                        )
                    )
                    state = transition.state
                }

                games.append(
                    Game(
                        driver: driver.rawValue,
                        ruleSet: ruleSet.rawValue,
                        boardSetup: setup.rawValue,
                        boardWrap: wrap.rawValue,
                        blenderBattles: battles,
                        startingPlayer: starting.rawValue,
                        seed: seed,
                        opening: opening,
                        steps: steps,
                        outcome: state.outcome.map(describe)
                    )
                )
                seed += 1
              }
            }
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
    : URL(fileURLWithPath: "conformance.json")
try FileManager.default.createDirectory(
    at: destination.deletingLastPathComponent(), withIntermediateDirectories: true
)
try data.write(to: destination)

let totalSteps = games.reduce(0) { $0 + $1.steps.count }
let finished = games.filter { $0.outcome != nil }.count
print("wrote \(games.count) games, \(totalSteps) moves, \(finished) finished")

// What the fixtures actually exercise. Printed because "the rule is ported" and "the
// rule is covered" are different claims, and only one of them is worth making.
var coverage: [String: Int] = [:]
for game in games {
    for step in game.steps {
        for event in step.events {
            let name = event.split(separator: " ").first.map(String.init) ?? ""
            coverage[name, default: 0] += 1
        }
    }
    if let outcome = game.outcome {
        coverage["outcome:" + outcome.replacingOccurrences(of: "one ", with: "")
            .replacingOccurrences(of: "two ", with: ""), default: 0] += 1
    }
}
print("  coverage:")
for (name, count) in coverage.sorted(by: { $0.key < $1.key }) {
    print("    \(count)\t\(name)")
}
print("  rules \(RulesEngine.rulesVersion), schema \(MatchPayload.currentSchemaVersion)")
print("  \(data.count) bytes → \(destination.path)")
