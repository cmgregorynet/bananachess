extension GameState {
    /// Replays one event onto a state, for presentation only. The UI walks a
    /// transition's events in order through this so the board animates each beat —
    /// the slide, then the strike, then the blend — instead of snapping to the
    /// finished position. It deliberately does not touch turn number or history:
    /// those come from the authoritative state the engine already returned.
    public func applying(_ event: GameEvent) -> GameState {
        var next = self
        switch event {
        case .moved(let pieceID, _, let to):
            next.setPosition(to, for: pieceID)
        case .blenderDestroyed(let pieceID, _, _, _):
            next.setPosition(nil, for: pieceID)
        case .blenderRestored(let pieceID, _, let at, _):
            next.setPosition(at, for: pieceID)
            next.setRespawnTurn(nil, for: pieceID)
        case .bananaReturned(let pieceID, let to):
            next.setPosition(to, for: pieceID)
        case .smoothie(_, let pieceIDs, let respawnTurn):
            for pieceID in pieceIDs {
                next.setPosition(nil, for: pieceID)
                next.setRespawnTurn(respawnTurn, for: pieceID)
            }
        case .blenderRespawned(let pieceID, _, let at):
            next.setPosition(at, for: pieceID)
            next.setRespawnTurn(nil, for: pieceID)
        case .blended(let pieceID, _, _, _, _, _):
            next.remove(pieceID)
        case .delivered(let pieceID, let kind, _, _, _):
            // A wildcard banana is not consumed by delivery — it steps off the board and
            // waits for its spawn to clear.
            if next.ruleSet.usesWildcards, kind == .banana {
                next.setPosition(nil, for: pieceID)
            } else {
                next.remove(pieceID)
            }
        case .smoothiePoured:
            // The points arrive in the `scoreChanged` that follows it; this event exists
            // for the UI to celebrate, and moves nothing on the board.
            break
        case .scoreChanged(let player, let newScore):
            next.scores[player] = newScore
        case .turnChanged(let active):
            next.activePlayer = active
        case .gameEnded(let outcome):
            next.status = .ended(outcome)
        case .pineappleTurnedGolden(let pieceID, let owner):
            next.wildcards.goldenPineapple[owner] = pieceID
            next.wildcards.firstPineappleBlended[owner] = true
        case .turnPassed:
            break
        }
        return next
    }
}
