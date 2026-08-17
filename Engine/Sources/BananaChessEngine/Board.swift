/// Fixed board geometry for the MVP ruleset. All values are tuning constants: the
/// engine never hard-codes 6 anywhere else.
public enum Board {
    public static let size = 6

    public static let allPositions: [Position] = (0..<size).flatMap { row in
        (0..<size).map { Position(row: row, column: $0) }
    }

    public static func contains(_ position: Position) -> Bool {
        (0..<size).contains(position.row) && (0..<size).contains(position.column)
    }
}

/// Row/column coordinate. Row 0 is player two's back row; row 5 is player one's.
public struct Position: Hashable, Codable, Sendable {
    public let row: Int
    public let column: Int

    public init(row: Int, column: Int) {
        self.row = row
        self.column = column
    }

    public func offset(row rowDelta: Int, column columnDelta: Int) -> Position {
        Position(row: row + rowDelta, column: column + columnDelta)
    }

    /// Alternating square colour. The two blender homes of a player sit on opposite
    /// colours, which is what lets a player's blenders together reach every square.
    public var isDarkSquare: Bool { (row + column).isMultiple(of: 2) }
}

extension Position: CustomStringConvertible {
    public var description: String { "r\(row)c\(column)" }
}

extension Position: Comparable {
    public static func < (lhs: Position, rhs: Position) -> Bool {
        (lhs.row, lhs.column) < (rhs.row, rhs.column)
    }
}

enum Direction {
    static let orthogonal: [(row: Int, column: Int)] = [
        (-1, 0), (0, -1), (0, 1), (1, 0),
    ]

    static let diagonal: [(row: Int, column: Int)] = [
        (-1, -1), (-1, 1), (1, -1), (1, 1),
    ]
}
