package com.charlesbarton.bananachess.art

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaletteTest {
    private val opaque =
        listOf(
            Triple("warmPaper", Palette.warmPaper, 0xE7D8D0),
            Triple("cream", Palette.cream, 0xF8E2D5),
            Triple("driftwood", Palette.driftwood, 0xF1D7C5),
            Triple("sand", Palette.sand, 0xBDA99D),
            Triple("fogWater", Palette.fogWater, 0xB1B3B3),
            Triple("slate", Palette.slate, 0xBEB7BB),
            Triple("ink", Palette.ink, 0x61524D),
            Triple("sage", Palette.sage, 0x6E7A4E),
            Triple("sageLight", Palette.sageLight, 0x8A9469),
            Triple("amber", Palette.amber, 0xE8A24A),
            Triple("bananaWash", Palette.bananaWash, 0xDFC77E),
            Triple("strawberryWash", Palette.strawberryWash, 0xC4776F),
            Triple("pineappleWash", Palette.pineappleWash, 0xD1AC63),
            Triple("blenderGlass", Palette.blenderGlass, 0xD5DAD6),
            Triple("squareLight", Palette.squareLight, 0xF4E4D8),
            Triple("squareDark", Palette.squareDark, 0xE0CCBE),
            Triple("clayLight", Palette.clayLight, 0xEFD3B6),
            Triple("clayDeep", Palette.clayDeep, 0xCFA987),
            Triple("indigoLight", Palette.indigoLight, 0x8398B5),
            Triple("indigoDeep", Palette.indigoDeep, 0x556C90),
            Triple("topPanelTint", Palette.topPanelTint, 0xD9E1EC),
        )

    @Test
    fun `every opaque colour equals its hex`() {
        for ((name, color, hex) in opaque) {
            assertEquals(0xFF000000.toInt() or hex, color.toArgb(), name)
        }
    }

    @Test
    fun `soft and faint inks are the ink at reduced alpha`() {
        assertEquals(Palette.ink.copy(alpha = 0.55f), Palette.inkSoft)
        assertEquals(Palette.ink.copy(alpha = 0.22f), Palette.inkFaint)
        assertEquals(0x61524D, Palette.inkSoft.toArgb() and 0xFFFFFF)
        assertEquals(0x61524D, Palette.inkFaint.toArgb() and 0xFFFFFF)
    }

    @Test
    fun `ink is the darkest value and nothing is pure black`() {
        val inkLuminance = luminance(Palette.ink)
        for ((name, color, _) in opaque) {
            assertTrue(luminance(color) >= inkLuminance, "$name is darker than ink")
        }
    }

    @Test
    fun `seats map to their trays and panel tints`() {
        assertEquals(Palette.driftwood, Palette.panelTint(PlayerSeat.Bottom))
        assertEquals(Palette.topPanelTint, Palette.panelTint(PlayerSeat.Top))
        assertEquals(Palette.clayLight, Palette.tray(PlayerSeat.Bottom))
        assertEquals(Palette.indigoLight, Palette.tray(PlayerSeat.Top))
        assertEquals(Palette.clayDeep, Palette.trayShade(PlayerSeat.Bottom))
        assertEquals(Palette.indigoDeep, Palette.trayShade(PlayerSeat.Top))
    }

    private fun luminance(color: Color): Float = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
}
