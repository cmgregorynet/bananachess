package com.charlesbarton.bananachess.engine.model

enum class PlayerId(
    override val rawValue: String,
) : RawValued {
    One("one"),
    Two("two"),
    ;

    val opponent: PlayerId get() = if (this == One) Two else One

    /** The row this player's blenders call home and their fruit start in front of. */
    val backRow: Int get() = if (this == One) Board.SIZE - 1 else 0

    /** Where this player's fruit begins the match. */
    val fruitRow: Int get() = if (this == One) Board.SIZE - 2 else 1

    /** Reaching any square on this row delivers a fruit for double its base value. */
    val deliveryRow: Int get() = opponent.backRow

    /** Forward is toward the delivery edge. Used by the CPU's advancement term. */
    val forwardRowDelta: Int get() = if (this == One) -1 else 1

    /**
     * Column index of the player's own left-hand file, used to lay out the opening
     * fruit order identically from each player's point of view.
     */
    internal fun columnFromOwnLeft(index: Int): Int = if (this == One) index else Board.SIZE - 1 - index
}
