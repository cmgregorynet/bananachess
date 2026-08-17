import Testing
@testable import BananaChessEngine

@Suite("Opening setup")
struct SetupTests {
    @Test("A new game matches the locked opening")
    func newGameSetup() {
        let state = GameSetup.newGame()

        #expect(state.pieces.count == 16)
        #expect(state.activePlayer == .one)
        #expect(state.scores == Scores())
        #expect(state.status == .inProgress)
        #expect(state.turnNumber == 1)

        for player in PlayerID.allCases {
            #expect(state.fruit(ownedBy: player).count == 6)
            #expect(state.blenders(ownedBy: player).count == 2)
            // Two of each: the corrected mix. Three bananas and one pineapple was a
            // slip in the original PRD, and it left the Golden Pineapple rule — which
            // promotes "that player's remaining pineapple" — with nothing to promote.
            #expect(state.fruit(ownedBy: player).filter { $0.kind == .banana }.count == 2)
            #expect(state.fruit(ownedBy: player).filter { $0.kind == .strawberry }.count == 2)
            #expect(state.fruit(ownedBy: player).filter { $0.kind == .pineapple }.count == 2)
            #expect(state.fruit(ownedBy: player).allSatisfy { $0.position?.row == player.fruitRow })
            #expect(state.fruitValueRemaining(for: player) == 12)
        }
    }

    @Test("Fruit order reads the same from each player's own left")
    func mirroredFruitOrder() {
        let state = GameSetup.newGame()

        for player in PlayerID.allCases {
            for (index, expected) in GameSetup.fruitOrder.enumerated() {
                let column = player.column(fromOwnLeft: index)
                let piece = state.piece(at: Position(row: player.fruitRow, column: column))
                #expect(piece?.kind == expected)
                #expect(piece?.owner == player)
            }
        }
    }

    @Test("A player's two blender homes sit on opposite square colours")
    func blenderHomeColours() {
        let state = GameSetup.newGame()

        for player in PlayerID.allCases {
            let homes = state.blenders(ownedBy: player).compactMap(\.home)
            #expect(homes.count == 2)
            #expect(homes[0].isDarkSquare != homes[1].isDarkSquare)
            #expect(homes.allSatisfy { $0.row == player.backRow })
        }
    }

    @Test("Every square is reachable by one of a player's blenders")
    func blendersCoverTheBoard() {
        let state = GameSetup.newGame()

        for player in PlayerID.allCases {
            let homes = state.blenders(ownedBy: player).compactMap(\.home)
            for square in Board.allPositions {
                let reachable = homes.contains { home in
                    // A diagonal mover only ever reaches squares of its own colour.
                    home.isDarkSquare == square.isDarkSquare
                }
                #expect(reachable, "\(square) unreachable for \(player)")
            }
        }
    }
}

@Suite("Movement")
struct MovementTests {
    @Test("Fruit moves exactly one square orthogonally, in any direction")
    func fruitMovement() {
        var builder = BoardBuilder()
        let banana = builder.fruit(.banana, .one, at: position(3, 3))
        let state = builder.state()

        let destinations = Set(RulesEngine.legalMoves(for: banana, in: state).map(\.destination))
        #expect(destinations == [position(2, 3), position(4, 3), position(3, 2), position(3, 4)])
    }

    @Test("Blenders move exactly one square diagonally, in any direction")
    func blenderMovement() {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3))
        let state = builder.state()

        let destinations = Set(RulesEngine.legalMoves(for: blender, in: state).map(\.destination))
        #expect(destinations == [position(2, 2), position(2, 4), position(4, 2), position(4, 4)])
    }

    @Test("Pieces cannot leave the board", arguments: [
        position(0, 0), position(0, 5), position(5, 0), position(5, 5),
    ])
    func cornersAreBounded(corner: Position) {
        var builder = BoardBuilder()
        let fruit = builder.fruit(.banana, .one, at: corner)
        let blender = builder.blender(.two, at: corner == position(0, 0) ? position(5, 5) : position(0, 0))
        let state = builder.state()

        #expect(RulesEngine.legalMoves(for: fruit, in: state).count == 2)
        #expect(RulesEngine.legalMoves(for: blender, in: state).count == 1)
        #expect(RulesEngine.legalMoves(for: fruit, in: state).allSatisfy { Board.contains($0.destination) })
    }

    @Test("Fruit never captures — not even an opponent's fruit, not even to deliver")
    func fruitDoesNotCapture() {
        var builder = BoardBuilder()
        let mover = builder.fruit(.banana, .one, at: position(3, 3))
        _ = builder.fruit(.banana, .two, at: position(2, 3))
        _ = builder.blender(.two, at: position(3, 4))
        let state = builder.state()

        let destinations = Set(RulesEngine.legalMoves(for: mover, in: state).map(\.destination))
        #expect(destinations == [position(4, 3), position(3, 2)])
    }

    @Test("A blocked delivery square cannot be entered")
    func blockedDeliverySquare() {
        var builder = BoardBuilder()
        let banana = builder.fruit(.banana, .one, at: position(1, 2))
        _ = builder.blender(.two, at: position(0, 2), home: position(0, 1))
        let state = builder.state()

        let destinations = Set(RulesEngine.legalMoves(for: banana, in: state).map(\.destination))
        #expect(!destinations.contains(position(0, 2)))
        #expect(state.scores == Scores())
    }

    @Test("A blender may move onto any fruit, its owner's included")
    func blenderMayEnterAnyFruit() {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3))
        _ = builder.fruit(.banana, .one, at: position(2, 2))
        _ = builder.fruit(.strawberry, .two, at: position(2, 4))
        let state = builder.state()

        let destinations = Set(RulesEngine.legalMoves(for: blender, in: state).map(\.destination))
        #expect(destinations.contains(position(2, 2)), "self-blending is now a legal move")
        #expect(destinations.contains(position(2, 4)))
        #expect(destinations.count == 4, "nothing on the board blocks a blender any more")
    }

    @Test("A destroyed blender has no moves and cannot be moved")
    func destroyedBlenderCannotMove() throws {
        var builder = BoardBuilder()
        let mine = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        let theirs = builder.blender(.two, at: position(2, 2), home: position(0, 4))
        _ = builder.fruit(.banana, .one, at: position(4, 5))
        _ = builder.fruit(.banana, .two, at: position(1, 5))
        let state = builder.state()

        let afterCombat = try RulesEngine.apply(state.move(mine, to: position(2, 2)), to: state).state

        #expect(afterCombat.isOffBoard(theirs))
        #expect(RulesEngine.legalMoves(for: theirs, in: afterCombat).isEmpty)
        #expect(throws: RulesError.pieceIsOffBoard(theirs)) {
            try RulesEngine.apply(
                Move(pieceID: theirs, origin: position(0, 4), destination: position(1, 3)),
                to: afterCombat
            )
        }
    }

    @Test("Only the active player's pieces can move")
    func turnOwnership() {
        let state = GameSetup.newGame()
        let opponentPiece = state.fruit(ownedBy: .two)[0]

        #expect(RulesEngine.legalMoves(in: state).allSatisfy {
            state.piece(with: $0.pieceID)?.owner == .one
        })

        let origin = opponentPiece.position!
        let illegal = Move(
            pieceID: opponentPiece.id,
            origin: origin,
            destination: origin.offset(row: 1, column: 0)
        )
        #expect(throws: RulesError.notActivePlayersPiece(opponentPiece.id)) {
            try RulesEngine.apply(illegal, to: state)
        }
    }

    @Test("A move whose origin no longer matches the piece is rejected")
    func staleOriginRejected() {
        let state = GameSetup.newGame()
        let piece = state.fruit(ownedBy: .one)[0]
        let stale = Move(pieceID: piece.id, origin: position(0, 0), destination: position(0, 1))

        #expect(throws: RulesError.self) { try RulesEngine.apply(stale, to: state) }
    }
}
