package com.charlesbarton.bananachess.engine.model

/**
 * Shared by the two board dimensions so the start screen can offer both through one
 * control rather than two near-identical ones.
 */
interface BoardOption : RawValued {
    val displayName: String
    val blurb: String
}

/**
 * How the pieces are arranged when a match begins. Orthogonal to [RuleSet]: any board
 * can be played under either set of rules.
 *
 * Nothing in the rules reads it after setup, because the arrangement is already baked
 * into the pieces by then. It is carried in the state so the board can be *named*, not
 * so it can be re-derived.
 */
enum class BoardSetup(
    override val rawValue: String,
    override val displayName: String,
    override val blurb: String,
) : BoardOption {
    /** Blenders on the back row, fruit in front of them. The original arrangement. */
    Standard("standard", "Standard", "Blenders behind, fruit in front."),

    /** The two rows swapped: fruit on the back row, blenders in front of it. */
    Blendy("blendy", "Blendy", "Swapped: fruit behind, blenders in front."),

    /** Every piece placed at random, subject to one rule: no fruit starts on its own delivery row. */
    Random("random", "Random", "Everything scattered. A different board every time."),
}
