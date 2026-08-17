/// The ordered record of everything one move caused. The UI animates these in order
/// and never recomputes rules or scores for itself.
public enum GameEvent: Hashable, Codable, Sendable {
    case moved(pieceID: PieceID, from: Position, to: Position)
    /// A blender ran into another and destroyed it. The attacker takes the square; the
    /// victim is gone until its owner buys it back.
    case blenderDestroyed(pieceID: PieceID, owner: PlayerID, by: PieceID, at: Position)
    /// A destroyed blender bought back onto its spawn.
    case blenderRestored(pieceID: PieceID, owner: PlayerID, at: Position, cost: Int)
    /// A smoothie victim returning on its own, for nothing.
    case blenderRespawned(pieceID: PieceID, owner: PlayerID, at: Position)
    /// A delivered banana reappearing on its spawn.
    case bananaReturned(pieceID: PieceID, to: Position)
    /// `points` is signed: positive when a blender took the other player's fruit,
    /// negative when it took its owner's own.
    case blended(pieceID: PieceID, kind: PieceKind, fruitOwner: PlayerID, at: Position, awardedTo: PlayerID, points: Int)
    case delivered(pieceID: PieceID, kind: PieceKind, owner: PlayerID, at: Position, points: Int)
    case scoreChanged(player: PlayerID, newScore: Int)
    case turnPassed(skipped: PlayerID)
    case turnChanged(active: PlayerID)
    case gameEnded(outcome: Outcome)

    // MARK: Wildcard rules

    /// The surviving pineapple has been promoted after the first one was blended.
    case pineappleTurnedGolden(pieceID: PieceID, owner: PlayerID)
    /// A player completed a Tropical Smoothie and collected the bonus.
    case smoothiePoured(player: PlayerID, points: Int)
    /// A player completed a Tropical Smoothie; the opponent's blenders are blown off
    /// the board and return by themselves at the start of `respawnTurn`.
    ///
    /// **Retained, not emitted.** The smoothie pays points now instead. Kept whole
    /// against the day the blowout comes back — see `RulesEngine.pourSmoothie`.
    case smoothie(player: PlayerID, pieceIDs: [PieceID], respawnTurn: Int)
}

public enum Outcome: Hashable, Codable, Sendable {
    case win(PlayerID, by: WinReason)
    case draw

    /// The ordinary case: whoever had the higher score when the fruit ran out.
    public static func win(_ player: PlayerID) -> Outcome {
        .win(player, by: .score)
    }

    public var winner: PlayerID? {
        if case .win(let winner, _) = self { return winner }
        return nil
    }

    public var reason: WinReason? {
        if case .win(_, let reason) = self { return reason }
        return nil
    }

    public func isWin(for player: PlayerID) -> Bool {
        winner == player
    }
}
