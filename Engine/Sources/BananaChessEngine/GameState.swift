public struct Scores: Hashable, Codable, Sendable {
    public var one: Int
    public var two: Int

    public init(one: Int = 0, two: Int = 0) {
        self.one = one
        self.two = two
    }

    public subscript(player: PlayerID) -> Int {
        get { player == .one ? one : two }
        set { if player == .one { one = newValue } else { two = newValue } }
    }

    /// Positive when `player` is ahead.
    public func margin(for player: PlayerID) -> Int { self[player] - self[player.opponent] }
}

public enum GameStatus: Hashable, Codable, Sendable {
    case inProgress
    case ended(Outcome)

    public var isOver: Bool { self != .inProgress }
}

/// The complete, self-contained match state. Everything the rules engine, the CPU, and
/// the online payload need — and nothing the UI merely wants.
public struct GameState: Hashable, Codable, Sendable {
    /// Always kept sorted by piece ID so encoding and hashing are canonical.
    public private(set) var pieces: [Piece]
    public var activePlayer: PlayerID
    public var scores: Scores
    public var status: GameStatus
    public var turnNumber: Int
    public var history: [GameAction]
    /// Fixed for the life of the match. Carried here so a saved game or an online
    /// payload always states which game it is.
    public var ruleSet: RuleSet
    /// Which opening arrangement this match was dealt. Nothing in the rules reads it —
    /// the arrangement is already in `pieces` — but a match should be able to say what
    /// it is, to the player and in a payload.
    public var boardSetup: BoardSetup
    /// Which edges of the board join up. Unlike `boardSetup` this is read on every turn:
    /// it decides which moves exist.
    public var boardWrap: BoardWrap
    /// Untouched unless `ruleSet` is `.wildcard`, but always present so the shape of a
    /// stored match never depends on which rules it is played under.
    public var wildcards: WildcardProgress

    public init(
        pieces: [Piece],
        activePlayer: PlayerID = .one,
        scores: Scores = Scores(),
        status: GameStatus = .inProgress,
        turnNumber: Int = 1,
        history: [GameAction] = [],
        ruleSet: RuleSet = .standard,
        boardSetup: BoardSetup = .standard,
        boardWrap: BoardWrap = .flat,
        wildcards: WildcardProgress = WildcardProgress()
    ) {
        self.pieces = pieces.sorted { $0.id < $1.id }
        self.activePlayer = activePlayer
        self.scores = scores
        self.status = status
        self.turnNumber = turnNumber
        self.history = history
        self.ruleSet = ruleSet
        self.boardSetup = boardSetup
        self.boardWrap = boardWrap
        self.wildcards = wildcards
    }

    // MARK: - Lookup

    public func piece(with id: PieceID) -> Piece? {
        pieces.first { $0.id == id }
    }

    public func piece(at position: Position) -> Piece? {
        pieces.first { $0.position == position }
    }

    public func pieces(ownedBy player: PlayerID) -> [Piece] {
        pieces.filter { $0.owner == player }
    }

    /// Every fruit the player still has, including a banana in transit between the far
    /// edge and its spawn — it is coming back, so it is still theirs.
    public func fruit(ownedBy player: PlayerID) -> [Piece] {
        pieces.filter { $0.owner == player && $0.isFruit }
    }

    /// Only the fruit actually standing on the board.
    public func onBoardFruit(ownedBy player: PlayerID) -> [Piece] {
        fruit(ownedBy: player).filter(\.isOnBoard)
    }

    /// Every blender spawn on the board, still marked once its blender has left it.
    /// Read from the pieces rather than from a constant, because where the spawns are
    /// depends on which `BoardSetup` the match was dealt.
    public var allSpawnSquares: Set<Position> {
        Set(pieces.filter(\.isBlender).compactMap(\.home))
    }

    public func pineapples(ownedBy player: PlayerID) -> [Piece] {
        pieces.filter { $0.owner == player && $0.kind == .pineapple }
    }

    /// A player's last pineapple, which is what makes it golden.
    ///
    /// Derived rather than recorded: the rule is "whenever there is one pineapple left it
    /// is golden", which is a fact about the position, not about how it came about. It
    /// does not matter whether the first one was blended, delivered, or never there —
    /// and there is no stored flag that can disagree with the board.
    public func goldenPineapple(ownedBy player: PlayerID) -> Piece? {
        guard ruleSet.usesWildcards else { return nil }
        let remaining = pineapples(ownedBy: player)
        return remaining.count == 1 ? remaining[0] : nil
    }

    public func isGolden(_ pieceID: PieceID) -> Bool {
        guard ruleSet.usesWildcards, let piece = piece(with: pieceID), piece.kind == .pineapple
        else { return false }
        return pineapples(ownedBy: piece.owner).count == 1
    }

    /// Which edges a given piece may cross.
    ///
    /// Blenders never cross one, whatever the board is set to: joined edges are a rule
    /// about the fruit's run, not about the machines chasing it. So a blender always sees
    /// walls, and a fruit sees whatever the match was set up with.
    public func edges(for piece: Piece) -> BoardWrap {
        piece.isBlender ? .flat : boardWrap
    }

    /// Every blender the player owns, including any currently off the board.
    public func blenders(ownedBy player: PlayerID) -> [Piece] {
        pieces.filter { $0.owner == player && $0.isBlender }
    }

    /// Only the blenders actually standing on the board — the ones that can move,
    /// threaten, or be run into this turn.
    public func activeBlenders(ownedBy player: PlayerID) -> [Piece] {
        blenders(ownedBy: player).filter(\.isOnBoard)
    }

    /// Turns until a smoothie victim comes back on its own; nil for a combat kill.
    public func turnsUntilRespawn(of piece: Piece) -> Int? {
        guard let respawnTurn = piece.respawnTurn else { return nil }
        return max(0, respawnTurn - turnNumber)
    }

    /// Blenders off the board — destroyed in combat, or blown up by a smoothie.
    public func destroyedBlenders(ownedBy player: PlayerID) -> [Piece] {
        blenders(ownedBy: player).filter(\.isDestroyed).sorted { $0.id < $1.id }
    }

    public var destroyedBlenders: [Piece] {
        pieces.filter(\.isDestroyed).sorted { $0.id < $1.id }
    }

    /// Delivered bananas queued to reappear once their spawn square clears.
    public func waitingBananas(ownedBy player: PlayerID) -> [Piece] {
        pieces.filter { $0.owner == player && $0.isWaitingToReturn }.sorted { $0.id < $1.id }
    }

    public var remainingFruit: [Piece] { pieces.filter(\.isFruit) }

    /// How the match finished, once it has.
    public var outcome: Outcome? {
        if case .ended(let outcome) = status { return outcome }
        return nil
    }

    /// Sum of base values still on the board for a player — the points they can still
    /// win or lose. Shown next to each score.
    public func fruitValueRemaining(for player: PlayerID) -> Int {
        fruit(ownedBy: player).reduce(0) { $0 + $1.kind.baseValue }
    }

    public var blenderHomes: [PieceID: Position] {
        pieces.reduce(into: [:]) { result, piece in
            if let home = piece.home { result[piece.id] = home }
        }
    }

    /// Deterministic FNV-1a digest of everything that makes two positions the same
    /// position. Unlike `hashValue` this is stable across processes and devices, which
    /// is what the CPU's transposition table and the seeded Easy CPU both need.
    public var positionKey: UInt64 {
        var hash: UInt64 = 0xCBF2_9CE4_8422_2325
        func mix(_ byte: UInt8) {
            hash ^= UInt64(byte)
            hash = hash &* 0x0000_0100_0000_01B3
        }
        func mix(_ value: Int) {
            withUnsafeBytes(of: Int64(value).littleEndian) { $0.forEach(mix) }
        }

        for piece in pieces {
            for byte in piece.id.rawValue.utf8 { mix(byte) }
            // An exploded blender is a different position from one standing anywhere,
            // and two blenders due back on different turns are different positions too.
            mix(piece.position?.row ?? -1)
            mix(piece.position?.column ?? -1)
            mix(piece.respawnTurn ?? 0)
        }
        mix(activePlayer == .one ? 1 : 2)
        mix(scores.one)
        mix(scores.two)
        return hash
    }

    // MARK: - Mutation (engine-internal; all rule enforcement happens in RulesEngine)

    mutating func setPosition(_ position: Position?, for id: PieceID) {
        guard let index = pieces.firstIndex(where: { $0.id == id }) else { return }
        pieces[index].position = position
    }

    mutating func setRespawnTurn(_ turn: Int?, for id: PieceID) {
        guard let index = pieces.firstIndex(where: { $0.id == id }) else { return }
        pieces[index].respawnTurn = turn
    }

    mutating func remove(_ id: PieceID) {
        pieces.removeAll { $0.id == id }
    }
}

// MARK: - Occupancy

/// Flat 6×6 lookup built once per rules operation. Sixteen linear scans per legal-move
/// query showed up in CPU search profiles; this does not.
struct Occupancy {
    private var squares: [PieceID?]

    init(_ state: GameState) {
        squares = Array(repeating: nil, count: Board.size * Board.size)
        for piece in state.pieces {
            guard let position = piece.position else { continue }
            squares[Occupancy.index(position)] = piece.id
        }
    }

    static func index(_ position: Position) -> Int {
        position.row * Board.size + position.column
    }

    subscript(position: Position) -> PieceID? {
        Board.contains(position) ? squares[Occupancy.index(position)] : nil
    }
}
