/// Which rule set a match is being played under. Fixed when the match is created and
/// carried in the state, so a saved game or an online payload always says which game it
/// is — the engine never has to be told out of band.
public enum RuleSet: String, Codable, Hashable, Sendable, CaseIterable, Identifiable {
    case standard
    case wildcard

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .standard: "Standard"
        case .wildcard: "Wildcard"
        }
    }

    public var blurb: String {
        switch self {
        case .standard: "Score the most points."
        case .wildcard: "Golden pineapples, smoothies, and two ways to win outright."
        }
    }

    public var usesWildcards: Bool { self == .wildcard }
}

/// A value held once per player. Encodes as two named fields rather than a dictionary,
/// which keeps the wire format byte-stable — a `Dictionary` would order its keys
/// arbitrarily and break payload comparison.
public struct PlayerPair<Value: Hashable & Codable & Sendable>: Hashable, Codable, Sendable {
    public var one: Value
    public var two: Value

    public init(_ value: Value) {
        one = value
        two = value
    }

    public init(one: Value, two: Value) {
        self.one = one
        self.two = two
    }

    public subscript(player: PlayerID) -> Value {
        get { player == .one ? one : two }
        set { if player == .one { one = newValue } else { two = newValue } }
    }
}

/// Which fruit types a player has blended at least one of. Three flags rather than a
/// `Set`, for the same byte-stability reason as `PlayerPair`.
public struct SmoothieProgress: Hashable, Codable, Sendable {
    public var banana = false
    public var strawberry = false
    public var pineapple = false
    /// At least one fruit in the recipe came off the opponent. A smoothie made entirely
    /// out of your own fruit does not pour, however many types went into it — the recipe
    /// takes a stolen ingredient.
    public var includesOpposingFruit = false

    public init() {}

    /// One of every fruit type, ignoring whose they were.
    public var hasEveryKind: Bool { banana && strawberry && pineapple }
    /// Everything the recipe asks for: all three types, at least one of them theirs.
    public var isComplete: Bool { hasEveryKind && includesOpposingFruit }
    /// True once the types are all in but the stolen ingredient is still missing — the
    /// state worth calling out, since it looks finished and is not.
    public var needsOpposingFruit: Bool { hasEveryKind && !includesOpposingFruit }
    public var collectedCount: Int { [banana, strawberry, pineapple].filter { $0 }.count }

    public func has(_ kind: PieceKind) -> Bool {
        switch kind {
        case .banana: banana
        case .strawberry: strawberry
        case .pineapple: pineapple
        case .blender: false
        }
    }

    mutating func record(_ kind: PieceKind, opposing: Bool) {
        switch kind {
        case .banana: banana = true
        case .strawberry: strawberry = true
        case .pineapple: pineapple = true
        case .blender: break
        }
        if opposing, kind != .blender { includesOpposingFruit = true }
    }
}

/// Everything the wildcard rules need to remember. Always present so the shape of a
/// saved match never depends on its rule set; simply untouched in a standard game.
public struct WildcardProgress: Hashable, Codable, Sendable {
    /// Each player's surviving pineapple, promoted once their first one was blended.
    /// Both players can hold one at the same time.
    public var goldenPineapple = PlayerPair<PieceID?>(nil)
    /// Set once that player's first pineapple has been blended, whether or not a
    /// survivor existed to promote.
    public var firstPineappleBlended = PlayerPair(false)
    /// Fruit types each player has blended, toward a Tropical Smoothie.
    public var smoothie = PlayerPair(SmoothieProgress())
    /// Smoothies only go off once each.
    public var smoothieFired = PlayerPair(false)
    /// Bananas each player has delivered, toward a Banana Split.
    public var bananasDelivered = PlayerPair(0)
    /// Fruit each player has blended *of their own*, toward a Blenderama.
    public var ownFruitBlended = PlayerPair(0)
    /// Set the moment the opponent scores at all — a fruit across, or one of this
    /// player's fruit through their blenders. Blenderama is then permanently unavailable
    /// to this player: the hard counter, tracked explicitly rather than inferred, so the
    /// board can say so out loud. It makes Blenderama a shutout — you have to run the
    /// whole thing while the other player never once gets on the board.
    public var blenderamaCountered = PlayerPair(false)

    public init() {}

    public func isGolden(_ pieceID: PieceID) -> Bool {
        goldenPineapple.one == pieceID || goldenPineapple.two == pieceID
    }
}

/// How a match was won. A points lead is only one of the ways.
public enum WinReason: String, Codable, Hashable, Sendable, CaseIterable {
    case score
    case bananaSplit
    case blenderama

    public var headline: String {
        switch self {
        case .score: "wins"
        case .bananaSplit: "wins by Banana Split"
        case .blenderama: "wins by Blenderama"
        }
    }
}
