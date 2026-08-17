public enum PlayerID: String, Codable, Sendable, CaseIterable, Hashable {
    case one
    case two

    public var opponent: PlayerID { self == .one ? .two : .one }

    /// The row this player's blenders call home and their fruit start in front of.
    public var backRow: Int { self == .one ? Board.size - 1 : 0 }

    /// Where this player's fruit begins the match.
    public var fruitRow: Int { self == .one ? Board.size - 2 : 1 }

    /// Reaching any square on this row delivers a fruit for double its base value.
    public var deliveryRow: Int { opponent.backRow }

    /// Forward is toward the delivery edge. Used by the CPU's advancement term.
    public var forwardRowDelta: Int { self == .one ? -1 : 1 }

    /// Column index of the player's own left-hand file, used to lay out the opening
    /// fruit order identically from each player's point of view.
    func column(fromOwnLeft index: Int) -> Int {
        self == .one ? index : Board.size - 1 - index
    }
}
