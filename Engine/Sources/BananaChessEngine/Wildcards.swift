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

    public init() {}

    /// One of every fruit type, whoever grew them. There is no restriction on whose
    /// fruit counts: a recipe made entirely of your own is a smoothie like any other.
    public var isComplete: Bool { banana && strawberry && pineapple }
    public var collectedCount: Int { [banana, strawberry, pineapple].filter { $0 }.count }

    public func has(_ kind: PieceKind) -> Bool {
        switch kind {
        case .banana: banana
        case .strawberry: strawberry
        case .pineapple: pineapple
        case .blender: false
        }
    }

    mutating func record(_ kind: PieceKind) {
        switch kind {
        case .banana: banana = true
        case .strawberry: strawberry = true
        case .pineapple: pineapple = true
        case .blender: break
        }
    }
}

/// Everything the wildcard rules need to remember. Always present so the shape of a
/// saved match never depends on its rule set; simply untouched in a standard game.
public struct WildcardProgress: Hashable, Codable, Sendable {
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

/// One player's position on each alternate win, read off the state. The scoreboard
/// tracker and the self-blend warning both ask this, so they can never disagree about
/// whether a run is still alive.
public struct WildcardStanding: Hashable, Sendable {
    public let smoothie: SmoothieProgress
    public let hasFiredSmoothie: Bool
    public let bananasDelivered: Int
    public let ownFruitBlended: Int
    public let isBlenderamaCountered: Bool
    /// Bananas recycle, so a Split only dies when the player has no banana left at all
    /// to run across — every one of theirs has been blended.
    public let isBananaSplitBlocked: Bool
    /// Countered by the opponent scoring, or dead because some of this player's fruit
    /// left the board by a route other than their own blender.
    public let isBlenderamaBlocked: Bool

    public var bananaSplitTarget: Int { GameSetup.bananaDeliveriesToWin }
    public var blenderamaTarget: Int { GameSetup.fruitCount }
    public var isBananaSplitComplete: Bool { bananasDelivered >= bananaSplitTarget }
}

extension GameState {
    public func wildcardStanding(for player: PlayerID) -> WildcardStanding {
        let fruit = fruit(ownedBy: player)
        let bananas = wildcards.bananasDelivered[player]
        let blended = wildcards.ownFruitBlended[player]
        return WildcardStanding(
            smoothie: wildcards.smoothie[player],
            hasFiredSmoothie: wildcards.smoothieFired[player],
            bananasDelivered: bananas,
            ownFruitBlended: blended,
            isBlenderamaCountered: wildcards.blenderamaCountered[player],
            isBananaSplitBlocked: bananas < GameSetup.bananaDeliveriesToWin
                && !fruit.contains { $0.kind == .banana },
            isBlenderamaBlocked: wildcards.blenderamaCountered[player]
                || blended + fruit.count < GameSetup.fruitCount
        )
    }
}
