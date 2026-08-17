import BananaChessEngine
import Foundation
import SwiftUI

@Observable
@MainActor
final class OnlineLobbyViewModel {
    private(set) var localPlayerName: String?
    private(set) var matches: [OnlineMatchSummary] = []
    private(set) var isLoading = false
    private(set) var isAuthenticating = false
    var error: String?

    @ObservationIgnored private let service: any OnlineMatchService
    @ObservationIgnored private var loadTask: Task<Void, Never>?
    @ObservationIgnored private var actionTask: Task<Void, Never>?
    @ObservationIgnored private var updatesTask: Task<Void, Never>?

    let ruleSet: RuleSet
    let boardSetup: BoardSetup

    init(
        service: any OnlineMatchService,
        ruleSet: RuleSet = .standard,
        boardSetup: BoardSetup = .standard
    ) {
        self.service = service
        self.ruleSet = ruleSet
        self.boardSetup = boardSetup
    }

    deinit {
        loadTask?.cancel()
        actionTask?.cancel()
        updatesTask?.cancel()
    }

    var isSignedIn: Bool { localPlayerName != nil }

    var yourTurnMatches: [OnlineMatchSummary] { matches.filter { $0.status == .yourTurn } }
    var waitingMatches: [OnlineMatchSummary] {
        matches.filter { $0.status == .waitingForOpponent || $0.status == .matchmaking }
    }
    var finishedMatches: [OnlineMatchSummary] {
        matches.filter { $0.status == .completed || $0.status == .ended }
    }

    func start() {
        guard !isAuthenticating, !isSignedIn else { return }
        isAuthenticating = true
        loadTask?.cancel()
        loadTask = Task { [weak self] in
            guard let self else { return }
            do {
                let player = try await service.authenticateLocalPlayer()
                guard !Task.isCancelled else { return }
                localPlayerName = player.displayName
                isAuthenticating = false
                observeUpdates()
                await refresh()
            } catch {
                guard !Task.isCancelled else { return }
                isAuthenticating = false
                self.error = error.localizedDescription
            }
        }
    }

    func refresh() async {
        guard isSignedIn else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            matches = try await service.loadMatches()
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func observeUpdates() {
        updatesTask?.cancel()
        updatesTask = Task { [weak self] in
            guard let self else { return }
            for await _ in service.matchUpdates() {
                guard !Task.isCancelled else { return }
                await refresh()
            }
        }
    }

    /// Both entry points return a match to open; failure leaves the lobby untouched.
    func startAutomatch(then open: @escaping (OnlineMatchSummary) -> Void) {
        let (ruleSet, boardSetup) = (ruleSet, boardSetup)
        run(
            { try await self.service.startAutomatch(ruleSet: ruleSet, boardSetup: boardSetup) },
            then: open
        )
    }

    func inviteFriend(then open: @escaping (OnlineMatchSummary) -> Void) {
        let (ruleSet, boardSetup) = (ruleSet, boardSetup)
        run(
            { try await self.service.inviteFriend(ruleSet: ruleSet, boardSetup: boardSetup) },
            then: open
        )
    }

    private func run(
        _ operation: @escaping @Sendable () async throws -> OnlineMatchSummary,
        then open: @escaping (OnlineMatchSummary) -> Void
    ) {
        actionTask?.cancel()
        actionTask = Task { [weak self] in
            guard let self else { return }
            do {
                let summary = try await operation()
                guard !Task.isCancelled else { return }
                await refresh()
                open(summary)
            } catch OnlineMatchError.cancelledByPlayer {
                // The player backed out of Game Center's own sheet; not an error.
            } catch {
                guard !Task.isCancelled else { return }
                self.error = error.localizedDescription
            }
        }
    }

    func mode(for summary: OnlineMatchSummary) -> MatchMode {
        .online(
            matchID: summary.id,
            localPlayer: summary.localPlayer,
            opponentName: summary.opponentName
        )
    }

    func makeMatchViewModel(for summary: OnlineMatchSummary) -> MatchViewModel {
        MatchViewModel(
            mode: mode(for: summary),
            state: summary.payload?.state ?? GameSetup.newGame(),
            onlineService: service
        )
    }
}
