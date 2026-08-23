/// Every number the CPU cares about, in one place. These are playtest dials, not rules —
/// changing them must never change which moves are *legal*.
public struct EvaluationWeights: Sendable, Hashable {
    /// Points already banked are the only certain currency.
    public var score: Int = 100
    /// A fruit still on the board is worth something to whoever can resolve it.
    public var fruitOnBoard: Int = 18
    /// Per row of progress toward the delivery edge, scaled by the fruit's value.
    public var advancement: Int = 6
    /// Own fruit standing on a square an enemy blender can reach next move.
    public var threatenedFruit: Int = -14
    /// Enemy fruit this player's blenders can reach next move.
    public var blenderThreat: Int = 12
    /// A blender sitting on its own spawn with an enemy blender covering that square.
    ///
    /// Spawn squares became safe from capture in rules 12, which does *not* retire this:
    /// the blender is now entombed rather than taken. It cannot be touched while it
    /// stays, and it dies the moment it leaves, so buying one back onto a camped spawn is
    /// still three points for a piece that can never do anything. Removing this term put
    /// the CPU straight back into the old loop one square over — rebuy, step off, get
    /// taken, rebuy — measured at −424 points before the self-play test caught it.
    ///
    /// Deliberately narrow: penalising *every* threatened blender made it too timid to
    /// attack at all, because the approach costs a turn before the reward arrives and a
    /// shallow search never sees that far.
    public var blenderOnCoveredSpawn: Int = -320
    /// Squares a blender can legally move to — a cornered blender is a dead piece.
    public var blenderMobility: Int = 3
    /// Own fruit parked on one of the player's own blender spawns. Under signed
    /// blending this is now a liability, not a setup: the blender comes back and eats
    /// it at a loss.
    public var fruitOnOwnBlenderHome: Int = -8
    /// A blender destroyed in combat. Priced deliberately above the three points it
    /// costs to restore one: a blender is a scoring engine that can take a pineapple
    /// every few turns, so valuing it below its own replacement cost would mean never
    /// buying one back — which is exactly what the CPU did at -70.
    ///
    /// But only *just* above, and the Blendy board is what proved it. At -450 a rebuy
    /// was worth +150 against its own cost, so when two blenders had nothing better to
    /// do they traded forever and both sides paid for it: 4,218 buy-backs across sixteen
    /// Blendy matches, none of which ever ended, with both players past -290 and not one
    /// fruit blended. Blendy exposes it because its blenders start facing an empty middle
    /// with no fruit in reach; standard play hid it behind a fruit line.
    ///
    /// Swept -450 → -150. At -250 every board finishes, buy-backs stay a real part of
    /// play (28 across sixteen standard matches, against 0 at -150), and the difficulty
    /// ladder holds at 10/10.
    public var blenderDestroyed: Int = -250

    /// Per fruit still standing on the emptier side. Fruit is the clock now: the match
    /// ends when either player runs out, so a player who is ahead wants it short and a
    /// player behind wants it long. Signed by who is winning.
    public var clockPressure: Int = 7

    // Wildcard rules.
    /// How much an outright win is worth when it is one move away, divided by the square
    /// of how many moves away it actually is. An alternate victory is not worth "some
    /// points" — it is worth the match — so the closer it gets the more it has to
    /// dominate everything else on the board. At one move away this is 400 points, which
    /// no delivery or blend can outbid; at six moves away it is 11, which is background
    /// noise. This is what lets the CPU spend a tempo getting into position to counter
    /// rather than only ever countering when the counter happens to also be greedy.
    ///
    /// Held down to 4,000 by measurement, not taste. At 40,000 the smoothie bonus made a
    /// Blenderama run cheap enough that both Medium CPUs simply raced for one, neither
    /// ever scored, so neither run was ever negated, and ten of twenty-four matches ended
    /// on turn 31 with the winner on −16. Exploitable as well as dull: score once and the
    /// CPU has thrown away its whole board. At 4,000 alternate wins still happen — five
    /// Banana Splits in twenty-four — without crowding out ordinary play. At 0 they never
    /// happen at all, which wastes the wildcard rules.
    public var alternateWinThreat: Int = 4_000
    public var smoothieProgress: Int = 22
    public var goldenPineapple: Int = 30

    public init() {}
}

enum Evaluation {
    static let winScore = 1_000_000

    /// Positive is good for `player`. Symmetric by construction: evaluating for the
    /// opponent returns the negation, which is what makes minimax sound here.
    static func score(
        _ state: GameState,
        for player: PlayerID,
        weights: EvaluationWeights
    ) -> Int {
        if case .ended(let outcome) = state.status {
            switch outcome {
            case .win(let winner, _):
                let margin = abs(state.scores.margin(for: player))
                return winner == player ? winScore + margin : -winScore - margin
            case .draw:
                return 0
            }
        }

        var total = state.scores.margin(for: player) * weights.score
        total += positional(state, for: player, weights: weights)
        total -= positional(state, for: player.opponent, weights: weights)

        // How many moves of match are left, roughly: whichever side is nearer to empty.
        let clock = min(
            state.fruit(ownedBy: player).count,
            state.fruit(ownedBy: player.opponent).count
        )
        let margin = state.scores.margin(for: player)
        if margin != 0 {
            total += (margin > 0 ? -clock : clock) * weights.clockPressure
        }
        if state.ruleSet.usesWildcards {
            total += wildcards(state, for: player, weights: weights)
            total -= wildcards(state, for: player.opponent, weights: weights)
        }
        return total
    }

    /// Progress toward an outright win, priced by how close it is rather than by how
    /// much of it is done. Both alternate victories are all-or-nothing, so what matters
    /// is the number of moves still needed — and the last one is worth vastly more than
    /// the first.
    private static func wildcards(
        _ state: GameState,
        for player: PlayerID,
        weights: EvaluationWeights
    ) -> Int {
        var total = 0

        if let steps = movesToBananaSplit(state, for: player) {
            total += threatValue(stepsAway: steps, weights: weights)
        }
        if let steps = movesToBlenderama(state, for: player) {
            total += threatValue(stepsAway: steps, weights: weights)
        }

        total += counterReadiness(state, for: player, weights: weights)
        total += state.wildcards.smoothie[player].collectedCount * weights.smoothieProgress
        if state.goldenPineapple(ownedBy: player) != nil {
            total += weights.goldenPineapple
        }
        return total
    }

    /// Credit for being *able* to answer the opponent's alternate win.
    ///
    /// A threat on its own is worth the same after every move that does not remove it,
    /// so it cancels out of the comparison and tells the CPU nothing about what to do.
    /// This is the half that moves it: closing on the fruit whose blending would kill
    /// the threat is worth a slice of the threat itself, and the slice grows as the
    /// threat closes. Without it the CPU only ever counters when the counter also
    /// happens to be the greediest move on the board.
    private static func counterReadiness(
        _ state: GameState,
        for player: PlayerID,
        weights: EvaluationWeights
    ) -> Int {
        let attacker = player.opponent
        var total = 0

        // Blenderama dies if this player blends any one of the attacker's fruit.
        if let steps = movesToBlenderama(state, for: attacker) {
            total += readiness(
                against: threatValue(stepsAway: steps, weights: weights),
                targets: state.fruit(ownedBy: attacker).compactMap(\.position),
                for: player,
                in: state
            )
        }

        // A Banana Split dies if this player blends the attacker's last banana.
        if let steps = movesToBananaSplit(state, for: attacker) {
            total += readiness(
                against: threatValue(stepsAway: steps, weights: weights),
                targets: state.fruit(ownedBy: attacker)
                    .filter { $0.kind == .banana }
                    .compactMap(\.position),
                for: player,
                in: state
            )
        }
        return total
    }

    private static func readiness(
        against threat: Int,
        targets: [Position],
        for player: PlayerID,
        in state: GameState
    ) -> Int {
        guard !targets.isEmpty else { return 0 }
        let distance = shortestBlenderApproach(of: player, to: targets, in: state) ?? Board.size
        let closeness = Board.size - min(distance, Board.size)
        return threat * closeness / Board.size
    }

    private static func threatValue(stepsAway: Int, weights: EvaluationWeights) -> Int {
        let steps = max(1, stepsAway)
        return weights.alternateWinThreat / (steps * steps)
    }

    /// An optimistic count of the moves still needed to blend every one of this player's
    /// own fruit: one move per remaining fruit at the very least, plus the distance a
    /// blender still has to travel to reach the nearest of them. Nil once the opponent
    /// has countered it, or once a fruit has left by any route but a self-blend.
    private static func movesToBlenderama(_ state: GameState, for player: PlayerID) -> Int? {
        guard !state.wildcards.blenderamaCountered[player] else { return nil }
        let blended = state.wildcards.ownFruitBlended[player]
        let remaining = state.fruit(ownedBy: player)
        guard blended > 0 || remaining.count == GameSetup.fruitCount else { return nil }
        guard blended + remaining.count >= GameSetup.fruitCount else { return nil }
        guard !remaining.isEmpty else { return nil }

        let approach = shortestBlenderApproach(
            of: player,
            to: remaining.compactMap(\.position),
            in: state
        )
        return remaining.count + (approach ?? Board.size)
    }

    /// Likewise for a Banana Split: one delivery is worth a whole trip across the board,
    /// so count the trips still needed and add how far the nearest banana has to go.
    private static func movesToBananaSplit(_ state: GameState, for player: PlayerID) -> Int? {
        let delivered = state.wildcards.bananasDelivered[player]
        let remaining = GameSetup.bananaDeliveriesToWin - delivered
        guard remaining > 0 else { return nil }
        let bananas = state.fruit(ownedBy: player).filter { $0.kind == .banana }
        guard !bananas.isEmpty else { return nil }

        let nearest = bananas
            .map { banana in
                banana.position.map {
                    Board.separation($0.row, player.deliveryRow, joined: state.boardWrap.wrapsRows)
                } ?? Board.size - 1
            }
            .min() ?? Board.size - 1
        return (remaining - 1) * (Board.size - 1) + nearest
    }

    /// Diagonal distance from this player's nearest on-board blender to the nearest of
    /// the given squares. Nil when they have no blender standing.
    private static func shortestBlenderApproach(
        of player: PlayerID,
        to squares: [Position],
        in state: GameState
    ) -> Int? {
        let blenders = state.activeBlenders(ownedBy: player).compactMap(\.position)
        guard !blenders.isEmpty, !squares.isEmpty else { return nil }
        return blenders
            .flatMap { from in
                squares.map { Board.blenderSteps(from: from, to: $0) }
            }
            .min()
    }

    private static func positional(
        _ state: GameState,
        for player: PlayerID,
        weights: EvaluationWeights
    ) -> Int {
        var total = 0
        let occupancy = Occupancy(state)
        let enemyBlenderReach = blenderReach(of: player.opponent, in: state)
        let ownHomes = Set(state.blenders(ownedBy: player).compactMap(\.home))
        let destroyedSpawns = Set(
            state.blenders(ownedBy: player).filter(\.isDestroyed).compactMap(\.home)
        )

        for fruit in state.fruit(ownedBy: player) {
            total += fruit.kind.baseValue * weights.fruitOnBoard
            guard let square = fruit.position else { continue }

            let distance = Board.separation(
                square.row, player.deliveryRow, joined: state.boardWrap.wrapsRows
            )
            let progress = (Board.size - 1) - distance
            total += progress * fruit.kind.baseValue * weights.advancement

            if enemyBlenderReach.contains(square) {
                total += fruit.kind.baseValue * weights.threatenedFruit
            }
            if ownHomes.contains(square), !destroyedSpawns.contains(square) {
                total += weights.fruitOnOwnBlenderHome
            }
        }

        for blender in state.blenders(ownedBy: player) {
            guard let position = blender.position else {
                // A smoothie victim is coming back for free; a combat loss only returns
                // if its owner spends a turn and three points on it.
                total += blender.needsBuyingBack ? weights.blenderDestroyed : weights.blenderDestroyed / 4
                continue
            }
            // Camped on its own spawn: safe where it stands, dead the moment it steps
            // off. `blenderReach` is raw geometry, so it still flags this correctly even
            // though the square itself can no longer be captured.
            if RulesEngine.isOnASpawn(blender, in: state), enemyBlenderReach.contains(position) {
                total += weights.blenderOnCoveredSpawn
            }

            var mobility = 0
            for step in Direction.diagonal {
                guard let square = Board.step(
                    from: position, row: step.row, column: step.column, wrap: .flat
                ) else { continue }
                guard let occupantID = occupancy[square], let occupant = state.piece(with: occupantID) else {
                    mobility += 1
                    continue
                }
                if occupant.isFruit && occupant.owner != player {
                    mobility += 1
                    total += occupant.kind.baseValue * weights.blenderThreat
                } else if occupant.isBlender {
                    mobility += 1
                }
                // Landing on your own fruit is legal but costs points, so it does not
                // count as somewhere this blender usefully wants to go.
            }
            total += mobility * weights.blenderMobility
        }

        return total
    }

    /// Squares the player's blenders could land on next move, used for both threat
    /// terms. A knocked-out blender reaches nothing.
    private static func blenderReach(of player: PlayerID, in state: GameState) -> Set<Position> {
        var reach: Set<Position> = []
        for blender in state.activeBlenders(ownedBy: player) {
            guard let position = blender.position else { continue }
            for step in Direction.diagonal {
                if let square = Board.step(
                    from: position, row: step.row, column: step.column, wrap: .flat
                ) {
                    reach.insert(square)
                }
            }
        }
        return reach
    }
}
