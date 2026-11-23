package com.charlesbarton.bananachess.art

import androidx.compose.ui.graphics.Color

/**
 * The locked palette. Nothing in the app names a colour that is not in here, and nothing here is
 * saturated or pure black: the darkest value is the warm ink.
 */
object Palette {
    // Papers and woods
    val warmPaper = Color(0xFFE7D8D0)
    val cream = Color(0xFFF8E2D5)
    val driftwood = Color(0xFFF1D7C5)
    val sand = Color(0xFFBDA99D)

    // Cools
    val fogWater = Color(0xFFB1B3B3)
    val slate = Color(0xFFBEB7BB)

    // Ink: the darkest value in the whole game
    val ink = Color(0xFF61524D)
    val inkSoft = ink.copy(alpha = 0.55f)
    val inkFaint = ink.copy(alpha = 0.22f)

    // Character sage
    val sage = Color(0xFF6E7A4E)
    val sageLight = Color(0xFF8A9469)

    /** The one accent. Selection, legal destinations, the thing to press, and nothing else. */
    val amber = Color(0xFFE8A24A)

    // Fruit washes: the wash says what a piece is; the tray underneath says whose it is.
    val bananaWash = Color(0xFFDFC77E)
    val strawberryWash = Color(0xFFC4776F)
    val pineappleWash = Color(0xFFD1AC63)
    val blenderGlass = Color(0xFFD5DAD6)

    // Board squares: two weights of the same warm paper
    val squareLight = Color(0xFFF4E4D8)
    val squareDark = Color(0xFFE0CCBE)

    // Ownership trays: warm clay against dusty indigo. Identity, not emphasis.
    val clayLight = Color(0xFFEFD3B6)
    val clayDeep = Color(0xFFCFA987)
    val indigoLight = Color(0xFF8398B5)
    val indigoDeep = Color(0xFF556C90)

    val topPanelTint = Color(0xFFD9E1EC)

    fun tray(seat: PlayerSeat): Color =
        when (seat) {
            PlayerSeat.Bottom -> clayLight
            PlayerSeat.Top -> indigoLight
        }

    fun trayShade(seat: PlayerSeat): Color =
        when (seat) {
            PlayerSeat.Bottom -> clayDeep
            PlayerSeat.Top -> indigoDeep
        }

    /** Panels stay light for both players; ownership there is carried by the tint and the name. */
    fun panelTint(seat: PlayerSeat): Color =
        when (seat) {
            PlayerSeat.Bottom -> driftwood
            PlayerSeat.Top -> topPanelTint
        }
}

/**
 * Which end of the board a player sits at. The engine's player id is about rules; this is about
 * the screen.
 */
enum class PlayerSeat {
    Bottom,
    Top,
}
