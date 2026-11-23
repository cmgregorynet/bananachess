package com.charlesbarton.bananachess.engine.setup

import com.charlesbarton.bananachess.engine.model.Board
import com.charlesbarton.bananachess.engine.model.BoardSetup
import com.charlesbarton.bananachess.engine.model.BoardWrap
import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.Piece
import com.charlesbarton.bananachess.engine.model.PieceId
import com.charlesbarton.bananachess.engine.model.PieceKind
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.model.Position
import com.charlesbarton.bananachess.engine.model.RuleSet
import com.charlesbarton.bananachess.engine.random.SplitMix64
import com.charlesbarton.bananachess.engine.random.shuffled
import kotlin.random.Random
import kotlin.random.nextULong

/**
 * The locked opening position. Tuning constants live here so playtest changes never
 * require touching the rules.
 */
object GameSetup {
    /**
     * Banana, Strawberry, Banana, Pineapple, Strawberry, Pineapple, read from each
     * player's own left-hand side, so both openings are mirror images.
     *
     * The asymmetry is deliberate: a row that reads the same forwards and backwards
     * leaves every fruit facing one of equal value, and self-play deadlocks.
     */
    val fruitOrder: List<PieceKind> =
        listOf(
            PieceKind.Banana,
            PieceKind.Strawberry,
            PieceKind.Banana,
            PieceKind.Pineapple,
            PieceKind.Strawberry,
            PieceKind.Pineapple,
        )

    /**
     * Columns of each player's back row that hold a blender. These sit on opposite
     * square colours, so a player's two blenders between them cover the whole board.
     */
    val blenderHomeColumns: List<Int> = listOf(1, 4)

    /** How many fruit each player starts with: the Blenderama target. */
    val fruitCount: Int get() = fruitOrder.size

    /** How many bananas each player starts with. */
    val bananaCount: Int get() = fruitOrder.count { it == PieceKind.Banana }

    /**
     * Cumulative banana deliveries that win a wildcard match outright. Deliberately
     * more than a player owns: bananas recycle, so it takes at least one round trip.
     */
    const val BANANA_DELIVERIES_TO_WIN: Int = 3

    fun pieceId(
        player: PlayerId,
        kind: String,
        index: Int,
    ): PieceId = PieceId("${player.rawValue}.$kind.$index")

    /**
     * Builds an opening position.
     *
     * [seed] only matters to [BoardSetup.Random], and only for reproducibility: pass one
     * to get the same scattered board twice, leave it off and the board is genuinely
     * random. The layout travels inside the state, so an online opponent receives the
     * board itself rather than a seed to re-roll.
     */
    fun newGame(
        startingPlayer: PlayerId = PlayerId.One,
        ruleSet: RuleSet = RuleSet.Standard,
        boardSetup: BoardSetup = BoardSetup.Standard,
        boardWrap: BoardWrap = BoardWrap.Flat,
        blenderBattles: Boolean = false,
        seed: ULong? = null,
    ): GameState {
        val pieces =
            when (boardSetup) {
                BoardSetup.Standard -> rowPieces(fruitRow = PlayerId::fruitRow, blenderRow = PlayerId::backRow)
                BoardSetup.Blendy -> rowPieces(fruitRow = PlayerId::backRow, blenderRow = PlayerId::fruitRow)
                BoardSetup.Random -> randomPieces(seed ?: Random.nextULong())
            }
        return GameState(
            pieces = pieces,
            activePlayer = startingPlayer,
            ruleSet = ruleSet,
            boardSetup = boardSetup,
            boardWrap = boardWrap,
            blenderBattles = blenderBattles,
        )
    }

    /** The two ordered arrangements, which differ only in which row each kind of piece stands on. */
    private fun rowPieces(
        fruitRow: (PlayerId) -> Int,
        blenderRow: (PlayerId) -> Int,
    ): List<Piece> =
        deal { player ->
            val fruit = fruitOrder.indices.map { Position(fruitRow(player), player.columnFromOwnLeft(it)) }
            val blenders = blenderHomeColumns.map { Position(blenderRow(player), it) }
            fruit to blenders
        }

    /**
     * Every piece on a square of its own, drawn from a shuffled board.
     *
     * One placement rule: **no fruit starts on its own delivery row.** A fruit that
     * begins there has a free delivery on turn one. Nothing else is constrained.
     */
    private fun randomPieces(seed: ULong): List<Piece> {
        val pool = Board.allPositions.shuffled(SplitMix64(seed)).toMutableList()

        fun take(isAllowed: (Position) -> Boolean): Position {
            // 36 squares, 16 pieces, and the rule excludes only 6 of them, so one always matches.
            val index = pool.indexOfFirst(isAllowed)
            check(index >= 0) { "no square left to deal" }
            return pool.removeAt(index)
        }

        return deal { player ->
            val fruit = fruitOrder.map { take { it.row != player.deliveryRow } }
            val blenders = blenderHomeColumns.map { take { true } }
            fruit to blenders
        }
    }

    /**
     * Builds both players' pieces, player one first, from each player's fruit squares
     * (in [fruitOrder]) and then blender squares. Every piece starts on its home.
     */
    private fun deal(squares: (PlayerId) -> Pair<List<Position>, List<Position>>): List<Piece> =
        PlayerId.entries.flatMap { player ->
            val (fruit, blenders) = squares(player)
            fruit.mapIndexed { index, square ->
                Piece(pieceId(player, "fruit", index), player, fruitOrder[index], position = square, home = square)
            } +
                blenders.mapIndexed { index, square ->
                    Piece(
                        pieceId(player, "blender", index),
                        player,
                        PieceKind.Blender,
                        position = square,
                        home = square,
                    )
                }
        }
}
