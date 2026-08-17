/// The locked opening position. Tuning constants live here so playtest changes never
/// require touching the rules.
public enum GameSetup {
    /// Banana, Strawberry, Banana, Pineapple, Strawberry, Pineapple — read from each
    /// player's own left-hand side, so both openings are mirror images.
    ///
    /// Two of each fruit. The original PRD listed three bananas and one pineapple;
    /// that was a slip, and the last banana should have been the second pineapple. It
    /// matters beyond the arithmetic: the Golden Pineapple rule promotes "that player's
    /// remaining pineapple", which only means anything if a player has two to start.
    ///
    /// The arrangement is not arbitrary either. A row that reads the same forwards and
    /// backwards — two of each, mirrored about its own centre — leaves every fruit
    /// facing one of equal value across the board, and self-play deadlocks: the two
    /// fruit lines advance into a wall and neither side ever has a local reason to pay
    /// for breaking it. Measured at 3 of 14 matches finishing, against 14 of 14 for
    /// this order. Keep the asymmetry.
    public static let fruitOrder: [PieceKind] = [
        .banana, .strawberry, .banana, .pineapple, .strawberry, .pineapple,
    ]

    /// Columns of each player's back row that hold a blender. These sit on opposite
    /// square colours, so a player's two blenders between them cover the whole board.
    public static let blenderHomeColumns = [1, 4]

    /// How many fruit each player starts with — the Blenderama target.
    public static var fruitCount: Int { fruitOrder.count }

    /// How many bananas each player starts with.
    public static var bananaCount: Int { fruitOrder.filter { $0 == .banana }.count }

    /// Cumulative banana deliveries that win a wildcard match outright. Deliberately
    /// more than a player owns: bananas recycle, so it takes at least one round trip.
    public static let bananaDeliveriesToWin = 3

    public static func pieceID(player: PlayerID, kind: String, index: Int) -> PieceID {
        PieceID("\(player.rawValue).\(kind).\(index)")
    }

    public static func newGame(
        startingPlayer: PlayerID = .one,
        ruleSet: RuleSet = .standard
    ) -> GameState {
        var pieces: [Piece] = []

        for player in PlayerID.allCases {
            for (index, kind) in fruitOrder.enumerated() {
                let column = player.column(fromOwnLeft: index)
                pieces.append(
                    Piece(
                        id: pieceID(player: player, kind: "fruit", index: index),
                        owner: player,
                        kind: kind,
                        position: Position(row: player.fruitRow, column: column),
                        // Bananas return here after a delivery under wildcard rules.
                        home: Position(row: player.fruitRow, column: column)
                    )
                )
            }

            for (index, column) in blenderHomeColumns.enumerated() {
                let home = Position(row: player.backRow, column: column)
                pieces.append(
                    Piece(
                        id: pieceID(player: player, kind: "blender", index: index),
                        owner: player,
                        kind: .blender,
                        position: home,
                        home: home
                    )
                )
            }
        }

        return GameState(pieces: pieces, activePlayer: startingPlayer, ruleSet: ruleSet)
    }

    /// Every blender home on the board, marked on the board even once vacated.
    public static var allBlenderHomes: [Position] {
        PlayerID.allCases.flatMap { player in
            blenderHomeColumns.map { Position(row: player.backRow, column: $0) }
        }
    }
}
