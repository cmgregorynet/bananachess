/// Which edges of the board join up. Orthogonal to both `RuleSet` and `BoardSetup`: any
/// arrangement can be played on any edges, under either set of rules.
///
/// This one *is* read by the rules, unlike `BoardSetup` — it changes which moves are
/// legal on every turn, not just where the pieces began.
///
/// It applies to **fruit only**. Blenders never cross an edge on any board: joined edges
/// are a rule about the fruit's run, not about the machines chasing it. See
/// `GameState.edges(for:)`, which is the only place that distinction is made.
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
        case .flat: "Edges are walls. Everything stops at them."
        case .sides: "Fruit can step off the left or right edge and arrive at the other."
        case .all: "Fruit can cross any edge, top and bottom too. Blenders never can."
        }
    }
}
