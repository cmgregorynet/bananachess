import Foundation

public enum CPUDifficulty: String, Codable, Sendable, CaseIterable, Identifiable {
    case easy
    case medium
    case hard
    case brutal

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .easy: "Easy"
        case .medium: "Medium"
        case .hard: "Hard"
        case .brutal: "Brutal"
        }
    }

    public var blurb: String {
        switch self {
        case .easy: "Moves without a plan. A good first game."
        case .medium: "Takes the point in front of it."
        case .hard: "Looks several moves ahead, and will trade."
        case .brutal: "Answers your plan before you finish it. Takes its time."
        }
    }

    public var plan: SearchPlan { SearchPlan.standard(for: self) }
}

/// What a difficulty actually *is*, as data rather than three branches.
///
/// Pulling these out turned a level into a handful of numbers that can be measured
/// against each other, which is the only way to answer "make it twice as hard" with
/// anything but a shrug. It also makes the levels benchmarkable: a duel between two
/// plans runs in seconds if the budgets are cut, and in minutes if they are not.
public struct SearchPlan: Sendable, Hashable {
    /// How far ahead to look, if the clock allows. One is a plain one-ply pick with no
    /// lookahead at all.
    public var depth: Int
    /// What fraction of the ranked moves to choose randomly between. One takes any legal
    /// move at all; anything at or below zero always plays the best one it found.
    public var candidateFraction: Double
    /// Stops deepening past this. The hard cap below it is absolute.
    public var softTimeBudget: Duration
    public var hardTimeCap: Duration
    /// How close two lines must be before the tie is broken on banking points now.
    /// Zero leaves the search's own judgement alone.
    public var tempoMargin: Int
    /// How many extra plies of *forcing* moves to follow past the nominal depth, so a
    /// line is never judged in the middle of a blend. Zero turns it off.
    ///
    /// This is the only lever that buys sight on this board. Width is priced out — a
    /// ply costs roughly seven times the last, and the clock stops every level at five —
    /// so the extra plies have to be spent on the handful of moves that actually settle
    /// anything rather than on all thirteen.
    public var quiescenceDepth: Int

    public init(
        depth: Int,
        candidateFraction: Double,
        softTimeBudget: Duration,
        hardTimeCap: Duration,
        tempoMargin: Int = 0,
        quiescenceDepth: Int = 0
    ) {
        self.depth = depth
        self.candidateFraction = candidateFraction
        self.softTimeBudget = softTimeBudget
        self.hardTimeCap = hardTimeCap
        self.tempoMargin = tempoMargin
        self.quiescenceDepth = quiescenceDepth
    }

    /// The shipped levels.
    ///
    /// Each was roughly doubled in strength on 6 October 2026, measured as a duel against
    /// the level it replaced — see `DECISIONS.md`. Easy used to move entirely at random,
    /// which is not an opponent so much as a board that shuffles; Medium looked exactly
    /// one move ahead; Hard searched with a transposition table that was misfiling its
    /// bounds and throwing away much of what it had worked out.
    public static func standard(for difficulty: CPUDifficulty) -> SearchPlan {
        switch difficulty {
        case .easy:
            // Still a beginner's opponent: it looks one move ahead and then picks
            // carelessly among the better half, so it takes a free piece when one is in
            // front of it and otherwise wanders.
            SearchPlan(
                depth: 1, candidateFraction: 0.5,
                softTimeBudget: .milliseconds(50), hardTimeCap: .milliseconds(600)
            )
        case .medium:
            // Searches now rather than looking exactly one move ahead, but kept brisk:
            // a middle setting that stalls a second a move is a worse opponent than one
            // that answers at once, whatever it plays.
            SearchPlan(
                depth: 4, candidateFraction: 0,
                softTimeBudget: .milliseconds(220), hardTimeCap: .milliseconds(450),
                tempoMargin: 48
            )
        case .hard:
            // Deliberately the same clock as before. Hard was already at the limit of
            // what is pleasant on a phone — measured at four seconds a move when the
            // budget was raised, which reads as a hang — so every bit of the improvement
            // has to come from searching better in the time it already had.
            SearchPlan(
                depth: 12, candidateFraction: 0,
                softTimeBudget: .milliseconds(1_500), hardTimeCap: .seconds(2)
            )
        case .brutal:
            // Measured, after a first attempt that simply doubled the clock and came out
            // *weaker*: a ply costs about seven times the one before it, so two seconds
            // and four seconds both complete exactly depth five and the extra two
            // seconds went entirely on a depth-six iteration that was always abandoned.
            // What the clock can buy is selective depth — eight further plies down
            // forcing lines only — so this level is told to spend it there instead.
            SearchPlan(
                depth: 12, candidateFraction: 0,
                softTimeBudget: .seconds(2), hardTimeCap: .seconds(4),
                quiescenceDepth: 8
            )
        }
    }

    /// What the levels were before that, kept so the improvement stays measurable rather
    /// than asserted.
    public static func previous(for difficulty: CPUDifficulty) -> SearchPlan {
        switch difficulty {
        case .easy:
            SearchPlan(
                depth: 1, candidateFraction: 1,
                softTimeBudget: .milliseconds(50), hardTimeCap: .milliseconds(600)
            )
        case .medium:
            SearchPlan(
                depth: 1, candidateFraction: 0,
                softTimeBudget: .milliseconds(300), hardTimeCap: .milliseconds(600)
            )
        case .hard:
            SearchPlan(
                depth: 8, candidateFraction: 0,
                softTimeBudget: .milliseconds(1_500), hardTimeCap: .seconds(2)
            )
        // Brutal is new, so the level it has to improve on is the one below it.
        case .brutal:
            SearchPlan.standard(for: .hard)
        }
    }
}

/// Picks one legal move for a difficulty from an immutable snapshot. Never mutates its
/// input and never applies its own move — the caller pushes the result back through
/// `RulesEngine.apply`, so the CPU has no private path into the game state.
public struct CPUPlayer: Sendable {
    public let difficulty: CPUDifficulty
    public var weights: EvaluationWeights
    /// Set for reproducible play in tests; nil uses the system generator.
    public var seed: UInt64?
    /// Overridable so two levels can be put head to head, and so a benchmark can cut the
    /// budgets without changing what is being compared.
    public var plan: SearchPlan

    public init(
        difficulty: CPUDifficulty,
        seed: UInt64? = nil,
        weights: EvaluationWeights = EvaluationWeights(),
        plan: SearchPlan? = nil
    ) {
        self.difficulty = difficulty
        self.seed = seed
        self.weights = weights
        self.plan = plan ?? difficulty.plan
    }

    /// Convenience for callers that only care about moves.
    public func selectMove(in state: GameState) -> Move? {
        guard case .move(let move) = selectAction(in: state) else { return nil }
        return move
    }

    public func selectAction(in state: GameState) -> GameAction? {
        let moves = RulesEngine.legalActions(in: state)
        guard !moves.isEmpty else { return nil }

        // One path for every level now. A level is its plan: how deep it looks, and how
        // carelessly it chooses among what it found.
        if plan.candidateFraction >= 1 { return pickRandom(from: moves, in: state) }
        if plan.depth <= 1 { return pickShallow(from: moves, in: state) }
        return pickBestSearch(from: moves, in: state)
    }

    private func pickRandom(from moves: [GameAction], in state: GameState) -> GameAction {
        guard seed != nil else { return moves.randomElement()! }
        var generator = generator(for: state)
        return moves.randomElement(using: &generator)!
    }

    /// One ply, then a pick from the better end of what it saw. With a fraction of zero
    /// this is the old Medium exactly; with a half it is an opponent that spots a free
    /// piece but will not plan for one.
    private func pickShallow(from moves: [GameAction], in state: GameState) -> GameAction {
        let player = state.activePlayer
        var scored: [(move: GameAction, value: Int)] = []
        scored.reserveCapacity(moves.count)

        for move in moves {
            guard let transition = try? RulesEngine.apply(
                move, to: state, battles: .expected, enforcingRepetition: false
            ) else { continue }
            scored.append((move, Evaluation.score(transition.state, for: player, weights: weights)))
        }
        guard !scored.isEmpty else { return moves[0] }
        return choose(from: scored, in: state)
    }

    /// Picks among the top slice of the ranked moves. Ties at the very top are broken the
    /// same way they always were, so a level with a fraction of zero is unchanged.
    private func choose(
        from scored: [(move: GameAction, value: Int)], in state: GameState
    ) -> GameAction {
        let ranked = scored.sorted { ($0.value, $0.move) > ($1.value, $1.move) }
        guard plan.candidateFraction > 0 else {
            let best = ranked[0].value
            return breakTie(among: ranked.filter { $0.value == best }.map(\.move), in: state)
        }
        let pool = max(1, Int((Double(ranked.count) * plan.candidateFraction).rounded(.up)))
        return breakTie(among: Array(ranked.prefix(pool)).map(\.move), in: state)
    }

    // MARK: - Searching

    private func pickBestSearch(from moves: [GameAction], in state: GameState) -> GameAction {
        let player = state.activePlayer
        let clock = ContinuousClock()
        let start = clock.now
        let softDeadline = start + plan.softTimeBudget
        let hardDeadline = start + plan.hardTimeCap

        var search = Search(
            root: player,
            weights: weights,
            clock: clock,
            deadline: hardDeadline,
            quiescenceDepth: plan.quiescenceDepth
        )

        var ordered = orderMoves(moves, in: state)
        var best = ordered[0]
        var depth = 2
        var values: [GameAction: Int] = [:]

        while depth <= min(plan.depth, Search.maximumDepth) {
            if clock.now >= softDeadline { break }
            guard let result = search.bestMove(from: state, moves: ordered, depth: depth) else {
                // Ran out of time mid-depth: the partial result is untrustworthy, so
                // the last fully completed depth stands.
                break
            }
            best = result.move
            values = result.values
            // Hand the next iteration this one's ranking. Deepening earns its keep
            // mostly through the ordering it passes forward, not through the values it
            // throws away: searching the previous best first is what lets the next depth
            // establish a tight window on its first move instead of its last.
            ordered.sort { (values[$0] ?? Int.min, $0) > (values[$1] ?? Int.min, $1) }
            depth += 1
            // A forced win is found, not improved on.
            if abs(result.value) >= Evaluation.winScore { break }
        }
        return preferringTempo(best, among: values, in: state)
    }

    /// Breaks a near-tie toward banking the points now.
    ///
    /// Deep search has a horizon, and a position where a delivery cannot be interfered
    /// with looks the same to it whether the points are taken this move or next — both
    /// lines score, so all that is left is positional noise. Measured at one such
    /// position: delivering scored 543, shuffling sideways 563, twenty points out of five
    /// hundred. The search is not wrong, but a player watching a computer shuffle while
    /// sitting on six free points does not see subtlety, they see a bug.
    ///
    /// Per level, and off for Hard, because it is an override of the search's judgement
    /// and Hard has the best judgement to override — see `DECISIONS.md` for the duel that
    /// settled that.
    private func preferringTempo(
        _ best: GameAction, among values: [GameAction: Int], in state: GameState
    ) -> GameAction {
        guard plan.tempoMargin > 0, let bestValue = values[best] else { return best }
        let contenders = values
            .filter { $0.value >= bestValue - plan.tempoMargin }
            .map(\.key)
        guard contenders.count > 1 else { return best }
        // Safe against alpha-beta's fail-low values, which are only upper bounds: move
        // ordering searches high-ranking moves first, so anything this can promote was
        // searched before the winner and therefore has an exact value.
        let ranked = contenders
            .map { (move: $0, rank: Search.staticRank(of: $0, in: state)) }
            .sorted { ($0.rank, $0.move) > ($1.rank, $1.move) }
        // Only overrides when the alternative actually banks something; otherwise the
        // search's own choice stands.
        guard let front = ranked.first, front.rank > Search.staticRank(of: best, in: state) else {
            return best
        }
        return front.move
    }

    // MARK: - Shared

    private func breakTie(among moves: [GameAction], in state: GameState) -> GameAction {
        guard moves.count > 1 else { return moves[0] }
        guard seed != nil else { return moves.randomElement()! }
        var generator = generator(for: state)
        return moves.randomElement(using: &generator)!
    }

    /// Reproducible for a given game, but deliberately not a pure function of the
    /// position: shuffling a fruit back and forth returns to the same position, and a
    /// position-only seed would answer it with the same move forever.
    private func generator(for state: GameState) -> SeededGenerator {
        SeededGenerator(seed: (seed ?? 0) ^ state.positionKey ^ UInt64(bitPattern: Int64(state.turnNumber)))
    }

    private func orderMoves(_ moves: [GameAction], in state: GameState) -> [GameAction] {
        moves
            .map { (move: $0, rank: Search.staticRank(of: $0, in: state)) }
            .sorted { ($0.rank, $0.move) > ($1.rank, $1.move) }
            .map(\.move)
    }
}

// MARK: - Alpha-beta search

private struct Search {
    /// Raised from eight once the search stopped paying for enforced legality at every
    /// node. The clock still decides where it actually stops, and on a real midgame
    /// position it stops at five: depth six costs about eight seconds. This ceiling is
    /// therefore not what binds, and raising it further buys nothing.
    static let maximumDepth = 12
    /// Checking a clock is not free; do it every this-many nodes.
    static let timeCheckInterval = 512

    let root: PlayerID
    let weights: EvaluationWeights
    let clock: ContinuousClock
    let deadline: ContinuousClock.Instant
    let quiescenceDepth: Int

    private var transpositions: [UInt64: Entry] = [:]
    private var nodes = 0
    private var timedOut = false

    struct Entry {
        let depth: Int
        let value: Int
        let bound: Bound
        /// The move that produced `value`. Kept even when the entry is too shallow to
        /// answer the current query: a move that was best at depth three is still the
        /// first thing worth trying at depth six, and trying it first is what makes the
        /// window narrow early enough for the cut-offs to do their work.
        let move: GameAction?
    }

    enum Bound {
        case exact
        case lower
        case upper
    }

    struct Result {
        let move: GameAction
        let value: Int
        /// Every root move's value, so the caller can tell a real preference from a tie.
        let values: [GameAction: Int]
    }

    init(
        root: PlayerID,
        weights: EvaluationWeights,
        clock: ContinuousClock,
        deadline: ContinuousClock.Instant,
        quiescenceDepth: Int
    ) {
        self.root = root
        self.weights = weights
        self.clock = clock
        self.deadline = deadline
        self.quiescenceDepth = quiescenceDepth
    }

    mutating func bestMove(from state: GameState, moves: [GameAction], depth: Int) -> Result? {
        var alpha = -Evaluation.winScore * 2
        let beta = Evaluation.winScore * 2
        var best: (move: GameAction, value: Int)?
        var values: [GameAction: Int] = [:]

        for move in moves {
            guard let transition = try? RulesEngine.apply(
                move, to: state, battles: .expected, enforcingRepetition: false
            ) else { continue }
            let value = negamaxed(transition.state, depth: depth - 1, alpha: alpha, beta: beta)
            if timedOut { return nil }
            values[move] = value
            if best == nil || value > best!.value {
                best = (move, value)
                alpha = max(alpha, value)
            }
        }
        guard let best else { return nil }
        return Result(move: best.move, value: best.value, values: values)
    }

    /// Minimax rather than true negamax: a player with no legal move is skipped, so the
    /// side to move does not simply alternate and the sign cannot be flipped blindly.
    private mutating func negamaxed(_ state: GameState, depth: Int, alpha: Int, beta: Int) -> Int {
        nodes += 1
        if nodes % Self.timeCheckInterval == 0, clock.now >= deadline {
            timedOut = true
        }
        if timedOut { return 0 }

        if depth <= 0 || state.status.isOver {
            return quiesced(state, alpha: alpha, beta: beta, remaining: quiescenceDepth)
        }

        let key = state.positionKey
        var hint: GameAction?
        if let entry = transpositions[key] {
            hint = entry.move
            if entry.depth >= depth {
                switch entry.bound {
                case .exact: return entry.value
                case .lower: if entry.value >= beta { return entry.value }
                case .upper: if entry.value <= alpha { return entry.value }
                }
            }
        }

        // Unenforced inside the search, and this is the difference between looking two
        // moves ahead and looking five. Since rules 18 every enforced legality query
        // plays out each candidate move to see whether it repeats, so asking for it at
        // every node costs a whole extra ply of work per ply of depth. The move finally
        // played is still taken from a fully enforced list at the root and applied
        // through the real path, so nothing illegal can escape — the search is merely
        // allowed to *consider* a repetition it would not be permitted to play.
        let moves = RulesEngine.legalActions(in: state, enforcingRepetition: false)
        guard !moves.isEmpty else {
            return Evaluation.score(state, for: root, weights: weights)
        }

        let maximizing = state.activePlayer == root
        // Kept, because a stored bound has to be classified against the window this node
        // was *given*, not the one it narrowed to. Comparing against the mutated values
        // marked almost everything `exact` — alpha has by then been raised to `best`, so
        // `best <= alpha` is true only by accident — and an entry claiming to be exact
        // when it is a bound is a wrong answer handed back with confidence. It is what
        // made Hard report a losing line as a forced win and stop deepening on it.
        let originalAlpha = alpha
        let originalBeta = beta
        var alpha = alpha
        var beta = beta
        var best = maximizing ? Int.min : Int.max
        var bestMove: GameAction?

        for move in ordered(moves, in: state, first: hint) {
            guard let transition = try? RulesEngine.apply(
                move, to: state, battles: .expected, enforcingRepetition: false
            ) else { continue }
            let value = negamaxed(transition.state, depth: depth - 1, alpha: alpha, beta: beta)
            if timedOut { return best == Int.min || best == Int.max ? 0 : best }

            if maximizing {
                if bestMove == nil || value > best { best = value; bestMove = move }
                alpha = max(alpha, best)
            } else {
                if bestMove == nil || value < best { best = value; bestMove = move }
                beta = min(beta, best)
            }
            if alpha >= beta { break }
        }

        // Nothing is learned from a node that ran out of time part way through, and
        // filing it would poison every later lookup at this depth.
        guard !timedOut else { return best }
        let bound: Bound =
            if best <= originalAlpha { .upper } else if best >= originalBeta { .lower } else { .exact }
        transpositions[key] = Entry(depth: depth, value: best, bound: bound, move: bestMove)
        return best
    }

    private func ordered(
        _ moves: [GameAction], in state: GameState, first hint: GameAction?
    ) -> [GameAction] {
        moves
            .map { (move: $0, rank: $0 == hint ? Int.max : Self.staticRank(of: $0, in: state)) }
            .sorted { ($0.rank, $0.move) > ($1.rank, $1.move) }
            .map(\.move)
    }

    /// Keeps following *forcing* moves past the nominal depth so a line is never judged
    /// half way through an exchange. Without it the search happily stops one ply after
    /// pushing a fruit next to a blender, banks the advance, and never sees it eaten:
    /// the horizon effect, and on this board it is the difference between a level that
    /// looks five moves ahead and one that plays the exchanges out.
    ///
    /// `standPat` is the value of declining to continue. A player here must actually
    /// move, so declining is not strictly available — the usual approximation, and sound
    /// in the direction that matters: it stops the side to move being credited with a
    /// forced loss it could have avoided by standing still.
    private mutating func quiesced(
        _ state: GameState, alpha: Int, beta: Int, remaining: Int
    ) -> Int {
        // Returned before the node is even counted, so a level with quiescence off
        // behaves exactly as it did before this existed — same leaf value, same node
        // count, so the clock is still polled on the same nodes.
        guard remaining > 0, !state.status.isOver else {
            return Evaluation.score(state, for: root, weights: weights)
        }

        nodes += 1
        if nodes % Self.timeCheckInterval == 0, clock.now >= deadline {
            timedOut = true
        }
        if timedOut { return 0 }

        let standPat = Evaluation.score(state, for: root, weights: weights)

        let maximizing = state.activePlayer == root
        var alpha = alpha
        var beta = beta
        if maximizing {
            if standPat >= beta { return standPat }
            alpha = max(alpha, standPat)
        } else {
            if standPat <= alpha { return standPat }
            beta = min(beta, standPat)
        }

        let forcing = RulesEngine.legalActions(in: state, enforcingRepetition: false)
            .filter { Self.isForcing($0, in: state) }
        guard !forcing.isEmpty else { return standPat }

        var best = standPat
        for move in ordered(forcing, in: state, first: nil) {
            guard let transition = try? RulesEngine.apply(
                move, to: state, battles: .expected, enforcingRepetition: false
            ) else { continue }
            let value = quiesced(
                transition.state, alpha: alpha, beta: beta, remaining: remaining - 1
            )
            if timedOut { return best }
            if maximizing {
                best = max(best, value)
                alpha = max(alpha, best)
            } else {
                best = min(best, value)
                beta = min(beta, best)
            }
            if alpha >= beta { break }
        }
        return best
    }

    /// A move that settles something: a fruit reaching its delivery row, or a blender
    /// arriving on an occupied square — a blend, a self-blend, or a blender battle.
    /// Decided without applying the move, which is the expensive half of a node.
    static func isForcing(_ action: GameAction, in state: GameState) -> Bool {
        guard case .move(let move) = action,
              let piece = state.piece(with: move.pieceID) else { return false }
        if piece.isFruit { return move.destination.row == piece.owner.deliveryRow }
        return state.piece(at: move.destination) != nil
    }

    /// Cheap ordering heuristic — no move is applied. Immediate points first, then
    /// fruit that is close to home free, then everything else. Self-blends sort last:
    /// they are legal and occasionally right, but they should be the moves the search
    /// reaches for only once the obvious ones have been refuted.
    static func staticRank(of action: GameAction, in state: GameState) -> Int {
        switch action {
        case .restoreBlender:
            return -60
        case .move(let move):
            return staticRank(of: move, in: state)
        }
    }

    private static func staticRank(of move: Move, in state: GameState) -> Int {
        guard let piece = state.piece(with: move.pieceID) else { return 0 }

        if piece.isFruit {
            if move.destination.row == piece.owner.deliveryRow {
                return 1_000 + piece.kind.deliveryValue * 10
            }
            let joined = state.boardWrap.wrapsRows
            let before = Board.separation(move.origin.row, piece.owner.deliveryRow, joined: joined)
            let after = Board.separation(move.destination.row, piece.owner.deliveryRow, joined: joined)
            return (before - after) * piece.kind.baseValue * 4
        }

        guard let occupant = state.piece(at: move.destination) else { return 1 }
        if occupant.isFruit {
            return occupant.owner == piece.owner
                ? -800 - occupant.kind.baseValue * 10
                : 800 + occupant.kind.baseValue * 10
        }
        // Destroying their blender is the strongest thing a blender can do; wrecking
        // your own is the weakest.
        return occupant.owner == piece.owner ? -900 : 900
    }
}
