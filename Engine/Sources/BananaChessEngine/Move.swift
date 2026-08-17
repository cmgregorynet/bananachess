public struct Move: Hashable, Codable, Sendable {
    public let pieceID: PieceID
    public let origin: Position
    public let destination: Position

    public init(pieceID: PieceID, origin: Position, destination: Position) {
        self.pieceID = pieceID
        self.origin = origin
        self.destination = destination
    }
}

extension Move: Comparable {
    /// Canonical ordering so move lists, CPU tie-breaks, and encoded histories are
    /// reproducible across runs and devices.
    public static func < (lhs: Move, rhs: Move) -> Bool {
        (lhs.pieceID, lhs.destination) < (rhs.pieceID, rhs.destination)
    }
}

extension Move: CustomStringConvertible {
    public var description: String { "\(pieceID) \(origin)→\(destination)" }
}
