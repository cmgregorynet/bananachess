import BananaChessEngine
import Foundation

@Observable
@MainActor
final class HomeViewModel {
    enum Route: Hashable {
        case local
        case computer(CPUDifficulty, PlayerID)
        case online
        /// A match arriving from, or leaving on, a link. The whole match travels in the
        /// URL, so there is nothing to look up and nothing to wait for.
        case byLink(matchID: String, state: GameState, localPlayer: PlayerID)
        /// A live iCloud match. The share URL is the invitation; moves arrive on their own.
        case cloud(summary: OnlineMatchSummary)
        /// Matches in progress over iCloud, and the button that starts another. Going via
        /// the lobby is what makes a match survive closing the app: without a list, an
        /// invitation you had already sent was unreachable the moment you left the board.
        case cloudLobby
    }

    var route: Route?
    var linkError: String?

    // Remembered between launches. The keys are the ones the UI tests set as launch
    // arguments, so they are part of the contract, not a detail.
    var difficulty: CPUDifficulty {
        didSet { defaults.set(difficulty.rawValue, forKey: Keys.difficulty) }
    }
    var humanPlaysFirst: Bool {
        didSet { defaults.set(humanPlaysFirst, forKey: Keys.humanPlaysFirst) }
    }
    var usesWildcardRules: Bool {
        didSet { defaults.set(usesWildcardRules, forKey: Keys.wildcardRules) }
    }
    var boardSetup: BoardSetup {
        didSet { defaults.set(boardSetup.rawValue, forKey: Keys.boardSetup) }
    }
    var boardWrap: BoardWrap {
        didSet { defaults.set(boardWrap.rawValue, forKey: Keys.boardWrap) }
    }
    var blenderBattles: Bool {
        didSet { defaults.set(blenderBattles, forKey: Keys.blenderBattles) }
    }

    @ObservationIgnored private let gameCenter: any OnlineMatchService
    @ObservationIgnored private let inbox: CloudKitInbox
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let blendEffects: BlendEffectPolicy
    @ObservationIgnored private var acceptTask: Task<Void, Never>?

    private enum Keys {
        static let difficulty = "lastCPUDifficulty"
        static let humanPlaysFirst = "lastCPUHumanPlaysFirst"
        static let wildcardRules = "wildcardRulesEnabled"
        static let boardSetup = "boardSetup"
        static let boardWrap = "boardWrap"
        static let blenderBattles = "blenderBattles"
    }

    init(
        gameCenter: any OnlineMatchService,
        inbox: CloudKitInbox,
        defaults: UserDefaults = .standard,
        blendEffects: BlendEffectPolicy = .launchDefault
    ) {
        self.gameCenter = gameCenter
        self.inbox = inbox
        self.defaults = defaults
        self.blendEffects = blendEffects

        difficulty = defaults.string(forKey: Keys.difficulty).flatMap(CPUDifficulty.init) ?? .medium
        humanPlaysFirst = defaults.object(forKey: Keys.humanPlaysFirst) as? Bool ?? true
        usesWildcardRules = defaults.bool(forKey: Keys.wildcardRules)
        boardSetup = defaults.string(forKey: Keys.boardSetup).flatMap(BoardSetup.init) ?? .standard
        boardWrap = defaults.string(forKey: Keys.boardWrap).flatMap(BoardWrap.init) ?? .flat
        blenderBattles = defaults.bool(forKey: Keys.blenderBattles)
    }

    deinit {
        acceptTask?.cancel()
    }

    private var options: MatchOptions {
        MatchOptions(
            ruleSet: usesWildcardRules ? .wildcard : .standard,
            boardSetup: boardSetup,
            boardWrap: boardWrap,
            blenderBattles: blenderBattles
        )
    }

    // MARK: - Menu

    func playLocal() {
        route = .local
    }

    func playComputer() {
        route = .computer(difficulty, humanPlaysFirst ? .one : .two)
    }

    func playOnline() {
        route = .online
    }

    /// Live over iCloud when it is available, and a correspondence link when it is not.
    /// The fallback matters: a player with no iCloud account should still be able to
    /// invite someone rather than be told no.
    func invite() {
        guard CloudKitMatchService.isConfigured else {
            startLinkMatch()
            return
        }
        route = .cloudLobby
    }

    /// The invitation *is* the first link, so the match is dealt with the opponent to
    /// move and the inviter sitting second. Tapping Invite by Link therefore lands on a
    /// board with something to send immediately — dealing it the other way round left
    /// nothing to share until you had taken a turn, which is not what the button says.
    ///
    /// It also splits the two advantages: whoever invites picks the board and the rules,
    /// whoever accepts gets the first move, which is worth about 72% of decided matches.
    private func startLinkMatch() {
        route = .byLink(matchID: UUID().uuidString, state: newGame(), localPlayer: .two)
    }

    // MARK: - Arrivals

    func open(_ url: URL) {
        do {
            let payload = try MatchLink.payload(from: url)
            // Whoever opens the link is whoever the match is waiting on.
            route = .byLink(
                matchID: payload.matchID,
                state: payload.state,
                localPlayer: payload.state.activePlayer
            )
        } catch {
            linkError = error.localizedDescription
        }
    }

    var acceptedInvitationID: String? { inbox.acceptedMatchID }

    func openAcceptedInvitation() {
        guard let accepted = inbox.acceptedMatchID else { return }
        inbox.acceptedMatchID = nil
        acceptTask?.cancel()
        acceptTask = Task { [weak self, inbox] in
            do {
                let summary = try await inbox.service.loadMatch(id: accepted)
                guard let self, !Task.isCancelled else { return }
                self.route = .cloud(summary: summary)
            } catch {
                guard let self, !Task.isCancelled else { return }
                self.linkError = error.localizedDescription
            }
        }
    }

    // MARK: - Destinations

    func makeMatchViewModel(for route: Route) -> MatchViewModel? {
        switch route {
        case .local:
            MatchViewModel(mode: .localVersus, state: newGame(), blendEffects: blendEffects)
        case .computer(let difficulty, let humanPlays):
            MatchViewModel(
                mode: .computer(difficulty: difficulty, humanPlays: humanPlays),
                state: newGame(),
                blendEffects: blendEffects
            )
        case .byLink(let matchID, let state, let localPlayer):
            MatchViewModel(
                mode: .byLink(matchID: matchID, localPlayer: localPlayer),
                state: state,
                blendEffects: blendEffects
            )
        case .cloud(let summary):
            MatchViewModel(
                mode: .online(
                    matchID: summary.id,
                    localPlayer: summary.localPlayer,
                    opponentName: summary.opponentName
                ),
                state: summary.payload?.state ?? GameSetup.newGame(),
                onlineService: inbox.service,
                invitationURL: summary.shareURL,
                blendEffects: blendEffects
            )
        case .online, .cloudLobby:
            nil
        }
    }

    func makeLobbyViewModel(for route: Route) -> OnlineLobbyViewModel? {
        switch route {
        case .online: OnlineLobbyViewModel(service: gameCenter, options: options)
        case .cloudLobby: OnlineLobbyViewModel(service: inbox.service, options: options)
        default: nil
        }
    }

    private func newGame() -> GameState {
        GameSetup.newGame(
            startingPlayer: .one,
            ruleSet: options.ruleSet,
            boardSetup: options.boardSetup,
            boardWrap: options.boardWrap,
            blenderBattles: options.blenderBattles
        )
    }
}

extension BlendEffectPolicy {
    /// One blend in three throws pulp at the screen. `-alwaysSplatter` pins it on for
    /// development and screenshots; like Undo, it does not exist in a release build.
    static var launchDefault: BlendEffectPolicy {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("-alwaysSplatter") ? .always : .standard
        #else
        .standard
        #endif
    }
}
