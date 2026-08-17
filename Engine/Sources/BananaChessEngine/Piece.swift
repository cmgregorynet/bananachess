/// Stable identifier that survives encoding, undo, and network round trips.
public struct PieceID: Hashable, Codable, Sendable, Comparable, CustomStringConvertible {
    public let rawValue: String

    public init(_ rawValue: String) {
        self.rawValue = rawValue
    }

    public init(from decoder: any Decoder) throws {
        rawValue = try decoder.singleValueContainer().decode(String.self)
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }

    public static func < (lhs: PieceID, rhs: PieceID) -> Bool { lhs.rawValue < rhs.rawValue }

    public var description: String { rawValue }
}

public enum PieceKind: String, Codable, Sendable, CaseIterable, Hashable {
    case banana
    case strawberry
    case pineapple
    case blender

    public var isFruit: Bool { self != .blender }

    /// Points a blender's owner receives for blending this fruit. Delivery pays double.
    public var baseValue: Int {
        switch self {
        case .banana: 1
        case .strawberry: 2
        case .pineapple: 3
        case .blender: 0
        }
    }

    public var deliveryValue: Int { baseValue * 2 }
}

public struct Piece: Identifiable, Hashable, Codable, Sendable {
    public let id: PieceID
    public let owner: PlayerID
    public let kind: PieceKind
    /// `nil` while the piece is off the board — a destroyed blender waiting to be
    /// bought back, or a delivered banana waiting for its spawn square to clear. A
    /// piece that is nowhere is genuinely nowhere: it does not occupy its spawn.
    public var position: Position?
    /// The square this piece started on. Blenders are restored here; delivered bananas
    /// return here.
    public let home: Position?
    /// Blenders only, and only after a Tropical Smoothie: the global turn at whose start
    /// this blender comes back on its own. A blender destroyed in combat has no such
    /// date — it returns when, and only when, its owner pays for it.
    public var respawnTurn: Int?

    public init(
        id: PieceID,
        owner: PlayerID,
        kind: PieceKind,
        position: Position?,
        home: Position? = nil,
        respawnTurn: Int? = nil
    ) {
        self.id = id
        self.owner = owner
        self.kind = kind
        self.position = position
        self.home = home
        self.respawnTurn = respawnTurn
    }

    public var isFruit: Bool { kind.isFruit }
    public var isBlender: Bool { kind == .blender }
    public var isOnBoard: Bool { position != nil }

    /// Off the board. Destroyed in combat if there is no return date, in which case it
    /// only comes back when its owner buys it; blown up by a smoothie if there is.
    public var isDestroyed: Bool { isBlender && position == nil }

    /// Off the board but due back on its own after a Tropical Smoothie.
    public var isAwaitingRespawn: Bool { isDestroyed && respawnTurn != nil }

    /// Off the board for good unless bought back.
    public var needsBuyingBack: Bool { isDestroyed && respawnTurn == nil }

    /// Delivered, scored, and queued to reappear on its spawn as soon as that square is
    /// free. Wildcard rules only — bananas do not recycle in a standard match.
    public var isWaitingToReturn: Bool { kind == .banana && position == nil }
}
