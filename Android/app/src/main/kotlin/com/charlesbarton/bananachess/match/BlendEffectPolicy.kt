package com.charlesbarton.bananachess.match

import kotlin.random.Random

/**
 * How often a blend throws pulp past the board. Injected rather than reached for, so a
 * preview or a UI test can force the splatter on or off instead of rolling dice.
 */
class BlendEffectPolicy(
    val splatterChance: Double = 0.3,
    val roll: () -> Double = { Random.nextDouble() },
) {
    companion object {
        /** Roughly one blend in three. */
        val Standard = BlendEffectPolicy()
        val Always = BlendEffectPolicy(splatterChance = 1.0, roll = { 0.0 })
        val Never = BlendEffectPolicy(splatterChance = 0.0, roll = { 1.0 })
    }
}
