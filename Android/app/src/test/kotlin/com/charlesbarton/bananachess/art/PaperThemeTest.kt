package com.charlesbarton.bananachess.art

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnitType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PaperThemeTest {
    @Test
    fun `the colour scheme is paper and ink with amber as the accent`() {
        assertEquals(Palette.amber, PaperColorScheme.primary)
        assertEquals(Palette.warmPaper, PaperColorScheme.background)
        assertEquals(Palette.cream, PaperColorScheme.surface)
        assertEquals(Palette.ink, PaperColorScheme.onBackground)
        assertEquals(Palette.ink, PaperColorScheme.onSurface)
    }

    @Test
    fun `no role falls back to black`() {
        val roles =
            with(PaperColorScheme) {
                listOf(scrim, inverseSurface, onPrimary, onSecondary, onTertiary, onError, outline, onSurfaceVariant)
            }
        for (role in roles) {
            assertNotEquals(0x000000, role.toArgb() and 0xFFFFFF)
            assertNotEquals(Color.Unspecified, role)
        }
    }

    @Test
    fun `every text style is serif and sized in sp`() {
        val styles =
            with(PaperTypography) {
                listOf(
                    displayLarge,
                    displayMedium,
                    displaySmall,
                    headlineLarge,
                    headlineMedium,
                    headlineSmall,
                    titleLarge,
                    titleMedium,
                    titleSmall,
                    bodyLarge,
                    bodyMedium,
                    bodySmall,
                    labelLarge,
                    labelMedium,
                    labelSmall,
                )
            }
        for (style in styles) {
            assertEquals(FontFamily.Serif, style.fontFamily)
            assertTrue(style.fontSize.type == TextUnitType.Sp)
        }
    }
}
