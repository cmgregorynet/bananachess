package com.charlesbarton.bananachess.engine.random

/**
 * SplitMix64, with Swift's bounded draw and shuffle on top of it.
 *
 * This exists to reproduce the Swift engine draw for draw. Any correct shuffle deals a
 * *fair* board; only this one deals the *same* board from the same seed.
 */
class SplitMix64(
    seed: ULong,
) {
    private var state: ULong = seed

    fun next(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }

    /**
     * A uniform value in `0 until upperBound`: Swift's `next(upperBound:)`, which is
     * Lemire's method. Plain modulo is also unbiased but consumes the generator
     * differently, and so deals a different board.
     */
    fun nextBelow(upperBound: ULong): ULong {
        require(upperBound != 0uL) { "upperBound must not be zero" }
        var random = next()
        var low = random * upperBound
        if (low < upperBound) {
            val threshold = (0uL - upperBound) % upperBound
            while (low < threshold) {
                random = next()
                low = random * upperBound
            }
        }
        return multiplyHigh(random, upperBound)
    }
}

/**
 * Swift's `shuffled(using:)`: a forward Fisher-Yates, which is not the textbook
 * direction. The receiver is left untouched.
 */
fun <T> List<T>.shuffled(generator: SplitMix64): List<T> {
    val result = toMutableList()
    var amount = result.size
    var current = 0
    while (amount > 1) {
        val target = current + generator.nextBelow(amount.toULong()).toInt()
        val held = result[current]
        result[current] = result[target]
        result[target] = held
        amount -= 1
        current += 1
    }
    return result
}

/** The high 64 bits of the 128-bit product. `Math.unsignedMultiplyHigh` needs a newer JDK than we target. */
private fun multiplyHigh(
    a: ULong,
    b: ULong,
): ULong {
    val mask = 0xFFFFFFFFuL
    val aLow = a and mask
    val aHigh = a shr 32
    val bLow = b and mask
    val bHigh = b shr 32
    val lowLow = aLow * bLow
    val highLow = aHigh * bLow
    val lowHigh = aLow * bHigh
    val highHigh = aHigh * bHigh
    val carry = ((lowLow shr 32) + (highLow and mask) + (lowHigh and mask)) shr 32
    return highHigh + (highLow shr 32) + (lowHigh shr 32) + carry
}
