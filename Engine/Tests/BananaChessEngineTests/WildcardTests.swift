import Testing
@testable import BananaChessEngine

@Suite("Wildcards are off unless asked for")
struct WildcardOptInTests {
    @Test("A standard match ignores every wildcard rule")
    func standardMatchIsUnchanged() throws {
        // A position that would trigger three wildcards at once under the other rules.
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        let pineapple = builder.fruit(.pineapple, .two, at: position(2, 4))
        _ = builder.fruit(.pineapple, .one, at: position(4, 0))
        _ = builder.fruit(.banana, .two, at: position(1, 1))
        let state = builder.state()

        #expect(state.ruleSet == .standard)
        let result = try RulesEngine.apply(state.move(blender, to: position(2, 4)), to: state)

        #expect(result.state.scores.one == 3, "plain base value, no golden anything")
        #expect(result.state.wildcards == WildcardProgress(), "nothing is tracked at all")
        #expect(result.state.piece(with: pineapple) == nil)
        #expect(!result.events.contains { if case .pineappleTurnedGolden = $0 { true } else { false } })
    }

    @Test("A new game carries the rule set it was created with")
    func ruleSetIsCarried() throws {
        let wild = GameSetup.newGame(ruleSet: .wildcard)
        #expect(wild.ruleSet == .wildcard)

        let move = RulesEngine.legalMoves(in: wild)[0]
        #expect(try RulesEngine.apply(move, to: wild).state.ruleSet == .wildcard)
    }
}

@Suite("Golden Pineapple")
struct GoldenPineappleTests {
    /// Player two owns both pineapples in this fixture; blending one promotes the other.
    private func fixture() -> (state: GameState, blender: PieceID, blended: PieceID, survivor: PieceID) {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        let blended = builder.fruit(.pineapple, .two, at: position(2, 4))
        let survivor = builder.fruit(.pineapple, .two, at: position(1, 1))
        _ = builder.fruit(.banana, .one, at: position(5, 5))
        return (builder.state(ruleSet: .wildcard), blender, blended, survivor)
    }

    @Test("Losing one of your pineapples promotes the other")
    func promotesTheSurvivor() throws {
        let f = fixture()
        let result = try RulesEngine.apply(f.state.move(f.blender, to: position(2, 4)), to: f.state)

        #expect(result.state.wildcards.goldenPineapple[.two] == f.survivor)
        #expect(result.state.wildcards.goldenPineapple[.one] == nil, "only the owner's own")
        #expect(result.state.wildcards.firstPineappleBlended[.two])
        #expect(result.events.contains(.pineappleTurnedGolden(pieceID: f.survivor, owner: .two)))
        #expect(result.state.scores.one == 3, "the pineapple that was blended scored normally")
    }

    @Test("Each player's pineapples are promoted independently")
    func bothPlayersCanHoldOne() throws {
        var builder = BoardBuilder()
        let mine = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        let theirs = builder.blender(.two, at: position(2, 1), home: position(0, 4))
        _ = builder.fruit(.pineapple, .two, at: position(2, 4))
        let theirSurvivor = builder.fruit(.pineapple, .two, at: position(0, 0))
        _ = builder.fruit(.pineapple, .one, at: position(3, 0))
        let mySurvivor = builder.fruit(.pineapple, .one, at: position(5, 5))
        let state = builder.state(ruleSet: .wildcard)

        var current = try RulesEngine.apply(state.move(mine, to: position(2, 4)), to: state).state
        current = try RulesEngine.apply(current.move(theirs, to: position(3, 0)), to: current).state

        #expect(current.wildcards.goldenPineapple[.two] == theirSurvivor)
        #expect(current.wildcards.goldenPineapple[.one] == mySurvivor)
    }

    @Test("Only the first of a player's pineapples promotes anything")
    func promotesOnlyOnce() throws {
        let f = fixture()
        var state = try RulesEngine.apply(f.state.move(f.blender, to: position(2, 4)), to: f.state).state
        let golden = try #require(state.wildcards.goldenPineapple[.two])

        // Walk round to the golden one and blend that too; nothing new is promoted.
        for step in [position(1, 3), position(2, 2)] {
            state.activePlayer = .one
            state = try RulesEngine.apply(state.move(f.blender, to: step), to: state).state
        }
        state.activePlayer = .one
        let onGolden = try RulesEngine.apply(state.move(f.blender, to: position(1, 1)), to: state)

        #expect(onGolden.state.wildcards.goldenPineapple[.two] == golden, "the record stands")
        #expect(onGolden.state.scores.one == 3 + 6, "and it paid double on the way out")
    }

    @Test("A Golden Pineapple delivers for three times base, not two")
    func goldenDeliversForTriple() throws {
        var builder = BoardBuilder()
        let golden = builder.fruit(.pineapple, .one, at: position(1, 2))
        _ = builder.fruit(.banana, .two, at: position(4, 4))
        var state = builder.state(ruleSet: .wildcard)
        state.wildcards.goldenPineapple[.one] = golden
        state.wildcards.firstPineappleBlended[.one] = true

        let result = try RulesEngine.apply(state.move(golden, to: position(0, 2)), to: state)

        #expect(result.state.scores.one == 9, "3 × base 3, where a plain pineapple would score 6")
    }

    @Test("The opponent blending a Golden Pineapple takes double")
    func opponentBlendsGoldenForDouble() throws {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        let golden = builder.fruit(.pineapple, .two, at: position(2, 4))
        _ = builder.fruit(.banana, .two, at: position(1, 1))
        var state = builder.state(ruleSet: .wildcard)
        state.wildcards.goldenPineapple[.two] = golden
        state.wildcards.firstPineappleBlended[.two] = true

        let result = try RulesEngine.apply(state.move(blender, to: position(2, 4)), to: state)

        #expect(result.state.scores.one == 6, "2 × base 3, where a plain pineapple would pay 3")
    }

    @Test("Blending your own Golden Pineapple costs double too")
    func selfBlendingGoldenCostsDouble() throws {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        let golden = builder.fruit(.pineapple, .one, at: position(2, 4))
        _ = builder.fruit(.banana, .two, at: position(1, 1))
        var state = builder.state(ruleSet: .wildcard)
        state.wildcards.goldenPineapple[.one] = golden
        state.wildcards.firstPineappleBlended[.one] = true

        let result = try RulesEngine.apply(state.move(blender, to: position(2, 4)), to: state)

        #expect(result.state.scores.one == -6)
    }

    @Test("A pineapple that is delivered rather than blended promotes nothing")
    func deliveryDoesNotPromote() throws {
        var builder = BoardBuilder()
        let pineapple = builder.fruit(.pineapple, .one, at: position(1, 2))
        _ = builder.fruit(.pineapple, .one, at: position(4, 4))
        _ = builder.fruit(.banana, .two, at: position(3, 0))
        let state = builder.state(ruleSet: .wildcard)

        let result = try RulesEngine.apply(state.move(pineapple, to: position(0, 2)), to: state)

        #expect(result.state.wildcards.goldenPineapple[.one] == nil)
        #expect(result.state.scores.one == 6, "an ordinary delivery")
    }
}

@Suite("Banana delivery")
struct BananaDeliveryTests {
    /// One banana one step from the far edge, with its spawn free behind it.
    private func fixture(delivered: Int = 0, ruleSet: RuleSet = .wildcard) -> (state: GameState, banana: PieceID) {
        var builder = BoardBuilder()
        let banana = builder.fruit(.banana, .one, at: position(1, 2), home: position(4, 2))
        _ = builder.fruit(.strawberry, .one, at: position(5, 5))
        _ = builder.fruit(.pineapple, .two, at: position(4, 4))
        var state = builder.state(ruleSet: ruleSet)
        state.wildcards.bananasDelivered[.one] = delivered
        return (state, banana)
    }

    @Test("A delivered banana scores and goes back to its own spawn")
    func bananaRecycles() throws {
        let f = fixture()
        let result = try RulesEngine.apply(f.state.move(f.banana, to: position(0, 2)), to: f.state)

        #expect(result.state.scores.one == 2, "an ordinary banana delivery")
        #expect(result.state.square(of: f.banana) == position(4, 2), "and it is back where it started")
        #expect(result.events.bananaReturn(of: f.banana) == position(4, 2))
        #expect(result.state.wildcards.bananasDelivered[.one] == 1)
    }

    @Test("Deliveries count cumulatively, and the third one wins")
    func thirdDeliveryWins() throws {
        let f = fixture(delivered: GameSetup.bananaDeliveriesToWin - 1)
        let result = try RulesEngine.apply(f.state.move(f.banana, to: position(0, 2)), to: f.state)

        #expect(result.state.wildcards.bananasDelivered[.one] == GameSetup.bananaDeliveriesToWin)
        #expect(result.state.status == .ended(.win(.one, by: .bananaSplit)))
    }

    @Test("Two deliveries is not yet three")
    func twoIsNotEnough() throws {
        let f = fixture(delivered: GameSetup.bananaDeliveriesToWin - 2)
        let result = try RulesEngine.apply(f.state.move(f.banana, to: position(0, 2)), to: f.state)

        #expect(result.state.status == .inProgress)
        #expect(result.state.wildcards.bananasDelivered[.one] == GameSetup.bananaDeliveriesToWin - 1)
    }

    /// The detail flagged in the handoff: a banana must not be lost because its spawn
    /// happened to be busy, or the three-banana win could become unreachable.
    @Test("A banana whose spawn is busy waits, then returns when it clears")
    func bananaWaitsForItsSpawn() throws {
        var builder = BoardBuilder()
        let banana = builder.fruit(.banana, .one, at: position(1, 2), home: position(4, 2))
        let squatter = builder.fruit(.strawberry, .one, at: position(4, 2))
        _ = builder.fruit(.pineapple, .two, at: position(0, 5))
        let state = builder.state(ruleSet: .wildcard)

        var current = try RulesEngine.apply(state.move(banana, to: position(0, 2)), to: state).state
        #expect(current.isOffBoard(banana), "it is in transit, not on the board")
        #expect(current.waitingBananas(ownedBy: .one).map(\.id) == [banana])
        #expect(current.scores.one == 2, "it still scored")

        // Player two moves, then player one steps the squatter aside.
        current = try idleTransition(current, protecting: [squatter]).state
        current.activePlayer = .one
        let cleared = try RulesEngine.apply(current.move(squatter, to: position(3, 2)), to: current)

        #expect(cleared.state.square(of: banana) == position(4, 2), "and comes straight back")
        #expect(cleared.events.bananaReturn(of: banana) == position(4, 2))
    }

    @Test("A banana in transit still counts as fruit in play")
    func transitDoesNotEndTheMatch() throws {
        // Player one's only fruit is the banana being delivered.
        var builder = BoardBuilder()
        let banana = builder.fruit(.banana, .one, at: position(1, 2), home: position(4, 2))
        _ = builder.fruit(.strawberry, .two, at: position(4, 2))
        _ = builder.fruit(.pineapple, .two, at: position(0, 5))
        let state = builder.state(ruleSet: .wildcard)

        let result = try RulesEngine.apply(state.move(banana, to: position(0, 2)), to: state)

        #expect(result.state.onBoardFruit(ownedBy: .one).isEmpty, "nothing of theirs is on the board")
        #expect(result.state.waitingBananas(ownedBy: .one).count == 1)
        #expect(result.state.status == .inProgress, "but the banana is coming back, so play continues")
    }

    @Test("Bananas do not recycle in a standard match")
    func standardBananasAreConsumed() throws {
        let f = fixture(ruleSet: .standard)
        let result = try RulesEngine.apply(f.state.move(f.banana, to: position(0, 2)), to: f.state)

        #expect(result.state.piece(with: f.banana) == nil, "delivered and gone, as before")
        #expect(result.state.scores.one == 2)
        #expect(result.state.wildcards.bananasDelivered[.one] == 0, "and nothing is counted")
    }

    @Test("A blended banana is gone for good and cannot be delivered again")
    func blendedBananasDoNotRecycle() throws {
        var builder = BoardBuilder()
        let blender = builder.blender(.two, at: position(3, 3), home: position(0, 1))
        let banana = builder.fruit(.banana, .one, at: position(2, 4), home: position(4, 4))
        _ = builder.fruit(.strawberry, .one, at: position(5, 5))
        _ = builder.fruit(.pineapple, .two, at: position(0, 0))
        let state = builder.state(active: .two, ruleSet: .wildcard)

        let result = try RulesEngine.apply(state.move(blender, to: position(2, 4)), to: state)

        #expect(result.state.piece(with: banana) == nil)
        #expect(result.state.waitingBananas(ownedBy: .one).isEmpty)
    }
}

@Suite("Tropical Smoothie")
struct TropicalSmoothieTests {
    /// One of each fruit type, all reachable by a single blender in turn.
    private func fixture() -> (state: GameState, blender: PieceID, theirBlenders: [PieceID]) {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        _ = builder.fruit(.banana, .two, at: position(2, 2))
        _ = builder.fruit(.strawberry, .two, at: position(2, 4))
        _ = builder.fruit(.pineapple, .two, at: position(4, 4))
        let first = builder.blender(.two, at: position(0, 1), home: position(0, 1))
        let second = builder.blender(.two, at: position(0, 4), home: position(0, 4))
        // A spare fruit each, so the filler turns always have something inert to move.
        _ = builder.fruit(.banana, .one, at: position(5, 5))
        _ = builder.fruit(.banana, .two, at: position(1, 5))
        return (builder.state(ruleSet: .wildcard), blender, [first, second].sorted())
    }

    /// The walk that collects banana, strawberry and pineapple in that order.
    private let recipeWalk = [
        position(2, 2), position(1, 3), position(2, 4), position(3, 3), position(4, 4),
    ]

    @Test("One of each fruit type pays three points")
    func completingASmoothiePaysThePoints() throws {
        let f = fixture()
        var state = f.state

        for destination in recipeWalk.dropLast() {
            state.activePlayer = .one
            state = try RulesEngine.apply(state.move(f.blender, to: destination), to: state).state
        }
        #expect(state.wildcards.smoothie[.one].collectedCount == 2, "still short a pineapple")
        #expect(!state.wildcards.smoothieFired[.one])

        let before = state.scores.one
        state.activePlayer = .one
        let result = try RulesEngine.apply(state.move(f.blender, to: recipeWalk.last!), to: state)

        #expect(result.state.wildcards.smoothie[.one].isComplete)
        #expect(result.state.wildcards.smoothieFired[.one])
        #expect(
            result.events.contains(
                .smoothiePoured(player: .one, points: RulesEngine.smoothieAward)
            )
        )

        // The pineapple it landed on is worth its own points; the smoothie adds three
        // more on top.
        let blended = result.events.compactMap { event -> Int? in
            if case .blended(_, _, _, _, _, let points) = event { return points }
            return nil
        }.reduce(0, +)
        #expect(result.state.scores.one == before + blended + RulesEngine.smoothieAward)
    }

    @Test("Both of the opponent's blenders are left alone — a smoothie pays, it does not blow up")
    func aSmoothieNoLongerTouchesBlenders() throws {
        let f = fixture()
        var state = f.state
        for destination in recipeWalk {
            state.activePlayer = .one
            state = try RulesEngine.apply(state.move(f.blender, to: destination), to: state).state
        }

        #expect(state.wildcards.smoothieFired[.one])
        #expect(state.activeBlenders(ownedBy: .two).count == 2, "both still standing")
        #expect(state.destroyedBlenders(ownedBy: .two).isEmpty)
        #expect(state.scores.two == 0, "and it cost them nothing at all")
    }

    @Test("Whose fruit went in does not matter — your own make a smoothie too")
    func yourOwnFruitCountsJustTheSame() throws {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        // Every type, and every one of them yours.
        _ = builder.fruit(.banana, .one, at: position(2, 2))
        _ = builder.fruit(.strawberry, .one, at: position(2, 4))
        _ = builder.fruit(.pineapple, .one, at: position(4, 4))
        _ = builder.fruit(.banana, .one, at: position(5, 5))
        _ = builder.fruit(.banana, .two, at: position(1, 5))
        _ = builder.blender(.two, at: position(0, 1), home: position(0, 1))
        var state = builder.state(ruleSet: .wildcard)

        for destination in recipeWalk.dropLast() {
            state.activePlayer = .one
            state = try RulesEngine.apply(state.move(blender, to: destination), to: state).state
        }
        state.activePlayer = .one
        let result = try RulesEngine.apply(state.move(blender, to: recipeWalk.last!), to: state)

        #expect(result.state.wildcards.smoothieFired[.one], "no stolen ingredient needed")
        #expect(
            result.events.contains(
                .smoothiePoured(player: .one, points: RulesEngine.smoothieAward)
            )
        )
        // Six points of self-harm on the way, three back at the end.
        #expect(result.state.scores.one == -6 + RulesEngine.smoothieAward)
    }

    @Test("A smoothie pays once and only once")
    func aSmoothiePaysOnce() throws {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        _ = builder.fruit(.banana, .two, at: position(2, 2))
        _ = builder.fruit(.banana, .two, at: position(4, 4))
        _ = builder.fruit(.banana, .one, at: position(5, 5))
        _ = builder.fruit(.banana, .two, at: position(1, 5))
        _ = builder.blender(.two, at: position(0, 1), home: position(0, 1))
        var state = builder.state(ruleSet: .wildcard)
        // Already collected strawberry and pineapple.
        state.wildcards.smoothie[.one].strawberry = true
        state.wildcards.smoothie[.one].pineapple = true

        state = try RulesEngine.apply(state.move(blender, to: position(2, 2)), to: state).state
        #expect(state.wildcards.smoothieFired[.one], "the banana completed it")
        let afterFirst = state.scores.one

        // Blend another banana: points for the fruit, but no second bonus.
        state.activePlayer = .one
        state = try RulesEngine.apply(state.move(blender, to: position(3, 3)), to: state).state
        state.activePlayer = .one
        let again = try RulesEngine.apply(state.move(blender, to: position(4, 4)), to: state)

        #expect(!again.events.contains { if case .smoothiePoured = $0 { true } else { false } })
        #expect(again.state.scores.one == afterFirst + PieceKind.banana.baseValue)
    }

    @Test("A standard match pays no smoothie bonus")
    func standardRulesPayNothing() throws {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        _ = builder.fruit(.banana, .two, at: position(2, 2))
        _ = builder.fruit(.banana, .one, at: position(5, 5))
        _ = builder.fruit(.banana, .two, at: position(1, 5))
        _ = builder.blender(.two, at: position(0, 1), home: position(0, 1))
        var state = builder.state()
        state.wildcards.smoothie[.one].strawberry = true
        state.wildcards.smoothie[.one].pineapple = true

        let result = try RulesEngine.apply(state.move(blender, to: position(2, 2)), to: state)

        #expect(!result.state.wildcards.smoothieFired[.one])
        #expect(result.state.scores.one == PieceKind.banana.baseValue, "the fruit, and nothing else")
    }

    // MARK: - The retained blowout
    //
    // `RulesEngine.pourSmoothie` is no longer called by the rules — a smoothie pays
    // points instead — but it is kept intact so the effect can be switched back on.
    // These drive it directly so it cannot quietly rot in the meantime.

    @Test("Retained: the blowout still takes both blenders off the board")
    func theRetainedBlowoutStillWorks() throws {
        let f = fixture()
        var state = f.state
        var events: [GameEvent] = []

        RulesEngine.pourSmoothie(for: .one, in: &state, events: &events)

        #expect(state.activeBlenders(ownedBy: .two).isEmpty)
        #expect(state.activeBlenders(ownedBy: .one).count == 1, "your own are untouched")
        #expect(events.contains { if case .smoothie = $0 { true } else { false } })

        let victim = state.piece(with: f.theirBlenders[0])
        #expect(victim?.respawnTurn == state.turnNumber + RulesEngine.smoothieBlackoutTurns)
        #expect(victim?.needsBuyingBack == false, "no need to pay for it")
    }

    @Test("Retained: standing on a spawn is no defence against the blowout")
    func theRetainedBlowoutIgnoresSpawnProtection() throws {
        // Both of player two's blenders are sitting on their own spawns, which makes
        // them safe from capture. The blowout is not a capture.
        let f = fixture()
        var state = f.state
        #expect(f.theirBlenders.allSatisfy { id in
            guard let piece = state.piece(with: id) else { return false }
            return RulesEngine.isOnASpawn(piece, in: state)
        })

        var events: [GameEvent] = []
        RulesEngine.pourSmoothie(for: .one, in: &state, events: &events)

        #expect(state.activeBlenders(ownedBy: .two).isEmpty)
    }

    @Test("Retained: victims come back by themselves, for nothing")
    func theRetainedVictimsReturnOnTheirOwn() throws {
        let f = fixture()
        var state = f.state
        var events: [GameEvent] = []
        RulesEngine.pourSmoothie(for: .one, in: &state, events: &events)
        state.activePlayer = .two

        // Player two plays their one blenderless turn, and they are back after it.
        #expect(state.activeBlenders(ownedBy: .two).isEmpty)
        state = try advanceTurns(2, from: state)

        #expect(state.square(of: f.theirBlenders[0]) == position(0, 1))
        #expect(state.square(of: f.theirBlenders[1]) == position(0, 4))
        #expect(state.scores.two == 0, "and it cost them nothing")
    }

    @Test("Retained: a victim on its way back cannot be bought back")
    func theRetainedVictimsAreNotForSale() throws {
        let f = fixture()
        var state = f.state
        var events: [GameEvent] = []
        RulesEngine.pourSmoothie(for: .one, in: &state, events: &events)
        state.activePlayer = .two

        let victim = f.theirBlenders[0]
        #expect(throws: RulesError.blenderReturningOnItsOwn(victim)) {
            try RulesEngine.apply(.restoreBlender(pieceID: victim, at: position(0, 1)), to: state)
        }
        #expect(
            !RulesEngine.legalActions(in: state)
                .contains(.restoreBlender(pieceID: victim, at: position(0, 1)))
        )
    }
}

@Suite("Fruit depletion ends the match")
struct FruitDepletionTests {
    @Test("Running one player out of fruit ends it, even with fruit still on the board")
    func oneEmptySideEndsIt() throws {
        var builder = BoardBuilder()
        let last = builder.fruit(.banana, .one, at: position(1, 2))
        // Player two still has plenty standing; none of it counts.
        _ = builder.fruit(.pineapple, .two, at: position(4, 4))
        _ = builder.fruit(.pineapple, .two, at: position(4, 5))
        _ = builder.fruit(.strawberry, .two, at: position(3, 0))
        let state = builder.state(scores: Scores(one: 0, two: 1))

        let result = try RulesEngine.apply(state.move(last, to: position(0, 2)), to: state)

        #expect(result.state.status == .ended(.win(.one, by: .score)))
        #expect(result.state.scores.one == 2, "the delivery that emptied their side still scored")
        #expect(result.state.scores.two == 1, "eight points of standing fruit counted for nothing")
        #expect(result.state.fruit(ownedBy: .two).count == 3)
    }

    @Test("Being blended out also ends it")
    func blendingOutTheLastFruitEndsIt() throws {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        _ = builder.fruit(.banana, .two, at: position(2, 4))
        _ = builder.fruit(.pineapple, .one, at: position(5, 5))
        let state = builder.state(scores: Scores(one: 0, two: 6))

        let result = try RulesEngine.apply(state.move(blender, to: position(2, 4)), to: state)

        #expect(result.state.status == .ended(.win(.two, by: .score)))
        #expect(result.state.scores.one == 1, "taking their last fruit paid its base value")
    }

    @Test("Blending your own last fruit ends the match too, at a price")
    func endingItOnYourOwnTerms() throws {
        // Player one leads by enough that paying a point to stop the clock is worth it.
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        _ = builder.fruit(.banana, .one, at: position(2, 4))
        _ = builder.fruit(.pineapple, .two, at: position(0, 0))
        let state = builder.state(scores: Scores(one: 9, two: 4))

        let result = try RulesEngine.apply(state.move(blender, to: position(2, 4)), to: state)

        #expect(result.state.scores.one == 8, "it cost a point")
        #expect(result.state.status == .ended(.win(.one, by: .score)), "and won the match")
    }

    @Test("A match with fruit on both sides carries on")
    func bothSidesStockedKeepsPlaying() throws {
        var builder = BoardBuilder()
        let banana = builder.fruit(.banana, .one, at: position(3, 3))
        _ = builder.fruit(.pineapple, .one, at: position(5, 5))
        _ = builder.fruit(.pineapple, .two, at: position(0, 0))
        let state = builder.state()

        let result = try RulesEngine.apply(state.move(banana, to: position(2, 3)), to: state)

        #expect(result.state.status == .inProgress)
    }

    @Test("Depletion applies under standard rules as well as wildcard")
    func appliesToBothRuleSets() throws {
        for ruleSet in RuleSet.allCases {
            var builder = BoardBuilder()
            let last = builder.fruit(.strawberry, .one, at: position(1, 2))
            _ = builder.fruit(.pineapple, .two, at: position(4, 4))
            let state = builder.state(ruleSet: ruleSet)

            let result = try RulesEngine.apply(state.move(last, to: position(0, 2)), to: state)

            #expect(result.state.status.isOver, "\(ruleSet) should end on depletion")
        }
    }

    /// The victory priority from §6: an alternate win is settled before the score is.
    @Test("An alternate win beats the depletion winner when one move triggers both")
    func alternateWinTakesPriority() throws {
        var builder = BoardBuilder()
        let last = builder.fruit(.banana, .one, at: position(1, 2))
        _ = builder.fruit(.pineapple, .two, at: position(4, 4))
        var state = builder.state(ruleSet: .wildcard, scores: Scores(one: 0, two: 50))
        state.wildcards.bananasDelivered[.one] = GameSetup.bananaDeliveriesToWin - 1

        // This delivery both completes a Banana Split and empties player one's side.
        let result = try RulesEngine.apply(state.move(last, to: position(0, 2)), to: state)

        #expect(result.state.status == .ended(.win(.one, by: .bananaSplit)),
                "the Split wins it, not player two's fifty points")
    }
}

@Suite("Blenderama")
struct BlenderamaTests {
    @Test("Blending every one of your own fruit wins outright")
    func blendingAllOwnFruitWins() throws {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        let last = builder.fruit(.banana, .one, at: position(2, 2))
        _ = builder.fruit(.pineapple, .two, at: position(0, 0))
        var state = builder.state(ruleSet: .wildcard, scores: Scores(one: -20, two: 30))
        state.wildcards.ownFruitBlended[.one] = GameSetup.fruitCount - 1

        let result = try RulesEngine.apply(state.move(blender, to: position(2, 2)), to: state)

        #expect(result.state.status == .ended(.win(.one, by: .blenderama)))
        #expect(result.state.piece(with: last) == nil)
        #expect(result.state.scores.one == -21, "twenty-one points down, and the winner")
    }

    /// The exact shape of "I completed Blenderama and lost". Blending all six of your
    /// own fruit costs you twelve points, so if the win does not fire you are deeply
    /// negative — and the match ends the instant your last fruit leaves the board.
    @Test("A countered Blenderama run ends in a loss, which is the whole risk of it")
    func aCounteredRunLosesTheMatch() throws {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        _ = builder.fruit(.banana, .one, at: position(2, 2))
        _ = builder.fruit(.pineapple, .two, at: position(0, 0))
        var state = builder.state(ruleSet: .wildcard, scores: Scores(one: -11, two: 0))
        // Five of their own already blended, and the opponent took the sixth earlier —
        // so the count can never reach six and the run was dead before it finished.
        state.wildcards.ownFruitBlended[.one] = GameSetup.fruitCount - 1
        state.wildcards.blenderamaCountered[.one] = true

        let result = try RulesEngine.apply(state.move(blender, to: position(2, 2)), to: state)

        #expect(result.state.wildcards.ownFruitBlended[.one] == GameSetup.fruitCount)
        #expect(result.state.outcome?.reason == .score, "no Blenderama: it was countered")
        #expect(result.state.outcome == .win(.two, by: .score), "and the run cost the match")
        #expect(result.state.scores.one == -12)
    }

    @Test("An uncountered run of the same length wins outright")
    func anUncounteredRunWins() throws {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        _ = builder.fruit(.banana, .one, at: position(2, 2))
        _ = builder.fruit(.pineapple, .two, at: position(0, 0))
        var state = builder.state(ruleSet: .wildcard, scores: Scores(one: -11, two: 0))
        state.wildcards.ownFruitBlended[.one] = GameSetup.fruitCount - 1

        let result = try RulesEngine.apply(state.move(blender, to: position(2, 2)), to: state)

        #expect(result.state.outcome == .win(.one, by: .blenderama))
        #expect(result.state.scores.one == -12, "twelve points down, and the winner")
    }

    /// The shutout condition. Blending one of your fruit was never the only way to stop
    /// a Blenderama — the opponent simply getting on the board at all does it.
    private func deliveryFixture(ruleSet: RuleSet = .wildcard) -> (state: GameState, fruit: PieceID) {
        var builder = BoardBuilder()
        // One step from player two's delivery row, which is player one's back row.
        let runner = builder.fruit(.strawberry, .two, at: position(4, 2))
        _ = builder.fruit(.banana, .one, at: position(1, 1))
        _ = builder.blender(.one, at: position(5, 1), home: position(5, 1))
        var state = builder.state(active: .two, ruleSet: ruleSet)
        state.wildcards.ownFruitBlended[.one] = 3
        return (state, runner)
    }

    @Test("A fruit across takes the opponent's Blenderama off — the shutout rule")
    func aDeliveryNegatesTheOpponentsBlenderama() throws {
        let f = deliveryFixture()
        #expect(!f.state.wildcards.blenderamaCountered[.one])

        let result = try RulesEngine.apply(
            f.state.move(f.fruit, to: position(PlayerID.two.deliveryRow, 2)),
            to: f.state
        )

        #expect(result.state.scores.two > 0, "they got on the board")
        #expect(result.state.wildcards.blenderamaCountered[.one], "so player one's run is off")
        #expect(!result.state.wildcards.blenderamaCountered[.two], "and theirs is untouched")
    }

    @Test("A run already at five cannot be finished once they have scored")
    func aScoringTurnKillsARunInProgress() throws {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        _ = builder.fruit(.banana, .one, at: position(2, 2))
        let theirs = builder.fruit(.strawberry, .two, at: position(4, 2))
        _ = builder.fruit(.pineapple, .two, at: position(0, 0))
        var state = builder.state(active: .two, ruleSet: .wildcard, scores: Scores(one: -11, two: 0))
        state.wildcards.ownFruitBlended[.one] = GameSetup.fruitCount - 1

        // They deliver. Nothing about player one's run has changed except that it is dead.
        state = try RulesEngine.apply(
            state.move(theirs, to: position(PlayerID.two.deliveryRow, 2)),
            to: state
        ).state
        #expect(state.wildcards.blenderamaCountered[.one])

        state.activePlayer = .one
        let result = try RulesEngine.apply(state.move(blender, to: position(2, 2)), to: state)

        #expect(result.state.wildcards.ownFruitBlended[.one] == GameSetup.fruitCount, "six of six")
        #expect(result.state.outcome?.reason != .blenderama, "and it wins nothing")
    }

    @Test("Your own scoring never touches your own run, and losing points never counts")
    func onlyTheOpponentsPointsNegate() throws {
        // Player one delivers: their own points cannot negate their own Blenderama.
        var builder = BoardBuilder()
        let mine = builder.fruit(.strawberry, .one, at: position(1, 2))
        _ = builder.fruit(.banana, .two, at: position(4, 4))
        _ = builder.blender(.two, at: position(0, 1), home: position(0, 1))
        var state = builder.state(ruleSet: .wildcard)
        state.wildcards.ownFruitBlended[.one] = 2

        state = try RulesEngine.apply(
            state.move(mine, to: position(PlayerID.one.deliveryRow, 2)),
            to: state
        ).state
        #expect(state.scores.one > 0)
        #expect(!state.wildcards.blenderamaCountered[.one], "your own points are your own business")
        #expect(state.wildcards.blenderamaCountered[.two], "but they take theirs off")

        // And a turn that costs points — buying a blender back — negates nothing.
        var second = BoardBuilder()
        let victim = second.destroyedBlender(.two, home: position(0, 1))
        _ = second.blender(.two, at: position(0, 4), home: position(0, 4))
        _ = second.fruit(.banana, .one, at: position(5, 5))
        _ = second.fruit(.banana, .two, at: position(1, 5))
        let paying = second.state(active: .two, ruleSet: .wildcard)

        let after = try RulesEngine.apply(
            .restoreBlender(pieceID: victim, at: position(0, 1)),
            to: paying
        )
        #expect(after.state.scores.two == -RulesEngine.blenderRestoreCost)
        #expect(!after.state.wildcards.blenderamaCountered[.one], "paying is not scoring")
    }

    @Test("A standard match ignores the shutout rule entirely")
    func standardRulesIgnoreTheShutout() throws {
        let f = deliveryFixture(ruleSet: .standard)
        let result = try RulesEngine.apply(
            f.state.move(f.fruit, to: position(PlayerID.two.deliveryRow, 2)),
            to: f.state
        )
        #expect(result.state.scores.two > 0)
        #expect(!result.state.wildcards.blenderamaCountered[.one], "nothing to counter")
    }

    @Test("A standard match has no Blenderama at all, so the same run just loses")
    func standardRulesHaveNoBlenderama() throws {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        _ = builder.fruit(.banana, .one, at: position(2, 2))
        _ = builder.fruit(.pineapple, .two, at: position(0, 0))
        var state = builder.state(scores: Scores(one: -11, two: 0))
        state.wildcards.ownFruitBlended[.one] = GameSetup.fruitCount - 1

        let result = try RulesEngine.apply(state.move(blender, to: position(2, 2)), to: state)

        #expect(result.state.ruleSet == .standard)
        #expect(result.state.outcome == .win(.two, by: .score))
    }

    @Test("Fruit the opponent blends does not count toward your Blenderama")
    func opponentBlendsDoNotCount() throws {
        var builder = BoardBuilder()
        let theirBlender = builder.blender(.two, at: position(3, 3), home: position(0, 1))
        _ = builder.fruit(.banana, .one, at: position(2, 2))
        _ = builder.fruit(.pineapple, .one, at: position(5, 5))
        _ = builder.fruit(.pineapple, .two, at: position(0, 0))
        var state = builder.state(active: .two, ruleSet: .wildcard)
        state.wildcards.ownFruitBlended[.one] = GameSetup.fruitCount - 1

        let result = try RulesEngine.apply(state.move(theirBlender, to: position(2, 2)), to: state)

        #expect(result.state.status == .inProgress, "they took it, so it was never yours to blend")
        #expect(result.state.wildcards.ownFruitBlended[.one] == GameSetup.fruitCount - 1)
        #expect(result.state.wildcards.ownFruitBlended[.two] == 0)
    }

    /// §4's hard counter, tracked explicitly rather than inferred from the count.
    @Test("One stolen self-blend puts Blenderama permanently out of reach")
    func opponentBlendCountersPermanently() throws {
        var builder = BoardBuilder()
        let theirBlender = builder.blender(.two, at: position(3, 3), home: position(0, 1))
        _ = builder.fruit(.banana, .one, at: position(2, 2))
        _ = builder.fruit(.strawberry, .one, at: position(5, 5))
        _ = builder.fruit(.pineapple, .two, at: position(0, 0))
        let state = builder.state(active: .two, ruleSet: .wildcard)

        let result = try RulesEngine.apply(state.move(theirBlender, to: position(2, 2)), to: state)

        #expect(result.state.wildcards.blenderamaCountered[.one], "they took one of mine")
        #expect(!result.state.wildcards.blenderamaCountered[.two])
    }

    @Test("Even reaching the full count cannot win once countered")
    func counteredBeatsTheCount() throws {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        _ = builder.fruit(.banana, .one, at: position(2, 2))
        _ = builder.fruit(.pineapple, .two, at: position(0, 0))
        var state = builder.state(ruleSet: .wildcard, scores: Scores(one: 20, two: 0))
        state.wildcards.ownFruitBlended[.one] = GameSetup.fruitCount - 1
        state.wildcards.blenderamaCountered[.one] = true

        let result = try RulesEngine.apply(state.move(blender, to: position(2, 2)), to: state)

        #expect(result.state.wildcards.ownFruitBlended[.one] == GameSetup.fruitCount)
        #expect(result.state.outcome?.reason == .score, "countered, so it ends on points instead")
        #expect(result.state.status == .ended(.win(.one, by: .score)))
    }


}
