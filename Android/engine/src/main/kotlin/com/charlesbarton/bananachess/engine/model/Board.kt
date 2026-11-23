package com.charlesbarton.bananachess.engine.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Fixed board geometry for the MVP ruleset. All values are tuning constants: the
 * engine never hard-codes 6 anywhere else.
 */
object Board {
    const val SIZE: Int = 6

    /** Every square, row-major. The random deal shuffles exactly this order. */
    val allPositions: List<Position> =
        (0 until SIZE).flatMap { row -> (0 until SIZE).map { column -> Position(row, column) } }

    fun contains(position: Position): Boolean = position.row in 0 until SIZE && position.column in 0 until SIZE

    /**
     * One step from [origin], with the board's edges applied. Returns null when the step
     * leaves the board and that edge is a wall.
     *
     * Every legal-move calculation in the engine and the CPU goes through here, so the
     * edges can never be right in one place and wrong in another.
     */
    fun step(
        origin: Position,
        rowDelta: Int,
        columnDelta: Int,
        wrap: BoardWrap,
    ): Position? {
        var row = origin.row + rowDelta
        var column = origin.column + columnDelta
        if (wrap.wrapsRows) row = wrapped(row)
        if (wrap.wrapsColumns) column = wrapped(column)
        val destination = Position(row, column)
        return if (contains(destination)) destination else null
    }

    /** Distance along one axis, the short way round when that axis joins up. */
    fun separation(
        a: Int,
        b: Int,
        joined: Boolean,
    ): Int {
        val direct = abs(a - b)
        return if (joined) min(direct, SIZE - direct) else direct
    }

    /**
     * How many blender moves separate two squares: Chebyshev, because a blender covers
     * a row and a column at once.
     *
     * No wrap parameter, deliberately: blenders never cross an edge on any board, so
     * their distances are always the walled ones.
     */
    fun blenderSteps(
        from: Position,
        to: Position,
    ): Int =
        max(
            separation(from.row, to.row, joined = false),
            separation(from.column, to.column, joined = false),
        )

    private fun wrapped(value: Int): Int = ((value % SIZE) + SIZE) % SIZE
}

/** Row/column coordinate. Row 0 is player two's back row; row 5 is player one's. */
data class Position(
    val row: Int,
    val column: Int,
) : Comparable<Position> {
    fun offset(
        rowDelta: Int,
        columnDelta: Int,
    ): Position = Position(row + rowDelta, column + columnDelta)

    /**
     * Alternating square colour. The two blender homes of a player sit on opposite
     * colours, which is what lets a player's blenders together reach every square.
     */
    val isDarkSquare: Boolean get() = (row + column) % 2 == 0

    override fun compareTo(other: Position): Int = compareValuesBy(this, other, Position::row, Position::column)

    override fun toString(): String = "r${row}c$column"
}

/** One step on the board, as a row and column delta. */
data class Offset(
    val row: Int,
    val column: Int,
)

internal object Direction {
    val orthogonal: List<Offset> = listOf(Offset(-1, 0), Offset(0, -1), Offset(0, 1), Offset(1, 0))

    val diagonal: List<Offset> = listOf(Offset(-1, -1), Offset(-1, 1), Offset(1, -1), Offset(1, 1))
}
