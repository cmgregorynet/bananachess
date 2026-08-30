import BananaChessEngine
import Foundation

enum MatchMode: Hashable, Codable, Sendable {
    case localVersus
    case computer(difficulty: CPUDifficulty, humanPlays: PlayerID)
    case online(matchID: String, localPlayer: PlayerID, opponentName: String?)
    /// Correspondence play by link: no server, no accounts. Each side sends the other a
    /// link containing the whole match, because a payload already is the whole match.
    case byLink(matchID: String, localPlayer: PlayerID)

    /// Which engine player sits at the bottom of the screen. The human always does;
    /// in local pass-and-play, player one does.
    var bottomPlayer: PlayerID {
        switch self {
        case .localVersus: .one
        case .computer(_, let humanPlays): humanPlays
        case .online(_, let localPlayer, _): localPlayer
        case .byLink(_, let localPlayer): localPlayer
        }
    }

    /// Players this device may move. Empty while it is the opponent's turn online.
    func canControl(_ player: PlayerID) -> Bool {
        switch self {
        case .localVersus: true
        case .computer(_, let humanPlays): player == humanPlays
        case .online(_, let localPlayer, _): player == localPlayer
        case .byLink(_, let localPlayer): player == localPlayer
        }
    }

    /// Whether a turn leaves on a link the player has to send by hand.
    var isByLink: Bool {
        if case .byLink = self { return true }
        return false
    }

    var isOnline: Bool {
        if case .online = self { return true }
        return false
    }

    var difficulty: CPUDifficulty? {
        if case .computer(let difficulty, _) = self { return difficulty }
        return nil
    }

    func name(for player: PlayerID) -> String {
        switch self {
        case .localVersus:
            player == .one ? "Player One" : "Player Two"
        case .computer(let difficulty, let humanPlays):
            player == humanPlays ? "You" : "\(difficulty.displayName) CPU"
        case .online(_, let localPlayer, let opponentName):
            player == localPlayer ? "You" : (opponentName ?? "Opponent")
        case .byLink(_, let localPlayer):
            player == localPlayer ? "You" : "Your opponent"
        }
    }
}
