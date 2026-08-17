import BananaChessEngine
import Foundation
import SwiftUI

@Observable
@MainActor
final class MatchViewModel {
    // Observed UI state
    /// What the board is showing right now. During a move this walks the transition's
    /// events one at a time; between moves it equals the authoritative state.
    private(set) var displayState: GameState
    private(set) var authoritativeState: GameState
    private(set) var selectedPieceID: PieceID?
    private(set) var legalDestinations: Set<Position> = []
    private(set) var isResolving = false
    private(set) var isOpponentThinking = false
    private(set) var flash: BoardFlash?
    private(set) var blendBursts: [BlendBurst] = []
    private(set) var splatter: Splatter?
    var pendingSelfBlend: PendingSelfBlend?

    /// A self-blend the player has aimed but not yet agreed to pay for.
    struct PendingSelfBlend: Equatable, Identifiable {
        let id = UUID()
        let move: Move
        let kind: PieceKind
        let blenderama: BlenderamaStanding

        /// Where a self-blend sits relative to Blenderama, which is the only reason to
        /// make one that costs this much. Spelled out at the moment of the decision:
        /// blending your own fruit with the run already dead is pure loss, and a player
        /// should never discover that only when the match ends.
        enum BlenderamaStanding: Equatable {
            case notPlaying
            case completesIt
            case counts(soFar: Int, target: Int)
            case alreadyOff
        }

        var prompt: String { "Blend your own \(kind.rawValue)?" }

        var detail: String {
            let cost = "It leaves the board and costs you "
                + "\(kind.baseValue) point\(kind.baseValue == 1 ? "" : "s")."
            switch blenderama {
            case .notPlaying:
                return cost
            case .completesIt:
                return cost + " It is also your last one — this wins the match by Blenderama."
            case .counts(let soFar, let target):
                return cost + " Blenderama: \(soFar + 1) of \(target) once this is done."
            case .alreadyOff:
                return cost
                    + " Blenderama is already off for this match, so this is points down"
                    + " and nothing else."
            }
        }
    }
    private(set) var onlineStatus: OnlineTurnStatus = .idle
    private(set) var errorMessage: String?
    var isShowingRestartConfirmation = false

    let mode: MatchMode

    // Infrastructure
    @ObservationIgnored private var undoStack: [GameState] = []
    @ObservationIgnored private var animationTask: Task<Void, Never>?
    @ObservationIgnored private var cpuTask: Task<Void, Never>?
    @ObservationIgnored private var updatesTask: Task<Void, Never>?
    @ObservationIgnored private var submitTask: Task<Void, Never>?
    @ObservationIgnored private var authority: MatchAuthority?
    @ObservationIgnored private let onlineService: (any OnlineMatchService)?
    @ObservationIgnored private let snapshots: MatchSnapshotStore
    @ObservationIgnored private var pendingTurn: MatchAuthority.PreparedTurn?
    @ObservationIgnored private var reduceMotion = false
    @ObservationIgnored private let blendEffects: BlendEffectPolicy
    /// Advanced once per effect so two blends in one turn never lay out identically.
    @ObservationIgnored private var effectSeed: UInt64 = 0x9E37_79B9_7F4A_7C15

    enum OnlineTurnStatus: Equatable {
        case idle
        case yourTurn
        case submitting
        case waitingForOpponent
        case submissionFailed(String)
        case finished
    }

    /// A short, non-blocking label over the board — the ×2 on a delivery, the wry note
    /// on a self-blend. Never interrupts play.
    struct BoardFlash: Equatable, Identifiable {
        let id = UUID()
        let text: String
        let position: Position
    }

    /// Pulp thrown at the square where a fruit was blended.
    struct BlendBurst: Equatable, Identifiable {
        let id = UUID()
        let kind: PieceKind
        let position: Position
        let seed: UInt64
    }

    /// The occasional splatter that clears the board. Tied to the burst that threw it
    /// so the view can find where on screen it came from.
    struct Splatter: Equatable, Identifiable {
        let id = UUID()
        let burstID: UUID
        let kind: PieceKind
        let seed: UInt64
    }

    init(
        mode: MatchMode,
        state: GameState = GameSetup.newGame(),
        onlineService: (any OnlineMatchService)? = nil,
        snapshots: MatchSnapshotStore = MatchSnapshotStore(),
        blendEffects: BlendEffectPolicy = .standard
    ) {
        self.mode = mode
        self.displayState = state
        self.authoritativeState = state
        self.onlineService = onlineService
        self.snapshots = snapshots
        self.blendEffects = blendEffects

        if case .online(let matchID, let localPlayer, _) = mode {
            authority = MatchAuthority(
                payload: MatchPayload(matchID: matchID, moveNumber: state.history.count, state: state),
                localPlayer: localPlayer
            )
            onlineStatus = state.activePlayer == localPlayer ? .yourTurn : .waitingForOpponent
        }
    }

    deinit {
        animationTask?.cancel()
        cpuTask?.cancel()
        updatesTask?.cancel()
        submitTask?.cancel()
    }

    // MARK: - Board geometry

    var bottomPlayer: PlayerID { mode.bottomPlayer }
    var topPlayer: PlayerID { mode.bottomPlayer.opponent }

    func seat(for player: PlayerID) -> PlayerSeat {
        player == bottomPlayer ? .bottom : .top
    }

    /// The board is rotated a half turn when the bottom seat belongs to player two, so
    /// each device always looks at the game from its own player's side.
    var isRotated: Bool { bottomPlayer == .two }

    // MARK: - Lifecycle

    func onAppear(reduceMotion: Bool) {
        self.reduceMotion = reduceMotion
        startCPUTurnIfNeeded()
        observeOnlineUpdates()
    }

    // MARK: - Interaction

    var isInputLocked: Bool {
        isResolving
            || isOpponentThinking
            || authoritativeState.status.isOver
            || !mode.canControl(authoritativeState.activePlayer)
            || onlineStatus == .submitting
    }

    func tap(_ position: Position) {
        guard !isInputLocked else { return }

        // A legal destination wins over re-selection. Since a blender may now land on
        // its owner's own fruit, those squares are both "one of my pieces" and "a
        // square I can move to", and treating them as selection would make self-blending
        // unreachable. To pick that fruit up instead, deselect first.
        if let selectedPieceID,
           legalDestinations.contains(position),
           let origin = displayState.piece(with: selectedPieceID)?.position {
            let move = Move(pieceID: selectedPieceID, origin: origin, destination: position)
            commitOrConfirm(move)
            return
        }

        // An empty spawn where a blender can be bought back. Handled here, on the
        // square itself, rather than by a second tap target layered over it: two
        // overlapping gestures on one square meant the purchase never fired.
        if let lost = restorableBlender(at: position) {
            restoreBlender(lost, at: position)
            return
        }

        guard let piece = displayState.piece(at: position), piece.owner == displayState.activePlayer
        else { return }

        if selectedPieceID == piece.id {
            clearSelection()
        } else {
            select(piece.id)
        }
    }

    /// Blending your own fruit costs points and cannot be taken back, so it asks first.
    /// Every other move commits on the tap, exactly as before.
    private func commitOrConfirm(_ move: Move) {
        guard let mover = displayState.piece(with: move.pieceID),
              let target = displayState.piece(at: move.destination),
              target.isFruit,
              target.owner == mover.owner
        else {
            clearSelection()
            commit(move)
            return
        }
        pendingSelfBlend = PendingSelfBlend(
            move: move,
            kind: target.kind,
            blenderama: blenderamaStanding(for: mover.owner)
        )
    }

    private func blenderamaStanding(
        for player: PlayerID
    ) -> PendingSelfBlend.BlenderamaStanding {
        guard displayState.ruleSet.usesWildcards else { return .notPlaying }
        guard !displayState.wildcards.blenderamaCountered[player] else { return .alreadyOff }
        let soFar = displayState.wildcards.ownFruitBlended[player]
        // Every fruit must go through your own blender, so if any has left the board any
        // other way the count can never reach the target.
        guard soFar + displayState.fruit(ownedBy: player).count >= GameSetup.fruitCount else {
            return .alreadyOff
        }
        if soFar + 1 >= GameSetup.fruitCount { return .completesIt }
        return .counts(soFar: soFar, target: GameSetup.fruitCount)
    }

    func confirmSelfBlend() {
        guard let pending = pendingSelfBlend else { return }
        pendingSelfBlend = nil
        clearSelection()
        commit(pending.move)
    }

    func cancelSelfBlend() {
        pendingSelfBlend = nil
    }

    func select(_ pieceID: PieceID) {
        selectedPieceID = pieceID
        legalDestinations = Set(RulesEngine.legalMoves(for: pieceID, in: authoritativeState).map(\.destination))
        Haptics.selection()
    }

    func clearSelection() {
        selectedPieceID = nil
        legalDestinations = []
    }

    // MARK: - Blender combat

    /// The destroyed blender that tapping this spawn would buy back, if any. A blender
    /// may come back on either of its owner's spawns, so the square decides which
    /// purchase is on offer, not the piece.
    func restorableBlender(at square: Position) -> PieceID? {
        guard !isInputLocked else { return nil }
        for action in RulesEngine.legalActions(in: authoritativeState) {
            if case .restoreBlender(let pieceID, let spawn) = action, spawn == square {
                return pieceID
            }
        }
        return nil
    }

    func restoreBlender(_ pieceID: PieceID, at square: Position) {
        clearSelection()
        commit(.restoreBlender(pieceID: pieceID, at: square))
    }

    /// Spawn squares that currently show a buy-back offer.
    var purchasableSpawns: [Position] {
        RulesEngine.legalActions(in: displayState).compactMap { action in
            if case .restoreBlender(_, let square) = action { return square }
            return nil
        }
    }


    // MARK: - Committing a move

    private func commit(_ move: Move) {
        commit(.move(move))
    }

    private func commit(_ action: GameAction) {
        guard let transition = try? RulesEngine.apply(action, to: authoritativeState) else { return }

        if mode.isOnline {
            submitOnline(action)
            return
        }

        undoStack.append(authoritativeState)
        authoritativeState = transition.state
        animate(transition.events)
    }

    /// Walks the engine's events in order, mutating only the display state. The rules
    /// and the scores were already decided; this just performs them.
    private func animate(_ events: [GameEvent]) {
        isResolving = true
        animationTask?.cancel()
        animationTask = Task { [weak self] in
            guard let self else { return }
            for event in events {
                if Task.isCancelled { break }
                self.spawnEffects(for: event)
                withAnimation(self.animation(for: event)) {
                    self.displayState = self.displayState.applying(event)
                    self.flash = self.boardFlash(for: event)
                }
                Haptics.play(for: event)
                try? await Task.sleep(for: self.beat(for: event))
            }
            guard !Task.isCancelled else { return }
            self.displayState = self.authoritativeState
            self.isResolving = false
            withAnimation(.easeOut(duration: 0.4)) { self.flash = nil }
            self.persist()
            self.startCPUTurnIfNeeded()
        }
    }

    private func animation(for event: GameEvent) -> Animation? {
        guard !reduceMotion else { return .easeInOut(duration: 0.12) }
        switch event {
        case .moved: return .spring(response: 0.34, dampingFraction: 0.78)
        case .blenderRestored, .bananaReturned, .blenderRespawned:
            return .spring(response: 0.5, dampingFraction: 0.62)
        case .blenderDestroyed, .smoothie: return .easeOut(duration: 0.22)
        case .blended, .delivered: return .easeOut(duration: 0.28)
        default: return .easeInOut(duration: 0.2)
        }
    }

    private func beat(for event: GameEvent) -> Duration {
        if reduceMotion { return .milliseconds(90) }
        switch event {
        case .moved: return .milliseconds(280)
        // Losing a blender to combat is permanent, so it gets a beat of its own.
        case .blenderDestroyed: return .milliseconds(480)
        case .blenderRestored, .bananaReturned, .blenderRespawned: return .milliseconds(380)
        // The two big wildcard moments get a beat to land before play carries on.
        case .pineappleTurnedGolden: return .milliseconds(620)
        case .smoothie, .smoothiePoured: return .milliseconds(620)
        // Long enough for the pulp to read; the tail of the burst is allowed to overrun
        // into the next event rather than holding up the turn.
        case .blended: return .milliseconds(420)
        case .delivered: return .milliseconds(320)
        case .scoreChanged: return .milliseconds(120)
        default: return .milliseconds(60)
        }
    }

    // MARK: - Blend effects

    /// Decorative only. The roll happens here, on the device watching, and never
    /// touches the rules engine or the online payload — two devices replaying the same
    /// match may splatter differently and both are correct.
    private func spawnEffects(for event: GameEvent) {
        switch event {
        case .blended(_, let kind, _, let square, _, _):
            let burst = BlendBurst(kind: kind, position: square, seed: nextEffectSeed())
            blendBursts.append(burst)

            if blendEffects.roll() < blendEffects.splatterChance {
                splatter = Splatter(burstID: burst.id, kind: kind, seed: nextEffectSeed())
            }

        case .blenderDestroyed(_, _, _, let square):
            // A blender coming apart throws glass, not pulp — and never splatters,
            // which stays the fruit's own signature.
            blendBursts.append(BlendBurst(kind: .blender, position: square, seed: nextEffectSeed()))

        case .smoothie(_, let pieceIDs, _):
            // A burst wherever each blender was standing when it went up.
            for pieceID in pieceIDs {
                guard let square = displayState.piece(with: pieceID)?.position else { continue }
                blendBursts.append(BlendBurst(kind: .blender, position: square, seed: nextEffectSeed()))
            }

        default:
            break
        }
    }

    private func nextEffectSeed() -> UInt64 {
        effectSeed = effectSeed &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
        return effectSeed
    }

    func retireBurst(_ id: UUID) {
        blendBursts.removeAll { $0.id == id }
    }

    func retireSplatter(_ id: UUID) {
        guard splatter?.id == id else { return }
        splatter = nil
    }

    private func clearEffects() {
        blendBursts.removeAll()
        splatter = nil
        flash = nil
    }

    private func boardFlash(for event: GameEvent) -> BoardFlash? {
        switch event {
        case .delivered(_, _, _, let square, let points):
            return BoardFlash(text: "×2  +\(points)", position: square)
        case .blended(_, _, let fruitOwner, let square, let awardedTo, let points):
            guard fruitOwner == awardedTo else {
                return BoardFlash(text: "+\(points)", position: square)
            }
            // A self-blend: the price is the point of the move, so say it plainly.
            return BoardFlash(text: "Own goal!  −\(abs(points))", position: square)
        case .blenderDestroyed(_, _, _, let square):
            return BoardFlash(text: "Blender destroyed", position: square)
        case .blenderRestored(_, _, let at, let cost):
            return BoardFlash(text: "Blender back · −\(cost)", position: at)
        case .blenderRespawned(_, _, let at):
            return BoardFlash(text: "Blender back", position: at)
        case .pineappleTurnedGolden(let pieceID, _):
            guard let square = displayState.piece(with: pieceID)?.position else { return flash }
            return BoardFlash(text: "Golden Pineapple!", position: square)
        case .smoothie(_, let pieceIDs, _):
            // Point at one of the blenders it took out.
            let square = pieceIDs.compactMap { displayState.piece(with: $0)?.position }.first
            return BoardFlash(text: "Tropical Smoothie!", position: square ?? Position(row: 0, column: 0))
        default:
            return flash
        }
    }

    // MARK: - CPU

    private func startCPUTurnIfNeeded() {
        guard case .computer(let difficulty, let humanPlays) = mode else { return }
        let cpuPlayer = humanPlays.opponent
        guard !authoritativeState.status.isOver,
              authoritativeState.activePlayer == cpuPlayer,
              !isResolving else { return }

        isOpponentThinking = true
        let snapshot = authoritativeState
        let cpu = CPUPlayer(difficulty: difficulty)

        cpuTask?.cancel()
        cpuTask = Task { [weak self] in
            // The search itself runs off the main actor against an immutable snapshot;
            // only the chosen Move comes back, and it is validated by the engine like
            // any other move.
            let action = await Task.detached(priority: .userInitiated) {
                cpu.selectAction(in: snapshot)
            }.value
            // A little thinking time even when the answer was instant, so the turn
            // reads as a turn rather than a flicker.
            try? await Task.sleep(for: .milliseconds(320))

            guard let self, !Task.isCancelled else { return }
            self.isOpponentThinking = false
            guard let action else { return }
            self.undoStack.append(self.authoritativeState)
            guard let transition = try? RulesEngine.apply(action, to: self.authoritativeState) else { return }
            self.authoritativeState = transition.state
            self.animate(transition.events)
        }
    }

    // MARK: - Online

    private func observeOnlineUpdates() {
        guard case .online(let matchID, _, _) = mode, let onlineService else { return }
        updatesTask?.cancel()
        updatesTask = Task { [weak self] in
            for await update in onlineService.matchUpdates() {
                guard let self, !Task.isCancelled else { return }
                switch update {
                case .matchChanged(let id), .matchEnded(let id), .invitationAccepted(let id):
                    guard id == matchID else { continue }
                    await self.refreshFromServer()
                }
            }
        }
    }

    /// Correctness comes from refreshing, not from notifications: this runs on every
    /// activation and whenever the match screen appears.
    func refreshFromServer() async {
        guard case .online(let matchID, _, _) = mode,
              let onlineService,
              var authority else { return }
        do {
            let summary = try await onlineService.loadMatch(id: matchID)
            guard let payload = summary.payload else { return }
            // adopt() refuses anything older than what is already stored, so a cached
            // or replayed payload can never walk the match backwards.
            guard (try? authority.adopt(payload)) != nil else { return }
            self.authority = authority
            applyRemote(payload)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func applyRemote(_ payload: MatchPayload) {
        let previous = displayState
        authoritativeState = payload.state

        if let lastMove = payload.lastMove,
           lastMove.playedBy != mode.bottomPlayer,
           !lastMove.events.isEmpty {
            // Animate the opponent's move from the events they sent, rather than
            // snapping the board to a new arrangement.
            displayState = previous
            animate(lastMove.events)
        } else {
            displayState = payload.state
        }

        onlineStatus = payload.state.status.isOver
            ? .finished
            : (payload.state.activePlayer == mode.bottomPlayer ? .yourTurn : .waitingForOpponent)
    }

    private func submitOnline(_ action: GameAction) {
        guard let authority, let turn = try? authority.prepareTurn(action) else { return }

        pendingTurn = turn
        // Show the move immediately. Local authority has not advanced, so this is still
        // fully reversible until Game Center confirms the write.
        authoritativeState = turn.payload.state
        animate(turn.events)
        send(turn)
    }

    func retrySubmission() {
        guard let turn = pendingTurn else { return }
        send(turn)
    }

    /// One submission path for both the first attempt and every retry. The payload is
    /// byte-identical each time and local authority only advances once, so a retry
    /// after an ambiguous failure can never score the same move twice.
    private func send(_ turn: MatchAuthority.PreparedTurn) {
        guard case .online(let matchID, _, _) = mode, let onlineService else { return }
        onlineStatus = .submitting

        submitTask?.cancel()
        submitTask = Task { [weak self] in
            do {
                try await onlineService.submit(
                    turn.payload,
                    expectedPredecessor: turn.expectedPredecessor,
                    for: matchID
                )
                if case .ended(let outcome) = turn.payload.state.status {
                    try await onlineService.finish(turn.payload, outcome: outcome, for: matchID)
                }
                guard let self, !Task.isCancelled else { return }
                self.confirm(turn)
            } catch {
                guard let self, !Task.isCancelled else { return }
                self.onlineStatus = .submissionFailed(error.localizedDescription)
            }
        }
    }

    private func confirm(_ turn: MatchAuthority.PreparedTurn) {
        guard var authority else { return }
        // A duplicate commit throws rather than double-applying; treat it as already done.
        try? authority.commit(turn)
        self.authority = authority
        pendingTurn = nil
        onlineStatus = authority.state.status.isOver ? .finished : .waitingForOpponent
    }

    /// Rolls the board back to the last state Game Center confirmed.
    func cancelSubmission() {
        guard let authority else { return }
        pendingTurn = nil
        authoritativeState = authority.state
        displayState = authority.state
        onlineStatus = authority.isLocalTurn ? .yourTurn : .waitingForOpponent
    }

    func forfeitOnlineMatch() {
        guard case .online(let matchID, _, _) = mode, let onlineService else { return }
        submitTask?.cancel()
        submitTask = Task { [weak self] in
            do {
                try await onlineService.forfeit(matchID: matchID)
                guard let self, !Task.isCancelled else { return }
                self.onlineStatus = .finished
            } catch {
                guard let self, !Task.isCancelled else { return }
                self.errorMessage = error.localizedDescription
            }
        }
    }

    // MARK: - Match controls

    func restart() {
        animationTask?.cancel()
        cpuTask?.cancel()
        // A restart is the same match again, so it keeps the rule set and the board mode
        // it started under. On a random board that means a *new* scatter rather than the
        // same one again: the point of the mode is a board you have not seen.
        let fresh = GameSetup.newGame(
            ruleSet: authoritativeState.ruleSet,
            boardSetup: authoritativeState.boardSetup
        )
        undoStack.removeAll()
        authoritativeState = fresh
        displayState = fresh
        isResolving = false
        isOpponentThinking = false
        clearEffects()
        clearSelection()
        persist()
        startCPUTurnIfNeeded()
    }

    var canUndo: Bool { !undoStack.isEmpty && !isResolving && !mode.isOnline }

    /// Development affordance only — never shown in a release build.
    func undo() {
        guard canUndo, let previous = undoStack.popLast() else { return }
        animationTask?.cancel()
        cpuTask?.cancel()
        isOpponentThinking = false
        isResolving = false
        authoritativeState = previous
        displayState = previous
        clearEffects()
        clearSelection()
    }

    // MARK: - Presentation helpers

    var outcome: Outcome? {
        if case .ended(let outcome) = displayState.status { return outcome }
        return nil
    }

    var resultText: String? {
        guard let outcome else { return nil }
        switch outcome {
        case .draw: return "A draw — nothing left to blend."
        case .win(let winner, let reason): return "\(mode.name(for: winner)) \(reason.headline)."
        }
    }

    /// The one-line explanation under the result, for wins that were not about points.
    var resultDetail: String? {
        switch outcome?.reason {
        case .bananaSplit: "Every banana across. The score did not matter."
        case .blenderama: "Every one of their own fruit, through their own blenders."
        default: nil
        }
    }

    var ruleSet: RuleSet { displayState.ruleSet }

    var turnIndicatorText: String {
        if displayState.status.isOver { return "Match over" }
        if isOpponentThinking, let difficulty = mode.difficulty {
            return "\(difficulty.displayName) CPU is thinking…"
        }
        switch onlineStatus {
        case .submitting: return "Sending your move…"
        case .waitingForOpponent: return "Waiting for \(mode.name(for: topPlayer))"
        case .submissionFailed: return "Move not sent"
        default: break
        }
        return "\(mode.name(for: displayState.activePlayer)) to move"
    }

    func isSelectable(_ piece: Piece) -> Bool {
        !isInputLocked && piece.owner == displayState.activePlayer
    }

    // MARK: - Persistence

    private func persist() {
        guard !mode.isOnline else { return }
        let snapshot = MatchSnapshot(mode: mode, state: authoritativeState)
        let store = snapshots
        Task.detached(priority: .utility) {
            await store.save(snapshot)
        }
    }
}
