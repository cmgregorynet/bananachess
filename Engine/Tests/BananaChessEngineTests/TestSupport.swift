import Foundation
@testable import BananaChessEngine

/// Builds arbitrary positions for rule fixtures without going through a real opening.
struct BoardBuilder {
    private(set) var pieces: [Piece] = []
    private var fruitCount = 0
    private var blenderCount = 0

    mutating func fruit(
        _ kind: PieceKind,
        _ owner: PlayerID,
        at position: Position,
        home: Position? = nil
    ) -> PieceID {
        let id = PieceID("\(owner.rawValue).fruit.\(fruitCount)")
        fruitCount += 1
        pieces.append(
            Piece(id: id, owner: owner, kind: kind, position: position, home: home ?? position)
        )
        return id
    }

    mutating func blender(
        _ owner: PlayerID,
        at position: Position,
        home: Position? = nil
    ) -> PieceID {
        let id = PieceID("\(owner.rawValue).blender.\(blenderCount)")
        blenderCount += 1
        pieces.append(
            Piece(id: id, owner: owner, kind: .blender, position: position, home: home ?? position)
        )
        return id
    }

    /// A blender already destroyed, for fixtures that start after a loss.
    mutating func destroyedBlender(_ owner: PlayerID, home: Position) -> PieceID {
        let id = PieceID("\(owner.rawValue).blender.\(blenderCount)")
        blenderCount += 1
        pieces.append(Piece(id: id, owner: owner, kind: .blender, position: nil, home: home))
        return id
    }

    func state(
        active: PlayerID = .one,
        ruleSet: RuleSet = .standard,
        scores: Scores = Scores()
    ) -> GameState {
        GameState(pieces: pieces, activePlayer: active, scores: scores, ruleSet: ruleSet)
    }
}

func position(_ row: Int, _ column: Int) -> Position {
    Position(row: row, column: column)
}

extension GameState {
    func move(_ id: PieceID, to destination: Position) -> Move {
        Move(pieceID: id, origin: piece(with: id)!.position!, destination: destination)
    }

    func square(of id: PieceID) -> Position? {
        piece(with: id)?.position
    }

    func isOffBoard(_ id: PieceID) -> Bool {
        piece(with: id).map { !$0.isOnBoard } ?? false
    }
}

/// Plays one deliberately inert move for the active player, so global turns can pass
/// while a knockout window elapses without the filler disturbing the fixture. Prefers a
/// fruit step that scores nothing, lands on an empty square, keeps off every blender
/// spawn, and leaves `protecting` alone.
func idleTransition(
    _ state: GameState,
    protecting: Set<PieceID> = []
) throws -> RulesEngine.Transition {
    let spawns = Set(state.pieces.compactMap(\.home))
    let legal = RulesEngine.legalMoves(in: state)

    let inert = legal.first { move in
        guard !protecting.contains(move.pieceID) else { return false }
        guard let piece = state.piece(with: move.pieceID), piece.isFruit else { return false }
        guard state.piece(at: move.destination) == nil else { return false }
        guard !spawns.contains(move.destination) else { return false }
        return move.destination.row != piece.owner.deliveryRow
    }

    // Fall back rather than fail: some fixtures run until nothing inert is left.
    guard let move = inert ?? legal.first(where: { !protecting.contains($0.pieceID) }) ?? legal.first
    else { return RulesEngine.Transition(state: state, events: []) }

    return try RulesEngine.apply(move, to: state)
}

func advanceTurns(
    _ count: Int,
    from state: GameState,
    protecting: Set<PieceID> = []
) throws -> GameState {
    var current = state
    for _ in 0..<count {
        guard !current.status.isOver else { break }
        current = try idleTransition(current, protecting: protecting).state
    }
    return current
}

extension Array where Element == GameEvent {
    var blends: [GameEvent] {
        filter { if case .blended = $0 { return true } else { return false } }
    }

    var destructions: [GameEvent] {
        filter { if case .blenderDestroyed = $0 { return true } else { return false } }
    }

    var restorations: [GameEvent] {
        filter { if case .blenderRestored = $0 { return true } else { return false } }
    }

    var deliveries: [GameEvent] {
        filter { if case .delivered = $0 { return true } else { return false } }
    }

    func bananaReturn(of id: PieceID) -> Position? {
        for event in self {
            if case .bananaReturned(let pieceID, let to) = event, pieceID == id { return to }
        }
        return nil
    }
}

/// Plays a whole match with two CPUs. Used to prove matches terminate and that no
/// resolution path ever leaves two pieces on one square.
@discardableResult
func playOut(
    _ state: GameState,
    one: CPUPlayer,
    two: CPUPlayer,
    turnLimit: Int = 600,
    onEachTurn: ((GameState, [GameEvent]) -> Void)? = nil
) -> GameState {
    var current = state
    var turns = 0
    while !current.status.isOver, turns < turnLimit {
        let cpu = current.activePlayer == .one ? one : two
        guard let action = cpu.selectAction(in: current) else { break }
        guard let transition = try? RulesEngine.apply(action, to: current) else { break }
        current = transition.state
        onEachTurn?(current, transition.events)
        turns += 1
    }
    return current
}
