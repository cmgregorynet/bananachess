import Foundation

public enum CPUDifficulty: String, Codable, Sendable, CaseIterable, Identifiable {
    case easy
    case medium
    case hard

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .easy: "Easy"
        case .medium: "Medium"
        case .hard: "Hard"
        }
    }

    public var blurb: String {
        switch self {
        case .easy: "Moves without a plan. A good first game."
        case .medium: "Takes the point in front of it."
        case .hard: "Looks several moves ahead, and will trade."
        }
    }

    /// Search stops deepening past this. The hard cap below it is absolute.
    var softTimeBudget: Duration {
        switch self {
        case .easy: .milliseconds(50)
        case .medium: .milliseconds(300)
        case .hard: .milliseconds(1_500)
        }
    }

    var hardTimeCap: Duration {
        switch self {
        case .easy, .medium: .milliseconds(600)
        case .hard: .seconds(2)
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

    public init(difficulty: CPUDifficulty, seed: UInt64? = nil, weights: EvaluationWeights = EvaluationWeights()) {
        self.difficulty = difficulty
        self.seed = seed
        self.weights = weights
    }

    /// Convenience for callers that only care about moves.
    public func selectMove(in state: GameState) -> Move? {
        guard case .move(let move) = selectAction(in: state) else { return nil }
        return move
    }

    public func selectAction(in state: GameState) -> GameAction? {
        let moves = RulesEngine.legalActions(in: state)
        guard !moves.isEmpty else { return nil }

        switch difficulty {
        case .easy:
            return pickRandom(from: moves, in: state)
        case .medium:
            return pickBestOnePly(from: moves, in: state)
        case .hard:
            return pickBestSearch(from: moves, in: state)
        }
    }

    // MARK: - Easy

    private func pickRandom(from moves: [GameAction], in state: GameState) -> GameAction {
        guard seed != nil else { return moves.randomElement()! }
        var generator = generator(for: state)
        return moves.randomElement(using: &generator)!
    }

    // MARK: - Medium

    private func pickBestOnePly(from moves: [GameAction], in state: GameState) -> GameAction {
        let player = state.activePlayer
        var scored: [(move: GameAction, value: Int)] = []
        scored.reserveCapacity(moves.count)

        for move in moves {
            guard let transition = try? RulesEngine.apply(move, to: state, battles: .expected)
            else { continue }
            scored.append((move, Evaluation.score(transition.state, for: player, weights: weights)))
        }
        guard let best = scored.map(\.value).max() else { return moves[0] }
        let tied = scored.filter { $0.value == best }.map(\.move)
        return breakTie(among: tied, in: state)
    }

    // MARK: - Hard

    private func pickBestSearch(from moves: [GameAction], in state: GameState) -> GameAction {
        let player = state.activePlayer
        let clock = ContinuousClock()
        let start = clock.now
        let softDeadline = start + difficulty.softTimeBudget
        let hardDeadline = start + difficulty.hardTimeCap

        var search = Search(
            root: player,
            weights: weights,
            clock: clock,
            deadline: hardDeadline
        )

        let ordered = orderMoves(moves, in: state)
        var best = ordered[0]
        var depth = 2

        while depth <= Search.maximumDepth {
            if clock.now >= softDeadline { break }
            guard let result = search.bestMove(from: state, moves: ordered, depth: depth) else {
                // Ran out of time mid-depth: the partial result is untrustworthy, so
                // the last fully completed depth stands.
                break
            }
            best = result.move
            depth += 1
            // A forced win is found, not improved on.
            if abs(result.value) >= Evaluation.winScore { break }
        }
        return best
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
    static let maximumDepth = 8
    /// Checking a clock is not free; do it every this-many nodes.
    static let timeCheckInterval = 512

    let root: PlayerID
    let weights: EvaluationWeights
    let clock: ContinuousClock
    let deadline: ContinuousClock.Instant

    private var transpositions: [UInt64: Entry] = [:]
    private var nodes = 0
    private var timedOut = false

    struct Entry {
        let depth: Int
        let value: Int
        let bound: Bound
    }

    enum Bound {
        case exact
        case lower
        case upper
    }

    struct Result {
        let move: GameAction
        let value: Int
    }

    init(root: PlayerID, weights: EvaluationWeights, clock: ContinuousClock, deadline: ContinuousClock.Instant) {
        self.root = root
        self.weights = weights
        self.clock = clock
        self.deadline = deadline
    }

    mutating func bestMove(from state: GameState, moves: [GameAction], depth: Int) -> Result? {
        var alpha = -Evaluation.winScore * 2
        let beta = Evaluation.winScore * 2
        var best: Result?

        for move in moves {
            guard let transition = try? RulesEngine.apply(move, to: state, battles: .expected)
            else { continue }
            let value = negamaxed(transition.state, depth: depth - 1, alpha: alpha, beta: beta)
            if timedOut { return nil }
            if best == nil || value > best!.value {
                best = Result(move: move, value: value)
                alpha = max(alpha, value)
            }
        }
        return best
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
            return Evaluation.score(state, for: root, weights: weights)
        }

        let key = state.positionKey
        if let entry = transpositions[key], entry.depth >= depth {
            switch entry.bound {
            case .exact: return entry.value
            case .lower: if entry.value >= beta { return entry.value }
            case .upper: if entry.value <= alpha { return entry.value }
            }
        }

        let moves = RulesEngine.legalActions(in: state)
        guard !moves.isEmpty else {
            return Evaluation.score(state, for: root, weights: weights)
        }

        let maximizing = state.activePlayer == root
        var alpha = alpha
        var beta = beta
        var best = maximizing ? Int.min : Int.max

        for move in ordered(moves, in: state) {
            guard let transition = try? RulesEngine.apply(move, to: state, battles: .expected)
            else { continue }
            let value = negamaxed(transition.state, depth: depth - 1, alpha: alpha, beta: beta)
            if timedOut { return best == Int.min || best == Int.max ? 0 : best }

            if maximizing {
                best = max(best, value)
                alpha = max(alpha, best)
            } else {
                best = min(best, value)
                beta = min(beta, best)
            }
            if alpha >= beta { break }
        }

        let bound: Bound =
            if best <= alpha { .upper } else if best >= beta { .lower } else { .exact }
        transpositions[key] = Entry(depth: depth, value: best, bound: bound)
        return best
    }

    private func ordered(_ moves: [GameAction], in state: GameState) -> [GameAction] {
        moves
            .map { (move: $0, rank: Self.staticRank(of: $0, in: state)) }
            .sorted { ($0.rank, $0.move) > ($1.rank, $1.move) }
            .map(\.move)
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
