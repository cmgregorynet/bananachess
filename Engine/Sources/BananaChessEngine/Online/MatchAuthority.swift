import Foundation

public enum MatchAuthorityError: Error, Equatable, LocalizedError {
    case notLocalTurn
    case matchIDMismatch
    case staleMoveNumber(expected: Int, received: Int)
    case duplicateSubmission(moveNumber: Int)
    case matchFinished

    public var errorDescription: String? {
        switch self {
        case .notLocalTurn: "It is not your turn."
        case .matchIDMismatch: "That update belongs to a different match."
        case .staleMoveNumber: "That update is older than the state already stored."
        case .duplicateSubmission: "That turn has already been submitted."
        case .matchFinished: "This match has finished."
        }
    }
}

/// Local mirror of a Game Center match, with the ordering rules that keep two devices
/// from disagreeing. Preparing a turn is pure — it produces the payload to send without
/// changing anything — so a failed network write can be retried with the identical
/// payload and can never score twice.
public struct MatchAuthority: Sendable, Equatable {
    public private(set) var payload: MatchPayload
    public let localPlayer: PlayerID

    public init(payload: MatchPayload, localPlayer: PlayerID) {
        self.payload = payload
        self.localPlayer = localPlayer
    }

    public var state: GameState { payload.state }
    public var moveNumber: Int { payload.moveNumber }

    public var isLocalTurn: Bool {
        !payload.state.status.isOver && payload.state.activePlayer == localPlayer
    }

    public struct PreparedTurn: Sendable, Equatable {
        public let payload: MatchPayload
        public let events: [GameEvent]
        /// The move number this turn expects to already be stored remotely. A mismatch
        /// means someone else wrote in between and the turn must be rebuilt.
        public let expectedPredecessor: Int
    }

    /// Builds — but does not commit — the payload for one local move.
    public func prepareTurn(_ move: Move) throws -> PreparedTurn {
        try prepareTurn(.move(move))
    }

    public func prepareTurn(_ action: GameAction) throws -> PreparedTurn {
        guard !payload.state.status.isOver else { throw MatchAuthorityError.matchFinished }
        guard isLocalTurn else { throw MatchAuthorityError.notLocalTurn }

        let transition = try RulesEngine.apply(action, to: payload.state)
        let next = MatchPayload(
            matchID: payload.matchID,
            moveNumber: payload.moveNumber + 1,
            state: transition.state,
            lastMove: LastMove(action: action, playedBy: localPlayer, events: transition.events)
        )
        return PreparedTurn(
            payload: next,
            events: transition.events,
            expectedPredecessor: payload.moveNumber
        )
    }

    /// Called once Game Center has confirmed the write. Rejects a second commit of the
    /// same turn, which is what a retry after an ambiguous failure looks like.
    public mutating func commit(_ turn: PreparedTurn) throws {
        guard turn.payload.matchID == payload.matchID else { throw MatchAuthorityError.matchIDMismatch }
        guard turn.expectedPredecessor == payload.moveNumber else {
            throw MatchAuthorityError.duplicateSubmission(moveNumber: turn.payload.moveNumber)
        }
        payload = turn.payload
    }

    /// Takes authoritative remote state. Never moves backwards: a cached or replayed
    /// older payload is refused rather than overwriting newer truth.
    public mutating func adopt(_ remote: MatchPayload) throws {
        guard remote.matchID == payload.matchID else { throw MatchAuthorityError.matchIDMismatch }
        guard remote.moveNumber >= payload.moveNumber else {
            throw MatchAuthorityError.staleMoveNumber(
                expected: payload.moveNumber,
                received: remote.moveNumber
            )
        }
        payload = remote
    }
}
