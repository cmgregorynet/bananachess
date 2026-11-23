package com.charlesbarton.bananachess.art

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.charlesbarton.bananachess.engine.model.PieceKind

/**
 * The art chokepoint: if a painted sprite has been dropped into `res/drawable` under the expected
 * name it is used; otherwise the vector artwork draws the piece. New art can therefore ship as a
 * file drop, and art never blocks the build.
 */
object ArtCatalog {
    /** `piece_<kind>_<p1|p2>`, the same names as the iOS asset catalog. */
    fun spriteName(
        kind: PieceKind,
        seat: PlayerSeat,
    ): String {
        val player =
            when (seat) {
                PlayerSeat.Bottom -> "p1"
                PlayerSeat.Top -> "p2"
            }
        return "piece_${kind.rawValue}_$player"
    }

    /** The drawable for a piece, or null when none has been added and the vectors should draw it. */
    @DrawableRes
    fun spriteId(
        context: Context,
        kind: PieceKind,
        seat: PlayerSeat,
    ): Int? = drawableId(context, spriteName(kind, seat))

    /** The painted sprite for a piece, or null when the vectors should draw it. */
    @Composable
    fun sprite(
        kind: PieceKind,
        seat: PlayerSeat,
    ): Painter? {
        val context = LocalContext.current
        val id = remember(context, kind, seat) { spriteId(context, kind, seat) }
        return id?.let { painterResource(it) }
    }

    // Looked up by name on purpose: the sprites are optional, so there is no R.drawable constant
    // to refer to until one is added.
    @SuppressLint("DiscouragedApi")
    @DrawableRes
    internal fun drawableId(
        context: Context,
        name: String,
    ): Int? = context.resources.getIdentifier(name, "drawable", context.packageName).takeIf { it != 0 }
}
