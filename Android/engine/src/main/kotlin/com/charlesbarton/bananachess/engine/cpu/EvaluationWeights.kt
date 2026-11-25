package com.charlesbarton.bananachess.engine.cpu

/**
 * Every number the CPU cares about, in one place. These are playtest dials, not rules:
 * changing them must never change which moves are *legal*.
 *
 * The values are the Swift engine's, where the playtest history behind each one is recorded.
 */
data class EvaluationWeights(
    /** Points already banked are the only certain currency. */
    val score: Int = 100,
    /** A fruit still on the board is worth something to whoever can resolve it. */
    val fruitOnBoard: Int = 18,
    /** Per row of progress toward the delivery edge, scaled by the fruit's value. */
    val advancement: Int = 6,
    /** Own fruit standing on a square an enemy blender can reach next move. */
    val threatenedFruit: Int = -14,
    /** Enemy fruit this player's blenders can reach next move. */
    val blenderThreat: Int = 12,
    /**
     * A blender sitting on its own spawn with an enemy blender covering that square.
     * It cannot be captured while it stays and dies the moment it leaves, so buying one
     * back onto a camped spawn is three points for a piece that can never do anything.
     */
    val blenderOnCoveredSpawn: Int = -320,
    /** Squares a blender can legally move to: a cornered blender is a dead piece. */
    val blenderMobility: Int = 3,
    /** Own fruit parked on one of the player's own blender spawns: the blender comes back and eats it at a loss. */
    val fruitOnOwnBlenderHome: Int = -8,
    /**
     * A blender destroyed in combat. Priced just above the three points it costs to
     * restore one, so the CPU does buy blenders back but does not trade them forever.
     */
    val blenderDestroyed: Int = -250,
    /**
     * Per fruit still standing on the emptier side. The match ends when either player
     * runs out, so a player who is ahead wants it short and a player behind wants it
     * long. Signed by who is winning.
     */
    val clockPressure: Int = 7,
    /**
     * Wildcard rules. How much an outright win is worth when it is one move away,
     * divided by the square of how many moves away it actually is.
     */
    val alternateWinThreat: Int = 4_000,
    val smoothieProgress: Int = 22,
    val goldenPineapple: Int = 30,
)
