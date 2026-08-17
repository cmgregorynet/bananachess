/// Everything a player can spend a turn on. Moving a piece is not the only one: a
/// blender destroyed in combat has to be bought back, and that costs the whole turn.
public enum GameAction: Hashable, Codable, Sendable {
    case move(Move)
    /// Spend the whole turn and `RulesEngine.blenderRestoreCost` points to put a
    /// destroyed blender back on *either* of its owner's spawn squares, whichever is
    /// free. Coming back on the other spawn changes the colour the blender travels on,
    /// which is the only way a blender ever switches diagonals.
    case restoreBlender(pieceID: PieceID, at: Position)
}

extension GameAction: CustomStringConvertible {
    public var description: String {
        switch self {
        case .move(let move): move.description
        case .restoreBlender(let id, let square): "restore \(id) at \(square)"
        }
    }
}

extension GameAction: Comparable {
    /// Canonical ordering, so action lists and CPU tie-breaks are reproducible.
    public static func < (lhs: GameAction, rhs: GameAction) -> Bool {
        switch (lhs, rhs) {
        case (.move(let a), .move(let b)): a < b
        case (.restoreBlender(let a, let p), .restoreBlender(let b, let q)): (a, p) < (b, q)
        case (.move, .restoreBlender): true
        case (.restoreBlender, .move): false
        }
    }
}
