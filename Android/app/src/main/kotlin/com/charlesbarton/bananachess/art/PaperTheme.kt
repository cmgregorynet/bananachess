package com.charlesbarton.bananachess.art

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The one theme of the app: light paper, ink text, the platform serif. It never reads the system
 * dark setting, because the game has no dark mode.
 */
@Composable
fun PaperTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PaperColorScheme, typography = PaperTypography) {
        CompositionLocalProvider(LocalContentColor provides Palette.ink, content = content)
    }
}

// Every role is set from the palette so that no Material default (purple, or the black scrim)
// can reach the screen.
internal val PaperColorScheme: ColorScheme =
    lightColorScheme(
        primary = Palette.amber,
        onPrimary = Palette.ink,
        primaryContainer = Palette.amber.copy(alpha = 0.35f).compositeOver(Palette.cream),
        onPrimaryContainer = Palette.ink,
        inversePrimary = Palette.amber,
        secondary = Palette.sage,
        onSecondary = Palette.cream,
        secondaryContainer = Palette.driftwood,
        onSecondaryContainer = Palette.ink,
        tertiary = Palette.indigoDeep,
        onTertiary = Palette.cream,
        tertiaryContainer = Palette.topPanelTint,
        onTertiaryContainer = Palette.ink,
        background = Palette.warmPaper,
        onBackground = Palette.ink,
        surface = Palette.cream,
        onSurface = Palette.ink,
        surfaceVariant = Palette.driftwood,
        onSurfaceVariant = Palette.ink,
        surfaceTint = Palette.cream,
        inverseSurface = Palette.ink,
        inverseOnSurface = Palette.cream,
        error = Palette.strawberryWash,
        onError = Palette.ink,
        errorContainer = Palette.strawberryWash.copy(alpha = 0.35f).compositeOver(Palette.cream),
        onErrorContainer = Palette.ink,
        outline = Palette.inkSoft,
        outlineVariant = Palette.inkFaint,
        scrim = Palette.ink,
        surfaceBright = Palette.cream,
        surfaceDim = Palette.squareDark,
        surfaceContainerLowest = Palette.cream,
        surfaceContainerLow = Palette.cream,
        surfaceContainer = Palette.squareLight,
        surfaceContainerHigh = Palette.driftwood,
        surfaceContainerHighest = Palette.warmPaper,
    )

private fun TextStyle.serif(): TextStyle = copy(fontFamily = FontFamily.Serif)

// Material's type scale (all sizes in sp, so text follows the user's font size) in the platform
// serif. labelLarge is the button face: the iOS callout size at medium weight.
internal val PaperTypography: Typography =
    Typography().run {
        Typography(
            displayLarge = displayLarge.serif(),
            displayMedium = displayMedium.serif(),
            displaySmall = displaySmall.serif(),
            headlineLarge = headlineLarge.serif(),
            headlineMedium = headlineMedium.serif(),
            headlineSmall = headlineSmall.serif(),
            titleLarge = titleLarge.serif(),
            titleMedium = titleMedium.serif(),
            titleSmall = titleSmall.serif(),
            bodyLarge = bodyLarge.serif(),
            bodyMedium = bodyMedium.serif(),
            bodySmall = bodySmall.serif(),
            labelLarge = labelLarge.serif().copy(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
            labelMedium = labelMedium.serif(),
            labelSmall = labelSmall.serif(),
        )
    }

@Preview(widthDp = 320)
@Composable
private fun PaperThemePreview() {
    PaperTheme {
        PaperBackground {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Banana Chess", style = MaterialTheme.typography.headlineLarge)
                Text(text = "Race the fruit. Mind the blenders.", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Share one phone and take turns.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.inkSoft,
                )
            }
        }
    }
}
