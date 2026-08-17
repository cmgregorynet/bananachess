/// How the pieces are arranged when a match begins. Orthogonal to `RuleSet`: any board
/// can be played under either set of rules.
///
/// Carried in the state for the same reason the rule set is — a saved game or an online
/// payload should be able to say what it is. Nothing in the rules reads it after setup,
/// because the arrangement is already baked into the pieces by then. It is here so the
/// board can be *named*, not so it can be re-derived.
public enum BoardSetup: String, Codable, Hashable, Sendable, CaseIterable, Identifiable {
    /// Blenders on the back row, fruit in front of them. The original arrangement.
    case standard
    /// The two rows swapped: fruit on the back row, blenders in front of it.
    case blendy
    /// Every piece placed at random, subject to one rule — see `GameSetup.newGame`.
    case random

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .standard: "Standard"
        case .blendy: "Blendy"
        case .random: "Random"
        }
    }

    public var blurb: String {
        switch self {
        case .standard: "Blenders behind, fruit in front."
        case .blendy: "Swapped: fruit behind, blenders in front."
        case .random: "Everything scattered. A different board every time."
        }
    }
}
