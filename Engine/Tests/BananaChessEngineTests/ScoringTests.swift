import Testing
@testable import BananaChessEngine

@Suite("Signed blending")
struct BlendingTests {
    /// RU-01
    @Test("Blending the opponent's fruit pays its base value", arguments: [
        (PieceKind.banana, 1), (.strawberry, 2), (.pineapple, 3),
    ])
    func opponentBlendPaysBaseValue(kind: PieceKind, expected: Int) throws {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        let fruit = builder.fruit(kind, .two, at: position(2, 4))
        let state = builder.state()

        let result = try RulesEngine.apply(state.move(blender, to: position(2, 4)), to: state)

        #expect(result.state.scores.one == expected)
        #expect(result.state.scores.two == 0)
        #expect(result.state.piece(with: fruit) == nil)
        #expect(result.state.square(of: blender) == position(2, 4), "the blender stays on the square")
        #expect(result.events.blends.count == 1)
    }

    /// RU-02
    @Test("Blending your own fruit costs you its base value", arguments: [
        (PieceKind.banana, -1), (.strawberry, -2), (.pineapple, -3),
    ])
    func selfBlendCostsBaseValue(kind: PieceKind, expected: Int) throws {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        let fruit = builder.fruit(kind, .one, at: position(2, 4))
        _ = builder.fruit(.banana, .two, at: position(0, 0))
        let state = builder.state()

        let result = try RulesEngine.apply(state.move(blender, to: position(2, 4)), to: state)

        #expect(result.state.scores.one == expected)
        #expect(result.state.scores.two == 0, "the other player gains nothing from it")
        #expect(result.state.piece(with: fruit) == nil)
        #expect(result.state.square(of: blender) == position(2, 4))
    }

    /// RU-03
    @Test("Scores go below zero and are never clamped")
    func scoresMayGoNegative() throws {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        _ = builder.fruit(.strawberry, .one, at: position(2, 4))
        _ = builder.fruit(.banana, .two, at: position(0, 0))
        let state = builder.state(scores: Scores(one: 1, two: 0))

        let result = try RulesEngine.apply(state.move(blender, to: position(2, 4)), to: state)

        #expect(result.state.scores.one == -1)
        #expect(result.events.contains(.scoreChanged(player: .one, newScore: -1)))
    }

    @Test("Blending never uses the delivery multiplier")
    func blendingIgnoresDeliveryMultiplier() throws {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(1, 3), home: position(5, 1))
        // Opponent pineapple sitting on player one's own delivery row.
        _ = builder.fruit(.pineapple, .two, at: position(0, 2))
        _ = builder.fruit(.banana, .two, at: position(3, 0))
        let state = builder.state()

        let result = try RulesEngine.apply(state.move(blender, to: position(0, 2)), to: state)

        #expect(result.state.scores.one == 3, "base value, not six")
    }

    @Test("Blending removes only the fruit it landed on")
    func blendRemovesOnlyTheTarget() throws {
        var builder = BoardBuilder()
        let blender = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        _ = builder.fruit(.banana, .two, at: position(2, 4))
        let bystander = builder.fruit(.pineapple, .two, at: position(2, 2))
        let state = builder.state()

        let result = try RulesEngine.apply(state.move(blender, to: position(2, 4)), to: state)

        #expect(result.state.piece(with: bystander) != nil)
        #expect(result.state.remainingFruit.count == 1)
    }
}

@Suite("Delivery")
struct DeliveryTests {
    @Test("Fruit reaching the far edge scores double and leaves the board", arguments: [
        (PieceKind.banana, 2), (.strawberry, 4), (.pineapple, 6),
    ])
    func deliveryScoresDouble(kind: PieceKind, expected: Int) throws {
        var builder = BoardBuilder()
        let fruit = builder.fruit(kind, .one, at: position(1, 2))
        _ = builder.fruit(.banana, .two, at: position(4, 5))
        let state = builder.state()

        let result = try RulesEngine.apply(state.move(fruit, to: position(0, 2)), to: state)

        #expect(result.state.scores.one == expected)
        #expect(result.state.piece(with: fruit) == nil)
        #expect(result.events.deliveries.count == 1)
    }

    @Test("Delivery works identically from player two's perspective")
    func deliveryFromPlayerTwo() throws {
        var builder = BoardBuilder()
        _ = builder.fruit(.banana, .one, at: position(2, 0))
        let fruit = builder.fruit(.pineapple, .two, at: position(4, 3))
        let state = builder.state(active: .two)

        let result = try RulesEngine.apply(state.move(fruit, to: position(5, 3)), to: state)

        #expect(result.state.scores.two == 6)
        #expect(result.state.scores.one == 0)
        #expect(result.state.piece(with: fruit) == nil)
    }

    @Test("Reaching your own back row is not a delivery")
    func ownBackRowIsNotDelivery() throws {
        var builder = BoardBuilder()
        let fruit = builder.fruit(.pineapple, .one, at: position(4, 3))
        _ = builder.fruit(.banana, .two, at: position(1, 1))
        let state = builder.state()

        let result = try RulesEngine.apply(state.move(fruit, to: position(5, 3)), to: state)

        #expect(result.state.scores == Scores())
        #expect(result.state.square(of: fruit) == position(5, 3))
    }
}

@Suite("Blender combat")
struct BlenderCombatTests {
    private func fixture() -> (state: GameState, mine: PieceID, theirs: PieceID) {
        var builder = BoardBuilder()
        let mine = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        let theirs = builder.blender(.two, at: position(2, 2), home: position(0, 4))
        _ = builder.fruit(.banana, .one, at: position(4, 0))
        _ = builder.fruit(.banana, .two, at: position(1, 5))
        return (builder.state(), mine, theirs)
    }

    /// Home is safe. A blender sitting on one of its owner's spawn squares cannot be
    /// taken by anything, which is what stops a buy-back being knocked straight off
    /// again for another three points.
    /// Player two's spawns are (0,1) and (0,4). The victim is placed wherever the test
    /// needs it, with the attacker one diagonal step away.
    private func spawnFixture(
        attackerAt origin: Position,
        victimAt square: Position,
        victimHome: Position = position(0, 1)
    ) -> (state: GameState, attacker: PieceID, victim: PieceID) {
        var builder = BoardBuilder()
        let attacker = builder.blender(.one, at: origin, home: position(5, 1))
        let victim = builder.blender(.two, at: square, home: victimHome)
        // Their other blender, so both of player two's spawns are real squares.
        _ = builder.blender(.two, at: position(3, 5), home: position(0, 4))
        _ = builder.fruit(.banana, .one, at: position(4, 0))
        _ = builder.fruit(.banana, .two, at: position(1, 5))
        return (builder.state(), attacker, victim)
    }

    @Test("A blender on its own spawn cannot be taken")
    func aBlenderOnItsSpawnIsSafe() throws {
        let f = spawnFixture(attackerAt: position(1, 2), victimAt: position(0, 1))
        let attack = f.state.move(f.attacker, to: position(0, 1))

        #expect(!RulesEngine.legalMoves(for: f.attacker, in: f.state).contains(attack))
        #expect(throws: (any Error).self) { try RulesEngine.apply(attack, to: f.state) }
        #expect(f.state.square(of: f.victim) == position(0, 1), "it just stands there")
    }

    @Test("Either of the owner's spawns is home, not only the one it started on")
    func theOtherSpawnIsAlsoSafe() throws {
        // Its own home is (0,1); it was bought back onto its sibling's spawn instead.
        // That square is still one of player two's, so it is still safe.
        let f = spawnFixture(
            attackerAt: position(1, 3),
            victimAt: position(0, 4),
            victimHome: position(0, 1)
        )
        let attack = f.state.move(f.attacker, to: position(0, 4))

        #expect(!RulesEngine.legalMoves(for: f.attacker, in: f.state).contains(attack))
    }

    @Test("One square off the spawn and it is fair game again")
    func steppingOffTheSpawnEndsTheProtection() throws {
        let f = spawnFixture(attackerAt: position(1, 2), victimAt: position(2, 1))
        let attack = f.state.move(f.attacker, to: position(2, 1))

        #expect(RulesEngine.legalMoves(for: f.attacker, in: f.state).contains(attack))
        let result = try RulesEngine.apply(attack, to: f.state)
        #expect(result.state.isOffBoard(f.victim))
    }

    @Test("The protection covers friendly blenders too — home is simply safe")
    func evenItsOwnSideCannotTakeIt() throws {
        var builder = BoardBuilder()
        let attacker = builder.blender(.two, at: position(1, 2), home: position(0, 4))
        let victim = builder.blender(.two, at: position(0, 1), home: position(0, 1))
        _ = builder.fruit(.banana, .one, at: position(4, 0))
        _ = builder.fruit(.banana, .two, at: position(1, 5))
        let state = builder.state(active: .two)

        #expect(
            !RulesEngine.legalMoves(for: attacker, in: state)
                .contains(state.move(attacker, to: position(0, 1)))
        )
    }

    @Test("A blender that runs into another destroys it and takes the square")
    func attackerDestroysAndAdvances() throws {
        let f = fixture()
        let result = try RulesEngine.apply(f.state.move(f.mine, to: position(2, 2)), to: f.state)

        #expect(result.state.square(of: f.mine) == position(2, 2), "the attacker survives and lands")
        #expect(result.state.isOffBoard(f.theirs), "the defender is destroyed")
        #expect(result.state.scores == Scores(), "combat itself pays nothing")
        #expect(result.events.destructions.count == 1)
    }

    @Test("Destroying a blender wins the square and nothing else")
    func destroyingGrantsNoExtraMove() throws {
        let f = fixture()
        let result = try RulesEngine.apply(f.state.move(f.mine, to: position(2, 2)), to: f.state)

        #expect(result.state.activePlayer == .two, "the turn passes straight over")
        #expect(result.state.square(of: f.mine) == position(2, 2))
        #expect(result.state.isOffBoard(f.theirs))
    }

    @Test("The attacker gets an ordinary turn afterwards, not a double one")
    func noSecondStepNextTurn() throws {
        let f = fixture()
        var current = try RulesEngine.apply(f.state.move(f.mine, to: position(2, 2)), to: f.state).state
        current = try idleTransition(current).state
        #expect(current.activePlayer == .one)

        // One diagonal step, and the turn is over.
        let step = try RulesEngine.apply(current.move(f.mine, to: position(1, 1)), to: current)

        #expect(step.state.activePlayer == .two, "one move, one turn")
        #expect(RulesEngine.legalActions(in: step.state).allSatisfy { action in
            if case .move(let move) = action {
                return step.state.piece(with: move.pieceID)?.owner == .two
            }
            return true
        })
    }

    @Test("A blender may destroy a friendly one")
    func friendlyFireIsLegal() throws {
        var builder = BoardBuilder()
        let mine = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        let friend = builder.blender(.one, at: position(2, 2), home: position(5, 4))
        _ = builder.fruit(.banana, .one, at: position(4, 0))
        _ = builder.fruit(.banana, .two, at: position(1, 5))
        let state = builder.state()

        let result = try RulesEngine.apply(state.move(mine, to: position(2, 2)), to: state)

        #expect(result.state.isOffBoard(friend))
        #expect(result.state.square(of: mine) == position(2, 2))
        #expect(result.state.activePlayer == .two)
    }

    @Test("Blenders do not come back on their own")
    func noAutomaticRespawn() throws {
        let f = fixture()
        var current = try RulesEngine.apply(f.state.move(f.mine, to: position(2, 2)), to: f.state).state

        for _ in 0..<8 {
            guard !current.status.isOver else { break }
            current = try idleTransition(current).state
            #expect(current.isOffBoard(f.theirs), "it stays destroyed until bought back")
        }
    }
}

@Suite("Buying a blender back")
struct BlenderRestoreTests {
    private func fixture() -> (state: GameState, destroyed: PieceID) {
        var builder = BoardBuilder()
        let destroyed = builder.destroyedBlender(.one, home: position(5, 1))
        _ = builder.blender(.two, at: position(0, 4), home: position(0, 4))
        _ = builder.fruit(.banana, .one, at: position(4, 0))
        _ = builder.fruit(.banana, .two, at: position(1, 5))
        return (builder.state(scores: Scores(one: 5, two: 0)), destroyed)
    }

    @Test("It costs the whole turn and three points")
    func costsATurnAndThreePoints() throws {
        let f = fixture()
        #expect(RulesEngine.legalActions(in: f.state).contains(.restoreBlender(pieceID: f.destroyed, at: position(5, 1))))

        let result = try RulesEngine.apply(.restoreBlender(pieceID: f.destroyed, at: position(5, 1)), to: f.state)

        #expect(result.state.square(of: f.destroyed) == position(5, 1))
        #expect(result.state.scores.one == 5 - RulesEngine.blenderRestoreCost)
        #expect(result.state.activePlayer == .two, "it used the whole turn")
        #expect(result.events.restorations.count == 1)
    }

    @Test("The score is allowed to go negative paying for it")
    func scoreMayGoNegative() throws {
        var builder = BoardBuilder()
        let destroyed = builder.destroyedBlender(.one, home: position(5, 1))
        _ = builder.blender(.two, at: position(0, 4), home: position(0, 4))
        _ = builder.fruit(.banana, .one, at: position(4, 0))
        _ = builder.fruit(.banana, .two, at: position(1, 5))
        let state = builder.state(scores: Scores(one: 1, two: 0))

        let result = try RulesEngine.apply(.restoreBlender(pieceID: destroyed, at: position(5, 1)), to: state)

        #expect(result.state.scores.one == -2)
    }

    @Test("A blender standing on the board cannot be bought")
    func onlyDestroyedBlenders() throws {
        var builder = BoardBuilder()
        let standing = builder.blender(.one, at: position(5, 1), home: position(5, 1))
        _ = builder.fruit(.banana, .one, at: position(4, 0))
        _ = builder.fruit(.banana, .two, at: position(1, 5))
        let state = builder.state()

        #expect(throws: RulesError.blenderNotDestroyed(standing)) {
            try RulesEngine.apply(.restoreBlender(pieceID: standing, at: position(5, 1)), to: state)
        }
    }

    @Test("It cannot be bought back onto an occupied spawn")
    func spawnMustBeClear() throws {
        var builder = BoardBuilder()
        let destroyed = builder.destroyedBlender(.one, home: position(5, 1))
        _ = builder.fruit(.banana, .one, at: position(5, 1))
        _ = builder.fruit(.banana, .two, at: position(1, 5))
        let state = builder.state()

        #expect(throws: RulesError.spawnOccupied(destroyed)) {
            try RulesEngine.apply(.restoreBlender(pieceID: destroyed, at: position(5, 1)), to: state)
        }
        #expect(!RulesEngine.legalActions(in: state).contains(.restoreBlender(pieceID: destroyed, at: position(5, 1))))
    }

    @Test("A blender may come back on either of its owner's spawns")
    func eitherSpawnWillDo() throws {
        var builder = BoardBuilder()
        let destroyed = builder.destroyedBlender(.one, home: position(5, 1))
        _ = builder.blender(.one, at: position(3, 3), home: position(5, 4))
        _ = builder.blender(.two, at: position(0, 4), home: position(0, 4))
        _ = builder.fruit(.banana, .one, at: position(4, 0))
        _ = builder.fruit(.banana, .two, at: position(1, 5))
        let state = builder.state()

        let offered = RulesEngine.legalActions(in: state).compactMap { action -> Position? in
            if case .restoreBlender(let id, let square) = action, id == destroyed { return square }
            return nil
        }
        #expect(Set(offered) == [position(5, 1), position(5, 4)], "both spawns are on offer")

        let elsewhere = try RulesEngine.apply(
            .restoreBlender(pieceID: destroyed, at: position(5, 4)),
            to: state
        )
        #expect(elsewhere.state.square(of: destroyed) == position(5, 4))
        #expect(elsewhere.state.scores.one == -RulesEngine.blenderRestoreCost)
    }

    @Test("Coming back on the other spawn switches the colour it travels on")
    func otherSpawnSwitchesDiagonal() throws {
        var builder = BoardBuilder()
        let destroyed = builder.destroyedBlender(.one, home: position(5, 1))
        _ = builder.blender(.two, at: position(0, 4), home: position(0, 4))
        _ = builder.fruit(.banana, .one, at: position(4, 0))
        _ = builder.fruit(.banana, .two, at: position(1, 5))
        // Its own spawn and the other one sit on opposite colours by design.
        _ = builder.blender(.one, at: position(3, 3), home: position(5, 4))
        let state = builder.state()
        #expect(position(5, 1).isDarkSquare != position(5, 4).isDarkSquare)

        let back = try RulesEngine.apply(
            .restoreBlender(pieceID: destroyed, at: position(5, 4)),
            to: state
        ).state

        // It now moves on the other colour — the only way a blender ever changes
        // diagonal, and the reason a player's own two blenders can now meet.
        let reachable = RulesEngine.legalMoves(for: destroyed, in: back).map(\.destination)
        #expect(reachable.allSatisfy { $0.isDarkSquare == position(5, 4).isDarkSquare })
        #expect(!reachable.contains { $0.isDarkSquare == position(5, 1).isDarkSquare })
    }

    @Test("A blender cannot come back on a square that is not a spawn")
    func onlySpawnSquares() throws {
        var builder = BoardBuilder()
        let destroyed = builder.destroyedBlender(.one, home: position(5, 1))
        _ = builder.blender(.two, at: position(0, 4), home: position(0, 4))
        _ = builder.fruit(.banana, .one, at: position(4, 0))
        _ = builder.fruit(.banana, .two, at: position(1, 5))
        let state = builder.state()

        #expect(throws: RulesError.notASpawnSquare(position(3, 3))) {
            try RulesEngine.apply(.restoreBlender(pieceID: destroyed, at: position(3, 3)), to: state)
        }
    }

    @Test("Only your own blenders")
    func notTheirs() throws {
        var builder = BoardBuilder()
        let theirs = builder.destroyedBlender(.two, home: position(0, 4))
        _ = builder.blender(.one, at: position(5, 1), home: position(5, 1))
        _ = builder.fruit(.banana, .one, at: position(4, 0))
        _ = builder.fruit(.banana, .two, at: position(1, 5))
        let state = builder.state()

        #expect(throws: RulesError.notActivePlayersPiece(theirs)) {
            try RulesEngine.apply(.restoreBlender(pieceID: theirs, at: position(0, 4)), to: state)
        }
    }
}

@Suite("Blender battles")
struct BlenderBattleTests {
    /// Two enemy blenders a diagonal apart, with each side's fruit set by the test so the
    /// standing totals are whatever it needs them to be.
    private func fixture(
        mine: [PieceKind],
        theirs: [PieceKind],
        battles: Bool = true
    ) -> (state: GameState, attacker: PieceID, defender: PieceID) {
        var builder = BoardBuilder()
        let attacker = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        let defender = builder.blender(.two, at: position(2, 2), home: position(0, 4))
        for (index, kind) in mine.enumerated() {
            _ = builder.fruit(kind, .one, at: position(5, index))
        }
        for (index, kind) in theirs.enumerated() {
            _ = builder.fruit(kind, .two, at: position(0, index))
        }
        var state = builder.state()
        state.blenderBattles = battles
        return (state, attacker, defender)
    }

    @Test("With battles off, a blender still simply takes the square")
    func battlesOffIsTheOldRule() throws {
        let f = fixture(mine: [.banana], theirs: [.pineapple, .pineapple], battles: false)
        let result = try RulesEngine.apply(f.state.move(f.attacker, to: position(2, 2)), to: f.state)

        #expect(result.state.isOffBoard(f.defender), "the defender is gone regardless of totals")
        #expect(result.state.square(of: f.attacker) == position(2, 2))
        #expect(!result.events.contains { if case .blenderBattle = $0 { true } else { false } })
    }

    @Test("An overwhelming attacker wins and takes the square")
    func aStrongAttackerWins() throws {
        // Twelve of fruit against one. Halved that is 6 against 0, and with the bonus the
        // attacker's worst roll still beats the defender's best.
        let f = fixture(
            mine: [.pineapple, .pineapple, .pineapple, .pineapple],
            theirs: [.banana]
        )
        let result = try RulesEngine.apply(f.state.move(f.attacker, to: position(2, 2)), to: f.state)

        let report = try #require(result.events.battleReports.first)
        #expect(report.attacker == .one)
        #expect(report.attackerFruit == 6, "twelve of standing fruit, counted at half")
        #expect(report.attackerBonus == RulesEngine.battleAttackerBonus)
        #expect(report.defenderFruit == 0, "one point of fruit rounds down to nothing")
        #expect(report.attackerWins)
        #expect(result.state.isOffBoard(f.defender))
        #expect(result.state.square(of: f.attacker) == position(2, 2), "and moves in")
    }

    @Test("An overwhelmed attacker loses its own blender, and the defender does not move")
    func aWeakAttackerLosesItsOwn() throws {
        // 0 plus 1 against 6: the attacker's best roll ties the defender's worst, and a
        // tie goes to the defender, so the attack cannot succeed on any roll.
        let f = fixture(
            mine: [.banana],
            theirs: [.pineapple, .pineapple, .pineapple, .pineapple]
        )
        let result = try RulesEngine.apply(f.state.move(f.attacker, to: position(2, 2)), to: f.state)

        let report = try #require(result.events.battleReports.first)
        #expect(!report.attackerWins)
        #expect(report.winner == .two)
        #expect(result.state.isOffBoard(f.attacker), "the attacker is the one that leaves")
        #expect(result.state.square(of: f.defender) == position(2, 2), "the defender holds its square")
        #expect(result.state.destroyedBlenders(ownedBy: .one).count == 1)
        #expect(result.state.destroyedBlenders(ownedBy: .two).isEmpty)
    }

    @Test("Only fruit still standing counts — blended and delivered fruit does not")
    func onlyStandingFruitCounts() throws {
        var builder = BoardBuilder()
        let attacker = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        let defender = builder.blender(.two, at: position(2, 2), home: position(0, 4))
        _ = builder.fruit(.pineapple, .one, at: position(5, 0))
        _ = builder.fruit(.banana, .two, at: position(0, 0))
        // A wildcard banana of theirs, delivered and waiting off-board to come back: it
        // is still their fruit, but it is not standing on anything.
        let waiting = builder.fruit(.pineapple, .two, at: position(0, 3))
        var state = builder.state(ruleSet: .wildcard)
        state.blenderBattles = true
        state.setPosition(nil, for: waiting)

        #expect(state.fruit(ownedBy: .two).count == 2, "both still theirs")
        #expect(state.fruitStandingValue(for: .two) == 1, "but only the banana is standing")
        #expect(state.fruitStandingValue(for: .one) == 3)

        let result = try RulesEngine.apply(state.move(attacker, to: position(2, 2)), to: state)
        let report = try #require(result.events.battleReports.first)
        #expect(report.attackerFruit == 1, "3 standing, counted at half")
        #expect(report.defenderFruit == 0, "1 standing, counted at half")
        // Close enough that the dice decide it, which is the point of halving — so this
        // asserts the right blender left rather than which one it was.
        #expect(result.state.isOffBoard(report.attackerWins ? defender : attacker))
    }

    @Test("A tie goes to the defender")
    func tiesGoToTheDefender() {
        let tied = BattleReport(
            attacker: .one,
            attackerFruit: 3, attackerBonus: 1, attackerRoll: 2,
            defenderFruit: 5, defenderRoll: 1
        )
        #expect(tied.attackerTotal == tied.defenderTotal)
        #expect(!tied.attackerWins, "the attacker has to actually beat the total")
        #expect(tied.winner == .two)
    }

    /// The bonus and the tie rule are one dial at half-step resolution, not two: a bonus
    /// of B with ties to the attacker is the identical rule to B+1 with ties to the
    /// defender. Pinned so that anyone retuning one remembers they are moving both.
    @Test("The bonus and the tie rule are the same dial")
    func bonusAndTieRuleAreOneDial() {
        for gap in -3...3 {
            for attackerRoll in 1...6 {
                for defenderRoll in 1...6 {
                    let report = BattleReport(
                        attacker: .one,
                        attackerFruit: gap, attackerBonus: 1, attackerRoll: attackerRoll,
                        defenderFruit: 0, defenderRoll: defenderRoll
                    )
                    #expect(
                        report.attackerWins == (gap + attackerRoll >= defenderRoll),
                        "bonus 1 with ties to the defender should equal bonus 0 with ties to the attacker"
                    )
                }
            }
        }
    }

    @Test("Running into your own blender is not a battle — there is nobody to roll against")
    func friendlyCollisionsAreNotBattles() throws {
        var builder = BoardBuilder()
        let attacker = builder.blender(.one, at: position(3, 3), home: position(5, 1))
        let friend = builder.blender(.one, at: position(2, 2), home: position(5, 4))
        _ = builder.fruit(.pineapple, .two, at: position(0, 0))
        _ = builder.fruit(.banana, .one, at: position(5, 0))
        var state = builder.state()
        state.blenderBattles = true

        let result = try RulesEngine.apply(state.move(attacker, to: position(2, 2)), to: state)

        #expect(!result.events.contains { if case .blenderBattle = $0 { true } else { false } })
        #expect(result.state.isOffBoard(friend))
        #expect(result.state.square(of: attacker) == position(2, 2))
    }

    /// Both dice come from the position, so the same move on two devices settles the same
    /// way. Without this an online match would fork the moment a battle happened.
    @Test("The same battle from the same position always goes the same way")
    func battlesAreDeterministic() throws {
        let f = fixture(mine: [.strawberry, .banana], theirs: [.strawberry, .banana])
        let move = f.state.move(f.attacker, to: position(2, 2))

        let first = try RulesEngine.apply(move, to: f.state)
        let second = try RulesEngine.apply(move, to: f.state)

        #expect(first.events == second.events)
        #expect(first.state == second.state)
    }

    /// Evenly matched sides, so the dice decide. Sampled over many distinct positions to
    /// check the rolls are actually varying rather than stuck.
    @Test("The dice are dice: even sides do not always go the same way")
    func evenBattlesAreDecidedByTheDice() throws {
        var attackerWins = 0
        var rolls: Set<Int> = []
        var sampled = 0

        for row in 1..<Board.size - 1 {
            for column in 1..<Board.size - 1 {
                var builder = BoardBuilder()
                let attacker = builder.blender(.one, at: position(row, column), home: position(5, 1))
                _ = builder.blender(.two, at: position(row - 1, column - 1), home: position(0, 4))
                // Level on standing fruit, so only the attacker's +2 and the dice differ.
                _ = builder.fruit(.strawberry, .one, at: position(5, 5))
                _ = builder.fruit(.strawberry, .two, at: position(0, 0))
                var state = builder.state()
                state.blenderBattles = true

                let result = try RulesEngine.apply(
                    state.move(attacker, to: position(row - 1, column - 1)), to: state
                )
                let report = try #require(result.events.battleReports.first)
                rolls.insert(report.attackerRoll)
                rolls.insert(report.defenderRoll)
                if report.attackerWins { attackerWins += 1 }
                sampled += 1
            }
        }

        #expect(rolls.allSatisfy { (1...6).contains($0) }, "a die shows one to six")
        #expect(rolls.count >= 5, "the rolls should vary across positions, saw \(rolls.sorted())")
        #expect(attackerWins > 0, "the attacker should win some")
        #expect(attackerWins < sampled, "and lose some, even holding the +2")
    }

    @Test("The CPU cannot see the dice before it decides")
    func theSearchDoesNotPeekAtTheDice() throws {
        let f = fixture(mine: [.strawberry], theirs: [.strawberry])
        let move = f.state.move(f.attacker, to: position(2, 2))

        let searched = try RulesEngine.apply(move, to: f.state, battles: .expected)
        let searchReport = try #require(searched.events.battleReports.first)

        #expect(searchReport.attackerRoll == searchReport.defenderRoll, "no dice in the search")
        // Level fruit plus the attacker's bonus, so the search expects the attack to work.
        #expect(searchReport.attackerWins)
    }
}

extension Array where Element == GameEvent {
    var battleReports: [BattleReport] {
        compactMap { if case .blenderBattle(let report, _) = $0 { report } else { nil } }
    }
}
