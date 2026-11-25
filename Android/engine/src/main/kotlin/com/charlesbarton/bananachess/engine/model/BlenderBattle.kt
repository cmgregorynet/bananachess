package com.charlesbarton.bananachess.engine.model

/**
 * The full arithmetic of one blender battle, kept so the board can show its working.
 *
 * A battle is the optional alternative to a blender simply destroying whatever it runs
 * into. Both sides count **half** the value of the fruit they still have standing on the
 * board (blended fruit is gone and delivered fruit has left, so neither counts), the
 * attacker adds one for going in, and both roll a die.
 */
data class BattleReport(
    val attacker: PlayerId,
    val attackerFruit: Int,
    val attackerBonus: Int,
    val attackerRoll: Int,
    val defenderFruit: Int,
    val defenderRoll: Int,
) {
    val defender: PlayerId get() = attacker.opponent
    val attackerTotal: Int get() = attackerFruit + attackerBonus + attackerRoll
    val defenderTotal: Int get() = defenderFruit + defenderRoll

    /**
     * A tie goes to the defender: the attacker has to actually beat the total. A battle
     * has to end with exactly one blender gone, because the two cannot share the square.
     */
    val attackerWins: Boolean get() = attackerTotal > defenderTotal

    val winner: PlayerId get() = if (attackerWins) attacker else defender
}

/** How a blender battle is settled. */
enum class BattleResolution {
    /**
     * Two real dice, drawn deterministically from the position so that both devices in
     * an online match compute the same battle from the same move.
     */
    Dice,

    /**
     * No dice: both sides roll the average. Used by the CPU's search only.
     *
     * The dice are a pure function of the position, so a searching CPU could read the
     * roll before deciding whether to attack, and would then only ever pick battles it
     * had already won. Searching with the average keeps the CPU honest.
     */
    Expected,
}
