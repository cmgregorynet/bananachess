package com.charlesbarton.bananachess.engine.model

/**
 * Which edges of the board join up. Orthogonal to both [RuleSet] and [BoardSetup].
 *
 * This one *is* read by the rules: it changes which moves are legal on every turn.
 * It applies to **fruit only**. Blenders never cross an edge on any board; see
 * [GameState.edgesFor], which is the only place that distinction is made.
 */
enum class BoardWrap(
    override val rawValue: String,
    override val displayName: String,
    override val blurb: String,
) : BoardOption {
    /** Edges are walls. A piece at the edge simply has fewer moves. */
    Flat("flat", "Walls", "Edges are walls. Everything stops at them."),

    /** Left and right join. Step off one side and arrive at the other, same row. */
    Sides("sides", "Sides", "Fruit can step off the left or right edge and arrive at the other."),

    /** Every edge joins, rows as well as columns. */
    All("all", "All", "Fruit can cross any edge, top and bottom too. Blenders never can."),
    ;

    val wrapsColumns: Boolean get() = this != Flat
    val wrapsRows: Boolean get() = this == All
}
