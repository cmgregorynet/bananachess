import Foundation

public struct OnlineLocalPlayer: Sendable, Equatable, Identifiable {
    public let id: String
    public let displayName: String

    public init(id: String, displayName: String) {
        self.id = id
        self.displayName = displayName
    }
}

public enum OnlineMatchStatus: String, Sendable, Codable, Equatable {
    case matchmaking
    case yourTurn
    case waitingForOpponent
    case completed
    case ended
}

public struct OnlineMatchSummary: Sendable, Hashable, Identifiable {
    public let id: String
    public let opponentName: String?
    public let localPlayer: PlayerID
    public let status: OnlineMatchStatus
    public let lastActivity: Date
    public let outcome: Outcome?
    public let payload: MatchPayload?

    public init(
        id: String,
        opponentName: String?,
        localPlayer: PlayerID,
        status: OnlineMatchStatus,
        lastActivity: Date,
        outcome: Outcome? = nil,
        payload: MatchPayload? = nil
    ) {
        self.id = id
        self.opponentName = opponentName
        self.localPlayer = localPlayer
        self.status = status
        self.lastActivity = lastActivity
        self.outcome = outcome
        self.payload = payload
    }
}

public enum OnlineMatchError: Error, Equatable, LocalizedError {
    case notAuthenticated
    case serviceUnavailable
    case cancelledByPlayer
    case matchNotFound
    case outOfTurn
    /// Someone wrote to the match between prepare and submit. Reload and rebuild.
    case conflict(remoteMoveNumber: Int)
    case transportFailure(String)

    public var errorDescription: String? {
        switch self {
        case .notAuthenticated: "Sign in to Game Center to play online."
        case .serviceUnavailable: "Game Center is not available right now."
        case .cancelledByPlayer: "Cancelled."
        case .matchNotFound: "That match is no longer available."
        case .outOfTurn: "It is not your turn."
        case .conflict: "This match moved on while you were away. Reloading."
        case .transportFailure(let detail): detail
        }
    }
}

public enum OnlineMatchUpdate: Sendable, Equatable {
    case matchChanged(id: String)
    case matchEnded(id: String)
    case invitationAccepted(id: String)
}

/// The whole online surface, with no GameKit in sight, so the rules engine stays
/// platform-independent and two simulated clients can play a full match in tests.
public protocol OnlineMatchService: Sendable {
    func authenticateLocalPlayer() async throws -> OnlineLocalPlayer
    func loadMatches() async throws -> [OnlineMatchSummary]
    func loadMatch(id: String) async throws -> OnlineMatchSummary
    /// The rule set is fixed by whoever creates the match and travels in its payload,
    /// so the other device never has to be told separately.
    func startAutomatch(ruleSet: RuleSet) async throws -> OnlineMatchSummary
    func inviteFriend(ruleSet: RuleSet) async throws -> OnlineMatchSummary
    /// Writes the resulting state and hands the turn to the opponent in one operation.
    /// `expectedPredecessor` is the move number this turn was built on; a mismatch is a
    /// conflict, not an overwrite.
    func submit(_ payload: MatchPayload, expectedPredecessor: Int, for matchID: String) async throws
    func finish(_ payload: MatchPayload, outcome: Outcome, for matchID: String) async throws
    func forfeit(matchID: String) async throws
    func matchUpdates() -> AsyncStream<OnlineMatchUpdate>
}
