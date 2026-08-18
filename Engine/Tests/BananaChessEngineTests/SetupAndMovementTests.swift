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

@Suite("Board setups")
struct BoardSetupTests {
    @Test("Every setup deals the same pieces, only differently placed", arguments: BoardSetup.allCases)
    func everySetupDealsTheSamePieces(setup: BoardSetup) {
        let state = GameSetup.newGame(boardSetup: setup, seed: 99)

        #expect(state.boardSetup == setup, "and the match says which it is")
        #expect(state.pieces.count == 16)
        for player in PlayerID.allCases {
            #expect(state.fruit(ownedBy: player).count == GameSetup.fruitCount)
            #expect(state.blenders(ownedBy: player).count == 2)
            for kind in [PieceKind.banana, .strawberry, .pineapple] {
                #expect(state.fruit(ownedBy: player).filter { $0.kind == kind }.count == 2)
            }
        }
        // No two pieces ever share a square.
        let squares = state.pieces.compactMap(\.position)
        #expect(Set(squares).count == squares.count)
        // Everything starts on its own square, which is where bananas recycle to.
        #expect(state.pieces.allSatisfy { $0.position == $0.home })
        #expect(!state.status.isOver)
        #expect(!RulesEngine.legalMoves(in: state).isEmpty)
    }

    @Test("Blendy is Standard with the two rows swapped")
    func blendyIsStandardInverted() {
        let standard = GameSetup.newGame(boardSetup: .standard)
        let blendy = GameSetup.newGame(boardSetup: .blendy)

        for player in PlayerID.allCases {
            #expect(standard.blenders(ownedBy: player).allSatisfy { $0.position?.row == player.backRow })
            #expect(standard.fruit(ownedBy: player).allSatisfy { $0.position?.row == player.fruitRow })

            #expect(blendy.fruit(ownedBy: player).allSatisfy { $0.position?.row == player.backRow })
            #expect(blendy.blenders(ownedBy: player).allSatisfy { $0.position?.row == player.fruitRow })

            // Same fruit in the same file, one row further back.
            for (mine, theirs) in zip(
                standard.fruit(ownedBy: player).sorted(by: { $0.id < $1.id }),
                blendy.fruit(ownedBy: player).sorted(by: { $0.id < $1.id })
            ) {
                #expect(mine.kind == theirs.kind)
                #expect(mine.position?.column == theirs.position?.column)
            }
        }
    }

    @Test("Blendy keeps a player's two spawns on opposite colours")
    func blendySpawnsStayOnOppositeColours() {
        // The invariant the whole buy-back rule leans on: a blender never changes
        // colour, so two spawns of the same colour would leave half the board uncovered.
        for setup in [BoardSetup.standard, .blendy] {
            let state = GameSetup.newGame(boardSetup: setup)
            for player in PlayerID.allCases {
                let colours = state.blenders(ownedBy: player).compactMap(\.position).map {
                    ($0.row + $0.column).isMultiple(of: 2)
                }
                #expect(Set(colours).count == 2, "\(setup) leaves \(player) covering one colour")
            }
        }
    }

    @Test("A random board never starts a fruit on its own delivery row")
    func randomNeverDealsAFreeDelivery() {
        // The one placement rule, checked across many boards rather than one.
        for seed in UInt64(1)...200 {
            let state = GameSetup.newGame(boardSetup: .random, seed: seed)
            for player in PlayerID.allCases {
                #expect(
                    state.fruit(ownedBy: player).allSatisfy { $0.position?.row != player.deliveryRow },
                    "seed \(seed) dealt \(player) a free delivery"
                )
            }
            let squares = state.pieces.compactMap(\.position)
            #expect(Set(squares).count == 16, "seed \(seed) overlapped two pieces")
        }
    }

    @Test("A seed deals the same board twice, and different seeds do not")
    func randomIsReproducibleFromItsSeed() {
        #expect(
            GameSetup.newGame(boardSetup: .random, seed: 7).pieces
                == GameSetup.newGame(boardSetup: .random, seed: 7).pieces
        )

        // Not a strict requirement of the rules, but a mode that quietly dealt the same
        // board every time would be a bug nobody noticed.
        let boards = Set((UInt64(1)...50).map { seed in
            GameSetup.newGame(boardSetup: .random, seed: seed).pieces.map(\.position).description
        })
        #expect(boards.count > 45, "seeds should not collide on the same arrangement")
    }

    @Test("A random board plays: it is legal, scored, and finishes", .timeLimit(.minutes(2)))
    func randomBoardsPlayThrough() {
        for seed in UInt64(1)...12 {
            var state = GameSetup.newGame(ruleSet: .wildcard, boardSetup: .random, seed: seed)
            let one = CPUPlayer(difficulty: .easy, seed: seed)
            let two = CPUPlayer(difficulty: .easy, seed: seed &* 7 &+ 3)
            while !state.status.isOver, state.turnNumber < 800 {
                let cpu = state.activePlayer == .one ? one : two
                guard let action = cpu.selectAction(in: state),
                      let result = try? RulesEngine.apply(action, to: state) else { break }
                state = result.state
                let squares = state.pieces.compactMap(\.position)
                #expect(Set(squares).count == squares.count, "pieces overlapped on seed \(seed)")
            }
            #expect(state.status.isOver, "seed \(seed) never finished")
            #expect(state.boardSetup == .random, "and it still knows what it was dealt")
        }
    }

    @Test("The setup survives a round trip through a payload")
    func setupSurvivesEncoding() throws {
        let payload = MatchPayload.newMatch(id: "m", ruleSet: .wildcard, boardSetup: .blendy)
        let decoded = try MatchCodec.decode(try MatchCodec.encode(payload))

        #expect(decoded.state.boardSetup == .blendy)
        #expect(decoded.state.pieces == payload.state.pieces)
    }
}

@Suite("Board edges")
struct BoardWrapTests {
    /// A lone fruit in a corner, so the edges are the only thing limiting it.
    private func corner(_ wrap: BoardWrap, at square: Position) -> (state: GameState, fruit: PieceID) {
        var builder = BoardBuilder()
        let fruit = builder.fruit(.strawberry, .one, at: square)
        _ = builder.fruit(.banana, .two, at: position(3, 3))
        var state = builder.state()
        state.boardWrap = wrap
        return (state, fruit)
    }

    @Test("With walls, a piece on the left edge cannot go further left")
    func wallsStopAPiece() {
        let f = corner(.flat, at: position(2, 0))
        let destinations = Set(RulesEngine.legalMoves(for: f.fruit, in: f.state).map(\.destination))

        #expect(!destinations.contains(position(2, 5)))
        #expect(destinations == [position(1, 0), position(3, 0), position(2, 1)])
    }

    @Test("With sides joined, stepping off the left edge arrives on the right")
    func sidesWrapAround() {
        let f = corner(.sides, at: position(2, 0))
        let destinations = Set(RulesEngine.legalMoves(for: f.fruit, in: f.state).map(\.destination))

        #expect(destinations.contains(position(2, 5)), "the same row, the far column")
        #expect(destinations.count == 4, "a piece on the edge is no longer short of a move")
    }

    @Test("Sides join, rows do not — the run home is unchanged")
    func sidesLeaveRowsAlone() {
        let f = corner(.sides, at: position(0, 2))
        let destinations = Set(RulesEngine.legalMoves(for: f.fruit, in: f.state).map(\.destination))

        #expect(!destinations.contains(position(5, 2)), "top and bottom are still walls")
        #expect(destinations == [position(1, 2), position(0, 1), position(0, 3)])
    }

    @Test("With every edge joined, rows wrap too")
    func allWrapsBothWays() {
        let f = corner(.all, at: position(0, 0))
        let destinations = Set(RulesEngine.legalMoves(for: f.fruit, in: f.state).map(\.destination))

        #expect(destinations == [position(5, 0), position(1, 0), position(0, 5), position(0, 1)])
    }

    @Test("Every piece has a move in every direction once all edges join")
    func allEdgesJoinedMeansNoPieceIsEverCornered() {
        var state = GameSetup.newGame(boardWrap: .all)
        state.boardWrap = .all
        for piece in state.pieces where piece.isOnBoard {
            // Four directions each, minus whatever is blocked by another piece — the
            // point is that the *edge* never removes one.
            let steps = RulesEngine.legalMoves(for: piece.id, in: state).count
            let blocked = piece.isBlender ? 0 : Direction.orthogonal.compactMap { step -> Position? in
                Board.step(from: piece.position!, row: step.row, column: step.column, wrap: .all)
            }.filter { state.piece(at: $0) != nil }.count
            #expect(steps + blocked == 4, "\(piece.id) had \(steps) moves and \(blocked) blocked")
        }
    }

    /// The invariant the whole buy-back rule leans on: a blender never changes square
    /// colour, so a player's two blenders between them cover the board. A 6×6 board is
    /// even in both directions, so wrapping shifts by an even number and the parity
    /// survives — worth pinning, because on an odd board it would not.
    @Test("A wrapping blender still never changes square colour", arguments: BoardWrap.allCases)
    func wrappingPreservesSquareColour(wrap: BoardWrap) {
        for origin in Board.allPositions {
            for step in Direction.diagonal {
                guard let destination = Board.step(
                    from: origin, row: step.row, column: step.column, wrap: wrap
                ) else { continue }
                #expect(
                    destination.isDarkSquare == origin.isDarkSquare,
                    "\(origin) → \(destination) changed colour under \(wrap)"
                )
            }
        }
    }

    @Test("A blender can wrap onto a fruit and blend it")
    func aWrappingBlenderStillBlends() throws {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(2, 0), home: position(5, 1))
        _ = builder.fruit(.pineapple, .two, at: position(1, 5))
        _ = builder.fruit(.banana, .one, at: position(4, 4))
        var state = builder.state()
        state.boardWrap = .sides

        let result = try RulesEngine.apply(state.move(blender, to: position(1, 5)), to: state)

        #expect(result.state.scores.one == 3)
        #expect(result.state.square(of: blender) == position(1, 5))
    }

    /// Worth stating out loud: with every edge joined, the delivery row is reachable
    /// *backwards*. A fruit on its own back row is one step from the far edge.
    @Test("With all edges joined, a fruit can deliver by going backwards")
    func allEdgesMakesTheBackRowAdjacentToTheGoal() throws {
        var builder = BoardBuilder()
        // Player one's back row is 5; their delivery row is 0. Those are now adjacent.
        let fruit = builder.fruit(.banana, .one, at: position(5, 2))
        _ = builder.fruit(.banana, .two, at: position(3, 3))
        var state = builder.state()
        state.boardWrap = .all

        let result = try RulesEngine.apply(state.move(fruit, to: position(0, 2)), to: state)

        #expect(result.state.scores.one == PieceKind.banana.deliveryValue)
        #expect(result.state.piece(with: fruit) == nil, "delivered, not merely moved")
    }

    @Test("The edges survive a round trip through a payload")
    func wrapSurvivesEncoding() throws {
        let payload = MatchPayload.newMatch(id: "m", boardSetup: .random, boardWrap: .all)
        let decoded = try MatchCodec.decode(try MatchCodec.encode(payload))

        #expect(decoded.state.boardWrap == .all)
        #expect(decoded.state.boardSetup == .random)
    }

    @Test("Matches on joined edges still play out", .timeLimit(.minutes(3)), arguments: BoardWrap.allCases)
    func wrappedMatchesTerminate(wrap: BoardWrap) {
        for seed in UInt64(1)...6 {
            var state = GameSetup.newGame(ruleSet: .wildcard, boardWrap: wrap, seed: seed)
            let one = CPUPlayer(difficulty: .easy, seed: seed)
            let two = CPUPlayer(difficulty: .easy, seed: seed &* 7 &+ 3)
            while !state.status.isOver, state.turnNumber < 800 {
                let cpu = state.activePlayer == .one ? one : two
                guard let action = cpu.selectAction(in: state),
                      let result = try? RulesEngine.apply(action, to: state) else { break }
                state = result.state
                let squares = state.pieces.compactMap(\.position)
                #expect(Set(squares).count == squares.count, "pieces overlapped under \(wrap)")
                #expect(squares.allSatisfy { Board.contains($0) }, "a piece left the board under \(wrap)")
            }
            #expect(state.status.isOver, "\(wrap) seed \(seed) never finished")
        }
    }
}
