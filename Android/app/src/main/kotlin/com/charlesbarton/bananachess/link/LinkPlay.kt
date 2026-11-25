package com.charlesbarton.bananachess.link

import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.model.PlayerId
import com.charlesbarton.bananachess.engine.online.LastMove
import com.charlesbarton.bananachess.engine.online.MatchAuthority
import com.charlesbarton.bananachess.engine.online.MatchCodecError
import com.charlesbarton.bananachess.engine.online.MatchLink
import com.charlesbarton.bananachess.engine.online.MatchLinkError
import com.charlesbarton.bananachess.engine.online.MatchOptions
import com.charlesbarton.bananachess.engine.online.MatchPayload
import com.charlesbarton.bananachess.match.MatchMode
import com.charlesbarton.bananachess.match.MatchSnapshot
import com.charlesbarton.bananachess.match.MatchSource
import com.charlesbarton.bananachess.match.MatchStart

/**
 * Correspondence play: a link *is* the whole match. There is no server; each side makes a move
 * and sends the other a link, and the link's bytes are the engine's, the same ones iOS writes.
 *
 * A link's move number is always the length of its match's history, as on iOS, so the snapshot
 * this phone keeps of a link match says by itself how far that match has got.
 */
object LinkPlay {
    /**
     * A new invitation, dealt with [options]. The invitation is the first link, so the match is
     * dealt with the opponent to move and the inviter sitting second: there is something to
     * send straight away. Whoever invites picks the board; whoever accepts moves first.
     */
    fun invite(
        options: MatchOptions,
        matchId: String,
    ): MatchSource.ByLink {
        val payload = MatchPayload.newMatch(id = matchId, startingPlayer = PlayerId.One, options = options)
        return MatchSource.ByLink(MatchLink.url(payload), localPlayer = PlayerId.Two)
    }

    /**
     * The match a link someone sent should open, seated as whoever the match is waiting on.
     *
     * A finished match never hands the turn over, so its active player is whoever played the
     * last move: the phone opening it is the other side.
     *
     * @param saved the match this phone keeps, if any. A link to that same match is refused only
     *   when it is behind; the same link opened again opens the saved match. Unlike iOS, which
     *   refuses an equal link too and calls it older, which it is not.
     * @throws LinkError
     */
    fun open(
        link: String,
        saved: MatchSnapshot?,
    ): MatchSource.ByLink {
        val payload = read(link)
        if (saved != null && saved.isFurtherOnThan(payload)) throw LinkError.Stale
        val state = payload.state
        val seat =
            if (state.status.isOver) {
                payload.lastMove?.playedBy?.opponent ?: state.activePlayer.opponent
            } else {
                state.activePlayer
            }
        return MatchSource.ByLink(link.trim(), localPlayer = seat)
    }

    /**
     * What a link match route opens. The saved match wins when it is the same match at least as
     * far on, as it is when the route is opened again after the process was restarted; dealing
     * the link again would put the board back to where the link left it.
     *
     * When [saved] holds the position just before the link's last move, the match opens there
     * and plays that move out. Without it the move is not seen, only its result.
     *
     * Null when the link cannot be read, which a link that was checked by [open] or made by
     * [invite] on this build never is.
     */
    fun start(
        source: MatchSource.ByLink,
        saved: MatchSnapshot?,
    ): MatchStart? {
        val payload =
            try {
                read(source.link)
            } catch (_: LinkError) {
                return null
            }
        if (saved != null && saved.holdsAtLeast(payload)) return MatchStart(saved.mode, saved.state)

        val lastMove = payload.lastMove
        val before =
            saved?.state?.takeIf {
                lastMove != null &&
                    lastMove.playedBy != source.localPlayer &&
                    lastMove.events.isNotEmpty() &&
                    saved.isLinkMatch(payload.matchId) &&
                    it.history == payload.state.history.dropLast(1)
            }
        return MatchStart(
            mode = MatchMode.ByLink(payload.matchId, source.localPlayer),
            state = payload.state,
            lastMove = lastMove,
            animateFrom = before,
        )
    }

    /**
     * The link for the opponent, or null while there is nothing for them to do: it exists only
     * when it is their turn or the match is over. Built from [state] as the rules decided it,
     * so a link taken mid-animation still carries the whole turn. The final position is
     * shareable too: the opponent should get to see how it ended.
     *
     * @param lastMove the move that produced [state], so the opponent sees it played out.
     */
    fun linkToSend(
        mode: MatchMode,
        state: GameState,
        lastMove: LastMove?,
    ): String? {
        val link = mode as? MatchMode.ByLink ?: return null
        if (!state.status.isOver && state.activePlayer == link.localPlayer) return null
        return MatchLink.url(
            MatchPayload(matchId = link.matchId, moveNumber = state.history.size, state = state, lastMove = lastMove),
        )
    }

    /** This phone's side of a link match standing at [state], for preparing its next turn. */
    fun authority(
        mode: MatchMode.ByLink,
        state: GameState,
    ): MatchAuthority =
        MatchAuthority(
            MatchPayload(matchId = mode.matchId, moveNumber = state.history.size, state = state),
            mode.localPlayer,
        )

    /** @throws LinkError */
    fun read(link: String): MatchPayload =
        try {
            MatchLink.payload(link)
        } catch (error: MatchLinkError) {
            throw LinkError.of(error)
        } catch (error: MatchCodecError) {
            throw LinkError.of(error)
        }

    private fun MatchSnapshot.isLinkMatch(matchId: String): Boolean = (mode as? MatchMode.ByLink)?.matchId == matchId

    private fun MatchSnapshot.holdsAtLeast(payload: MatchPayload): Boolean =
        isLinkMatch(payload.matchId) && state.history.size >= payload.moveNumber

    private fun MatchSnapshot.isFurtherOnThan(payload: MatchPayload): Boolean =
        isLinkMatch(payload.matchId) && state.history.size > payload.moveNumber
}
