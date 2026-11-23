package com.charlesbarton.bananachess.art

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PaperGrainTest {
    @Test
    fun `the generator reproduces the reference sequence`() {
        // The first outputs of the iOS generator for the same seed, as 16-bit numerators.
        val random = GrainRandom()
        val numerators = List(4) { Math.round(random.next() * 65535.0) }
        assertEquals(listOf(53053L, 47776L, 5053L, 34572L), numerators)
    }

    @Test
    fun `two generations give the same pixels`() {
        assertContentEquals(PaperGrain.tilePixels(), PaperGrain.tilePixels())
        assertContentEquals(PaperGrain.tilePixels(scale = 3), PaperGrain.tilePixels(scale = 3))
    }

    @Test
    fun `the tile is opaque grey speckle on white`() {
        val pixels = PaperGrain.tilePixels()
        assertEquals(PaperGrain.TILE_SIDE * PaperGrain.TILE_SIDE, pixels.size)

        var speckled = 0
        for (pixel in pixels) {
            val red = (pixel shr 16) and 0xFF
            assertEquals(0xFF, pixel ushr 24, "alpha")
            assertEquals(red, (pixel shr 8) and 0xFF, "green")
            assertEquals(red, pixel and 0xFF, "blue")
            // Speckles are grey 0.35 at low alpha, so even where they pile up the paper stays light.
            assertTrue(red > 0x80, "a speckle is too dark: $red")
            if (red < 0xFF) speckled += 1
        }
        assertTrue(speckled > pixels.size / 10, "only $speckled pixels carry grain")
    }

    @Test
    fun `the tile scales with the pixel density`() {
        val side = PaperGrain.TILE_SIDE * 2
        assertEquals(side * side, PaperGrain.tilePixels(scale = 2).size)
        assertFailsWith<IllegalArgumentException> { PaperGrain.tilePixels(scale = 0) }
        assertFailsWith<IllegalArgumentException> { PaperGrain.tilePixels(scale = PaperGrain.MAX_SCALE + 1) }
    }
}
