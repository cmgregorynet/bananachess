import BananaChessEngine
import Foundation
import GameKit
import UIKit

/// GameKit turn-based matches behind the engine's `OnlineMatchService`. Game Center is
/// the store and forward; there is no backend of our own. Everything rules-shaped lives
/// in the engine, so this file only translates.
@MainActor
final class GameCenterMatchService: NSObject, OnlineMatchService {
    private let broadcaster = MatchUpdateBroadcaster()
    private var isListening = false
    private var matchmakerContinuation: CheckedContinuation<OnlineMatchSummary, any Error>?
    private var matchmakerViewController: GKTurnBasedMatchmakerViewController?
    /// Used for the placeholder payload of a match Game Center has created but nobody
    /// has moved in yet. The authoritative rule set is whatever the first player writes.
    private var pendingOptions = MatchOptions()

    nonisolated func matchUpdates() -> AsyncStream<OnlineMatchUpdate> {
        let broadcaster = broadcaster
        return AsyncStream { continuation in
            Task { await broadcaster.register(continuation) }
        }
    }

    // MARK: - Authentication

    func authenticateLocalPlayer() async throws -> OnlineLocalPlayer {
        let local = GKLocalPlayer.local
        if local.isAuthenticated {
            startListeningIfNeeded()
            return OnlineLocalPlayer(id: local.gamePlayerID, displayName: local.displayName)
        }

        let authenticated: Bool = try await withCheckedThrowingContinuation { continuation in
            let resume = SingleResume(continuation)
            local.authenticateHandler = { viewController, error in
                if let viewController {
                    // Game Center wants to show its sign-in sheet; the handler fires
                    // again once the player is done with it.
                    Self.present(viewController)
                    return
                }
                if let error {
                    resume.finish(.failure(OnlineMatchError.transportFailure(error.localizedDescription)))
                } else {
                    resume.finish(.success(GKLocalPlayer.local.isAuthenticated))
                }
            }
        }

        guard authenticated else { throw OnlineMatchError.notAuthenticated }
        startListeningIfNeeded()
        return OnlineLocalPlayer(id: local.gamePlayerID, displayName: local.displayName)
    }

    private func startListeningIfNeeded() {
        guard !isListening else { return }
        isListening = true
        GKLocalPlayer.local.register(self)
    }

    // MARK: - Matches

    func loadMatches() async throws -> [OnlineMatchSummary] {
        try requireAuthentication()
        let matches = try await GKTurnBasedMatch.loadMatches()
        return matches
            .map(summary(for:))
            .sorted { $0.lastActivity > $1.lastActivity }
    }

    func loadMatch(id: String) async throws -> OnlineMatchSummary {
        try requireAuthentication()
        return summary(for: try await GKTurnBasedMatch.load(withID: id))
    }

    func startAutomatch(_ options: MatchOptions) async throws -> OnlineMatchSummary {
        try requireAuthentication()
        pendingOptions = options
        let match = try await GKTurnBasedMatch.find(for: Self.matchRequest())
        return summary(for: match)
    }

    /// Presents Game Center's own matchmaker so the player can pick a friend. Current
    /// GameKit does not hand the match back through the delegate — it arrives as a turn
    /// event — so the listener below completes this call.
    func inviteFriend(_ options: MatchOptions) async throws -> OnlineMatchSummary {
        try requireAuthentication()
        pendingOptions = options
        let viewController = GKTurnBasedMatchmakerViewController(matchRequest: Self.matchRequest())
        viewController.turnBasedMatchmakerDelegate = self
        viewController.showExistingMatches = true

        return try await withCheckedThrowingContinuation { continuation in
            matchmakerContinuation = continuation
            matchmakerViewController = viewController
            Self.present(viewController)
        }
    }

    /// A match created through Game Center's own matchmaker arrives as a turn event
    /// rather than a delegate callback, so the listener completes the pending invite.
    private func completeMatchmakingIfPending(matchID: String) async {
        guard matchmakerContinuation != nil,
              let match = try? await GKTurnBasedMatch.load(withID: matchID),
              let continuation = matchmakerContinuation else { return }
        matchmakerContinuation = nil
        matchmakerViewController?.dismiss(animated: true)
        matchmakerViewController = nil
        continuation.resume(returning: summary(for: match))
    }

    func submit(_ payload: MatchPayload, expectedPredecessor: Int, for matchID: String) async throws {
        try requireAuthentication()
        let match = try await GKTurnBasedMatch.load(withID: matchID)
        try verifyLocalTurn(in: match)
        try verifyNoConflict(in: match, expectedPredecessor: expectedPredecessor)

        let data = try MatchCodec.encode(payload)
        let next = match.participants.filter { $0.player?.gamePlayerID != GKLocalPlayer.local.gamePlayerID }
        do {
            // One operation: the resulting state is stored and the turn passes together,
            // so there is no window where the match has our move but not our opponent.
            try await match.endTurn(
                withNextParticipants: next,
                turnTimeout: Self.turnTimeout,
                match: data
            )
        } catch {
            throw OnlineMatchError.transportFailure(error.localizedDescription)
        }
    }

    func finish(_ payload: MatchPayload, outcome: Outcome, for matchID: String) async throws {
        try requireAuthentication()
        let match = try await GKTurnBasedMatch.load(withID: matchID)

        for participant in match.participants {
            let seat = self.seat(of: participant, in: match)
            participant.matchOutcome = switch outcome {
            case .draw: .tied
            case .win(let winner, _): seat == winner ? .won : .lost
            }
        }

        do {
            try await match.endMatchInTurn(withMatch: try MatchCodec.encode(payload))
        } catch {
            throw OnlineMatchError.transportFailure(error.localizedDescription)
        }
    }

    func forfeit(matchID: String) async throws {
        try requireAuthentication()
        let match = try await GKTurnBasedMatch.load(withID: matchID)
        let data = match.matchData ?? Data()

        do {
            if isLocalTurn(in: match) {
                let next = match.participants.filter {
                    $0.player?.gamePlayerID != GKLocalPlayer.local.gamePlayerID
                }
                try await match.participantQuitInTurn(
                    with: .quit,
                    nextParticipants: next,
                    turnTimeout: Self.turnTimeout,
                    match: data
                )
            } else {
                try await match.participantQuitOutOfTurn(with: .quit)
            }
        } catch {
            throw OnlineMatchError.transportFailure(error.localizedDescription)
        }
        await broadcaster.send(.matchEnded(id: matchID))
    }

    // MARK: - Translation

    /// GameKit publishes its turn-timeout constants as mutable globals, which Swift 6
    /// will not let concurrent code touch. Ninety days stands in: long enough that
    /// leaving the app between turns — the whole point of an asynchronous match — never
    /// costs anyone a game.
    private static let turnTimeout: TimeInterval = 90 * 24 * 60 * 60

    private static func matchRequest() -> GKMatchRequest {
        let request = GKMatchRequest()
        request.minPlayers = 2
        request.maxPlayers = 2
        request.defaultNumberOfPlayers = 2
        return request
    }

    private func requireAuthentication() throws {
        guard GKLocalPlayer.local.isAuthenticated else { throw OnlineMatchError.notAuthenticated }
    }

    private func isLocalTurn(in match: GKTurnBasedMatch) -> Bool {
        match.currentParticipant?.player?.gamePlayerID == GKLocalPlayer.local.gamePlayerID
    }

    private func verifyLocalTurn(in match: GKTurnBasedMatch) throws {
        guard isLocalTurn(in: match) else { throw OnlineMatchError.outOfTurn }
    }

    /// Refuses to write over a match that moved on since the turn was built.
    private func verifyNoConflict(in match: GKTurnBasedMatch, expectedPredecessor: Int) throws {
        guard let data = match.matchData, !data.isEmpty else { return }
        guard let remote = try? MatchCodec.decode(data) else { return }
        guard remote.moveNumber == expectedPredecessor else {
            throw OnlineMatchError.conflict(remoteMoveNumber: remote.moveNumber)
        }
    }

    /// Seats follow participant order, which Game Center keeps stable for the life of a
    /// match: whoever created it is player one on both devices.
    private func seat(of participant: GKTurnBasedParticipant, in match: GKTurnBasedMatch) -> PlayerID {
        match.participants.firstIndex(of: participant) == 0 ? .one : .two
    }

    private func localSeat(in match: GKTurnBasedMatch) -> PlayerID {
        let localID = GKLocalPlayer.local.gamePlayerID
        return match.participants.firstIndex { $0.player?.gamePlayerID == localID } == 0 ? .one : .two
    }

    private func summary(for match: GKTurnBasedMatch) -> OnlineMatchSummary {
        let seat = localSeat(in: match)
        let payload: MatchPayload? =
            if let data = match.matchData, !data.isEmpty {
                try? MatchCodec.decode(data)
            } else {
                // A brand new match carries no data until someone moves.
                MatchPayload.newMatch(id: match.matchID, options: pendingOptions)
            }

        let status: OnlineMatchStatus =
            switch match.status {
            case .matching: .matchmaking
            case .ended: match.participants.contains { $0.matchOutcome == .quit } ? .ended : .completed
            default: isLocalTurn(in: match) ? .yourTurn : .waitingForOpponent
            }

        let outcome: Outcome? =
            if case .ended(let result) = payload?.state.status {
                result
            } else if match.status == .ended {
                localOutcome(in: match, seat: seat)
            } else {
                nil
            }

        return OnlineMatchSummary(
            id: match.matchID,
            opponentName: match.participants
                .first { $0.player?.gamePlayerID != GKLocalPlayer.local.gamePlayerID }?
                .player?.displayName,
            localPlayer: seat,
            status: status,
            lastActivity: match.participants.compactMap(\.lastTurnDate).max() ?? match.creationDate,
            outcome: outcome,
            payload: payload
        )
    }

    private func localOutcome(in match: GKTurnBasedMatch, seat: PlayerID) -> Outcome? {
        let localID = GKLocalPlayer.local.gamePlayerID
        guard let local = match.participants.first(where: { $0.player?.gamePlayerID == localID })
        else { return nil }
        switch local.matchOutcome {
        case .won: return .win(seat)
        case .lost, .quit: return .win(seat.opponent)
        case .tied: return .draw
        default: return nil
        }
    }

    private static func present(_ viewController: UIViewController) {
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let root = scene.keyWindow?.rootViewController else { return }
        var presenter = root
        while let presented = presenter.presentedViewController { presenter = presented }
        presenter.present(viewController, animated: true)
    }
}

// MARK: - Game Center listeners

extension GameCenterMatchService: GKLocalPlayerListener {
    nonisolated func player(
        _ player: GKPlayer,
        receivedTurnEventFor match: GKTurnBasedMatch,
        didBecomeActive: Bool
    ) {
        let id = match.matchID
        let broadcaster = broadcaster
        Task { @MainActor in
            await self.completeMatchmakingIfPending(matchID: id)
            await broadcaster.send(.matchChanged(id: id))
        }
    }

    nonisolated func player(_ player: GKPlayer, matchEnded match: GKTurnBasedMatch) {
        let id = match.matchID
        let broadcaster = broadcaster
        Task { await broadcaster.send(.matchEnded(id: id)) }
    }
}

// GameKit's matchmaker delegate predates concurrency annotations but only ever calls
// back on the main thread.
extension GameCenterMatchService: @preconcurrency GKTurnBasedMatchmakerViewControllerDelegate {
    func turnBasedMatchmakerViewControllerWasCancelled(_ viewController: GKTurnBasedMatchmakerViewController) {
        viewController.dismiss(animated: true)
        failMatchmaking(with: OnlineMatchError.cancelledByPlayer)
    }

    func turnBasedMatchmakerViewController(
        _ viewController: GKTurnBasedMatchmakerViewController,
        didFailWithError error: any Error
    ) {
        viewController.dismiss(animated: true)
        failMatchmaking(with: OnlineMatchError.transportFailure(error.localizedDescription))
    }

    private func failMatchmaking(with error: any Error) {
        guard let continuation = matchmakerContinuation else { return }
        matchmakerContinuation = nil
        matchmakerViewController = nil
        continuation.resume(throwing: error)
    }
}

// MARK: - Support

/// Fan-out for match updates: both the lobby and an open match want to hear about the
/// same turn event, and one `AsyncStream` cannot serve two consumers.
actor MatchUpdateBroadcaster {
    private var continuations: [UUID: AsyncStream<OnlineMatchUpdate>.Continuation] = [:]

    func register(_ continuation: AsyncStream<OnlineMatchUpdate>.Continuation) {
        let id = UUID()
        continuations[id] = continuation
        continuation.onTermination = { [weak self] _ in
            Task { await self?.remove(id) }
        }
    }

    func send(_ update: OnlineMatchUpdate) {
        for continuation in continuations.values { continuation.yield(update) }
    }

    private func remove(_ id: UUID) {
        continuations.removeValue(forKey: id)
    }
}

/// `authenticateHandler` outlives the call that installed it and can fire repeatedly;
/// only the first outcome may resume the awaiting call.
private final class SingleResume<T: Sendable>: @unchecked Sendable {
    private var continuation: CheckedContinuation<T, any Error>?

    init(_ continuation: CheckedContinuation<T, any Error>) {
        self.continuation = continuation
    }

    func finish(_ result: sending Result<T, any Error>) {
        guard let continuation else { return }
        self.continuation = nil
        continuation.resume(with: result)
    }
}
