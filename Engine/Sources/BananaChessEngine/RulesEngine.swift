import Foundation

public enum RulesError: Error, Hashable, LocalizedError {
    case gameOver
    case unknownPiece(PieceID)
    case notActivePlayersPiece(PieceID)
    case pieceIsOffBoard(PieceID)
    case originMismatch(expected: Position, given: Position)
    case illegalDestination(Position)
    case blenderNotDestroyed(PieceID)
    case blenderReturningOnItsOwn(PieceID)
    case notASpawnSquare(Position)
    case spawnOccupied(PieceID)
    case wouldRepeatPosition

    public var errorDescription: String? {
        switch self {
        case .gameOver: "The match has already finished."
        case .unknownPiece: "That piece is no longer on the board."
        case .notActivePlayersPiece: "That piece belongs to the other player."
        case .pieceIsOffBoard: "That blender is still waiting to come back."
        case .originMismatch: "That piece has moved since the move was created."
        case .illegalDestination: "That square is not a legal destination."
        case .blenderNotDestroyed: "That blender is already on the board."
        case .blenderReturningOnItsOwn: "That blender is coming back on its own."
        case .notASpawnSquare: "A blender can only come back on one of your spawns."
        case .spawnOccupied: "Something is standing on that blender's spawn."
        case .wouldRepeatPosition:
            "That would put the board back where it has already been twice."
        }
    }
}

/// The single deterministic transition path shared by local play, the CPU, and the
/// network. Stateless by design: every function is a pure function of the state given.
public enum RulesEngine {
    /// Bumped whenever a rules change would make two clients disagree. Encoded into
    /// every online payload.
    ///
    /// 2 — signed self-blending and collision knockout, replacing reset-only
    ///     self-blending and instant blender resets.
    /// 3 — optional wildcard rules: Golden Pineapple, Banana Split, Tropical Smoothie,
    ///     Blenderama. Standard play is unchanged, but a match now states which rule
    ///     set it is being played under.
    /// 4 — corrected opening: two of each fruit rather than three bananas and one
    ///     pineapple, and Banana Split requires the opponent to have delivered none.
    /// 5 — the match ends when either player runs out of fruit, not when the board is
    ///     empty, and Blenderama's hard counter is tracked explicitly.
    /// 6 — blender combat: the attacker survives and earns a Mega turn, losses are
    ///     permanent and bought back for three points, and bananas recycle.
    /// 7 — a Tropical Smoothie costs its victims one turn rather than the whole match.
    /// 8 — the Mega Blender is gone: destroying a blender wins the square and nothing
    ///     more.
    /// 9 — a destroyed blender may be bought back onto either of its owner's spawns.
    /// 10 — a Tropical Smoothie needs at least one of the opponent's fruit in it.
    /// 11 — Blenderama is a shutout: any scoring turn by the opponent takes it off.
    /// 12 — a blender standing on one of its owner's spawns cannot be taken.
    /// 13 — a Tropical Smoothie pays three points instead of blowing up blenders, and
    ///      takes any fruit, whoever grew it.
    /// 14 — boards may join their edges, and blenders may never cross one.
    /// 15 — a player's last pineapple is golden however the other one left.
    /// 16 — optional blender battles: a collision is fought over, not simply won.
    /// 17 — battles count fruit at half value, the attacker's bonus is one, and a tie
    ///      goes to the defender.
    /// 18 — a move that would stand the board in the same arrangement a third time is
    ///      not available.
    public static let rulesVersion = 18

    /// Points it costs to put a destroyed blender back on its spawn, which also takes
    /// the whole turn. Scores may go negative paying it — there is no affordability
    /// check, deliberately.
    public static let blenderRestoreCost = 3

    /// How many times one arrangement may stand on the board. A move that would make an
    /// arrangement happen for the third time is simply not available to that player.
    ///
    /// This is what finally bounds a match. Before it, a pair of blenders with nothing
    /// better to do could trade squares forever, and self-play measured matches that were
    /// still going after a thousand turns; the previous fixes were all to the CPU's
    /// judgement, which left a human free to do it to another human.
    public static let repetitionLimit = 3

    /// What a blender adds to its side of a battle for being the one going in.
    ///
    /// One, not two, and paired with ties going to the defender. Measured over 230 real
    /// battles: at two with ties to the attacker the attacker won 89% and a third of
    /// battles were settled before anyone rolled. At one with ties to the defender, and
    /// fruit counting half, the attacker wins 68% and every battle turns on the dice.
    public static let battleAttackerBonus = 1

    /// What completing a Tropical Smoothie pays its maker, once per match.
    public static let smoothieAward = 3

    /// How long the opponent went without blenders when a smoothie blew them up.
    ///
    /// **Retained, unused.** Smoothies pay points now. This and `pourSmoothie` are kept
    /// intact and tested so the blowout can be switched back on — see `RULES_ADDENDUM.md`.
    public static let smoothieBlackoutTurns = 2

    // MARK: - Legal moves

    /// Whether this action would put the board into an arrangement that has already
    /// stood twice — the third occurrence being the one the rule forbids.
    ///
    /// Answered by playing the action out, because a turn does more than move one piece:
    /// bananas come home, blenders come back, and the turn changes hands, all of which
    /// are part of the arrangement. The simulation runs with the rule switched off, which
    /// is what stops this from asking itself the same question forever.
    static func wouldRepeat(_ action: GameAction, in state: GameState) -> Bool {
        guard let next = try? apply(action, to: state, enforcingRepetition: false) else {
            return false
        }
        return state.timesSeen(next.state.boardKey) >= repetitionLimit - 1
    }

    public static func legalMoves(in state: GameState) -> [Move] {
        legalMoves(for: state.activePlayer, in: state)
    }

    public static func legalMoves(
        for player: PlayerID,
        in state: GameState,
        enforcingRepetition: Bool = true
    ) -> [Move] {
        guard !state.status.isOver else { return [] }
        let occupancy = Occupancy(state)
        return state.pieces(ownedBy: player)
            .flatMap { legalMoves(for: $0, in: state, occupancy: occupancy) }
            .filter { !enforcingRepetition || !wouldRepeat(.move($0), in: state) }
            .sorted()
    }

    public static func legalMoves(
        for pieceID: PieceID,
        in state: GameState,
        enforcingRepetition: Bool = true
    ) -> [Move] {
        guard !state.status.isOver, let piece = state.piece(with: pieceID) else { return [] }
        return legalMoves(for: piece, in: state, occupancy: Occupancy(state))
            .filter { !enforcingRepetition || !wouldRepeat(.move($0), in: state) }
            .sorted()
    }

    static func legalMoves(for piece: Piece, in state: GameState, occupancy: Occupancy) -> [Move] {
        // A blender waiting to respawn has no moves; it is not on the board.
        guard let origin = piece.position else { return [] }
        let steps = piece.isBlender ? Direction.diagonal : Direction.orthogonal
        var moves: [Move] = []
        moves.reserveCapacity(4)

        for step in steps {
            guard let destination = Board.step(
                from: origin,
                row: step.row,
                column: step.column,
                wrap: state.edges(for: piece)
            ) else { continue }
            guard canLand(piece, on: destination, in: state, occupancy: occupancy) else { continue }
            moves.append(Move(pieceID: piece.id, origin: origin, destination: destination))
        }
        return moves
    }

    private static func canLand(
        _ piece: Piece,
        on destination: Position,
        in state: GameState,
        occupancy: Occupancy
    ) -> Bool {
        guard let occupantID = occupancy[destination], let occupant = state.piece(with: occupantID)
        else { return true }

        // Fruit never captures — including onto a blocked delivery square.
        guard piece.isBlender else { return false }

        // A blender standing on one of its owner's spawn squares cannot be taken, by
        // either side. Home is safe: the square a blender is bought back onto is not a
        // square it can be knocked straight off again, which is what turned buy-backs
        // into an endless toll before.
        if occupant.isBlender, isOnASpawn(occupant, in: state) { return false }

        // Otherwise a blender may land on anything: any fruit, whoever grew it, and any
        // other blender, its owner's included.
        return true
    }

    /// Whether a blender is standing on one of *its owner's* spawn squares — either of
    /// them, since a destroyed blender may be bought back onto either. Both are home.
    ///
    /// Written as a scan rather than through `spawnSquares` because this sits in the
    /// CPU's inner loop and must not allocate.
    static func isOnASpawn(_ blender: Piece, in state: GameState) -> Bool {
        guard blender.isBlender, let square = blender.position else { return false }
        return state.pieces.contains {
            $0.isBlender && $0.owner == blender.owner && $0.home == square
        }
    }

    // MARK: - Validation

    public static func validate(_ action: GameAction, in state: GameState) throws {
        guard !state.status.isOver else { throw RulesError.gameOver }

        switch action {
        case .restoreBlender(let id, let square):
            guard let blender = state.piece(with: id) else { throw RulesError.unknownPiece(id) }
            guard blender.owner == state.activePlayer else {
                throw RulesError.notActivePlayersPiece(id)
            }
            guard blender.isDestroyed else { throw RulesError.blenderNotDestroyed(id) }
            guard blender.needsBuyingBack else { throw RulesError.blenderReturningOnItsOwn(id) }
            guard spawnSquares(ownedBy: blender.owner, in: state).contains(square) else {
                throw RulesError.notASpawnSquare(square)
            }
            guard state.piece(at: square) == nil else { throw RulesError.spawnOccupied(id) }
            return

        case .move(let move):
            try validate(move, in: state)
        }
    }

    /// Public legality includes the repetition rule; the engine's own internal
    /// simulations switch it off so that asking the question does not ask it again.
    private static func validateRepetition(_ action: GameAction, in state: GameState) throws {
        guard wouldRepeat(action, in: state) else { return }
        throw RulesError.wouldRepeatPosition
    }

    public static func validate(_ move: Move, in state: GameState) throws {
        guard !state.status.isOver else { throw RulesError.gameOver }
        guard let piece = state.piece(with: move.pieceID) else {
            throw RulesError.unknownPiece(move.pieceID)
        }
        guard piece.owner == state.activePlayer else {
            throw RulesError.notActivePlayersPiece(move.pieceID)
        }
        guard let origin = piece.position else {
            throw RulesError.pieceIsOffBoard(move.pieceID)
        }
        guard origin == move.origin else {
            throw RulesError.originMismatch(expected: origin, given: move.origin)
        }
        let occupancy = Occupancy(state)
        let legal = legalMoves(for: piece, in: state, occupancy: occupancy)
        guard legal.contains(move) else {
            throw RulesError.illegalDestination(move.destination)
        }
    }

    // MARK: - Legal actions

    /// Every action the active player may take: moves, plus any blender of theirs that
    /// can be put back onto a clear spawn.
    public static func legalActions(
        in state: GameState,
        enforcingRepetition: Bool = true
    ) -> [GameAction] {
        guard !state.status.isOver else { return [] }

        var actions = legalMoves(
            for: state.activePlayer, in: state, enforcingRepetition: enforcingRepetition
        ).map(GameAction.move)
        let spawns = spawnSquares(ownedBy: state.activePlayer, in: state)
        for blender in state.destroyedBlenders(ownedBy: state.activePlayer) where blender.needsBuyingBack {
            for spawn in spawns where state.piece(at: spawn) == nil {
                let restore = GameAction.restoreBlender(pieceID: blender.id, at: spawn)
                if enforcingRepetition, wouldRepeat(restore, in: state) { continue }
                actions.append(restore)
            }
        }
        return actions.sorted()
    }

    // MARK: - Apply

    public struct Transition: Sendable {
        public let state: GameState
        public let events: [GameEvent]
    }

    /// Convenience for the common case.
    public static func apply(
        _ move: Move,
        to state: GameState,
        battles: BattleResolution = .dice,
        enforcingRepetition: Bool = true
    ) throws -> Transition {
        try apply(
            .move(move), to: state, battles: battles, enforcingRepetition: enforcingRepetition
        )
    }

    /// Applies one validated action and returns the resulting state plus the ordered
    /// events it produced. Within a move: the piece moves, then combat or a blend at its
    /// destination resolves, then delivery. The turn then advances, and anything due
    /// back — a delivered banana, a blender whose smoothie blackout has run out — returns
    /// before the next player can pick a piece.
    public static func apply(
        _ action: GameAction,
        to state: GameState,
        battles: BattleResolution = .dice,
        enforcingRepetition: Bool = true
    ) throws -> Transition {
        try validate(action, in: state)
        if enforcingRepetition { try validateRepetition(action, in: state) }

        var next = state
        var events: [GameEvent] = []

        switch action {
        case .restoreBlender(let id, let square):
            restore(id, at: square, in: &next, events: &events)

        case .move(let move):
            // Force-unwrap is safe: validate() proved the piece exists and belongs to
            // the active player.
            let piece = state.piece(with: move.pieceID)!
            let occupant = state.piece(at: move.destination)

            events.append(.moved(pieceID: piece.id, from: move.origin, to: move.destination))

            if piece.isBlender {
                if let occupant, occupant.isBlender {
                    resolveCollision(
                        attacker: piece,
                        defender: occupant,
                        at: move.destination,
                        battles: battles,
                        in: &next,
                        events: &events
                    )
                } else {
                    if let occupant {
                        blend(occupant, at: move.destination, blendedBy: piece.owner, in: &next, events: &events)
                    }
                    next.setPosition(move.destination, for: piece.id)
                }
            } else {
                next.setPosition(move.destination, for: piece.id)
                if move.destination.row == piece.owner.deliveryRow {
                    deliver(piece, at: move.destination, in: &next, events: &events)
                }
            }

        }

        next.history.append(action)
        announceGoldenPineapples(since: state, in: next, events: &events)
        recordScoringTurnForWildcards(by: state.activePlayer, from: state.scores, in: &next)
        finishTurn(in: &next, events: &events, enforcingRepetition: enforcingRepetition)
        // Recorded last, once the turn has actually changed hands: the arrangement
        // includes whose move it is, so counting it any earlier would file it under
        // the wrong player and the rule would never match anything.
        next.recordArrangement()
        return Transition(state: next, events: events)
    }

    /// A pineapple turning golden changes no state — `GameState.isGolden` reads it off
    /// the board — so this exists purely so the UI has a beat to play it on. Emitted the
    /// moment a player drops to their last pineapple, however it went: blended, delivered,
    /// or taken by either side.
    private static func announceGoldenPineapples(
        since previous: GameState,
        in state: GameState,
        events: inout [GameEvent]
    ) {
        guard state.ruleSet.usesWildcards else { return }
        for player in PlayerID.allCases {
            guard previous.pineapples(ownedBy: player).count > 1,
                  let survivor = state.goldenPineapple(ownedBy: player) else { continue }
            events.append(.pineappleTurnedGolden(pieceID: survivor.id, owner: player))
        }
    }

    /// Blenderama is a shutout, not just a solo run: it only stands if the opponent has
    /// never put points on the board. Any turn that nets its player points — a fruit
    /// across, or one of theirs through your blenders — takes the *other* player's
    /// Blenderama off for the rest of the match, exactly as stealing one of their
    /// self-blends does.
    ///
    /// Measured on the score rather than the action, because "netted positive points" is
    /// the rule. It falls out that a Blenderama run never negates itself and buying a
    /// blender back never negates theirs: both cost points rather than earning them.
    private static func recordScoringTurnForWildcards(
        by player: PlayerID,
        from previous: Scores,
        in state: inout GameState
    ) {
        guard state.ruleSet.usesWildcards, state.scores[player] > previous[player] else { return }
        state.wildcards.blenderamaCountered[player.opponent] = true
    }

    /// A blender that runs into another destroys it outright and takes the square. That
    /// is the whole of it: the attacker gains position and tempo, not a second move.
    /// Settles a blender running into another: a battle if the match is playing them,
    /// otherwise the plain kill it has always been.
    ///
    /// A battle is only ever between two *players*, so running into one of your own
    /// blenders still destroys it outright — there is nobody to roll against.
    private static func resolveCollision(
        attacker: Piece,
        defender: Piece,
        at square: Position,
        battles: BattleResolution,
        in state: inout GameState,
        events: inout [GameEvent]
    ) {
        guard state.blenderBattles, defender.owner != attacker.owner else {
            destroy(defender, by: attacker, at: square, in: &state, events: &events)
            state.setPosition(square, for: attacker.id)
            return
        }

        let report = battleReport(
            attacker: attacker,
            defender: defender,
            at: square,
            battles: battles,
            in: state
        )
        events.append(.blenderBattle(report, at: square))

        if report.attackerWins {
            destroy(defender, by: attacker, at: square, in: &state, events: &events)
            state.setPosition(square, for: attacker.id)
        } else {
            // The attack failed: the attacker is the one that leaves, and the defender
            // holds the square it was already standing on.
            destroy(attacker, by: defender, at: square, in: &state, events: &events)
        }
    }

    /// Both dice come from the position itself, so two devices given the same move
    /// compute the same battle. Salted differently per side so the two rolls are not the
    /// same number, and salted with the square so two battles in one position differ.
    private static func battleReport(
        attacker: Piece,
        defender: Piece,
        at square: Position,
        battles: BattleResolution,
        in state: GameState
    ) -> BattleReport {
        let attackerRoll: Int
        let defenderRoll: Int
        switch battles {
        case .dice:
            let base = state.positionKey
                &+ UInt64(bitPattern: Int64(square.row &* Board.size &+ square.column))
            attackerRoll = die(base &+ 0x9E37_79B9)
            defenderRoll = die(base &+ 0x85EB_CA6B)
        case .expected:
            // Any equal pair does: being the same on both sides it cancels, leaving the
            // comparison as standing fruit plus the attacker's bonus against standing
            // fruit. Four is the rounded mean of a die, so the totals the CPU reasons
            // about are at least the right size.
            attackerRoll = 4
            defenderRoll = 4
        }

        return BattleReport(
            attacker: attacker.owner,
            attackerFruit: state.battleStrength(for: attacker.owner),
            attackerBonus: battleAttackerBonus,
            attackerRoll: attackerRoll,
            defenderFruit: state.battleStrength(for: defender.owner),
            defenderRoll: defenderRoll
        )
    }

    private static func die(_ seed: UInt64) -> Int {
        var generator = SeededGenerator(seed: seed)
        return Int(generator.next() % 6) + 1
    }

    private static func destroy(
        _ victim: Piece,
        by attacker: Piece,
        at square: Position,
        in state: inout GameState,
        events: inout [GameEvent]
    ) {
        state.setPosition(nil, for: victim.id)
        events.append(
            .blenderDestroyed(
                pieceID: victim.id,
                owner: victim.owner,
                by: attacker.id,
                at: square
            )
        )
    }

    /// Every square this player's blenders spawn on. A destroyed blender may be bought
    /// back onto either of them, not only the one it started on.
    public static func spawnSquares(ownedBy player: PlayerID, in state: GameState) -> [Position] {
        state.blenders(ownedBy: player).compactMap(\.home).sorted()
    }

    /// Buying a destroyed blender back: the whole turn, and three points off the score,
    /// which is allowed to go negative.
    private static func restore(
        _ id: PieceID,
        at square: Position,
        in state: inout GameState,
        events: inout [GameEvent]
    ) {
        guard let blender = state.piece(with: id) else { return }
        state.setPosition(square, for: id)
        state.scores[blender.owner] -= blenderRestoreCost
        events.append(
            .blenderRestored(pieceID: id, owner: blender.owner, at: square, cost: blenderRestoreCost)
        )
        events.append(.scoreChanged(player: blender.owner, newScore: state.scores[blender.owner]))
    }

    // MARK: - Resolution

    /// Signed blending: taking the other player's fruit pays its base value, taking
    /// your own costs it. Blending never uses the delivery multiplier, and scores are
    /// deliberately allowed below zero — clamping would hide the price of clearing your
    /// own path.
    private static func blend(
        _ fruit: Piece,
        at square: Position,
        blendedBy player: PlayerID,
        in state: inout GameState,
        events: inout [GameEvent]
    ) {
        // A Golden Pineapple is worth double to whoever puts it through a blender —
        // and costs its owner double for the same reason.
        let magnitude = state.isGolden(fruit.id)
            ? fruit.kind.baseValue * 2
            : fruit.kind.baseValue
        let points = fruit.owner == player ? -magnitude : magnitude

        state.remove(fruit.id)
        state.scores[player] += points
        events.append(
            .blended(
                pieceID: fruit.id,
                kind: fruit.kind,
                fruitOwner: fruit.owner,
                at: square,
                awardedTo: player,
                points: points
            )
        )
        events.append(.scoreChanged(player: player, newScore: state.scores[player]))

        guard state.ruleSet.usesWildcards else { return }
        recordBlendForWildcards(fruit, blendedBy: player, in: &state, events: &events)
    }

    // MARK: - Wildcard rules

    private static func recordBlendForWildcards(
        _ fruit: Piece,
        blendedBy player: PlayerID,
        in state: inout GameState,
        events: inout [GameEvent]
    ) {
        if fruit.owner == player {
            state.wildcards.ownFruitBlended[player] += 1
        } else {
            // Stealing one of their self-blends is the hard counter: their Blenderama
            // is off for the rest of the match, whatever they do afterwards.
            state.wildcards.blenderamaCountered[fruit.owner] = true
        }

        state.wildcards.smoothie[player].record(fruit.kind)
        guard state.wildcards.smoothie[player].isComplete,
              !state.wildcards.smoothieFired[player] else { return }
        state.wildcards.smoothieFired[player] = true
        awardSmoothie(to: player, in: &state, events: &events)
    }

    /// One of every fruit type through your blenders, whoever grew them, pays
    /// `smoothieAward` points. Once each per match.
    private static func awardSmoothie(
        to player: PlayerID,
        in state: inout GameState,
        events: inout [GameEvent]
    ) {
        state.scores[player] += smoothieAward
        events.append(.smoothiePoured(player: player, points: smoothieAward))
        events.append(.scoreChanged(player: player, newScore: state.scores[player]))
    }

    /// One of every fruit type through your blenders, at least one of them taken off the
    /// opponent, blows both of theirs off the board — but only for a turn. Three of your
    /// own fruit is not a recipe, it is just twelve points of self-harm. Unlike a combat kill these come back by themselves: their
    /// owner plays exactly one turn without blenders, and they return at the start of the
    /// turn after that. A blender already off the board cannot be blown up again.
    /// **Retained, no longer called.** Completing a smoothie pays points now; this is the
    /// old effect, kept whole against its return. `internal` rather than `private` purely
    /// so the tests can still exercise it — dead code that nothing runs is dead code that
    /// quietly stops working. Wire it back in by calling it from
    /// `recordBlendForWildcards` in place of `awardSmoothie`.
    static func pourSmoothie(
        for player: PlayerID,
        in state: inout GameState,
        events: inout [GameEvent]
    ) {
        let victims = state.activeBlenders(ownedBy: player.opponent).map(\.id).sorted()
        guard !victims.isEmpty else { return }

        let due = state.turnNumber + smoothieBlackoutTurns
        for id in victims {
            state.setPosition(nil, for: id)
            state.setRespawnTurn(due, for: id)
        }
        events.append(.smoothie(player: player, pieceIDs: victims, respawnTurn: due))
    }

    /// Puts back blenders whose smoothie blackout has elapsed. If the spawn happens to
    /// be occupied they simply wait for it, exactly as a delivered banana does — losing
    /// one to an accident of timing would turn a one-turn penalty into a permanent one.
    private static func returnScheduledBlenders(in state: inout GameState, events: inout [GameEvent]) {
        for blender in state.pieces.filter(\.isAwaitingRespawn).sorted(by: { $0.id < $1.id }) {
            guard let due = blender.respawnTurn, due <= state.turnNumber else { continue }
            guard let home = blender.home, state.piece(at: home) == nil else { continue }
            state.setPosition(home, for: blender.id)
            state.setRespawnTurn(nil, for: blender.id)
            events.append(.blenderRespawned(pieceID: blender.id, owner: blender.owner, at: home))
        }
    }

    /// Immediate victories, checked as a pure function of the state so it does not
    /// matter whether the move or a respawn landing was what completed one.
    private static func wildcardWin(in state: GameState) -> Outcome? {
        guard state.ruleSet.usesWildcards else { return nil }
        for player in PlayerID.allCases {
            // Three banana deliveries, counted cumulatively across the match. With only
            // two bananas each that takes at least one round trip, so it is a route a
            // trailing player can commit to and a leading player can see coming.
            if state.wildcards.bananasDelivered[player] >= GameSetup.bananaDeliveriesToWin {
                return .win(player, by: .bananaSplit)
            }
            if state.wildcards.ownFruitBlended[player] >= GameSetup.fruitCount,
               !state.wildcards.blenderamaCountered[player] {
                return .win(player, by: .blenderama)
            }
        }
        return nil
    }

    private static func deliver(
        _ fruit: Piece,
        at square: Position,
        in state: inout GameState,
        events: inout [GameEvent]
    ) {
        // A Golden Pineapple that makes it across is worth three times its base value
        // rather than the usual two.
        let points = state.isGolden(fruit.id)
            ? fruit.kind.baseValue * 3
            : fruit.kind.deliveryValue

        let recycles = state.ruleSet.usesWildcards && fruit.kind == .banana
        if recycles {
            state.wildcards.bananasDelivered[fruit.owner] += 1
        }

        if recycles {
            // Wildcard bananas are not consumed: they go back to their spawn, or wait
            // just off the board until that square is clear.
            state.setPosition(nil, for: fruit.id)
        } else {
            state.remove(fruit.id)
        }
        state.scores[fruit.owner] += points
        events.append(
            .delivered(
                pieceID: fruit.id,
                kind: fruit.kind,
                owner: fruit.owner,
                at: square,
                points: points
            )
        )
        events.append(.scoreChanged(player: fruit.owner, newScore: state.scores[fruit.owner]))
    }

    // MARK: - Turn and end state

    /// `enforcingRepetition` is threaded all the way down to the skip check, and that
    /// is the whole reason it exists. Deciding whether the next player has a move is
    /// itself a legality question, so a simulated turn that asked it *with* the rule
    /// on would simulate another turn to answer it, for ever. Inside a simulation the
    /// skip check therefore ignores repetition: it can only differ in the rare case
    /// where every one of a player's moves is banned, and it only affects whose turn
    /// it is in a position being weighed rather than one being played.
    private static func finishTurn(
        in state: inout GameState,
        events: inout [GameEvent],
        enforcingRepetition: Bool
    ) {
        // Alternate victories win outright, so they are checked before anything else
        // gets a chance to happen.
        if let win = wildcardWin(in: state) {
            end(&state, with: win, events: &events)
            return
        }

        state.turnNumber += 1
        // Delivered bananas, and blenders whose smoothie blackout has run out, come back
        // before the next player picks anything up.
        returnWaitingBananas(in: &state, events: &events)
        returnScheduledBlenders(in: &state, events: &events)

        if let win = wildcardWin(in: state) {
            end(&state, with: win, events: &events)
            return
        }

        if isFruitDepleted(state) {
            end(&state, events: &events)
            return
        }

        let opponent = state.activePlayer.opponent
        if !legalActions(for: opponent, in: state, enforcingRepetition: enforcingRepetition)
            .isEmpty {
            state.activePlayer = opponent
            events.append(.turnChanged(active: opponent))
        } else if !legalActions(
            for: state.activePlayer, in: state, enforcingRepetition: enforcingRepetition
        ).isEmpty {
            // Not in the PRD, but the engine has to answer it: a player with nothing
            // legal to do is skipped rather than deadlocking the match.
            events.append(.turnPassed(skipped: opponent))
            events.append(.turnChanged(active: state.activePlayer))
        } else {
            end(&state, events: &events)
        }
    }

    /// Wildcard rules only: a delivered banana reappears on its spawn as soon as that
    /// square is clear. Without this a three-banana win would be impossible whenever a
    /// spawn happened to be occupied at the wrong moment.
    private static func returnWaitingBananas(in state: inout GameState, events: inout [GameEvent]) {
        guard state.ruleSet.usesWildcards else { return }
        for banana in state.pieces.filter(\.isWaitingToReturn).sorted(by: { $0.id < $1.id }) {
            guard let home = banana.home, state.piece(at: home) == nil else { continue }
            state.setPosition(home, for: banana.id)
            events.append(.bananaReturned(pieceID: banana.id, to: home))
        }
    }

    /// Actions available to a player who is not necessarily the active one, used by the
    /// skip check.
    private static func legalActions(
        for player: PlayerID,
        in state: GameState,
        enforcingRepetition: Bool = true
    ) -> [GameAction] {
        var probe = state
        probe.activePlayer = player
        return legalActions(in: probe, enforcingRepetition: enforcingRepetition)
    }

    /// The match ends the moment *either* player runs out of fruit — not when the board
    /// is empty. Fruit still standing has never scored and never will, so it counts for
    /// nothing: whoever is ahead at that instant wins.
    ///
    /// A banana in transit between the far edge and its spawn still counts as being in
    /// play. It is coming back, and ending a match on that accident of timing would make
    /// the three-banana victory unreachable for anyone down to their last banana.
    public static func isFruitDepleted(_ state: GameState) -> Bool {
        PlayerID.allCases.contains { state.fruit(ownedBy: $0).isEmpty }
    }

    private static func end(_ state: inout GameState, events: inout [GameEvent]) {
        end(&state, with: outcome(for: state.scores), events: &events)
    }

    private static func end(_ state: inout GameState, with outcome: Outcome, events: inout [GameEvent]) {
        state.status = .ended(outcome)
        events.append(.gameEnded(outcome: outcome))
    }

    public static func outcome(for scores: Scores) -> Outcome {
        if scores.one > scores.two { return .win(.one) }
        if scores.two > scores.one { return .win(.two) }
        return .draw
    }
}
