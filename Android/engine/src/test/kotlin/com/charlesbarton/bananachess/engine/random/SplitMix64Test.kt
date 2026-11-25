package com.charlesbarton.bananachess.engine.random

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Expected values were produced by the TypeScript port (`Web/src/engine/random.ts`), which passes the fixture. */
class SplitMix64Test {
    private fun draws(
        seed: ULong,
        count: Int = 4,
    ): List<ULong> = SplitMix64(seed).let { generator -> List(count) { generator.next() } }

    private fun bounded(
        seed: ULong,
        upperBound: ULong,
    ): List<ULong> = SplitMix64(seed).let { generator -> List(5) { generator.nextBelow(upperBound) } }

    @Test
    fun `next matches the reference sequence`() {
        assertEquals(
            listOf(16294208416658607535uL, 7960286522194355700uL, 487617019471545679uL, 17909611376780542444uL),
            draws(0uL),
        )
        assertEquals(
            listOf(10451216379200822465uL, 13757245211066428519uL, 17911839290282890590uL, 8196980753821780235uL),
            draws(1uL),
        )
        assertEquals(
            listOf(13679457532755275413uL, 2949826092126892291uL, 5139283748462763858uL, 6349198060258255764uL),
            draws(42uL),
        )
        assertEquals(
            listOf(5395234354446855067uL, 16021672434157553954uL, 153047824787635229uL, 8387618351419058064uL),
            draws(0xDEADBEEFuL),
        )
    }

    @Test
    fun `next wraps from the largest seed`() {
        assertEquals(
            listOf(16490336266968443936uL, 16834447057089888969uL, 4048727598324417001uL, 7862637804313477842uL),
            draws(ULong.MAX_VALUE),
        )
    }

    @Test
    fun `nextBelow matches the reference for small bounds`() {
        assertEquals(listOf<ULong>(20u, 26u, 34u, 15u, 15u), bounded(1uL, 36uL))
        assertEquals(listOf<ULong>(2u, 0u, 5u, 3u, 2u), bounded(7uL, 6uL))
        assertEquals(
            listOf<ULong>(682362739u, 750694898u, 265322442u, 784813697u, 262553423u),
            bounded(9uL, 1_000_000_007uL),
        )
    }

    @Test
    fun `nextBelow one is always zero`() {
        assertEquals(List(5) { 0uL }, bounded(42uL, 1uL))
    }

    @Test
    fun `nextBelow matches the reference for bounds above the signed range`() {
        assertEquals(
            listOf(
                1474913046063446145uL,
                8007990562831494531uL,
                2014432356388812462uL,
                7384525663493887954uL,
                3135310438806241002uL,
            ),
            bounded(42uL, 9223372036854775809uL),
        )
        assertEquals(
            listOf(
                2092789425003139052uL,
                12918135221727111560uL,
                11307387092600937728uL,
                1344154044715485646uL,
                3992596847233833365uL,
            ),
            bounded(3uL, ULong.MAX_VALUE),
        )
    }

    @Test
    fun `nextBelow redraws when the first draw falls in the rejected band`() {
        // For this bound almost half of all draws are rejected. These seeds reject at least once.
        val upperBound = 9223372036854775809uL
        val expected = mapOf(0uL to 243808509735772839uL, 1uL to 8955919645141445295uL, 4uL to 8231000348891568152uL)
        for ((seed, value) in expected) {
            val generator = SplitMix64(seed)
            assertEquals(value, generator.nextBelow(upperBound), "seed $seed")

            val singleDraw = SplitMix64(seed).apply { next() }
            assertNotEquals(singleDraw.next(), generator.next(), "seed $seed should have consumed a redraw")
        }
    }

    @Test
    fun `nextBelow stays below its bound`() {
        val generator = SplitMix64(2026uL)
        for (upperBound in listOf(1uL, 2uL, 3uL, 6uL, 36uL, 1000uL)) {
            repeat(200) { assertTrue(generator.nextBelow(upperBound) < upperBound) }
        }
    }

    @Test
    fun `nextBelow refuses a zero bound`() {
        assertFailsWith<IllegalArgumentException> { SplitMix64(1uL).nextBelow(0uL) }
    }

    @Test
    fun `shuffled matches the reference order`() {
        val squares = List(36) { it }
        assertEquals(
            listOf(
                20,
                27,
                35,
                17,
                18,
                28,
                32,
                22,
                15,
                30,
                0,
                26,
                7,
                25,
                23,
                4,
                5,
                6,
                9,
                34,
                21,
                12,
                16,
                24,
                1,
                13,
                31,
                33,
                10,
                2,
                14,
                8,
                11,
                19,
                3,
                29,
            ),
            squares.shuffled(SplitMix64(1uL)),
        )
        assertEquals(
            listOf(
                21,
                27,
                22,
                28,
                13,
                15,
                1,
                3,
                5,
                7,
                18,
                0,
                25,
                11,
                34,
                19,
                20,
                23,
                24,
                2,
                16,
                9,
                6,
                8,
                14,
                33,
                31,
                32,
                4,
                26,
                35,
                10,
                12,
                29,
                30,
                17,
            ),
            squares.shuffled(SplitMix64(2uL)),
        )
        assertEquals(listOf(3, 7, 0, 2, 5, 6, 9, 8, 1, 4), List(10) { it }.shuffled(SplitMix64(5uL)))
    }

    @Test
    fun `shuffled leaves the receiver alone and keeps every element`() {
        val original = List(36) { it }
        val snapshot = original.toList()

        val shuffled = original.shuffled(SplitMix64(99uL))

        assertEquals(snapshot, original)
        assertEquals(snapshot, shuffled.sorted())
    }

    @Test
    fun `shuffled draws nothing for lists of zero or one`() {
        val generator = SplitMix64(7uL)

        assertEquals(emptyList(), emptyList<Int>().shuffled(generator))
        assertEquals(listOf("only"), listOf("only").shuffled(generator))

        assertEquals(SplitMix64(7uL).next(), generator.next())
    }
}
