/// Which edges of the board join up. Orthogonal to both `RuleSet` and `BoardSetup`: any
/// arrangement can be played on any edges, under either set of rules.
///
/// This one *is* read by the rules, unlike `BoardSetup` — it changes which moves are
/// legal on every turn, not just where the pieces began.
public enum BoardWrap: String, Codable, Hashable, Sendable, CaseIterable, Identifiable {
    /// Edges are walls. A piece at the edge simply has fewer moves.
    case flat
    /// Left and right join. Step off one side and arrive at the other, same row.
    case sides
    /// Every edge joins, rows as well as columns — the board is a loop both ways.
    case all

    public var id: String { rawValue }

    public var wrapsColumns: Bool { self != .flat }
    public var wrapsRows: Bool { self == .all }

    public var displayName: String {
        switch self {
        case .flat: "Walls"
        case .sides: "Sides"
        case .all: "All"
        }
    }

    public var blurb: String {
        switch self {
        case .flat: "Edges are walls. Pieces stop at them."
        case .sides: "Left and right join up. Step off one side, arrive at the other."
        case .all: "Every edge joins up, top and bottom too — including the run home."
        }
    }
}
