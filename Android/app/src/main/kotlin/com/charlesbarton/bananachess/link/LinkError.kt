package com.charlesbarton.bananachess.link

import com.charlesbarton.bananachess.engine.online.MatchCodecError
import com.charlesbarton.bananachess.engine.online.MatchLinkError

/**
 * Why a match link could not be opened. [message] is written for the player who tapped it, so
 * the engine's wording, which speaks of payloads and updates, is mapped onto these.
 */
sealed class LinkError(
    override val message: String,
) : Exception(message) {
    data object NotAMatchLink : LinkError("That link isn't a Banana Chess match.")

    data object Damaged : LinkError("That match link is damaged and can't be read.")

    data object FromNewerVersion : LinkError("That match was sent from a newer version of Banana Chess.")

    data object FromOlderVersion :
        LinkError("That match was sent from an older version of Banana Chess and can't be opened.")

    /** The phone already holds this match at the same move or a later one. */
    data object Stale : LinkError("That link is older than the match on this phone.")

    companion object {
        /** The player's version of an engine error from reading a link. */
        fun of(error: MatchLinkError): LinkError =
            when (error) {
                MatchLinkError.NotAMatchLink -> NotAMatchLink
                MatchLinkError.Unreadable -> Damaged
                is MatchLinkError.UnsupportedFormat -> FromNewerVersion
            }

        fun of(error: MatchCodecError): LinkError =
            when (error) {
                MatchCodecError.Malformed -> {
                    Damaged
                }

                is MatchCodecError.UnsupportedSchema -> {
                    FromNewerVersion
                }

                is MatchCodecError.OutdatedSchema -> {
                    FromOlderVersion
                }

                // The rules version alone can differ in either direction.
                is MatchCodecError.IncompatibleRules -> {
                    if (error.found > error.supported) FromNewerVersion else FromOlderVersion
                }
            }
    }
}
