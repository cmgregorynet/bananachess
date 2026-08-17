import Foundation

/// Shared store standing in for Game Center's servers. Two `InMemoryMatchService`
/// instances pointed at one store behave like two devices in one match, which is how
/// the online rules are tested without a network or a sandbox account.
public actor InMemoryMatchStore {
    struct Record {
        var payload: MatchPayload
        var participants: [OnlineLocalPlayer]
        var status: OnlineMatchStatus
        var outcome: Outcome?
        var lastActivity: Date
    }

    private var records: [String: Record] = [:]
    private var continuations: [String: AsyncStream<OnlineMatchUpdate>.Continuation] = [:]
    private var nextMatchNumber = 1
    private var clock = Date(timeIntervalSince1970: 0)

    /// Fails the next `count` submissions with a transport error, so the retry path can
    /// be exercised deterministically.
    private var injectedFailures = 0

    public init() {}

    public func injectSubmissionFailures(_ count: Int) {
        injectedFailures = count
    }

    func advanceClock() -> Date {
        clock = clock.addingTimeInterval(1)
        return clock
    }

    func createMatch(
        host: OnlineLocalPlayer,
        guest: OnlineLocalPlayer,
        ruleSet: RuleSet,
        boardSetup: BoardSetup
    ) -> String {
        let id = "match-\(nextMatchNumber)"
        nextMatchNumber += 1
        records[id] = Record(
            payload: .newMatch(id: id, ruleSet: ruleSet, boardSetup: boardSetup),
            participants: [host, guest],
            status: .yourTurn,
            outcome: nil,
            lastActivity: advanceClock()
        )
        return id
    }

    func record(for id: String) throws -> Record {
        guard let record = records[id] else { throw OnlineMatchError.matchNotFound }
        return record
    }

    func allRecords() -> [(id: String, record: Record)] {
        records.map { (id: $0.key, record: $0.value) }.sorted { $0.id < $1.id }
    }

    func submit(_ payload: MatchPayload, expectedPredecessor: Int, for matchID: String) throws {
        guard var record = records[matchID] else { throw OnlineMatchError.matchNotFound }
        if injectedFailures > 0 {
            injectedFailures -= 1
            throw OnlineMatchError.transportFailure("Injected failure")
        }
        guard record.payload.moveNumber == expectedPredecessor else {
            throw OnlineMatchError.conflict(remoteMoveNumber: record.payload.moveNumber)
        }
        record.payload = payload
        record.lastActivity = advanceClock()
        records[matchID] = record
        broadcast(.matchChanged(id: matchID))
    }

    func finish(_ payload: MatchPayload, outcome: Outcome, for matchID: String) throws {
        guard var record = records[matchID] else { throw OnlineMatchError.matchNotFound }
        record.payload = payload
        record.outcome = outcome
        record.status = .completed
        record.lastActivity = advanceClock()
        records[matchID] = record
        broadcast(.matchEnded(id: matchID))
    }

    func forfeit(matchID: String, by player: PlayerID) throws {
        guard var record = records[matchID] else { throw OnlineMatchError.matchNotFound }
        record.outcome = .win(player.opponent)
        record.status = .ended
        record.lastActivity = advanceClock()
        records[matchID] = record
        broadcast(.matchEnded(id: matchID))
    }

    func register(_ continuation: AsyncStream<OnlineMatchUpdate>.Continuation, for playerID: String) {
        continuations[playerID] = continuation
    }

    private func broadcast(_ update: OnlineMatchUpdate) {
        for continuation in continuations.values {
            continuation.yield(update)
        }
    }
}

/// One simulated device. Also drives SwiftUI previews and the offline-development path.
public struct InMemoryMatchService: OnlineMatchService {
    private let store: InMemoryMatchStore
    private let localPlayer: OnlineLocalPlayer
    private let opponent: OnlineLocalPlayer
    public var isAuthenticationAvailable = true

    public init(store: InMemoryMatchStore, localPlayer: OnlineLocalPlayer, opponent: OnlineLocalPlayer) {
        self.store = store
        self.localPlayer = localPlayer
        self.opponent = opponent
    }

    public func authenticateLocalPlayer() async throws -> OnlineLocalPlayer {
        guard isAuthenticationAvailable else { throw OnlineMatchError.notAuthenticated }
        return localPlayer
    }

    public func loadMatches() async throws -> [OnlineMatchSummary] {
        await store.allRecords().map { summary(id: $0.id, record: $0.record) }
    }

    public func loadMatch(id: String) async throws -> OnlineMatchSummary {
        let record = try await store.record(for: id)
        return summary(id: id, record: record)
    }

    public func startAutomatch(
        ruleSet: RuleSet = .standard,
        boardSetup: BoardSetup = .standard
    ) async throws -> OnlineMatchSummary {
        let id = await store.createMatch(
            host: localPlayer,
            guest: opponent,
            ruleSet: ruleSet,
            boardSetup: boardSetup
        )
        return try await loadMatch(id: id)
    }

    public func inviteFriend(
        ruleSet: RuleSet = .standard,
        boardSetup: BoardSetup = .standard
    ) async throws -> OnlineMatchSummary {
        try await startAutomatch(ruleSet: ruleSet, boardSetup: boardSetup)
    }

    public func submit(_ payload: MatchPayload, expectedPredecessor: Int, for matchID: String) async throws {
        try await store.submit(payload, expectedPredecessor: expectedPredecessor, for: matchID)
    }

    public func finish(_ payload: MatchPayload, outcome: Outcome, for matchID: String) async throws {
        try await store.finish(payload, outcome: outcome, for: matchID)
    }

    public func forfeit(matchID: String) async throws {
        let record = try await store.record(for: matchID)
        try await store.forfeit(matchID: matchID, by: seat(in: record))
    }

    public func matchUpdates() -> AsyncStream<OnlineMatchUpdate> {
        AsyncStream { continuation in
            Task { await store.register(continuation, for: localPlayer.id) }
        }
    }

    // MARK: - Private

    /// Seat assignment is by participant order, which Game Center keeps stable for the
    /// life of a match: the player who created it is always player one.
    private func seat(in record: InMemoryMatchStore.Record) -> PlayerID {
        record.participants.first?.id == localPlayer.id ? .one : .two
    }

    private func summary(id: String, record: InMemoryMatchStore.Record) -> OnlineMatchSummary {
        let seat = seat(in: record)
        let status: OnlineMatchStatus =
            if record.status == .completed || record.status == .ended {
                record.status
            } else if record.payload.state.status.isOver {
                .completed
            } else {
                record.payload.state.activePlayer == seat ? .yourTurn : .waitingForOpponent
            }

        return OnlineMatchSummary(
            id: id,
            opponentName: record.participants.first { $0.id != localPlayer.id }?.displayName,
            localPlayer: seat,
            status: status,
            lastActivity: record.lastActivity,
            outcome: record.outcome ?? {
                if case .ended(let outcome) = record.payload.state.status { return outcome }
                return nil
            }(),
            payload: record.payload
        )
    }
}
