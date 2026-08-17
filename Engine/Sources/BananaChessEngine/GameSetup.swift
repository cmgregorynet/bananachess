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

    /// Builds an opening position.
    ///
    /// `seed` only matters to `.random`, and only for reproducibility: pass one to get
    /// the same scattered board twice, leave it off and the board is genuinely random.
    /// The layout is settled here and travels inside the state, so an online opponent
    /// receives the board itself rather than a seed to re-roll.
    public static func newGame(
        startingPlayer: PlayerID = .one,
        ruleSet: RuleSet = .standard,
        boardSetup: BoardSetup = .standard,
        seed: UInt64? = nil
    ) -> GameState {
        let pieces = switch boardSetup {
        case .standard: rowPieces(fruitRow: \.fruitRow, blenderRow: \.backRow)
        case .blendy: rowPieces(fruitRow: \.backRow, blenderRow: \.fruitRow)
        case .random: randomPieces(seed: seed ?? .random(in: .min ... .max))
        }

        return GameState(
            pieces: pieces,
            activePlayer: startingPlayer,
            ruleSet: ruleSet,
            boardSetup: boardSetup
        )
    }

    /// The two ordered arrangements. `.standard` and `.blendy` differ by nothing but
    /// which row each kind of piece stands on, so they share this.
    private static func rowPieces(
        fruitRow: KeyPath<PlayerID, Int>,
        blenderRow: KeyPath<PlayerID, Int>
    ) -> [Piece] {
        var pieces: [Piece] = []
        for player in PlayerID.allCases {
            for (index, kind) in fruitOrder.enumerated() {
                // Bananas return to `home` after a delivery under wildcard rules.
                let square = Position(
                    row: player[keyPath: fruitRow],
                    column: player.column(fromOwnLeft: index)
                )
                pieces.append(
                    Piece(
                        id: pieceID(player: player, kind: "fruit", index: index),
                        owner: player,
                        kind: kind,
                        position: square,
                        home: square
                    )
                )
            }

            for (index, column) in blenderHomeColumns.enumerated() {
                let home = Position(row: player[keyPath: blenderRow], column: column)
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
        return pieces
    }

    /// Every piece on a square of its own, drawn from a shuffled board.
    ///
    /// One placement rule, and it is the one that matters: **no fruit starts on its own
    /// delivery row.** A fruit that begins there has a free delivery available on turn
    /// one for the price of a sideways step, which is not a random opening so much as a
    /// random winner. Nothing else is constrained — a blender may start next to enemy
    /// fruit, and the two sides may be lopsided. That is the mode.
    private static func randomPieces(seed: UInt64) -> [Piece] {
        var rng = SeededGenerator(seed: seed)
        var pool = Board.allPositions.shuffled(using: &rng)

        func take(where isAllowed: (Position) -> Bool) -> Position {
            // Safe: 36 squares, 16 pieces, and the rule rules out only 6 of them.
            let index = pool.firstIndex(where: isAllowed)!
            return pool.remove(at: index)
        }

        var pieces: [Piece] = []
        for player in PlayerID.allCases {
            for (index, kind) in fruitOrder.enumerated() {
                let square = take { $0.row != player.deliveryRow }
                pieces.append(
                    Piece(
                        id: pieceID(player: player, kind: "fruit", index: index),
                        owner: player,
                        kind: kind,
                        position: square,
                        home: square
                    )
                )
            }

            for index in blenderHomeColumns.indices {
                let home = take { _ in true }
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
        return pieces
    }
}
