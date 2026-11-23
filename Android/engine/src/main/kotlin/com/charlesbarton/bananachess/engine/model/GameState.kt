package com.charlesbarton.bananachess.engine.model

import kotlin.math.max

data class Scores(
    val one: Int = 0,
    val two: Int = 0,
) {
    operator fun get(player: PlayerId): Int = if (player == PlayerId.One) one else two

    /** A copy with [player]'s score replaced. */
    fun updated(
        player: PlayerId,
        value: Int,
    ): Scores = if (player == PlayerId.One) copy(one = value) else copy(two = value)

    /** Positive when [player] is ahead. */
    fun margin(player: PlayerId): Int = this[player] - this[player.opponent]
}

sealed interface GameStatus {
    data object InProgress : GameStatus

    data class Ended(
        val outcome: Outcome,
    ) : GameStatus

    val isOver: Boolean get() = this != InProgress
}

/**
 * The complete, self-contained match state. Everything the rules engine, the CPU, and
 * the online payload need, and nothing the UI merely wants.
 *
 * Immutable. Build one with `GameState(pieces = ...)`, which sorts the pieces and seeds
 * [boardHistory]; change one with [updated] and the piece updaters, each of which
 * returns a new state. `copy` is private so neither invariant can be bypassed.
 */
@ConsistentCopyVisibility
data class GameState private constructor(
    /** Always sorted by piece id, so encoding and hashing are canonical. */
    val pieces: List<Piece>,
    val activePlayer: PlayerId,
    /** May go negative. */
    val scores: Scores,
    val status: GameStatus,
    val turnNumber: Int,
    val history: List<GameAction>,
    /** Fixed for the life of the match. */
    val ruleSet: RuleSet,
    /** Which opening arrangement this match was dealt. Nothing in the rules reads it. */
    val boardSetup: BoardSetup,
    /** Which edges of the board join up. Read on every turn: it decides which moves exist. */
    val boardWrap: BoardWrap,
    /**
     * Every arrangement this match has stood in, including the one on the board now.
     * A move that would make an arrangement happen for a third time is not allowed.
     */
    val boardHistory: List<ULong>,
    /** Whether a blender running into another fights for it rather than simply taking it. */
    val blenderBattles: Boolean,
    /** Untouched unless [ruleSet] is wildcard, but always present. */
    val wildcards: WildcardProgress,
) {
    companion object {
        /**
         * [boardHistory] defaults to the opening arrangement, so a freshly built state is
         * already self-consistent and no caller has to remember to seed it.
         */
        operator fun invoke(
            pieces: List<Piece>,
            activePlayer: PlayerId = PlayerId.One,
            scores: Scores = Scores(),
            status: GameStatus = GameStatus.InProgress,
            turnNumber: Int = 1,
            history: List<GameAction> = emptyList(),
            ruleSet: RuleSet = RuleSet.Standard,
            boardSetup: BoardSetup = BoardSetup.Standard,
            boardWrap: BoardWrap = BoardWrap.Flat,
            blenderBattles: Boolean = false,
            boardHistory: List<ULong>? = null,
            wildcards: WildcardProgress = WildcardProgress(),
        ): GameState {
            val sorted = pieces.sortedBy { it.id }
            return GameState(
                pieces = sorted,
                activePlayer = activePlayer,
                scores = scores,
                status = status,
                turnNumber = turnNumber,
                history = history.toList(),
                ruleSet = ruleSet,
                boardSetup = boardSetup,
                boardWrap = boardWrap,
                boardHistory = boardHistory?.toList() ?: listOf(boardKey(sorted, activePlayer)),
                blenderBattles = blenderBattles,
                wildcards = wildcards,
            )
        }

        private fun boardKey(
            pieces: List<Piece>,
            activePlayer: PlayerId,
        ): ULong {
            var hash = Fnv1a.OFFSET
            for (piece in pieces) {
                hash = Fnv1a.mix(hash, piece.position?.row ?: -1)
                hash = Fnv1a.mix(hash, piece.position?.column ?: -1)
                hash = Fnv1a.mix(hash, piece.respawnTurn ?: 0)
            }
            return Fnv1a.mix(hash, if (activePlayer == PlayerId.One) 1 else 2)
        }
    }

    // Lookup

    fun piece(id: PieceId): Piece? = pieces.firstOrNull { it.id == id }

    fun pieceAt(position: Position): Piece? = pieces.firstOrNull { it.position == position }

    fun pieces(player: PlayerId): List<Piece> = pieces.filter { it.owner == player }

    /**
     * Every fruit the player still has, including a banana in transit between the far
     * edge and its spawn: it is coming back, so it is still theirs.
     */
    fun fruit(player: PlayerId): List<Piece> = pieces.filter { it.owner == player && it.isFruit }

    /** Only the fruit actually standing on the board. */
    fun onBoardFruit(player: PlayerId): List<Piece> = pieces.filter { it.owner == player && it.isFruit && it.isOnBoard }

    /**
     * Every blender spawn on the board, still marked once its blender has left it.
     * Read from the pieces rather than from a constant, because where the spawns are
     * depends on which [BoardSetup] the match was dealt.
     */
    val allSpawnSquares: Set<Position> get() = pieces.filter { it.isBlender }.mapNotNullTo(mutableSetOf()) { it.home }

    /**
     * What a player brings to a blender battle: the value of the fruit they still have
     * standing. Blended fruit is gone, and delivered fruit has left the board, including
     * a wildcard banana waiting off-board to come back.
     */
    fun fruitStandingValue(player: PlayerId): Int = onBoardFruit(player).sumOf { it.kind.baseValue }

    /** What that fruit is actually worth in a battle: **half** its value, rounded down. */
    fun battleStrength(player: PlayerId): Int = fruitStandingValue(player) / 2

    fun pineapples(player: PlayerId): List<Piece> =
        pieces.filter { it.owner == player && it.kind == PieceKind.Pineapple }

    /**
     * A player's last pineapple, which is what makes it golden. Derived rather than
     * recorded: there is no stored flag that can disagree with the board.
     */
    fun goldenPineapple(player: PlayerId): Piece? {
        if (!ruleSet.usesWildcards) return null
        return pineapples(player).singleOrNull()
    }

    fun isGolden(pieceId: PieceId): Boolean {
        if (!ruleSet.usesWildcards) return false
        val piece = piece(pieceId) ?: return false
        return piece.kind == PieceKind.Pineapple && pineapples(piece.owner).size == 1
    }

    /**
     * Which edges a given piece may cross. Blenders never cross one, whatever the board
     * is set to, so a blender always sees walls and a fruit sees the match's setting.
     */
    fun edgesFor(piece: Piece): BoardWrap = if (piece.isBlender) BoardWrap.Flat else boardWrap

    /** Every blender the player owns, including any currently off the board. */
    fun blenders(player: PlayerId): List<Piece> = pieces.filter { it.owner == player && it.isBlender }

    /** Only the blenders actually standing on the board. */
    fun activeBlenders(player: PlayerId): List<Piece> = blenders(player).filter { it.isOnBoard }

    /** Turns until a smoothie victim comes back on its own; null for a combat kill. */
    fun turnsUntilRespawn(piece: Piece): Int? = piece.respawnTurn?.let { max(0, it - turnNumber) }

    /** Blenders off the board: destroyed in combat, or blown up by a smoothie. In id order. */
    fun destroyedBlenders(player: PlayerId): List<Piece> = blenders(player).filter { it.isDestroyed }

    val destroyedBlenders: List<Piece> get() = pieces.filter { it.isDestroyed }

    /** Delivered bananas queued to reappear once their spawn square clears. In id order. */
    fun waitingBananas(player: PlayerId): List<Piece> = pieces.filter { it.owner == player && it.isWaitingToReturn }

    val remainingFruit: List<Piece> get() = pieces.filter { it.isFruit }

    /** How the match finished, once it has. */
    val outcome: Outcome? get() = (status as? GameStatus.Ended)?.outcome

    /**
     * Sum of base values of the fruit a player still has: the points they can still
     * win or lose. Shown next to each score.
     */
    fun fruitValueRemaining(player: PlayerId): Int = fruit(player).sumOf { it.kind.baseValue }

    val blenderHomes: Map<PieceId, Position>
        get() = pieces.mapNotNull { piece -> piece.home?.let { piece.id to it } }.toMap()

    // Keys

    /**
     * Deterministic digest of the *arrangement* (where every piece stands and whose
     * turn it is) and nothing else.
     *
     * Deliberately excludes the scores, which is what separates this from [positionKey]:
     * a key that included the score would see a brand new position each lap of a loop
     * and never notice the repetition it exists to stop. Piece identity is not hashed
     * either: [pieces] is sorted by id, so the index already says which piece it is.
     */
    val boardKey: ULong get() = boardKey(pieces, activePlayer)

    /** How many times this exact arrangement has already stood on the board. */
    fun timesSeen(key: ULong): Int = boardHistory.count { it == key }

    /**
     * Deterministic FNV-1a digest of everything that makes two positions the same
     * position. Stable across processes and devices, which the CPU's transposition
     * table, the seeded Easy CPU and the battle dice all need.
     */
    val positionKey: ULong
        get() {
            var hash = Fnv1a.OFFSET
            for (piece in pieces) {
                for (byte in piece.id.rawValue.encodeToByteArray()) hash = Fnv1a.mix(hash, byte)
                hash = Fnv1a.mix(hash, piece.position?.row ?: -1)
                hash = Fnv1a.mix(hash, piece.position?.column ?: -1)
                hash = Fnv1a.mix(hash, piece.respawnTurn ?: 0)
            }
            hash = Fnv1a.mix(hash, if (activePlayer == PlayerId.One) 1 else 2)
            hash = Fnv1a.mix(hash, scores.one)
            return Fnv1a.mix(hash, scores.two)
        }

    // Updates. Each returns a new state; nothing here enforces rules.

    /**
     * A copy with the given fields replaced. Pieces and [boardHistory] are left out on
     * purpose: they change only through the updaters below, which keep them canonical.
     */
    fun updated(
        activePlayer: PlayerId = this.activePlayer,
        scores: Scores = this.scores,
        status: GameStatus = this.status,
        turnNumber: Int = this.turnNumber,
        history: List<GameAction> = this.history,
        ruleSet: RuleSet = this.ruleSet,
        boardSetup: BoardSetup = this.boardSetup,
        boardWrap: BoardWrap = this.boardWrap,
        blenderBattles: Boolean = this.blenderBattles,
        wildcards: WildcardProgress = this.wildcards,
    ): GameState =
        copy(
            activePlayer = activePlayer,
            scores = scores,
            status = status,
            turnNumber = turnNumber,
            history = if (history === this.history) history else history.toList(),
            ruleSet = ruleSet,
            boardSetup = boardSetup,
            boardWrap = boardWrap,
            blenderBattles = blenderBattles,
            wildcards = wildcards,
        )

    /** Moves a piece, or takes it off the board with null. Unknown ids change nothing. */
    internal fun withPosition(
        position: Position?,
        id: PieceId,
    ): GameState = copy(pieces = pieces.map { if (it.id == id) it.copy(position = position) else it })

    internal fun withRespawnTurn(
        turn: Int?,
        id: PieceId,
    ): GameState = copy(pieces = pieces.map { if (it.id == id) it.copy(respawnTurn = turn) else it })

    /** Removes a piece from the match altogether. */
    internal fun removing(id: PieceId): GameState = copy(pieces = pieces.filter { it.id != id })

    /** Appends the current [boardKey] to [boardHistory]. */
    internal fun recordingArrangement(): GameState = copy(boardHistory = boardHistory + boardKey)
}

/** FNV-1a 64. Integers are mixed as the 8 little-endian bytes of their signed 64-bit value. */
private object Fnv1a {
    const val OFFSET: ULong = 0xCBF29CE484222325uL
    private const val PRIME: ULong = 0x100000001B3uL

    fun mix(
        hash: ULong,
        byte: Byte,
    ): ULong = (hash xor (byte.toULong() and 0xFFuL)) * PRIME

    fun mix(
        hash: ULong,
        value: Int,
    ): ULong {
        val bits = value.toLong()
        var result = hash
        for (shift in 0 until Long.SIZE_BITS step Byte.SIZE_BITS) {
            result = (result xor ((bits ushr shift).toULong() and 0xFFuL)) * PRIME
        }
        return result
    }
}

/**
 * Flat 6×6 lookup built once per rules operation. Sixteen linear scans per legal-move
 * query showed up in CPU search profiles; this does not.
 */
internal class Occupancy(
    state: GameState,
) {
    private val squares: Array<PieceId?> = arrayOfNulls(Board.SIZE * Board.SIZE)

    init {
        for (piece in state.pieces) {
            val position = piece.position ?: continue
            squares[index(position)] = piece.id
        }
    }

    operator fun get(position: Position): PieceId? = if (Board.contains(position)) squares[index(position)] else null

    companion object {
        fun index(position: Position): Int = position.row * Board.SIZE + position.column
    }
}
